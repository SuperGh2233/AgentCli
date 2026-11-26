package com.paicli.llm.core;

import java.util.Objects;

/**
 * 消息里的一块内容，和具体厂商的接口格式无关。
 *
 * <p>一条消息由若干块按顺序组成：助手消息可以是“思考 → 文字 → 工具调用”，用户消息可以是“文字 + 图片”。
 * 各家接口的差异（OpenAI 的 tool_calls 字段、Anthropic 的 tool_use 块、DeepSeek 正文里的 DSML）
 * 只在各自的转换层里处理，Agent 循环只认这几种块。
 */
public sealed interface ContentBlock
        permits ContentBlock.Text, ContentBlock.Reasoning, ContentBlock.Image, ContentBlock.ToolCall {

    /** 普通文字。 */
    record Text(String text) implements ContentBlock {
        public Text {
            text = text == null ? "" : text;
        }
    }

    /**
     * 模型的思考内容。有的模型（DeepSeek V4、GLM-5.3、Kimi thinking 等）要求下一轮请求原样带回，
     * 带不带由具体 Client 决定，这里只负责保存。
     */
    record Reasoning(String text) implements ContentBlock {
        public Reasoning {
            text = text == null ? "" : text;
        }
    }

    /** 图片：base64 数据或网址二选一。 */
    record Image(String base64, String url, String mimeType) implements ContentBlock {
        public Image {
            if ((base64 == null || base64.isBlank()) == (url == null || url.isBlank())) {
                throw new IllegalArgumentException("图片必须且只能提供 base64 或 url 中的一个");
            }
        }

        public static Image ofBase64(String base64, String mimeType) {
            return new Image(base64, null, mimeType == null || mimeType.isBlank() ? "image/png" : mimeType);
        }

        public static Image ofUrl(String url) {
            return new Image(null, url, null);
        }
    }

    /** 模型请求调用一个工具。argumentsJson 保留模型给出的原文，不在这里解析，参数校验交给工具层。 */
    record ToolCall(String id, String name, String argumentsJson) implements ContentBlock {
        public ToolCall {
            Objects.requireNonNull(id, "tool call id");
            Objects.requireNonNull(name, "tool name");
            argumentsJson = argumentsJson == null ? "" : argumentsJson;
        }
    }
}
