package com.paicli.tui;

import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogBuilder;
import com.googlecode.lanterna.gui2.dialogs.MessageDialogButton;
import com.googlecode.lanterna.gui2.dialogs.ListSelectDialogBuilder;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import com.paicli.tui.pane.CenterPane;
import com.paicli.tui.pane.StatusPane;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lanterna 全屏 TUI 形态的 {@link Renderer} 适配器。
 *
 * <p>设计：本类不拥有 Lanterna 主循环，而是作为现有 {@link LanternaWindow} 的"视图"。
 * 真正的 GUI 主循环由 {@link com.paicli.tui.TuiBootstrap}（或后续 Main）启动。
 * 本类把 {@code Renderer} 方法委托到 {@link CenterPane}/{@link StatusPane} 等 widget。
 *
 * <p>所有 widget 操作必须在 GUI 事件线程执行，本类内部用
 * {@link LanternaWindow#runOnGuiThread} 封送。
 */
public final class LanternaRenderer implements Renderer {

    private final LanternaWindow window;
    private final CenterPane centerPane;
    private final StatusPane statusPane;
    private final WindowBasedTextGUI gui;
    private final PrintStream stream;
    private volatile boolean closed;
    private final LanternaElicitation elicitation;

    public LanternaRenderer(LanternaWindow window) {
        this.window = Objects.requireNonNull(window);
        this.centerPane = window.getRootPane().getCenterPane();
        this.statusPane = window.getRootPane().getStatusPane();
        this.gui = window.getGui();
        this.stream = new PrintStream(new CenterPaneSink(), true, StandardCharsets.UTF_8);
        this.elicitation = new LanternaElicitation(new LanternaElicitation.Dialogs() {
            public boolean available() { return !closed && gui != null && window.isGuiRunning(); }
            public boolean isGuiThread() { return window.isGuiThread(); }
            public void dispatch(Runnable task) { window.runOnGuiThread(task); }
            public Runnable show(String text, LanternaElicitation.Step step, java.util.function.Consumer<String> answer) {
                ElicitationDialog dialog = new ElicitationDialog(text, step, window.getScreen().getTerminalSize(), answer);
                gui.addWindow(dialog);
                return dialog::close;
            }
        }, com.paicli.mcp.elicitation.ElicitationPrompter.desktopBrowser(), java.time.Duration.ofMinutes(5));
    }

    // Deterministic dialog bridge for tests; no terminal is constructed.
    LanternaRenderer(LanternaElicitation elicitation) {
        this.window = null;
        this.centerPane = null;
        this.statusPane = null;
        this.gui = null;
        this.stream = new PrintStream(OutputStream.nullOutputStream());
        this.elicitation = elicitation;
    }

    @Override public java.util.Set<String> supportedElicitationModes() {
        return closed ? java.util.Set.of() : elicitation.supportedModes();
    }

    @Override public com.paicli.mcp.elicitation.ElicitationResponse promptElicitation(
            com.paicli.mcp.elicitation.ElicitationRequest request) {
        return closed ? com.paicli.mcp.elicitation.ElicitationResponse.cancel() : elicitation.prompt(request);
    }

    @Override
    public void start() {
        // GUI 主循环由 LanternaWindow.start() 驱动，本方法 no-op。
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (window != null) window.close();
        } catch (Exception ignored) {
        }
    }

    @Override
    public PrintStream stream() {
        return stream;
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        for (LlmClient.ToolCall tc : toolCalls) {
            String name = tc.function().name();
            String args = tc.function().arguments();
            window.runOnGuiThread(() -> centerPane.appendToolCall(name, args));
        }
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
        StringBuilder sb = new StringBuilder();
        sb.append("📝 ").append(filePath == null ? "(unnamed)" : filePath).append("\n");
        if (before == null) {
            sb.append("(新建文件，").append(after == null ? 0 : after.length()).append(" 字符)");
        } else if (after == null) {
            sb.append("(删除文件)");
        } else {
            sb.append(before.length()).append(" → ").append(after.length()).append(" 字符");
        }
        String text = sb.toString();
        window.runOnGuiThread(() -> centerPane.appendSystemMessage(text));
    }

    @Override
    public void updateStatus(StatusInfo status) {
        // StatusPane 当前没有公开的 setStatus 接口；保留 hook 给后续增强。
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        AtomicReference<ApprovalResult> result = new AtomicReference<>();
        Object lock = new Object();
        window.runOnGuiThread(() -> {
            ApprovalResult r = doShowApprovalDialog(request);
            synchronized (lock) {
                result.set(r);
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            while (result.get() == null) {
                try {
                    lock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ApprovalResult.reject("审批被中断");
                }
            }
        }
        return result.get();
    }

    private ApprovalResult doShowApprovalDialog(ApprovalRequest request) {
        String title = "⚠️ HITL 审批: " + request.toolName();
        String body = request.toDisplayText();
        if (request.permanentAllowProposal() == null) {
            MessageDialogButton button = new MessageDialogBuilder()
                    .setTitle(title)
                    .setText(body + "\n\n[Yes] 批准  [No] 拒绝  [Cancel] 跳过")
                    .addButton(MessageDialogButton.Yes)
                    .addButton(MessageDialogButton.No)
                    .addButton(MessageDialogButton.Cancel)
                    .build()
                    .showDialog(gui);
            if (button == null) return ApprovalResult.skip();
            return switch (button) {
                case Yes -> ApprovalResult.approve();
                case No -> ApprovalResult.reject("Lanterna 模态框拒绝");
                case Cancel -> ApprovalResult.skip();
                default -> ApprovalResult.skip();
            };
        }

        String permanentOption = "永久允许（需再次确认）："
                + ApprovalRequest.scopeDescription(request.permanentAllowProposal());
        ListSelectDialogBuilder<String> approvalChoices = new ListSelectDialogBuilder<String>()
                .setTitle(title)
                .setDescription(body + "\n请选择本次操作的授权方式");
        approvalChoices.addListItem("仅批准本次");
        approvalChoices.addListItem(permanentOption);
        approvalChoices.addListItem("拒绝");
        approvalChoices.addListItem("跳过");
        String selected = approvalChoices.build().showDialog(gui);
        if (selected == null || selected.equals("跳过")) return ApprovalResult.skip();
        if (selected.equals("拒绝")) return ApprovalResult.reject("Lanterna 模态框拒绝");
        if (selected.equals("仅批准本次")) return ApprovalResult.approve();
        if (!selected.equals(permanentOption)) return ApprovalResult.skip();

        String confirmation = "将保存以下永久授权规则：\n"
                + ApprovalRequest.scopeDescription(request.permanentAllowProposal())
                + "\n项目根目录：" + request.permanentAllowProposal().projectRoot()
                + "\n该规则仅对当前项目生效。确认保存吗？";
        MessageDialogButton confirmed = new MessageDialogBuilder()
                .setTitle("二次确认：永久授权")
                .setText(confirmation)
                .addButton(MessageDialogButton.Yes)
                .addButton(MessageDialogButton.No)
                .build()
                .showDialog(gui);
        return confirmed == MessageDialogButton.Yes
                ? ApprovalResult.allowPermanently() : ApprovalResult.skip();
    }

    @Override
    public int openPalette(String title, List<String> items) {
        if (items == null || items.isEmpty()) {
            return -1;
        }
        AtomicReference<Integer> result = new AtomicReference<>();
        Object lock = new Object();
        window.runOnGuiThread(() -> {
            ListSelectDialogBuilder<String> builder = new ListSelectDialogBuilder<String>()
                    .setTitle(title == null ? "选择" : title)
                    .setDescription("方向键 + Enter 选择");
            for (String item : items) {
                builder.addListItem(item);
            }
            String selected = builder.build().showDialog(gui);
            int idx = selected == null ? -1 : items.indexOf(selected);
            synchronized (lock) {
                result.set(idx);
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            while (result.get() == null) {
                try {
                    lock.wait(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
            }
        }
        return result.get();
    }

    /** 返回底层 LanternaWindow，方便 Main 启动 GUI 主循环。 */
    public LanternaWindow window() {
        return window;
    }

    /** 把外部 Map 风格的属性面板（如 /config）一次性渲染到 CenterPane（用于状态展示）。 */
    public void appendKeyValueBlock(String header, Map<String, String> kv) {
        StringBuilder sb = new StringBuilder(header).append("\n");
        for (var e : kv.entrySet()) {
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()).append("\n");
        }
        String text = sb.toString();
        window.runOnGuiThread(() -> centerPane.appendSystemMessage(text));
    }

    /** 把 stream() 的字节缓冲到 CenterPane（按行 flush）。 */
    private final class CenterPaneSink extends OutputStream {
        private final StringBuilder buffer = new StringBuilder();

        @Override
        public synchronized void write(int b) {
            char ch = (char) (b & 0xFF);
            if (ch == '\n') {
                String line = buffer.toString();
                buffer.setLength(0);
                window.runOnGuiThread(() -> centerPane.appendAssistantOutput(line));
            } else {
                buffer.append(ch);
            }
        }

        @Override
        public synchronized void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
                write(b[i] & 0xFF);
            }
        }

        @Override
        public synchronized void flush() {
            if (buffer.length() > 0) {
                String line = buffer.toString();
                buffer.setLength(0);
                window.runOnGuiThread(() -> centerPane.appendAssistantOutput(line));
            }
        }
    }
}
