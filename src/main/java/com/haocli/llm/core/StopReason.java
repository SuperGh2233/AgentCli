package com.haocli.llm.core;

import java.util.Locale;

/**
 * 模型为什么停下来。Agent 循环靠它区分“正常答完”“要调工具”“被长度截断”，
 * 而不是只看有没有工具调用：被截断时工具参数可能是半截 JSON，不能当成正常调用。
 */
public enum StopReason {
    /** 正常答完。 */
    END_TURN,
    /** 停下来等工具结果。 */
    TOOL_USE,
    /** 输出达到长度上限被截断；如果同时有工具调用，参数可能不完整。 */
    MAX_TOKENS,
    /** 被供应商的内容审核拦下。 */
    CONTENT_FILTER,
    /** 供应商没给原因，或给了不认识的原因。 */
    UNKNOWN;

    /**
     * 按 OpenAI 兼容接口的 finish_reason 换算。
     *
     * <p>截断优先于工具调用：length 加上工具调用说明参数可能被截断，必须报 MAX_TOKENS。
     * 其他情况只要最终结果里有工具调用就算 TOOL_USE——DeepSeek 把工具调用写进正文（DSML）时，
     * 线上的 finish_reason 是 stop，但转换后确实有工具调用。
     */
    public static StopReason fromOpenAiFinishReason(String finishReason, boolean hasToolCalls) {
        String normalized = finishReason == null ? "" : finishReason.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("length") || normalized.equals("max_tokens")) {
            return MAX_TOKENS;
        }
        if (normalized.equals("content_filter") || normalized.equals("sensitive")) {
            return CONTENT_FILTER;
        }
        if (hasToolCalls) {
            return TOOL_USE;
        }
        if (normalized.isEmpty()) {
            return END_TURN;
        }
        // 说是 tool_calls 却没解析出任何调用，属于异常回复，不算正常答完。
        return switch (normalized) {
            case "stop", "end_turn" -> END_TURN;
            default -> UNKNOWN;
        };
    }
}
