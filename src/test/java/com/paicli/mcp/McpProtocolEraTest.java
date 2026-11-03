package com.paicli.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.config.McpConfigLoader;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.protocol.McpInitializeRequest;
import com.paicli.mcp.protocol.McpProtocolVersion;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class McpProtocolEraTest {
    private static final ObjectMapper M = new ObjectMapper();
    private static ObjectNode complete() { return M.createObjectNode().put("resultType", "complete"); }
    private static ObjectNode discovery() {
        ObjectNode result = complete();
        result.putArray("supportedProtocolVersions").add("2026-07-28");
        result.putObject("capabilities").putObject("resources");
        return result;
    }

    @Test void defaultLegacyEnvelopeIsUnchanged() throws Exception {
        var t = new InMemoryTransport().handle("initialize", p -> M.createObjectNode())
                .handle("tools/list", p -> M.createObjectNode());
        try (var c = new McpClient("test", t)) {
            c.initialize(); c.listTools();
            var sent = t.sentMessages();
            assertEquals(3, sent.size());
            assertEquals("initialize", sent.get(0).path("method").asText());
            assertEquals(McpInitializeRequest.toJson(), sent.get(0).path("params"));
            assertEquals("notifications/initialized", sent.get(1).path("method").asText());
            assertEquals(M.createObjectNode(), sent.get(1).path("params"));
            assertEquals(M.createObjectNode(), sent.get(2).path("params"));
            assertFalse(c.isModern());
        }
    }

    @Test void modernDiscoveryAndEveryNormalRequestCarryMetadata() throws Exception {
        var t = new InMemoryTransport().handle("server/discover", p -> discovery());
        for (String method : new String[]{"tools/list", "tools/call", "resources/list", "resources/read", "prompts/list"})
            t.handle(method, p -> complete());
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.initialize(); assertTrue(c.supportsResources());
            c.listTools(); c.callTool("echo", "{}"); c.listResources(); c.readResource("test://a"); c.listPrompts();
            assertEquals(6, t.sentMessages().size());
            assertEquals("server/discover", t.sentMessages().get(0).path("method").asText());
            for (var request : t.sentMessages()) {
                var meta = request.path("params").path("_meta");
                assertEquals("2026-07-28", meta.path("io.modelcontextprotocol/protocolVersion").asText());
                assertEquals(M.createObjectNode(), meta.path("io.modelcontextprotocol/clientCapabilities"));
                assertEquals("paicli", meta.path("io.modelcontextprotocol/clientInfo").path("name").asText());
                assertEquals(com.paicli.cli.Main.VERSION, meta.path("io.modelcontextprotocol/clientInfo").path("version").asText());
            }
            assertEquals("echo", t.sentMessages().get(2).path("params").path("name").asText());
            assertEquals("test://a", t.sentMessages().get(4).path("params").path("uri").asText());
        }
    }

    @Test void discoveryRequiresPinnedVersionAndNeverFallsBack() throws Exception {
        for (String versions : new String[]{"[]", "[\"2025-03-26\"]", "null", "\"2026-07-28\""}) {
            var result = complete(); result.set("supportedProtocolVersions", M.readTree(versions));
            var t = new InMemoryTransport().handle("server/discover", p -> result);
            try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
                assertTrue(assertThrows(IOException.class, c::initialize).getMessage().contains("不会自动降级"));
                assertEquals(1, t.sentMessages().size());
            }
        }
    }

    @Test void inputRequiredAndInvalidResultsFailBeforeDeserialization() throws Exception {
        for (String type : new String[]{"input_required", "unknown", ""}) {
            var result = M.createObjectNode(); if (!type.isEmpty()) result.put("resultType", type);
            var t = new InMemoryTransport();
            for (String method : new String[]{"server/discover", "tools/list", "tools/call", "resources/list", "resources/read", "prompts/list"})
                t.handle(method, p -> result);
            try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
                String expected = type.equals("input_required") ? "MRTR" : "resultType";
                assertTrue(assertThrows(IOException.class, c::initialize).getMessage().contains(expected));
                assertTrue(assertThrows(IOException.class, c::listTools).getMessage().contains(expected));
                assertTrue(assertThrows(IOException.class, () -> c.callTool("echo", "{}")).getMessage().contains(expected));
                assertTrue(assertThrows(IOException.class, c::listResources).getMessage().contains(expected));
                assertTrue(assertThrows(IOException.class, () -> c.readResource("test://a")).getMessage().contains(expected));
                assertTrue(assertThrows(IOException.class, c::listPrompts).getMessage().contains(expected));
            }
        }
    }

    @Test void modernPingAndSubscriptionNeverReachTransport() throws Exception {
        var t = new InMemoryTransport();
        try (var c = new McpClient("test", t, McpProtocolVersion.MODERN)) {
            c.ping(1);
            assertTrue(assertThrows(IOException.class, () -> c.subscribeResource("test://a")).getMessage().contains("尚未实现"));
            assertTrue(t.sentMessages().isEmpty());
        }
    }

    @Test void configDefaultsValidationAndFingerprint() throws Exception {
        var loader = new McpConfigLoader(Path.of("/tmp/protocol-config"));
        var c = new McpServerConfig(); c.setCommand("unused");
        loader.prepare(c); assertEquals(McpProtocolVersion.LEGACY, c.protocolVersion());
        String original = McpConfigLoader.fingerprint(c);
        c.setProtocolVersion("2025-03-26"); loader.prepare(c);
        c.setProtocolVersion("2026-07-28"); loader.prepare(c);
        assertNotEquals(original, McpConfigLoader.fingerprint(c));
        assertEquals(McpProtocolVersion.MODERN, M.readValue("{\"protocolVersion\":\"2026-07-28\"}", McpServerConfig.class).protocolVersion());
        for (String value : new String[]{"", "2025-11-25", " 2026-07-28"}) {
            c.setProtocolVersion(value);
            assertThrows(IllegalArgumentException.class, () -> loader.prepare(c));
        }
    }
}
