package com.haocli.llm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 和厂商无关的一条对话消息：角色 + 按顺序排列的内容块。
 *
 * <p>工具结果单独作为一种角色（{@link Role#TOOL_RESULT}），一条结果对应一个工具调用 id，
 * 和 OpenAI 兼容接口一一对应；要发给把工具结果放在 user 消息里的接口（比如 Anthropic）时，
 * 由那家的转换层把相邻的结果合并。
 */
public record ChatMessage(Role role, List<ContentBlock> content, String toolCallId) {

    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL_RESULT
    }

    public ChatMessage {
        Objects.requireNonNull(role, "role");
        content = content == null ? List.of() : List.copyOf(content);
        if (role == Role.TOOL_RESULT && (toolCallId == null || toolCallId.isBlank())) {
            throw new IllegalArgumentException("工具结果必须带对应的工具调用 id");
        }
        if (role != Role.TOOL_RESULT && toolCallId != null) {
            throw new IllegalArgumentException("只有工具结果可以带工具调用 id");
        }
        for (ContentBlock block : content) {
            Objects.requireNonNull(block, "content block");
            if (role != Role.ASSISTANT
                    && (block instanceof ContentBlock.ToolCall || block instanceof ContentBlock.Reasoning)) {
                throw new IllegalArgumentException("只有助手消息可以包含工具调用或思考内容");
            }
        }
    }

    public static ChatMessage system(String text) {
        return new ChatMessage(Role.SYSTEM, List.of(new ContentBlock.Text(text)), null);
    }

    public static ChatMessage user(String text) {
        return new ChatMessage(Role.USER, List.of(new ContentBlock.Text(text)), null);
    }

    public static ChatMessage user(List<ContentBlock> blocks) {
        return new ChatMessage(Role.USER, blocks, null);
    }

    public static ChatMessage assistant(List<ContentBlock> blocks) {
        return new ChatMessage(Role.ASSISTANT, blocks, null);
    }

    public static ChatMessage toolResult(String toolCallId, String text) {
        return new ChatMessage(Role.TOOL_RESULT, List.of(new ContentBlock.Text(text)), toolCallId);
    }

    /** 所有文字块按顺序直接拼接；没有文字块时返回空串。 */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.Text text) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    /** 所有思考块按顺序拼接；没有思考块时返回空串。 */
    public String reasoning() {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.Reasoning reasoning) {
                sb.append(reasoning.text());
            }
        }
        return sb.toString();
    }

    public List<ContentBlock.ToolCall> toolCalls() {
        List<ContentBlock.ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : content) {
            if (block instanceof ContentBlock.ToolCall call) {
                calls.add(call);
            }
        }
        return List.copyOf(calls);
    }

    public boolean hasToolCalls() {
        return content.stream().anyMatch(ContentBlock.ToolCall.class::isInstance);
    }
}
