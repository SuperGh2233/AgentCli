package com.paicli.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.paicli.llm.LlmClient;
import com.paicli.memory.LongTermMemory;
import com.paicli.memory.MemoryManager;
import com.paicli.plan.ExecutionPlan;
import com.paicli.plan.Planner;
import com.paicli.plan.Task;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class ResourceFailureIntegrationTest {
    @TempDir Path root;
    private static final String EVENT = "Resource failure observation: REPEATED_EDIT_SOURCE_NOT_FOUND";
    private static final PrintStream OUT = new PrintStream(OutputStream.nullOutputStream());

    private static final class Fake implements LlmClient {
        final List<List<ToolCall>> batches;
        final List<List<Message>> requests = new ArrayList<>();
        int step;
        Fake(List<List<ToolCall>> batches) { this.batches = batches; }
        public ChatResponse chat(List<Message> messages, List<Tool> tools) {
            requests.add(List.copyOf(messages));
            var calls = step < batches.size() ? batches.get(step++) : null;
            return new ChatResponse("assistant", calls == null ? "done" : "", calls, 1, 1);
        }
        public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) {
            return chat(messages, tools);
        }
        public String getModelName() { return "fake"; }
        public String getProviderName() { return "fake"; }
    }

    private List<List<LlmClient.ToolCall>> edits(int count) {
        var batches = new ArrayList<List<LlmClient.ToolCall>>();
        for (int i = 0; i < count; i++) batches.add(List.of(new LlmClient.ToolCall("edit" + i,
                new LlmClient.ToolCall.Function("edit_file",
                        "{\"path\":\"private.txt\",\"old_text\":\"private missing source\",\"new_text\":\"variant" + i + "\"}"))));
        return batches;
    }

    private ToolRegistry registry() throws Exception {
        Files.writeString(root.resolve("private.txt"), "source");
        var registry = new ToolRegistry();
        registry.setProjectPath(root.toString());
        return registry;
    }

    private Supplier<String> runner(String mode, Fake fake, ToolRegistry registry) {
        if (mode.equals("react")) {
            var agent = new Agent(fake, registry);
            return () -> agent.run("private user request");
        }
        if (mode.equals("worker")) {
            var agent = new SubAgent("private-worker", AgentRole.WORKER, fake, registry);
            return () -> agent.execute(AgentMessage.task("owner", "private user request"), OUT).content();
        }
        var memory = new MemoryManager(fake, 4096, 128000,
                new LongTermMemory(root.resolve("memory").toFile()));
        memory.setAutoFactExtractionEnabled(false);
        var planner = new Planner(fake) {
            @Override public ExecutionPlan createPlan(String goal) {
                var plan = new ExecutionPlan("plan", goal);
                plan.addTask(new Task("task", "private task", Task.TaskType.FILE_WRITE));
                plan.computeExecutionOrder();
                return plan;
            }
        };
        var agent = new PlanExecuteAgent(fake, registry, planner, memory,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), OUT);
        return () -> agent.run("private user request");
    }

    private static final class Capture implements AutoCloseable {
        final Logger logger = (Logger) LoggerFactory.getLogger("com.paicli.agent");
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        final String old = System.getProperty(ResourceFailureObserver.ENABLED_PROPERTY);
        Capture() { appender.start(); logger.addAppender(appender); }
        List<String> events() {
            synchronized (appender) {
                return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                        .filter(s -> s.startsWith("Resource failure observation:")).toList();
            }
        }
        public void close() {
            logger.detachAppender(appender); appender.stop();
            if (old == null) System.clearProperty(ResourceFailureObserver.ENABLED_PROPERTY);
            else System.setProperty(ResourceFailureObserver.ENABLED_PROPERTY, old);
        }
    }

    @Test void allThreeModesOptInOnlyAndLeaveModelRequestsAndReturnsUnchanged() throws Exception {
        try (var capture = new Capture()) {
            for (String mode : List.of("react", "plan", "worker")) {
                System.clearProperty(ResourceFailureObserver.ENABLED_PROPERTY);
                var off = new Fake(edits(5));
                String expected = runner(mode, off, registry()).get();
                assertTrue(capture.events().isEmpty());
                System.setProperty(ResourceFailureObserver.ENABLED_PROPERTY, "true");
                var on = new Fake(edits(5));
                assertEquals(expected, runner(mode, on, registry()).get());
                assertEquals(off.requests, on.requests, mode);
                assertEquals(List.of(EVENT), capture.events(), mode);
                for (String event : capture.events()) {
                    assertFalse(event.contains(root.toString()));
                    assertFalse(event.contains("private"));
                    assertFalse(event.contains("private missing source"));
                }
                capture.appender.list.clear();
            }
        }
    }

    @Test void repeatedRunsDoNotAccumulateAndEachRunCanLogOnce() throws Exception {
        try (var capture = new Capture()) {
            System.setProperty(ResourceFailureObserver.ENABLED_PROPERTY, "true");
            for (String mode : List.of("react", "plan", "worker")) {
                var fake = new Fake(edits(2));
                var run = runner(mode, fake, registry());
                run.get(); fake.step = 0; run.get();
                assertTrue(capture.events().isEmpty(), mode);
                var full = new Fake(edits(4));
                var fullRun = runner(mode, full, registry());
                fullRun.get(); full.step = 0; fullRun.get();
                assertEquals(List.of(EVENT, EVENT), capture.events(), mode);
                capture.appender.list.clear();
            }
        }
    }

    @Test void mixedSuccessBatchResetsInAllModes() throws Exception {
        try (var capture = new Capture()) {
            System.setProperty(ResourceFailureObserver.ENABLED_PROPERTY, "true");
            for (String mode : List.of("react", "plan", "worker")) {
                var batches = edits(5);
                batches.set(2, List.of(batches.get(2).get(0), new LlmClient.ToolCall("read",
                        new LlmClient.ToolCall.Function("read_file", "{\"path\":\"private.txt\"}"))));
                runner(mode, new Fake(batches), registry()).get();
                assertTrue(capture.events().isEmpty(), mode);
            }
        }
    }

    @Test void concurrentWorkersDoNotCombineStreaksOrOneTimeFlags() throws Exception {
        try (var capture = new Capture()) {
            System.setProperty(ResourceFailureObserver.ENABLED_PROPERTY, "true");
            var registry = registry();
            var executor = Executors.newFixedThreadPool(2);
            try {
                for (int count : List.of(2, 4)) {
                    var first = runner("worker", new Fake(edits(count)), registry);
                    var second = runner("worker", new Fake(edits(count)), registry);
                    var a = executor.submit(first::get);
                    var b = executor.submit(second::get);
                    assertEquals("done", a.get(20, TimeUnit.SECONDS));
                    assertEquals("done", b.get(20, TimeUnit.SECONDS));
                    assertEquals(count == 2 ? List.of() : List.of(EVENT, EVENT), capture.events());
                }
            } finally { executor.shutdownNow(); }
        }
    }

    @Test void hitlDenialBreaksRealFailureStreakInAllModes() throws Exception {
        try (var capture = new Capture()) {
            System.setProperty(ResourceFailureObserver.ENABLED_PROPERTY, "true");
            for (String mode : List.of("react", "plan", "worker")) {
                var approvals = new java.util.concurrent.atomic.AtomicInteger();
                var registry = new com.paicli.hitl.HitlToolRegistry(new com.paicli.hitl.HitlHandler() {
                    public boolean isEnabled() { return true; }
                    public void setEnabled(boolean enabled) { }
                    public com.paicli.hitl.ApprovalResult requestApproval(com.paicli.hitl.ApprovalRequest request) {
                        return approvals.incrementAndGet() == 3
                                ? com.paicli.hitl.ApprovalResult.reject("private denial")
                                : com.paicli.hitl.ApprovalResult.approve();
                    }
                });
                Files.writeString(root.resolve("private.txt"), "source");
                registry.setProjectPath(root.toString());
                runner(mode, new Fake(edits(5)), registry).get();
                assertEquals(5, approvals.get());
                assertTrue(capture.events().isEmpty(), mode);
                assertEquals("source", Files.readString(root.resolve("private.txt")));
            }
        }
    }

    @Test void cancelDuringApprovalDoesNotCountUnstartedEditAndNextRunStartsFresh() throws Exception {
        try (var capture = new Capture()) {
            System.setProperty(ResourceFailureObserver.ENABLED_PROPERTY, "true");
            var manager = new com.paicli.runtime.input.TurnInputManager(
                    "session", true, com.paicli.runtime.input.TurnInputManager.Limits.defaults());
            manager.startTurn("turn"); manager.markRunning("turn");
            var approvals = new java.util.concurrent.atomic.AtomicInteger();
            var registry = new com.paicli.hitl.HitlToolRegistry(new com.paicli.hitl.HitlHandler() {
                public boolean isEnabled() { return true; }
                public void setEnabled(boolean enabled) { }
                public com.paicli.hitl.ApprovalResult requestApproval(com.paicli.hitl.ApprovalRequest request) {
                    if (approvals.incrementAndGet() == 3) assertTrue(manager.accept(
                            "session", "turn", "esc", "test", "Esc",
                            com.paicli.runtime.input.DeliveryMode.INTERRUPT).accepted());
                    return com.paicli.hitl.ApprovalResult.approve();
                }
            });
            Files.writeString(root.resolve("private.txt"), "source");
            registry.setProjectPath(root.toString());
            var fake = new Fake(edits(5));
            var agent = new Agent(fake, registry);
            agent.configureSteeringSafePoint(manager, "turn");
            assertTrue(agent.run("private user request").contains("取消"));
            assertEquals(3, approvals.get());
            assertTrue(capture.events().isEmpty());
            assertEquals("source", Files.readString(root.resolve("private.txt")));
            runner("react", new Fake(edits(2)), registry).get();
            assertTrue(capture.events().isEmpty());
        }
    }
}
