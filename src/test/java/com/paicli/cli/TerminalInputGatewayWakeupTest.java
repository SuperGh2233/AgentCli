package com.paicli.cli;

import com.paicli.runtime.input.TurnInputManager;
import com.paicli.util.TerminalInputLock;
import org.jline.reader.LineReader;
import org.jline.terminal.TerminalBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.paicli.cli.InputCoordinator.Mode.*;
import static com.paicli.cli.TerminalInputGateway.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class TerminalInputGatewayWakeupTest {
    private static final Duration WAIT = Duration.ofSeconds(3);
    private final TurnInputManager manager = new TurnInputManager("wake", true, TurnInputManager.Limits.defaults());
    private final InputCoordinator coordinator = new InputCoordinator("wake", manager, true);

    private void start() {
        manager.startTurn("turn");
        manager.markRunning("turn");
        coordinator.beginRun("turn");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS));
    }

    private static void assertTerminalFree() throws Exception {
        var probe = Executors.newSingleThreadExecutor();
        try {
            assertTrue(probe.submit(() -> {
                boolean acquired = TerminalInputLock.tryAcquire();
                if (acquired) TerminalInputLock.release();
                return acquired;
            }).get(3, TimeUnit.SECONDS));
        } finally { probe.shutdownNow(); }
    }

    @Test
    void openBeforeFirstReadAndEverySubpromptNotifyOutsideMonitorAndKeepApprovalMode() throws Exception {
        start();
        var signals = new LinkedBlockingQueue<Boolean>();
        var gatewayRef = new AtomicReference<TerminalInputGateway>();
        var opened = new CountDownLatch(1);
        var publish = new CountDownLatch(1);
        var between = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var close = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        List<String> ids = new ArrayList<>();
        try (var gateway = new TerminalInputGateway(coordinator, request -> {
            ids.add(request.id());
            return "n";
        }, () -> signals.add(Thread.holdsLock(gatewayRef.get())))) {
            gatewayRef.set(gateway);
            assertEquals(InteractionState.NONE, gateway.awaitInteraction(Duration.ZERO).state());
            var result = worker.submit(() -> {
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    opened.countDown();
                    await(publish);
                    assertEquals("n", session.read(Kind.APPROVAL_CHOICE, "choice", WAIT));
                    between.countDown();
                    await(second);
                    assertEquals("n", session.read(Kind.REJECTION_REASON, "reason", WAIT));
                    completed.countDown();
                    await(close);
                }
                return true;
            });
            await(opened);
            assertEquals(Boolean.FALSE, signals.poll(3, TimeUnit.SECONDS));
            assertEquals(InteractionState.BETWEEN_SUBPROMPTS, gateway.awaitInteraction(Duration.ZERO).state());
            assertEquals(InteractionState.BETWEEN_SUBPROMPTS,
                    gateway.awaitInteraction(Duration.ofMillis(10)).state());
            assertEquals(APPROVAL_INPUT, coordinator.capture().mode());
            assertEquals(DispatchStatus.IDLE, gateway.serviceNext().status());
            publish.countDown();
            assertEquals(InteractionState.PENDING, gateway.awaitInteraction(WAIT).state());
            assertEquals(Boolean.FALSE, signals.poll(3, TimeUnit.SECONDS));
            assertEquals(DispatchStatus.DELIVERED, gateway.serviceNext().status());
            await(between);
            assertEquals(APPROVAL_INPUT, coordinator.capture().mode());
            assertEquals(InteractionState.BETWEEN_SUBPROMPTS, gateway.awaitInteraction(Duration.ZERO).state());
            second.countDown();
            assertEquals(InteractionState.PENDING, gateway.awaitInteraction(WAIT).state());
            assertEquals(Boolean.FALSE, signals.poll(3, TimeUnit.SECONDS));
            assertEquals(DispatchStatus.DELIVERED, gateway.serviceNext().status());
            await(completed);
            assertEquals(APPROVAL_INPUT, coordinator.capture().mode());
            close.countDown();
            assertEquals(InteractionState.NONE, gateway.awaitInteraction(WAIT).state());
            assertTrue(result.get(3, TimeUnit.SECONDS));
            assertEquals(Boolean.FALSE, signals.poll(3, TimeUnit.SECONDS));
            assertEquals(RUNNING_INPUT, coordinator.capture().mode());
            assertEquals(2, ids.stream().distinct().count());
            assertEquals(0, manager.snapshot().pendingCount());
            assertTerminalFree();
        } finally {
            publish.countDown(); second.countDown(); close.countDown(); worker.shutdownNow();
        }
    }

    @Test
    void boundedWaitDoesNotHoldTerminalLockAndRejectsOtherUiOwner() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var opened = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var gateway = new TerminalInputGateway(coordinator, request -> fail("no read"))) {
            var result = worker.submit(() -> {
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    opened.countDown();
                    await(finish);
                    assertTrue(TerminalInputLock.tryAcquire());
                    TerminalInputLock.release();
                }
                return true;
            });
            await(opened);
            finish.countDown();
            assertEquals(InteractionState.NONE, gateway.awaitInteraction(WAIT).state());
            result.get(3, TimeUnit.SECONDS);
            worker.submit(() -> assertThrows(IllegalStateException.class,
                    () -> gateway.awaitInteraction(Duration.ZERO))).get(3, TimeUnit.SECONDS);
            assertThrows(IllegalArgumentException.class,
                    () -> gateway.awaitInteraction(Duration.ofMillis(-1)));
        } finally { finish.countDown(); worker.shutdownNow(); }
    }

    @Test
    void internalWakeRetriesSameRequestWithoutFailingWorkerOrSubmittingDraft() throws Exception {
        start();
        var calls = new AtomicInteger();
        var opened = new CountDownLatch(1);
        var worker = Executors.newSingleThreadExecutor();
        try (var gateway = new TerminalInputGateway(coordinator, request -> {
            if (calls.getAndIncrement() == 0) throw new InputRetryException();
            return "n";
        })) {
            var result = worker.submit(() -> {
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    opened.countDown();
                    return session.read(Kind.APPROVAL_CHOICE, "choice", WAIT);
                }
            });
            await(opened);
            var state = gateway.awaitInteraction(WAIT);
            var retry = gateway.serviceNext();
            assertEquals(DispatchStatus.RETRY, retry.status());
            assertEquals(state.request().id(), retry.requestId());
            assertNull(retry.text());
            assertFalse(result.isDone());
            assertEquals(APPROVAL_INPUT, coordinator.capture().mode());
            assertTerminalFree();
            var delivery = gateway.serviceNext();
            assertEquals(DispatchStatus.DELIVERED, delivery.status());
            assertEquals(retry.requestId(), delivery.requestId());
            assertEquals("n", result.get(3, TimeUnit.SECONDS));
            assertEquals(0, manager.snapshot().pendingCount());
        } finally { worker.shutdownNow(); }
    }

    @Test
    void timeoutWakesProductionReaderThroughAdapterAndRetainsDraft() throws Exception {
        // Real WakeableLineReader, no PTY or second terminal reader. The worker
        // waits for CALLBACK_INIT so timeout happens during approval readLine.
        var input = new PipedInputStream();
        try (var keys = new PipedOutputStream(input);
             var terminal = TerminalBuilder.builder().system(false).dumb(true)
                     .streams(input, new ByteArrayOutputStream()).build()) {
            var reader = new WakeableLineReader(terminal, "gateway-test");
            var ready = new CountDownLatch(1);
            reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
                reader.getBuffer().write("草稿y");
                reader.getBuffer().cursor(1);
                ready.countDown();
                return true;
            });
            var adapter = new JLineInputAdapter(reader);
            var worker = Executors.newSingleThreadExecutor();
            var opened = new CountDownLatch(1);
            var publish = new CountDownLatch(1);
            var wake = new AtomicInteger();
            try (var gateway = new TerminalInputGateway(coordinator, adapter, () -> {
                // Open/publish flags may precede readLine; all are real wakes.
                wake.incrementAndGet();
                reader.requestWake();
            })) {
                var result = worker.submit(() -> {
                    try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                        opened.countDown();
                        await(publish);
                        return assertThrows(InputUnavailableException.class,
                                () -> session.read(Kind.APPROVAL_CHOICE, "approve> ",
                                        Duration.ofMillis(350))).failure();
                    }
                });
                await(opened);
                publish.countDown();
                assertEquals(InteractionState.PENDING, gateway.awaitInteraction(WAIT).state());
                String id = null;
                while (!result.isDone()) {
                    var dispatch = gateway.serviceNext();
                    if (dispatch.status() == DispatchStatus.IDLE) break;
                    assertEquals(DispatchStatus.RETRY, dispatch.status());
                    if (id == null) id = dispatch.requestId();
                    assertEquals(id, dispatch.requestId());
                    var draft = reader.takeSuspendedDraft();
                    assertNotNull(draft);
                    assertEquals("草稿y", draft.text());
                    assertEquals(1, draft.cursor());
                }
                await(ready);
                assertEquals(Failure.TIMEOUT, result.get(3, TimeUnit.SECONDS));
                assertTrue(wake.get() >= 3);
                assertTerminalFree();
            } finally { publish.countDown(); worker.shutdownNow(); }
        }
    }

    @Test
    void lateEnterOrYAfterTimeoutNeverApprovesNextRequest() throws Exception {
        for (String late : List.of("", "y")) {
            var worker = Executors.newSingleThreadExecutor();
            var opened = new CountDownLatch(1);
            var secondOpened = new CountDownLatch(1);
            var reads = new AtomicInteger();
            try (var gateway = new TerminalInputGateway(coordinator, request -> {
                if (reads.getAndIncrement() == 0) {
                    await(secondOpened);
                    return late;
                }
                return "n";
            })) {
                var result = worker.submit(() -> {
                    try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                        opened.countDown();
                        assertEquals(Failure.TIMEOUT, assertThrows(InputUnavailableException.class,
                                () -> session.read(Kind.APPROVAL_CHOICE, "old", Duration.ofMillis(150))).failure());
                    }
                    try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                        secondOpened.countDown();
                        return session.read(Kind.APPROVAL_CHOICE, "new", WAIT);
                    }
                });
                await(opened);
                gateway.awaitInteraction(WAIT);
                var stale = gateway.serviceNext();
                assertEquals(DispatchStatus.RETRY, stale.status());
                assertEquals(Failure.TIMEOUT, stale.failure());
                assertEquals(late, stale.text());
                var current = gateway.awaitInteraction(WAIT);
                assertNotEquals(stale.requestId(), current.request().id());
                assertEquals(DispatchStatus.DELIVERED, gateway.serviceNext().status());
                assertEquals("n", result.get(3, TimeUnit.SECONDS));
                assertTerminalFree();
            } finally { worker.shutdownNow(); }
        }
    }

    @Test
    void failingCallbackCannotBreakPublicationDeliveryOrClose() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var opened = new CountDownLatch(1);
        try (var gateway = new TerminalInputGateway(coordinator, request -> "", () -> {
            throw new IllegalStateException("callback failed");
        })) {
            var result = worker.submit(() -> {
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    opened.countDown();
                    return session.read(Kind.APPROVAL_CHOICE, "choice", WAIT);
                }
            });
            await(opened);
            assertEquals(InteractionState.PENDING, gateway.awaitInteraction(WAIT).state());
            assertEquals(DispatchStatus.DELIVERED, gateway.serviceNext().status());
            assertEquals("", result.get(3, TimeUnit.SECONDS));
            assertEquals(InteractionState.NONE, gateway.awaitInteraction(WAIT).state());
            gateway.close();
            assertTerminalFree();
        } finally { worker.shutdownNow(); }
    }

    @Test
    void closeAndWorkerInterruptionSignalBlockedUiAndReleaseSession() throws Exception {
        for (boolean interrupt : new boolean[]{false, true}) {
            var worker = Executors.newSingleThreadExecutor();
            var requester = new AtomicReference<Thread>();
            var signals = new LinkedBlockingQueue<Boolean>();
            var opened = new CountDownLatch(1);
            try (var gateway = new TerminalInputGateway(coordinator, request -> fail("no read"),
                    () -> signals.add(true))) {
                var result = worker.submit(() -> {
                    requester.set(Thread.currentThread());
                    try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                        opened.countDown();
                        return assertThrows(InputUnavailableException.class,
                                () -> session.read(Kind.APPROVAL_CHOICE, "choice", WAIT)).failure();
                    } finally { Thread.interrupted(); }
                });
                await(opened);
                gateway.awaitInteraction(WAIT);
                assertNotNull(signals.poll(3, TimeUnit.SECONDS));
                assertNotNull(signals.poll(3, TimeUnit.SECONDS));
                if (interrupt) requester.get().interrupt();
                else gateway.close();
                assertEquals(interrupt ? Failure.INTERRUPTED : Failure.CLOSED,
                        result.get(3, TimeUnit.SECONDS));
                assertNotNull(signals.poll(3, TimeUnit.SECONDS));
                assertEquals(InteractionState.NONE, gateway.awaitInteraction(WAIT).state());
                assertTerminalFree();
            } finally { worker.shutdownNow(); }
        }
    }

    @Test
    void controlCFromJLineFailsApprovalAndIsReportedAsInterrupt() throws Exception {
        var worker = Executors.newSingleThreadExecutor();
        var opened = new CountDownLatch(1);
        try (var gateway = new TerminalInputGateway(coordinator, request -> {
            throw new JLineInputAdapter.InputCancelledException("draft");
        })) {
            var result = worker.submit(() -> {
                try (var session = gateway.openInteraction(APPROVAL_INPUT)) {
                    opened.countDown();
                    return assertThrows(InputUnavailableException.class,
                            () -> session.read(Kind.APPROVAL_CHOICE, "choice", WAIT)).failure();
                }
            });
            await(opened);
            gateway.awaitInteraction(WAIT);
            var dispatch = gateway.serviceNext();
            assertEquals(DispatchStatus.FAILED, dispatch.status());
            assertEquals(Failure.INTERRUPTED, dispatch.failure());
            assertEquals(Failure.INTERRUPTED, result.get(3, TimeUnit.SECONDS));
            assertTerminalFree();
        } finally { worker.shutdownNow(); }
    }
}
