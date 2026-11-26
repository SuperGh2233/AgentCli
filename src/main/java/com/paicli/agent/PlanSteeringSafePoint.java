package com.paicli.agent;

import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.runtime.input.DeliveryMode;
import com.paicli.runtime.input.RuntimeInput;
import com.paicli.runtime.input.TurnInputManager;
import com.paicli.runtime.input.TurnState;

import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/** 仅由 Plan 执行线程在完整 DAG 批次收尾后使用；不读终端，不启动 Queue。 */
final class PlanSteeringSafePoint {
    enum FinalizationDecision { CONTINUE, FINALIZE, CANCELLED }
    record Prepared(boolean proceed, TurnInputManager.Delivery delivery,
                    String requestId, String feedback) { }

    private final TurnInputManager manager;
    private final String turnId;
    private final StringBuilder includedFeedback = new StringBuilder();

    PlanSteeringSafePoint(TurnInputManager manager, String turnId) {
        this.manager = Objects.requireNonNull(manager, "manager");
        if (!manager.enabled()) throw new IllegalArgumentException("Steering is disabled");
        if (turnId == null || turnId.isBlank()) throw new IllegalArgumentException("turnId is required");
        this.turnId = turnId;
    }

    Prepared reserve() {
        var reservation = manager.reserveIfRunning(turnId);
        var delivery = reservation.delivery();
        if (delivery == null) return new Prepared(reservation.proceed(), null, null, "");
        // startNextQueuedTurn 的初始 ENQUEUE 必须由调用方单独消费，不能当作计划修订。
        if (delivery.inputs().stream().anyMatch(input -> input.mode() != DeliveryMode.STEER
                || !turnId.equals(input.expectedTurnId()))) {
            manager.requeue(delivery);
            throw new IllegalStateException("计划安全点只接收当前 Turn 的 STEER，Queue 初始输入须由调用方处理");
        }
        String feedback = delivery.inputs().stream().sorted(Comparator.comparingLong(RuntimeInput::sequence))
                .map(input -> "补充要求 #" + input.sequence() + "：\n" + input.content())
                .collect(Collectors.joining("\n\n"));
        return new Prepared(true, delivery, UUID.randomUUID().toString(), feedback);
    }

    void commit(Prepared prepared, ConversationLedger ledger) {
        manager.commit(prepared.delivery(), prepared.requestId());
        includedFeedback.append(prepared.feedback()).append("\n");
        for (RuntimeInput input : prepared.delivery().inputs()) {
            ledger.appendMessage("plan", "plan-agent", "runtime_steer_included",
                    LlmClient.Message.user(input.content()));
        }
    }

    void uncertain(Prepared prepared) {
        manager.markDeliveryUnknown(prepared.delivery(), prepared.requestId());
    }

    void requeue(Prepared prepared) { manager.requeue(prepared.delivery()); }

    String includedFeedback() { return includedFeedback.toString(); }

    boolean isRunning() {
        var snapshot = manager.snapshot();
        return turnId.equals(snapshot.turnId()) && snapshot.state() == TurnState.RUNNING;
    }

    FinalizationDecision finalizeOrContinue() {
        synchronized (manager) {
            if (!isRunning()) return FinalizationDecision.CANCELLED;
            return manager.finalizeOrContinue(turnId) == TurnInputManager.Finalization.CONTINUE
                    ? FinalizationDecision.CONTINUE : FinalizationDecision.FINALIZE;
        }
    }

    void completeTurn() { manager.completeTurn(turnId); }

    /** 失败/取消退出时释放输入窗口；保留已提交和交付不确定的记录。 */
    void stopIfActive() {
        synchronized (manager) {
            var snapshot = manager.snapshot();
            if (!turnId.equals(snapshot.turnId())) return;
            switch (snapshot.state()) {
                case CANCELLING -> manager.finishCancellation(turnId);
                case STARTING, RUNNING, WAITING_APPROVAL, FINALIZING -> manager.failTurn(turnId);
                default -> { }
            }
        }
    }
}
