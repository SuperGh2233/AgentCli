package com.paicli.agent;

import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ExecutionPlan;
import com.paicli.plan.Planner;
import com.paicli.plan.Task;
import com.paicli.runtime.input.*;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlanSteeringSafePointTest {
    @TempDir Path tempDir;
    private final PrintStream out = new PrintStream(new ByteArrayOutputStream());

    @Test
    void parallelBatchCompletesBeforeOrderedSteersReviseOnlyC() throws Exception {
        Harness h = new Harness();
        Task a = h.plan.getTask("a"), b = h.plan.getTask("b");
        CyclicBarrier barrier = new CyclicBarrier(2);
        h.client.taskReply = (messages, tools) -> {
            String task = currentTask(messages);
            if (task.equals("A") || task.equals("B")) {
                if (task.equals("A")) {
                    h.accept("one", "第一条", DeliveryMode.STEER);
                    h.accept("queue", "下轮任务", DeliveryMode.ENQUEUE);
                    h.accept("two", "第二条", DeliveryMode.STEER);
                }
                await(barrier);
                assertEquals("C", h.plan.getTask("c").getDescription());
            } else {
                assertEquals("新的 C", task);
            }
            return reply(task + " result");
        };
        h.planner.revisionCheck = () -> {
            assertEquals(Task.TaskStatus.COMPLETED, a.getStatus());
            assertEquals(Task.TaskStatus.COMPLETED, b.getStatus());
            assertEquals("A result", a.getResult());
            assertEquals("B result", b.getResult());
        };
        h.client.revisionJson = revision("c", "新的 C", "a", "b");
        assertTrue(h.run().contains("计划执行完成"));
        assertSame(a, h.plan.getTask("a"));
        assertSame(b, h.plan.getTask("b"));
        String feedback = h.planner.feedback.get(0);
        assertTrue(feedback.indexOf("第一条") < feedback.indexOf("第二条"));
        assertFalse(feedback.contains("下轮任务"));
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, h.status("one"));
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, h.status("two"));
        assertEquals(InputStatus.ACCEPTED, h.status("queue"));
        assertEquals(1, h.manager.snapshot().queuedCount());
        assertEquals(TurnState.COMPLETED, h.manager.snapshot().state());
        String ledger = Files.readString(tempDir.resolve("history/raw/steering.jsonl"));
        assertTrue(ledger.contains("runtime_steer_included"));
        assertTrue(ledger.contains("第一条"));
    }

    @Test
    void emptyRevisionsCommitAndContinueOriginalPlan() throws Exception {
        Harness h = new Harness();
        h.accept("one", "保持计划", DeliveryMode.STEER);
        assertTrue(h.run().contains("计划执行完成"));
        assertEquals("C", h.plan.getTask("c").getDescription());
        assertEquals(Task.TaskStatus.COMPLETED, h.plan.getTask("c").getStatus());
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, h.status("one"));
        assertEquals(1, h.client.revisionCalls.get());
    }

    @Test
    void clearingSafePointRestoresUnconfiguredExecution() throws Exception {
        Harness h = new Harness();
        h.accept("one", "不应读取", DeliveryMode.STEER);
        h.agent.clearPlanSteeringSafePoint();
        assertTrue(h.run().contains("计划执行完成"));
        assertEquals(0, h.client.revisionCalls.get());
        assertEquals(InputStatus.PENDING_DELIVERY, h.status("one"));
        assertEquals(TurnState.RUNNING, h.manager.snapshot().state());
    }

    @Test
    void malformedAndCyclicProposalsCommitButNeverStartOldC() throws Exception {
        for (String json : List.of("not json", revision("c", "bad", "c"),
                revision("missing", "bad"), revision("a", "rewrite completed"))) {
            Harness h = new Harness();
            Task c = h.plan.getTask("c");
            List<String> order = List.copyOf(h.plan.getExecutionOrder());
            h.accept("one", "调整 C", DeliveryMode.STEER);
            h.client.revisionJson = json;
            String result = h.run();
            assertTrue(result.contains("修订未应用"), result);
            assertSame(c, h.plan.getTask("c"));
            assertEquals("C", c.getDescription());
            assertEquals(order, h.plan.getExecutionOrder());
            assertEquals(Task.TaskStatus.PENDING, c.getStatus());
            assertEquals(2, h.client.taskCalls.get());
            assertEquals(InputStatus.INCLUDED_IN_REQUEST, h.status("one"));
            assertEquals(TurnState.FAILED, h.manager.snapshot().state());
        }
    }

    @Test
    void requestExceptionIsUncertainAndNeverRetried() throws Exception {
        Harness h = new Harness();
        h.accept("one", "调整 C", DeliveryMode.STEER);
        h.client.revisionFailure = new IOException("transport failed");
        String result = h.run();
        assertTrue(result.contains("无法确认"), result);
        assertEquals(InputStatus.DELIVERY_UNCERTAIN, h.status("one"));
        assertEquals(Task.TaskStatus.PENDING, h.plan.getTask("c").getStatus());
        assertEquals(1, h.client.revisionCalls.get());
        assertEquals(2, h.client.taskCalls.get());
        assertEquals(0, h.manager.snapshot().reservedCount());
    }

    @Test
    void nullModelResponseIsUncertainRatherThanInvalidProposal() throws Exception {
        Harness h = new Harness();
        h.accept("one", "调整 C", DeliveryMode.STEER);
        h.client.nullRevisionResponse = true;
        assertTrue(h.run().contains("无法确认"));
        assertEquals(InputStatus.DELIVERY_UNCERTAIN, h.status("one"));
        assertEquals(0, h.manager.snapshot().reservedCount());
        assertEquals(Task.TaskStatus.PENDING, h.plan.getTask("c").getStatus());
    }

    @Test
    void cancellationDuringRevisionCommitsDeliveredInputWithoutApplyingProposal() throws Exception {
        Harness h = new Harness();
        h.accept("one", "调整 C", DeliveryMode.STEER);
        h.client.revisionJson = revision("c", "新的 C", "a", "b");
        h.planner.revisionCheck = () -> h.accept("cancel", "停止", DeliveryMode.INTERRUPT);
        assertTrue(h.run().contains("取消"));
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, h.status("one"));
        assertEquals("C", h.plan.getTask("c").getDescription());
        assertEquals(Task.TaskStatus.PENDING, h.plan.getTask("c").getStatus());
        assertEquals(TurnState.CANCELLED, h.manager.snapshot().state());
        assertEquals(0, h.manager.snapshot().reservedCount());
    }

    @Test
    void cancelledReservationCannotProceedOrFinalize() {
        TurnInputManager manager = manager();
        manager.accept("session", "turn", "one", "test", "补充", DeliveryMode.STEER);
        manager.accept("session", "turn", "stop", "test", "停止", DeliveryMode.INTERRUPT);
        PlanSteeringSafePoint point = new PlanSteeringSafePoint(manager, "turn");
        var prepared = point.reserve();
        assertFalse(prepared.proceed());
        assertNull(prepared.delivery());
        assertEquals(0, manager.snapshot().reservedCount());
        assertEquals(PlanSteeringSafePoint.FinalizationDecision.CANCELLED, point.finalizeOrContinue());
        point.stopIfActive();
        assertEquals(TurnState.CANCELLED, manager.snapshot().state());
        assertEquals(InputStatus.CANCELLED, manager.input("one").orElseThrow().status());
    }

    @Test
    void lateSteerAtLastTaskStillMakesPlannerRequestAndDrainsNewArrival() throws Exception {
        Harness h = new Harness();
        h.client.taskReply = (messages, tools) -> {
            if (currentTask(messages).equals("C")) h.accept("last", "最终补充", DeliveryMode.STEER);
            return reply("done");
        };
        h.planner.revisionCheck = () -> {
            assertTrue(h.plan.getAllTasks().stream().noneMatch(t -> t.getStatus() == Task.TaskStatus.PENDING));
            if (h.planner.feedback.size() == 1) h.accept("later", "再补充", DeliveryMode.STEER);
        };
        assertTrue(h.run().contains("计划执行完成"));
        assertEquals(2, h.client.revisionCalls.get(), "没有 PENDING 也必须调用模型");
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, h.status("last"));
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, h.status("later"));
        assertEquals(InputAcceptance.Code.STALE_TURN,
                h.accept("stale", "太迟", DeliveryMode.STEER).code());
    }

    @Test
    void finalizeRaceEitherContinuesWithInputOrExplicitlyRejectsIt() throws Exception {
        for (int i = 0; i < 30; i++) {
            TurnInputManager manager = manager();
            PlanSteeringSafePoint point = new PlanSteeringSafePoint(manager, "turn");
            CyclicBarrier barrier = new CyclicBarrier(2);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<InputAcceptance> arrival = executor.submit(() -> {
                    await(barrier);
                    return manager.accept("session", "turn", "race", "test", "补充", DeliveryMode.STEER);
                });
                await(barrier);
                var decision = point.finalizeOrContinue();
                var accepted = arrival.get(5, TimeUnit.SECONDS);
                if (accepted.code() == InputAcceptance.Code.ACCEPTED) {
                    assertEquals(PlanSteeringSafePoint.FinalizationDecision.CONTINUE, decision);
                    var prepared = point.reserve();
                    assertEquals("补充", prepared.delivery().inputs().get(0).content());
                    point.commit(prepared, ConversationLedger.disabled());
                    assertEquals(PlanSteeringSafePoint.FinalizationDecision.FINALIZE, point.finalizeOrContinue());
                } else {
                    assertEquals(InputAcceptance.Code.STALE_TURN, accepted.code());
                    assertEquals(PlanSteeringSafePoint.FinalizationDecision.FINALIZE, decision);
                }
                point.completeTurn();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void cancellationBeforeSafePointPreventsRevisionAndNextBatch() throws Exception {
        Harness h = new Harness();
        h.client.taskReply = (messages, tools) -> {
            if (currentTask(messages).equals("A")) {
                h.accept("steer", "补充", DeliveryMode.STEER);
                h.accept("cancel", "停止", DeliveryMode.INTERRUPT);
            }
            return reply("done");
        };
        assertTrue(h.run().contains("取消"));
        assertEquals(0, h.client.revisionCalls.get());
        assertEquals(Task.TaskStatus.PENDING, h.plan.getTask("c").getStatus());
    }

    @Test
    void queuedInitialInputCannotBecomeDagSteering() {
        TurnInputManager manager = manager();
        manager.accept("session", "turn", "queue", "test", "next", DeliveryMode.ENQUEUE);
        assertEquals(TurnInputManager.Finalization.FINALIZE, manager.finalizeOrContinue("turn"));
        manager.completeTurn("turn");
        manager.startNextQueuedTurn("next");
        manager.markRunning("next");
        PlanSteeringSafePoint point = new PlanSteeringSafePoint(manager, "next");
        assertThrows(IllegalStateException.class, point::reserve);
        assertEquals(InputStatus.PENDING_DELIVERY, manager.input("queue").orElseThrow().status());
        assertEquals(0, manager.snapshot().reservedCount());
    }

    @Test
    void unsentReservationCanBeRequeued() {
        TurnInputManager manager = manager();
        manager.accept("session", "turn", "one", "test", "补充", DeliveryMode.STEER);
        PlanSteeringSafePoint point = new PlanSteeringSafePoint(manager, "turn");
        var prepared = point.reserve();
        point.requeue(prepared);
        assertEquals(InputStatus.PENDING_DELIVERY, manager.input("one").orElseThrow().status());
        assertEquals(1, manager.snapshot().pendingCount());
    }

    @Test
    void noWebSteeringReachesLaterTaskPolicy() throws Exception {
        Harness h = new Harness();
        h.accept("one", "不要联网，只用本地资料 https://new.example/", DeliveryMode.STEER);
        h.client.taskReply = (messages, tools) -> {
            if (currentTask(messages).equals("C")) {
                assertFalse(tools.stream().anyMatch(t -> t.name().equals("web_search") || t.name().equals("web_fetch")));
            }
            return reply("done");
        };
        assertTrue(h.run().contains("计划执行完成"));
    }

    @Test
    void failedBatchRecordsSuccessThenSteeringBeforeReplan() throws Exception {
        Harness h = new Harness();
        // 1/4 完成，触发重规划；A 的失败不能让 B 的成功丢失。
        h.plan.addTask(new Task("d", "D", Task.TaskType.ANALYSIS, List.of("c")));
        h.plan.computeExecutionOrder();
        h.accept("one", "后续只用本地资料", DeliveryMode.STEER);
        h.client.taskReply = (messages, tools) -> {
            if (currentTask(messages).equals("A")) throw new IOException("A failed");
            return reply("B result");
        };
        h.planner.replanCheck = () -> {
            assertEquals(Task.TaskStatus.FAILED, h.plan.getTask("a").getStatus());
            assertEquals(Task.TaskStatus.COMPLETED, h.plan.getTask("b").getStatus());
            assertEquals("B result", h.plan.getTask("b").getResult());
            assertEquals(InputStatus.INCLUDED_IN_REQUEST, h.status("one"));
        };
        assertTrue(h.run().contains("计划执行完成"));
        assertEquals(1, h.planner.replans);
    }

    @Test
    void nonReplannedFailureSkipsDependentsBeforeRevisingIndependentPendingTask() throws Exception {
        Harness h = new Harness();
        h.plan.addTask(new Task("x", "X", Task.TaskType.ANALYSIS));
        h.plan.addTask(new Task("y", "Y", Task.TaskType.ANALYSIS));
        h.plan.addTask(new Task("d", "D", Task.TaskType.ANALYSIS, List.of("c")));
        h.plan.addTask(new Task("e", "E", Task.TaskType.ANALYSIS, List.of("a", "b")));
        h.plan.addTask(new Task("f", "F", Task.TaskType.ANALYSIS, List.of("e")));
        h.plan.computeExecutionOrder();
        h.client.taskReply = (messages, tools) -> {
            if (currentTask(messages).equals("C")) {
                h.accept("one", "调整独立的 F", DeliveryMode.STEER);
                throw new IOException("C failed");
            }
            return reply(currentTask(messages));
        };
        h.client.revisionJson = revision("f", "新的 F", "e");
        h.planner.revisionCheck = () -> {
            assertEquals(Task.TaskStatus.FAILED, h.plan.getTask("c").getStatus());
            assertEquals(Task.TaskStatus.SKIPPED, h.plan.getTask("d").getStatus());
            assertEquals(Task.TaskStatus.COMPLETED, h.plan.getTask("e").getStatus());
        };
        String result = h.run();
        assertTrue(result.contains("计划部分完成"), result);
        assertEquals("新的 F", h.plan.getTask("f").getResult());
        assertEquals(Task.TaskStatus.COMPLETED, h.plan.getTask("f").getStatus());
        assertEquals(0, h.planner.replans);
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, h.status("one"));
    }

    @Test
    void replanLimitSkipsAllPendingBeforeLateSteeringDrain() throws Exception {
        String old = System.getProperty(PlanExecuteAgent.MAX_REPLANS_PROPERTY);
        try {
            System.setProperty(PlanExecuteAgent.MAX_REPLANS_PROPERTY, "0");
            Harness h = new Harness();
            h.client.taskReply = (messages, tools) -> {
                if (currentTask(messages).equals("A")) {
                    h.accept("one", "补充", DeliveryMode.STEER);
                    throw new IOException("A failed");
                }
                return reply("done");
            };
            h.planner.revisionCheck = () -> {
                assertEquals(Task.TaskStatus.SKIPPED, h.plan.getTask("c").getStatus());
                assertTrue(h.plan.getAllTasks().stream().noneMatch(t -> t.getStatus() == Task.TaskStatus.PENDING));
                if (h.planner.feedback.size() == 1) h.accept("late", "最后补充", DeliveryMode.STEER);
            };
            assertTrue(h.run().contains("已达到重新规划上限"));
            assertEquals(2, h.client.revisionCalls.get());
            assertEquals(InputStatus.INCLUDED_IN_REQUEST, h.status("late"));
            assertEquals(TurnState.COMPLETED, h.manager.snapshot().state());
            assertEquals(0, h.planner.replans);
        } finally {
            if (old == null) System.clearProperty(PlanExecuteAgent.MAX_REPLANS_PROPERTY);
            else System.setProperty(PlanExecuteAgent.MAX_REPLANS_PROPERTY, old);
        }
    }

    private class Harness {
        final TurnInputManager manager = manager();
        final FakeClient client = new FakeClient();
        final ExecutionPlan plan = new ExecutionPlan("plan", "分析项目");
        final StubPlanner planner;
        final PlanExecuteAgent agent;
        Harness() throws IOException {
            plan.addTask(new Task("a", "A", Task.TaskType.ANALYSIS));
            plan.addTask(new Task("b", "B", Task.TaskType.ANALYSIS));
            plan.addTask(new Task("c", "C", Task.TaskType.ANALYSIS, List.of("a", "b")));
            plan.computeExecutionOrder();
            planner = new StubPlanner(client, plan);
            ToolRegistry registry = new ToolRegistry();
            registry.setProjectPath(tempDir.toString());
            MemoryManager memory = new MemoryManager(client, 4096, 128000,
                    new LongTermMemory(tempDir.resolve("memory").toFile()));
            memory.setAutoFactExtractionEnabled(false);
            agent = new PlanExecuteAgent(client, registry, planner, memory,
                    (goal, p) -> PlanExecuteAgent.PlanReviewDecision.execute(), out);
            agent.setConversationLedger(ConversationLedger.open(tempDir.resolve("history"), "steering"));
            agent.configurePlanSteeringSafePoint(manager, "turn");
        }
        InputAcceptance accept(String id, String content, DeliveryMode mode) {
            return manager.accept("session", "turn", id, "test", content, mode);
        }
        InputStatus status(String id) { return manager.input(id).orElseThrow().status(); }
        String run() { return agent.run("分析项目 https://original.example/"); }
    }

    private class StubPlanner extends Planner {
        final ExecutionPlan plan;
        final List<String> feedback = new ArrayList<>();
        Runnable revisionCheck = () -> {};
        Runnable replanCheck = () -> {};
        int replans;
        StubPlanner(LlmClient client, ExecutionPlan plan) { super(client, out); this.plan = plan; }
        @Override public ExecutionPlan createPlan(String goal) { return plan; }
        @Override public List<ExecutionPlan.PendingTaskRevision> revisePendingTasks(ExecutionPlan p, String text) throws IOException {
            feedback.add(text);
            revisionCheck.run();
            return super.revisePendingTasks(p, text);
        }
        @Override public ExecutionPlan replan(ExecutionPlan p, String reason) {
            replanCheck.run();
            replans++;
            ExecutionPlan next = new ExecutionPlan("replan", p.getGoal());
            next.addTask(new Task("r", "replacement", Task.TaskType.ANALYSIS));
            next.computeExecutionOrder();
            return next;
        }
    }

    @FunctionalInterface private interface Reply {
        LlmClient.ChatResponse chat(List<LlmClient.Message> messages, List<LlmClient.Tool> tools) throws IOException;
    }
    private static class FakeClient implements LlmClient {
        final AtomicInteger taskCalls = new AtomicInteger(), revisionCalls = new AtomicInteger();
        String revisionJson = "{\"revisions\":[]}";
        IOException revisionFailure;
        boolean nullRevisionResponse;
        Reply taskReply = (messages, tools) -> reply(currentTask(messages) + " result");
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
            if (tools == null) {
                revisionCalls.incrementAndGet();
                if (revisionFailure != null) throw revisionFailure;
                if (nullRevisionResponse) return null;
                return reply(revisionJson);
            }
            taskCalls.incrementAndGet();
            return taskReply.chat(messages, tools);
        }
        @Override public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
            return chat(messages, tools);
        }
        @Override public String getModelName() { return "fake"; }
        @Override public String getProviderName() { return "fake"; }
    }
    private static TurnInputManager manager() {
        TurnInputManager manager = new TurnInputManager("session", true, TurnInputManager.Limits.defaults());
        manager.startTurn("turn");
        manager.markRunning("turn");
        return manager;
    }
    private static LlmClient.ChatResponse reply(String text) { return new LlmClient.ChatResponse("assistant", text, null, 10, 2); }
    private static String currentTask(List<LlmClient.Message> messages) {
        return messages.stream().filter(m -> "user".equals(m.role())).map(LlmClient.Message::content)
                .flatMap(String::lines).filter(s -> s.startsWith("当前任务：")).findFirst().orElseThrow().substring(5);
    }
    private static String revision(String id, String description, String... deps) {
        return "{\"revisions\":[{\"id\":\"" + id + "\",\"description\":\"" + description
                + "\",\"type\":\"ANALYSIS\",\"dependencies\":["
                + String.join(",", Arrays.stream(deps).map(s -> "\"" + s + "\"").toList()) + "]}]}";
    }
    private static void await(CyclicBarrier barrier) throws IOException {
        try { barrier.await(5, TimeUnit.SECONDS); }
        catch (Exception e) { throw new IOException(e); }
    }
}
