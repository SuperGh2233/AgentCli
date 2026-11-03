package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.oauth.McpTokenProvider;
import com.paicli.mcp.protocol.McpProtocolVersion;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModernHttpTransportTest {
    @Test void derivesHeadersIgnoresConfiguredAndReturnedSessionAndNeverDeletes() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var transport = new StreamableHttpTransport(server.url("/mcp").toString(),
                    Map.of("Mcp-Session-Id", "injected", "Mcp-Method", "wrong", "Mcp-Name", "wrong",
                            "MCP-Protocol-Version", "wrong"), null, McpProtocolVersion.MODERN);
            String[] methods = {"server/discover", "tools/list", "tools/call", "prompts/get", "resources/read"};
            String[] names = {null, null, "echo", "hello", "test://a"};
            for (int i = 0; i < methods.length; i++) {
                server.enqueue(new MockResponse().setHeader("Mcp-Session-Id", "must-ignore").setBody("{}"));
                var message = new ObjectMapper().createObjectNode().put("jsonrpc", "2.0").put("id", i).put("method", methods[i]);
                message.putObject("params").put("name", "prompts/get".equals(methods[i]) ? "hello" : "echo").put("uri", "test://a");
                transport.send(message);
                var request = server.takeRequest();
                assertEquals("POST", request.getMethod());
                assertEquals("2026-07-28", request.getHeader("MCP-Protocol-Version"));
                assertEquals(methods[i], request.getHeader("Mcp-Method"));
                assertEquals(names[i], request.getHeader("Mcp-Name"));
                assertNull(request.getHeader("Mcp-Session-Id"));
            }
            transport.close(); transport.close();
            assertEquals(5, server.getRequestCount());
        }
    }

    @Test void missingOrEmptyPrincipalOmitsNameHeader() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            try (var transport = new StreamableHttpTransport(server.url("/mcp").toString(),
                    Map.of("Mcp-Name", "injected"), null, McpProtocolVersion.MODERN)) {
                for (String method : new String[]{"tools/call", "prompts/get", "resources/read"}) {
                    for (String params : new String[]{"{}", "{\"name\":\"\",\"uri\":\"\"}", "{\"name\":null,\"uri\":null}"}) {
                        server.enqueue(new MockResponse().setBody("{}"));
                        var message = new ObjectMapper().createObjectNode().put("method", method);
                        message.set("params", new ObjectMapper().readTree(params));
                        transport.send(message);
                        assertNull(server.takeRequest().getHeader("Mcp-Name"));
                    }
                }
            }
        }
    }

    @Test void oauthRefreshRetainsModernHeadersAndBodyWithoutSessionOrDelete() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(401));
            server.enqueue(new MockResponse().setHeader("Mcp-Session-Id", "ignored").setBody("{}"));
            AtomicInteger refresh = new AtomicInteger();
            AtomicInteger resourceChecks = new AtomicInteger();
            String url = server.url("/mcp").toString();
            var provider = new McpTokenProvider() {
                public void validateResource(String resource) { assertEquals(url, resource); resourceChecks.incrementAndGet(); }
                public String accessToken() { return "old"; }
                public String refreshAfterUnauthorized(String rejected) { assertEquals("old", rejected); refresh.incrementAndGet(); return "new"; }
            };
            var transport = new StreamableHttpTransport(url, Map.of(), provider, McpProtocolVersion.MODERN);
            transport.send(new ObjectMapper().readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\"}}"));
            transport.close();
            var first = server.takeRequest(); var retry = server.takeRequest();
            assertEquals("Bearer old", first.getHeader("Authorization"));
            assertEquals("Bearer new", retry.getHeader("Authorization"));
            for (var request : new okhttp3.mockwebserver.RecordedRequest[]{first, retry}) {
                assertEquals("2026-07-28", request.getHeader("MCP-Protocol-Version"));
                assertEquals("tools/call", request.getHeader("Mcp-Method"));
                assertEquals("echo", request.getHeader("Mcp-Name"));
                assertNull(request.getHeader("Mcp-Session-Id"));
            }
            assertEquals(first.getBody().readUtf8(), retry.getBody().readUtf8());
            assertEquals(1, refresh.get()); assertEquals(1, resourceChecks.get()); assertEquals(2, server.getRequestCount());
        }
    }
}
