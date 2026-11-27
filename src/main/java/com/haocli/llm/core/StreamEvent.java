package com.haocli.llm.core;

/**
 * 模型流式输出的事件，所有供应商统一成这几种。
 *
 * <p>顺序约定：若干 {@link ReasoningDelta} / {@link TextDelta}（可能交错）→ 每个工具调用一条
 * {@link ToolCallCompleted}（按调用顺序）→ 至多一条 {@link UsageReported} → 最后恰好一条
 * {@link Completed}。请求失败时不发 {@link Completed}，由调用方法抛出异常。
 *
 * <p>工具调用只在整条回复结束、确认参数完整之后才发：OpenAI 兼容接口在出错重试时可能已经流出了一半的
 * 工具调用片段，DeepSeek 的 DSML 工具调用要等正文结束才能解析，提前发会让界面显示重复或错误的调用。
 */
public sealed interface StreamEvent permits StreamEvent.ReasoningDelta, StreamEvent.TextDelta,
        StreamEvent.ToolCallCompleted, StreamEvent.UsageReported, StreamEvent.Completed {

    /** 一段新的思考内容。 */
    record ReasoningDelta(String text) implements StreamEvent {
    }

    /** 一段新的正文。 */
    record TextDelta(String text) implements StreamEvent {
    }

    /** 一个参数已经完整的工具调用。index 是它在本条回复里的序号，从 0 开始。 */
    record ToolCallCompleted(int index, ContentBlock.ToolCall call) implements StreamEvent {
    }

    /** 供应商返回了完整的 Token 用量。 */
    record UsageReported(TokenUsage usage) implements StreamEvent {
    }

    /** 回复结束，带上完整结果。 */
    record Completed(ModelResponse response) implements StreamEvent {
    }
}
