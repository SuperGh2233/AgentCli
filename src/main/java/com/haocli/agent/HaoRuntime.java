package com.haocli.agent;

import com.haocli.agent.event.AgentEvent;
import com.haocli.agent.event.AgentEventListener;
import com.haocli.context.ContextProfile;
import com.haocli.headless.UnattendedPermissions;
import com.haocli.llm.LlmClient;
import com.haocli.memory.AutoCompactionManager;
import com.haocli.prompt.PromptAssembler;
import com.haocli.prompt.PromptContext;
import com.haocli.prompt.PromptMode;
import com.haocli.runtime.CancellationContext;
import com.haocli.runtime.CancellationToken;
import com.haocli.tool.Tool;
import com.haocli.tool.ToolRegistry;
import com.haocli.tool.TurnToolPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 进程内运行时：其他 Java 应用不经过命令行、终端界面、MCP，直接嵌入 HaoCli 的 Agent 内核。
 *
 * <pre>{@code
 * HaoRuntime runtime = HaoRuntime.builder()
 *         .model(llmClient)                         // 任意 LlmClient 实现
 *         .projectRoot(Path.of("/srv/workspace"))   // 文件工具只能访问这个目录
 *         .tool(myTool)                             // 自定义工具（com.haocli.tool.Tool）
 *         .context(task -> "订单系统只读，金额单位是分")   // 嵌入方提供的上下文
 *         .onEvent(event -> log(event))             // 运行事件
 *         .build();
 * HaoRuntime.RunResult result = runtime.run("查一下订单 42 的状态");
 * }</pre>
 *
 * <p>每次 {@link #run} 是一次独立任务（新对话）。执行走和命令行同一个 {@link AgentLoop}：工具调用和结果配对、
 * 截断保护、预算、取消、五层上下文处理都相同。权限默认只读，用无人值守通道（{@code -p}、Runtime API）同一套白名单；
 * 被策略拒绝的调用作为拒绝结果还给模型。同一个实例同一时间只跑一个任务。</p>
 */
public final class HaoRuntime {

    /** 嵌入方提供的上下文（可信，放进系统提示词）；按任务返回，可以返回空字符串。 */
    @FunctionalInterface
    public interface ContextProvider {
        String context(String task);
    }

    public enum Status { COMPLETED, PARTIAL, FAILED, CANCELLED }

    /**
     * 嵌入方的扩展模块（稳定的公开接口，内部适配成内核的 {@code TurnHook}）。在内置模块之后按注册顺序调用；
     * 抛出的异常只记日志，不打断任务。同一个实例会用于每次运行，需要状态时请自己保证线程安全。
     */
    public interface Hook {
        /** 每次模型请求之前。 */
        default void beforeModelRequest(View view) {
        }

        /** 一批工具结果已经写回对话之后（每个结果都已和它的调用配对）。 */
        default void afterToolResults(List<ToolResult> results, View view) {
        }
    }

    /** 模块能看到和能做的事：只读的对话、追加一条备注、发一条提示事件。不能改写或删除已有消息。 */
    public interface View {
        /** 当前对话（只读）。 */
        List<LlmClient.Message> messages();

        /** 追加一条 user 消息（比如提醒、外部状态），下一次模型请求会带上。 */
        void addNote(String text);

        /** 发一条提示事件（{@code AgentEvent.Notice}），不进对话。 */
        void notice(String text);
    }

    /** 模块看到的一条工具结果。 */
    public record ToolResult(String id, String name, com.haocli.tool.ToolStatus status, String text) {
    }

    /**
     * 一次运行的结构化结果。reason 在失败和部分完成时说明原因（比如 {@code llm_transient}、
     * {@code hard_iteration_limit}），完成和取消时为 null。
     */
    public record RunResult(Status status, String answer, String reason, int modelRequests,
                            long inputTokens, long outputTokens, long cachedInputTokens, long durationMs) {
    }

    private static final String DEFAULT_PERMISSIONS_NOTE = "权限：默认只读；需要写文件时由嵌入方用 edit 权限创建运行时。";

    private final LlmClient model;
    private final Path projectRoot;
    private final List<Tool> tools;
    private final ContextProvider contextProvider;
    private final UnattendedPermissions permissions;
    private final RunLimits limits;
    private final AgentEventListener listener;
    private final HarnessFeatures features;
    private final List<Hook> hooks;
    private final AtomicReference<CancellationToken> running = new AtomicReference<>();

    private HaoRuntime(Builder b) {
        this.model = Objects.requireNonNull(b.model, "model");
        this.projectRoot = Objects.requireNonNull(b.projectRoot, "projectRoot").toAbsolutePath().normalize();
        this.tools = List.copyOf(b.tools);
        this.contextProvider = b.contextProvider == null ? task -> "" : b.contextProvider;
        this.permissions = b.permissions == null ? UnattendedPermissions.READ_ONLY : b.permissions;
        this.limits = b.limits == null ? RunLimits.UNLIMITED : b.limits;
        this.listener = b.listener == null ? AgentEventListener.NONE : b.listener;
        this.features = b.features == null ? HarnessFeatures.ALL_ON : b.features;
        this.hooks = List.copyOf(b.hooks);
        // 提前校验权限（比如拒绝 sandbox），不要等到第一次运行才失败
        this.permissions.newToolRegistry(projectRoot, tools);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 在当前线程执行一个任务，直到完成、部分完成、失败或被 {@link #cancel} 取消。 */
    public RunResult run(String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("任务为空");
        }
        ToolRegistry registry = permissions.newToolRegistry(projectRoot, tools);
        registry.setContextProfile(ContextProfile.from(model));
        registry.setCurrentModel(model.getProviderName(), model.getModelName());
        ConversationContext conversation = new ConversationContext(
                "sdk", "runtime", new AutoCompactionManager(model), () -> 0L);
        conversation.messages().add(LlmClient.Message.system(systemPrompt(task)));
        conversation.append(LlmClient.Message.user(task), "user_input");

        AtomicReference<AgentEvent.TurnFinished> finished = new AtomicReference<>();
        AgentEventListener events = event -> {
            if (event instanceof AgentEvent.TurnFinished f) finished.set(f);
            listener.onEvent(event);
        };
        AgentLoop loop = new AgentLoop(registry, conversation, features, () -> {
            List<TurnHook> all = new ArrayList<>(DefaultTurnHooks.create(features, registry));
            hooks.forEach(hook -> all.add(adapt(hook)));
            return all;
        });
        int trigger = ContextProfile.from(model).compressionTriggerTokens();
        AgentLoop.Turn turn = new AgentLoop.Turn(model, TurnToolPolicy.forExplicitTask(task), null, HOST,
                () -> trigger, events, limits);

        CancellationToken token = CancellationContext.startRun();
        if (!running.compareAndSet(null, token)) {
            CancellationContext.clear(token);
            throw new IllegalStateException("这个运行时正在执行另一个任务");
        }
        try {
            AgentLoop.Result result = loop.run(turn);
            AgentEvent.TurnFinished f = finished.get();
            return new RunResult(Status.valueOf(result.status().name()), result.answer(), result.reason(),
                    f == null ? 0 : f.modelRequests(),
                    f == null ? 0 : f.inputTokens(), f == null ? 0 : f.outputTokens(),
                    f == null ? 0 : f.cachedInputTokens(), f == null ? 0 : f.durationMs());
        } finally {
            running.set(null);
            CancellationContext.clear(token);
        }
    }

    private static TurnHook adapt(Hook hook) {
        return new TurnHook() {
            @Override
            public void beforeModelRequest(Context context) {
                hook.beforeModelRequest(view(context));
            }

            @Override
            public void afterToolResults(List<com.haocli.tool.ToolRegistry.ToolExecutionResult> results, Context context) {
                hook.afterToolResults(results.stream()
                        .map(r -> new ToolResult(r.id(), r.name(), r.status(), r.result()))
                        .toList(), view(context));
            }
        };
    }

    private static View view(TurnHook.Context context) {
        return new View() {
            @Override
            public List<LlmClient.Message> messages() {
                return java.util.Collections.unmodifiableList(context.conversation().messages());
            }

            @Override
            public void addNote(String text) {
                if (text != null && !text.isBlank()) {
                    context.conversation().append(LlmClient.Message.user(text), "sdk_hook_note");
                }
            }

            @Override
            public void notice(String text) {
                context.notice(text);
            }
        };
    }

    /** 请求取消正在执行的任务（可以从别的线程调用）；内核在下一个安全点停下，结果状态为 CANCELLED。 */
    public void cancel() {
        CancellationToken token = running.get();
        if (token != null) {
            token.cancel();
        }
    }

    private String systemPrompt(String task) {
        String base = PromptAssembler.createDefault().assemble(PromptMode.AGENT, PromptContext.builder()
                .toolsEnabled(model.supportsTools())
                .build());
        String extra = contextProvider.context(task);
        StringBuilder sb = new StringBuilder(base);
        sb.append("\n\n## 运行环境\n").append(DEFAULT_PERMISSIONS_NOTE)
                .append("\n项目根目录：").append(projectRoot);
        if (extra != null && !extra.isBlank()) {
            sb.append("\n\n## 嵌入方提供的上下文\n").append(extra.trim());
        }
        return sb.toString();
    }

    private static final AgentLoop.Host HOST = new AgentLoop.Host() {
        @Override
        public boolean answerStreamed() {
            return true;
        }

        @Override
        public String formatAnswer(String reasoningTranscript, String answer) {
            return answer;
        }

        @Override
        public boolean returnFinalResponseWhenStreamed() {
            return true;
        }

        @Override
        public void recordTokenUsage(AgentBudget budget) {
        }

        @Override
        public void afterFinalAnswer() {
        }
    };

    public static final class Builder {
        private LlmClient model;
        private Path projectRoot;
        private final List<Tool> tools = new ArrayList<>();
        private ContextProvider contextProvider;
        private UnattendedPermissions permissions;
        private RunLimits limits;
        private AgentEventListener listener;
        private HarnessFeatures features;
        private final List<Hook> hooks = new ArrayList<>();

        private Builder() {
        }

        /** 嵌入方模块，在内置模块之后按注册顺序调用。 */
        public Builder hook(Hook hook) {
            this.hooks.add(Objects.requireNonNull(hook, "hook"));
            return this;
        }

        /** 必填：任意 {@link LlmClient} 实现（内置服务商、OpenAI 兼容网关或自己的实现）。 */
        public Builder model(LlmClient model) {
            this.model = model;
            return this;
        }

        /** 必填：文件类工具只能访问这个目录（PathGuard）。 */
        public Builder projectRoot(Path projectRoot) {
            this.projectRoot = projectRoot;
            return this;
        }

        public Builder tool(Tool tool) {
            this.tools.add(Objects.requireNonNull(tool, "tool"));
            return this;
        }

        public Builder tools(List<? extends Tool> tools) {
            tools.forEach(this::tool);
            return this;
        }

        public Builder context(ContextProvider contextProvider) {
            this.contextProvider = contextProvider;
            return this;
        }

        /** 默认只读；edit 允许写文件和有副作用的自定义工具；命令要逐条列出。 */
        public Builder permissions(UnattendedPermissions permissions) {
            this.permissions = permissions;
            return this;
        }

        /** 模型请求次数、总 Token、墙钟时间上限；默认不限。 */
        public Builder limits(RunLimits limits) {
            this.limits = limits;
            return this;
        }

        public Builder onEvent(AgentEventListener listener) {
            this.listener = listener;
            return this;
        }

        /** 可选：关掉部分 Harness 模块（消融实验用），默认全开。 */
        public Builder features(HarnessFeatures features) {
            this.features = features;
            return this;
        }

        public HaoRuntime build() {
            return new HaoRuntime(this);
        }
    }
}
