package com.paicli.headless;

import com.paicli.hitl.ApprovalRequest;
import com.paicli.hitl.ApprovalResult;
import com.paicli.llm.LlmClient;
import com.paicli.render.Renderer;
import com.paicli.render.StatusInfo;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.List;

/**
 * 什么都不显示的渲染器。无界面模式的输出只来自 Agent 事件，Agent 内部写给终端的内容全部丢弃，
 * 保证标准输出里只有约定格式的结果。需要人确认的请求一律拒绝。
 */
final class SilentRenderer implements Renderer {

    private final PrintStream sink = new PrintStream(OutputStream.nullOutputStream(), true);

    @Override
    public void start() {
    }

    @Override
    public boolean rendersReasoning() {
        return false;
    }

    @Override
    public void close() {
    }

    @Override
    public PrintStream stream() {
        return sink;
    }

    @Override
    public void appendToolCalls(List<LlmClient.ToolCall> toolCalls) {
    }

    @Override
    public void appendDiff(String filePath, String before, String after) {
    }

    @Override
    public void updateStatus(StatusInfo status) {
    }

    @Override
    public ApprovalResult promptApproval(ApprovalRequest request) {
        return ApprovalResult.reject("无界面模式没有人可以确认");
    }

    @Override
    public int openPalette(String title, List<String> items) {
        return -1;
    }
}
