package com.paicli.llm.core;

import com.paicli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyMessageCodecTest {

    private static LlmClient.ToolCall legacyCall(String id, String name, String args) {
        return new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(name, args));
    }

    private static void assertRoundTrip(LlmClient.Message original) {
        ChatMessage converted = LegacyMessageCodec.toChatMessage(original);
        assertEquals(original, LegacyMessageCodec.toLegacy(converted), "来回转换后应和原消息完全相同");
    }

    @Test
    void typicalConversationRoundTripsWithoutChange() {
        List<LlmClient.Message> history = List.of(
                LlmClient.Message.system("你是编码助手"),
                LlmClient.Message.user("读一下 a.txt"),
                LlmClient.Message.assistant("先读文件", "", List.of(
                        legacyCall("call_1", "read_file", "{\"path\":\"a.txt\"}"),
                        legacyCall("call_2", "list_dir", "{\"path\":\".\"}"))),
                LlmClient.Message.tool("call_1", "文件内容:\nhello"),
                LlmClient.Message.tool("call_2", ""),
                LlmClient.Message.assistant("思考", "读完了"),
                LlmClient.Message.assistant("没有思考的回答"));

        for (LlmClient.Message message : history) {
            assertRoundTrip(message);
        }
        assertEquals(history, LegacyMessageCodec.toLegacyMessages(LegacyMessageCodec.toChatMessages(history)));
    }

    @Test
    void assistantBlocksKeepReasoningTextToolCallOrder() {
        ChatMessage converted = LegacyMessageCodec.toChatMessage(LlmClient.Message.assistant(
                "思考过程", "我来读文件", List.of(legacyCall("call_1", "read_file", "{\"path\":\"a\"}"))));

        assertEquals(ChatMessage.Role.ASSISTANT, converted.role());
        assertEquals(3, converted.content().size());
        assertInstanceOf(ContentBlock.Reasoning.class, converted.content().get(0));
        assertInstanceOf(ContentBlock.Text.class, converted.content().get(1));
        ContentBlock.ToolCall call = assertInstanceOf(ContentBlock.ToolCall.class, converted.content().get(2));
        assertEquals("call_1", call.id());
        assertEquals("read_file", call.name());
        assertEquals("{\"path\":\"a\"}", call.argumentsJson());
        assertEquals("思考过程", converted.reasoning());
        assertEquals("我来读文件", converted.text());
    }

    @Test
    void emptyReasoningAndNullContentAreDistinguished() {
        assertRoundTrip(new LlmClient.Message("assistant", null, "", null, null));
        assertRoundTrip(new LlmClient.Message("assistant", "", null, null, null));

        ChatMessage nullContent = LegacyMessageCodec.toChatMessage(new LlmClient.Message("assistant", null, null,
                List.of(legacyCall("c", "list_dir", "{}")), null));
        assertEquals(1, nullContent.content().size(), "content 为 null 时不生成文字块");
    }

    @Test
    void userImagesBecomeImageBlocksAndRoundTrip() {
        LlmClient.Message original = LlmClient.Message.user(List.of(
                LlmClient.ContentPart.text("看看这张图"),
                LlmClient.ContentPart.imageBase64("AAAA", "image/jpeg"),
                LlmClient.ContentPart.imageUrl("https://example.com/a.png")));

        ChatMessage converted = LegacyMessageCodec.toChatMessage(original);
        assertEquals(3, converted.content().size());
        ContentBlock.Image base64 = assertInstanceOf(ContentBlock.Image.class, converted.content().get(1));
        assertEquals("AAAA", base64.base64());
        assertEquals("image/jpeg", base64.mimeType());
        ContentBlock.Image url = assertInstanceOf(ContentBlock.Image.class, converted.content().get(2));
        assertEquals("https://example.com/a.png", url.url());

        assertRoundTrip(original);
    }

    @Test
    void multipleTextPartsStayMultipart() {
        // 去掉图片后剩“原文字 + 省略说明”两段文字，仍然是多段内容。
        LlmClient.Message original = LlmClient.Message.user(List.of(
                LlmClient.ContentPart.text("看图"),
                LlmClient.ContentPart.imageBase64("AAAA", "image/png"))).withoutImageContent();
        assertEquals(2, original.contentParts().size());

        assertRoundTrip(original);
        assertTrue(LegacyMessageCodec.toLegacy(LegacyMessageCodec.toChatMessage(original)).hasContentParts());
    }

    @Test
    void singleTextPartIsFlattenedToPlainContent() {
        LlmClient.Message original = LlmClient.Message.user(List.of(LlmClient.ContentPart.text("只有一段")));

        LlmClient.Message back = LegacyMessageCodec.toLegacy(LegacyMessageCodec.toChatMessage(original));

        assertEquals("只有一段", back.content());
        assertFalse(back.hasContentParts(), "只有一段文字时转回普通 content（发给供应商时两种写法等价）");
    }

    @Test
    void reasoningOnNonAssistantMessageIsDropped() {
        ChatMessage converted = LegacyMessageCodec.toChatMessage(
                new LlmClient.Message("user", "问题", "不该出现的思考", null, null));

        assertEquals(List.of(new ContentBlock.Text("问题")), converted.content());
    }

    @Test
    void unknownRoleAndToolResultWithoutIdAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> LegacyMessageCodec.toChatMessage(new LlmClient.Message("developer", "x")));
        assertThrows(IllegalArgumentException.class,
                () -> LegacyMessageCodec.toChatMessage(new LlmClient.Message("tool", "x", null, null, null)));
    }

    @Test
    void chatResponseConvertsBothWays() {
        LlmClient.ChatResponse legacy = new LlmClient.ChatResponse("assistant", "正文", "思考",
                List.of(legacyCall("call_9", "grep_code", "{\"pattern\":\"x\"}")),
                120, 30, 100, "deepseek-v4-pro-2026", true);

        ModelResponse response = LegacyMessageCodec.toModelResponse(legacy, "tool_calls");

        assertEquals(StopReason.TOOL_USE, response.stopReason());
        assertEquals(new TokenUsage(120, 30, 100, true), response.usage());
        assertEquals("deepseek-v4-pro-2026", response.resolvedModel());
        assertEquals("tool_calls", response.rawFinishReason());
        assertEquals(legacy, LegacyMessageCodec.toChatResponse(response));
    }

    @Test
    void missingUsageIsNotReportedAsZero() {
        LlmClient.ChatResponse legacy = new LlmClient.ChatResponse("assistant", "答", "", null, 0, 0);

        ModelResponse response = LegacyMessageCodec.toModelResponse(legacy, null);

        assertFalse(response.usage().reported());
        assertEquals(StopReason.END_TURN, response.stopReason());
        assertNull(response.rawFinishReason());
        assertEquals(List.of(new ContentBlock.Text("答")), response.message().content(), "空思考不生成思考块");
    }

    @Test
    void stopReasonMapping() {
        assertEquals(StopReason.END_TURN, StopReason.fromOpenAiFinishReason("stop", false));
        assertEquals(StopReason.TOOL_USE, StopReason.fromOpenAiFinishReason("tool_calls", true));
        assertEquals(StopReason.TOOL_USE, StopReason.fromOpenAiFinishReason("stop", true),
                "DSML 转换出的工具调用：线上是 stop，结果里有调用");
        assertEquals(StopReason.MAX_TOKENS, StopReason.fromOpenAiFinishReason("length", true),
                "截断优先：工具参数可能不完整");
        assertEquals(StopReason.MAX_TOKENS, StopReason.fromOpenAiFinishReason("length", false));
        assertEquals(StopReason.CONTENT_FILTER, StopReason.fromOpenAiFinishReason("content_filter", false));
        assertEquals(StopReason.UNKNOWN, StopReason.fromOpenAiFinishReason("tool_calls", false),
                "说要调工具却没有调用，属于异常回复");
        assertEquals(StopReason.UNKNOWN, StopReason.fromOpenAiFinishReason("something_new", false));
        assertEquals(StopReason.END_TURN, StopReason.fromOpenAiFinishReason(null, false));
    }

    @Test
    void chatMessageValidatesRoleSpecificBlocks() {
        assertThrows(IllegalArgumentException.class, () -> ChatMessage.user(List.of(
                new ContentBlock.ToolCall("c", "read_file", "{}"))));
        assertThrows(IllegalArgumentException.class, () -> new ChatMessage(ChatMessage.Role.USER,
                List.of(new ContentBlock.Text("x")), "call_1"));
        assertThrows(IllegalArgumentException.class, () -> new ContentBlock.Image(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new ContentBlock.Image("AAAA", "https://x", null));
    }
}
