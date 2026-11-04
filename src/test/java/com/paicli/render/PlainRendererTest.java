package com.paicli.render;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.mcp.elicitation.ElicitationRequest;
import com.paicli.mcp.elicitation.ElicitationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainRendererTest {

    @Test
    void modifyPromptSafelyDisplaysOriginalButReturnsUnencodedJson() {
        String original = "foo\\bar\u001B[2J\r\n\u202E";
        String modified = "{\"command\":\"foo\\\\bar\u202E\"}";
        var request = ApprovalRequest.of("execute_command", original, null);
        var sink = new ByteArrayOutputStream();
        var renderer = new PlainRenderer(new PrintStream(sink, true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader("m\n" + modified + "\n")));
        var result = renderer.promptApproval(request);
        assertEquals(ApprovalResult.Decision.MODIFIED, result.decision());
        assertEquals(modified, result.modifiedArguments());
        assertEquals(original, request.arguments());
        String output = sink.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("当前参数：foo\\bar<U+001B>[2J<U+000D><U+000A><U+202E>"));
        assertSafeApprovalDisplay(output);
    }

    @Test
    void approveAllScopeAndConfirmationSafelyDisplayMcpNames() {
        String server = "demo\u001B[2J\r\n\u202E";
        String tool = "mcp__" + server + "__foo\\bar";
        for (String scope : new String[]{"tool", "server"}) {
            var sink = new ByteArrayOutputStream();
            var renderer = new PlainRenderer(new PrintStream(sink, true, StandardCharsets.UTF_8),
                    new BufferedReader(new StringReader("a\n" + scope + "\n")));
            var handler = new com.paicli.hitl.RendererHitlHandler(renderer, true);
            var request = ApprovalRequest.of(tool, "{}", null);
            var result = handler.requestApproval(request);
            assertEquals(scope.equals("tool") ? ApprovalResult.Decision.APPROVED_ALL
                    : ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, result.decision());
            assertTrue(scope.equals("tool") ? handler.isApprovedAllByTool(tool) : handler.isApprovedAllByServer(server));
            assertFalse(handler.isApprovedAllByTool(ApprovalRequest.encodeForDisplay(tool)));
            assertFalse(handler.isApprovedAllByServer(ApprovalRequest.encodeForDisplay(server)));
            assertEquals(result.decision(), handler.requestApproval(request).decision());
            String output = sink.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains(ApprovalRequest.encodeForDisplay(tool)));
            assertTrue(output.contains(ApprovalRequest.encodeForDisplay(server)));
            assertSafeApprovalDisplay(output);
        }
    }

    private static void assertSafeApprovalDisplay(String output) {
        output.codePoints().forEach(cp -> assertFalse((cp < 32 && cp != '\n')
                || (cp >= 0x7F && cp <= 0x9F) || cp == 0x202E));
    }

    @Test
    void streamReturnsConfiguredPrintStream() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);
        PlainRenderer renderer = new PlainRenderer(out, new BufferedReader(new StringReader("")));
        renderer.stream().println("hello");
        renderer.stream().flush();
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("hello"));
    }

    @Test
    void appendToolCallsRendersGroupedLabels() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader("")));

        var call = new LlmClient.ToolCall(
                "tc-1",
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"README.md\"}"));
        renderer.appendToolCalls(List.of(call));
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("📖 读取 1 个文件"), text);
        assertTrue(text.contains("README.md"), text);
    }

    @Test
    void appendToolCallsHandlesUnknownToolName() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader("")));
        var call = new LlmClient.ToolCall(
                "tc-2",
                new LlmClient.ToolCall.Function("custom_tool", "{}"));
        renderer.appendToolCalls(List.of(call));
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("🔧 custom_tool × 1"));
    }

    @Test
    void appendToolCallsRendersMcpLabel() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader("")));
        var call = new LlmClient.ToolCall(
                "tc-3",
                new LlmClient.ToolCall.Function("mcp__chrome-devtools__navigate_page", "{}"));
        renderer.appendToolCalls(List.of(call));
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("🔌 调用 MCP 工具 chrome-devtools.navigate_page"), text);
    }

    @Test
    void promptApprovalApprovesOnEnter() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader("\n"));
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), reader);

        ApprovalResult result = renderer.promptApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"a.txt\"}", "Test"));
        assertNotNull(result);
        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
    }

    @Test
    void promptApprovalRejectsWithReason() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader("n\nrisky path\n"));
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), reader);

        ApprovalResult result = renderer.promptApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"a.txt\"}", "Test"));
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
        assertEquals("risky path", result.reason());
    }

    @Test
    void promptApprovalApproveAllForBuiltinTool() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader("a\n"));
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), reader);

        ApprovalResult result = renderer.promptApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"a.txt\"}", "Test"));
        assertEquals(ApprovalResult.Decision.APPROVED_ALL, result.decision());
    }

    @Test
    void promptApprovalApproveAllByServerForMcp() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader("a\nserver\n"));
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), reader);

        ApprovalResult result = renderer.promptApproval(
                ApprovalRequest.of("mcp__chrome-devtools__click", "{}", "Test"));
        assertEquals(ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, result.decision());
    }

    @Test
    void promptApprovalSkipReturnsSkipped() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader("s\n"));
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), reader);

        ApprovalResult result = renderer.promptApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"a.txt\"}", "Test"));
        assertEquals(ApprovalResult.Decision.SKIPPED, result.decision());
    }

    @Test
    void promptApprovalModifyAcceptsValidJson() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader("m\n{\"path\":\"b.txt\"}\n"));
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), reader);

        ApprovalResult result = renderer.promptApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"a.txt\"}", "Test"));
        assertEquals(ApprovalResult.Decision.MODIFIED, result.decision());
        assertTrue(result.modifiedArguments().contains("b.txt"));
    }

    @Test
    void promptElicitationFormReviewsBeforeAccepting() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader("""
                alice
                submit
                """));
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), reader);
        var schema = new ObjectMapper().readTree("""
                {"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}
                """);
        var request = new ElicitationRequest("demo", "identity", ElicitationRequest.Mode.FORM,
                "Please provide your name", schema, null);
        ElicitationResponse response = renderer.promptElicitation(request);
        assertEquals(ElicitationResponse.Action.ACCEPT, response.action());
        assertEquals("alice", response.content().path("name").asText());
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("请检查即将发送"));
    }

    @Test
    void promptElicitationUrlRequiresExplicitOpenChoice() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader("decline" + System.lineSeparator()));
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), reader);
        var request = new ElicitationRequest("demo", "authorize", ElicitationRequest.Mode.URL,
                "Open provider authorization", null, java.net.URI.create("https://example.test/authorize"));
        ElicitationResponse response = renderer.promptElicitation(request);
        assertEquals(ElicitationResponse.Action.DECLINE, response.action());
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("https://example.test/authorize"));
    }

    @Test
    void openPaletteReturnsSelectedIndex() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader("2\n"));
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), reader);

        int idx = renderer.openPalette("选一个", List.of("一", "二", "三"));
        assertEquals(1, idx);  // 第 2 项的 index 是 1
    }

    @Test
    void openPaletteReturnsMinusOneOnEmptyInput() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        BufferedReader reader = new BufferedReader(new StringReader("\n"));
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8), reader);

        assertEquals(-1, renderer.openPalette("title", List.of("a", "b")));
    }

    @Test
    void appendDiffPrintsFilePathAndLengthChange() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader("")));

        renderer.appendDiff("README.md", "old content", "old content updated");
        String text = sink.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("README.md"));
        assertTrue(text.contains("11 → 19"));
    }

    @Test
    void appendDiffNewFileReportsCreation() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader("")));

        renderer.appendDiff("new.md", null, "hello");
        assertTrue(sink.toString(StandardCharsets.UTF_8).contains("新建文件"));
    }

    @Test
    void updateStatusIsNoop() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        PlainRenderer renderer = new PlainRenderer(
                new PrintStream(sink, true, StandardCharsets.UTF_8),
                new BufferedReader(new StringReader("")));

        renderer.updateStatus(StatusInfo.idle("glm-5.1", 200_000L, false));
        assertEquals("", sink.toString(StandardCharsets.UTF_8));
    }
}
