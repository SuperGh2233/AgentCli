package com.paicli.runtime.input;

/** 一次任务（Turn）的状态；CANCELLING 仍需执行方确认已经停止。 */
public enum TurnState {
    IDLE, STARTING, RUNNING, WAITING_APPROVAL, FINALIZING,
    CANCELLING, COMPLETED, CANCELLED, FAILED
}
