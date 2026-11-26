package com.paicli.llm.core;

/**
 * 一次请求的 Token 用量。reported=false 表示供应商没有返回完整用量，几个数字都不可信，
 * 不能当成“用了 0 个”。
 */
public record TokenUsage(int inputTokens, int outputTokens, int cachedInputTokens, boolean reported) {

    public static final TokenUsage NOT_REPORTED = new TokenUsage(0, 0, 0, false);

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || cachedInputTokens < 0) {
            throw new IllegalArgumentException("Token 数不能为负");
        }
    }
}
