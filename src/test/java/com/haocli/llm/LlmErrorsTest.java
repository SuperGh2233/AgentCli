package com.haocli.llm;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmErrorsTest {

    @Test
    void recognisesContextOverflowFromCommonProviders() {
        assertTrue(LlmErrors.isContextOverflow(new IOException(
                "API请求失败: 400 - This model's maximum context length is 131072 tokens")));
        assertTrue(LlmErrors.isContextOverflow(new IOException(
                "API请求失败: 400 - {\"error\":{\"code\":\"context_length_exceeded\"}}")));
        assertTrue(LlmErrors.isContextOverflow(new IOException("API请求失败: 413 - request too large")));
        assertTrue(LlmErrors.isContextOverflow(new IOException("API请求失败: 400 - 输入超出模型上下文长度")));
    }

    @Test
    void otherFailuresAreNotOverflow() {
        assertFalse(LlmErrors.isContextOverflow(new IOException("API请求失败: 401 - invalid api key")));
        assertFalse(LlmErrors.isContextOverflow(new IOException("API请求失败: 429 - rate limit")));
        assertFalse(LlmErrors.isContextOverflow(new IOException("connection reset")));
        assertFalse(LlmErrors.isContextOverflow(null));
    }
}
