package com.haocli.memory;

import com.haocli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第 2 层历史裁剪：旧图片换成文字、旧的超长结果留头尾；不删消息、不碰最近的、不重复裁剪。 */
class HistoryTrimmerTest {

    @Test
    void replacesOlderImagesWithTextAndKeepsTheLatest() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        for (int i = 0; i < 3; i++) {
            history.add(LlmClient.Message.user(List.of(LlmClient.ContentPart.text("截图 " + i),
                    LlmClient.ContentPart.imageBase64("aGVsbG8=", "image/png"))));
        }

        HistoryTrimmer.Result result = new HistoryTrimmer().trim(history, 6);

        assertEquals(2, result.trimmedImageMessages());
        assertEquals(4, history.size(), "不删消息");
        for (int i = 1; i <= 2; i++) {
            LlmClient.Message trimmed = history.get(i);
            assertEquals("user", trimmed.role());
            assertFalse(trimmed.hasImageContent());
            assertNull(trimmed.contentParts());
            assertTrue(trimmed.content().startsWith("截图 " + (i - 1)), trimmed.content());
            assertTrue(trimmed.content().contains(HistoryTrimmer.MARKER));
        }
        assertTrue(history.get(3).hasImageContent(), "最近一条图片保留");
    }

    @Test
    void shortensOldLongToolResultsButNotRecentOnes() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        for (int i = 0; i < 8; i++) {
            history.add(call("c" + i, "execute_command", "{\"command\":\"cmd" + i + "\"}"));
            history.add(result("c" + i, "HEAD" + big(10_000) + "TAIL"));
        }
        List<String> idsBefore = toolIds(history);

        HistoryTrimmer.Result result = new HistoryTrimmer().trim(history, 6);

        assertEquals(2, result.trimmedToolResults(), "8 条里最近 6 条不动");
        assertEquals(idsBefore, toolIds(history), "调用和结果仍然一一对应");
        String first = history.get(2).content();
        assertTrue(first.startsWith("HEAD") && first.endsWith("TAIL"), "保留开头和结尾");
        assertTrue(first.contains(HistoryTrimmer.MARKER));
        assertTrue(first.length() < 3_000);
        assertEquals(10_008, history.get(history.size() - 1).content().length());
        assertEquals(0, new HistoryTrimmer().trim(history, 6).count(), "不重复裁剪");
    }

    @Test
    void doesNothingBelowItsTrigger() {
        List<LlmClient.Message> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            history.add(call("c" + i, "execute_command", "{}"));
            history.add(result("c" + i, big(10_000)));
        }

        assertEquals(0, new HistoryTrimmer().trimIfNeeded(history, 10_000_000).count());
        assertTrue(new HistoryTrimmer().trimIfNeeded(history, 1_000).count() > 0);
    }

    static LlmClient.Message call(String id, String tool, String args) {
        return LlmClient.Message.assistant("", List.of(
                new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(tool, args))));
    }

    static LlmClient.Message result(String id, String content) {
        return LlmClient.Message.tool(id, content);
    }

    static String big(int chars) {
        return "x".repeat(chars);
    }

    static List<String> toolIds(List<LlmClient.Message> history) {
        return history.stream().filter(m -> "tool".equals(m.role())).map(LlmClient.Message::toolCallId).toList();
    }
}
