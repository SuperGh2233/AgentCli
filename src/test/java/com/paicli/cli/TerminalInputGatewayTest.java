package com.paicli.cli;

import com.paicli.runtime.input.TurnInputManager;
import com.paicli.util.TerminalInputLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.paicli.cli.InputCoordinator.Mode.*;
import static com.paicli.cli.TerminalInputGateway.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TerminalInputGatewayTest {
    private static final Duration WAIT = Duration.ofSeconds(3);
    private final TurnInputManager manager = new TurnInputManager("session", true, TurnInputManager.Limits.defaults());
    private final InputCoordinator coordinator = new InputCoordinator("session", manager, true);

    private void start() {
        manager.startTurn("turn");
        manager.markRunning("turn");
        coordinator.beginRun("turn");
    }

    @Test
    void workerPostsAndOnlyOwnerReadsAllSubpromptsWithDifferentIds() throws Exception {
        start();
        Thread owner = Thread.currentThread();
        List<String> ids = new ArrayList<>();
        List<Kind> kinds = new ArrayList<>();
        try (var gateway = new TerminalInputGateway(coordinator, request -> {
            assertSame(owner, Thread.currentThread());
            ids.add(request.id());
            kinds.add(request.kind());
            return "中文\n原文";
        })) {
            var worker = Executors.newSingleThreadExecutor();
            try {
                var result = worker.submit(() -> {
                    try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                        List<String> lines = new ArrayList<>();
                        for (Kind kind : List.of(Kind.APPROVAL_CHOICE, Kind.REJECTION_REASON,
                                Kind.MODIFIED_ARGUMENTS, Kind.APPROVAL_SCOPE)) {
                            lines.add(session.read(kind, "提示", WAIT));
                        }
                        return lines;
                    }
                });
                pump(gateway, result);
                assertEquals(List.of("中文\n原文", "中文\n原文", "中文\n原文", "中文\n原文"), result.get());
                assertEquals(4, ids.stream().distinct().count());
                assertEquals(4, kinds.size());
                assertEquals(0, manager.snapshot().pendingCount());
                assertEquals(RUNNING_INPUT, coordinator.capture().mode());
            } finally { worker.shutdownNow(); }
        }
    }

    @Test
    void interactionInvalidatesOldReadAndStaysActiveBetweenSubprompts() throws Exception {
        start();
        var old = coordinator.capture();
        var opened = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try (var gateway = new TerminalInputGateway(coordinator, request -> fail("不应读取"))) {
            var result = worker.submit(() -> {
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    opened.countDown();
                    assertTrue(finish.await(3, TimeUnit.SECONDS));
                }
                return true;
            });
            try {
                assertTrue(opened.await(3, TimeUnit.SECONDS));
                assertEquals(InputCoordinator.Route.RETRY, coordinator.submit(old, "y").route());
                assertEquals(DispatchStatus.IDLE, gateway.serviceNext().status());
                assertEquals(APPROVAL_INPUT, coordinator.capture().mode());
                assertEquals(InputCoordinator.Route.INTERACTION,
                        coordinator.submit(coordinator.capture(), "/steer 不是新指令").route());
                assertEquals(0, manager.snapshot().pendingCount());
            } finally { finish.countDown(); }
            assertTrue(result.get(3, TimeUnit.SECONDS));
        } finally { worker.shutdownNow(); }
    }

    @Test
    void lateResponseAfterTimeoutIsRetryAndCannotApproveNextRequest() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var firstFailed = new CountDownLatch(1);
        List<String> ids = new ArrayList<>();
        try (var gateway = new TerminalInputGateway(coordinator, request -> {
            ids.add(request.id());
            if (ids.size() == 1) assertTrue(firstFailed.await(3, TimeUnit.SECONDS));
            return ids.size() == 1 ? "y" : "n";
        })) {
            var result = worker.submit(() -> {
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    var error = assertThrows(InputUnavailableException.class,
                            () -> session.read(Kind.APPROVAL_CHOICE, "first", Duration.ofMillis(200)));
                    assertEquals(Failure.TIMEOUT, error.failure());
                } finally { firstFailed.countDown(); }
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    return session.read(Kind.APPROVAL_CHOICE, "second", WAIT);
                }
            });
            var dispatch = nextDispatch(gateway);
            assertEquals(DispatchStatus.RETRY, dispatch.status());
            assertEquals("y", dispatch.text());
            pump(gateway, result);
            assertEquals("n", result.get());
            assertEquals(2, ids.stream().distinct().count());
        } finally { worker.shutdownNow(); }
    }

    @Test
    void eofAndReadErrorAreFailuresRatherThanEmptyEnter() throws Exception {
        for (boolean eof : new boolean[]{true, false}) {
            var worker = Executors.newSingleThreadExecutor();
            try (var gateway = new TerminalInputGateway(coordinator, request -> {
                if (eof) return null;
                throw new IOException("broken");
            })) {
                var result = worker.submit(() -> {
                    try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                        return assertThrows(InputUnavailableException.class,
                                () -> session.read(Kind.MODIFIED_ARGUMENTS, "json", WAIT)).failure();
                    }
                });
                pump(gateway, result);
                assertEquals(eof ? Failure.EOF : Failure.READ_ERROR, result.get());
                assertEquals(IDLE_INPUT, coordinator.capture().mode());
            } finally { worker.shutdownNow(); }
        }
    }

    @Test
    void interruptedWorkerPreservesInterruptAndReleasesInteraction() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var requester = new AtomicReference<Thread>();
        var finished = new CountDownLatch(1);
        try (var gateway = new TerminalInputGateway(coordinator, request -> {
            requester.get().interrupt();
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            return "y";
        })) {
            var result = worker.submit(() -> {
                try (var session = gateway.openInteraction(PLAN_REVIEW_INPUT)) {
                    requester.set(Thread.currentThread());
                    var error = assertThrows(InputUnavailableException.class,
                            () -> session.read(Kind.PLAN_REVIEW, "plan", WAIT));
                    assertEquals(Failure.INTERRUPTED, error.failure());
                    return Thread.currentThread().isInterrupted();
                } finally { Thread.interrupted(); finished.countDown(); }
            });
            assertEquals(DispatchStatus.RETRY, nextDispatch(gateway).status());
            assertTrue(result.get(3, TimeUnit.SECONDS));
            assertEquals(IDLE_INPUT, coordinator.capture().mode());
        } finally { worker.shutdownNow(); }
    }

    @Test
    void conflictingSessionIsBusyAndWrongKindCannotReadOrChangeMode() throws Exception {
        var opened = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var firstWorker = Executors.newSingleThreadExecutor();
        var secondWorker = Executors.newSingleThreadExecutor();
        try (var gateway = new TerminalInputGateway(coordinator, request -> fail("不应读取"))) {
            var result = firstWorker.submit(() -> {
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    assertThrows(IllegalArgumentException.class, () -> session.read(Kind.PLAN_REVIEW, "plan", WAIT));
                    assertThrows(IllegalArgumentException.class,
                            () -> session.read(Kind.APPROVAL_CHOICE, "choice", Duration.ZERO));
                    opened.countDown();
                    assertTrue(finish.await(3, TimeUnit.SECONDS));
                }
                return true;
            });
            try {
                assertTrue(opened.await(3, TimeUnit.SECONDS));
                assertEquals(Failure.BUSY, secondWorker.submit(() -> assertThrows(InputUnavailableException.class,
                        () -> gateway.openInteraction(PLAN_REVIEW_INPUT)).failure()).get(3, TimeUnit.SECONDS));
                assertEquals(APPROVAL_INPUT, coordinator.capture().mode());
            } finally { finish.countDown(); }
            assertTrue(result.get(3, TimeUnit.SECONDS));
        } finally { firstWorker.shutdownNow(); secondWorker.shutdownNow(); }
    }

    @Test
    void closeWakesPendingWorkerAndRejectsFurtherRequests() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var reading = new CountDownLatch(1);
        try (var gateway = new TerminalInputGateway(coordinator, request -> null)) {
            var result = worker.submit(() -> {
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    reading.countDown();
                    return assertThrows(InputUnavailableException.class,
                            () -> session.read(Kind.APPROVAL_SCOPE, "scope", WAIT)).failure();
                }
            });
            assertTrue(reading.await(3, TimeUnit.SECONDS));
            gateway.close();
            assertEquals(Failure.CLOSED, result.get(3, TimeUnit.SECONDS));
            assertEquals(Failure.CLOSED, assertThrows(InputUnavailableException.class,
                    () -> gateway.openInteraction(APPROVAL_INPUT)).failure());
        } finally { worker.shutdownNow(); }
    }

    @Test
    void planReviewAndSupplementUseSameInteractionAndNeverEnqueue() throws Exception {
        start();
        try (var gateway = new TerminalInputGateway(coordinator, request -> "/queue 计划补充🙂")) {
            var worker = Executors.newSingleThreadExecutor();
            try {
                var result = worker.submit(() -> {
                    try (var session = gateway.openInteraction(PLAN_REVIEW_INPUT)) {
                        session.read(Kind.PLAN_REVIEW, "review", WAIT);
                        return session.read(Kind.PLAN_SUPPLEMENT, "supplement", WAIT);
                    }
                });
                pump(gateway, result);
                assertEquals("/queue 计划补充🙂", result.get());
                assertEquals(0, manager.snapshot().queuedCount());
            } finally { worker.shutdownNow(); }
        }
    }

    @Test
    void nonOwnerCannotServiceAndOwnerCannotWaitForItself() throws Exception {
        try (var gateway = new TerminalInputGateway(coordinator, request -> fail("不应读取"))) {
            assertThrows(IllegalStateException.class, () -> gateway.openInteraction(APPROVAL_INPUT));
            var worker = Executors.newSingleThreadExecutor();
            try {
                worker.submit(() -> assertThrows(IllegalStateException.class, gateway::serviceNext))
                        .get(3, TimeUnit.SECONDS);
            } finally { worker.shutdownNow(); }
        }
    }

    @Test
    void disabledAndNonInteractiveGatewaysNeverReadOrChangeMode() throws Exception {
        var disabled = new TurnInputManager("session", false, TurnInputManager.Limits.defaults());
        for (var input : List.of(new InputCoordinator("session", disabled, true),
                new InputCoordinator("session", manager, false))) {
            try (var gateway = new TerminalInputGateway(input, request -> fail("不应读取"))) {
                var worker = Executors.newSingleThreadExecutor();
                try {
                    assertEquals(Failure.DISABLED, worker.submit(() -> assertThrows(InputUnavailableException.class,
                            () -> gateway.openInteraction(APPROVAL_INPUT)).failure()).get(3, TimeUnit.SECONDS));
                    assertEquals(DispatchStatus.IDLE, gateway.serviceNext().status());
                } finally { worker.shutdownNow(); }
            }
        }
    }

    @Test
    void workerWaitDoesNotHoldTerminalLockAndUiReadDoes() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var probe = Executors.newSingleThreadExecutor();
        AtomicBoolean heldDuringRead = new AtomicBoolean();
        try (var gateway = new TerminalInputGateway(coordinator, request -> {
            heldDuringRead.set(probe.submit(() -> {
                boolean acquired = TerminalInputLock.tryAcquire();
                if (acquired) TerminalInputLock.release();
                return !acquired;
            }).get(3, TimeUnit.SECONDS));
            return "y";
        })) {
            var result = worker.submit(() -> {
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    assertTrue(TerminalInputLock.tryAcquire());
                    TerminalInputLock.release();
                    return session.read(Kind.APPROVAL_CHOICE, "choice", WAIT);
                }
            });
            pump(gateway, result);
            assertEquals("y", result.get());
            assertTrue(heldDuringRead.get());
        } finally { worker.shutdownNow(); probe.shutdownNow(); }
    }

    private static Dispatch nextDispatch(TerminalInputGateway gateway) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var dispatch = gateway.serviceNext();
            if (dispatch.status() != DispatchStatus.IDLE) return dispatch;
            Thread.sleep(1);
        }
        return fail("UI 未收到输入请求");
    }

    private static void pump(TerminalInputGateway gateway, Future<?> result) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!result.isDone() && System.nanoTime() < deadline) {
            gateway.serviceNext();
            Thread.sleep(1);
        }
        result.get(1, TimeUnit.SECONDS);
    }
}
