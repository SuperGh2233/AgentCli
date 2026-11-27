package com.haocli.headless;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.haocli.agent.event.AgentEvent;
import com.haocli.agent.event.AgentEventListener;
import com.haocli.tool.ToolResultOffloader;

/**
 * 从一轮的 {@link AgentEvent} 统计出来的 harness 指标：模型请求数、工具调用数和成败、跳过数、提示数、Token。
 * 无界面模式把它放进结果 JSON，场景回归和真实模型对照实验都用同一套口径。
 *
 * @param toolCalls     真正交给执行的工具调用（{@code tool_call_started} 条数）
 * @param toolSkipped   没执行的调用，比如参数被长度截断
 * @param notices       提示条数（压缩、重复调用提醒、预算触发、截断等）
 */
public record HarnessMetrics(String status, int modelRequests, int toolCalls, int toolSucceeded, int toolFailed,
                             int toolSkipped, int notices, long inputTokens, long outputTokens,
                             long cachedInputTokens, long durationMs) {

    /** 工具成功率；一次都没执行时返回 null（不能当成 0% 或 100%）。 */
    public Double toolSuccessRate() {
        int finished = toolSucceeded + toolFailed;
        return finished == 0 ? null : (double) toolSucceeded / finished;
    }

    /** 写进 JSON；includeDuration=false 时去掉耗时，方便不同提交之间逐行比较。 */
    public void writeTo(ObjectNode node, boolean includeDuration) {
        node.put("status", status);
        node.put("model_requests", modelRequests);
        writeToolStats(node);
        node.put("input_tokens", inputTokens);
        node.put("output_tokens", outputTokens);
        node.put("cached_input_tokens", cachedInputTokens);
        if (includeDuration) {
            node.put("duration_ms", durationMs);
        }
    }

    /** 只写工具统计和提示数（无界面结果对象里已经有状态、请求数和用量）。 */
    public void writeToolStats(ObjectNode node) {
        ObjectNode tools = node.putObject("tools");
        tools.put("calls", toolCalls);
        tools.put("succeeded", toolSucceeded);
        tools.put("failed", toolFailed);
        tools.put("skipped", toolSkipped);
        Double rate = toolSuccessRate();
        if (rate == null) {
            tools.putNull("success_rate");
        } else {
            tools.put("success_rate", Math.round(rate * 1000) / 1000.0);
        }
        node.put("notices", notices);
    }

    /**
     * 上下文处理策略五层各自的触发情况（评测用）。runs 是触发次数（一次请求前或一次紧急压缩算一次），
     * 后面的数是处理的条数；tokensSaved 是各次压缩前后估算 Token 之差的累计。
     */
    public record ContextStats(int offloads, int trimRuns, int trimmed, int foldRuns, int folded,
                               int clearingRuns, int cleared, int summaries, int emergencyRuns, long tokensSaved) {
        public void writeTo(ObjectNode node) {
            ObjectNode context = node.putObject("context");
            context.put("offloads", offloads);
            context.put("trim_runs", trimRuns);
            context.put("trimmed", trimmed);
            context.put("fold_runs", foldRuns);
            context.put("folded", folded);
            context.put("clearing_runs", clearingRuns);
            context.put("cleared", cleared);
            context.put("summaries", summaries);
            context.put("emergency_runs", emergencyRuns);
            context.put("tokens_saved", tokensSaved);
        }
    }

    /** 订阅事件并累计。一个实例只统计一轮。 */
    public static final class Collector implements AgentEventListener {
        private int toolCalls;
        private int toolSucceeded;
        private int toolFailed;
        private int toolSkipped;
        private int notices;
        private int offloads;
        private int trimRuns;
        private int trimmed;
        private int foldRuns;
        private int folded;
        private int clearingRuns;
        private int cleared;
        private int summaries;
        private int emergencyRuns;
        private long tokensSaved;
        private AgentEvent.TurnFinished finished;

        @Override
        public void onEvent(AgentEvent event) {
            if (event instanceof AgentEvent.ToolCallStarted) {
                toolCalls++;
            } else if (event instanceof AgentEvent.ToolCallFinished finishedTool) {
                if (finishedTool.success()) {
                    toolSucceeded++;
                } else {
                    toolFailed++;
                }
                if (finishedTool.output() != null && finishedTool.output().contains(ToolResultOffloader.OFFLOAD_MARKER)) {
                    offloads++;
                }
            } else if (event instanceof AgentEvent.ContextCompacted compacted) {
                if (compacted.trimmed() > 0) {
                    trimRuns++;
                    trimmed += compacted.trimmed();
                }
                if (compacted.folded() > 0) {
                    foldRuns++;
                    folded += compacted.folded();
                }
                if (compacted.clearedToolResults() > 0) {
                    clearingRuns++;
                    cleared += compacted.clearedToolResults();
                }
                if (compacted.summarized()) {
                    summaries++;
                }
                if ("emergency".equals(compacted.trigger())) {
                    emergencyRuns++;
                }
                tokensSaved += compacted.savedTokens();
            } else if (event instanceof AgentEvent.ToolCallSkipped) {
                toolSkipped++;
            } else if (event instanceof AgentEvent.Notice) {
                notices++;
            } else if (event instanceof AgentEvent.TurnFinished turnFinished) {
                finished = turnFinished;
            }
        }

        public AgentEvent.TurnFinished finished() {
            return finished;
        }

        public ContextStats contextStats() {
            return new ContextStats(offloads, trimRuns, trimmed, foldRuns, folded, clearingRuns, cleared,
                    summaries, emergencyRuns, tokensSaved);
        }

        /** 本轮还没结束时状态为 "unfinished"，Token 和请求数为 0。 */
        public HarnessMetrics snapshot() {
            if (finished == null) {
                return new HarnessMetrics("unfinished", 0, toolCalls, toolSucceeded, toolFailed, toolSkipped,
                        notices, 0, 0, 0, 0);
            }
            return new HarnessMetrics(AgentEventJson.status(finished.status()), finished.modelRequests(),
                    toolCalls, toolSucceeded, toolFailed, toolSkipped, notices,
                    finished.inputTokens(), finished.outputTokens(), finished.cachedInputTokens(),
                    finished.durationMs());
        }
    }
}
