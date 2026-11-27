package com.haocli.runtime.task;

import com.haocli.agent.event.AgentEventListener;

@FunctionalInterface
public interface TaskRunner {
    String run(String prompt) throws Exception;

    /**
     * 执行任务，同时把本轮的 Agent 事件交给 {@code events}（Runtime API 用它把中间过程写进事件流）。
     * 默认实现不产生事件，只调 {@link #run(String)}。
     */
    default String run(String prompt, AgentEventListener events) throws Exception {
        return run(prompt);
    }
}
