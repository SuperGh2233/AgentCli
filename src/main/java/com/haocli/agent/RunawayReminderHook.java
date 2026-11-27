package com.haocli.agent;

import com.haocli.llm.LlmClient;
import com.haocli.tool.ToolRegistry.ToolExecutionResult;
import com.haocli.util.AnsiStyle;

import java.util.List;

/** 重复调用同样的工具时提醒模型换思路；只提醒，不拦截工具。 */
final class RunawayReminderHook implements TurnHook {
    private final RunawayGuard guard = new RunawayGuard();

    @Override
    public void afterToolResults(List<ToolExecutionResult> results, Context context) {
        guard.observe(results).ifPresent(reminder -> {
            context.conversation().append(LlmClient.Message.user(reminder), "runaway_reminder");
            context.notice(AnsiStyle.subtle("  ↻ 检测到重复的工具调用，已提醒模型换个思路"));
        });
    }
}
