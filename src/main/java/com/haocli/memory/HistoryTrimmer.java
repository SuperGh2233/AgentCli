package com.haocli.memory;

import com.haocli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩第 2 层：历史裁剪。不调用模型，把历史里的大块内容换成简短说明。
 *
 * <ul>
 *   <li>图片：除最近 {@value #KEEP_RECENT_IMAGE_MESSAGES} 条带图片的消息外，图片换成一行文字（单张图几千 Token）</li>
 *   <li>超长工具结果：最近几条之外、超过 {@value #LONG_RESULT_CHARS} 字符的，只保留开头和结尾</li>
 * </ul>
 *
 * <p>只替换内容、不删消息，所以工具调用和结果仍然一一对应。改旧消息会让服务端前缀缓存失效，
 * 所以只在上下文超过摘要阈值的 {@value #TRIGGER_RATIO} 倍时才做。</p>
 */
public final class HistoryTrimmer {
    private static final Logger log = LoggerFactory.getLogger(HistoryTrimmer.class);

    public static final String MARKER = "[历史裁剪]";
    static final double TRIGGER_RATIO = 0.4;
    static final int KEEP_RECENT_IMAGE_MESSAGES = 1;
    static final int KEEP_RECENT_TOOL_RESULTS = 6;
    static final int LONG_RESULT_CHARS = 6_000;
    static final int HEAD_CHARS = 1_500;
    static final int TAIL_CHARS = 500;

    public int triggerTokens(int compactionTriggerTokens) {
        if (compactionTriggerTokens <= 0) return 0;
        return Math.max(1, (int) Math.floor(compactionTriggerTokens * TRIGGER_RATIO));
    }

    /** 达到触发线才裁剪。 */
    public Result trimIfNeeded(List<LlmClient.Message> history, int compactionTriggerTokens) {
        int trigger = triggerTokens(compactionTriggerTokens);
        if (history == null || history.isEmpty() || trigger <= 0) return Result.none();
        if (TokenBudget.estimateMessagesTokens(history) < trigger) return Result.none();
        return trim(history, KEEP_RECENT_TOOL_RESULTS);
    }

    /** 不看阈值立即裁剪；紧急压缩时 keepRecentToolResults 可以更小。 */
    public Result trim(List<LlmClient.Message> history, int keepRecentToolResults) {
        if (history == null || history.isEmpty()) return Result.none();
        int images = trimImages(history);
        int results = trimLongToolResults(history, Math.max(0, keepRecentToolResults));
        if (images > 0 || results > 0) {
            log.info("history trimming: {} image message(s), {} long tool result(s)", images, results);
        }
        return new Result(images, results);
    }

    private static int trimImages(List<LlmClient.Message> history) {
        List<Integer> imageIndices = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if (history.get(i).hasImageContent()) imageIndices.add(i);
        }
        int end = Math.max(0, imageIndices.size() - KEEP_RECENT_IMAGE_MESSAGES);
        for (int n = 0; n < end; n++) {
            int index = imageIndices.get(n);
            LlmClient.Message message = history.get(index);
            int count = message.imagePartCount();
            StringBuilder text = new StringBuilder();
            for (LlmClient.ContentPart part : message.contentParts()) {
                if (!part.isImage() && part.text() != null && !part.text().isBlank()) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(part.text());
                }
            }
            if (!text.isEmpty()) text.append('\n');
            text.append(MARKER).append(" 这里原有 ").append(count)
                    .append(" 张图片，已从上下文移除以节省空间；需要时重新截图或重新读取。");
            history.set(index, new LlmClient.Message(message.role(), text.toString(), message.reasoningContent(),
                    message.toolCalls(), message.toolCallId(), null));
        }
        return end;
    }

    private static int trimLongToolResults(List<LlmClient.Message> history, int keepRecent) {
        List<Integer> toolIndices = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            if ("tool".equals(history.get(i).role())) toolIndices.add(i);
        }
        int end = Math.max(0, toolIndices.size() - keepRecent);
        int trimmed = 0;
        for (int n = 0; n < end; n++) {
            int index = toolIndices.get(n);
            LlmClient.Message message = history.get(index);
            String content = message.content();
            if (content == null || content.length() <= LONG_RESULT_CHARS || isAlreadyReduced(content)) continue;
            int omitted = content.length() - HEAD_CHARS - TAIL_CHARS;
            String reduced = content.substring(0, HEAD_CHARS)
                    + "\n…\n" + MARKER + " 这次工具输出中间约 " + omitted
                    + " 字符已从上下文省略以节省空间；需要完整内容时重新调用工具。\n…\n"
                    + content.substring(content.length() - TAIL_CHARS);
            history.set(index, LlmClient.Message.tool(message.toolCallId(), reduced));
            trimmed++;
        }
        return trimmed;
    }

    static boolean isAlreadyReduced(String content) {
        return content.contains(MARKER) || content.startsWith(LocalFolder.MARKER)
                || content.startsWith(ToolResultClearer.PLACEHOLDER_PREFIX);
    }

    public record Result(int trimmedImageMessages, int trimmedToolResults) {
        public static Result none() {
            return new Result(0, 0);
        }

        public int count() {
            return trimmedImageMessages + trimmedToolResults;
        }
    }
}
