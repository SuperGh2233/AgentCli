package com.haocli.agent;

import com.haocli.llm.LlmClient;
import com.haocli.tool.LoadedSkillMessages;
import com.haocli.tool.ToolRegistry;
import com.haocli.tool.ToolRegistry.ToolExecutionResult;

import java.util.List;

/** load_skill 成功后改写它的结果，并在同一轮下一次请求前把 Skill 正文加进对话。 */
final class SkillInjectionHook implements TurnHook {
    private final ToolRegistry toolRegistry;
    private String pendingMessage = "";

    SkillInjectionHook(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public List<ToolExecutionResult> beforeAppendToolResults(List<ToolExecutionResult> results, Context context) {
        LoadedSkillMessages.Injection skills = LoadedSkillMessages.prepare(
                results, toolRegistry.getSkillRegistry(), context.conversation().messages());
        pendingMessage = skills.message();
        return skills.results();
    }

    @Override
    public void afterToolResults(List<ToolExecutionResult> results, Context context) {
        if (!pendingMessage.isEmpty()) {
            context.conversation().append(LlmClient.Message.user(pendingMessage), "skill_injection");
            pendingMessage = "";
        }
    }
}
