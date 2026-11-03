package com.paicli.mcp.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.protocol.McpInitializeRequest;
import com.paicli.mcp.protocol.McpProtocolVersion;
import com.paicli.mcp.oauth.McpTokenProvider;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class StreamableHttpTransport implements McpTransport {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    private static final OkHttpClient DEFAULT_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build();
    private static final OkHttpClient OAUTH_CLIENT = DEFAULT_CLIENT.newBuilder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private final McpProtocolVersion protocolVersion;
    private final OkHttpClient client;
    private final String url;
    private final McpTokenProvider tokens;
    private final Map<String, String> headers;
    private final List<Consumer<JsonNode>> listeners = new CopyOnWriteArrayList<>();
    private volatile String sessionId;
    private volatile boolean closed;

    public StreamableHttpTransport(String url, Map<String, String> headers) {
        this(url, headers, null);
    }

    public StreamableHttpTransport(String url, Map<String, String> headers, McpTokenProvider tokens) {
        this(url, headers, tokens, McpProtocolVersion.LEGACY);
    }

    public StreamableHttpTransport(String url, Map<String, String> headers, McpTokenProvider tokens,
                                   McpProtocolVersion protocolVersion) {
        this.protocolVersion = java.util.Objects.requireNonNull(protocolVersion);
        if (tokens != null && headers != null && headers.keySet().stream().anyMatch("Authorization"::equalsIgnoreCase))
            throw new IllegalArgumentException("OAuth 不能与静态 Authorization 同时配置");
        this.tokens = tokens;
        this.client = tokens == null ? DEFAULT_CLIENT : OAUTH_CLIENT;
        this.url = url;
        this.headers = headers == null ? Map.of() : Map.copyOf(headers);
    }

    @Override
    public void send(JsonNode message) throws IOException {
        if (closed) throw new IOException("MCP HTTP transport 已关闭");
        RequestBody body = RequestBody.create(MAPPER.writeValueAsString(message), JSON);
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .post(body);
        headers.forEach(builder::header);
        if (protocolVersion.modern()) {
            builder.header("MCP-Protocol-Version", protocolVersion.value());
            builder.removeHeader("Mcp-Session-Id");
            String method = message.path("method").asText("");
            builder.header("Mcp-Method", method);
            builder.removeHeader("Mcp-Name");
            String nameField = switch (method) {
                case "tools/call", "prompts/get" -> "name";
                case "resources/read" -> "uri";
                default -> null;
            };
            JsonNode principal = nameField == null ? null : message.path("params").get(nameField);
            if (principal != null && principal.isTextual() && !principal.textValue().isBlank()) {
                builder.header("Mcp-Name", principal.textValue());
            }
        }
        if (!protocolVersion.modern() && sessionId != null && !sessionId.isBlank()) {
            builder.header("Mcp-Session-Id", sessionId);
        }

        try (Response response = execute(builder)) {
            String newSession = response.header("Mcp-Session-Id");
            if (!protocolVersion.modern() && newSession != null && !newSession.isBlank()) {
                sessionId = newSession;
            }
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code()
                        + (tokens != null && response.code() == 403 ? "：权限不足，请检查 oauth.scopes 与服务端授权；不会自动重试" : ""));
            }
            ResponseBody responseBody = response.body();
            if (responseBody == null) {
                return;
            }
            String contentType = response.header("Content-Type", "");
            String raw = responseBody.string();
            // notification 路径下 server 可以返回 202 + 空 body 或 200 + 空 body。
            // 这里 swallow 空响应，避免 Jackson 对空字符串抛 MismatchedInputException。
            if (raw == null || raw.isBlank()) {
                return;
            }
            List<JsonNode> messages = contentType.contains("text/event-stream")
                    ? parseSse(raw)
                    : List.of(MAPPER.readTree(raw));
            for (JsonNode node : messages) {
                for (Consumer<JsonNode> listener : listeners) {
                    listener.accept(node);
                }
            }
        }
    }

    private Response execute(Request.Builder builder) throws IOException {
        String access = null;
        if (tokens != null) {
            tokens.validateResource(url);
            access = tokens.accessToken();
            builder.header("Authorization", "Bearer " + access);
        }
        Response response = client.newCall(builder.build()).execute();
        if (response.code() == 401 && access != null) {
            response.close();
            String refreshed = tokens.refreshAfterUnauthorized(access);
            builder.header("Authorization", "Bearer " + refreshed);
            response = client.newCall(builder.build()).execute();
        }
        if (tokens != null && response.code() == 401) {
            response.close();
            throw new IOException("OAuth 认证失败，请重新 /mcp login <name>");
        }
        return response;
    }

    @Override
    public void onReceive(Consumer<JsonNode> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public String transportName() {
        return "http";
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (protocolVersion.modern() || sessionId == null || sessionId.isBlank()) {
            return;
        }
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("MCP-Protocol-Version", McpInitializeRequest.PROTOCOL_VERSION)
                .header("Mcp-Session-Id", sessionId)
                .delete();
        headers.forEach(builder::header);
        if (tokens != null) {
            try {
                tokens.validateResource(url);
                builder.header("Authorization", "Bearer " + tokens.currentToken());
            } catch (IOException e) { sessionId = null; return; }
        }
        // close 是 best-effort：server 已经关停 / 网络不通时不应该让 PaiCLI 退出卡住。
        // 主 client 的 callTimeout 是 60s，这里用 5s 短超时单独发请求。
        OkHttpClient closeClient = client.newBuilder()
                .callTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .connectTimeout(2, TimeUnit.SECONDS)
                .build();
        try (Response ignored = closeClient.newCall(builder.build()).execute()) {
            // best effort
        } catch (IOException ignored) {
        } finally { sessionId = null; }
    }

    private static List<JsonNode> parseSse(String raw) throws IOException {
        List<JsonNode> messages = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : raw.split("\\R")) {
            if (line.isBlank()) {
                if (!data.isEmpty()) {
                    messages.add(MAPPER.readTree(data.toString()));
                    data.setLength(0);
                }
                continue;
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring("data:".length()).trim());
            }
        }
        if (!data.isEmpty()) {
            messages.add(MAPPER.readTree(data.toString()));
        }
        return messages;
    }
}
