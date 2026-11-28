package com.haocli.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 依赖方向（PLAN-20261126 甲-8，先用测试守边界，再考虑拆 Maven 模块）：扫描源码里的 import 和全限定名。
 * 规则只描述“不准依赖谁”，新增依赖违反规则时这里会失败，而不是等拆模块时才发现。
 */
class ArchitectureBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java/com/haocli");
    private static final Pattern REFERENCE = Pattern.compile("com\\.haocli\\.([a-z]+)\\.");

    /** 内核：执行协议、事件、工具契约。 */
    private static final List<String> KERNEL_FILES = List.of(
            "agent/AgentLoop.java", "agent/TurnHook.java", "agent/ConversationContext.java",
            "agent/DefaultTurnHooks.java", "agent/AgentBudget.java", "agent/RunLimits.java",
            "agent/event/AgentEvent.java", "agent/event/AgentEventListener.java",
            "tool/Tool.java", "tool/ToolOutput.java", "tool/ToolStatus.java",
            "tool/ToolArguments.java", "tool/ToolContext.java");

    /** 内核不准依赖：界面、入口、外部协议、评测、具体的可选能力。 */
    private static final List<String> KERNEL_FORBIDDEN = List.of(
            "cli", "tui", "render", "headless", "mcp", "eval", "web", "browser", "rag", "lsp",
            "skill", "snapshot", "hitl", "image");

    /** 进程内 SDK 不准依赖界面、终端入口、MCP 和评测。 */
    private static final List<String> SDK_FORBIDDEN = List.of("cli", "tui", "render", "mcp", "eval");

    @Test
    void kernelDoesNotDependOnPeripheralModules() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String file : KERNEL_FILES) {
            violations.addAll(violations(MAIN.resolve(file), KERNEL_FORBIDDEN));
        }
        assertTrue(violations.isEmpty(), "内核依赖了外围模块：\n" + String.join("\n", violations));
    }

    @Test
    void sdkDoesNotNeedTheCliTuiMcpOrEvaluation() throws IOException {
        List<String> violations = violations(MAIN.resolve("agent/HaoRuntime.java"), SDK_FORBIDDEN);
        assertTrue(violations.isEmpty(), "HaoRuntime 依赖了不该依赖的模块：\n" + String.join("\n", violations));
    }

    @Test
    void productCodeNeverDependsOnTheEvaluationPackage() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !MAIN.relativize(p).startsWith("eval")).toList()) {
                violations.addAll(violations(file, List.of("eval")));
            }
        }
        assertTrue(violations.isEmpty(), "产品代码依赖了评测包：\n" + String.join("\n", violations));
    }

    private static List<String> violations(Path file, List<String> forbidden) throws IOException {
        assertTrue(Files.isRegularFile(file), "规则里的文件不存在（改名后要同步这里）: " + file);
        List<String> found = new ArrayList<>();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                continue;
            }
            Matcher m = REFERENCE.matcher(line);
            while (m.find()) {
                if (forbidden.contains(m.group(1))) {
                    found.add(MAIN.relativize(file) + ":" + (i + 1) + " → com.haocli." + m.group(1));
                }
            }
        }
        return found;
    }
}
