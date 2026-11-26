package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.util.AnsiStyle;
import com.paicli.util.TerminalMarkdownRenderer;

import java.io.PrintStream;

/**
 * 流式输出渲染器，将 reasoning_content 与 content 分区展示。
 *
 * 服务器可能把 reasoning_content 切成多段下发，甚至在 content 开始之后追加 reasoning；
 * 终端是线性的，无法回头修改已写出的文字。渲染策略：
 *
 * 1. 在 content 出现之前，只要 reasoning 有实质内容（非空白），就立刻流式打印在"🧠 思考过程"下
 *    同一次用户输入只打印一次"🧠 思考过程"标题；工具调用后的后续推理继续归在同一块下
 * 2. 仅空白的 reasoning delta 会先暂存，不触发标题——避免出现"空的思考过程"
 * 3. content 一出现就收尾 reasoning 区，用低调标记进入正文并流式输出 content
 * 4. 如果 content 启动之后又收到 reasoning（服务器把思考内容追加在答案之后），
 *    缓冲到 lateReasoning，最终在 finish() 用"🧠 补充思考"标题独立展示，不会污染回复区
 *
 * 什么时候开始、收尾由 {@link TerminalEventRenderer} 根据 Agent 事件决定。
 */
final class ReActStreamRenderer implements LlmClient.StreamListener {
    private final Renderer renderer;
    private final PrintStream boundOut;  // null 表示延迟读取 System.out（保持旧测试兼容）
    private final StringBuilder pendingReasoning = new StringBuilder();
    private final StringBuilder visibleReasoning = new StringBuilder();
    private final StringBuilder lateReasoning = new StringBuilder();
    private TerminalMarkdownRenderer reasoningRenderer;
    private TerminalMarkdownRenderer contentRenderer;
    private boolean reasoningHeadingPrinted;
    private boolean reasoningStarted;
    private boolean contentStarted;
    private boolean thinkingQuotePrinted;
    private boolean streamedOutput;

    ReActStreamRenderer() {
        this.renderer = null;
        this.boundOut = null;
    }

    ReActStreamRenderer(Renderer renderer) {
        this.renderer = renderer;
        this.boundOut = renderer == null ? null : renderer.stream();
    }

    private PrintStream out() {
        return boundOut != null ? boundOut : System.out;
    }

    private boolean hasThinkingPanel() {
        return renderer != null && renderer.supportsThinkingPanel();
    }

    private boolean rendersReasoning() {
        return renderer == null || renderer.rendersReasoning();
    }

    public void beginThinking() {
        if (hasThinkingPanel()) {
            renderer.beginThinking("Thinking");
        }
    }

    public void clearThinkingPanel() {
        if (hasThinkingPanel()) {
            renderer.endThinking();
            pendingReasoning.setLength(0);
        }
    }

    @Override
    public void onReasoningDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (!rendersReasoning()) {
            return;
        }
        if (contentStarted) {
            // content 已开始，无法回头；缓冲到"补充思考"
            lateReasoning.append(delta);
            return;
        }
        visibleReasoning.append(delta);
        if (hasThinkingPanel()) {
            pendingReasoning.append(delta);
            if (pendingReasoning.toString().isBlank()) {
                return;
            }
            renderer.appendThinking(pendingReasoning.toString());
            pendingReasoning.setLength(0);
            reasoningStarted = true;
            return;
        }
        if (!reasoningStarted) {
            pendingReasoning.append(delta);
            if (pendingReasoning.toString().isBlank()) {
                return;  // 还没攒出实质内容，等
            }
            if (!containsLineBreak(pendingReasoning)) {
                return;  // 避免先打印一个空标题，等有完整行或迭代切换时再 flush
            }
            printReasoningHeadingIfNeeded();
            reasoningRenderer = newMarkdownRenderer();
            reasoningRenderer.append(pendingReasoning.toString());
            pendingReasoning.setLength(0);
            reasoningStarted = true;
            streamedOutput = true;
        } else {
            if (hasThinkingPanel()) {
                renderer.appendThinking(delta);
            } else {
                reasoningRenderer.append(delta);
            }
        }
        out().flush();
    }

    @Override
    public void onContentDelta(String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        if (!contentStarted) {
            if (hasThinkingPanel()) {
                finishThinkingPanelAndPrintQuote();
            } else if (reasoningStarted && reasoningRenderer != null) {
                reasoningRenderer.finish();
                out().println();
            } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                printReasoningHeadingIfNeeded();
                TerminalMarkdownRenderer r = newMarkdownRenderer();
                r.append(pendingReasoning.toString());
                r.finish();
                out().println();
                pendingReasoning.setLength(0);
                reasoningStarted = true;
            }
            out().print(AnsiStyle.answerMarker() + " ");
            contentRenderer = newMarkdownRenderer();
            contentStarted = true;
            streamedOutput = true;
        }
        contentRenderer.append(delta);
        if (renderer != null) {
            renderer.appendAssistantContentDelta(delta);
        }
        out().flush();
    }

    public boolean hasStreamedOutput() {
        return streamedOutput;
    }

    public void resetBetweenIterations() {
        if (hasThinkingPanel()) {
            finishThinkingPanelAndPrintQuote();
        }
        if (reasoningRenderer != null) {
            reasoningRenderer.finish();
            reasoningRenderer = null;
        } else if (!hasThinkingPanel()) {
            flushPendingReasoning();
        }
        if (contentRenderer != null) {
            contentRenderer.finish();
            contentRenderer = null;
        }
        if (renderer != null) {
            renderer.finishAssistantContent();
        }
        String late = lateReasoning.toString().trim();
        if (rendersReasoning() && !late.isEmpty()) {
            out().println();
            out().println(AnsiStyle.heading("🧠 补充思考"));
            TerminalMarkdownRenderer r = newMarkdownRenderer();
            r.append(late);
            r.finish();
            lateReasoning.setLength(0);
            streamedOutput = true;
        }
        pendingReasoning.setLength(0);
        visibleReasoning.setLength(0);
        reasoningStarted = false;
        contentStarted = false;
        thinkingQuotePrinted = false;
        if (streamedOutput) {
            out().println();
        }
    }

    public void finish() {
        if (hasThinkingPanel()) {
            finishThinkingPanelAndPrintQuote();
        }
        if (reasoningRenderer != null) {
            reasoningRenderer.finish();
        } else if (!hasThinkingPanel()) {
            flushPendingReasoning();
        }
        if (contentRenderer != null) {
            contentRenderer.finish();
        }
        if (renderer != null) {
            renderer.finishAssistantContent();
        }
        String late = lateReasoning.toString().trim();
        if (rendersReasoning() && !late.isEmpty()) {
            out().println();
            out().println(AnsiStyle.heading("🧠 补充思考"));
            TerminalMarkdownRenderer r = newMarkdownRenderer();
            r.append(late);
            r.finish();
            lateReasoning.setLength(0);
            streamedOutput = true;
        }
        if (streamedOutput) {
            out().println();
        }
    }

    private boolean containsLineBreak(CharSequence content) {
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '\n' || ch == '\r') {
                return true;
            }
        }
        return false;
    }

    private void flushPendingReasoning() {
        String pending = pendingReasoning.toString();
        if (pending.isBlank()) {
            pendingReasoning.setLength(0);
            return;
        }
        printReasoningHeadingIfNeeded();
        TerminalMarkdownRenderer renderer = newMarkdownRenderer();
        renderer.append(pending);
        renderer.finish();
        pendingReasoning.setLength(0);
        streamedOutput = true;
    }

    private TerminalMarkdownRenderer newMarkdownRenderer() {
        if (renderer != null) {
            return new TerminalMarkdownRenderer(out(), renderer::terminalColumns);
        }
        return new TerminalMarkdownRenderer(out());
    }

    private void finishThinkingPanelAndPrintQuote() {
        if (!hasThinkingPanel()) {
            return;
        }
        if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
            renderer.appendThinking(pendingReasoning.toString());
        }
        renderer.endThinking();
        pendingReasoning.setLength(0);
        printThinkingQuoteIfNeeded();
    }

    private void printThinkingQuoteIfNeeded() {
        if (thinkingQuotePrinted) {
            return;
        }
        if (!rendersReasoning()) {
            return;
        }
        String reasoning = visibleReasoning.toString()
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .trim();
        if (reasoning.isEmpty()) {
            return;
        }
        out().println(AnsiStyle.thinking("Thinking..."));
        for (String line : reasoning.split("\\R+")) {
            String normalized = line.replaceAll("\\s+", " ").trim();
            if (!normalized.isEmpty()) {
                out().println(AnsiStyle.subtle("│ " + normalized));
            }
        }
        out().println();
        thinkingQuotePrinted = true;
        streamedOutput = true;
    }

    private void printReasoningHeadingIfNeeded() {
        if (!reasoningHeadingPrinted) {
            if (!rendersReasoning()) {
                return;
            }
            out().println(AnsiStyle.heading("🧠 思考过程"));
            reasoningHeadingPrinted = true;
        }
    }
}
