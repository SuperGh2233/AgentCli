package com.haocli.headless;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.haocli.agent.event.AgentEvent;

import java.util.Locale;

/**
 * 把 {@link AgentEvent} 转成一行 JSON。字段名用下划线风格，{@code type} 字段区分事件种类：
 * turn_started、model_request_started、reasoning_delta、text_delta、tool_call_started、tool_call_finished、
 * tool_call_skipped、notice、usage、turn_finished。这是对外格式，改字段名要同步更新文档。
 * 给进程内终端用的字段（Notice 的带样式原文和 attachedToAnswer、ToolCallStarted 的分批标记）不写进 JSON。
 * 无界面模式的 stream-json 和 Runtime API 的 {@code agent.*} 事件都用这一份格式。
 */
public final class AgentEventJson {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentEventJson() {
    }

    public static ObjectNode toJson(AgentEvent event) {
        ObjectNode node = MAPPER.createObjectNode();
        if (event instanceof AgentEvent.TurnStarted e) {
            node.put("type", "turn_started").put("turn_id", e.turnId());
        } else if (event instanceof AgentEvent.ModelRequestStarted) {
            node.put("type", "model_request_started");
        } else if (event instanceof AgentEvent.ReasoningDelta e) {
            node.put("type", "reasoning_delta").put("text", e.text());
        } else if (event instanceof AgentEvent.TextDelta e) {
            node.put("type", "text_delta").put("text", e.text());
        } else if (event instanceof AgentEvent.ToolCallStarted e) {
            node.put("type", "tool_call_started").put("id", e.id()).put("name", e.name())
                    .put("arguments", e.argumentsJson());
        } else if (event instanceof AgentEvent.ToolCallFinished e) {
            node.put("type", "tool_call_finished").put("id", e.id()).put("name", e.name())
                    .put("success", e.success()).put("status", e.status().wireName()).put("output", e.output());
        } else if (event instanceof AgentEvent.ToolCallSkipped e) {
            node.put("type", "tool_call_skipped").put("id", e.id()).put("name", e.name()).put("reason", e.reason());
        } else if (event instanceof AgentEvent.Notice e) {
            node.put("type", "notice").put("text", e.text());
        } else if (event instanceof AgentEvent.Usage e) {
            node.put("type", "usage")
                    .put("input_tokens", e.inputTokens())
                    .put("output_tokens", e.outputTokens())
                    .put("cached_input_tokens", e.cachedInputTokens())
                    .put("reported", e.reported());
        } else if (event instanceof AgentEvent.TurnFinished e) {
            node.put("type", "turn_finished")
                    .put("turn_id", e.turnId())
                    .put("status", status(e.status()))
                    .put("answer", e.answer())
                    .put("model_requests", e.modelRequests())
                    .put("duration_ms", e.durationMs())
                    .put("input_tokens", e.inputTokens())
                    .put("output_tokens", e.outputTokens())
                    .put("cached_input_tokens", e.cachedInputTokens());
        } else {
            node.put("type", "unknown");
        }
        return node;
    }

    /** 状态的对外写法：completed / partial / failed / cancelled。 */
    public static String status(AgentEvent.TurnStatus status) {
        return status.name().toLowerCase(Locale.ROOT);
    }
}
