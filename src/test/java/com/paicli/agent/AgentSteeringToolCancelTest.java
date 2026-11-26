package com.paicli.agent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.llm.LlmClient;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.runtime.input.DeliveryMode;
import com.paicli.runtime.input.TurnInputManager;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class AgentSteeringToolCancelTest {
    private static final String TOOL = "mcp__fake__dangerous";

    private static TurnInputManager manager() {
        var manager = new TurnInputManager("session", true, TurnInputManager.Limits.defaults());
        manager.startTurn("turn");
        manager.markRunning("turn");
        return manager;
    }

    private static void interrupt(TurnInputManager manager) {
        assertTrue(manager.accept("session", "turn", "esc", "test", "Esc",
                DeliveryMode.INTERRUPT).accepted());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "latch timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static ToolRegistry registry(Path project, AtomicInteger starts, Runnable firstTool) {
        var registry = new ToolRegistry();
        registry.setProjectPath(project.toString());
        var schema = JsonNodeFactory.instance.objectNode().put("type", "object");
        registry.registerMcpToolOutput(new McpToolDescriptor("fake", "dangerous", TOOL, "fake", schema), args -> {
            if (starts.incrementAndGet() == 1) firstTool.run();
            return ToolOutput.text("real-result");
        });
        return registry;
    }

    private static final class FakeClient implements LlmClient {
        private final List<ToolCall> calls;
        private final Runnable onFirstResponse;
        private int requests;

        FakeClient(List<String> ids, Runnable onFirstResponse) {
            calls = ids.stream().map(id -> new ToolCall(id, new ToolCall.Function(TOOL, "{}"))).toList();
            this.onFirstResponse = onFirstResponse;
        }

        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            return chat(messages, tools, StreamListener.NO_OP);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            if (++requests == 1) {
                onFirstResponse.run();
                return new ChatResponse("assistant", "", calls, 4, 2);
            }
            return new ChatResponse("assistant", "done", null, 4, 2);
        }
        @Override public String getModelName() { return "fake"; }
        @Override public String getProviderName() { return "fake"; }
    }

    private static List<LlmClient.Message> results(Agent agent) {
        return agent.getConversationHistory().stream().filter(m -> "tool".equals(m.role())).toList();
    }

    @Test
    void interruptWhileFirstToolRunsKeepsRealResultAndSkipsQueuedTool(@TempDir Path project) throws Exception {
        var manager = manager();
        var starts = new AtomicInteger();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var agent = new Agent(new FakeClient(List.of("first", "second"), () -> {}),
                registry(project, starts, () -> { started.countDown(); await(release); }));
        agent.configureSteeringSafePoint(manager, "turn");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var run = executor.submit(() -> agent.run("执行两个测试工具"));
            await(started);
            interrupt(manager);
            release.countDown();
            assertTrue(run.get(5, TimeUnit.SECONDS).contains("已取消"));
            assertEquals(1, starts.get(), "queued dangerous tool must not start after INTERRUPT");
            var results = results(agent);
            assertEquals(List.of("first", "second"), results.stream().map(LlmClient.Message::toolCallId).toList());
            assertTrue(results.get(0).content().contains("real-result"));
            assertTrue(results.get(1).content().contains("未启动"));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptBeforeFirstClaimStartsNoToolsButKeepsBothResults(@TempDir Path project) {
        var manager = manager();
        var starts = new AtomicInteger();
        var agent = new Agent(new FakeClient(List.of("first", "second"), () -> interrupt(manager)),
                registry(project, starts, () -> {}));
        agent.configureSteeringSafePoint(manager, "turn");
        assertTrue(agent.run("执行两个测试工具").contains("已取消"));
        assertEquals(0, starts.get());
        assertEquals(List.of("first", "second"), results(agent).stream().map(LlmClient.Message::toolCallId).toList());
        assertTrue(results(agent).stream().allMatch(m -> m.content().contains("未启动")));
    }

    @Test
    void repeatedInvocationCannotStartAgainAndDefaultAgentKeepsOldPath(@TempDir Path project) {
        for (boolean steering : List.of(true, false)) {
            var starts = new AtomicInteger();
            var agent = new Agent(new FakeClient(List.of("same", "same"), () -> {}),
                    registry(project, starts, () -> {}));
            if (steering) agent.configureSteeringSafePoint(manager(), "turn");
            assertTrue(agent.run("执行测试工具").contains("done"));
            assertEquals(steering ? 1 : 2, starts.get());
            assertEquals(2, results(agent).size());
        }
    }
}
