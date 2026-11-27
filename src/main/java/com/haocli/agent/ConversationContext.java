package com.haocli.agent;

import com.haocli.history.ConversationLedger;
import com.haocli.llm.LlmClient;
import com.haocli.memory.AutoCompactionManager;
import com.haocli.memory.TokenBudget;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 一个 Agent 实际发给模型的对话（短期上下文）和它的会话记录。
 *
 * <p>只有这一份消息列表，不另存副本。所有“往对话里加消息”都经过 {@link #append}，同时写进只追加的会话记录；
 * 原地改写对话的操作（压缩、清理旧工具结果、恢复会话）之后必须调用 {@link #recordViewCheckpoint}，
 * 否则 /resume 还原出来的对话和模型当时看到的不一样。压缩已经在这里自动写检查点。
 */
final class ConversationContext {

    private final List<LlmClient.Message> messages = new ArrayList<>();
    private final String mode;
    private final String actor;
    private final AutoCompactionManager compaction;
    private final LongSupplier extraRequestTokens;
    private ConversationLedger ledger = ConversationLedger.disabled();

    /**
     * @param mode               会话记录里的执行方式标记，比如 react
     * @param actor              会话记录里的角色标记，比如 agent
     * @param compaction         压缩协调器
     * @param extraRequestTokens 每次请求除消息外还要占的 Token（工具定义），用于估算上下文大小
     */
    ConversationContext(String mode, String actor, AutoCompactionManager compaction, LongSupplier extraRequestTokens) {
        this.mode = mode;
        this.actor = actor;
        this.compaction = compaction;
        this.extraRequestTokens = extraRequestTokens;
    }

    /** 实际发给模型的消息列表本身（不是副本）。 */
    List<LlmClient.Message> messages() {
        return messages;
    }

    ConversationLedger ledger() {
        return ledger;
    }

    AutoCompactionManager compaction() {
        return compaction;
    }

    /**
     * 换会话记录。已有的 system prompt 会补记一条，之前的其他消息不重放
     * （调用方在构造 Agent 后立刻挂上记录）。
     */
    void attachLedger(ConversationLedger next) {
        ConversationLedger target = next == null ? ConversationLedger.disabled() : next;
        if (ledger == target) {
            return;
        }
        ledger = target;
        if (!messages.isEmpty()) {
            ledger.appendMessage(mode, actor, "session_attach", messages.get(0));
        }
    }

    void append(LlmClient.Message message, String source) {
        messages.add(message);
        ledger.appendMessage(mode, actor, source, message);
    }

    /**
     * 对话里放精简版，会话记录里存完整版。用于最终回复：不要求回传思考内容的供应商不需要它，
     * 但会话记录要保留完整的模型输出。
     */
    void appendWithFullRecord(LlmClient.Message inConversation, LlmClient.Message fullRecord, String source) {
        messages.add(inConversation);
        ledger.appendMessage(mode, actor, source, fullRecord);
    }

    void replaceSystemPrompt(LlmClient.Message systemMessage, String source) {
        messages.set(0, systemMessage);
        ledger.appendMessage(mode, actor, source, systemMessage);
    }

    /** 下一次请求大约要占多少 Token：消息 + 工具定义。 */
    long estimateRequestTokens() {
        long messageTokens = TokenBudget.estimateMessagesTokens(messages);
        return Math.max(0L, messageTokens + extraRequestTokens.getAsLong());
    }

    /**
     * 把原地改写后的完整对话记一份。图片在下一轮本来就会被省略，这里不保存图片数据。
     */
    void recordViewCheckpoint(String source, Map<String, Object> extra) {
        if (!ledger.isEnabled()) {
            return;
        }
        List<LlmClient.Message> snapshot = messages.stream()
                .skip(1)
                .map(LlmClient.Message::withoutImageContent)
                .toList();
        Map<String, Object> metadata = new LinkedHashMap<>(extra);
        metadata.put("messages", snapshot);
        ledger.appendEvent(ConversationLedger.VIEW_CHECKPOINT_EVENT, mode, actor, source, metadata);
    }

    /**
     * 达到触发线才压缩；有改动就写压缩记录和检查点。异常交给调用方（压缩失败不能打断任务）。
     */
    AutoCompactionManager.Result compactIfNeeded(int triggerTokens) {
        int beforeMessages = messages.size();
        long beforeTokens = estimateRequestTokens();
        AutoCompactionManager.Result result = compaction.compactIfNeeded(messages, triggerTokens);
        if (result.clearedToolResults() > 0) {
            recordCompaction("automatic:tool_result_clearing", beforeMessages, beforeTokens);
        }
        if (result.compacted()) {
            recordCompaction("automatic:" + result.strategy().name().toLowerCase(), beforeMessages, beforeTokens);
        }
        if (result.clearedToolResults() > 0 || result.compacted()) {
            recordViewCheckpoint("automatic_compaction", Map.of());
        }
        return result;
    }

    /** 用户手动 /compact，不等触发线。 */
    AutoCompactionManager.Result compactNow() {
        int beforeMessages = messages.size();
        long beforeTokens = estimateRequestTokens();
        AutoCompactionManager.Result result = compaction.compactNow(messages);
        if (result.compacted()) {
            recordCompaction("manual", beforeMessages, beforeTokens);
            recordViewCheckpoint("manual_compaction", Map.of());
        }
        return result;
    }

    private void recordCompaction(String source, int beforeMessages, long beforeTokens) {
        ledger.appendEvent(
                "compaction",
                mode,
                actor,
                source,
                Map.of(
                        "beforeMessages", beforeMessages,
                        "afterMessages", messages.size(),
                        "beforeTokens", beforeTokens,
                        "afterTokens", estimateRequestTokens()));
    }
}
