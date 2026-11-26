package com.paicli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ToolFailureCodeTest {
    @TempDir Path root;

    @Test void matcherOnlyTypesMissingSourceAndRetainsFallback() {
        var missing = assertThrows(TextEditMatcher.EditException.class,
                () -> TextEditMatcher.apply("source", "missing", "new", false, "a"));
        assertEquals(ToolOutput.FailureCode.EDIT_SOURCE_NOT_FOUND, missing.failureCode());
        var duplicate = assertThrows(TextEditMatcher.EditException.class,
                () -> TextEditMatcher.apply("source source", "source", "new", false, "a"));
        assertEquals(ToolOutput.FailureCode.NONE, duplicate.failureCode());
        var empty = assertThrows(TextEditMatcher.EditException.class,
                () -> TextEditMatcher.apply("source", "", "new", false, "a"));
        assertEquals(ToolOutput.FailureCode.NONE, empty.failureCode());
        assertEquals("new", TextEditMatcher.apply("source", "1 | source", "new", false, "a").content());
    }

    @Test void classificationCompletionSkillRewriteAndOffloadPreserveMetadata() throws Exception {
        var code = ToolOutput.FailureCode.EDIT_SOURCE_NOT_FOUND;
        var urls = List.of("https://example.invalid");
        var output = new ToolOutput("工具执行失败: " + "x".repeat(40000), List.of(), true, urls, code);
        // Exercise the same classification used for MCP outputs without invoking any server.
        var classify = ToolRegistry.class.getDeclaredMethod("classifyOutput", ToolOutput.class);
        classify.setAccessible(true);
        var classified = (ToolOutput) classify.invoke(null, output);
        assertFalse(classified.successful());
        assertEquals(code, classified.failureCode());
        assertEquals(List.of(), classified.discoveredUrls()); // Keep existing URL authority semantics.
        var registry = new ToolRegistry() {
            @Override public ToolOutput executeToolOutput(String name, String args) { return new ToolOutput(classified.text(), classified.imageParts(), false, urls, code); }
        };
        registry.setProjectPath(root.toString());
        var original = registry.executeTools(List.of(new ToolRegistry.ToolInvocation("id", "edit_file", "{}"))).get(0);
        assertEquals(code, original.failureCode());
        assertFalse(original.successful());
        assertFalse(original.timedOut());
        var rewrite = LoadedSkillMessages.class.getDeclaredMethod("withResult",
                ToolRegistry.ToolExecutionResult.class, String.class, boolean.class);
        rewrite.setAccessible(true);
        var rewritten = (ToolRegistry.ToolExecutionResult) rewrite.invoke(null, original, "changed", false);
        assertMetadata(original, rewritten);
        var offloaded = registry.offloadForContext(original);
        assertNotEquals(original.result(), offloaded.result());
        assertMetadata(original, offloaded);
        assertTrue(Files.isDirectory(root.resolve(ToolResultOffloader.OUTPUT_DIR)));
        assertEquals(ToolOutput.FailureCode.NONE, new ToolOutput("legacy", List.of(), false, urls).failureCode());
        assertEquals(ToolOutput.FailureCode.NONE, new ToolRegistry.ToolExecutionResult(
                "id", "edit_file", "{}", original.result(), 1, false, List.of(), false, urls).failureCode());
    }

    private void assertMetadata(ToolRegistry.ToolExecutionResult expected, ToolRegistry.ToolExecutionResult actual) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.name(), actual.name());
        assertEquals(expected.argumentsJson(), actual.argumentsJson());
        assertEquals(expected.elapsedMillis(), actual.elapsedMillis());
        assertEquals(expected.timedOut(), actual.timedOut());
        assertEquals(expected.successful(), actual.successful());
        assertEquals(expected.imageParts(), actual.imageParts());
        assertEquals(expected.discoveredUrls(), actual.discoveredUrls());
        assertEquals(expected.failureCode(), actual.failureCode());
    }
}
