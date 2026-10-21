package com.paicli.runtime.input;

import java.time.Instant;

/** 接收成功的不可变消息；sequence 由管理器在锁内分配，expectedTurnId 保留原始目标。 */
public record RuntimeInput(String messageId, String sessionId, String expectedTurnId,
                           long sequence, String source, String content,
                           DeliveryMode mode, Instant receivedAt) {
}
