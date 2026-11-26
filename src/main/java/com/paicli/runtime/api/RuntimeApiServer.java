package com.paicli.runtime.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.agent.event.AgentEvent;
import com.paicli.agent.event.AgentEventListener;
import com.paicli.headless.AgentEventJson;
import com.paicli.runtime.task.TaskRunner;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本机 Runtime API：threads / turns / events 三个端点，事件按 SSE 格式回放。
 *
 * <p>一轮执行时，除了原来的 {@code turn.started}、{@code message.delta}、{@code turn.completed} / {@code turn.failed}，
 * 还把 Agent 的每个事件写成 {@code agent.<type>}（比如 {@code agent.tool_call_started}），
 * {@code data} 是 {@code {"turn_id":…,"event":{…}}}，{@code event} 和无界面模式 stream-json 的一行相同。
 */
public class RuntimeApiServer implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RuntimeThreadStore store;
    private final TaskRunner runner;
    private final String apiKey;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "paicli-runtime-api");
        thread.setDaemon(true);
        return thread;
    });

    public RuntimeApiServer(RuntimeThreadStore store, TaskRunner runner, int port, String apiKey) throws IOException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("Runtime API 需要配置 PAICLI_RUNTIME_API_KEY 或 -Dpaicli.runtime.api.key");
        }
        this.store = store;
        this.runner = runner;
        this.apiKey = apiKey;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.server.createContext("/v1/threads", this::handleThreads);
        this.server.setExecutor(executor);
    }

    public static String configuredApiKey() {
        String configured = System.getProperty("paicli.runtime.api.key");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("PAICLI_RUNTIME_API_KEY");
        }
        return configured;
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handleThreads(HttpExchange exchange) throws IOException {
        try {
            if (!authorized(exchange)) {
                writeJson(exchange, 401, "{\"error\":\"unauthorized\"}");
                return;
            }
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(method) && "/v1/threads".equals(path)) {
                String id = store.createThread();
                writeJson(exchange, 200, json("id", id, "object", "thread"));
                return;
            }
            if ("POST".equals(method) && path.matches("/v1/threads/[^/]+/turns")) {
                handleTurn(exchange, threadId(path));
                return;
            }
            if ("GET".equals(method) && path.matches("/v1/threads/[^/]+/events")) {
                handleEvents(exchange, threadId(path));
                return;
            }
            writeJson(exchange, 404, "{\"error\":\"not_found\"}");
        } catch (Exception e) {
            writeJson(exchange, 500, json("error", Objects.toString(e.getMessage(), "")));
        }
    }

    private void handleTurn(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, "{\"error\":\"thread_not_found\"}");
            return;
        }
        JsonNode body = MAPPER.readTree(exchange.getRequestBody());
        String input = body.path("input").asText("");
        if (input.isBlank()) {
            writeJson(exchange, 400, "{\"error\":\"input_required\"}");
            return;
        }
        String turnId = "turn_" + Long.toHexString(System.nanoTime());
        store.appendEvent(threadId, "turn.started", json("turn_id", turnId, "input", input));
        executor.submit(() -> runTurn(threadId, turnId, input));
        writeJson(exchange, 202, json("id", turnId, "object", "turn", "status", "running"));
    }

    private void runTurn(String threadId, String turnId, String input) {
        TurnEventWriter agentEvents = new TurnEventWriter(store, threadId, turnId);
        try {
            String result = runner.run(input, agentEvents);
            agentEvents.flush();
            store.appendEvent(threadId, "message.delta",
                    json("turn_id", turnId, "content", Objects.toString(result, "")));
            store.appendEvent(threadId, "turn.completed", json("turn_id", turnId, "status", "completed"));
        } catch (Exception e) {
            agentEvents.flush();
            store.appendEvent(threadId, "turn.failed",
                    json("turn_id", turnId, "error", Objects.toString(e.getMessage(), "")));
        }
    }

    /**
     * 把一轮的 Agent 事件写进 thread 的事件流。思考 / 正文增量一个 token 一条，每条都写库太慢，
     * 所以连续的同类增量先攒着，攒满 {@link #MERGE_LIMIT} 个字符或来了别的事件时合并成一条写入。
     */
    static final class TurnEventWriter implements AgentEventListener {
        static final int MERGE_LIMIT = 512;

        private final RuntimeThreadStore store;
        private final String threadId;
        private final String turnId;
        private final StringBuilder pending = new StringBuilder();
        /** 正在攒的增量是哪一类；null 表示没有。 */
        private Class<? extends AgentEvent> pendingType;

        TurnEventWriter(RuntimeThreadStore store, String threadId, String turnId) {
            this.store = store;
            this.threadId = threadId;
            this.turnId = turnId;
        }

        @Override
        public synchronized void onEvent(AgentEvent event) {
            String delta = event instanceof AgentEvent.TextDelta text ? text.text()
                    : event instanceof AgentEvent.ReasoningDelta reasoning ? reasoning.text() : null;
            if (delta == null) {
                flush();
                write(event);
                return;
            }
            if (pendingType != null && pendingType != event.getClass()) {
                flush();
            }
            pendingType = event.getClass();
            pending.append(delta);
            if (pending.length() >= MERGE_LIMIT) {
                flush();
            }
        }

        /** 把攒着的增量写出去；一轮结束时调用方也要调一次，免得最后一段丢掉。 */
        synchronized void flush() {
            if (pendingType == null) {
                return;
            }
            String text = pending.toString();
            AgentEvent merged = pendingType == AgentEvent.TextDelta.class
                    ? new AgentEvent.TextDelta(text)
                    : new AgentEvent.ReasoningDelta(text);
            pendingType = null;
            pending.setLength(0);
            write(merged);
        }

        private void write(AgentEvent event) {
            ObjectNode payload = AgentEventJson.toJson(event);
            ObjectNode data = MAPPER.createObjectNode();
            data.put("turn_id", turnId);
            data.set("event", payload);
            store.appendEvent(threadId, "agent." + payload.path("type").asText("unknown"), data.toString());
        }
    }

    private void handleEvents(HttpExchange exchange, String threadId) throws IOException {
        if (!store.exists(threadId)) {
            writeJson(exchange, 404, "{\"error\":\"thread_not_found\"}");
            return;
        }
        long after = parseAfter(exchange.getRequestURI().getQuery());
        List<RuntimeEvent> events = store.events(threadId, after);
        byte[] body = formatSse(events).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        String direct = exchange.getRequestHeaders().getFirst("X-PaiCLI-API-Key");
        return ("Bearer " + apiKey).equals(auth) || apiKey.equals(direct);
    }

    private static String threadId(String path) {
        String[] parts = path.split("/");
        return parts.length >= 4 ? parts[3] : "";
    }

    private static long parseAfter(String query) {
        if (query == null || query.isBlank()) {
            return 0;
        }
        for (String part : query.split("&")) {
            if (part.startsWith("after=")) {
                try {
                    return Long.parseLong(part.substring("after=".length()));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    private static String formatSse(List<RuntimeEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (RuntimeEvent event : events) {
            sb.append("id: ").append(event.id()).append('\n');
            sb.append("event: ").append(event.type()).append('\n');
            sb.append("data: ").append(event.data()).append("\n\n");
        }
        return sb.toString();
    }

    private static void writeJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** 用成对的键和值拼一个 JSON 对象；交给 Jackson 转义，回答里有制表符等控制字符也是合法 JSON。 */
    private static String json(String... keysAndValues) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            node.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return node.toString();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
