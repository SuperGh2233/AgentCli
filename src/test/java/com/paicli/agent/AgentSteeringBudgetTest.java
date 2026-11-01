package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.runtime.input.DeliveryMode;
import com.paicli.runtime.input.InputStatus;
import com.paicli.runtime.input.TurnInputManager;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
@ResourceLock("java.lang.System.properties")
class AgentSteeringBudgetTest {
    enum Outcome { SUCCESS, IO_ERROR, RUNTIME_ERROR }

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void budgetRequestDeliversToolTimeSteersExactlyOnce(Outcome outcome, @TempDir Path projectDir)
            throws Exception {
        String property = "paicli.react.hard.max.iterations";
        String previous = System.getProperty(property);
        var manager = new TurnInputManager("session", true, TurnInputManager.Limits.defaults());
        manager.startTurn("turn");
        manager.markRunning("turn");
        var toolStarted = new CountDownLatch(1);
        var releaseTool = new CountDownLatch(1);
        var registry = new ToolRegistry() {
            @Override
            public String executeTool(String name, String argumentsJson) {
                toolStarted.countDown();
                try {
                    assertTrue(releaseTool.await(3, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return "fake directory result";
            }
        };
        registry.setProjectPath(projectDir.toString());
        var client = new BudgetClient(outcome);
        var agent = new Agent(client, registry);
        agent.configureSteeringSafePoint(manager, "turn");
        var worker = Executors.newSingleThreadExecutor();
        try {
            System.setProperty(property, "1");
            var result = worker.submit(() -> agent.run("列出当前目录内容"));
            assertTrue(toolStarted.await(3, TimeUnit.SECONDS));
            for (String id : List.of("steer-a", "steer-b")) {
                assertTrue(manager.accept("session", "turn", id, "cli", id, DeliveryMode.STEER).accepted());
            }
            assertTrue(manager.accept("session", "turn", "queued", "cli", "next task",
                    DeliveryMode.ENQUEUE).accepted());
            releaseTool.countDown();
            if (outcome == Outcome.RUNTIME_ERROR) {
                var error = assertThrows(ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, error.getCause());
            } else {
                String answer = result.get(5, TimeUnit.SECONDS);
                assertTrue(answer.contains(outcome == Outcome.SUCCESS ? "部分完成" : "收尾调用失败"), answer);
            }
            assertEquals(2, client.requests.size());
            assertEquals(List.of(), client.requestTools.get(1));
            var request = client.requests.get(1);
            int toolIndex = -1;
            for (int i = 0; i < request.size(); i++) {
                if ("tool".equals(request.get(i).role())) toolIndex = i;
            }
            assertTrue(toolIndex >= 0);
            int priorIndex = toolIndex;
            for (String id : List.of("steer-a", "steer-b")) {
                assertEquals(0, count(client.requests.get(0), id));
                assertEquals(1, count(request, id));
                int index = request.indexOf(LlmClient.Message.user(id));
                assertTrue(index > priorIndex);
                priorIndex = index;
                var view = manager.input(id).orElseThrow();
                assertEquals(outcome == Outcome.SUCCESS ? InputStatus.INCLUDED_IN_REQUEST
                        : InputStatus.DELIVERY_UNCERTAIN, view.status());
                assertNotNull(view.requestId());
                assertEquals(outcome == Outcome.SUCCESS ? 1 : 0, count(agent.getConversationHistory(), id));
            }
            assertEquals(manager.input("steer-a").orElseThrow().requestId(),
                    manager.input("steer-b").orElseThrow().requestId());
            assertEquals(0, count(request, "next task"));
            assertEquals(1, manager.snapshot().queuedCount());
            assertEquals(0, manager.snapshot().pendingCount());
            assertEquals(0, manager.snapshot().reservedCount());
            assertTrue(manager.reserveForRequest("turn").isEmpty(), "不能自动重发");
        } finally {
            releaseTool.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS));
            if (previous == null) System.clearProperty(property);
            else System.setProperty(property, previous);
        }
    }

    private static long count(List<LlmClient.Message> messages, String text) {
        return messages.stream().filter(m -> "user".equals(m.role()) && text.equals(m.content())).count();
    }

    private static final class BudgetClient implements LlmClient {
        private final Outcome outcome;
        private final List<List<Message>> requests = new ArrayList<>();
        private final List<List<Tool>> requestTools = new ArrayList<>();

        private BudgetClient(Outcome outcome) { this.outcome = outcome; }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            return chat(messages, tools, StreamListener.NO_OP);
        }

        @Override
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
                throws IOException {
            requests.add(List.copyOf(messages));
            requestTools.add(tools == null ? List.of() : List.copyOf(tools));
            if (requests.size() == 1) {
                return new ChatResponse("assistant", "", List.of(new ToolCall("budget-tool",
                        new ToolCall.Function("list_dir", "{\"path\":\".\"}"))), 4, 2);
            }
            assertEquals(2, requests.size(), "收尾请求不能重试");
            if (outcome == Outcome.IO_ERROR) throw new IOException("fake budget failure");
            if (outcome == Outcome.RUNTIME_ERROR) throw new IllegalStateException("fake runtime failure");
            return new ChatResponse("assistant", "已整理工具结果", null, 4, 2);
        }

        @Override public String getModelName() { return "fake"; }
        @Override public String getProviderName() { return "fake"; }
    }
}
