package com.haocli.llm.core;

import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/**
 * 和厂商无关的模型接口：输入统一的消息和工具定义，输出统一的流式事件和结果。
 *
 * <p>升级计划第 1 阶段先通过 {@link LegacyModelClient} 包装现有的 {@code LlmClient}。第 2 阶段分两步：
 * 先把循环原样搬进内核 {@code AgentLoop}（行为不变），再让内核改用这个接口并按停止原因处理截断；
 * 各家 Client 以后再逐个改成直接实现它。
 */
public interface ModelClient {

    /**
     * 发一次请求并按 {@link StreamEvent} 的顺序约定回调。
     *
     * @param events 事件回调；传 null 表示不需要流式事件，这时停止原因只能按有没有工具调用推断
     * @return 完整结果，和最后一条 {@link StreamEvent.Completed} 里的相同
     * @throws IOException 供应商调用失败或被取消
     */
    ModelResponse stream(List<ChatMessage> messages, List<ToolSpec> tools, Consumer<StreamEvent> events)
            throws IOException;

    String modelName();

    String providerName();

    /** 尽力取消正在进行的请求。 */
    default void cancel() {
    }
}
