package com.paicli.render.inline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.cli.InputCoordinator;
import com.paicli.cli.TerminalInputGateway;
import com.paicli.hitl.ApprovalPolicy;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.PermissionRule;
import com.paicli.hitl.ApprovalResult;
import com.paicli.util.AnsiStyle;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import static com.paicli.cli.TerminalInputGateway.Kind.*;
import static com.paicli.hitl.ApprovalRequest.encodeForDisplay;

/**
 * Inline 形态的 HITL 审批提示。
 *
 * <p>旧路径的主菜单选项 {@code y / a / n / s / m} 通过 raw mode 单字符读取，
 * 后续输入回退到 {@code BufferedReader.readLine}。显式注入输入请求桥时，所有选项和子提示
 * 都改由 UI 所有者通过同一个 JLine Reader 按行读取；失败则拒绝审批。
 *
 * <p>有意保持和 {@link com.paicli.render.PlainRenderer#promptApproval} 一致的语义；
 * 只是首选项交互更紧凑。
 */
public final class InlineApprovalPrompter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ATTEMPTS = 5;

    private final PrintStream out;
    private final Terminal terminal;
    private final BufferedReader stdinReader;
    private final TerminalInputGateway gateway;
    private final Duration inputTimeout;

    public InlineApprovalPrompter(PrintStream out, Terminal terminal) {
        this(out, terminal, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)));
    }

    InlineApprovalPrompter(PrintStream out, Terminal terminal, BufferedReader stdinReader) {
        this.out = out;
        this.terminal = terminal;
        this.stdinReader = stdinReader;
        this.gateway = null;
        this.inputTimeout = null;
    }

    /** 显式注入的新路径：worker 不创建或读取 stdin/terminal；仅由 opt-in 的 inline ReAct 运行期绑定。 */
    public InlineApprovalPrompter(PrintStream out, TerminalInputGateway gateway, Duration inputTimeout) {
        this.out = Objects.requireNonNull(out, "out");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.inputTimeout = Objects.requireNonNull(inputTimeout, "inputTimeout");
        if (inputTimeout.isNegative() || inputTimeout.isZero()) throw new IllegalArgumentException("超时必须为正数");
        inputTimeout.toNanos();
        this.terminal = null;
        this.stdinReader = null;
    }

    public ApprovalResult prompt(ApprovalRequest request) {
        if (gateway == null) return prompt(request, null);
        try (var session = gateway.openInteraction(InputCoordinator.Mode.APPROVAL_INPUT)) {
            return prompt(request, session);
        } catch (TerminalInputGateway.InputUnavailableException e) {
            return ApprovalResult.reject(e.getMessage());
        }
    }

    private ApprovalResult prompt(ApprovalRequest request, TerminalInputGateway.Session session) {
        boolean sensitive = request.sensitiveNotice() != null && !request.sensitiveNotice().isBlank();
        out.println();
        out.println(AnsiStyle.heading("⚠️  HITL 审批"));
        out.println(request.toDisplayText());

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String options = sensitive
                    ? "[y] approve  [n] reject  [s] skip  [m] modify"
                    : request.permanentAllowProposal() != null
                    ? "[y] approve  [p] permanent allow (confirm again)  [a] session allow  [n] reject  [s] skip  [m] modify"
                    : "[y] approve  [a] all  [n] reject  [s] skip  [m] modify";
            if (session != null) {
                options += "（输入选项后按 Enter；空 Enter 也会批准）";
            }
            String optionsLine = AnsiStyle.subtle(options);
            if (session == null) {
                out.print("> " + optionsLine + " ");
                out.flush();
            } else {
                // 新路径的输入提示由 JLine 唯一 Reader 显示，避免与原始 PrintStream 提示重复。
                out.println(optionsLine);
                out.flush();
            }

            int key = readSingleKey(session);
            if (key < 0) {
                out.println();
                return ApprovalResult.reject("无法读取按键");
            }
            char ch = Character.toLowerCase((char) key);
            // echo + newline so后续输出不和提示行挤在一起
            out.println(ch == '\n' || ch == '\r' ? "Enter" : encodeForDisplay(String.valueOf(ch)));
            out.flush();

            switch (ch) {
                case 'y', '\n', '\r' -> {
                    return ApprovalResult.approve();
                }
                case 'a' -> {
                    if (sensitive) {
                        out.println(AnsiStyle.subtle("  敏感操作不支持全部放行，请选 y/n/s/m"));
                        continue;
                    }
                    return promptApproveAllScope(request, session);
                }
                case 'p' -> {
                    if (sensitive || request.permanentAllowProposal() == null) {
                        out.println(AnsiStyle.subtle("  当前操作不支持永久授权"));
                        continue;
                    }
                    if (confirmPermanentAllow(request, session)) return ApprovalResult.allowPermanently();
                }
                case 'n' -> {
                    return ApprovalResult.reject(promptForReason(session));
                }
                case 's' -> {
                    return ApprovalResult.skip();
                }
                case 'm' -> {
                    ApprovalResult modified = promptForModifiedArgs(request, session);
                    if (modified != null) {
                        return modified;
                    }
                }
                default -> out.println(AnsiStyle.subtle("  ❓ 未识别按键 '" + encodeForDisplay(String.valueOf(ch)) + "'，重新选择"));
            }
        }
        out.println(AnsiStyle.subtle("  连续多次无效输入，保守处理为拒绝"));
        return ApprovalResult.reject("连续多次无效输入");
    }

    private int readSingleKey(TerminalInputGateway.Session session) {
        if (session != null) {
            String line = session.read(APPROVAL_CHOICE, "审批选项 y/p/a/n/s/m（按 Enter 提交）", inputTimeout);
            // 普通句子即使以 y 开头也不能被截成批准；所有输入都留在审批模式。
            return line.isEmpty() ? '\n' : line.length() == 1 ? line.charAt(0) : '?';
        }
        Attributes original;
        try {
            original = terminal.enterRawMode();
        } catch (Exception e) {
            return -1;
        }
        try {
            terminal.flush();
            return terminal.reader().read();
        } catch (Exception e) {
            return -1;
        } finally {
            try {
                terminal.setAttributes(original);
            } catch (Exception ignored) {
            }
        }
    }

    private String promptForReason(TerminalInputGateway.Session session) {
        if (session == null) {
            out.print("  拒绝原因（可直接回车跳过）: ");
            out.flush();
        }
        try {
            String line = readLine(session, REJECTION_REASON, "拒绝原因（可直接回车跳过）");
            return line == null ? "" : line.trim();
        } catch (IOException e) {
            return "";
        }
    }

    private boolean confirmPermanentAllow(ApprovalRequest request, TerminalInputGateway.Session session) {
        PermissionRule proposal = request.permanentAllowProposal();
        out.println("\n  即将保存永久授权规则：");
        out.println("  " + encodeForDisplay(ApprovalRequest.scopeDescription(proposal)));
        out.println("  项目根目录：" + encodeForDisplay(proposal.projectRoot()));
        out.println("  规则仅适用于当前项目。请准确输入 YES 确认保存：");
        if (session == null) {
            out.print("  > ");
            out.flush();
        }
        try {
            String confirmation = readLine(session, APPROVAL_SCOPE, "输入 YES 确认保存永久授权规则");
            if (confirmation != null && confirmation.trim().equals("YES")) {
                out.println(AnsiStyle.subtle("  已确认永久授权规则"));
                return true;
            }
        } catch (IOException ignored) {
            // Fail closed.
        }
        out.println(AnsiStyle.subtle("  未确认，永久授权未保存"));
        return false;
    }

    private ApprovalResult promptApproveAllScope(ApprovalRequest request, TerminalInputGateway.Session session) {
        String mcpServer = ApprovalPolicy.mcpServerName(request.toolName());
        if (mcpServer == null || mcpServer.isBlank()) {
            out.println(AnsiStyle.subtle("  已批准，后续 " + encodeForDisplay(request.toolName()) + " 自动通过"));
            return ApprovalResult.approveAll();
        }
        out.println("  全部放行范围 [tool/Enter] 仅本工具  [server] 整个 MCP server " + encodeForDisplay(mcpServer));
        if (session == null) {
            out.print("> ");
            out.flush();
        }
        String scope;
        try {
            scope = readLine(session, APPROVAL_SCOPE, "全部放行范围 tool/server");
        } catch (IOException e) {
            scope = "";
        }
        String n = scope == null ? "" : scope.trim().toLowerCase();
        if ("server".equals(n) || "s".equals(n)) {
            out.println(AnsiStyle.subtle("  已批准 server 范围"));
            return ApprovalResult.approveAllByServer();
        }
        out.println(AnsiStyle.subtle("  已批准 tool 范围"));
        return ApprovalResult.approveAll();
    }

    private ApprovalResult promptForModifiedArgs(ApprovalRequest request, TerminalInputGateway.Session session) {
        out.println("  当前参数: " + encodeForDisplay(request.arguments()));
        if (session == null) {
            out.print("  修改后的 JSON（空行 = 保留原参数）: ");
            out.flush();
        }
        String modified;
        try {
            modified = readLine(session, MODIFIED_ARGUMENTS, "修改后的 JSON（空行保留原参数）");
        } catch (IOException e) {
            return null;
        }
        if (modified == null || modified.isBlank()) {
            out.println(AnsiStyle.subtle("  保留原参数"));
            return ApprovalResult.approve();
        }
        String trimmed = modified.trim();
        try {
            JSON.readTree(trimmed);
        } catch (Exception e) {
            out.println(AnsiStyle.subtle("  ❌ 非法 JSON: " + encodeForDisplay(e.getMessage())));
            return null;
        }
        return ApprovalResult.modify(trimmed);
    }

    private String readLine(TerminalInputGateway.Session session, TerminalInputGateway.Kind kind, String prompt)
            throws IOException {
        return session == null ? stdinReader.readLine() : session.read(kind, prompt, inputTimeout);
    }
}
