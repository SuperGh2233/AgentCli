package com.paicli.llm.core;

import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * 把现有的 {@link LlmClient} 包成新的 {@link ModelClient}：请求前把消息转成旧格式，
 * 流式回调转成 {@link StreamEvent}，结束后把旧回复转成 {@link ModelResponse}。
 *
 * <p>重试、取消、DSML 转换、思考内容回传这些行为全部沿用原来的 Client，不在这里重复实现。
 * 工具调用和用量在原 Client 返回之后才发事件，所以 DeepSeek 正文里的 DSML 转出来的调用也能拿到。
 */
public final class LegacyModelClient implements ModelClient {

    private final LlmClient delegate;

    public LegacyModelClient(LlmClient delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public LlmClient delegate() {
        return delegate;
    }

    /**
     * 一次调用的两种结果：原 Client 返回的旧格式回复（原样，不经过转换），和新格式结果（带停止原因）。
     */
    public record Exchange(LlmClient.ChatResponse raw, ModelResponse response) {
    }

    @Override
    public ModelResponse stream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<StreamEvent> events)
            throws IOException {
        return exchange(LegacyMessageCodec.toLegacyMessages(messages), LegacyMessageCodec.toLegacyTools(tools), events)
                .response();
    }

    /**
     * 直接用旧格式的对话和工具定义发请求，同时拿到新格式的事件和停止原因。
     *
     * <p>给对话还存成旧格式的调用方用（目前是 Agent 内核）：请求原样交给原 Client，不做消息转换，
     * 发给供应商的内容和直接调用 {@code chat} 完全相同；旧格式回复也原样返回，不会因为来回转换丢掉
     * null 和空串的区别。对话整体换成新格式要等会话存储改造（升级计划第 7 阶段）。
     */
    public Exchange exchange(List<LlmClient.Message> messages, List<LlmClient.Tool> tools,
                             Consumer<StreamEvent> events) throws IOException {
        if (events == null) {
            // 不传 listener 时保持旧的非流式行为（重试策略和 DSML 过滤都依赖 listener 是不是 NO_OP），
            // 代价是拿不到供应商的停止原因。
            LlmClient.ChatResponse raw = delegate.chat(messages, tools, LlmClient.StreamListener.NO_OP);
            return new Exchange(raw, LegacyMessageCodec.toModelResponse(raw, null));
        }

        EventForwarder forwarder = new EventForwarder(events);
        LlmClient.ChatResponse raw = delegate.chat(messages, tools, forwarder);
        ModelResponse result = LegacyMessageCodec.toModelResponse(raw, forwarder.finishReason);

        List<ContentBlock.ToolCall> calls = result.message().toolCalls();
        for (int i = 0; i < calls.size(); i++) {
            events.accept(new StreamEvent.ToolCallCompleted(i, calls.get(i)));
        }
        if (result.usage().reported()) {
            events.accept(new StreamEvent.UsageReported(result.usage()));
        }
        events.accept(new StreamEvent.Completed(result));
        return new Exchange(raw, result);
    }

    @Override
    public String modelName() {
        return delegate.getModelName();
    }

    @Override
    public String providerName() {
        return delegate.getProviderName();
    }

    @Override
    public void cancel() {
        delegate.cancelInFlightCalls();
    }

    private static final class EventForwarder implements LlmClient.StreamListener {
        private final Consumer<StreamEvent> events;
        private String finishReason;

        private EventForwarder(Consumer<StreamEvent> events) {
            this.events = events;
        }

        @Override
        public void onReasoningDelta(String delta) {
            if (delta != null && !delta.isEmpty()) {
                events.accept(new StreamEvent.ReasoningDelta(delta));
            }
        }

        @Override
        public void onContentDelta(String delta) {
            if (delta != null && !delta.isEmpty()) {
                events.accept(new StreamEvent.TextDelta(delta));
            }
        }

        @Override
        public void onFinishReason(String reason) {
            this.finishReason = reason;
        }
    }
}
