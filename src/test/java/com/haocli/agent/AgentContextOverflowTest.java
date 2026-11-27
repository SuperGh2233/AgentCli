package com.haocli.agent;

import com.haocli.agent.event.AgentEvent;
import com.haocli.llm.LlmClient;
import com.haocli.memory.HistoryTrimmer;
import com.haocli.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 模型报上下文超长：紧急压缩一次后重试；再次超长返回部分完成。 */
class AgentContextOverflowTest {

    private static final String OVERFLOW = "API请求失败: 400 - This model's maximum context length is 131072 tokens";

    @TempDir
    Path tempDir;

    private String oldMemoryDir;
    private String oldHarness;

    @BeforeEach
    void isolate() throws IOException {
        oldMemoryDir = System.getProperty("haocli.memory.dir");
        oldHarness = System.getProperty(HarnessFeatures.PROPERTY);
        System.setProperty("haocli.memory.dir", tempDir.resolve("memory").toString());
        // 关掉落盘，让大文件内容原样进对话，紧急压缩才有东西可减。
        System.setProperty(HarnessFeatures.PROPERTY, "context.offload");
        Files.writeString(tempDir.resolve("a.txt"), "a".repeat(20_000));
        Files.writeString(tempDir.resolve("b.txt"), "b".repeat(20_000));
    }

    @AfterEach
    void restore() {
        restoreProperty("haocli.memory.dir", oldMemoryDir);
        restoreProperty(HarnessFeatures.PROPERTY, oldHarness);
    }

    @Test
    void emergencyCompactionThenRetrySucceeds() {
        QueueClient llm = new QueueClient(List.of(
                read("c1", "a.txt"), read("c2", "b.txt"),
                new IOException(OVERFLOW),
                new LlmClient.ChatResponse("assistant", "读完了", null, 10, 2)));
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());

        String result = new Agent(llm, tools).run("读一下两个文件");

        assertEquals(4, llm.requests.size(), "超长那次之后重试了一次");
        assertFalse(result.contains("部分完成"), result);
        List<LlmClient.Message> retry = llm.requests.get(3);
        assertTrue(retry.stream().anyMatch(m -> m.content() != null && m.content().contains(HistoryTrimmer.MARKER)),
                "重试的请求里较早的大结果已被裁剪");
        assertEquals(llm.requests.get(2).size(), retry.size(), "只替换内容，不删消息");
    }

    @Test
    void secondOverflowReturnsPartial() {
        QueueClient llm = new QueueClient(List.of(
                read("c1", "a.txt"), read("c2", "b.txt"),
                new IOException(OVERFLOW), new IOException(OVERFLOW)));
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());

        String result = new Agent(llm, tools).run("读一下两个文件");

        assertEquals(4, llm.requests.size(), "只紧急压缩一次");
        assertTrue(result.contains("部分完成") && result.contains("上下文超出模型上限"), result);
    }

    @Test
    void emergencyCompactionEmitsLayerStatsAndPersistentOverflowReportsReason() {
        QueueClient llm = new QueueClient(List.of(
                read("c1", "a.txt"), read("c2", "b.txt"),
                new IOException(OVERFLOW), new IOException(OVERFLOW)));
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, tools);
        List<AgentEvent> events = new ArrayList<>();
        agent.setEventListener(events::add);

        agent.run("读一下两个文件");

        List<AgentEvent.ContextCompacted> compactions = events.stream()
                .filter(AgentEvent.ContextCompacted.class::isInstance)
                .map(AgentEvent.ContextCompacted.class::cast)
                .toList();
        assertEquals(1, compactions.size(), events.toString());
        AgentEvent.ContextCompacted emergency = compactions.get(0);
        assertEquals("emergency", emergency.trigger());
        assertTrue(emergency.trimmed() > 0, "紧急压缩先做不调模型的裁剪");
        assertTrue(emergency.beforeTokens() > emergency.afterTokens(),
                "压缩后估算 Token 要变小: " + emergency);
        AgentEvent.TurnFinished finished = (AgentEvent.TurnFinished) events.get(events.size() - 1);
        assertEquals(AgentEvent.TurnStatus.PARTIAL, finished.status());
        assertEquals("context_overflow", finished.reason());
    }

    @Test
    void failedTurnCarriesClassifiedReason() {
        javax.net.ssl.SSLHandshakeException handshake =
                new javax.net.ssl.SSLHandshakeException("Remote host terminated the handshake");
        QueueClient llm = new QueueClient(List.of(handshake));
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());
        Agent agent = new Agent(llm, tools);
        List<AgentEvent> events = new ArrayList<>();
        agent.setEventListener(events::add);

        agent.run("你好");

        AgentEvent.TurnFinished finished = (AgentEvent.TurnFinished) events.get(events.size() - 1);
        assertEquals(AgentEvent.TurnStatus.FAILED, finished.status());
        assertEquals("llm_transient", finished.reason(), "网络抖动要和 Agent 自身的问题分开");
    }

    @Test
    void otherErrorsStillFailImmediately() {
        QueueClient llm = new QueueClient(List.of(new IOException("API请求失败: 401 - invalid api key")));
        ToolRegistry tools = new ToolRegistry();
        tools.setProjectPath(tempDir.toString());

        String result = new Agent(llm, tools).run("你好");

        assertEquals(1, llm.requests.size());
        assertTrue(result.contains("调用 LLM 失败"), result);
    }

    private static LlmClient.ChatResponse read(String id, String path) {
        LlmClient.ToolCall call = new LlmClient.ToolCall(id,
                new LlmClient.ToolCall.Function("read_file", "{\"path\":\"" + path + "\"}"));
        return new LlmClient.ChatResponse("assistant", "", List.of(call), 10, 2);
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static final class QueueClient implements LlmClient {
        private final Queue<Object> responses;
        private final List<List<Message>> requests = new ArrayList<>();

        private QueueClient(List<Object> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            requests.add(List.copyOf(messages));
            Object next = responses.poll();
            if (next == null) {
                throw new IOException("缺少预设响应");
            }
            if (next instanceof IOException error) {
                throw error;
            }
            return (ChatResponse) next;
        }

        @Override
        public String getModelName() {
            return "test-model";
        }

        @Override
        public String getProviderName() {
            return "test";
        }

        @Override
        public int maxContextWindow() {
            return 256_000;
        }
    }
}
