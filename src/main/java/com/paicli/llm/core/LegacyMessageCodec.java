package com.paicli.llm.core;

import com.paicli.llm.LlmClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 旧接口 {@link LlmClient} 的消息格式（OpenAI Chat Completions 形状）和新的 {@link ChatMessage} 互相转换。
 *
 * <p>转换规则：
 * <ul>
 *   <li>思考内容只对助手消息保留（旧格式也只在助手消息里把它发给供应商），放在最前面</li>
 *   <li>有多段内容（contentParts）时逐段转成文字块 / 图片块；没有时把 content 转成一个文字块；
 *       content 为 null 时不生成文字块，空串仍生成一个空文字块，保证来回转换不变</li>
 *   <li>转回旧格式时，有图片或多于一个文字块才生成 contentParts；只有一段文字的多段内容会变成普通 content
 *       （发给供应商时从一元素数组变成字符串，OpenAI 兼容接口里两者等价）</li>
 *   <li>空的工具调用列表和 null 都表示没有工具调用，转回来统一是 null</li>
 * </ul>
 */
public final class LegacyMessageCodec {

    private LegacyMessageCodec() {
    }

    public static List<ChatMessage> toChatMessages(List<LlmClient.Message> messages) {
        List<ChatMessage> converted = new ArrayList<>(messages == null ? 0 : messages.size());
        if (messages != null) {
            for (LlmClient.Message message : messages) {
                converted.add(toChatMessage(message));
            }
        }
        return converted;
    }

    public static List<LlmClient.Message> toLegacyMessages(List<ChatMessage> messages) {
        List<LlmClient.Message> converted = new ArrayList<>(messages == null ? 0 : messages.size());
        if (messages != null) {
            for (ChatMessage message : messages) {
                converted.add(toLegacy(message));
            }
        }
        return converted;
    }

    public static ChatMessage toChatMessage(LlmClient.Message message) {
        ChatMessage.Role role = role(message.role());
        List<ContentBlock> blocks = new ArrayList<>();
        if (role == ChatMessage.Role.ASSISTANT && message.reasoningContent() != null) {
            blocks.add(new ContentBlock.Reasoning(message.reasoningContent()));
        }
        if (message.hasContentParts()) {
            for (LlmClient.ContentPart part : message.contentParts()) {
                ContentBlock block = toBlock(part);
                if (block != null) {
                    blocks.add(block);
                }
            }
        } else if (message.content() != null) {
            blocks.add(new ContentBlock.Text(message.content()));
        }
        if (message.toolCalls() != null) {
            for (LlmClient.ToolCall call : message.toolCalls()) {
                blocks.add(new ContentBlock.ToolCall(call.id(), call.function().name(), call.function().arguments()));
            }
        }
        String toolCallId = role == ChatMessage.Role.TOOL_RESULT ? message.toolCallId() : null;
        return new ChatMessage(role, blocks, toolCallId);
    }

    public static LlmClient.Message toLegacy(ChatMessage message) {
        StringBuilder reasoning = null;
        List<ContentBlock> visible = new ArrayList<>();
        List<LlmClient.ToolCall> toolCalls = new ArrayList<>();
        boolean hasImage = false;
        // 项目编译目标是 Java 17，不能用 switch 模式匹配。
        for (ContentBlock block : message.content()) {
            if (block instanceof ContentBlock.Reasoning r) {
                if (reasoning == null) {
                    reasoning = new StringBuilder();
                }
                reasoning.append(r.text());
            } else if (block instanceof ContentBlock.Text t) {
                visible.add(t);
            } else if (block instanceof ContentBlock.Image i) {
                visible.add(i);
                hasImage = true;
            } else if (block instanceof ContentBlock.ToolCall c) {
                toolCalls.add(new LlmClient.ToolCall(c.id(),
                        new LlmClient.ToolCall.Function(c.name(), c.argumentsJson())));
            }
        }

        String content;
        List<LlmClient.ContentPart> parts = null;
        if (hasImage || visible.size() > 1) {
            parts = new ArrayList<>(visible.size());
            for (ContentBlock block : visible) {
                parts.add(toPart(block));
            }
            parts = List.copyOf(parts);
            content = LlmClient.Message.textOf(parts);
        } else if (visible.size() == 1) {
            content = ((ContentBlock.Text) visible.get(0)).text();
        } else {
            content = null;
        }

        return new LlmClient.Message(
                roleName(message.role()),
                content,
                reasoning == null ? null : reasoning.toString(),
                toolCalls.isEmpty() ? null : List.copyOf(toolCalls),
                message.toolCallId(),
                parts);
    }

    public static List<ToolSpec> toToolSpecs(List<LlmClient.Tool> tools) {
        if (tools == null) {
            return null;
        }
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (LlmClient.Tool tool : tools) {
            specs.add(new ToolSpec(tool.name(), tool.description(), tool.parameters()));
        }
        return specs;
    }

    public static List<LlmClient.Tool> toLegacyTools(List<ToolSpec> tools) {
        if (tools == null) {
            return null;
        }
        List<LlmClient.Tool> legacy = new ArrayList<>(tools.size());
        for (ToolSpec tool : tools) {
            legacy.add(new LlmClient.Tool(tool.name(), tool.description(), tool.parametersSchema()));
        }
        return legacy;
    }

    /**
     * 旧接口的回复转成新结果。旧回复里思考内容为空串时不生成思考块。
     *
     * @param finishReason 供应商原始停止原因；拿不到时传 null，按有没有工具调用推断
     */
    public static ModelResponse toModelResponse(LlmClient.ChatResponse response, String finishReason) {
        List<ContentBlock> blocks = new ArrayList<>();
        if (response.reasoningContent() != null && !response.reasoningContent().isEmpty()) {
            blocks.add(new ContentBlock.Reasoning(response.reasoningContent()));
        }
        if (response.content() != null && !response.content().isEmpty()) {
            blocks.add(new ContentBlock.Text(response.content()));
        }
        if (response.toolCalls() != null) {
            for (LlmClient.ToolCall call : response.toolCalls()) {
                blocks.add(new ContentBlock.ToolCall(call.id(), call.function().name(), call.function().arguments()));
            }
        }
        ChatMessage message = ChatMessage.assistant(blocks);
        TokenUsage usage = response.usagePresent()
                ? new TokenUsage(response.inputTokens(), response.outputTokens(), response.cachedInputTokens(), true)
                : TokenUsage.NOT_REPORTED;
        return new ModelResponse(message,
                StopReason.fromOpenAiFinishReason(finishReason, message.hasToolCalls()),
                usage, response.resolvedModel(), finishReason);
    }

    /**
     * 新结果转回旧接口的回复，给还没迁移的调用方用。没有思考块时思考内容为空串，没有文字块时正文为空串，
     * 和 {@code AbstractOpenAiCompatibleClient} 的返回习惯一致。
     */
    public static LlmClient.ChatResponse toChatResponse(ModelResponse response) {
        ChatMessage message = response.message();
        List<LlmClient.ToolCall> toolCalls = new ArrayList<>();
        for (ContentBlock.ToolCall call : message.toolCalls()) {
            toolCalls.add(new LlmClient.ToolCall(call.id(), new LlmClient.ToolCall.Function(call.name(), call.argumentsJson())));
        }
        TokenUsage usage = response.usage();
        return new LlmClient.ChatResponse(
                "assistant",
                message.text(),
                message.reasoning(),
                toolCalls.isEmpty() ? null : List.copyOf(toolCalls),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.cachedInputTokens(),
                response.resolvedModel(),
                usage.reported());
    }

    private static ContentBlock toBlock(LlmClient.ContentPart part) {
        if (part == null) {
            return null;
        }
        if (part.isText()) {
            return new ContentBlock.Text(part.text());
        }
        if ("image_base64".equals(part.type()) && part.imageBase64() != null && !part.imageBase64().isBlank()) {
            return ContentBlock.Image.ofBase64(part.imageBase64(), part.mimeType());
        }
        if ("image_url".equals(part.type()) && part.imageUrl() != null && !part.imageUrl().isBlank()) {
            return ContentBlock.Image.ofUrl(part.imageUrl());
        }
        // 旧格式里不认识或残缺的段，发请求时本来也会被跳过。
        return null;
    }

    private static LlmClient.ContentPart toPart(ContentBlock block) {
        if (block instanceof ContentBlock.Text text) {
            return LlmClient.ContentPart.text(text.text());
        }
        ContentBlock.Image image = (ContentBlock.Image) block;
        return image.base64() != null
                ? LlmClient.ContentPart.imageBase64(image.base64(), image.mimeType())
                : LlmClient.ContentPart.imageUrl(image.url());
    }

    private static ChatMessage.Role role(String role) {
        String normalized = role == null ? "" : role.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "system" -> ChatMessage.Role.SYSTEM;
            case "user" -> ChatMessage.Role.USER;
            case "assistant" -> ChatMessage.Role.ASSISTANT;
            case "tool" -> ChatMessage.Role.TOOL_RESULT;
            default -> throw new IllegalArgumentException("不认识的消息角色: " + role);
        };
    }

    private static String roleName(ChatMessage.Role role) {
        return switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL_RESULT -> "tool";
        };
    }
}
