package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.runtime.input.RuntimeInput;
import com.paicli.runtime.input.TurnInputManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 3A: an opt-in, in-memory handoff from accepted Steering inputs to the
 * NEXT ReAct model request. Does not read a terminal, start a turn, or grant tools.
 * Main does not install this yet.
 */
final class ReActSteeringSafePoint {
    enum FinalizationDecision { CONTINUE, FINALIZE, CANCELLED }

    record Prepared(boolean proceed, TurnInputManager.Delivery delivery,
                    String correlationId,
                    List<LlmClient.Message> requestMessages) {
        Prepared {
            requestMessages = List.copyOf(requestMessages);
        }
    }

    private final TurnInputManager manager;
    private final String turnId;

    ReActSteeringSafePoint(TurnInputManager manager, String turnId) {
        this.manager = Objects.requireNonNull(manager, "manager");
        if (!manager.enabled()) throw new IllegalArgumentException("Steering is disabled");
        if (turnId == null || turnId.isBlank()) throw new IllegalArgumentException("turnId is required");
        this.turnId = turnId;
    }

    /** Called by the ReAct owner only after all prior tool results are appended. */
    Prepared reserve(List<LlmClient.Message> history) {
        Objects.requireNonNull(history, "history");
        var reservation = manager.reserveIfRunning(turnId);
        if (!reservation.proceed() || reservation.delivery() == null) {
            return new Prepared(reservation.proceed(), null, null, history);
        }
        List<LlmClient.Message> nextRequest = new ArrayList<>(history);
        for (RuntimeInput input : reservation.delivery().inputs()) {
            // Input provenance remains with the existing session's acceptance
            // rules. Tool authority stays frozen in TurnToolPolicy; text alone
            // MUST NOT expand allowed tools or URL permissions.
            nextRequest.add(LlmClient.Message.user(input.content()));
        }
        return new Prepared(true, reservation.delivery(),
                UUID.randomUUID().toString(), nextRequest);
    }

    /** Only after the model call returns successfully: never mark a prompt as executed. */
    void commit(Prepared prepared) {
        manager.commit(prepared.delivery(), prepared.correlationId());
    }

    /** If sending threw, delivery might have happened. Never requeue automatically. */
    void uncertain(Prepared prepared) {
        manager.markDeliveryUnknown(prepared.delivery(), prepared.correlationId());
    }

    /** 请求尚未调用时取消，可以安全退回。 */
    void requeue(Prepared prepared) {
        manager.requeue(prepared.delivery());
    }

    /** Atomically closes the input window unless a cancellation already won it. */
    FinalizationDecision finalizeOrContinue() {
        try {
            return manager.finalizeOrContinue(turnId) == TurnInputManager.Finalization.CONTINUE
                    ? FinalizationDecision.CONTINUE : FinalizationDecision.FINALIZE;
        } catch (IllegalStateException stateError) {
            if (manager.snapshot().state() == com.paicli.runtime.input.TurnState.CANCELLING) {
                return FinalizationDecision.CANCELLED;
            }
            throw stateError;
        }
    }

    void completeTurn() {
        manager.completeTurn(turnId);
    }

    List<LlmClient.Message> additions(Prepared prepared) {
        List<LlmClient.Message> inputs = new ArrayList<>();
        for (RuntimeInput input : prepared.delivery().inputs()) {
            inputs.add(LlmClient.Message.user(input.content()));
        }
        return List.copyOf(inputs);
    }
}
