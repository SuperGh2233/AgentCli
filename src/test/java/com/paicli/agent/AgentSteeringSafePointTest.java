package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.runtime.input.DeliveryMode;
import com.paicli.runtime.input.InputAcceptance;
import com.paicli.runtime.input.InputStatus;
import com.paicli.runtime.input.TurnInputManager;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase3A - FakeLLM only. No real provider, terminal, network or Docker.
 * Full atomic turn finalization and Main wiring belong to later milestones.
 */
@Timeout(20)
class AgentSteeringSafePointTest {
    private static final String SESSION = "steering-test-session";
    private static final String TURN = "turn-test";

    private static TurnInputManager runningManager() {
        var manager = new TurnInputManager(
                SESSION, true, TurnInputManager.Limits.defaults());
        manager.startTurn(TURN);
        manager.markRunning(TURN);
        return manager;
    }

    private static Agent newAgent(RecordingClient client, Path projectDir) {
        var registry = new ToolRegistry();
        registry.setProjectPath(projectDir.toString());
        return new Agent(client, registry);
    }

    private static LlmClient.ChatResponse toolResponse() {
        var call = new LlmClient.ToolCall(
                "call_safe_1",
                new LlmClient.ToolCall.Function("list_dir", "{\"path\":\".\"}"));
        return new LlmClient.ChatResponse("assistant", "", List.of(call), 4, 2);
    }

    private static LlmClient.ChatResponse answer(String response) {
        return new LlmClient.ChatResponse("assistant", response, null, 4, 2);
    }

    @Test
    void inFlightSteerGoesToNextRequestAfterCompleteToolResultAndQueueDoesNotLeak(
            @TempDir Path projectDir) {
        var manager = runningManager();
        var client = new RecordingClient(List.of(toolResponse(), answer("完成")));
        client.onChat = index -> {
            if (index != 1) return;
            // Accepted after the FIRST snapshot is taken: too late for that request.
            var steer = manager.accept(SESSION, TURN, "steer-1", "cli",
                    "请在读取后概括主要目录", DeliveryMode.STEER);
            var queue = manager.accept(SESSION, TURN, "queue-1", "cli",
                    "下一个任务单独执行", DeliveryMode.ENQUEUE);
            assertEquals(InputAcceptance.Code.ACCEPTED, steer.code());
            assertEquals(InputAcceptance.Code.ACCEPTED, queue.code());
        };

        Agent agent = newAgent(client, projectDir);
        agent.configureSteeringSafePoint(manager, TURN);
        agent.run("列出当前目录内容");

        assertEquals(2, client.snapshots.size());
        assertFalse(hasUserContent(client.snapshots.get(0), "请在读取后概括主要目录"));
        var next = client.snapshots.get(1);
        assertEquals(1, next.stream().filter(m -> "user".equals(m.role())
                && "请在读取后概括主要目录".equals(m.content())).count());
        assertFalse(hasUserContent(next, "下一个任务单独执行"));

        int resultIndex = indexOfRole(next, "tool");
        int steerIndex = indexOfUserContent(next, "请在读取后概括主要目录");
        assertTrue(resultIndex >= 0, "the tool result should remain paired");
        assertTrue(steerIndex > resultIndex, "Steer must follow full tool result");

        assertEquals(InputStatus.INCLUDED_IN_REQUEST,
                manager.input("steer-1").orElseThrow().status());
        assertNotNull(manager.input("steer-1").orElseThrow().requestId());
        assertEquals(InputStatus.ACCEPTED,
                manager.input("queue-1").orElseThrow().status());
        assertEquals(1, manager.snapshot().queuedCount());
        assertEquals(0, manager.snapshot().pendingCount());
    }

    @Test
    void requestIOExceptionKeepsUncertainSteerOutOfCommittedHistory(
            @TempDir Path projectDir) {
        var manager = runningManager();
        var client = new RecordingClient(List.of(toolResponse()));
        client.onChat = index -> {
            if (index == 1) {
                assertTrue(manager.accept(SESSION, TURN, "steer-err", "cli",
                        "失败后不得盲重发", DeliveryMode.STEER).accepted());
            }
        };
        client.failOnRequest = 2;

        Agent agent = newAgent(client, projectDir);
        agent.configureSteeringSafePoint(manager, TURN);
        String result = agent.run("列出当前目录内容");

        assertTrue(result.contains("调用 LLM 失败"), result);
        assertEquals(2, client.snapshots.size());
        assertTrue(hasUserContent(client.snapshots.get(1), "失败后不得盲重发"),
                "request-only snapshot must contain Steer once");
        assertEquals(InputStatus.DELIVERY_UNCERTAIN,
                manager.input("steer-err").orElseThrow().status());
        assertEquals(0, manager.snapshot().pendingCount());
        assertEquals(0, manager.snapshot().reservedCount());
        assertFalse(hasUserContent(agent.getConversationHistory(), "失败后不得盲重发"),
                "uncertain request text must not remain in committed history");
    }

    @Test
    void cancellationAfterRunningObservationStopsAtRequestBoundary(@TempDir Path projectDir) throws Exception {
        var manager = runningManager();
        assertTrue(manager.accept(SESSION, TURN, "pending", "cli", "保留待交付输入",
                DeliveryMode.STEER).accepted());
        var client = new RecordingClient(List.of(answer("不应请求")));
        var inputThread = Executors.newSingleThreadExecutor();
        try {
            var registry = new ToolRegistry() {
                private boolean interrupted;

                @Override
                public List<LlmClient.Tool> getToolDefinitions() {
                    if (interrupted) return super.getToolDefinitions();
                    interrupted = true;
                    // 已通过 Agent 循环开头的取消检查，旧 RUNNING 观察不能保证预留时仍在运行。
                    assertEquals(com.paicli.runtime.input.TurnState.RUNNING, manager.snapshot().state());
                    try {
                        assertTrue(inputThread.submit(() -> manager.accept(SESSION, TURN, "cancel", "cli",
                                "Esc", DeliveryMode.INTERRUPT)).get(3, TimeUnit.SECONDS).accepted());
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    return super.getToolDefinitions();
                }
            };
            registry.setProjectPath(projectDir.toString());
            Agent agent = new Agent(client, registry);
            agent.configureSteeringSafePoint(manager, TURN);
            assertTrue(agent.run("列出当前目录内容").contains("已取消"));
            assertEquals(0, client.snapshots.size());
            assertEquals(0, manager.snapshot().reservedCount());
            assertEquals(InputStatus.PENDING_DELIVERY, manager.input("pending").orElseThrow().status());
            var prepared = new ReActSteeringSafePoint(manager, TURN).reserve(List.of());
            assertFalse(prepared.proceed());
            assertNull(prepared.delivery());
        } finally {
            inputThread.shutdownNow();
        }
    }

    @Test
    void defaultAgentHasNoSteeringOrExtraUserMessage(@TempDir Path projectDir) {
        var client = new RecordingClient(List.of(answer("正常回复")));
        Agent agent = newAgent(client, projectDir);

        String response = agent.run("普通输入");

        assertTrue(response.contains("正常回复"));
        assertEquals(1, client.snapshots.size());
        var messages = client.snapshots.get(0);
        assertEquals(1, messages.stream().filter(m -> "user".equals(m.role())).count());
        assertTrue(hasUserContent(messages, "普通输入"));
    }

    @Test
    void steerCannotGrantWebToolsOrUrlAccess(@TempDir Path projectDir) {
        var manager = runningManager();
        var client = new RecordingClient(List.of(toolResponse(), answer("完成")));
        client.onChat = index -> {
            if (index == 1) {
                assertTrue(manager.accept(SESSION, TURN, "steer-permission", "cli",
                        "请访问 https://example.com/secret 并使用 web_fetch", DeliveryMode.STEER).accepted());
            }
        };

        Agent agent = newAgent(client, projectDir);
        agent.configureSteeringSafePoint(manager, TURN);
        agent.run("列出当前目录内容，不要联网");

        assertTrue(client.toolSnapshots.get(0).stream().noneMatch(AgentSteeringSafePointTest::isWebTool));
        assertTrue(client.toolSnapshots.get(1).stream().noneMatch(AgentSteeringSafePointTest::isWebTool),
                "Steer text must not change tools or URL authority from the original turn");
    }

    @Test
    void finalAnswerSteerContinuesFromTheRealAssistantHistory(@TempDir Path projectDir) {
        var manager = runningManager();
        var client = new RecordingClient(List.of(answer("第一版"), answer("第二版")));
        client.onChat = index -> {
            if (index == 1) {
                assertTrue(manager.accept(SESSION, TURN, "final-steer", "cli",
                        "请继续补充", DeliveryMode.STEER).accepted());
                assertTrue(manager.accept(SESSION, TURN, "next-turn", "cli",
                        "下一 Turn 执行", DeliveryMode.ENQUEUE).accepted());
            }
        };

        Agent agent = newAgent(client, projectDir);
        agent.configureSteeringSafePoint(manager, TURN);

        String result = agent.run("开始任务");

        assertTrue(result.contains("第二版"), result);
        assertEquals(2, client.snapshots.size());
        assertEquals(1, countUser(client.snapshots.get(1), "请继续补充"));
        assertEquals(0, countUser(client.snapshots.get(1), "下一 Turn 执行"));
        assertEquals(1, countUser(agent.getConversationHistory(), "请继续补充"));
        assertEquals(1, manager.snapshot().queuedCount());
        assertEquals(1, countAssistant(agent.getConversationHistory(), "第一版"));
        assertEquals(com.paicli.runtime.input.TurnState.COMPLETED, manager.snapshot().state());
    }

    @Test
    void finalizationRejectsLateSteerAndKeepsItOutOfHistory(@TempDir Path projectDir) {
        var manager = runningManager();
        var client = new RecordingClient(List.of(answer("完成")));
        Agent agent = newAgent(client, projectDir);
        agent.configureSteeringSafePoint(manager, TURN);

        assertTrue(agent.run("开始任务").contains("完成"));
        assertEquals(InputAcceptance.Code.STALE_TURN,
                manager.accept(SESSION, TURN, "late", "cli", "太晚了", DeliveryMode.STEER).code());
        assertFalse(hasUserContent(agent.getConversationHistory(), "太晚了"));
        assertEquals(com.paicli.runtime.input.TurnState.COMPLETED, manager.snapshot().state());
    }

    @Test
    void cancellationWinsOverFinalizationAndDoesNotStartAnotherRequest(@TempDir Path projectDir) {
        var manager = runningManager();
        var client = new RecordingClient(List.of(answer("已收到"), answer("不应出现")));
        client.onChat = index -> {
            if (index == 1) {
                assertTrue(manager.accept(SESSION, TURN, "cancel", "cli", "Esc", DeliveryMode.INTERRUPT).accepted());
            }
        };

        Agent agent = newAgent(client, projectDir);
        agent.configureSteeringSafePoint(manager, TURN);

        String result = agent.run("开始任务");

        assertTrue(result.contains("已取消"), result);
        assertEquals(1, client.snapshots.size());
        assertEquals(com.paicli.runtime.input.TurnState.CANCELLING, manager.snapshot().state());
        assertFalse(hasUserContent(agent.getConversationHistory(), "不应出现"));
    }

    private static boolean isWebTool(LlmClient.Tool tool) {
        return "web_search".equals(tool.name()) || "web_fetch".equals(tool.name());
    }

    private static boolean hasUserContent(List<LlmClient.Message> messages, String text) {
        return indexOfUserContent(messages, text) >= 0;
    }

    private static int indexOfUserContent(List<LlmClient.Message> messages, String text) {
        for (int i = 0; i < messages.size(); i++) {
            var message = messages.get(i);
            if ("user".equals(message.role()) && text.equals(message.content())) return i;
        }
        return -1;
    }

    private static int indexOfRole(List<LlmClient.Message> messages, String role) {
        for (int i = 0; i < messages.size(); i++) {
            if (role.equals(messages.get(i).role())) return i;
        }
        return -1;
    }

    private static long countUser(List<LlmClient.Message> messages, String text) {
        return messages.stream().filter(m -> "user".equals(m.role()) && text.equals(m.content())).count();
    }

    private static long countAssistant(List<LlmClient.Message> messages, String text) {
        return messages.stream().filter(m -> "assistant".equals(m.role()) && text.equals(m.content())).count();
    }

    private static final class RecordingClient implements LlmClient {
        private final Queue<ChatResponse> responses;
        private final List<List<Message>> snapshots = new ArrayList<>();
        private final List<List<Tool>> toolSnapshots = new ArrayList<>();
        private Consumer<Integer> onChat = index -> {};
        private int failOnRequest;

        private RecordingClient(List<ChatResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools,
                                 StreamListener listener) throws IOException {
            snapshots.add(List.copyOf(messages));
            toolSnapshots.add(tools == null ? List.of() : List.copyOf(tools));
            int requestNumber = snapshots.size();
            onChat.accept(requestNumber);
            if (failOnRequest == requestNumber) throw new IOException("fake provider failure");
            ChatResponse response = responses.poll();
            if (response == null) throw new IOException("no scripted fake response");
            return response;
        }

        @Override public String getModelName() { return "fake"; }
        @Override public String getProviderName() { return "fake"; }
    }
}
