package com.paicli.runtime.input;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.paicli.runtime.input.DeliveryMode.*;
import static com.paicli.runtime.input.InputAcceptance.Code.*;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class TurnInputManagerTest {
    private static final String SESSION = "session";
    private static final String TURN = "turn-1";

    private TurnInputManager manager() {
        TurnInputManager manager = new TurnInputManager(SESSION, true,
                TurnInputManager.Limits.defaults());
        manager.startTurn(TURN);
        manager.markRunning(TURN);
        return manager;
    }

    private InputAcceptance accept(TurnInputManager manager, String id, DeliveryMode mode) {
        return manager.accept(SESSION, TURN, id, "test", id, mode);
    }

    private TurnInputManager.Delivery reserve(TurnInputManager manager, String turn) {
        return manager.reserveForRequest(turn).orElseThrow();
    }

    @Test
    void toolClaimsRequireRunningTurnAndAreNeverReplayed() {
        var manager = manager();
        assertEquals(TurnInputManager.ToolStartClaim.INVALID_ID, manager.claimToolStart(TURN, " "));
        assertEquals(TurnInputManager.ToolStartClaim.NOT_RUNNING, manager.claimToolStart("old", "call"));
        assertEquals(TurnInputManager.ToolStartClaim.ALLOWED, manager.claimToolStart(TURN, "call"));
        assertEquals(TurnInputManager.ToolStartClaim.DUPLICATE, manager.claimToolStart(TURN, "call"));
        manager.waitForApproval(TURN);
        assertEquals(TurnInputManager.ToolStartClaim.NOT_RUNNING, manager.claimToolStart(TURN, "next"));
        manager.markRunning(TURN);
        manager.finalizeOrContinue(TURN);
        assertEquals(TurnInputManager.ToolStartClaim.NOT_RUNNING, manager.claimToolStart(TURN, "next"));
        manager.completeTurn(TURN);
        manager.startTurn("turn-2");
        assertEquals(TurnInputManager.ToolStartClaim.NOT_RUNNING, manager.claimToolStart("turn-2", "call"));
        manager.markRunning("turn-2");
        assertEquals(TurnInputManager.ToolStartClaim.ALLOWED, manager.claimToolStart("turn-2", "call"));
        assertEquals(TurnInputManager.ToolStartClaim.DISABLED,
                new TurnInputManager(SESSION, false, TurnInputManager.Limits.defaults()).claimToolStart(TURN, "call"));
    }

    @Test
    void interruptAndClaimHaveBothDeterministicLockOrders() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            for (boolean cancelFirst : List.of(true, false)) {
                var manager = manager();
                var firstDone = new CountDownLatch(1);
                var first = executor.submit(() -> {
                    try {
                        if (cancelFirst) assertTrue(accept(manager, "esc", INTERRUPT).accepted());
                        else assertEquals(TurnInputManager.ToolStartClaim.ALLOWED, manager.claimToolStart(TURN, "call"));
                    } finally { firstDone.countDown(); }
                });
                await(firstDone);
                first.get(3, TimeUnit.SECONDS);
                if (!cancelFirst) assertTrue(accept(manager, "esc", INTERRUPT).accepted());
                assertEquals(TurnInputManager.ToolStartClaim.NOT_RUNNING, manager.claimToolStart(TURN, "call"));
                assertEquals(TurnInputManager.ToolStartClaim.NOT_RUNNING, manager.claimToolStart(TURN, "new"));
                manager.finishCancellation(TURN);
                assertEquals(TurnInputManager.ToolStartClaim.NOT_RUNNING, manager.claimToolStart(TURN, "new"));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void steersUseFifoAndManagerAssignedMonotonicSequence() {
        TurnInputManager manager = manager();
        assertEquals(1, accept(manager, "z", STEER).input().sequence());
        assertEquals(2, accept(manager, "a", STEER).input().sequence());
        assertEquals(3, accept(manager, "q", ENQUEUE).input().sequence());
        var delivery = reserve(manager, TURN);
        assertEquals(List.of("z", "a"), delivery.inputs().stream()
                .map(RuntimeInput::messageId).toList());
        assertThrows(UnsupportedOperationException.class, () -> delivery.inputs().clear());
        manager.commit(delivery, "request-1");
        assertTrue(manager.reserveForRequest(TURN).isEmpty());
        assertEquals(1, manager.snapshot().queuedCount());
    }

    @Test
    void duplicateIdKeepsOriginalSequenceContentAndCurrentStatus() {
        TurnInputManager manager = manager();
        var first = accept(manager, "one", STEER);
        var duplicate = manager.accept(SESSION, TURN, "one", "test", "changed", ENQUEUE);
        assertEquals(DUPLICATE, duplicate.code());
        assertEquals(first.input(), duplicate.input());
        assertFalse(duplicate.accepted());
        assertEquals(0, manager.snapshot().queuedCount());
        var delivery = reserve(manager, TURN);
        assertEquals(InputStatus.DELIVERY_RESERVED, accept(manager, "one", STEER).status());
        manager.commit(delivery, "request-1");
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, accept(manager, "one", STEER).status());
        assertEquals(2, accept(manager, "two", STEER).input().sequence());
    }

    @Test
    void wrongOrMissingTurnAndSessionCannotEnqueueOrInterrupt() {
        TurnInputManager manager = manager();
        for (DeliveryMode mode : DeliveryMode.values()) {
            assertEquals(STALE_TURN, manager.accept(SESSION, "old", "id", "test", "text", mode).code());
            assertEquals(STALE_TURN, manager.accept("other", TURN, "id", "test", "text", mode).code());
        }
        assertEquals(TurnState.RUNNING, manager.snapshot().state());
        assertEquals(0, manager.snapshot().queuedCount());
        TurnInputManager idle = new TurnInputManager(SESSION, true, TurnInputManager.Limits.defaults());
        assertEquals(STALE_TURN, accept(idle, "id", STEER).code());
    }

    @Test
    void validatesEveryRequiredFieldWithoutMutatingState() {
        TurnInputManager manager = manager();
        for (String blank : new String[]{null, "", " \t\n", "\u3000"}) {
            assertEquals(INVALID_INPUT, manager.accept(blank, TURN, "id", "test", "text", STEER).code());
            assertEquals(INVALID_INPUT, manager.accept(SESSION, blank, "id", "test", "text", STEER).code());
            assertEquals(INVALID_INPUT, manager.accept(SESSION, TURN, blank, "test", "text", STEER).code());
            assertEquals(INVALID_INPUT, manager.accept(SESSION, TURN, "id", "test", blank, STEER).code());
            assertEquals(INVALID_INPUT, manager.accept(SESSION, TURN, "id", blank, "text", STEER).code());
        }
        assertEquals(INVALID_INPUT, manager.accept(SESSION, TURN, "id", "test", "text", null).code());
        assertEquals(1, accept(manager, "first", STEER).input().sequence());
        assertThrows(IllegalArgumentException.class, () -> new TurnInputManager(" "));
    }

    @Test
    void steerLimitCountsAllAcceptedSteersEvenAfterCommit() {
        TurnInputManager manager = manager();
        for (int i = 0; i < 20; i++) {
            assertTrue(accept(manager, "s" + i, STEER).accepted());
        }
        manager.commit(reserve(manager, TURN), "request");
        assertEquals(STEER_LIMIT, accept(manager, "overflow", STEER).code());
        assertEquals(DUPLICATE, accept(manager, "s0", STEER).code());
        assertTrue(accept(manager, "queue", ENQUEUE).accepted());
        assertEquals(TurnInputManager.Finalization.FINALIZE, manager.finalizeOrContinue(TURN));
        manager.completeTurn(TURN);
        manager.startTurn("turn-2");
        manager.markRunning("turn-2");
        assertTrue(manager.accept(SESSION, "turn-2", "new", "test", "text", STEER).accepted());
    }

    @Test
    void queueHasSeparateFiftyMessageLimitAndRejectedIdCanBeRetried() {
        TurnInputManager manager = manager();
        for (int i = 0; i < 50; i++) {
            assertTrue(accept(manager, "q" + i, ENQUEUE).accepted());
        }
        assertEquals(QUEUE_FULL, accept(manager, "overflow", ENQUEUE).code());
        assertTrue(manager.input("overflow").isEmpty());
        assertTrue(accept(manager, "steer", STEER).accepted());
        manager.commit(reserve(manager, TURN), "request");
        manager.finalizeOrContinue(TURN);
        manager.completeTurn(TURN);
        assertEquals("q0", manager.startNextQueuedTurn("turn-2").orElseThrow().messageId());
        assertTrue(manager.accept(SESSION, "turn-2", "overflow", "test", "text", ENQUEUE).accepted());
        assertEquals(50, manager.snapshot().queuedCount());
    }

    @Test
    void utf8LimitUsesBytesAndAcceptsExactBoundary() {
        TurnInputManager manager = manager();
        String boundary = "中".repeat(5461) + "a";
        assertEquals(16384, boundary.getBytes(StandardCharsets.UTF_8).length);
        assertTrue(manager.accept(SESSION, TURN, "boundary", "test", boundary, STEER).accepted());
        assertEquals(TOO_LARGE, manager.accept(SESSION, TURN, "large", "test", boundary + "a", STEER).code());
        assertEquals(TOO_LARGE, manager.accept(SESSION, TURN, "emoji", "test", "😀".repeat(4097), ENQUEUE).code());
        assertTrue(manager.accept(SESSION, TURN, "emoji-ok", "test", "😀".repeat(4096), ENQUEUE).accepted());
        assertTrue(manager.accept(SESSION, TURN, "ascii", "test", "a".repeat(16384), STEER).accepted());
        assertEquals(TOO_LARGE, manager.accept(SESSION, TURN, "ascii-large", "test", "a".repeat(16385), STEER).code());
    }

    @Test
    void limitsAreConfigurableAndMustBePositive() {
        var manager = new TurnInputManager(SESSION, true, new TurnInputManager.Limits(1, 2, 3));
        manager.startTurn(TURN);
        manager.markRunning(TURN);
        assertTrue(manager.accept(SESSION, TURN, "s", "test", "中", STEER).accepted());
        assertEquals(STEER_LIMIT, manager.accept(SESSION, TURN, "s2", "test", "a", STEER).code());
        assertTrue(manager.accept(SESSION, TURN, "q1", "test", "a", ENQUEUE).accepted());
        assertTrue(manager.accept(SESSION, TURN, "q2", "test", "a", ENQUEUE).accepted());
        assertEquals(QUEUE_FULL, manager.accept(SESSION, TURN, "q3", "test", "a", ENQUEUE).code());
        assertEquals(TOO_LARGE, manager.accept(SESSION, TURN, "long", "test", "中文", STEER).code());
        assertThrows(IllegalArgumentException.class, () -> new TurnInputManager.Limits(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TurnInputManager.Limits(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TurnInputManager.Limits(1, 1, 0));
    }

    @Test
    void reserveCommitAndRequeueHaveDistinctObservableStates() {
        TurnInputManager manager = manager();
        assertEquals(InputStatus.PENDING_DELIVERY, accept(manager, "a", STEER).status());
        accept(manager, "b", STEER);
        var first = reserve(manager, TURN);
        assertEquals(0, manager.snapshot().pendingCount());
        assertEquals(2, manager.snapshot().reservedCount());
        assertEquals(InputStatus.DELIVERY_RESERVED, manager.input("a").orElseThrow().status());
        assertNull(manager.input("a").orElseThrow().requestId());
        accept(manager, "c", STEER);
        assertThrows(IllegalStateException.class, () -> manager.reserveForRequest(TURN));
        assertEquals(TurnInputManager.Finalization.CONTINUE, manager.finalizeOrContinue(TURN));
        manager.requeue(first);
        assertEquals(InputStatus.PENDING_DELIVERY, manager.input("a").orElseThrow().status());
        var second = reserve(manager, TURN);
        assertEquals(List.of("a", "b", "c"), second.inputs().stream().map(RuntimeInput::messageId).toList());
        assertThrows(IllegalStateException.class, () -> manager.commit(first, "stale-request"));
        assertThrows(IllegalStateException.class, () -> manager.requeue(first));
        assertThrows(IllegalArgumentException.class, () -> manager.commit(second, " "));
        manager.commit(second, "request-2");
        for (String id : List.of("a", "b", "c")) {
            var input = manager.input(id).orElseThrow();
            assertEquals(InputStatus.INCLUDED_IN_REQUEST, input.status());
            assertEquals("request-2", input.requestId());
        }
        assertTrue(manager.reserveForRequest(TURN).isEmpty());
        assertThrows(IllegalStateException.class, () -> manager.requeue(second));
        assertThrows(IllegalStateException.class, () -> manager.commit(second, "again"));
    }

    @Test
    void reservationCannotBeUsedByAnotherManager() {
        TurnInputManager first = manager();
        TurnInputManager second = manager();
        accept(first, "a", STEER);
        accept(second, "a", STEER);
        var delivery = reserve(first, TURN);
        assertThrows(IllegalStateException.class, () -> second.commit(delivery, "request"));
        assertThrows(IllegalStateException.class, () -> second.requeue(delivery));
        assertEquals(1, second.snapshot().pendingCount());
    }

    @Test
    void reservedMessagesAlsoPreventFinalization() {
        TurnInputManager manager = manager();
        accept(manager, "a", STEER);
        var delivery = reserve(manager, TURN);
        assertEquals(TurnInputManager.Finalization.CONTINUE, manager.finalizeOrContinue(TURN));
        assertThrows(IllegalStateException.class, () -> manager.completeTurn(TURN));
        manager.commit(delivery, "request");
        assertEquals(TurnInputManager.Finalization.FINALIZE, manager.finalizeOrContinue(TURN));
        assertEquals(STALE_TURN, accept(manager, "late", STEER).code());
        manager.completeTurn(TURN);
    }

    @Test
    void queueStartsOneNewTurnAtATimeAndNeverLeaksIntoCurrentRequest() {
        TurnInputManager manager = manager();
        assertEquals(InputStatus.ACCEPTED, accept(manager, "q1", ENQUEUE).status());
        accept(manager, "q2", ENQUEUE);
        assertTrue(manager.reserveForRequest(TURN).isEmpty());
        assertTrue(manager.startNextQueuedTurn("turn-2").isEmpty());
        manager.finalizeOrContinue(TURN);
        assertTrue(manager.startNextQueuedTurn("turn-2").isEmpty());
        manager.completeTurn(TURN);
        RuntimeInput first = manager.startNextQueuedTurn("turn-2").orElseThrow();
        assertEquals("q1", first.messageId());
        assertEquals(TURN, first.expectedTurnId());
        assertEquals("turn-2", manager.input("q1").orElseThrow().deliveryTurnId());
        assertEquals(TurnState.STARTING, manager.snapshot().state());
        manager.markRunning("turn-2");
        var delivery = reserve(manager, "turn-2");
        manager.requeue(delivery);
        var retry = reserve(manager, "turn-2");
        assertEquals(List.of(first), retry.inputs());
        manager.commit(retry, "request-q1");
        manager.finalizeOrContinue("turn-2");
        manager.completeTurn("turn-2");
        assertEquals("q2", manager.startNextQueuedTurn("turn-3").orElseThrow().messageId());
        assertEquals(0, manager.snapshot().queuedCount());
    }

    @Test
    void interruptPreservesQueueButRequiresConfirmedStopAndExplicitResume() {
        TurnInputManager manager = manager();
        accept(manager, "q", ENQUEUE);
        accept(manager, "s", STEER);
        assertEquals(InputStatus.INTERRUPT_REQUESTED, accept(manager, "cancel", INTERRUPT).status());
        assertEquals(TurnState.CANCELLING, manager.snapshot().state());
        assertTrue(manager.snapshot().queuePaused());
        assertEquals(STALE_TURN, accept(manager, "late", STEER).code());
        assertTrue(manager.startNextQueuedTurn("turn-2").isEmpty());
        assertThrows(IllegalStateException.class, manager::resumeQueue);
        manager.finishCancellation(TURN);
        assertEquals(InputStatus.CANCELLED, manager.input("s").orElseThrow().status());
        assertEquals(InputStatus.ACCEPTED, manager.input("q").orElseThrow().status());
        assertTrue(manager.startNextQueuedTurn("turn-2").isEmpty());
        manager.resumeQueue();
        assertEquals("q", manager.startNextQueuedTurn("turn-2").orElseThrow().messageId());
    }

    @Test
    void interruptMustResolveReservationWithoutLyingAboutSubmittedRequests() {
        TurnInputManager manager = manager();
        accept(manager, "s", STEER);
        var delivery = reserve(manager, TURN);
        accept(manager, "cancel", INTERRUPT);
        assertThrows(IllegalStateException.class, () -> manager.finishCancellation(TURN));
        manager.commit(delivery, "already-submitted");
        manager.finishCancellation(TURN);
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, manager.input("s").orElseThrow().status());
        assertEquals("already-submitted", manager.input("s").orElseThrow().requestId());
    }

    @Test
    void unsentReservationCanBeReturnedDuringCancellation() {
        TurnInputManager manager = manager();
        accept(manager, "s", STEER);
        var delivery = reserve(manager, TURN);
        accept(manager, "cancel", INTERRUPT);
        manager.requeue(delivery);
        manager.finishCancellation(TURN);
        assertEquals(InputStatus.CANCELLED, manager.input("s").orElseThrow().status());
        assertNull(manager.input("s").orElseThrow().requestId());
    }

    @Test
    void unknownDeliveryCanFailWithoutRequeueAndKeepsTraceAcrossTurns() {
        TurnInputManager manager = manager();
        var first = accept(manager, "a", STEER).input();
        var second = accept(manager, "b", STEER).input();
        var delivery = reserve(manager, TURN);
        accept(manager, "pending", STEER);
        accept(manager, "q", ENQUEUE);
        // 传输结果为 UNKNOWN；不能用 commit 或 requeue 假装已知发送结果。
        assertThrows(IllegalStateException.class, () -> manager.failTurn(TURN));
        manager.markDeliveryUnknown(delivery, "unknown-request");
        assertEquals(TurnState.RUNNING, manager.snapshot().state());
        assertEquals(0, manager.snapshot().reservedCount());
        assertEquals(1, manager.snapshot().pendingCount());
        var remaining = reserve(manager, TURN);
        assertEquals(List.of("pending"), remaining.inputs().stream().map(RuntimeInput::messageId).toList());
        manager.requeue(remaining);
        assertDoesNotThrow(() -> manager.failTurn(TURN));
        assertEquals(TurnState.FAILED, manager.snapshot().state());
        assertEquals(0, manager.snapshot().reservedCount());
        assertEquals(0, manager.snapshot().pendingCount());
        for (RuntimeInput input : List.of(first, second)) {
            var view = manager.input(input.messageId()).orElseThrow();
            assertEquals(input, view.input());
            assertEquals(InputStatus.DELIVERY_UNCERTAIN, view.status());
            assertEquals(TURN, view.deliveryTurnId());
            assertEquals("unknown-request", view.requestId());
        }
        assertEquals(InputStatus.CANCELLED, manager.input("pending").orElseThrow().status());
        assertEquals(InputStatus.ACCEPTED, manager.input("q").orElseThrow().status());
        assertTrue(manager.snapshot().queuePaused());
        assertTrue(manager.startNextQueuedTurn("turn-2").isEmpty());
        manager.resumeQueue();
        assertEquals("q", manager.startNextQueuedTurn("turn-2").orElseThrow().messageId());
        manager.markRunning("turn-2");
        var next = reserve(manager, "turn-2");
        assertEquals(List.of("q"), next.inputs().stream().map(RuntimeInput::messageId).toList());
        manager.commit(next, "next-request");
        assertTrue(manager.reserveForRequest("turn-2").isEmpty());
        var duplicate = manager.accept(SESSION, "turn-2", "a", "test", "changed", STEER);
        assertEquals(DUPLICATE, duplicate.code());
        assertEquals(first, duplicate.input());
        assertEquals(InputStatus.DELIVERY_UNCERTAIN, duplicate.status());
        assertEquals("unknown-request", manager.input("a").orElseThrow().requestId());
        assertEquals(TURN, manager.input("a").orElseThrow().deliveryTurnId());
        assertThrows(IllegalStateException.class, () -> manager.requeue(delivery));
        assertThrows(IllegalStateException.class, () -> manager.commit(delivery, "retry"));
    }

    @Test
    void unknownDeliveryCanFinishCancellationWithoutOverwritingConfirmedDelivery() {
        TurnInputManager manager = manager();
        accept(manager, "confirmed", STEER);
        manager.commit(reserve(manager, TURN), "confirmed-request");
        var uncertain = accept(manager, "uncertain", STEER).input();
        var delivery = reserve(manager, TURN);
        accept(manager, "pending", STEER);
        accept(manager, "q", ENQUEUE);
        accept(manager, "cancel", INTERRUPT);
        // 取消期间也可能无法确定请求是否送达，仍需明确收尾。
        assertThrows(IllegalStateException.class, () -> manager.finishCancellation(TURN));
        manager.markDeliveryUnknown(delivery, "unknown-request");
        assertEquals(TurnState.CANCELLING, manager.snapshot().state());
        assertDoesNotThrow(() -> manager.finishCancellation(TURN));
        assertEquals(TurnState.CANCELLED, manager.snapshot().state());
        assertEquals(0, manager.snapshot().reservedCount());
        assertEquals(0, manager.snapshot().pendingCount());
        var view = manager.input("uncertain").orElseThrow();
        assertEquals(uncertain, view.input());
        assertEquals(InputStatus.DELIVERY_UNCERTAIN, view.status());
        assertEquals(TURN, view.deliveryTurnId());
        assertEquals("unknown-request", view.requestId());
        var duplicate = accept(manager, "uncertain", STEER);
        assertEquals(DUPLICATE, duplicate.code());
        assertEquals(InputStatus.DELIVERY_UNCERTAIN, duplicate.status());
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, manager.input("confirmed").orElseThrow().status());
        assertEquals("confirmed-request", manager.input("confirmed").orElseThrow().requestId());
        assertEquals(InputStatus.CANCELLED, manager.input("pending").orElseThrow().status());
        assertEquals(InputStatus.ACCEPTED, manager.input("q").orElseThrow().status());
        assertTrue(manager.snapshot().queuePaused());
        assertTrue(manager.startNextQueuedTurn("turn-2").isEmpty());
        assertThrows(IllegalStateException.class, () -> manager.requeue(delivery));
        assertThrows(IllegalStateException.class, () -> manager.commit(delivery, "retry"));
    }

    @Test
    void unknownDeliveryRequiresRequestIdAndRejectsForeignOrConsumedReservations() {
        TurnInputManager manager = manager();
        TurnInputManager other = manager();
        accept(manager, "s", STEER);
        accept(other, "s", STEER);
        var delivery = reserve(manager, TURN);
        var otherDelivery = reserve(other, TURN);
        var before = manager.input("s").orElseThrow();
        for (String invalid : new String[]{null, "", " \t\n", "\u3000"}) {
            assertThrows(IllegalArgumentException.class, () -> manager.markDeliveryUnknown(delivery, invalid));
        }
        assertThrows(IllegalStateException.class, () -> manager.markDeliveryUnknown(null, "request"));
        assertThrows(IllegalStateException.class, () -> other.markDeliveryUnknown(delivery, "foreign"));
        assertEquals(before, manager.input("s").orElseThrow());
        assertEquals(1, manager.snapshot().reservedCount());
        assertEquals(InputStatus.DELIVERY_RESERVED, other.input("s").orElseThrow().status());
        other.commit(otherDelivery, "confirmed");
        assertThrows(IllegalStateException.class, () -> other.markDeliveryUnknown(otherDelivery, "unknown"));
        assertEquals("confirmed", other.input("s").orElseThrow().requestId());
        assertEquals(InputStatus.INCLUDED_IN_REQUEST, other.input("s").orElseThrow().status());
        manager.requeue(delivery);
        var current = reserve(manager, TURN);
        assertThrows(IllegalStateException.class, () -> manager.markDeliveryUnknown(delivery, "stale"));
        assertEquals(1, manager.snapshot().reservedCount());
        manager.markDeliveryUnknown(current, "unknown");
        var uncertain = manager.input("s").orElseThrow();
        assertThrows(IllegalStateException.class, () -> manager.markDeliveryUnknown(current, "again"));
        assertThrows(IllegalStateException.class, () -> manager.requeue(current));
        assertThrows(IllegalStateException.class, () -> manager.commit(current, "late-confirmation"));
        assertEquals(uncertain, manager.input("s").orElseThrow());
        assertTrue(manager.reserveForRequest(TURN).isEmpty());
    }

    @Test
    void failureCancelsUndeliveredSteersAndPausesQueue() {
        TurnInputManager manager = manager();
        accept(manager, "s", STEER);
        accept(manager, "q", ENQUEUE);
        manager.failTurn(TURN);
        assertEquals(TurnState.FAILED, manager.snapshot().state());
        assertEquals(InputStatus.CANCELLED, manager.input("s").orElseThrow().status());
        assertTrue(manager.startNextQueuedTurn("turn-2").isEmpty());
        manager.resumeQueue();
        assertTrue(manager.startNextQueuedTurn("turn-2").isPresent());
    }

    @Test
    void turnTransitionsAreCheckedAndOldTurnCannotAffectNewTurn() {
        TurnInputManager manager = new TurnInputManager(SESSION, true, TurnInputManager.Limits.defaults());
        assertEquals(TurnState.IDLE, manager.snapshot().state());
        manager.startTurn(TURN);
        assertEquals(TurnState.STARTING, manager.snapshot().state());
        assertTrue(accept(manager, "early", STEER).accepted());
        assertThrows(IllegalStateException.class, () -> manager.reserveForRequest(TURN));
        assertThrows(IllegalStateException.class, () -> manager.startTurn("turn-2"));
        assertThrows(IllegalStateException.class, () -> manager.completeTurn(TURN));
        manager.markRunning(TURN);
        manager.waitForApproval(TURN);
        assertEquals(TurnState.WAITING_APPROVAL, manager.snapshot().state());
        assertTrue(accept(manager, "waiting", STEER).accepted());
        assertThrows(IllegalStateException.class, () -> manager.reserveForRequest(TURN));
        manager.markRunning(TURN);
        manager.commit(reserve(manager, TURN), "request");
        manager.finalizeOrContinue(TURN);
        manager.completeTurn(TURN);
        manager.startTurn("turn-2");
        manager.markRunning("turn-2");
        assertEquals(STALE_TURN, accept(manager, "late", STEER).code());
        assertThrows(IllegalStateException.class, () -> manager.finalizeOrContinue(TURN));
        assertThrows(IllegalStateException.class, () -> manager.failTurn(TURN));
        assertEquals(TurnState.RUNNING, manager.snapshot().state());
        assertTrue(manager.reserveForRequest("turn-2").isEmpty());
        manager.finalizeOrContinue("turn-2");
        manager.completeTurn("turn-2");
        assertThrows(IllegalArgumentException.class, () -> manager.startTurn(TURN));
        assertThrows(IllegalArgumentException.class, () -> manager.startTurn(" "));
    }

    @Test
    void deduplicationAndSequenceSurviveTurnChanges() {
        TurnInputManager manager = manager();
        accept(manager, "same", STEER);
        manager.commit(reserve(manager, TURN), "request");
        manager.finalizeOrContinue(TURN);
        manager.completeTurn(TURN);
        manager.startTurn("turn-2");
        manager.markRunning("turn-2");
        assertEquals(DUPLICATE, manager.accept(SESSION, "turn-2", "same", "test", "text", STEER).code());
        assertEquals(2, manager.accept(SESSION, "turn-2", "new", "test", "text", STEER).input().sequence());
    }

    @Test
    void featureFlagDefaultsOffAndIsReadOnlyAtConstruction() {
        String key = TurnInputManager.ENABLED_PROPERTY;
        String old = System.getProperty(key);
        try {
            System.clearProperty(key);
            TurnInputManager disabled = new TurnInputManager(SESSION);
            assertFalse(disabled.enabled());
            assertEquals(DISABLED, accept(disabled, "s", STEER).code());
            assertThrows(IllegalStateException.class, () -> disabled.startTurn(TURN));
            System.setProperty(key, "true");
            assertTrue(new TurnInputManager(SESSION).enabled());
            assertFalse(disabled.enabled());
            System.setProperty(key, "invalid");
            assertFalse(new TurnInputManager(SESSION).enabled());
        } finally {
            if (old == null) System.clearProperty(key);
            else System.setProperty(key, old);
        }
    }

    @Test
    void concurrentDuplicateSubmissionsOnlyOccupyOneSlot() throws Exception {
        TurnInputManager manager = manager();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<InputAcceptance>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    await(go);
                    return accept(manager, "same", STEER);
                }));
            }
            await(ready);
            go.countDown();
            int accepted = 0;
            for (var result : results) {
                var code = result.get(3, TimeUnit.SECONDS).code();
                if (code == ACCEPTED) accepted++;
                else assertEquals(DUPLICATE, code);
            }
            assertEquals(1, accepted);
            assertEquals(1, reserve(manager, TURN).inputs().size());
        } finally {
            go.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void finalizeAndAcceptRaceIsAtomicAcrossThreeHundredSeededInterleavings() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        Random random = new Random(0x51EE71L);
        int accepted = 0;
        int stale = 0;
        try {
            for (int i = 0; i < 300; i++) {
                TurnInputManager manager = manager();
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                CountDownLatch firstDone = new CountDownLatch(1);
                int order = random.nextInt(3); // 强制两种先后顺序，以及同时放行的真实竞争。
                Future<InputAcceptance> input = workers.submit(() -> {
                    ready.countDown();
                    await(go);
                    if (order == 1) await(firstDone);
                    try { return accept(manager, "last", STEER); }
                    finally { if (order == 0) firstDone.countDown(); }
                });
                Future<TurnInputManager.Finalization> close = workers.submit(() -> {
                    ready.countDown();
                    await(go);
                    if (order == 0) await(firstDone);
                    try { return manager.finalizeOrContinue(TURN); }
                    finally { if (order == 1) firstDone.countDown(); }
                });
                await(ready);
                go.countDown();
                InputAcceptance receipt = input.get(3, TimeUnit.SECONDS);
                var decision = close.get(3, TimeUnit.SECONDS);
                if (receipt.accepted()) {
                    accepted++;
                    assertEquals(TurnInputManager.Finalization.CONTINUE, decision);
                    var delivery = reserve(manager, TURN);
                    assertEquals(List.of(receipt.input()), delivery.inputs());
                    manager.commit(delivery, "request");
                    assertTrue(manager.reserveForRequest(TURN).isEmpty());
                    assertEquals(TurnInputManager.Finalization.FINALIZE, manager.finalizeOrContinue(TURN));
                } else {
                    stale++;
                    assertEquals(STALE_TURN, receipt.code());
                    assertEquals(TurnInputManager.Finalization.FINALIZE, decision);
                    assertTrue(manager.input("last").isEmpty());
                }
                manager.completeTurn(TURN);
                manager.startTurn("turn-2");
                manager.markRunning("turn-2");
                assertTrue(manager.reserveForRequest("turn-2").isEmpty());
                assertEquals(0, manager.snapshot().queuedCount());
            }
            assertTrue(accepted > 0);
            assertTrue(stale > 0);
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(3, TimeUnit.SECONDS), "协调超时，不能无限等待");
    }
}
