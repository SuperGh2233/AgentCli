package com.haocli.agent;

import com.haocli.memory.AutoCompactionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文处理策略的第 2–5 层（历史裁剪、局部折叠、清理旧工具结果、摘要压缩）；哪几层开着由构造参数决定。
 * 每次请求前按阈值从便宜到贵依次做；模型报上下文超长时做一次紧急压缩。
 */
final class ContextCompactionHook implements TurnHook {
    private static final Logger log = LoggerFactory.getLogger(ContextCompactionHook.class);
    private final AutoCompactionManager.Layers layers;

    ContextCompactionHook(AutoCompactionManager.Layers layers) {
        this.layers = layers;
    }

    @Override
    public void beforeModelRequest(Context context) {
        try {
            report(context, context.conversation().compactIfNeeded(context.compressionTriggerTokens(), layers));
        } catch (Exception e) {
            log.warn("conversationHistory compaction failed", e);
        }
    }

    @Override
    public boolean onContextOverflow(Context context) {
        try {
            AutoCompactionManager.Result result = context.conversation().compactEmergency(layers);
            report(context, result);
            return result.changed();
        } catch (Exception e) {
            log.warn("emergency compaction failed", e);
            return false;
        }
    }

    private static void report(Context context, AutoCompactionManager.Result result) {
        if (result.trimmed() > 0 || result.folded() > 0) {
            StringBuilder text = new StringBuilder("✂️ 上下文增长，");
            if (result.trimmed() > 0) text.append("已裁剪 ").append(result.trimmed()).append(" 处大块历史内容");
            if (result.trimmed() > 0 && result.folded() > 0) text.append("，");
            if (result.folded() > 0) text.append("已折叠 ").append(result.folded()).append(" 条过时的工具结果");
            context.notice(text.append("。").toString());
        }
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
    }
}
