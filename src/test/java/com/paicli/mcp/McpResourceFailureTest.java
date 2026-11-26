package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.config.McpConfigLoader;
import com.paicli.mcp.config.McpServerConfig;
import com.paicli.mcp.config.McpTrustStore;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.resources.McpResourceTool;
import com.paicli.mcp.transport.McpPidFile;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Actual manager registration path, using only in-memory JSON-RPC. */
class McpResourceFailureTest {
    @TempDir Path temp;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void normalResourcesPreserveTextAndSuccess() throws Exception {
        for (var descriptor : McpResourceTool.descriptors("demo")) {
            try (var client = new McpClient("demo", transport(false, false))) {
                String expected = McpResourceTool.invoker(client, descriptor).apply(arguments());
                var result = execute(client, descriptor, arguments());
                assertTrue(result.successful());
                assertEquals(expected, result.result());
                assertTrue(result.result().contains("repo://doc"));
            }
        }
    }

    @Test
    void ioExceptionsAreStructuredFailures() throws Exception {
        for (var descriptor : McpResourceTool.descriptors("demo")) {
            try (var client = new McpClient("demo", transport(true, false))) {
                var result = execute(client, descriptor, arguments());
                assertFalse(result.successful(), descriptor.name());
                assertEquals("MCP resource 工具调用失败 (demo/" + descriptor.name() + "): offline failure", result.result());
            }
        }
    }

    @Test
    void rpcErrorsAreStructuredFailures() throws Exception {
        for (var descriptor : McpResourceTool.descriptors("demo")) {
            try (var client = new McpClient("demo", transport(false, true))) {
                var result = execute(client, descriptor, arguments());
                assertFalse(result.successful(), descriptor.name());
                assertTrue(result.result().contains("offline error"));
            }
        }
    }

    @Test
    void missingUriPreservesErrorTextButFails() throws Exception {
        var descriptor = McpResourceTool.descriptors("demo").get(1);
        try (var client = new McpClient("demo", transport(false, false))) {
            var result = execute(client, descriptor, "{\"uri\":\" \"}");
            assertFalse(result.successful());
            assertEquals("读取 MCP resource 失败: 缺少必填参数 uri", result.result());
            assertEquals(result.result(), McpResourceTool.invoker(client, descriptor).apply("{}"));
        }
    }

    @Test
    void regularToolIsErrorPassesThroughManagerAndRegistry() throws Exception {
        var schema = JSON.readTree("{\"type\":\"object\"}");
        var descriptor = new McpToolDescriptor("demo", "echo", "mcp__demo__echo", "", schema);
        try (var client = new McpClient("demo", transport(false, false))) {
            var result = execute(client, descriptor, arguments());
            assertFalse(result.successful());
            assertEquals("MCP 工具返回错误: server failure", result.result());
        }
    }

    private String arguments() { return "{\"uri\":\"repo://doc\"}"; }

    private ToolRegistry.ToolExecutionResult execute(McpClient client, McpToolDescriptor descriptor, String args) throws Exception {
        ToolRegistry registry = new ToolRegistry();
        try (var manager = new McpServerManager(registry, temp,
                new McpConfigLoader(temp.resolve("user.json"), temp.resolve("project.json"), temp),
                new McpTrustStore(temp.resolve("trust.json")), new McpPidFile(temp.resolve("pids.json")))) {
            var replace = McpServerManager.class.getDeclaredMethod("replaceTools", McpServer.class, McpClient.class, List.class);
            replace.setAccessible(true);
            replace.invoke(manager, new McpServer("demo", new McpServerConfig()), client, List.of(descriptor));
            return registry.executeTools(List.of(new ToolRegistry.ToolInvocation("call", descriptor.namespacedName(), args))).get(0);
        }
    }

    private McpTransport transport(boolean throwsIo, boolean rpcError) {
        return new McpTransport() {
            Consumer<JsonNode> receiver;
            public void onReceive(Consumer<JsonNode> listener) { receiver = listener; }
            public void close() { }
            public void send(JsonNode request) throws IOException {
                if (throwsIo) throw new IOException("offline failure");
                var response = JSON.createObjectNode().put("jsonrpc", "2.0");
                response.set("id", request.get("id"));
                if (rpcError) {
                    response.putObject("error").put("code", -32603).put("message", "offline error");
                } else {
                    String body = switch (request.path("method").asText()) {
                        case "resources/list" -> "{\"resources\":[{\"uri\":\"repo://doc\",\"name\":\"doc\"}]}";
                        case "resources/read" -> "{\"contents\":[{\"uri\":\"repo://doc\",\"text\":\"error: valid resource data\"}]}";
                        case "tools/call" -> "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"server failure\"}]}";
                        default -> throw new AssertionError("Unexpected request: " + request);
                    };
                    response.set("result", JSON.readTree(body));
                }
                receiver.accept(response);
            }
        };
    }
}
