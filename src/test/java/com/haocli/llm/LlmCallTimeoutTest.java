package com.haocli.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 单次模型请求默认不设总时长上限：长回复只要一直在输出就不被掐断（以前 600s 会掐断，
 * Terminal-Bench regex-chess 经较慢网关时稳定复现）；连接卡死仍由 300s 的读取间隔发现。
 */
class LlmCallTimeoutTest {

    @Test
    void sharedClientHasNoTotalCallTimeoutButKeepsTheIdleReadTimeout() {
        if (System.getProperty(AbstractOpenAiCompatibleClient.CALL_TIMEOUT_PROPERTY) == null) {
            assertEquals(0, AbstractOpenAiCompatibleClient.SHARED_HTTP_CLIENT.callTimeoutMillis(),
                    "默认不限总时长");
        }
        if (System.getProperty("haocli.llm.read.timeout.seconds") == null) {
            assertEquals(300_000, AbstractOpenAiCompatibleClient.SHARED_HTTP_CLIENT.readTimeoutMillis(),
                    "卡死检测（两次读之间最多 300 秒）不变");
        }
    }

    @Test
    void explicitPositiveValueSetsAHardCap() {
        String old = System.getProperty(AbstractOpenAiCompatibleClient.CALL_TIMEOUT_PROPERTY);
        try {
            System.setProperty(AbstractOpenAiCompatibleClient.CALL_TIMEOUT_PROPERTY, "900");
            assertEquals(900, AbstractOpenAiCompatibleClient.callTimeoutSeconds());
            System.setProperty(AbstractOpenAiCompatibleClient.CALL_TIMEOUT_PROPERTY, "abc");
            assertEquals(0, AbstractOpenAiCompatibleClient.callTimeoutSeconds(), "写错按不限处理");
            System.setProperty(AbstractOpenAiCompatibleClient.CALL_TIMEOUT_PROPERTY, "-5");
            assertEquals(0, AbstractOpenAiCompatibleClient.callTimeoutSeconds());
        } finally {
            if (old == null) System.clearProperty(AbstractOpenAiCompatibleClient.CALL_TIMEOUT_PROPERTY);
            else System.setProperty(AbstractOpenAiCompatibleClient.CALL_TIMEOUT_PROPERTY, old);
        }
    }
}
