package com.paicli.mcp.jsonrpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.mcp.transport.McpTransport;
import com.paicli.mcp.protocol.McpProtocolVersion;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public class JsonRpcClient implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int METHOD_NOT_FOUND = -32601;

    private final McpTransport transport;
    private final McpProtocolVersion protocolVersion;
    private final AtomicLong ids = new AtomicLong(1);
    private final ConcurrentHashMap<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "paicli-mcp-jsonrpc");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Consumer<JsonNode>> notificationListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private volatile String disconnectReason;
    private volatile java.util.function.Supplier<java.util.Set<String>> supportedElicitationModes = java.util.Set::of;

    public void setSupportedElicitationModes(java.util.function.Supplier<java.util.Set<String>> supplier) {
        supportedElicitationModes = supplier == null ? java.util.Set::of : supplier;
    }

    private java.util.Set<String> currentElicitationModes() {
        try {
            var modes = supportedElicitationModes.get();
            return modes == null ? java.util.Set.of() : java.util.Set.copyOf(modes);
        } catch (RuntimeException e) {
            return java.util.Set.of();
        }
    }

    public JsonRpcClient(McpTransport transport) {
        this(transport, McpProtocolVersion.LEGACY);
    }

    public JsonRpcClient(McpTransport transport, McpProtocolVersion protocolVersion) {
        this.protocolVersion = java.util.Objects.requireNonNull(protocolVersion);
        this.transport = transport;
        this.transport.onReceive(this::handleMessage);
        this.transport.onUnexpectedClose(this::failPending);
    }

    /** server 进程退出后，正在等回复的请求立即失败，不再干等超时。 */
    private void failPending(String reason) {
        disconnectReason = reason;
        IOException error = new IOException(reason);
        pending.keySet().forEach(id -> {
            CompletableFuture<JsonNode> future = pending.remove(id);
            if (future != null) {
                future.completeExceptionally(error);
            }
        });
    }

    public boolean hasPendingRequests() {
        return !pending.isEmpty();
    }

    public JsonNode request(String method, JsonNode params) throws IOException {
        return request(method, params, DEFAULT_TIMEOUT_SECONDS);
    }

    public JsonNode request(String method, JsonNode params, long timeoutSeconds) throws IOException {
        return request(method, params, TimeUnit.SECONDS.toMillis(timeoutSeconds), TimeUnit.MILLISECONDS);
    }

    public JsonNode request(String method, JsonNode params, long timeout, TimeUnit unit) throws IOException {
        return requestInternal(method, params, timeout, unit, false);
    }

    /** Only modern business operations can enter the MRTR loop. */
    public JsonNode requestWithInputs(String method, JsonNode params) throws IOException {
        if (!protocolVersion.modern() || !java.util.Set.of("tools/call", "resources/read", "prompts/get").contains(method))
            throw new IOException("MRTR 不支持此协议或操作");
        return requestInternal(method, params, DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, true);
    }

    private JsonNode requestInternal(String method, JsonNode params, long timeout, TimeUnit unit,
                                    boolean allowInput) throws IOException {
        if (disconnectReason != null) {
            throw new IOException(disconnectReason);
        }
        params = protocolVersion.decorate(params, allowInput ? currentElicitationModes() : java.util.Set.of());
        long timeoutMillis = unit.toMillis(timeout);
        long id = ids.getAndIncrement();
        ObjectNode request = MAPPER.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        if (params != null) {
            request.set("params", params);
        }

        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        scheduler.schedule(() -> {
            CompletableFuture<JsonNode> removed = pending.remove(id);
            if (removed != null) {
                removed.completeExceptionally(new TimeoutException("JSON-RPC request timed out: " + method));
            }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        // 注册 pending 和进程退出之间有竞争窗口，放进去之后再检查一次
        if (disconnectReason != null) {
            pending.remove(id);
            throw new IOException(disconnectReason);
        }

        try {
            transport.send(request);
            JsonNode result = future.get(timeoutMillis + 1000, TimeUnit.MILLISECONDS);
            if (protocolVersion.modern()) {
                String type = result == null ? "" : result.path("resultType").asText("");
                if ("input_required".equals(type) && allowInput) return result;
                if ("input_required".equals(type)) {
                    throw new IOException("MCP 2026-07-28 input_required：此请求入口不支持 MRTR 补充输入");
                }
                if (!"complete".equals(type)) {
                    throw new IOException("MCP 2026-07-28 协议响应无效：resultType 必须是 complete 或 input_required");
                }
            }
            return result;
        } catch (JsonRpcException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            if (e.getCause() instanceof JsonRpcException jsonRpcException) {
                throw jsonRpcException;
            }
            if (e.getCause() instanceof IOException ioException) {
                throw ioException;
            }
            throw new IOException(e.getMessage(), e);
        }
    }

    public void sendNotification(String method, JsonNode params) throws IOException {
        params = protocolVersion.decorate(params);
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        if (params != null) {
            notification.set("params", params);
        }
        transport.send(notification);
    }

    public void onNotification(Consumer<JsonNode> listener) {
        if (listener != null) {
            notificationListeners.add(listener);
        }
    }

    /**
     * 先按有没有 method 区分方向：带 method 的是 server 发来的请求或通知，不带的才是我们请求的回复。
     * 不能先拿 id 去 pending 里找：server 的请求 id 也从 1 开始，会和我们自己的请求撞号。
     */
    private void handleMessage(JsonNode message) {
        if (message.hasNonNull("method")) {
            if (protocolVersion.modern()) return;
            JsonNode idNode = message.get("id");
            if (idNode == null || idNode.isNull()) {
                for (Consumer<JsonNode> listener : notificationListeners) {
                    listener.accept(message);
                }
            } else {
                // 回复放到别的线程发，避免在 stdout 读线程里阻塞写 stdin
                try {
                    scheduler.execute(() -> replyToServerRequest(idNode, message.path("method").asText()));
                } catch (RejectedExecutionException ignored) {
                    // client 已关闭，不再回复
                }
            }
            return;
        }
        Long id = responseId(message.get("id"));
        if (id == null) {
            return;
        }
        CompletableFuture<JsonNode> future = pending.remove(id);
        if (future == null) {
            return;
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            future.completeExceptionally(new JsonRpcException(
                    error.path("code").asInt(-32603),
                    error.path("message").asText("JSON-RPC error")));
            return;
        }
        future.complete(message.get("result"));
    }

    /** 我们发出的 id 都是数字；个别 server 会把它回成数字字符串，其他形式一律不认。 */
    private static Long responseId(JsonNode idNode) {
        if (idNode == null || idNode.isNull()) {
            return null;
        }
        if (idNode.isIntegralNumber()) {
            return idNode.asLong();
        }
        if (idNode.isTextual() && idNode.asText().matches("-?\\d{1,18}")) {
            return Long.parseLong(idNode.asText());
        }
        return null;
    }

    private void replyToServerRequest(JsonNode idNode, String method) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", idNode);
        if ("ping".equals(method)) {
            response.putObject("result");
        } else {
            ObjectNode error = response.putObject("error");
            error.put("code", METHOD_NOT_FOUND);
            error.put("message", "Method not found: " + method);
        }
        try {
            transport.send(response);
        } catch (IOException ignored) {
            // transport 已关闭或断开，server 那边会自己超时
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        transport.close();
    }
}
