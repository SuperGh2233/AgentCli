package com.paicli.testing;

import com.paicli.llm.LlmClient;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.function.BiConsumer;

/**
 * 测试用的假模型：按脚本依次返回预设回复，并记录每次请求实际发出的消息和工具定义。
 *
 * <p>用于 Agent 循环的回放测试，不访问网络、不调用真实模型。脚本用完后再请求会抛
 * {@link IOException}，让“多发了一次请求”这类回归直接暴露出来。
 */
public final class ScriptedLlmClient implements LlmClient {

    /** 脚本里的一步：回复 + 可选的供应商停止原因（比如 length 表示被长度截断）。 */
    public record Step(ChatResponse response, String finishReason) {
    }

    private final Queue<Step> script;
    private final List<List<Message>> requests = new ArrayList<>();
    private final List<List<Tool>> toolRequests = new ArrayList<>();
    private BiConsumer<Integer, List<Message>> onRequest = (number, messages) -> { };
    private int failOnRequest;
    private boolean streamContent;

    private ScriptedLlmClient(List<Step> script) {
        this.script = new ArrayDeque<>(script);
    }

    public static ScriptedLlmClient of(ChatResponse... responses) {
        return new ScriptedLlmClient(Arrays.stream(responses).map(r -> new Step(r, null)).toList());
    }

    /** 需要指定停止原因时用这个。 */
    public static ScriptedLlmClient ofSteps(Step... steps) {
        return new ScriptedLlmClient(Arrays.asList(steps));
    }

    public static Step step(ChatResponse response) {
        return new Step(response, null);
    }

    /** 这次回复达到输出长度上限被截断（finish_reason=length）。 */
    public static Step truncated(ChatResponse response) {
        return new Step(response, "length");
    }

    /** 模型给出最终回复，不调用工具。 */
    public static ChatResponse answer(String content) {
        return new ChatResponse("assistant", content, null, 10, 5);
    }

    /** 模型在一次回复里请求一个或多个工具调用。 */
    public static ChatResponse toolCalls(ToolCall... calls) {
        return new ChatResponse("assistant", "", List.of(calls), 10, 5);
    }

    public static ToolCall call(String id, String toolName, String argumentsJson) {
        return new ToolCall(id, new ToolCall.Function(toolName, argumentsJson));
    }

    /** 第 number 次请求（从 1 开始）发出时回调，可在这里模拟取消、Steer 等外部事件。 */
    public ScriptedLlmClient onRequest(BiConsumer<Integer, List<Message>> callback) {
        this.onRequest = callback == null ? (number, messages) -> { } : callback;
        return this;
    }

    /** 像真实供应商一样把回答正文通过 listener 流式发出（整段作为一次增量）。 */
    public ScriptedLlmClient streamingContent() {
        this.streamContent = true;
        return this;
    }

    /** 第 number 次请求（从 1 开始）抛出 IOException，模拟供应商调用失败。 */
    public ScriptedLlmClient failOnRequest(int number) {
        this.failOnRequest = number;
        return this;
    }

    /** 每次请求发出的消息快照，按请求顺序。 */
    public List<List<Message>> requests() {
        return requests;
    }

    /** 每次请求暴露给模型的工具定义快照；没有工具时是空列表。 */
    public List<List<Tool>> toolRequests() {
        return toolRequests;
    }

    public List<Message> request(int number) {
        return requests.get(number - 1);
    }

    public int remainingResponses() {
        return script.size();
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener)
            throws IOException {
        requests.add(List.copyOf(messages));
        toolRequests.add(tools == null ? List.of() : List.copyOf(tools));
        int number = requests.size();
        onRequest.accept(number, requests.get(number - 1));
        if (failOnRequest == number) {
            throw new IOException("scripted provider failure on request " + number);
        }
        Step step = script.poll();
        if (step == null) {
            throw new IOException("scripted responses exhausted at request " + number);
        }
        if (streamContent && listener != null && step.response().content() != null
                && !step.response().content().isEmpty()) {
            listener.onContentDelta(step.response().content());
        }
        // 和真实 Client 一样：只在成功返回前回调一次停止原因。
        if (step.finishReason() != null && listener != null) {
            listener.onFinishReason(step.finishReason());
        }
        return step.response();
    }

    @Override
    public String getModelName() {
        return "scripted";
    }

    @Override
    public String getProviderName() {
        return "scripted";
    }
}
