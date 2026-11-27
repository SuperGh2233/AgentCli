package com.haocli.agent;

import com.haocli.memory.AutoCompactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 上下文处理策略里的“清理旧工具结果”和“摘要压缩”两层；哪一层开着由构造参数决定。 */
final class ContextCompactionHook implements TurnHook {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactionHook.class);
    private final boolean clearing;
    private final boolean summary;

    ContextCompactionHook(boolean clearing, boolean summary) {
        this.clearing = clearing;
        this.summary = summary;
    }

    @Override
    public void beforeModelRequest(Context context) {
        try {
            AutoCompactionManager.Result result = context.conversation()
                    .compactIfNeeded(context.compressionTriggerTokens(), clearing, summary);
            if (result.clearedToolResults() > 0) {
                context.notice("🧹 上下文增长，已清理 " + result.clearedToolResults()
                        + " 条较早的工具结果（保留最近几条；需要时可重新调用工具或读回卸载文件）。");
            }
            if (result.compacted()) {
                String strategy = result.strategy() == AutoCompactionManager.Strategy.SESSION_MEMORY
                        ? "会话记忆摘要"
                        : "完整对话摘要";
                context.notice("📦 上下文接近窗口上限，已通过" + strategy + "压缩后继续。");
            }
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }
}
