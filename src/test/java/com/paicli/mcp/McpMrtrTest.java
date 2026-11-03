package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.elicitation.ElicitationResponse;
import com.paicli.mcp.jsonrpc.JsonRpcClient;
import com.paicli.mcp.protocol.McpProtocolVersion;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class McpMrtrTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static ObjectNode done() { return M.createObjectNode().put("resultType", "complete"); }
    private static ObjectNode input() { return M.createObjectNode().put("resultType", "input_required"); }
    private static ObjectNode entry() {
        var e = M.createObjectNode().put("method", "elicitation/create");
        var p = e.putObject("params"); p.put("message", "Choose");
        p.putObject("requestedSchema").put("type", "object").putObject("properties")
                .putObject("choice").put("type", "boolean");
        return e;
    }
    private static ObjectNode round(String key) {
        var r = input(); r.putObject("inputRequests").set(key, entry()); return r;
    }
    @Test void completeNeedsNoInteraction() throws Exception {
        var t = new InMemoryTransport().handle("tools/call", p -> done());
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.setElicitationHandler(r -> { fail("unexpected prompt"); return null; });
            c.callTool("echo", "{}"); assertEquals(1, t.sentMessages().size());
        }
    }
    @Test void multiRoundPreservesBusinessAndOnlyImmediateStateAndAnswers() throws Exception {
        var n = new AtomicInteger();
        String state = "  opaque\u0000雪\n ";
        var t = new InMemoryTransport().handle("tools/call", p -> switch(n.getAndIncrement()) {
            case 0 -> round("first").put("requestState", state);
            case 1 -> round("second");
            default -> done();
        });
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.setElicitationHandler(r -> new ElicitationResponse(ElicitationResponse.Action.ACCEPT, M.createObjectNode()));
            c.callTool("echo", "{\"x\":1}");
            var sent = t.sentMessages(); assertEquals(3, sent.size());
            var ids = new java.util.HashSet<JsonNode>();
            for (var m : sent) {
                assertTrue(ids.add(m.get("id")));
                assertEquals("tools/call", m.path("method").asText());
                assertEquals("echo", m.path("params").path("name").asText());
                assertEquals(1, m.path("params").path("arguments").path("x").asInt());
                assertTrue(m.path("params").path("_meta").path("io.modelcontextprotocol/clientCapabilities").isEmpty());
            }
            assertFalse(sent.get(0).path("params").has("requestState"));
            assertEquals(state, sent.get(1).path("params").path("requestState").textValue());
            assertFalse(sent.get(2).path("params").has("requestState"));
            assertEquals(java.util.Set.of("first"), keys(sent.get(1).path("params").path("inputResponses")));
            assertEquals(java.util.Set.of("second"), keys(sent.get(2).path("params").path("inputResponses")));
        }
    }
    private static java.util.Set<String> keys(JsonNode n) {
        var keys = new java.util.HashSet<String>(); n.fieldNames().forEachRemaining(keys::add); return keys;
    }
    @Test void acceptDeclineCancelReachServerOnAllOperations() throws Exception {
        for (String method : new String[]{"tools/call", "resources/read", "prompts/get"}) {
            for (var action : ElicitationResponse.Action.values()) {
                var n = new AtomicInteger();
                var t = new InMemoryTransport().handle(method, p -> n.getAndIncrement() == 0 ? round("k") : done());
                try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
                    c.setElicitationHandler(r -> new ElicitationResponse(action,
                            action == ElicitationResponse.Action.ACCEPT ? M.createObjectNode() : null));
                    invoke(c, method);
                    var original = (ObjectNode) t.sentMessages().get(0).path("params").deepCopy();
                    var retry = (ObjectNode) t.sentMessages().get(1).path("params").deepCopy();
                    retry.remove("inputResponses");
                    assertEquals(original, retry);
                    assertNotEquals(t.sentMessages().get(0).get("id"), t.sentMessages().get(1).get("id"));
                    var response = t.sentMessages().get(1).path("params").path("inputResponses").path("k");
                    assertEquals(action.name().toLowerCase(java.util.Locale.ROOT), response.path("action").asText());
                    assertEquals(action == ElicitationResponse.Action.ACCEPT, response.has("content"));
                    assertFalse(t.sentMessages().get(1).path("params").has("requestState"));
                }
            }
        }
    }
    private static void invoke(McpClient c, String method) throws Exception {
        switch(method) {
            case "tools/call" -> c.callTool("echo", "{}");
            case "resources/read" -> c.readResource("test://a");
            default -> c.getPrompt("p", M.createObjectNode().put("x", "y"));
        }
    }
    @Test void stateOnlyRoundAndLaterRequestAreIsolated() throws Exception {
        var n = new AtomicInteger();
        var t = new InMemoryTransport().handle("prompts/get", p -> n.getAndIncrement() == 0 ? input().put("requestState", "") : done());
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.getPrompt("p", null); c.getPrompt("q", null);
            assertEquals("", t.sentMessages().get(1).path("params").path("requestState").textValue());
            assertFalse(t.sentMessages().get(1).path("params").has("inputResponses"));
            assertFalse(t.sentMessages().get(2).path("params").has("requestState"));
        }
    }
    @Test void malformedWholeRoundFailsBeforeAnyPrompt() throws Exception {
        var cases = new java.util.ArrayList<ObjectNode>();
        cases.add(input()); cases.add(input().put("requestState", 3)); cases.add(input().putNull("requestState"));
        cases.add(input().putNull("inputRequests")); cases.add(input().put("inputRequests", "bad"));
        var unknown = round("good"); unknown.withObject("inputRequests").putObject("bad").put("method", "sampling/createMessage"); cases.add(unknown);
        var roots = round("good"); roots.withObject("inputRequests").putObject("bad").put("method", "roots/list"); cases.add(roots);
        var malformed = round("good"); malformed.withObject("inputRequests").putObject("bad").put("method", "elicitation/create"); cases.add(malformed);
        var count = input(); var map = count.putObject("inputRequests"); for(int i=0;i<9;i++) map.set("k"+i, entry()); cases.add(count);
        var huge = round("k"); huge.withObject("inputRequests").withObject("k").put("padding", "x".repeat(65536)); cases.add(huge);
        for (var result : cases) {
            var t = new InMemoryTransport().handle("tools/call", p -> result);
            try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
                c.setElicitationHandler(r -> { fail("partial answers collected"); return null; });
                assertThrows(IOException.class, () -> c.callTool("echo", "{}"));
                assertEquals(1, t.sentMessages().size());
            }
        }
    }
    @Test void roundLimitAndRequestBoundary() throws Exception {
        var result = input(); var map = result.putObject("inputRequests"); for(int i=0;i<8;i++) map.set("k"+i, entry());
        var prompts = new AtomicInteger();
        var t = new InMemoryTransport().handle("tools/call", p -> result);
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.setElicitationHandler(r -> { prompts.incrementAndGet(); return ElicitationResponse.cancel(); });
            assertThrows(IOException.class, () -> c.callTool("echo", "{}"));
            assertEquals(5, t.sentMessages().size()); assertEquals(32, prompts.get());
        }
    }
    @Test void legacyAndStrictRpcNeverPromptOrRetry() throws Exception {
        var t = new InMemoryTransport().handle("tools/call", p -> round("k"));
        try (var c = new McpClient("test", t)) {
            c.setElicitationHandler(r -> { fail("legacy prompt"); return null; });
            c.callTool("echo", "{}"); assertEquals(1, t.sentMessages().size());
            assertFalse(t.sentMessages().get(0).path("params").has("_meta"));
        }
        var strict = new InMemoryTransport().handle("tools/call", p -> round("k"));
        try (var rpc = new JsonRpcClient(strict, McpProtocolVersion.MODERN)) {
            assertThrows(IOException.class, () -> rpc.request("tools/call", M.createObjectNode()));
            assertThrows(IOException.class, () -> rpc.requestWithInputs("tools/list", M.createObjectNode()));
            assertEquals(1, strict.sentMessages().size());
        }
    }

    @Test void urlAcceptAndDefaultCancelHaveNoContent() throws Exception {
        for (boolean accept : new boolean[]{true, false}) {
            var result = input();
            var entry = M.createObjectNode().put("method", "elicitation/create");
            entry.putObject("params").put("mode", "url").put("message", "Open")
                    .put("url", "https://example.com/consent");
            result.putObject("inputRequests").set("url", entry);
            var n = new AtomicInteger();
            var t = new InMemoryTransport().handle("tools/call", p -> n.getAndIncrement() == 0 ? result : done());
            try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
                if (accept) c.setElicitationHandler(r -> new ElicitationResponse(ElicitationResponse.Action.ACCEPT, null));
                c.callTool("echo", "{}");
                var response = t.sentMessages().get(1).path("params").path("inputResponses").path("url");
                assertEquals(accept ? "accept" : "cancel", response.path("action").asText());
                assertFalse(response.has("content"));
            }
        }
    }

    @Test void unrelatedOperationsKeepStrictResultValidation() throws Exception {
        var t = new InMemoryTransport();
        for (String method : new String[]{"server/discover", "tools/list", "resources/list", "prompts/list"})
            t.handle(method, p -> round("k"));
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.setElicitationHandler(r -> { fail("unrelated operation prompted"); return null; });
            assertThrows(IOException.class, c::initialize);
            assertThrows(IOException.class, c::listTools);
            assertThrows(IOException.class, c::listResources);
            assertThrows(IOException.class, c::listPrompts);
            assertEquals(4, t.sentMessages().size());
        }
    }

    @Test void requestStateTypeIsCheckedBeforePromptAndNotExposedInError() throws Exception {
        var result = round("k"); result.putObject("requestState").put("secret", "do-not-expose");
        var t = new InMemoryTransport().handle("tools/call", p -> result);
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.setElicitationHandler(r -> { fail("prompt before state validation"); return null; });
            var error = assertThrows(IOException.class, () -> c.callTool("echo", "{}"));
            assertFalse(error.toString().contains("do-not-expose"));
            assertNull(error.getCause());
        }
    }

    @Test void invalidAcceptContentFailsWithoutRetryAndNullHandlerResponseCancels() throws Exception {
        var t = new InMemoryTransport().handle("tools/call", p -> round("k"));
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.setElicitationHandler(r -> new ElicitationResponse(ElicitationResponse.Action.ACCEPT, null));
            assertThrows(IOException.class, () -> c.callTool("echo", "{}"));
            assertEquals(1, t.sentMessages().size());
        }
        var n = new AtomicInteger();
        var cancel = new InMemoryTransport().handle("tools/call", p -> n.getAndIncrement() == 0 ? round("k") : done());
        try (var c = new McpClient("test", cancel, McpProtocolVersion.MODERN)) {
            c.setElicitationHandler(r -> null);
            c.callTool("echo", "{}");
            assertEquals("cancel", cancel.sentMessages().get(1).path("params").path("inputResponses").path("k").path("action").asText());
        }
    }
}
