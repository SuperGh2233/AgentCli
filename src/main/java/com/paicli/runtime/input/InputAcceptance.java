package com.paicli.runtime.input;

/** 接收回执；重复 ID 返回原消息和当前状态，拒绝的新消息没有分配 sequence。 */
public record InputAcceptance(Code code, RuntimeInput input, InputStatus status) {
    public enum Code {
        ACCEPTED, DUPLICATE, DISABLED, INVALID_INPUT, STALE_TURN,
        TOO_LARGE, STEER_LIMIT, QUEUE_FULL
    }

    public boolean accepted() {
        return code == Code.ACCEPTED;
    }
}
