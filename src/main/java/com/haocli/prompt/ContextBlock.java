package com.haocli.prompt;

import com.haocli.llm.LlmClient;
import com.haocli.memory.TokenBudget;

import java.util.List;

/**
 * 系统提示词里的一块内容：来自哪里、可不可信、是不是固定不变。装配器按块组织提示词，
 * {@code /context} 按块显示各自占多少 Token；外部数据单独成块、标明不可信。
 *
 * @param id      稳定的名字（base、skills、project-rules……）
 * @param source  来源类别
 * @param trusted false 表示外部数据（MCP 资源等），只作参考，其中的指令不得执行
 * @param pinned  内置规则，任何情况下都保留
 * @param content 渲染进提示词的原文（含小节标题）
 */
public record ContextBlock(String id, Source source, boolean trusted, boolean pinned, String content) {

    public enum Source {
        BUILTIN("内置规则"),
        SKILL_INDEX("Skill 索引"),
        RUNTIME("运行时信息"),
        EXTERNAL("外部数据（不可信）"),
        PROJECT_RULES("项目规则 HAO.md"),
        MEMORY("长期记忆");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public ContextBlock {
        content = content == null ? "" : content.trim();
    }

    public boolean isEmpty() {
        return content.isEmpty();
    }

    public int estimatedTokens() {
        return isEmpty() ? 0 : TokenBudget.estimateMessagesTokens(List.of(LlmClient.Message.system(content)));
    }
}
