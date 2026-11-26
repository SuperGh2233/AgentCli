package com.paicli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.llm.LlmClient;
import com.paicli.memory.MemoryEntry;
import com.paicli.memory.TokenBudget;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** 每次请求模型前，把这次请求的上下文构成（各角色 Token、图片、工具定义）写进日志，便于排查上下文膨胀。 */
final class RequestContextLogger {

    private RequestContextLogger() {
    }

    static void log(Logger log, String scope, List<LlmClient.Message> history, List<LlmClient.Tool> tools) {
        if (!log.isInfoEnabled()) {
            return;
        }
        int systemTokens = 0;
        int userTokens = 0;
        int assistantTokens = 0;
        int toolMessageTokens = 0;
        int imageParts = 0;
        int messages = 0;
        StringBuilder imageDetails = new StringBuilder();
        for (int messageIndex = 0; messageIndex < history.size(); messageIndex++) {
            LlmClient.Message msg = history.get(messageIndex);
            messages++;
            int tokens = TokenBudget.estimateMessagesTokens(List.of(msg));
            imageParts += msg.imagePartCount();
            appendImageDetails(imageDetails, msg, messageIndex);
            switch (msg.role()) {
                case "system" -> systemTokens += tokens;
                case "user" -> userTokens += tokens;
                case "assistant" -> assistantTokens += tokens;
                case "tool" -> toolMessageTokens += tokens;
                default -> {
                }
            }
        }
        int toolsSchemaTokens = 0;
        int toolCount = tools == null ? 0 : tools.size();
        if (tools != null && !tools.isEmpty()) {
            try {
                toolsSchemaTokens = MemoryEntry.estimateTokens(new ObjectMapper().writeValueAsString(tools));
            } catch (Exception e) {
                log.debug("Failed to estimate tools schema tokens", e);
            }
        }
        int estimatedTotal = systemTokens + userTokens + assistantTokens + toolMessageTokens + toolsSchemaTokens;
        log.info("LLM request context [{}]: messages={}, images={}, systemTokens={}, userTokens={}, assistantTokens={}, toolMessageTokens={}, tools={}, toolsSchemaTokens={}, estimatedTotal={}",
                scope, messages, imageParts, systemTokens, userTokens, assistantTokens, toolMessageTokens,
                toolCount, toolsSchemaTokens, estimatedTotal);
        if (!imageDetails.isEmpty()) {
            log.info("LLM request images [{}]: {}", scope, imageDetails);
        }
    }

    private static void appendImageDetails(StringBuilder sb, LlmClient.Message msg, int messageIndex) {
        if (msg == null || !msg.hasContentParts()) {
            return;
        }
        for (int partIndex = 0; partIndex < msg.contentParts().size(); partIndex++) {
            LlmClient.ContentPart part = msg.contentParts().get(partIndex);
            if (part == null || !part.isImage()) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            String payload = "image_url".equals(part.type()) ? part.imageUrl() : part.imageBase64();
            sb.append("#").append(messageIndex)
                    .append(".").append(partIndex)
                    .append(" role=").append(msg.role())
                    .append(" type=").append(part.type())
                    .append(" mime=").append(part.mimeType() == null ? "-" : part.mimeType())
                    .append(" payloadChars=").append(payload == null ? 0 : payload.length())
                    .append(" sha256=").append(shortSha256(payload));
        }
    }

    private static String shortSha256(String value) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
    }
}
