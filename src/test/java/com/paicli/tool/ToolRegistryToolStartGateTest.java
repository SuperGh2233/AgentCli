package com.paicli.tool;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.hitl.HitlHandler;
import com.paicli.hitl.HitlToolRegistry;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.runtime.input.DeliveryMode;
import com.paicli.runtime.input.TurnInputManager;
import com.paicli.tool.ToolRegistry.ToolInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ToolRegistryToolStartGateTest {
    private static TurnInputManager manager() {
        var manager = new TurnInputManager("session", true, TurnInputManager.Limits.defaults());
        manager.startTurn("turn");
        manager.markRunning("turn");
        return manager;
    }

    private static ToolRegistry.ToolStartGate gate(TurnInputManager manager) {
        return invocation -> {
            var claim = manager.claimToolStart("turn", invocation.id());
            return claim == TurnInputManager.ToolStartClaim.ALLOWED ? null : "工具未启动：" + claim;
        };
    }

    private static void cancel(TurnInputManager manager) {
        assertTrue(manager.accept("session", "turn", "esc", "test", "Esc", DeliveryMode.INTERRUPT).accepted());
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "latch timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static void fake(ToolRegistry registry, String name, Function<String, ToolOutput> executor) {
        // 只替换测试内执行器，包括只读工具名；仍经过真实注册表的校验和执行入口。
        registry.registerMcpToolOutput(new McpToolDescriptor("fake", "tool", name, "fake",
                JsonNodeFactory.instance.objectNode().put("type", "object")), executor);
    }

    @Test
    void parallelWorkersClaimAtEntryAndKeepOrderAfterOutOfOrderCompletion(@TempDir Path project) throws Exception {
        var manager = manager();
        var registry = new ToolRegistry();
        registry.setProjectPath(project.toString());
        var started = new CountDownLatch(4);
        var releaseFirstThree = new CountDownLatch(1);
        var releaseFourth = new CountDownLatch(1);
        var fourthFinished = new CountDownLatch(1);
        var deniedQueued = new CountDownLatch(2);
        var calls = new AtomicInteger();
        List<String> finished = Collections.synchronizedList(new ArrayList<>());
        fake(registry, "read_file", args -> {
            calls.incrementAndGet();
            started.countDown();
            if (args.contains("fourth")) {
                await(releaseFourth);
                finished.add(args);
                fourthFinished.countDown();
            } else {
                await(releaseFirstThree);
                finished.add(args);
            }
            return ToolOutput.text("real:" + args);
        });
        var invocations = List.of("first", "second", "third", "fourth", "fifth", "sixth").stream()
                .map(id -> new ToolInvocation(id, "read_file", "{\"id\":\"" + id + "\"}")).toList();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var run = executor.submit(() -> registry.executeTools(invocations, invocation -> {
                String reason = gate(manager).rejectionReason(invocation);
                if (reason != null) deniedQueued.countDown();
                return reason;
            }));
            await(started);
            cancel(manager);
            releaseFourth.countDown();
            await(fourthFinished);
            await(deniedQueued);
            releaseFirstThree.countDown();
            var results = run.get(5, TimeUnit.SECONDS);
            assertEquals(4, calls.get());
            assertTrue(finished.get(0).contains("fourth"));
            assertEquals(invocations.stream().map(ToolInvocation::id).toList(), results.stream().map(ToolRegistry.ToolExecutionResult::id).toList());
            assertTrue(results.subList(0, 4).stream().allMatch(r -> r.successful() && r.result().startsWith("real:")));
            assertTrue(results.subList(4, 6).stream().allMatch(r -> !r.successful() && r.result().contains("未启动")));
        } finally {
            releaseFourth.countDown();
            releaseFirstThree.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void interruptDuringRealHitlWaitIsCheckedAfterApprovalAndDoesNotLeak(@TempDir Path project) throws Exception {
        var manager = manager();
        var approvalEntered = new CountDownLatch(1);
        var approve = new CountDownLatch(1);
        var approvals = new AtomicInteger();
        var starts = new AtomicInteger();
        var registry = new HitlToolRegistry(new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) {
                approvals.incrementAndGet();
                approvalEntered.countDown();
                await(approve);
                return ApprovalResult.approve();
            }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        });
        registry.setProjectPath(project.toString());
        fake(registry, "mcp__fake__dangerous", args -> {
            starts.incrementAndGet();
            return ToolOutput.text("real");
        });
        var invocation = new ToolInvocation("call", "mcp__fake__dangerous", "{}");
        var executor = Executors.newSingleThreadExecutor();
        try {
            var result = executor.submit(() -> registry.executeTools(List.of(invocation), gate(manager)));
            await(approvalEntered);
            assertEquals(0, starts.get());
            cancel(manager);
            approve.countDown();
            assertFalse(result.get(5, TimeUnit.SECONDS).get(0).successful());
            assertEquals(0, starts.get(), "approval after cancellation must not start the tool");
            // 同一线程、同一注册表，旧调用路径没有残留的取消检查。
            assertTrue(executor.submit(() -> registry.executeTools(List.of(invocation))).get(5, TimeUnit.SECONDS).get(0).successful());
            assertEquals(1, starts.get());
            assertEquals(2, approvals.get());
        } finally {
            approve.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void hitlDenialNeverClaimsAndReplayAcrossBatchesNeverRunsTwice(@TempDir Path project) {
        var starts = new AtomicInteger();
        var registry = new HitlToolRegistry(new HitlHandler() {
            public ApprovalResult requestApproval(ApprovalRequest request) { return ApprovalResult.reject("test denial"); }
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
        });
        registry.setProjectPath(project.toString());
        fake(registry, "mcp__fake__dangerous", args -> { starts.incrementAndGet(); return ToolOutput.text("real"); });
        var denied = registry.executeTools(List.of(new ToolInvocation("denied", "mcp__fake__dangerous", "{}")),
                invocation -> { fail("HITL rejection must precede claim"); return null; });
        assertFalse(denied.get(0).successful());
        assertEquals(0, starts.get());

        var manager = manager();
        var plain = new ToolRegistry();
        plain.setProjectPath(project.toString());
        fake(plain, "mcp__fake__dangerous", args -> { starts.incrementAndGet(); return ToolOutput.text("real"); });
        var calls = List.of(new ToolInvocation("same", "mcp__fake__dangerous", "{}"));
        assertTrue(plain.executeTools(calls, gate(manager)).get(0).successful());
        var replay = plain.executeTools(calls, gate(manager)).get(0);
        assertFalse(replay.successful());
        assertTrue(replay.result().contains("DUPLICATE"));
        assertEquals(1, starts.get());
    }

    @Test
    void policyDenialPrecedesGateAndKeepsInvocationOrder(@TempDir Path project) {
        var registry = new ToolRegistry();
        registry.setProjectPath(project.toString());
        var starts = new AtomicInteger();
        fake(registry, "web_fetch", args -> { starts.incrementAndGet(); return ToolOutput.text("must not execute"); });
        fake(registry, "read_file", args -> { starts.incrementAndGet(); return ToolOutput.text("real"); });
        var policy = TurnToolPolicy.fromUserInput("读取本地文件，不要联网");
        var exposure = policy.expose(registry.getToolDefinitions());
        List<String> claims = new ArrayList<>();
        var results = policy.execute(registry, List.of(
                new ToolInvocation("blocked", "web_fetch", "{\"url\":\"https://example.com\"}"),
                new ToolInvocation("local", "read_file", "{}")), exposure, invocation -> {
                    claims.add(invocation.id());
                    return null;
                });
        assertEquals(List.of("local"), claims);
        assertEquals(1, starts.get());
        assertEquals(List.of("blocked", "local"), results.stream().map(ToolRegistry.ToolExecutionResult::id).toList());
        assertFalse(results.get(0).successful());
        assertTrue(results.get(1).successful());
    }
}
