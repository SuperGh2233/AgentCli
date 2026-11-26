package com.paicli.runtime.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.agent.event.AgentEvent;
import com.paicli.headless.UnattendedPermissions;
import com.paicli.headless.UnattendedTaskRunner;
import com.paicli.testing.ScriptedLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.paicli.testing.ScriptedLlmClient.answer;
import static com.paicli.testing.ScriptedLlmClient.call;
import static com.paicli.testing.ScriptedLlmClient.toolCalls;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeApiServerTest {

    @Test
    void exposesThreadTurnAndSseEvents(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, prompt -> "reply:" + prompt, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();

            HttpResponse<String> created = client.send(request(base + "/v1/threads", "POST", "")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, created.statusCode());
            String threadId = extract(created.body(), "thread_");

            HttpResponse<String> turn = client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                            "{\"input\":\"hello\"}").build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(202, turn.statusCode());

            String events = waitForEvents(client, base, threadId);
            assertTrue(events.contains("event: turn.started"));
            assertTrue(events.contains("event: message.delta"));
            assertTrue(events.contains("reply:hello"));
            assertTrue(events.contains("event: turn.completed"));
        }
    }

    @Test
    void streamsTheSameAgentEventsAsHeadlessMode(@TempDir Path tempDir) throws Exception {
        Path project = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(project.resolve("README.md"), "hello");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("c1", "read_file", "{\"path\":\"README.md\"}")),
                answer("第一行\t带制表符")).streamingContent();
        UnattendedTaskRunner runner = new UnattendedTaskRunner(() -> llm, project, UnattendedPermissions.READ_ONLY, null);

        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, runner, 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            String threadId = extract(client.send(request(base + "/v1/threads", "POST", "").build(),
                    HttpResponse.BodyHandlers.ofString()).body(), "thread_");
            String turnId = extract(client.send(request(base + "/v1/threads/" + threadId + "/turns", "POST",
                    "{\"input\":\"读一下 README\"}").build(), HttpResponse.BodyHandlers.ofString()).body(), "turn_");

            List<String[]> events = parseSse(waitForEvents(client, base, threadId));
            List<String> names = events.stream().map(e -> e[0]).toList();
            assertInOrder(names, "turn.started", "agent.turn_started", "agent.model_request_started",
                    "agent.tool_call_started", "agent.tool_call_finished", "agent.model_request_started",
                    "agent.text_delta", "agent.usage", "agent.turn_finished", "message.delta", "turn.completed");

            ObjectMapper mapper = new ObjectMapper();
            for (String[] event : events) {
                JsonNode data = mapper.readTree(event[1]); // 每条 data 都必须是合法 JSON
                if (event[0].startsWith("agent.")) {
                    assertEquals(turnId, data.path("turn_id").asText(), "agent 事件带 Runtime API 的 turn_id");
                    assertEquals(event[0].substring("agent.".length()), data.path("event").path("type").asText());
                }
                if (event[0].equals("agent.tool_call_finished")) {
                    assertTrue(data.path("event").path("success").asBoolean(), event[1]);
                }
                if (event[0].equals("agent.turn_finished")) {
                    assertEquals("completed", data.path("event").path("status").asText());
                }
                if (event[0].equals("message.delta")) {
                    assertEquals("第一行\t带制表符", data.path("content").asText(), "控制字符要正确转义");
                }
            }
        }
    }

    @Test
    void consecutiveDeltasAreMergedBeforeTheyAreStored(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"))) {
            String threadId = store.createThread();
            RuntimeApiServer.TurnEventWriter writer = new RuntimeApiServer.TurnEventWriter(store, threadId, "turn_x");

            writer.onEvent(new AgentEvent.TurnStarted("t1"));
            for (int i = 0; i < 600; i++) {
                writer.onEvent(new AgentEvent.TextDelta("a"));
            }
            writer.onEvent(new AgentEvent.ReasoningDelta("想"));
            writer.onEvent(new AgentEvent.ReasoningDelta("一想"));
            writer.onEvent(new AgentEvent.TextDelta("尾巴"));
            List<String> beforeFlush = agentEvents(store, threadId);
            writer.flush();

            List<String> stored = agentEvents(store, threadId);
            assertEquals(List.of(
                    "agent.turn_started:",
                    "agent.text_delta:" + "a".repeat(RuntimeApiServer.TurnEventWriter.MERGE_LIMIT),
                    "agent.text_delta:" + "a".repeat(600 - RuntimeApiServer.TurnEventWriter.MERGE_LIMIT),
                    "agent.reasoning_delta:想一想",
                    "agent.text_delta:尾巴"), stored);
            assertEquals(stored.subList(0, 4), beforeFlush, "最后一段增量要等 flush 才写入");
        }
    }

    private static List<String> agentEvents(RuntimeThreadStore store, String threadId) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        List<String> result = new ArrayList<>();
        for (RuntimeEvent event : store.events(threadId, 0)) {
            if (event.type().startsWith("agent.")) {
                result.add(event.type() + ":" + mapper.readTree(event.data()).path("event").path("text").asText(""));
            }
        }
        return result;
    }

    /** 每个元素是 {事件名, data}。 */
    private static List<String[]> parseSse(String body) {
        List<String[]> events = new ArrayList<>();
        for (String block : body.split("\n\n")) {
            String name = null;
            String data = null;
            for (String line : block.split("\n")) {
                if (line.startsWith("event: ")) {
                    name = line.substring("event: ".length());
                } else if (line.startsWith("data: ")) {
                    data = line.substring("data: ".length());
                }
            }
            if (name != null) {
                events.add(new String[]{name, data});
            }
        }
        return events;
    }

    /** expected 按顺序出现在 actual 里（中间可以夹别的事件）。 */
    private static void assertInOrder(List<String> actual, String... expected) {
        int from = 0;
        for (String name : expected) {
            int index = actual.subList(from, actual.size()).indexOf(name);
            assertTrue(index >= 0, "缺少或顺序不对: " + name + " in " + actual);
            from += index + 1;
        }
    }

    @Test
    void rejectsMissingApiKey(@TempDir Path tempDir) throws Exception {
        try (RuntimeThreadStore store = new RuntimeThreadStore(tempDir.resolve("runtime.db"));
             RuntimeApiServer server = new RuntimeApiServer(store, prompt -> "x", 0, "secret")) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/v1/threads"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(3))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(401, response.statusCode());
        }
    }

    private static HttpRequest.Builder request(String url, String method, String body) {
        HttpRequest.BodyPublisher publisher = body == null || body.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body);
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(3))
                .header("Authorization", "Bearer secret")
                .header("Content-Type", "application/json")
                .method(method, publisher);
    }

    private static String waitForEvents(HttpClient client, String base, String threadId) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos(); // 真 Agent 第一次启动要加载提示词等，留足时间
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = client.send(request(base + "/v1/threads/" + threadId + "/events", "GET", "")
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.body().contains("turn.completed")) {
                return response.body();
            }
            Thread.sleep(30);
        }
        fail("events did not complete");
        return "";
    }

    private static String extract(String body, String prefix) {
        int start = body.indexOf(prefix);
        assertTrue(start >= 0, body);
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }
}
