package com.paicli.render.inline;

import com.paicli.cli.InputCoordinator;
import com.paicli.cli.TerminalInputGateway;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.runtime.input.TurnInputManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.paicli.cli.TerminalInputGateway.Kind.*;
import static com.paicli.hitl.ApprovalResult.Decision.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class InlineApprovalGatewayTest {
    private static final ApprovalRequest BUILTIN = ApprovalRequest.of("write_file", "{}", "test");
    private static final ApprovalRequest MCP = ApprovalRequest.of("mcp__example__tool", "{}", "test");
    private final TurnInputManager manager = new TurnInputManager("session", true, TurnInputManager.Limits.defaults());
    private final InputCoordinator coordinator = new InputCoordinator("session", manager, true);
    private final List<TerminalInputGateway.Kind> kinds = new ArrayList<>();

    @Test
    void rejectionReasonAndChineseUseSameUiReader() throws Exception {
        var result = run(BUILTIN, "n", "不允许写入🙂");
        assertEquals(REJECTED, result.decision());
        assertEquals("不允许写入🙂", result.reason());
        assertEquals(List.of(APPROVAL_CHOICE, REJECTION_REASON), kinds);
    }

    @Test
    void modifiedJsonAndScopeUseSameUiReader() throws Exception {
        var modified = run(BUILTIN, "m", "{\"path\":\"中文.txt\"}");
        assertEquals(MODIFIED, modified.decision());
        assertEquals("{\"path\":\"中文.txt\"}", modified.modifiedArguments());
        assertEquals(List.of(APPROVAL_CHOICE, MODIFIED_ARGUMENTS), kinds);
        kinds.clear();
        assertEquals(APPROVED_ALL_BY_SERVER, run(MCP, "a", "server").decision());
        assertEquals(List.of(APPROVAL_CHOICE, APPROVAL_SCOPE), kinds);
    }

    @Test
    void eofAtChoiceAndEverySubpromptRejects() throws Exception {
        assertEquals(REJECTED, run(BUILTIN).decision());
        for (String choice : List.of("n", "m", "a")) {
            var result = run(choice.equals("a") ? MCP : BUILTIN, choice);
            assertEquals(REJECTED, result.decision(), choice);
            assertTrue(result.reason().contains("EOF"), result.reason());
        }
    }

    @Test
    void realEmptyLineRetainsEnterAndKeepOriginalAndToolScopeSemantics() throws Exception {
        assertEquals(APPROVED, run(BUILTIN, "").decision());
        assertEquals(APPROVED, run(BUILTIN, "m", "").decision());
        assertEquals(APPROVED_ALL, run(MCP, "a", "").decision());
    }

    @Test
    void ordinaryLineStartingWithYAndQueueCommandNeverApproveOrSteer() throws Exception {
        manager.startTurn("turn");
        manager.markRunning("turn");
        coordinator.beginRun("turn");
        assertEquals(REJECTED, run(BUILTIN, "yes please change plan", "/queue 不排队").decision());
        assertEquals(0, manager.snapshot().pendingCount());
        assertEquals(0, manager.snapshot().queuedCount());
    }

    @Test
    void readerErrorAndCancellationRejectEvenAfterModifyOrApproveAll() throws Exception {
        for (String choice : List.of("m", "a")) {
            for (boolean interrupt : new boolean[]{false, true}) {
                int[] count = {0};
                try {
                    var result = run(MCP, request -> {
                        if (count[0]++ == 0) return choice;
                        if (interrupt) throw new InterruptedException("cancel");
                        throw new IOException("broken");
                    }, Duration.ofSeconds(3));
                    assertEquals(REJECTED, result.decision());
                    assertTrue(result.reason().contains(interrupt ? "INTERRUPTED" : "READ_ERROR"));
                    assertEquals(interrupt, Thread.currentThread().isInterrupted());
                } finally { Thread.interrupted(); }
            }
        }
    }

    @Test
    void timeoutRejectsAndLateEmptyEnterIsRetry() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        try (var gateway = new TerminalInputGateway(coordinator, request -> {
            Thread.sleep(100);
            return "";
        })) {
            var prompt = new InlineApprovalPrompter(new PrintStream(new ByteArrayOutputStream()),
                    gateway, Duration.ofMillis(30));
            var result = worker.submit(() -> prompt.prompt(BUILTIN));
            TerminalInputGateway.Dispatch dispatch = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (dispatch == null && !result.isDone() && System.nanoTime() < deadline) {
                var next = gateway.serviceNext();
                if (next.status() != TerminalInputGateway.DispatchStatus.IDLE) dispatch = next;
                Thread.sleep(1);
            }
            assertEquals(REJECTED, result.get(3, TimeUnit.SECONDS).decision());
            // 若 UI 确实开始读取，返回的空 Enter 也必须 RETRY，不能批准。
            if (dispatch != null) assertEquals(TerminalInputGateway.DispatchStatus.RETRY, dispatch.status());
        } finally { worker.shutdownNow(); }
    }

    @Test
    void sensitiveApprovalStillDisallowsAllAndInvalidJsonStillReprompts() throws Exception {
        var sensitive = ApprovalRequest.of("write_file", "{}", "test", null, "敏感操作");
        assertEquals(APPROVED, run(sensitive, "a", "y").decision());
        assertEquals(SKIPPED, run(BUILTIN, "m", "not-json", "s").decision());
    }

    private ApprovalResult run(ApprovalRequest request, String... lines) throws Exception {
        int[] index = {0};
        return run(request, input -> {
            kinds.add(input.kind());
            return index[0] < lines.length ? lines[index[0]++] : null;
        }, Duration.ofSeconds(3));
    }

    private ApprovalResult run(ApprovalRequest request, TerminalInputGateway.InputReader reader,
                               Duration timeout) throws Exception {
        Thread owner = Thread.currentThread();
        var worker = Executors.newSingleThreadExecutor();
        try (var gateway = new TerminalInputGateway(coordinator, input -> {
            assertSame(owner, Thread.currentThread());
            return reader.read(input);
        })) {
            var prompt = new InlineApprovalPrompter(new PrintStream(new ByteArrayOutputStream()), gateway, timeout);
            var result = worker.submit(() -> prompt.prompt(request));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!result.isDone() && System.nanoTime() < deadline) {
                gateway.serviceNext();
                if (!Thread.currentThread().isInterrupted()) Thread.sleep(1);
            }
            // 被读取器中断的是 UI；临时清标记以取断言结果，随后恢复。
            boolean interrupted = Thread.interrupted();
            try { return result.get(1, TimeUnit.SECONDS); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        } finally { worker.shutdownNow(); }
    }
}
