package com.paicli.cli;

import com.paicli.runtime.input.DeliveryMode;
import com.paicli.runtime.input.InputAcceptance;
import com.paicli.runtime.input.InputStatus;
import com.paicli.runtime.input.TurnInputManager;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.paicli.cli.InputCoordinator.Mode.*;
import static com.paicli.cli.InputCoordinator.Route.*;
import static org.junit.jupiter.api.Assertions.*;

class InputCoordinatorTest {
    private final TurnInputManager manager = new TurnInputManager(
            "session", true, TurnInputManager.Limits.defaults());
    private final InputCoordinator coordinator = new InputCoordinator("session", manager, true);

    private void start() {
        manager.startTurn("turn");
        manager.markRunning("turn");
        assertTrue(coordinator.beginRun("turn"));
    }

    @Test
    void routesPlainEnterAndExplicitCommandsWithoutClaimingDelivery() {
        start();
        for (String line : new String[]{"中文\n第二行🙂", "/steer 调整要求", "/queue 下一个任务"}) {
            var result = coordinator.submit(coordinator.capture(), line);
            assertEquals(ACCEPTED, result.route());
            assertTrue(result.notice().contains("不代表模型已收到/执行"));
            assertNotEquals(InputStatus.INCLUDED_IN_REQUEST, result.acceptance().status());
        }
        assertEquals(2, manager.snapshot().pendingCount());
        assertEquals(1, manager.snapshot().queuedCount());
        var delivery = manager.reserveForRequest("turn").orElseThrow();
        assertEquals("中文\n第二行🙂", delivery.inputs().get(0).content());
        assertEquals("调整要求", delivery.inputs().get(1).content());
        assertEquals(DeliveryMode.STEER, delivery.inputs().get(1).mode());
    }

    @Test
    void statusDoesNotEnqueueOrExposeMessageContents() {
        start();
        coordinator.submit(coordinator.capture(), "private text");
        var result = coordinator.submit(coordinator.capture(), "/inputs");
        assertEquals(STATUS, result.route());
        assertEquals(1, result.snapshot().pendingCount());
        assertTrue(result.notice().contains("待交付 1 条"));
        assertFalse(result.notice().contains("private text"));
        assertTrue(result.notice().contains("不代表模型已收到/执行"));
    }

    @Test
    void rejectsEmptyArgumentsAndOtherCommandsDuringRun() {
        start();
        for (String line : new String[]{"/queue", "/steer  ", "/inputs extra", "/plan x", "/team x", "/hitl on", "/unknown"}) {
            assertEquals(REJECTED, coordinator.submit(coordinator.capture(), line).route(), line);
        }
        assertEquals(0, manager.snapshot().pendingCount());
        assertEquals(IGNORED, coordinator.submit(coordinator.capture(), " \n").route());
    }

    @Test
    void approvalAndPlanReviewOwnAllTheirInputsIncludingEsc() {
        start();
        for (var mode : new InputCoordinator.Mode[]{APPROVAL_INPUT, PLAN_REVIEW_INPUT}) {
            try (var interaction = coordinator.enterInteraction(mode)) {
                assertEquals(mode, coordinator.capture().mode());
                for (String text : new String[]{"", "y", "/queue not a task", "{\"path\":\"中文\"}"}) {
                    var result = coordinator.submit(coordinator.capture(), text);
                    assertEquals(INTERACTION, result.route());
                    assertEquals(text, result.text());
                }
                assertEquals(INTERACTION, coordinator.escape(coordinator.capture()).route());
                assertThrows(IllegalStateException.class, () -> coordinator.enterInteraction(APPROVAL_INPUT));
            }
        }
        assertEquals(RUNNING_INPUT, coordinator.capture().mode());
        assertEquals(0, manager.snapshot().pendingCount());
    }

    @Test
    void approvalRequestFromWorkerInvalidatesOldPromptInsteadOfApproving() throws Exception {
        start();
        var old = coordinator.capture();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var interaction = worker.submit(() -> coordinator.enterInteraction(APPROVAL_INPUT)).get(2, TimeUnit.SECONDS);
            try (interaction) {
                var result = coordinator.submit(old, "y");
                assertEquals(RETRY, result.route());
                assertEquals("y", result.text());
                assertEquals(0, manager.snapshot().pendingCount());
            }
        } finally {
            worker.shutdownNow();
        }
        assertEquals(RETRY, coordinator.submit(old, "y").route());
    }

    @Test
    void readEventsHaveOneOwnerAndTicketsCannotBeReplayedOrBorrowed() throws Exception {
        start();
        var ticket = coordinator.capture();
        var worker = Executors.newSingleThreadExecutor();
        try {
            var error = worker.submit(() -> assertThrows(IllegalStateException.class,
                    () -> coordinator.submit(ticket, "wrong thread"))).get(2, TimeUnit.SECONDS);
            assertNotNull(error);
        } finally {
            worker.shutdownNow();
        }
        assertEquals(ACCEPTED, coordinator.submit(ticket, "once").route());
        assertEquals(RETRY, coordinator.submit(ticket, "twice").route());
        var other = new InputCoordinator("session", manager, true);
        assertEquals(RETRY, other.submit(coordinator.capture(), "foreign ticket").route());
        assertEquals(1, manager.snapshot().pendingCount());
    }

    @Test
    void endRunPreservesPendingAndQueuedMessagesAndInvalidatesPrompt() {
        start();
        var steer = coordinator.submit(coordinator.capture(), "pending").acceptance().input();
        coordinator.submit(coordinator.capture(), "/queue later");
        var old = coordinator.capture();
        var result = coordinator.endRun("turn");
        assertEquals(1, result.snapshot().pendingCount());
        assertEquals(1, result.snapshot().queuedCount());
        assertTrue(result.notice().contains("不代表模型已收到/执行"));
        assertEquals(InputStatus.PENDING_DELIVERY, manager.input(steer.messageId()).orElseThrow().status());
        assertEquals(RETRY, coordinator.submit(old, "late").route());
        assertEquals(IDLE_INPUT, coordinator.capture().mode());
        assertEquals(FORWARD, coordinator.submit(coordinator.capture(), "/plan next").route());
    }

    @Test
    void rejectsStaleTurnFromManagerWithoutRetargeting() {
        start();
        var ticket = coordinator.capture();
        manager.finalizeOrContinue("turn");
        manager.completeTurn("turn");
        manager.startTurn("next");
        manager.markRunning("next");
        var result = coordinator.submit(ticket, "late");
        assertEquals(RETRY, result.route());
        assertEquals("late", result.text());
        assertTrue(result.notice().contains("保留为草稿"));
        assertEquals(InputAcceptance.Code.STALE_TURN, result.acceptance().code());
        assertEquals(0, manager.snapshot().pendingCount());
    }

    @Test
    void preservesLineEnteredAfterAtomicFinalizationAsNextPromptDraft() {
        start();
        var ticket = coordinator.capture();
        assertEquals(TurnInputManager.Finalization.FINALIZE, manager.finalizeOrContinue("turn"));
        var result = coordinator.submit(ticket, "刚好结束时输入的内容");
        assertEquals(RETRY, result.route());
        assertEquals("刚好结束时输入的内容", result.text());
        assertEquals(InputAcceptance.Code.STALE_TURN, result.acceptance().code());
        assertTrue(result.notice().contains("保留为草稿"));
        assertEquals(0, manager.snapshot().pendingCount());
    }

    @Test
    void onlyDecodedStandaloneEscRequestsInterruptAndPausesQueue() {
        start();
        coordinator.submit(coordinator.capture(), "/queue later");
        // 控制序列交给既有 JLine 解码；本组件不读字节，也不把带 ESC 的文本当取消。
        assertFalse(Main.decideEscCancel(27, "[A"));
        assertFalse(Main.decideEscCancel(27, "[Z"));
        assertFalse(Main.decideEscCancel(27, "[200~中文"));
        var result = coordinator.escape(coordinator.capture());
        assertEquals(ACCEPTED, result.route());
        assertEquals(DeliveryMode.INTERRUPT, result.acceptance().input().mode());
        assertTrue(manager.snapshot().queuePaused());
        assertEquals(1, manager.snapshot().queuedCount());
        assertEquals(REJECTED, coordinator.submit(coordinator.capture(), "after interrupt").route());
    }

    @Test
    void disabledAndNonInteractivePathsDoNotConsumeOrChangeManager() {
        var disabled = new TurnInputManager("session", false, TurnInputManager.Limits.defaults());
        for (var input : new InputCoordinator[]{new InputCoordinator("session", disabled, true),
                new InputCoordinator("session", manager, false)}) {
            assertFalse(input.enabled());
            assertFalse(input.beginRun("turn"));
            assertEquals(LEGACY, input.submit(input.capture(), "/queue untouched").route());
            assertEquals(LEGACY, input.escape(input.capture()).route());
        }
        assertEquals(0, manager.snapshot().queuedCount());
    }

    @Test
    void activeInteractionMustFinishBeforeRunEndsAndStaleCloseCannotResetNewMode() {
        start();
        var first = coordinator.enterInteraction(APPROVAL_INPUT);
        assertThrows(IllegalStateException.class, () -> coordinator.endRun("turn"));
        first.close();
        try (var second = coordinator.enterInteraction(PLAN_REVIEW_INPUT)) {
            first.close();
            assertEquals(PLAN_REVIEW_INPUT, coordinator.capture().mode());
        }
        assertThrows(IllegalStateException.class, () -> coordinator.endRun("wrong"));
        assertEquals(STATUS, coordinator.endRun("turn").route());
    }
}
