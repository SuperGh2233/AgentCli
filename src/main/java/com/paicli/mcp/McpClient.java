package com.paicli.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.jsonrpc.JsonRpcClient;
import com.paicli.mcp.jsonrpc.JsonRpcException;
import com.paicli.mcp.protocol.McpCallToolRequest;
import com.paicli.mcp.protocol.McpCallToolResult;
import com.paicli.mcp.protocol.McpInitializeRequest;
import com.paicli.mcp.protocol.McpProtocolVersion;
import com.paicli.mcp.protocol.McpSchemaSanitizer;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.mcp.elicitation.ElicitationRequest;
import com.paicli.mcp.elicitation.ElicitationResponse;
import com.paicli.mcp.elicitation.McpElicitationHandler;
import com.paicli.mcp.resources.McpResourceContent;
import com.paicli.mcp.resources.McpResourceDescriptor;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.tool.ToolOutput;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class McpClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_INITIALIZE_TIMEOUT_SECONDS = 60;
    private static final String INITIALIZE_TIMEOUT_PROPERTY = "paicli.mcp.initialize.timeout.seconds";
    private static final String INITIALIZE_TIMEOUT_ENV = "PAICLI_MCP_INITIALIZE_TIMEOUT_SECONDS";

    private final String serverName;
    private final McpProtocolVersion protocolVersion;
    private final JsonRpcClient rpc;
    private final McpTransport transport;
    private volatile McpElicitationHandler elicitationHandler = McpElicitationHandler.CANCEL;
    private volatile JsonNode serverCapabilities = JsonNodeFactory.instance.objectNode();

    public McpClient(String serverName, McpTransport transport) {
        this(serverName, transport, McpProtocolVersion.LEGACY);
    }

    public McpClient(String serverName, McpTransport transport, McpProtocolVersion protocolVersion) {
        this.protocolVersion = java.util.Objects.requireNonNull(protocolVersion);
        this.serverName = serverName;
        this.transport = transport;
        this.rpc = new JsonRpcClient(transport, protocolVersion);
        this.rpc.setSupportedElicitationModes(() -> elicitationHandler.supportedModes());
    }

    public boolean isModern() { return protocolVersion.modern(); }

    /** Set by McpServerManager; used by the modern MRTR request loop. */
    public void setElicitationHandler(McpElicitationHandler handler) {
        elicitationHandler = handler == null ? McpElicitationHandler.CANCEL : handler;
    }

    public ElicitationResponse elicit(ElicitationRequest request) {
        ElicitationResponse response = elicitationHandler.elicit(request);
        return response == null ? ElicitationResponse.cancel() : response;
    }

    public void initialize() throws IOException {
        if (isModern()) {
            JsonNode result = rpc.request("server/discover", JsonNodeFactory.instance.objectNode(), initializeTimeoutSeconds());
            JsonNode versions = result.path("supportedProtocolVersions");
            boolean supported = false;
            if (versions.isArray()) {
                for (JsonNode version : versions) {
                    if (version.isTextual() && protocolVersion.value().equals(version.textValue())) supported = true;
                }
            }
            if (!supported) throw new IOException("MCP server/discover 未声明支持固定版本 2026-07-28；不会自动降级，请检查服务端或 protocolVersion 配置");
            serverCapabilities = result.path("capabilities");
            return;
        }
        JsonNode result = rpc.request("initialize", McpInitializeRequest.toJson(), initializeTimeoutSeconds());
        serverCapabilities = result == null ? JsonNodeFactory.instance.objectNode() : result.path("capabilities");
        rpc.sendNotification("notifications/initialized", JsonNodeFactory.instance.objectNode());
    }

    static int initializeTimeoutSeconds() {
        String configured = System.getProperty(INITIALIZE_TIMEOUT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(INITIALIZE_TIMEOUT_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
        try {
            int seconds = Integer.parseInt(configured.trim());
            return seconds > 0 ? seconds : DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_INITIALIZE_TIMEOUT_SECONDS;
        }
    }

    public boolean supportsResources() {
        return serverCapabilities.has("resources");
    }

    public boolean supportsPrompts() {
        return serverCapabilities.has("prompts");
    }

    public List<McpToolDescriptor> listTools() throws IOException {
        JsonNode result = rpc.request("tools/list", JsonNodeFactory.instance.objectNode(), 30);
        JsonNode tools = result.path("tools");
        if (!tools.isArray()) {
            return List.of();
        }
        List<McpToolDescriptor> descriptors = new ArrayList<>();
        for (JsonNode tool : tools) {
            String name = tool.path("name").asText("");
            if (name.isBlank()) {
                continue;
            }
            String description = tool.path("description").asText("");
            JsonNode rawSchema = tool.path("inputSchema");
            JsonNode schema = McpSchemaSanitizer.sanitize(rawSchema);
            descriptors.add(new McpToolDescriptor(
                    serverName,
                    name,
                    McpToolDescriptor.namespaced(serverName, name),
                    description,
                    schema,
                    rawSchema.isObject() ? rawSchema : null
            ));
        }
        return descriptors;
    }

    public String callTool(String toolName, String argumentsJson) throws IOException {
        return callToolOutput(toolName, argumentsJson).text();
    }

    public ToolOutput callToolOutput(String toolName, String argumentsJson) throws IOException {
        JsonNode args;
        if (argumentsJson == null || argumentsJson.isBlank()) {
            args = JsonNodeFactory.instance.objectNode();
        } else {
            args = MAPPER.readTree(argumentsJson);
        }
        ObjectNode params = McpCallToolRequest.toJson(toolName, args);
        JsonNode result = requestWithInputs("tools/call", params);
        McpCallToolResult callResult = MAPPER.treeToValue(result, McpCallToolResult.class);
        ToolOutput output = callResult.toToolOutput();
        if (callResult.isError()) {
            return ToolOutput.failure("MCP 工具返回错误: " + output.text(), output.imageParts());
        }
        return output;
    }

    public List<McpResourceDescriptor> listResources() throws IOException {
        try {
            JsonNode result = rpc.request("resources/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode resources = result.path("resources");
            if (!resources.isArray()) {
                return List.of();
            }
            List<McpResourceDescriptor> descriptors = new ArrayList<>();
            for (JsonNode resource : resources) {
                McpResourceDescriptor descriptor = McpResourceDescriptor.fromJson(serverName, resource);
                if (descriptor != null) {
                    descriptors.add(descriptor);
                }
            }
            return descriptors;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {
                return List.of();
            }
            throw e;
        }
    }

    public List<McpResourceContent> readResource(String uri) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        JsonNode result = requestWithInputs("resources/read", params);
        JsonNode contents = result.path("contents");
        if (!contents.isArray()) {
            return List.of();
        }
        List<McpResourceContent> resourceContents = new ArrayList<>();
        for (JsonNode content : contents) {
            McpResourceContent resourceContent = McpResourceContent.fromJson(content);
            if (resourceContent != null) {
                resourceContents.add(resourceContent);
            }
        }
        return resourceContents;
    }

    public JsonNode getPrompt(String name, JsonNode arguments) throws IOException {
        ObjectNode params = JsonNodeFactory.instance.objectNode().put("name", name);
        if (arguments != null) params.set("arguments", arguments.deepCopy());
        return requestWithInputs("prompts/get", params);
    }

    private static final int MAX_INPUT_ROUNDS = 4;
    private static final int MAX_INPUT_REQUESTS = 8;
    private static final int MAX_INPUT_MAP_CHARS = 65536;

    private JsonNode requestWithInputs(String method, ObjectNode original) throws IOException {
        if (!isModern()) return rpc.request(method, original, 60);
        ObjectNode params = original.deepCopy();
        for (int round = 0; ; round++) {
            JsonNode result = rpc.requestWithInputs(method, params);
            if ("complete".equals(result.path("resultType").asText())) return result;
            if (round >= MAX_INPUT_ROUNDS) throw new IOException("MRTR 超过补充输入轮数上限");
            JsonNode requests = result.get("inputRequests");
            JsonNode state = result.get("requestState");
            if ((requests == null && state == null) || (state != null && !state.isTextual()))
                throw new IOException("MRTR input_required 缺少输入或 requestState 不是字符串");
            if (requests != null && (!requests.isObject() || requests.size() > MAX_INPUT_REQUESTS
                    || requests.toString().length() > MAX_INPUT_MAP_CHARS))
                throw new IOException("MRTR inputRequests 格式错误或超过大小上限");
            List<ElicitationRequest> parsed = new ArrayList<>();
            if (requests != null) {
                var fields = requests.fields();
                try {
                    while (fields.hasNext()) {
                        var entry = fields.next();
                        parsed.add(ElicitationRequest.parse(serverName, entry.getKey(), entry.getValue()));
                    }
                } catch (IllegalArgumentException e) {
                    throw new IOException("MRTR inputRequests 包含无效或不支持的补充输入");
                }
            }
            ObjectNode responses = JsonNodeFactory.instance.objectNode();
            for (ElicitationRequest request : parsed) {
                ElicitationResponse response = elicit(request);
                if (response.action() == ElicitationResponse.Action.ACCEPT
                        && (request.mode() == ElicitationRequest.Mode.URL && response.content() != null
                        || request.mode() == ElicitationRequest.Mode.FORM && response.content() == null))
                    throw new IOException("MRTR elicitation 响应 content 与 mode 不符");
                responses.set(request.requestKey(), response.toJson());
            }
            java.util.Set<String> responseKeys = new java.util.HashSet<>();
            responses.fieldNames().forEachRemaining(responseKeys::add);
            java.util.Set<String> requestKeys = new java.util.HashSet<>();
            if (requests != null) requests.fieldNames().forEachRemaining(requestKeys::add);
            if (!responseKeys.equals(requestKeys)) throw new IOException("MRTR inputResponses key 不匹配");
            // State and answers belong only to the immediate retry; never accumulate them.
            params = original.deepCopy();
            if (requests != null) params.set("inputResponses", responses);
            if (state != null) params.set("requestState", state.deepCopy());
        }
    }

    public void subscribeResource(String uri) throws IOException {
        if (isModern()) throw new IOException("MCP 2026-07-28 不支持 resources/subscribe；现代变化通知尚未实现，请手动重新读取资源");
        ObjectNode params = JsonNodeFactory.instance.objectNode();
        params.put("uri", uri);
        rpc.request("resources/subscribe", params, 30);
    }

    public List<String> listPrompts() throws IOException {
        try {
            JsonNode result = rpc.request("prompts/list", JsonNodeFactory.instance.objectNode(), 30);
            JsonNode prompts = result.path("prompts");
            if (!prompts.isArray()) {
                return List.of();
            }
            List<String> lines = new ArrayList<>();
            for (JsonNode prompt : prompts) {
                String name = prompt.path("name").asText("");
                if (name.isBlank()) {
                    continue;
                }
                String title = prompt.path("title").asText("");
                String description = prompt.path("description").asText("");
                String display = title.isBlank() ? name : title + " (" + name + ")";
                lines.add(description.isBlank() ? display : display + " - " + description);
            }
            return lines;
        } catch (JsonRpcException e) {
            if (e.code() == -32601) {
                return List.of();
            }
            throw e;
        }
    }

    public void onNotification(Consumer<JsonNode> listener) {
        rpc.onNotification(listener);
    }

    public static String formatResources(List<McpResourceDescriptor> resources) {
        if (resources == null || resources.isEmpty()) {
            return "📭 该 MCP server 暂无 resources";
        }
        StringBuilder sb = new StringBuilder("📚 MCP resources（").append(resources.size()).append("）\n");
        for (McpResourceDescriptor resource : resources) {
            sb.append("- ").append(resource.uri());
            String name = resource.displayName();
            if (name != null && !name.isBlank() && !name.equals(resource.uri())) {
                sb.append(" | ").append(name);
            }
            if (resource.mimeType() != null && !resource.mimeType().isBlank()) {
                sb.append(" | ").append(resource.mimeType());
            }
            if (resource.description() != null && !resource.description().isBlank()) {
                sb.append("\n  ").append(resource.description());
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public static String formatResourceContents(List<McpResourceContent> contents) {
        if (contents == null || contents.isEmpty()) {
            return "📭 MCP resource 内容为空";
        }
        StringBuilder sb = new StringBuilder();
        for (McpResourceContent content : contents) {
            String mimeType = content.mimeType() == null || content.mimeType().isBlank()
                    ? "application/octet-stream"
                    : content.mimeType();
            sb.append("<resource uri=\"").append(escapeXml(content.uri()))
                    .append("\" mimeType=\"").append(escapeXml(mimeType)).append("\">\n");
            if (content.isText()) {
                sb.append(content.text());
            } else {
                sb.append("[binary resource blob omitted, base64 length=")
                        .append(content.blob() == null ? 0 : content.blob().length())
                        .append(']');
            }
            sb.append("\n</resource>\n");
        }
        return sb.toString().trim();
    }

    private static String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    /** 探活：server 回了结果或回了“不支持 ping”的错误都算活着，超时或断开才抛 IOException。 */
    public void ping(long timeoutMillis) throws IOException {
        if (isModern()) return;
        try {
            rpc.request("ping", JsonNodeFactory.instance.objectNode(), timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (JsonRpcException ignored) {
            // 回了 JSON-RPC 错误说明进程还在正常读写
        }
    }

    public boolean hasPendingRequests() {
        return rpc.hasPendingRequests();
    }

    public void onUnexpectedClose(Consumer<String> listener) {
        transport.onUnexpectedClose(listener);
    }

    public List<String> stderrLines() {
        return transport.stderrLines();
    }

    public Long processId() {
        return transport.processId();
    }

    public String transportName() {
        return transport.transportName();
    }

    @Override
    public void close() {
        // 直接走 transport-level 关闭信号：stdio 通过 stdin EOF + 进程销毁；HTTP 通过 DELETE session。
        // 之前会先发 shutdown notification，但当 server 卡死 / 队列堵塞时这条通知会让 close 阻塞 60 秒。
        // 移除后退出更快、行为更可预期；shutdown 语义改由 transport 层承担。
        rpc.close();
    }
}
