package com.haocli.llm.core;

import java.util.Objects;

/**
 * 一次模型调用的完整结果。
 *
 * @param message          助手消息（思考、文字、工具调用块）
 * @param stopReason       停止原因
 * @param usage            Token 用量
 * @param resolvedModel    供应商实际使用的模型 ID；没返回或前后不一致时为 null
 * @param rawFinishReason  供应商原始的停止原因字符串，排查问题用；拿不到时为 null
 */
public record ModelResponse(ChatMessage message, StopReason stopReason, TokenUsage usage,
                            String resolvedModel, String rawFinishReason) {

    public ModelResponse {
        Objects.requireNonNull(message, "message");
        if (message.role() != ChatMessage.Role.ASSISTANT) {
            throw new IllegalArgumentException("模型回复必须是助手消息");
        }
        stopReason = stopReason == null ? StopReason.UNKNOWN : stopReason;
        usage = usage == null ? TokenUsage.NOT_REPORTED : usage;
    }
}
