package com.haocli.tool;

import com.haocli.runtime.CancellationContext;
import com.haocli.runtime.CancellationToken;
import com.haocli.tool.ToolRegistry.ToolExecutionResult;
import com.haocli.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 甲-1：工具结果带细分状态；successful() 的含义不变（除 SUCCESS 外都是失败）；给模型看的文字不变。 */
class ToolStatusTest {

    @Test
    void oldBooleanConstructorsMapToSuccessOrFailed() {
        assertEquals(ToolStatus.SUCCESS, ToolOutput.text("ok").status());
        assertEquals(ToolStatus.FAILED, ToolOutput.failure("bad").status());
        assertEquals(ToolStatus.SUCCESS, new ToolOutput("ok", List.of(), true, List.of()).status());
        assertFalse(ToolOutput.denied("no").successful());
        assertFalse(ToolOutput.skipped("no").successful());
        assertFalse(ToolOutput.unknown("?").successful());
        assertTrue(ToolOutput.text("ok").successful());

        ToolOutput withUrls = ToolOutput.discovered("found", List.of("https://example.com"));
        assertEquals(List.of(), withUrls.withStatus(ToolStatus.FAILED).discoveredUrls(), "失败结果不能授予网址");
        assertEquals(List.of("https://example.com"), withUrls.withText("x").discoveredUrls());
    }

    @Test
    void legacyTextPrefixesMapToDetailedStatus() {
        assertEquals(ToolStatus.SUCCESS, ToolRegistry.legacyStatus("文件内容:\nhello"));
        assertEquals(ToolStatus.DENIED, ToolRegistry.legacyStatus("🛡️ 策略拒绝: 路径越界"));
        assertEquals(ToolStatus.DENIED, ToolRegistry.legacyStatus("[HITL] 操作已被拒绝：不需要"));
        assertEquals(ToolStatus.DENIED, ToolRegistry.legacyStatus("[AUTO] 自动审查未放行：危险"));
        assertEquals(ToolStatus.SKIPPED, ToolRegistry.legacyStatus("[HITL] 操作已被跳过"));
        assertEquals(ToolStatus.CANCELLED, ToolRegistry.legacyStatus("用户取消了此次工具调用"));
        assertEquals(ToolStatus.TIMEOUT, ToolRegistry.legacyStatus("工具执行超时（90秒），已取消"));
        assertEquals(ToolStatus.FAILED, ToolRegistry.legacyStatus("读取文件失败: 不是普通文件"));
    }

    @Test
    void timeoutsAreTimeoutForReadOnlyToolsAndUnknownForOthers() {
        assertEquals(ToolStatus.TIMEOUT, ToolRegistry.timeoutStatus("read_file"));
        assertEquals(ToolStatus.UNKNOWN, ToolRegistry.timeoutStatus("execute_command"));
        assertEquals(ToolStatus.UNKNOWN, ToolRegistry.timeoutStatus("write_file"));

        ToolExecutionResult legacy = new ToolExecutionResult("c1", "read_file", "{}", "超时", 1, true, List.of());
        assertEquals(ToolStatus.TIMEOUT, legacy.status());
        assertFalse(legacy.successful());
        ToolExecutionResult denied = new ToolExecutionResult("c2", "web_fetch", "{}", "🛡️ 工具调用已拒绝 [X]: y", 0, false, List.of());
        assertEquals(ToolStatus.DENIED, denied.status(), "旧的 7 参数构造函数按前缀推断");
    }

    @Test
    void dispatchReportsDeniedInvalidArgumentAndCancelledWithUnchangedText(@TempDir Path projectDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectDir.toString());

        ToolOutput outside = registry.executeToolOutput("read_file", "{\"path\":\"../../etc/passwd\"}");
        assertEquals(ToolStatus.DENIED, outside.status(), outside.text());
        assertTrue(outside.text().startsWith("🛡️ 策略拒绝"), "给模型看的文字不变: " + outside.text());

        ToolOutput missingArg = registry.executeToolOutput("read_file", "{}");
        assertEquals(ToolStatus.FAILED, missingArg.status());
        assertEquals(ToolOutput.FailureCode.INVALID_ARGUMENT, missingArg.failureCode());

        CancellationToken token = CancellationContext.startRun();
        try {
            token.cancel();
            List<ToolExecutionResult> results = registry.executeTools(
                    List.of(new ToolInvocation("c1", "read_file", "{\"path\":\"a.txt\"}")));
            assertEquals(ToolStatus.CANCELLED, results.get(0).status());
            assertEquals("工具执行失败: 用户取消了此次工具调用", results.get(0).result(), "文字和以前一样");
        } finally {
            CancellationContext.clear(token);
        }
    }

    @Test
    void offloadingLargeResultsKeepsTheStatus() {
        ToolExecutionResult denied = new ToolExecutionResult("c1", "web_fetch", "{}", "🛡️ x", 0, false, List.of(),
                ToolStatus.DENIED, List.of(), ToolOutput.FailureCode.NONE);

        assertEquals(ToolStatus.DENIED, denied.withResult("preview").status());
    }
}
