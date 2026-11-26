package com.paicli.agent;

import com.paicli.agent.event.AgentEvent;
import com.paicli.agent.event.AgentEventListener;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 交互终端的显示端：只看 {@link AgentEvent}，把它们画到 {@link Renderer} 上（流式思考 / 正文、工具调用分组、
 * 工具结果摘要、提示、状态栏）。无界面模式和 Runtime API 消费的是同一串事件。
 *
 * <p>流式显示什么时候收尾由事件推出来：{@link AgentEvent.ModelRequestStarted} 打开一段流式显示；
 * 之后第一个“不是增量也不是用量”的事件（工具调用、跳过、提示、下一次请求）先把它收尾，再显示自己；
 * 一轮结束时还开着，就按有没有流式输出决定完整收尾还是只关思考面板。这和重构前内核里直接调用
 * 显示方法的时机一一对应，终端输出不变。
 *
 * <p>一个实例只服务一轮，在 Agent 工作线程上同步调用。
 */
final class TerminalEventRenderer implements AgentEventListener {

    /** 状态栏更新：本轮累计 Token、已用时间、阶段（running / idle）。 */
    @FunctionalInterface
    interface StatusSink {
        void push(long inputTokens, long outputTokens, long cachedInputTokens, long elapsedMs, String phase);
    }

    private final Renderer renderer;
    private final ReActStreamRenderer stream;
    private final StatusSink status;
    private final List<LlmClient.ToolCall> pendingVisibleCalls = new ArrayList<>();
    private final Map<String, String> argumentsById = new HashMap<>();
    private boolean streamOpen;
    /** 已经收到一批的开始事件、还没收到这批最后一条。 */
    private boolean collectingBatch;
    private int toolsStarted;
    private int toolsFinished;
    private long startNanos = System.nanoTime();
    private long inputTokens;
    private long outputTokens;
    private long cachedInputTokens;

    TerminalEventRenderer(Renderer renderer, StatusSink status) {
        this.renderer = renderer;
        this.stream = new ReActStreamRenderer(renderer);
        this.status = status == null ? (in, out, cached, elapsed, phase) -> { } : status;
    }

    /** 这一轮有没有把思考或回答流式显示出来（内核据此决定返回值里还要不要带回答）。 */
    boolean hasStreamedOutput() {
        return stream.hasStreamedOutput();
    }

    @Override
    public void onEvent(AgentEvent event) {
        if (event instanceof AgentEvent.TurnStarted) {
            startNanos = System.nanoTime();
            pushStatus("running");
        } else if (event instanceof AgentEvent.ModelRequestStarted) {
            closeStream();
            stream.beginThinking();
            streamOpen = true;
        } else if (event instanceof AgentEvent.ReasoningDelta reasoning) {
            stream.onReasoningDelta(reasoning.text());
        } else if (event instanceof AgentEvent.TextDelta text) {
            stream.onContentDelta(text.text());
        } else if (event instanceof AgentEvent.Usage usage) {
            inputTokens += usage.inputTokens();
            outputTokens += usage.outputTokens();
            cachedInputTokens += usage.cachedInputTokens();
        } else if (event instanceof AgentEvent.ToolCallStarted started) {
            onToolCallStarted(started);
        } else if (event instanceof AgentEvent.ToolCallFinished finished) {
            onToolCallFinished(finished);
        } else if (event instanceof AgentEvent.ToolCallSkipped) {
            closeStream();
            pushStatus("running");
        } else if (event instanceof AgentEvent.Notice notice) {
            if (!notice.attachedToAnswer()) {
                closeStream();
                renderer.stream().println(notice.styledText());
            }
        } else if (event instanceof AgentEvent.TurnFinished finished) {
            pushStatus("idle");
            if (streamOpen) {
                boolean answered = finished.status() == AgentEvent.TurnStatus.COMPLETED
                        || finished.status() == AgentEvent.TurnStatus.PARTIAL;
                if (answered && !stream.hasStreamedOutput()) {
                    // 回答没流式显示过：只关思考面板，回答由调用方拿返回值打印。
                    stream.clearThinkingPanel();
                } else {
                    stream.finish();
                }
                streamOpen = false;
            }
        }
    }

    private void onToolCallStarted(AgentEvent.ToolCallStarted started) {
        if (!collectingBatch) {
            // 新的一批：先收尾流式显示，避免按行缓冲的文字被审批提示“跨过”造成错位。
            closeStream();
            collectingBatch = true;
            toolsStarted = 0;
            toolsFinished = 0;
            argumentsById.clear();
            pendingVisibleCalls.clear();
        }
        toolsStarted++;
        argumentsById.put(started.id(), started.argumentsJson());
        if (started.allowedByTurnPolicy()) {
            pendingVisibleCalls.add(new LlmClient.ToolCall(started.id(),
                    new LlmClient.ToolCall.Function(started.name(), started.argumentsJson())));
        }
        if (started.lastInBatch()) {
            renderer.appendToolCalls(List.copyOf(pendingVisibleCalls));
            pendingVisibleCalls.clear();
            collectingBatch = false;
        }
    }

    private void onToolCallFinished(AgentEvent.ToolCallFinished finished) {
        String line = ToolResultSummaries.displayLine(finished.name(), argumentsById.get(finished.id()),
                finished.output());
        if (line != null) {
            renderer.stream().println(line);
        }
        toolsFinished++;
        if (toolsFinished == toolsStarted) {
            pushStatus("running");
        }
    }

    private void closeStream() {
        if (streamOpen) {
            stream.resetBetweenIterations();
            streamOpen = false;
        }
    }

    private void pushStatus(String phase) {
        status.push(inputTokens, outputTokens, cachedInputTokens,
                (System.nanoTime() - startNanos) / 1_000_000L, phase);
    }
}
