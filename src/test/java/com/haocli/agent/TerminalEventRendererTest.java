package com.haocli.agent;

import com.haocli.agent.event.AgentEvent;
import com.haocli.hitl.ApprovalRequest;
import com.haocli.hitl.ApprovalResult;
import com.haocli.llm.LlmClient;
import com.haocli.render.Renderer;
import com.haocli.render.StatusInfo;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 交互终端只看事件也要显示得和以前一样：流式收尾时机、工具分组、提示、工具摘要、状态栏。 */
class TerminalEventRendererTest {

    /** 把工具分组显示也写进同一个输出流，方便检查先后顺序。 */
    private static final class RecordingRenderer implements Renderer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final PrintStream stream = new PrintStream(output, true, StandardCharsets.UTF_8);
        private final boolean thinkingPanel;
        final List<List<String>> toolBatches = new ArrayList<>();
        int endThinkingCalls;

        RecordingRenderer(boolean thinkingPanel) {
            this.thinkingPanel = thinkingPanel;
        }

        String text() {
            return output.toString(StandardCharsets.UTF_8).replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "");
        }

        @Override
        public void start() {
        }

        @Override
        public boolean supportsThinkingPanel() {
            return thinkingPanel;
        }

        @Override
        public void beginThinking(String label) {
        }

        @Override
        public void appendThinking(String delta) {
        }

        @Override
        public void endThinking() {
            endThinkingCalls++;
        }

        @Override
        public PrintStream stream() {
            return stream;
        }

        @Override
        public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
            List<String> names = toolCalls.stream().map(call -> call.function().name()).toList();
            toolBatches.add(names);
            stream.println("[tools " + String.join(",", names) + "]");
        }

        @Override
        public void appendDiff(String filePath, String before, String after) {
        }

        @Override
        public void updateStatus(StatusInfo status) {
        }

        @Override
        public ApprovalResult promptApproval(ApprovalRequest request) {
            return ApprovalResult.reject("test");
        }

        @Override
        public int openPalette(String title, List<String> items) {
            return -1;
        }

        @Override
        public void close() {
        }
    }

    private static AgentEvent.TurnFinished finished(AgentEvent.TurnStatus status) {
        return new AgentEvent.TurnFinished("t1", status, "", 1, 5, 10, 5, 0);
    }

    @Test
    void toolBatchIsShownOnceAfterStreamedTextAndHidesCallsTheTurnPolicyRefuses() {
        RecordingRenderer renderer = new RecordingRenderer(false);
        TerminalEventRenderer terminal = new TerminalEventRenderer(renderer, null);

        terminal.onEvent(new AgentEvent.TurnStarted("t1"));
        terminal.onEvent(new AgentEvent.ModelRequestStarted());
        terminal.onEvent(new AgentEvent.TextDelta("先看看文件"));
        terminal.onEvent(new AgentEvent.Usage(10, 5, 0, true));
        terminal.onEvent(new AgentEvent.ToolCallStarted("c1", "read_file", "{\"path\":\"a\"}", true, false));
        terminal.onEvent(new AgentEvent.ToolCallStarted("c2", "web_fetch", "{}", false, false));
        terminal.onEvent(new AgentEvent.ToolCallStarted("c3", "read_file", "{\"path\":\"b\"}", true, true));

        assertEquals(List.of(List.of("read_file", "read_file")), renderer.toolBatches, "整批到齐后显示一次，不显示被拒的");
        String text = renderer.text();
        assertTrue(text.indexOf("先看看文件") < text.indexOf("[tools"),
                "按行缓冲的正文要在工具显示之前收尾输出: " + text);
    }

    @Test
    void noticesArePrintedWithStyleUnlessAlreadyInTheAnswer() {
        RecordingRenderer renderer = new RecordingRenderer(false);
        TerminalEventRenderer terminal = new TerminalEventRenderer(renderer, null);

        terminal.onEvent(new AgentEvent.TurnStarted("t1"));
        terminal.onEvent(new AgentEvent.Notice("压缩了上下文", "📦 压缩了上下文", false));
        terminal.onEvent(new AgentEvent.Notice("回答被截断", "  ⚠ 回答被截断", true));
        terminal.onEvent(finished(AgentEvent.TurnStatus.COMPLETED));

        String text = renderer.text();
        assertTrue(text.contains("📦 压缩了上下文"), text);
        assertFalse(text.contains("回答被截断"), "已拼进回答的提示不能再打印一遍: " + text);
    }

    @Test
    void streamedAnswerIsFlushedBeforeTrailingNoticeAndNotClosedTwice() {
        RecordingRenderer renderer = new RecordingRenderer(false);
        TerminalEventRenderer terminal = new TerminalEventRenderer(renderer, null);

        terminal.onEvent(new AgentEvent.TurnStarted("t1"));
        terminal.onEvent(new AgentEvent.ModelRequestStarted());
        terminal.onEvent(new AgentEvent.TextDelta("最后一行没有换行"));
        terminal.onEvent(new AgentEvent.Usage(10, 5, 0, true));
        terminal.onEvent(new AgentEvent.Notice("回答被截断", "  ⚠ 回答被截断", false));
        String afterNotice = renderer.text();
        terminal.onEvent(finished(AgentEvent.TurnStatus.COMPLETED));

        assertTrue(terminal.hasStreamedOutput());
        assertTrue(afterNotice.indexOf("最后一行没有换行") < afterNotice.indexOf("⚠ 回答被截断"), afterNotice);
        assertEquals(afterNotice, renderer.text(), "提示前已经收尾过，一轮结束时不能再收尾一次（会多出空行）");
    }

    @Test
    void webSearchSummaryUsesArgumentsFromTheStartEvent() {
        RecordingRenderer renderer = new RecordingRenderer(false);
        TerminalEventRenderer terminal = new TerminalEventRenderer(renderer, null);

        terminal.onEvent(new AgentEvent.TurnStarted("t1"));
        terminal.onEvent(new AgentEvent.ModelRequestStarted());
        terminal.onEvent(new AgentEvent.Usage(10, 5, 0, true));
        terminal.onEvent(new AgentEvent.ToolCallStarted("s1", "web_search", "{\"query\":\"java 21\"}", true, true));
        terminal.onEvent(new AgentEvent.ToolCallFinished("s1", "web_search", true, "1. a\n2. b\n"));

        assertTrue(renderer.text().contains("→ 搜索 \"java 21\" 返回 2 条结果"), renderer.text());
    }

    @Test
    void thinkingOnlyAnswerClosesPanelWithoutQuoteButFailureFinishesNormally() {
        RecordingRenderer answered = new RecordingRenderer(true);
        TerminalEventRenderer terminal = new TerminalEventRenderer(answered, null);
        terminal.onEvent(new AgentEvent.TurnStarted("t1"));
        terminal.onEvent(new AgentEvent.ModelRequestStarted());
        terminal.onEvent(new AgentEvent.ReasoningDelta("想一想\n"));
        terminal.onEvent(new AgentEvent.Usage(10, 5, 0, true));
        terminal.onEvent(finished(AgentEvent.TurnStatus.COMPLETED));

        assertFalse(terminal.hasStreamedOutput(), "只有思考面板时不算流式显示过回答");
        assertEquals(1, answered.endThinkingCalls);
        assertFalse(answered.text().contains("Thinking..."), "回答由调用方连同思考过程一起打印，这里不重复: " + answered.text());

        RecordingRenderer failed = new RecordingRenderer(true);
        TerminalEventRenderer failing = new TerminalEventRenderer(failed, null);
        failing.onEvent(new AgentEvent.TurnStarted("t1"));
        failing.onEvent(new AgentEvent.ModelRequestStarted());
        failing.onEvent(new AgentEvent.ReasoningDelta("想一想\n"));
        failing.onEvent(finished(AgentEvent.TurnStatus.FAILED));

        assertTrue(failed.text().contains("Thinking..."), "失败时照常收尾，保留已显示的思考: " + failed.text());
    }

    @Test
    void statusBarGetsRunningThenIdleWithTotalsFromUsageEvents() {
        List<String> pushes = new ArrayList<>();
        TerminalEventRenderer terminal = new TerminalEventRenderer(new RecordingRenderer(false),
                (in, out, cached, elapsed, phase) -> pushes.add(phase + ":" + in + "/" + out + "/" + cached));

        terminal.onEvent(new AgentEvent.TurnStarted("t1"));
        terminal.onEvent(new AgentEvent.ModelRequestStarted());
        terminal.onEvent(new AgentEvent.Usage(10, 5, 2, true));
        terminal.onEvent(new AgentEvent.ToolCallStarted("c1", "read_file", "{}", true, false));
        terminal.onEvent(new AgentEvent.ToolCallStarted("c2", "read_file", "{}", true, true));
        terminal.onEvent(new AgentEvent.ToolCallFinished("c1", "read_file", true, "ok"));
        terminal.onEvent(new AgentEvent.ToolCallFinished("c2", "read_file", true, "ok"));
        terminal.onEvent(new AgentEvent.ModelRequestStarted());
        terminal.onEvent(new AgentEvent.Usage(20, 5, 0, true));
        terminal.onEvent(finished(AgentEvent.TurnStatus.COMPLETED));

        assertEquals(List.of("running:0/0/0", "running:10/5/2", "idle:30/10/2"), pushes,
                pushes.stream().collect(Collectors.joining(" | ")));
    }
}
