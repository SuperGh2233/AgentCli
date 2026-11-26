package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.hitl.*;
import com.paicli.mcp.elicitation.*;
import com.paicli.mcp.jsonrpc.JsonRpcClient;
import com.paicli.mcp.protocol.McpProtocolVersion;
import com.paicli.render.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpElicitationCapabilitiesTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static final String CAP = "io.modelcontextprotocol/clientCapabilities";
    private static JsonNode caps(JsonNode message) { return message.path("params").path("_meta").path(CAP); }
    private static JsonNode done() { return M.createObjectNode().put("resultType", "complete"); }
    private static McpElicitationHandler handler(Set<String> modes) {
        return new McpElicitationHandler() {
            public ElicitationResponse elicit(ElicitationRequest request) { return ElicitationResponse.cancel(); }
            public Set<String> supportedModes() { return modes; }
        };
    }
    @Test void exactModesAndUnknownModes() throws Exception {
        for (var modes : java.util.List.of(Set.of("form", "url"), Set.of("form"), Set.of("url"), Set.<String>of(), Set.of("unknown"))) {
            var expected = M.createObjectNode();
            if (modes.contains("form") || modes.contains("url")) {
                var e = expected.putObject("elicitation");
                for (String mode : java.util.List.of("form", "url")) if (modes.contains(mode)) e.putObject(mode);
            }
            assertEquals(expected, McpProtocolVersion.MODERN.decorate(null, modes).path("_meta").path(CAP));
        }
    }
    @Test void preservesMetadataAndDoesNotMutateCaller() throws Exception {
        var original = M.readTree("{\"name\":\"echo\",\"_meta\":{\"trace\":{\"id\":7},\"io.modelcontextprotocol/clientCapabilities\":{\"custom\":{\"enabled\":true},\"elicitation\":{\"bad\":{}}}}}");
        var copy = original.deepCopy();
        var decorated = McpProtocolVersion.MODERN.decorate(original, Set.of("url", "unknown"));
        assertEquals(copy, original);
        assertEquals(original.path("_meta").path("trace"), decorated.path("_meta").path("trace"));
        assertEquals(M.readTree("{\"custom\":{\"enabled\":true},\"elicitation\":{\"url\":{}}}"), decorated.path("_meta").path(CAP));
        assertEquals("echo", decorated.path("name").asText());
    }
    @Test void ordinaryRequestsAndNotificationsRemoveStaleElicitationButPreserveCustomCapabilities() throws Exception {
        var params = M.readTree("{\"_meta\":{\"io.modelcontextprotocol/clientCapabilities\":{\"custom\":{},\"elicitation\":{\"form\":{},\"url\":{}}}}}");
        var t = new InMemoryTransport().handle("tools/call", p -> done());
        try (var rpc = new JsonRpcClient(t, McpProtocolVersion.MODERN)) {
            rpc.setSupportedElicitationModes(() -> Set.of("form"));
            rpc.request("tools/call", params);
            rpc.sendNotification("notifications/test", params);
            for (var message : t.sentMessages()) assertEquals(M.readTree("{\"custom\":{}}"), caps(message));
            assertTrue(params.path("_meta").path(CAP).has("elicitation"));
            rpc.requestWithInputs("tools/call", params);
            assertEquals(M.readTree("{\"custom\":{},\"elicitation\":{\"form\":{}}}"), caps(t.sentMessages().get(2)));
        }
    }

    @Test void onlyEligibleModernInputsAdvertise() throws Exception {
        var t = new InMemoryTransport();
        var operations = java.util.List.of("tools/call", "resources/read", "prompts/get");
        var ordinary = java.util.List.of("server/discover", "tools/list", "resources/list", "prompts/list");
        for (String method : operations) t.handle(method, p -> done());
        for (String method : ordinary) t.handle(method, p -> done());
        try (var rpc = new JsonRpcClient(t, McpProtocolVersion.MODERN)) {
            var queries = new java.util.concurrent.atomic.AtomicInteger();
            rpc.setSupportedElicitationModes(() -> { queries.incrementAndGet(); return Set.of("form", "url"); });
            for (String method : operations) {
                rpc.requestWithInputs(method, null);
                assertEquals(M.readTree("{\"elicitation\":{\"form\":{},\"url\":{}}}"), caps(t.sentMessages().get(t.sentMessages().size()-1)));
                rpc.request(method, null);
                assertTrue(caps(t.sentMessages().get(t.sentMessages().size()-1)).isEmpty());
            }
            for (String method : ordinary) {
                rpc.request(method, null);
                assertTrue(caps(t.sentMessages().get(t.sentMessages().size()-1)).isEmpty());
                assertThrows(IOException.class, () -> rpc.requestWithInputs(method, null));
            }
            rpc.sendNotification("notifications/test", null);
            assertTrue(caps(t.sentMessages().get(t.sentMessages().size()-1)).isEmpty());
            assertEquals(3, queries.get());
        }
    }
    @Test void legacyNeverQueriesOrAdvertises() throws Exception {
        var t = new InMemoryTransport().handle("tools/call", p -> M.createObjectNode());
        try (var rpc = new JsonRpcClient(t)) {
            rpc.setSupportedElicitationModes(() -> { fail("legacy queried modes"); return Set.of("form"); });
            rpc.request("tools/call", M.createObjectNode());
            rpc.sendNotification("notifications/test", M.createObjectNode());
            assertThrows(IOException.class, () -> rpc.requestWithInputs("tools/call", null));
            for (var message : t.sentMessages()) assertFalse(message.path("params").has("_meta"));
        }
    }
    @Test void defaultsAndBenchmarkStyleClientStayEmpty() throws Exception {
        assertTrue(McpElicitationHandler.CANCEL.supportedModes().isEmpty());
        McpElicitationHandler lambda = r -> ElicitationResponse.cancel();
        assertTrue(lambda.supportedModes().isEmpty());
        var t = new InMemoryTransport().handle("tools/call", p -> done());
        try (var c = new McpClient("benchmark", t, McpProtocolVersion.MODERN)) {
            c.callTool("echo", "{}");
            c.setElicitationHandler(null); c.callTool("echo", "{}");
            c.setElicitationHandler(lambda); c.callTool("echo", "{}");
            for (var message : t.sentMessages()) assertTrue(caps(message).isEmpty());
        }
        assertTrue(new TerminalHitlHandler(false).supportedModes().isEmpty());
    }
    @Test void capabilityFailureAndNullFailClosed() throws Exception {
        var t = new InMemoryTransport().handle("tools/call", p -> done());
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.setElicitationHandler(new McpElicitationHandler() {
                public ElicitationResponse elicit(ElicitationRequest r) { return ElicitationResponse.cancel(); }
                public Set<String> supportedModes() { throw new IllegalStateException("unavailable"); }
            });
            c.callTool("echo", "{}");
            c.setElicitationHandler(handler(null)); c.callTool("echo", "{}");
            for (var message : t.sentMessages()) assertTrue(caps(message).isEmpty());
        }
    }
    @Test void rendererAndSwitchableChangesReachVeryNextClientRequest() throws Exception {
        Renderer renderer = mock(Renderer.class);
        when(renderer.supportedElicitationModes()).thenReturn(Set.of("form"));
        var ui = new RendererHitlHandler(renderer, false);
        var switchable = new SwitchableHitlHandler(ui);
        var t = new InMemoryTransport().handle("tools/call", p -> done());
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.setElicitationHandler(switchable); c.callTool("echo", "{}");
            assertEquals(M.readTree("{\"elicitation\":{\"form\":{}}}"), caps(t.sentMessages().get(0)));
            when(renderer.supportedElicitationModes()).thenReturn(Set.of("url"));
            c.callTool("echo", "{}");
            assertEquals(M.readTree("{\"elicitation\":{\"url\":{}}}"), caps(t.sentMessages().get(1)));
            switchable.setDelegate(new TerminalHitlHandler(false)); c.callTool("echo", "{}");
            assertTrue(caps(t.sentMessages().get(2)).isEmpty());
            switchable.setDelegate(new RendererHitlHandler(new PlainRenderer(), false)); c.callTool("echo", "{}");
            assertEquals(M.readTree("{\"elicitation\":{\"form\":{},\"url\":{}}}"), caps(t.sentMessages().get(3)));
        }
    }
    @Test void plainSupportsBothAndDefaultRendererStaysEmpty() {
        assertEquals(Set.of("form", "url"), new PlainRenderer().supportedElicitationModes());
        var renderer = mock(Renderer.class, CALLS_REAL_METHODS);
        assertTrue(renderer.supportedElicitationModes().isEmpty());
    }
}
