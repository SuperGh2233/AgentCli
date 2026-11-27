package com.haocli.agent.event;

import com.haocli.tool.ToolStatus;

/**
 * Agent 一轮执行过程中对外发出的事件，所有前端（交互终端、无界面模式、Runtime API、微信）都可以只消费它。
 *
 * <p>顺序约定（一轮用户输入）：{@link TurnStarted} → 每次模型请求先一条 {@link ModelRequestStarted}，然后若干
 * {@link ReasoningDelta} / {@link TextDelta}，再一条 {@link Usage}；模型要调工具时，每个调用一条
 * {@link ToolCallStarted}（同一批的最后一条 {@code lastInBatch=true}），整批执行完后每个调用一条
 * {@link ToolCallFinished}（被长度截断没执行的调用发 {@link ToolCallSkipped}）；过程中可能有 {@link Notice}；
 * 最后恰好一条 {@link TurnFinished}。内核抛出意外异常时不发 {@link TurnFinished}，由调用方报错。
 *
 * <p>交互终端（{@code TerminalEventRenderer}）、无界面模式、Runtime API 都只靠这些事件显示过程。
 * 事件里的文字都不带终端颜色代码；只有 {@link Notice#styledText()} 是给进程内终端用的带样式原文，不写进 JSON。
 */
public sealed interface AgentEvent permits AgentEvent.TurnStarted, AgentEvent.ReasoningDelta,
        AgentEvent.TextDelta, AgentEvent.ModelRequestStarted, AgentEvent.ToolCallStarted, AgentEvent.ToolCallFinished, AgentEvent.ToolCallSkipped,
        AgentEvent.Notice, AgentEvent.Usage, AgentEvent.ContextCompacted, AgentEvent.TurnFinished {

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

    /** 即将发起一次模型请求（含超预算后的收尾请求）。Steering 安全点不放行时，后面可能直接是 {@link TurnFinished}。 */
    record ModelRequestStarted() implements AgentEvent {
    }

    record ReasoningDelta(String text) implements AgentEvent {
    }

    record TextDelta(String text) implements AgentEvent {
    }

    /**
     * 模型请求调用一个工具（参数原文，可能不合法）。
     *
     * @param allowedByTurnPolicy 本轮工具策略是否放行（不放行的调用不执行，直接回一条拒绝结果；终端不显示它）
     * @param lastInBatch         是不是这次模型回复里的最后一个调用；终端等整批到齐再按工具名分组显示
     */
    record ToolCallStarted(String id, String name, String argumentsJson, boolean allowedByTurnPolicy,
                           boolean lastInBatch) implements AgentEvent {
        public ToolCallStarted(String id, String name, String argumentsJson) {
            this(id, name, argumentsJson, true, true);
        }
    }

    /**
     * 工具执行结束。output 是还给模型之前的原始结果文字（未加不可信标记）；status 是细分状态，
     * success 等于 {@code status == SUCCESS}（两者不一致时以 status 为准）。
     */
    record ToolCallFinished(String id, String name, boolean success, String output, ToolStatus status)
            implements AgentEvent {
        public ToolCallFinished {
            status = status == null ? (success ? ToolStatus.SUCCESS : ToolStatus.FAILED) : status;
            success = status.successful();
        }

        public ToolCallFinished(String id, String name, boolean success, String output) {
            this(id, name, success, output, null);
        }
    }

    /** 工具调用没有执行，比如参数被长度截断。 */
    record ToolCallSkipped(String id, String name, String reason) implements AgentEvent {
    }

    /**
     * 给用户的提示，比如压缩了上下文、检测到重复调用、预算触发。
     *
     * @param text             不带颜色代码、去掉首尾空白的文字（对外格式用这个）
     * @param styledText       终端显示用的原文，可能带颜色代码；只在进程内使用
     * @param attachedToAnswer 内核已经把这句拼进返回给调用方的回答文字里（回答没有流式显示时），终端不要再单独打印
     */
    record Notice(String text, String styledText, boolean attachedToAnswer) implements AgentEvent {
        public Notice(String text) {
            this(text, text, false);
        }
    }

    /**
     * 上下文处理策略第 2–5 层改动了对话（每次改动一条），供评测统计各层触发次数和节省的 Token。
     *
     * @param trigger            {@code automatic}（请求前按阈值）或 {@code emergency}（模型报上下文超长）
     * @param trimmed            第 2 层历史裁剪处理的条数
     * @param folded             第 3 层局部折叠处理的条数
     * @param clearedToolResults 第 4 层清理的旧工具结果条数
     * @param summarized         第 5 层是否做了摘要压缩
     * @param beforeTokens       压缩前本次请求的估算 Token
     * @param afterTokens        压缩后本次请求的估算 Token
     */
    record ContextCompacted(String trigger, int trimmed, int folded, int clearedToolResults, boolean summarized,
                            long beforeTokens, long afterTokens) implements AgentEvent {
        public long savedTokens() {
            return Math.max(0L, beforeTokens - afterTokens);
        }
    }

    /** 一次模型请求的 Token 用量；reported=false 表示供应商没给完整用量。 */
    record Usage(int inputTokens, int outputTokens, int cachedInputTokens, boolean reported) implements AgentEvent {
    }

    /**
     * 一轮结束。answer 是模型的原始回答（不含思考过程和终端提示）；部分完成时是整理出的部分结果；
     * 取消和失败时是给用户的说明。Token 是本轮累计。
     *
     * <p>reason 是机器可读的结束原因，completed / cancelled 时为 null：失败时是 {@code llm_<分类>}
     * （见 {@code LlmFailureClassifier.Category}，比如 {@code llm_transient}、{@code llm_billing}），
     * 部分完成时是预算原因（{@code token_budget_exceeded}、{@code hard_iteration_limit}、{@code time_limit_exceeded}、
     * {@code stagnation_detected}）或 {@code context_overflow}。
     */
    record TurnFinished(String turnId, TurnStatus status, String answer, int modelRequests, long durationMs,
                        long inputTokens, long outputTokens, long cachedInputTokens, String reason)
            implements AgentEvent {
        public TurnFinished(String turnId, TurnStatus status, String answer, int modelRequests, long durationMs,
                            long inputTokens, long outputTokens, long cachedInputTokens) {
            this(turnId, status, answer, modelRequests, durationMs, inputTokens, outputTokens, cachedInputTokens, null);
        }
    }
}
