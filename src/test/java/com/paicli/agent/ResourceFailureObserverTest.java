package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResourceFailureObserverTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    private ToolExecutionResult failure(String path, int variant) throws Exception {
        return new ToolExecutionResult("id", "edit_file", mapper.writeValueAsString(Map.of(
                "path", path, "old_text", "missing", "new_text", "replacement " + variant)),
                "工具执行失败: old_text 在文件中不存在: " + path, 1, false, List.of(), false, List.of());
    }

    @Test void varyingReplacementAndNormalizedKnownPathProduceOneObservation() throws Exception {
        Files.writeString(root.resolve("a"), "source");
        var observer = new ResourceFailureObserver(root, true);
        assertTrue(observer.observe(List.of(failure("a", 1))).isEmpty());
        assertTrue(observer.observe(List.of(failure("./a", 2))).isEmpty());
        assertEquals(ResourceFailureObserver.Notice.REPEATED_EDIT_SOURCE_NOT_FOUND,
                observer.observe(List.of(failure(root.resolve("a").toString(), 3))).orElseThrow());
        assertTrue(observer.observe(List.of(failure("a", 4))).isEmpty());
    }

    @Test void defaultsOffAndOriginalGuardUnaffected() throws Exception {
        Files.writeString(root.resolve("a"), "source");
        var observer = new ResourceFailureObserver(root);
        var guard = new RunawayGuard();
        for (int i = 0; i < 6; i++) {
            var results = List.of(failure("a", i));
            assertTrue(observer.observe(results).isEmpty());
            assertTrue(guard.observe(results).isEmpty());
        }
    }

    @Test void differentResourcesAndSourceSelectorsDoNotAccumulate() throws Exception {
        Files.writeString(root.resolve("a"), "source");
        Files.writeString(root.resolve("b"), "source");
        var observer = new ResourceFailureObserver(root, true);
        for (String path : List.of("a", "b", "a", "b", "a", "b"))
            assertTrue(observer.observe(List.of(failure(path, 1))).isEmpty());
        for (int i = 0; i < 5; i++) {
            var f = failure("a", i);
            var args = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(f.argumentsJson());
            args.put("old_text", "source " + i);
            assertTrue(observer.observe(List.of(copy(f, "edit_file", args.toString(), f.result(), false, false))).isEmpty());
        }
    }

    @Test void successCancellationApprovalUnknownFailureAndMixedBatchesReset() throws Exception {
        Files.writeString(root.resolve("a"), "source");
        var f = failure("a", 0);
        for (String text : List.of("[AUTO] refused", "[HITL] approved", "用户取消了此次工具调用",
                "工具执行失败: 用户取消了此次工具调用", "工具执行失败: 工具执行被中断", "unknown")) {
            assertBreak(f, copy(f, "edit_file", f.argumentsJson(), text, false, false));
        }
        assertBreak(f, copy(f, "edit_file", f.argumentsJson(), f.result(), true, false));
        assertBreak(f, copy(f, "edit_file", f.argumentsJson(), f.result(), false, true));
        var observer = new ResourceFailureObserver(root, true);
        observer.observe(List.of(f)); observer.observe(List.of(f));
        assertTrue(observer.observe(List.of(f, copy(f, "read_file", "{}", "ok", true, false))).isEmpty());
        assertTrue(observer.observe(List.of(f)).isEmpty());
    }

    private void assertBreak(ToolExecutionResult f, ToolExecutionResult breaker) {
        var observer = new ResourceFailureObserver(root, true);
        observer.observe(List.of(f)); observer.observe(List.of(f));
        assertTrue(observer.observe(List.of(breaker)).isEmpty());
        assertTrue(observer.observe(List.of(f)).isEmpty());
        assertTrue(observer.observe(List.of(f)).isEmpty());
    }

    @Test void pagingPollingCommandsAndMcpNeverGeneralized() throws Exception {
        Files.writeString(root.resolve("a"), "source");
        for (String tool : List.of("read_file", "list_dir", "execute_command", "mcp__one__poll", "mcp__two__poll")) {
            var observer = new ResourceFailureObserver(root, true);
            for (int i = 0; i < 5; i++) {
                var f = failure("a", i);
                assertTrue(observer.observe(List.of(copy(f, tool,
                        "{\"path\":\"a\",\"offset\":" + i + "}", f.result(), false, false))).isEmpty());
                assertTrue(observer.observe(List.of(copy(f, tool,
                        "{\"path\":\"a\",\"offset\":" + i + "}", "normal result", true, false))).isEmpty());
            }
        }
    }

    @Test void batchDuplicatesCountAsOneStepAndEmptyOrInvalidInputResets() throws Exception {
        Files.writeString(root.resolve("a"), "source");
        var f = failure("a", 0);
        var observer = new ResourceFailureObserver(root, true);
        assertTrue(observer.observe(List.of(f, f, f)).isEmpty());
        assertTrue(observer.observe(List.of(f)).isEmpty());
        assertTrue(observer.observe(List.of()).isEmpty());
        assertTrue(observer.observe(List.of(f)).isEmpty());
        assertTrue(observer.observe(List.of(f)).isEmpty());
        assertTrue(observer.observe(null).isEmpty());
        assertBreak(f, copy(f, "edit_file", "not json", f.result(), false, false));
        assertBreak(f, copy(f, "edit_file", "{\"path\":[]} ", f.result(), false, false));
    }

    @Test void unsafeMissingInvalidAndCaseDifferentPathsNotMerged() throws Exception {
        Files.writeString(root.resolve("a"), "source");
        Path outside = Files.createTempFile(root.getParent(), "observer-outside", ".txt");
        try {
            Files.createSymbolicLink(root.resolve("escape"), outside);
            for (String path : List.of("../" + outside.getFileName(), outside.toString(), "escape", "missing", "\u0000")) {
                var observer = new ResourceFailureObserver(root, true);
                for (int i = 0; i < 4; i++) assertTrue(observer.observe(List.of(failure(path, i))).isEmpty());
            }
            var observer = new ResourceFailureObserver(root, true);
            for (String path : List.of("a", "A", "a", "A"))
                assertTrue(observer.observe(List.of(failure(path, 1))).isEmpty());
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test void observesActualTypedToolFailure() throws Exception {
        Files.writeString(root.resolve("a"), "source");
        var registry = new ToolRegistry();
        registry.setProjectPath(root.toString());
        var observer = new ResourceFailureObserver(root, true);
        for (int i = 0; i < 3; i++) {
            var results = registry.executeTools(List.of(new ToolRegistry.ToolInvocation("id" + i,
                    "edit_file", failure("a", i).argumentsJson())));
            assertFalse(results.get(0).successful());
            assertEquals(i == 2, observer.observe(results).isPresent());
        }
    }

    private ToolExecutionResult copy(ToolExecutionResult f, String tool, String args, String text,
                                     boolean success, boolean timeout) {
        return new ToolExecutionResult(f.id(), tool, args, text, 1, timeout, List.of(), success, List.of());
    }
}
