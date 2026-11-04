package com.paicli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.llm.LlmClient;
import com.paicli.mcp.elicitation.ElicitationRequest;
import com.paicli.mcp.elicitation.ElicitationResponse;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererHitlHandlerTest {

    @Test
    void cachedApprovalDisplaysSafeNamesAndRetainsRawKeys() {
        String server = "demo\u001B[2J\r\n\u202E";
        String tool = "mcp__" + server + "__foo\\bar";
        for (boolean byServer : new boolean[]{false, true}) {
            StubRenderer stub = new StubRenderer();
            stub.nextResult = byServer ? ApprovalResult.approveAllByServer() : ApprovalResult.approveAll();
            var handler = new RendererHitlHandler(stub, true);
            var request = ApprovalRequest.of(tool, "{}", null);
            handler.requestApproval(request);
            assertTrue(byServer ? handler.isApprovedAllByServer(server) : handler.isApprovedAllByTool(tool));
            assertFalse(handler.isApprovedAllByServer(ApprovalRequest.encodeForDisplay(server)));
            assertFalse(handler.isApprovedAllByTool(ApprovalRequest.encodeForDisplay(tool)));
            assertEquals(stub.nextResult.decision(), handler.requestApproval(request).decision());
            assertEquals(1, stub.promptCount);
            String output = stub.sink.toString(StandardCharsets.UTF_8);
            assertTrue(output.contains(ApprovalRequest.encodeForDisplay(byServer ? server : tool)));
            assertFalse(output.contains("\u001B"));
            assertFalse(output.contains("\r"));
            assertFalse(output.contains("\u202E"));
            assertEquals(tool, request.toolName());
            assertEquals("{}", request.arguments());
        }
    }

    @Test
    void elicitationIsIndependentFromApprovalGrantsAndHitlToggle() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.approveAll();
        ObjectMapper mapper = new ObjectMapper();
        stub.nextElicitation = new ElicitationResponse(ElicitationResponse.Action.ACCEPT,
                mapper.createObjectNode().put("name", "alice"));
        RendererHitlHandler handler = new RendererHitlHandler(stub, false);
        handler.requestApproval(ApprovalRequest.of("mcp__demo__write", "{}", "test"));
        assertTrue(handler.isApprovedAllByTool("mcp__demo__write"));

        ObjectNode schema = mapper.createObjectNode().put("type", "object");
        ObjectNode properties = mapper.createObjectNode();
        properties.set("name", mapper.createObjectNode().put("type", "string"));
        schema.set("properties", properties);
        var request = new ElicitationRequest("demo", "identity", ElicitationRequest.Mode.FORM,
                "Provide your name", schema, null);
        assertEquals(ElicitationResponse.Action.ACCEPT, handler.requestElicitation(request).action());
        assertEquals(1, stub.elicitationCount);
        assertEquals(1, stub.promptCount);
    }

    @Test
    void delegatesPromptToRenderer() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.approve();
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);

        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("write_file", "{\"path\":\"a\"}", "test"));
        assertEquals(ApprovalResult.Decision.APPROVED, result.decision());
        assertEquals(1, stub.promptCount);
    }

    @Test
    void approveAllByToolFastPathSkipsRenderer() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.approveAll();
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);

        // First call asks renderer and records approve-all
        handler.requestApproval(ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(1, stub.promptCount);
        assertTrue(handler.isApprovedAllByTool("write_file"));

        // Second call should NOT prompt — fast path
        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(1, stub.promptCount);  // unchanged
        assertEquals(ApprovalResult.Decision.APPROVED_ALL, result.decision());
    }

    @Test
    void approveAllByServerCoversFutureMcpCalls() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.approveAllByServer();
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);

        handler.requestApproval(ApprovalRequest.of("mcp__chrome-devtools__click", "{}", "test"));
        assertTrue(handler.isApprovedAllByServer("chrome-devtools"));

        // Different tool from same server — fast path
        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("mcp__chrome-devtools__navigate_page", "{}", "test"));
        assertEquals(1, stub.promptCount);
        assertEquals(ApprovalResult.Decision.APPROVED_ALL_BY_SERVER, result.decision());
    }

    @Test
    void clearApprovedAllResetsState() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.approveAll();
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);
        handler.requestApproval(ApprovalRequest.of("write_file", "{}", "test"));
        assertTrue(handler.isApprovedAllByTool("write_file"));

        handler.clearApprovedAll();
        assertFalse(handler.isApprovedAllByTool("write_file"));
    }

    @Test
    void enabledFlagIsTracked() {
        RendererHitlHandler handler = new RendererHitlHandler(new StubRenderer(), false);
        assertFalse(handler.isEnabled());
        handler.setEnabled(true);
        assertTrue(handler.isEnabled());
    }

    @Test
    void rejectedResultDoesNotPersistApproval() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = ApprovalResult.reject("nope");
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);

        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("write_file", "{}", "test"));
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
        assertFalse(handler.isApprovedAllByTool("write_file"));
    }

    @Test
    void nullResultFromRendererBecomesReject() {
        StubRenderer stub = new StubRenderer();
        stub.nextResult = null;
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);

        ApprovalResult result = handler.requestApproval(
                ApprovalRequest.of("write_file", "{}", "test"));
        assertNotNull(result);
        assertEquals(ApprovalResult.Decision.REJECTED, result.decision());
    }

    @Test
    void clearServerRevokesServerAndToolGrantsOnlyForThatServer() {
        StubRenderer stub = new StubRenderer();
        RendererHitlHandler handler = new RendererHitlHandler(stub, true);
        for (String server : List.of("alpha", "alpha-other")) {
            stub.nextResult = ApprovalResult.approveAll();
            handler.requestApproval(ApprovalRequest.of("mcp__" + server + "__old", "{}", "test"));
            stub.nextResult = ApprovalResult.approveAllByServer();
            handler.requestApproval(ApprovalRequest.of("mcp__" + server + "__another", "{}", "test"));
        }
        handler.clearApprovedAllForServer(null);
        handler.clearApprovedAllForServer("alpha");
        assertFalse(handler.isApprovedAllByServer("alpha"));
        assertFalse(handler.isApprovedAllByTool("mcp__alpha__old"));
        assertTrue(handler.isApprovedAllByServer("alpha-other"));
        assertTrue(handler.isApprovedAllByTool("mcp__alpha-other__old"));
        stub.nextResult = ApprovalResult.reject("must reapprove");
        assertEquals(ApprovalResult.Decision.REJECTED, handler.requestApproval(
                ApprovalRequest.of("mcp__alpha__old", "{}", "test")).decision());
        assertEquals(5, stub.promptCount);
    }

    private static final class StubRenderer implements Renderer {
        private final ByteArrayOutputStream sink = new ByteArrayOutputStream();
        private final PrintStream out = new PrintStream(sink, true, StandardCharsets.UTF_8);
        ApprovalResult nextResult;
        int promptCount;
        int elicitationCount;
        ElicitationResponse nextElicitation = ElicitationResponse.cancel();

        @Override public void start() {}
        @Override public void close() {}
        @Override public PrintStream stream() { return out; }
        @Override public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {}
        @Override public void appendDiff(String filePath, String before, String after) {}
        @Override public void updateStatus(StatusInfo status) {}
        @Override public ApprovalResult promptApproval(ApprovalRequest request) {
            promptCount++;
            return nextResult;
        }
        @Override public ElicitationResponse promptElicitation(ElicitationRequest request) {
            elicitationCount++;
            return nextElicitation;
        }
        @Override public int openPalette(String title, List<String> items) { return -1; }
    }
}
