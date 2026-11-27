package com.haocli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.haocli.llm.core.ContentBlock;
import com.haocli.llm.core.LegacyMessageCodec;
import com.haocli.llm.core.LegacyModelClient;
import com.haocli.llm.core.ModelResponse;
import com.haocli.llm.core.StopReason;
import com.haocli.llm.core.StreamEvent;
import com.haocli.llm.core.TokenUsage;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 升级计划第 1 阶段的结束条件：同一段供应商流式响应，分别走旧接口 {@link LlmClient#chat} 和新接口
 * {@link LegacyModelClient#stream}，发出去的请求和拿到的结果必须一致；新接口额外给出停止原因和结构化事件。
 * 覆盖 DeepSeek（含正文里的 DSML 工具调用）和 GLM 两个 Client。只用本地 MockWebServer，不访问真实模型。
 */
@Timeout(20)
class ModelClientReplayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DSML_BLOCK = """
            <｜｜DSML｜｜ calls>
            <｜｜DSML｜｜ invoke name="list_dir">
            <｜｜DSML｜｜ parameter name="path" string="true">demo</｜｜DSML｜｜ parameter>
            </｜｜DSML｜｜ invoke>
            </｜｜DSML｜｜ calls>""";

    // ---- 场景 ----

    @Test
    void deepSeekReasoningAnswerIsIdenticalOnBothInterfaces() throws Exception {
        String sse = sse(reasoning("先想"), reasoning("一下"), content("答案"), content("是 42"),
                finish("stop"), usage(50, 8));
        Replay replay = replay(url -> new DeepSeekClient("test-key", "deepseek-v4-pro", url), sse);

        assertEquals(StopReason.END_TURN, replay.response.stopReason());
        assertEquals("stop", replay.response.rawFinishReason());
        assertEquals(new TokenUsage(50, 8, 0, true), replay.response.usage());
        assertEquals("先想一下", replay.response.message().reasoning());
        assertEquals("答案是 42", replay.response.message().text());
        assertEquals(List.of("R:先想", "R:一下", "T:答案", "T:是 42"), replay.newDeltas);
        assertTail(replay.events, StreamEvent.UsageReported.class, StreamEvent.Completed.class);
    }

    @Test
    void deepSeekStreamedToolCallsAreIdenticalAndEmittedAsCompletedEvents() throws Exception {
        String sse = sse(
                toolCall(0, "call_a", "read_file", "{\"path\""),
                toolCall(0, null, null, ":\"a.txt\"}"),
                toolCall(1, "call_b", "list_dir", "{\"path\":\".\"}"),
                finish("tool_calls"));
        Replay replay = replay(url -> new DeepSeekClient("test-key", "deepseek-v4-pro", url), sse);

        assertEquals(StopReason.TOOL_USE, replay.response.stopReason());
        List<StreamEvent.ToolCallCompleted> calls = replay.events.stream()
                .filter(StreamEvent.ToolCallCompleted.class::isInstance)
                .map(StreamEvent.ToolCallCompleted.class::cast)
                .toList();
        assertEquals(2, calls.size());
        assertEquals(new StreamEvent.ToolCallCompleted(0,
                new ContentBlock.ToolCall("call_a", "read_file", "{\"path\":\"a.txt\"}")), calls.get(0));
        assertEquals(1, calls.get(1).index());
        assertEquals("list_dir", calls.get(1).call().name());
        assertFalse(replay.response.usage().reported(), "供应商没给用量就不能当成 0");
        assertTail(replay.events, StreamEvent.ToolCallCompleted.class, StreamEvent.Completed.class);
    }

    @Test
    void deepSeekDsmlToolCallIsReportedAsToolUseOnTheNewInterface() throws Exception {
        String sse = sse(content("我来列一下目录。\n"), content(DSML_BLOCK), finish("stop"));
        Replay replay = replay(url -> new DeepSeekClient("test-key", "deepseek-flash", url), sse);

        assertEquals("stop", replay.response.rawFinishReason());
        assertEquals(StopReason.TOOL_USE, replay.response.stopReason(),
                "线上是 stop，但 DSML 转出了工具调用，新接口要报 TOOL_USE");
        assertEquals(1, replay.response.message().toolCalls().size());
        assertEquals("list_dir", replay.response.message().toolCalls().get(0).name());
        assertTrue(replay.newDeltas.stream().noneMatch(delta -> delta.contains("DSML")),
                "DSML 原文不能作为正文流给界面: " + replay.newDeltas);
    }

    @Test
    void glmTruncatedToolCallIsReportedAsMaxTokens() throws Exception {
        String sse = sse(reasoning("要读文件"), toolCall(0, "call_x", "read_file", "{\"path\":\"a"),
                finish("length"), usage(900, 4096));
        Replay replay = replay(url -> new GLMClient("test-key", "glm-5.1", url), sse);

        assertEquals(StopReason.MAX_TOKENS, replay.response.stopReason(),
                "被长度截断时工具参数是半截 JSON，不能当成正常的工具调用");
        assertTrue(replay.response.message().hasToolCalls());
        assertEquals(new TokenUsage(900, 4096, 0, true), replay.response.usage());
    }

    @Test
    void glmPlainAnswerIsIdenticalOnBothInterfaces() throws Exception {
        String sse = sse(content("你好"), finish("stop"), usage(10, 2));
        Replay replay = replay(url -> new GLMClient("test-key", "glm-5.1", url), sse);

        assertEquals(StopReason.END_TURN, replay.response.stopReason());
        assertEquals(List.of("T:你好"), replay.newDeltas);
    }

    @Test
    void withoutEventConsumerStopReasonIsInferred() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sseResponse(sse(toolCall(0, "call_1", "list_dir", "{}"), finish("tool_calls"))));
            LegacyModelClient client = new LegacyModelClient(
                    new DeepSeekClient("test-key", "deepseek-v4-pro", server.url("/chat/completions").toString()));

            ModelResponse response = client.stream(LegacyMessageCodec.toChatMessages(history()),
                    LegacyMessageCodec.toToolSpecs(tools()), null);

            assertEquals(StopReason.TOOL_USE, response.stopReason());
            assertNull(response.rawFinishReason(), "不传事件回调时拿不到供应商原始停止原因");
        }
    }

    // ---- 回放工具 ----

    private interface ClientFactory {
        LlmClient create(String url);
    }

    private record Replay(ModelResponse response, List<String> newDeltas, List<StreamEvent> events) {
    }

    /**
     * 同一段 SSE 先走旧接口、再走新接口，断言两次请求体相同、回复相同、流式文字相同，返回新接口的结果供进一步断言。
     */
    private static Replay replay(ClientFactory factory, String sse) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sseResponse(sse));
            server.enqueue(sseResponse(sse));
            String url = server.url("/chat/completions").toString();

            List<String> legacyDeltas = new ArrayList<>();
            LlmClient legacyClient = factory.create(url);
            LlmClient.ChatResponse legacy = legacyClient.chat(history(), tools(), new LlmClient.StreamListener() {
                @Override
                public void onReasoningDelta(String delta) {
                    legacyDeltas.add("R:" + delta);
                }

                @Override
                public void onContentDelta(String delta) {
                    legacyDeltas.add("T:" + delta);
                }
            });
            JsonNode legacyRequest = MAPPER.readTree(server.takeRequest().getBody().readUtf8());

            List<StreamEvent> events = new ArrayList<>();
            List<String> newDeltas = new ArrayList<>();
            LegacyModelClient modelClient = new LegacyModelClient(factory.create(url));
            ModelResponse response = modelClient.stream(
                    LegacyMessageCodec.toChatMessages(history()),
                    LegacyMessageCodec.toToolSpecs(tools()),
                    event -> {
                        events.add(event);
                        if (event instanceof StreamEvent.ReasoningDelta r) {
                            newDeltas.add("R:" + r.text());
                        } else if (event instanceof StreamEvent.TextDelta t) {
                            newDeltas.add("T:" + t.text());
                        }
                    });
            JsonNode newRequest = MAPPER.readTree(server.takeRequest().getBody().readUtf8());

            assertEquals(legacyRequest, newRequest, "新旧接口发给供应商的请求体必须相同");
            assertEquals(legacy, LegacyMessageCodec.toChatResponse(response), "新接口的结果转回旧格式应和旧接口相同");
            assertEquals(legacyDeltas, newDeltas, "流式文字必须相同");
            StreamEvent last = events.get(events.size() - 1);
            assertEquals(response, assertInstanceOf(StreamEvent.Completed.class, last).response());
            assertEquals(1, events.stream().filter(StreamEvent.Completed.class::isInstance).count());
            return new Replay(response, newDeltas, events);
        }
    }

    /** 带思考内容、工具调用和工具结果的历史，用来确认请求转换（含 reasoning_content 回传）不变。 */
    private static List<LlmClient.Message> history() {
        return List.of(
                LlmClient.Message.system("你是编码助手"),
                LlmClient.Message.user("看看 a.txt"),
                LlmClient.Message.assistant("先读文件", "", List.of(new LlmClient.ToolCall("call_0",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"a.txt\"}")))),
                LlmClient.Message.tool("call_0", "文件内容:\nhello"),
                LlmClient.Message.user("继续"));
    }

    private static List<LlmClient.Tool> tools() {
        ObjectNode schema = MAPPER.createObjectNode().put("type", "object");
        schema.putObject("properties").putObject("path").put("type", "string");
        return List.of(
                new LlmClient.Tool("read_file", "读文件", schema),
                new LlmClient.Tool("list_dir", "列目录", schema));
    }

    @SafeVarargs
    private static void assertTail(List<StreamEvent> events, Class<? extends StreamEvent>... expected) {
        assertTrue(events.size() >= expected.length, events.toString());
        for (int i = 0; i < expected.length; i++) {
            assertInstanceOf(expected[i], events.get(events.size() - expected.length + i), events.toString());
        }
    }

    private static MockResponse sseResponse(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    private static String sse(String... payloads) {
        StringBuilder sb = new StringBuilder();
        for (String payload : payloads) {
            sb.append("data: ").append(payload).append("\n\n");
        }
        return sb.append("data: [DONE]\n\n").toString();
    }

    private static String content(String text) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("choices").addObject().putObject("delta").put("content", text);
        return root.toString();
    }

    private static String reasoning(String text) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("choices").addObject().putObject("delta").put("reasoning_content", text);
        return root.toString();
    }

    private static String toolCall(int index, String id, String name, String argumentsDelta) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode calls = root.putArray("choices").addObject().putObject("delta").putArray("tool_calls");
        ObjectNode call = calls.addObject().put("index", index);
        if (id != null) {
            call.put("id", id);
        }
        ObjectNode function = call.putObject("function");
        if (name != null) {
            function.put("name", name);
        }
        function.put("arguments", argumentsDelta);
        return root.toString();
    }

    private static String finish(String reason) {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.putObject("delta");
        choice.put("finish_reason", reason);
        return root.toString();
    }

    private static String usage(int prompt, int completion) {
        ObjectNode root = MAPPER.createObjectNode();
        root.putArray("choices");
        root.putObject("usage").put("prompt_tokens", prompt).put("completion_tokens", completion);
        return root.toString();
    }
}
