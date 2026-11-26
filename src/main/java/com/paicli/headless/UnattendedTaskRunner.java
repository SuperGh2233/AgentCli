package com.paicli.headless;

import com.paicli.agent.Agent;
import com.paicli.agent.event.AgentEvent;
import com.paicli.agent.event.AgentEventListener;
import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.runtime.task.TaskRunner;
import com.paicli.tool.CommandSandboxMode;
import com.paicli.tool.CommandSandboxStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Runtime API（{@code serve --http}）和后台任务的执行器：每个任务一个新 Agent，
 * 工具按 {@link UnattendedPermissions} 白名单放行，渲染器静默，结果取本轮事件里的原始回答。
 *
 * <p>取代原来 {@code Main.runHeadlessTask} 里的裸 {@code ToolRegistry}（没有审批也没有白名单）。
 * 结果改从 {@link AgentEvent.TurnFinished} 取：原来直接用 {@code agent.run()} 的返回值，
 * 真实模型流式输出时返回值是空串，调用方拿不到回答。
 */
public final class UnattendedTaskRunner implements TaskRunner {

    private final Supplier<LlmClient> llm;
    private final Path projectDir;
    private final UnattendedPermissions permissions;
    private final Path historyHome;

    /**
     * @param llm         每个任务开始时取当前模型（交互会话里切换模型后，新任务跟着变）
     * @param historyHome 会话记录所在的用户目录；null 表示不记录
     */
    public UnattendedTaskRunner(Supplier<LlmClient> llm, Path projectDir, UnattendedPermissions permissions,
                                Path historyHome) {
        this.llm = Objects.requireNonNull(llm, "llm");
        this.projectDir = projectDir.toAbsolutePath().normalize();
        this.permissions = permissions == null ? UnattendedPermissions.READ_ONLY : permissions;
        this.historyHome = historyHome;
    }

    public UnattendedPermissions permissions() {
        return permissions;
    }

    @Override
    public String run(String prompt) {
        return run(prompt, AgentEventListener.NONE);
    }

    /** 和 {@link #run(String)} 一样，另外把本轮每个事件转给 {@code events}（Runtime API 写进事件流）。 */
    @Override
    public String run(String prompt, AgentEventListener events) {
        AgentEventListener forward = events == null ? AgentEventListener.NONE : events;
        HeadlessToolRegistry registry = new HeadlessToolRegistry(permissions.mode(), permissions.allowedCommands());
        registry.setProjectPath(projectDir.toString());
        CommandSandboxStatus sandbox = registry.configureCommandSandbox(CommandSandboxMode.fromConfiguration(), projectDir);
        if (sandbox != null && sandbox.message() != null && !sandbox.message().isBlank()) {
            System.err.println(sandbox.message());
        }

        Agent agent = new Agent(llm.get(), registry);
        agent.setRenderer(new SilentRenderer());
        agent.setConversationLedger(openLedger());
        AtomicReference<AgentEvent.TurnFinished> finished = new AtomicReference<>();
        agent.setEventListener(event -> {
            if (event instanceof AgentEvent.TurnFinished turnFinished) {
                finished.set(turnFinished);
            }
            forward.onEvent(event);
        });
        String returned = agent.run(prompt);
        AgentEvent.TurnFinished result = finished.get();
        if (result == null || result.answer() == null) {
            return returned == null ? "" : returned;
        }
        return result.answer();
    }

    private ConversationLedger openLedger() {
        if (historyHome == null) {
            return ConversationLedger.disabled();
        }
        try {
            return ConversationLedger.openDefault(historyHome);
        } catch (IOException e) {
            // 记录目录暂时不可用时任务照常执行。
            return ConversationLedger.disabled();
        }
    }
}
