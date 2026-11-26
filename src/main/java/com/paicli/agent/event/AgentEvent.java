package com.paicli.agent.event;

/**
 * Agent 一轮执行过程中对外发出的事件，所有前端（交互终端、无界面模式、Runtime API、微信）都可以只消费它。
 *
 * <p>顺序约定（一轮用户输入）：{@link TurnStarted} → 每次模型请求若干 {@link ReasoningDelta} /
 * {@link TextDelta}，然后 {@link Usage}；模型要调工具时，每个调用一条 {@link ToolCallStarted}，执行完后每个调用一条
 * {@link ToolCallFinished}（被长度截断没执行的调用发 {@link ToolCallSkipped}）；过程中可能有 {@link Notice}；
 * 最后恰好一条 {@link TurnFinished}。内核抛出意外异常时不发 {@link TurnFinished}，由调用方报错。
 *
 * <p>事件里的文字都不带终端颜色代码。
 */
public sealed interface AgentEvent permits AgentEvent.TurnStarted, AgentEvent.ReasoningDelta,
        AgentEvent.TextDelta, AgentEvent.ToolCallStarted, AgentEvent.ToolCallFinished, AgentEvent.ToolCallSkipped,
        AgentEvent.Notice, AgentEvent.Usage, AgentEvent.TurnFinished {

    /** 一轮的结束状态。 */
    enum TurnStatus {
        /** 模型给出了最终回复。 */
        COMPLETED,
        /** 超出预算或判定停滞，整理出了部分结果。 */
        PARTIAL,
        CANCELLED,
        /** 调用模型失败。 */
        FAILED
    }

    record TurnStarted(String turnId) implements AgentEvent {
    }

    record ReasoningDelta(String text) implements AgentEvent {
    }

    record TextDelta(String text) implements AgentEvent {
    }

    /** 模型请求调用一个工具（参数原文，可能不合法）。 */
    record ToolCallStarted(String id, String name, String argumentsJson) implements AgentEvent {
    }

    /** 工具执行结束。output 是还给模型之前的原始结果文字（未加不可信标记）。 */
    record ToolCallFinished(String id, String name, boolean success, String output) implements AgentEvent {
    }

    /** 工具调用没有执行，比如参数被长度截断。 */
    record ToolCallSkipped(String id, String name, String reason) implements AgentEvent {
    }

    /** 给用户的提示，比如压缩了上下文、检测到重复调用、预算触发。 */
    record Notice(String text) implements AgentEvent {
    }

    /** 一次模型请求的 Token 用量；reported=false 表示供应商没给完整用量。 */
    record Usage(int inputTokens, int outputTokens, int cachedInputTokens, boolean reported) implements AgentEvent {
    }

    /**
     * 一轮结束。answer 是模型的原始回答（不含思考过程和终端提示）；部分完成时是整理出的部分结果；
     * 取消和失败时是给用户的说明。Token 是本轮累计。
     */
    record TurnFinished(String turnId, TurnStatus status, String answer, int modelRequests, long durationMs,
                        long inputTokens, long outputTokens, long cachedInputTokens) implements AgentEvent {
    }
}
