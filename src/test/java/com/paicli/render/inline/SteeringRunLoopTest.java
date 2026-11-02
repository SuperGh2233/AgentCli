package com.paicli.render.inline;

import com.paicli.cli.InputCoordinator;
import com.paicli.cli.SteeringRunLoop;
import com.paicli.cli.WakeableLineReader;
import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.runtime.input.TurnInputManager;
import com.paicli.runtime.input.TurnState;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class SteeringRunLoopTest {
    private static final String SESSION = "steering-run-loop-session";
    private static final String TURN = "steering-run-loop-turn";

    @Test
    void routesPlainTextToSteerAndQueueOnlyToNextTurn() throws Exception {
        var input = new PipedInputStream();
        try (var keys = new PipedOutputStream(input);
             var terminal = TerminalBuilder.builder().system(false).dumb(true)
                     .streams(input, new ByteArrayOutputStream()).build()) {
            var reader = new WakeableLineReader(terminal, "steering-test");
            var manager = new TurnInputManager(SESSION, true, TurnInputManager.Limits.defaults());
            var coordinator = new InputCoordinator(SESSION, manager, true);
            var output = new ByteArrayOutputStream();
            var renderer = new InlineRenderer(terminal, new PrintStream(output, true, StandardCharsets.UTF_8));
            renderer.bindLineReader(reader);
            var taskStarted = new CountDownLatch(1);
            Thread producer = writeAfter(taskStarted, keys, "补充当前任务\n/queue 下一步任务\n");
            try {
                var result = SteeringRunLoop.run(reader, renderer, manager, coordinator, TURN, () -> {
                    taskStarted.countDown();
                    await(() -> manager.snapshot().pendingCount() == 1
                            && manager.snapshot().queuedCount() == 1);
                    var delivery = manager.reserveForRequest(TURN).orElseThrow();
                    assertEquals(1, delivery.inputs().size());
                    assertEquals("补充当前任务", delivery.inputs().get(0).content());
                    manager.commit(delivery, "fake-request");
                    assertEquals(TurnInputManager.Finalization.FINALIZE, manager.finalizeOrContinue(TURN));
                    manager.completeTurn(TURN);
                    return "done";
                });
                assertTrue(result.workerStopped());
                assertEquals("done", result.response());
                assertEquals(TurnState.COMPLETED, manager.snapshot().state());
                assertEquals(0, manager.snapshot().pendingCount());
                assertEquals(1, manager.snapshot().queuedCount());
                String rendered = output.toString(StandardCharsets.UTF_8);
                assertTrue(rendered.contains("不会自动启动下一任务"));
                assertTrue(rendered.contains("补充当前任务"), "accepted Steer must be rendered as a user message");
                assertTrue(rendered.contains("/queue 下一步任务"), "queued input must be visible in the transcript");
            } finally {
                producer.interrupt();
                producer.join(1000);
                renderer.close();
            }
        }
    }

    @Test
    void hitlChoiceAndRejectionReasonUseTheSameUiReader() throws Exception {
        var input = new PipedInputStream();
        var terminalOutput = new ByteArrayOutputStream();
        try (var keys = new PipedOutputStream(input);
             var terminal = TerminalBuilder.builder().system(false).dumb(true)
                     .streams(input, terminalOutput).build()) {
            var reader = new WakeableLineReader(terminal, "steering-approval-test");
            var manager = new TurnInputManager(SESSION, true, TurnInputManager.Limits.defaults());
            var coordinator = new InputCoordinator(SESSION, manager, true);
            var output = new ByteArrayOutputStream();
            var renderer = new InlineRenderer(terminal, new PrintStream(output, true, StandardCharsets.UTF_8));
            renderer.bindLineReader(reader);
            var taskStarted = new CountDownLatch(1);
            Thread producer = new Thread(() -> {
                try {
                    assertTrue(taskStarted.await(3, TimeUnit.SECONDS));
                    await(() -> terminalOutput.toString(StandardCharsets.UTF_8).contains("审批选项 y/a/n/s/m"));
                    keys.write("n\n".getBytes(StandardCharsets.UTF_8));
                    keys.flush();
                    await(() -> terminalOutput.toString(StandardCharsets.UTF_8).contains("拒绝原因（可直接回车跳过）"));
                    keys.write("不允许写入\n".getBytes(StandardCharsets.UTF_8));
                    keys.flush();
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, "steering-approval-input");
            producer.setDaemon(true);
            producer.start();
            try {
                var result = SteeringRunLoop.run(reader, renderer, manager, coordinator, TURN, () -> {
                    taskStarted.countDown();
                    ApprovalResult approval = renderer.promptApproval(
                            ApprovalRequest.of("write_file", "{}", "fake tool"));
                    assertEquals(ApprovalResult.Decision.REJECTED, approval.decision());
                    assertEquals("不允许写入", approval.reason());
                    assertEquals(TurnInputManager.Finalization.FINALIZE, manager.finalizeOrContinue(TURN));
                    manager.completeTurn(TURN);
                    return "done";
                });
                assertTrue(result.workerStopped());
                assertEquals("done", result.response());
                assertEquals(TurnState.COMPLETED, manager.snapshot().state());
                assertEquals(0, manager.snapshot().pendingCount());
                assertEquals(0, manager.snapshot().queuedCount());
                String approvalOutput = output.toString(StandardCharsets.UTF_8);
                String jlineOutput = terminalOutput.toString(StandardCharsets.UTF_8);
                assertTrue(approvalOutput.contains("HITL 审批"));
                assertTrue(approvalOutput.contains("[y] approve"));
                assertFalse(approvalOutput.contains("拒绝原因（可直接回车跳过）:"),
                        "gateway path must not print a duplicate raw-input prompt");
                assertTrue(jlineOutput.contains("拒绝原因（可直接回车跳过）"),
                        "the unified JLine Reader owns the rejection-reason prompt");
            } finally {
                producer.interrupt();
                producer.join(1000);
                renderer.close();
            }
        }
    }

    @Test
    void approvalWakeupReturnsTheRunningDraftAndCursorToTheNextPrompt() throws Exception {
        var input = new PipedInputStream();
        try (var keys = new PipedOutputStream(input);
             var terminal = TerminalBuilder.builder().system(false).dumb(true)
                     .streams(input, new ByteArrayOutputStream()).build()) {
            var reader = new WakeableLineReader(terminal, "steering-draft-test");
            var manager = new TurnInputManager(SESSION, true, TurnInputManager.Limits.defaults());
            var coordinator = new InputCoordinator(SESSION, manager, true);
            var output = new ByteArrayOutputStream();
            var renderer = new InlineRenderer(terminal, new PrintStream(output, true, StandardCharsets.UTF_8));
            renderer.bindLineReader(reader);
            var taskStarted = new CountDownLatch(1);
            Thread producer = new Thread(() -> {
                try {
                    assertTrue(taskStarted.await(3, TimeUnit.SECONDS));
                    keys.write("保留草稿".getBytes(StandardCharsets.UTF_8));
                    keys.flush();
                    Thread.sleep(250);
                    keys.write("n\n不允许写入\n".getBytes(StandardCharsets.UTF_8));
                    keys.flush();
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, "steering-draft-input");
            producer.setDaemon(true);
            producer.start();
            try {
                var result = SteeringRunLoop.run(reader, renderer, manager, coordinator, TURN, () -> {
                    taskStarted.countDown();
                    Thread.sleep(120);
                    ApprovalResult approval = renderer.promptApproval(
                            ApprovalRequest.of("write_file", "{}", "fake tool"));
                    assertEquals(ApprovalResult.Decision.REJECTED, approval.decision());
                    assertEquals(TurnInputManager.Finalization.FINALIZE, manager.finalizeOrContinue(TURN));
                    manager.completeTurn(TURN);
                    return "done";
                });
                assertTrue(result.workerStopped());
                assertEquals("done", result.response());
                assertEquals("保留草稿", result.promptSeed());
                assertEquals("保留草稿".length(), result.promptCursor());
            } finally {
                producer.interrupt();
                producer.join(1000);
                renderer.close();
            }
        }
    }

    @Test
    void standaloneEscCancelsTheRunningInputMode() throws Exception {
        var input = new PipedInputStream();
        try (var keys = new PipedOutputStream(input);
             var terminal = TerminalBuilder.builder().system(false).dumb(true)
                     .streams(input, new ByteArrayOutputStream()).build()) {
            var reader = new WakeableLineReader(terminal, "steering-esc-test");
            var manager = new TurnInputManager(SESSION, true, TurnInputManager.Limits.defaults());
            var coordinator = new InputCoordinator(SESSION, manager, true);
            var renderer = new InlineRenderer(terminal, new PrintStream(new ByteArrayOutputStream()));
            renderer.bindLineReader(reader);
            var taskStarted = new CountDownLatch(1);
            Thread producer = new Thread(() -> {
                try {
                    assertTrue(taskStarted.await(3, TimeUnit.SECONDS));
                    Thread.sleep(120);
                    keys.write(27);
                    keys.flush();
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, "steering-esc-input");
            producer.setDaemon(true);
            producer.start();
            try {
                var result = SteeringRunLoop.run(reader, renderer, manager, coordinator, TURN, () -> {
                    taskStarted.countDown();
                    new CountDownLatch(1).await();
                    return "unexpected";
                });
                assertTrue(result.workerStopped());
                assertTrue(result.response().contains("取消"));
                assertEquals(TurnState.CANCELLED, manager.snapshot().state());
            } finally {
                producer.interrupt();
                producer.join(1000);
                renderer.close();
            }
        }
    }

    @Test
    void cancelCommandStopsTheWorkerAndPausesQueuedWork() throws Exception {
        var input = new PipedInputStream();
        try (var keys = new PipedOutputStream(input);
             var terminal = TerminalBuilder.builder().system(false).dumb(true)
                     .streams(input, new ByteArrayOutputStream()).build()) {
            var reader = new WakeableLineReader(terminal, "steering-cancel-test");
            var manager = new TurnInputManager(SESSION, true, TurnInputManager.Limits.defaults());
            var coordinator = new InputCoordinator(SESSION, manager, true);
            var renderer = new InlineRenderer(terminal, new PrintStream(new ByteArrayOutputStream()));
            renderer.bindLineReader(reader);
            var taskStarted = new CountDownLatch(1);
            Thread producer = writeAfter(taskStarted, keys, "/cancel\n");
            try {
                var result = SteeringRunLoop.run(reader, renderer, manager, coordinator, TURN, () -> {
                    taskStarted.countDown();
                    new CountDownLatch(1).await();
                    return "unexpected";
                });
                assertTrue(result.workerStopped());
                assertTrue(result.response().contains("取消"));
                assertEquals(TurnState.CANCELLED, manager.snapshot().state());
                assertTrue(manager.snapshot().queuePaused());
            } finally {
                producer.interrupt();
                producer.join(1000);
                renderer.close();
            }
        }
    }

    private static Thread writeAfter(CountDownLatch started, PipedOutputStream keys, String text) {
        Thread producer = new Thread(() -> {
            try {
                assertTrue(started.await(3, TimeUnit.SECONDS));
                keys.write(text.getBytes(StandardCharsets.UTF_8));
                keys.flush();
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        }, "steering-test-input");
        producer.setDaemon(true);
        producer.start();
        return producer;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "timed out waiting for the steering input");
    }
}
