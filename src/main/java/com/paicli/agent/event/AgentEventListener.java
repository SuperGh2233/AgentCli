package com.paicli.agent.event;

/** 接收 {@link AgentEvent}。在 Agent 工作线程上同步调用，实现不要阻塞太久，也不要抛异常。 */
@FunctionalInterface
public interface AgentEventListener {

    AgentEventListener NONE = event -> { };

    void onEvent(AgentEvent event);
}
