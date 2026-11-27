package com.haocli.agent;

import com.haocli.llm.LlmClient;
import com.haocli.context.ContextProfile;
import com.haocli.context.TokenUsageFormatter;
import com.haocli.history.ConversationLedger;
import com.haocli.memory.AutoCompactionManager;
import com.haocli.memory.ExplicitMemoryHints;
import com.haocli.memory.MemoryManager;
import com.haocli.prompt.PromptAssembler;
import com.haocli.prompt.PromptContext;
import com.haocli.prompt.PromptMode;
import com.haocli.prompt.ProjectMemoryLoader;
import com.haocli.render.PlainRenderer;
import com.haocli.render.Renderer;
import com.haocli.render.StatusInfo;
import com.haocli.runtime.input.TurnInputManager;
import com.haocli.skill.SkillIndexFormatter;
import com.haocli.skill.SkillRegistry;
import com.haocli.util.AnsiStyle;
import com.haocli.tool.ToolRegistry;
import com.haocli.tool.TurnToolPolicy;
import com.haocli.image.ImageReferenceParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * ReAct 执行方式（默认）的对外入口。
 *
 * <p>循环本身在 {@link AgentLoop}，对话和会话记录在 {@link ConversationContext}。这个类负责组装依赖、
 * 每轮开始前的准备（本轮工具策略、长期记忆放进 system prompt、写入用户输入）和对外方法
 * （清空、恢复、手动压缩、状态显示）。
 */
public class Agent {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);
    /** 正文里残留了没被执行的工具调用文本时给用户的提示，三条执行路径共用。 */
    static final String UNEXECUTED_TOOL_CALL_NOTICE =
            "  ⚠ 上面有一段工具调用格式的文本没有被执行（本轮没有开放对应工具，或格式无法解析）；"
                    + "如果它是你要求展示的示例，可以忽略这条提示";
    private static final java.util.regex.Pattern RESTORED_WEB_COMMAND = java.util.regex.Pattern.compile(
            "(?i)(?:^|[;&|\\s\"])(?:curl|wget|httpie|lynx)(?:$|[\\s\"])");
    private LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    /** 实际发给模型的对话，就是 {@code conversation.messages()} 这一份列表，不是副本。 */
    private final List<LlmClient.Message> conversationHistory;
    private final MemoryManager memoryManager;
    private final AutoCompactionManager autoCompactionManager;
    private final ConversationContext conversation;
    private final AgentLoop loop;
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private Renderer renderer;
    private Supplier<Boolean> hitlEnabledSupplier = () -> false;
    private Supplier<String> sessionModeSupplier = () -> null;
    private boolean returnFinalResponseWhenStreamed;
    private com.haocli.agent.event.AgentEventListener eventListener = com.haocli.agent.event.AgentEventListener.NONE;
    /** 每轮的硬上限；交互命令行不限，无人值守入口由调用方设置。 */
    private RunLimits runLimits = RunLimits.UNLIMITED;
    // Phase 3A: opt-in only for the gated interactive inline ReAct path; Main
    // installs one safe point per Turn and clears it only after the worker stops.
    private ReActSteeringSafePoint steeringSafePoint;
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public Agent(LlmClient llmClient) {
        this(llmClient, new ToolRegistry());
    }

    public Agent(LlmClient llmClient, ToolRegistry toolRegistry) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memoryManager = new MemoryManager(llmClient);
        this.autoCompactionManager = new AutoCompactionManager(llmClient);
        this.conversation = new ConversationContext("react", "agent", autoCompactionManager,
                this::estimateToolsSchemaTokens);
        this.conversationHistory = conversation.messages();
        HarnessFeatures harness = HarnessFeatures.fromSystem();
        this.toolRegistry.setContextOffloadEnabled(harness.contextOffload());
        this.loop = new AgentLoop(toolRegistry, conversation, harness);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.memoryManager.setExternalContextTracker(this.toolRegistry.getExternalContextTracker());
        this.toolRegistry.setMemoryWriter((fact, scope, replaceId, keepBoth) ->
                memoryManager.storeFact(fact, scope, replaceId, keepBoth).describe());
        conversationHistory.add(LlmClient.Message.system(buildSystemPrompt("")));
    }

    /**
     * Attaches the append-only session ledger used by the CLI entry point. Existing
     * in-memory history is not replayed except for the current system prompt, because
     * callers attach the ledger immediately after constructing the Agent.
     */
    public void setConversationLedger(ConversationLedger conversationLedger) {
        conversation.attachLedger(conversationLedger);
    }

    public void setLlmClient(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.memoryManager.setLlmClient(llmClient);
        this.autoCompactionManager.setLlmClient(llmClient);
        this.toolRegistry.setContextProfile(memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
    }

    /**
     * Phase 3 opt-in: the CLI owns the turn lifecycle and enables this only for an
     * interactive inline ReAct run after the unified terminal input path is ready.
     */
    public void configureSteeringSafePoint(TurnInputManager manager, String turnId) {
        this.steeringSafePoint = new ReActSteeringSafePoint(manager, turnId);
    }

    /** Clear the per-turn hook so a later legacy/Plan/Team run cannot reuse it. */
    public void clearSteeringSafePoint() {
        this.steeringSafePoint = null;
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    public void setRenderer(Renderer renderer) {
        this.renderer = renderer;
    }

    public void setReturnFinalResponseWhenStreamed(boolean returnFinalResponseWhenStreamed) {
        this.returnFinalResponseWhenStreamed = returnFinalResponseWhenStreamed;
    }

    /** 订阅每轮执行的结构化事件（无界面模式、Runtime API 等用）；传 null 取消订阅。 */
    public void setEventListener(com.haocli.agent.event.AgentEventListener listener) {
        this.eventListener = listener == null ? com.haocli.agent.event.AgentEventListener.NONE : listener;
    }

    /** 每轮的硬上限（模型请求次数、总 Token、时间）；传 null 表示不限。 */
    public void setRunLimits(RunLimits limits) {
        this.runLimits = limits == null ? RunLimits.UNLIMITED : limits;
    }

    RunLimits runLimits() {
        return runLimits;
    }

    /**
     * 注入 HITL 启用状态的快照源，用于状态栏 / StatusInfo 显示。
     * Main 启动后用 {@code reactAgent.setHitlEnabledSupplier(hitlHandler::isEnabled)} 接进来。
     */
    public void setHitlEnabledSupplier(Supplier<Boolean> supplier) {
        this.hitlEnabledSupplier = supplier == null ? () -> false : supplier;
    }

    /** 状态栏左侧的会话模式文案（auto / plan / yolo / ask），由 CLI 的模式切换提供。 */
    public void setSessionModeSupplier(Supplier<String> supplier) {
        this.sessionModeSupplier = supplier == null ? () -> null : supplier;
    }

    /**
     * 获取渲染器；首次调用时如果未设置，懒加载一个 {@link PlainRenderer} 兜底，
     * 保证旧调用方（构造 Agent 后没有 setRenderer 的代码、单测等）行为不变。
     */
    private Renderer renderer() {
        if (renderer == null) {
            renderer = new PlainRenderer();
        }
        return renderer;
    }

    /**
     * 运行 Agent 循环
     */
    public String run(String userInput) {
        return run(userInput, userInput);
    }

    /**
     * Runs with expanded delivery content while deriving tool authority only from
     * the text the user actually submitted.
     */
    public String run(String userInput, String submittedUserInput) {
        return runInternal(userInput, submittedUserInput, false);
    }

    /**
     * Runs an input that a trusted caller has already classified as an explicit task envelope.
     * URL provenance and no-web rules still come from {@code submittedUserInput}; only the
     * bare-title actionability heuristic is bypassed.
     */
    public String runExplicitTask(String userInput, String submittedUserInput) {
        return runInternal(userInput, submittedUserInput, true);
    }

    private String runInternal(String userInput, String submittedUserInput, boolean explicitTaskEnvelope) {
        log.info("ReAct run started: inputLength={}", userInput == null ? 0 : userInput.length());
        TurnToolPolicy turnToolPolicy = explicitTaskEnvelope
                ? TurnToolPolicy.forExplicitTask(
                        submittedUserInput,
                        toolRegistry.isSharedBrowserSession(),
                        toolRegistry.hasAgentOwnedCurrentBrowserPage())
                : TurnToolPolicy.fromUserInput(
                        submittedUserInput,
                        toolRegistry.isSharedBrowserSession(),
                        toolRegistry.hasAgentOwnedCurrentBrowserPage());
        if (turnToolPolicy.headlineOnly()) {
            renderer().stream().println(AnsiStyle.subtle(
                    "  ℹ 这句输入看起来是标题或摘录，本轮未开放联网工具；需要搜索或抓取时请说明要做什么"));
        }
        pruneHistoricalImagePayloads();
        recordExpandedMcpResources(userInput, submittedUserInput);
        storeExplicitBrowserMemoryHint(userInput);

        // 检索相关长期记忆，注入到 system prompt
        ContextProfile contextProfile = memoryManager.getContextProfile();
        String memoryContext = memoryManager.buildContextForQuery(userInput, contextProfile.memoryContextTokens());
        updateSystemPromptWithMemory(memoryContext);

        conversation.append(ImageReferenceParser.userMessage(
                userInput,
                Path.of(toolRegistry.getProjectPath())), "user_input");

        // 终端和外部订阅者（无界面模式、Runtime API）看的是同一串事件；终端先画，订阅者后收。
        TerminalEventRenderer terminal = new TerminalEventRenderer(renderer(), this::pushStatus);
        com.haocli.agent.event.AgentEventListener external = eventListener;
        AgentLoop.Turn turn = new AgentLoop.Turn(
                llmClient,
                turnToolPolicy,
                steeringSafePoint,
                new ReActHost(submittedUserInput, terminal),
                () -> memoryManager.getContextProfile().compressionTriggerTokens(),
                event -> {
                    deliver(terminal, event);
                    deliver(external, event);
                },
                runLimits);
        return loop.run(turn).text();
    }

    /** 一个订阅者出错不能影响另一个，也不能打断任务。 */
    private static void deliver(com.haocli.agent.event.AgentEventListener listener,
                                com.haocli.agent.event.AgentEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException e) {
            log.warn("Agent event listener failed on {}", event.getClass().getSimpleName(), e);
        }
    }

    /**
     * 清空对话历史并重建基础系统提示，不影响长期记忆条目
     */
    public void clearHistory() {
        autoCompactionManager.clear(conversationHistory);
        memoryManager.getExternalContextTracker().reset();
        conversation.ledger().appendEvent(
                ConversationLedger.HISTORY_CLEAR_EVENT,
                "react",
                "agent",
                "slash_clear",
                java.util.Map.of("discardedViewMessages", conversationHistory.size()));
        conversationHistory.clear();
        conversation.append(
                LlmClient.Message.system(buildSystemPrompt("")),
                "history_reset");
    }

    /**
     * /resume：用以前某个会话的对话替换当前对话。system prompt 按当前项目重新生成，
     * 恢复后的对话会作为检查点写进本次会话记录，所以本次会话之后也能再被恢复。
     */
    public void resumeConversation(String fromSessionId, List<LlmClient.Message> messages) {
        autoCompactionManager.clear(conversationHistory);
        memoryManager.getExternalContextTracker().reset();
        conversationHistory.clear();
        conversation.append(
                LlmClient.Message.system(buildSystemPrompt("")),
                "history_reset");
        if (messages != null) {
            conversationHistory.addAll(messages);
        }
        recordRestoredExternalContext(conversationHistory);
        conversation.recordViewCheckpoint("resume", Map.of("resumedFrom", fromSessionId == null ? "" : fromSessionId));
    }

    /** 恢复的对话里读过网页、浏览器、MCP 或 curl 结果时，照样暂停自动写记忆。 */
    private void recordRestoredExternalContext(List<LlmClient.Message> messages) {
        com.haocli.memory.ExternalContextTracker tracker = memoryManager.getExternalContextTracker();
        for (LlmClient.Message message : messages) {
            if ("user".equals(message.role())
                    && message.content() != null
                    && message.content().contains("<resource server=\"")) {
                tracker.record("mcp_resource_mention");
            }
            if (message.toolCalls() == null) {
                continue;
            }
            for (LlmClient.ToolCall toolCall : message.toolCalls()) {
                if (toolCall == null || toolCall.function() == null) {
                    continue;
                }
                String name = toolCall.function().name();
                if (!tracker.recordToolResult(name)
                        && "execute_command".equals(name)
                        && toolCall.function().arguments() != null
                        && RESTORED_WEB_COMMAND.matcher(toolCall.function().arguments()).find()) {
                    tracker.record("execute_command(curl/wget)");
                }
            }
        }
    }

    /**
     * 手动压缩当前 ReAct 对话历史，不等待上下文窗口阈值触发。
     */
    public CompactionResult compactHistoryNow() {
        long beforeTokens = estimateCurrentContextTokens();
        try {
            // 压缩记录和检查点由 ConversationContext 写。
            AutoCompactionManager.Result result = conversation.compactNow();
            return new CompactionResult(result.compacted(), beforeTokens, estimateCurrentContextTokens(), null);
        } catch (Exception e) {
            log.warn("manual conversationHistory compaction failed", e);
            return new CompactionResult(false, beforeTokens, estimateCurrentContextTokens(), e.getMessage());
        }
    }

    public record CompactionResult(boolean compacted, long beforeTokens, long afterTokens, String error) {
    }

    /** 当前状态栏快照：ctx 表示下一轮请求仍会携带的上下文估算，不含累计 in/out 用量。 */
    public StatusInfo currentStatus(String phase) {
        String normalizedPhase = phase == null || phase.isBlank() ? "idle" : phase;
        String model = llmClient == null ? "—" : com.haocli.llm.ModelCatalog.statusName(llmClient.getProviderName(), llmClient.getModelName());
        long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
        boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
        long contextTokens = estimateCurrentContextTokens();
        if ("idle".equals(normalizedPhase)) {
            return StatusInfo.idle(model, contextWindow, contextTokens, hitl).withSessionMode(sessionModeSupplier.get());
        }
        return StatusInfo.active(model, contextWindow, contextTokens, hitl, normalizedPhase)
                .withSessionMode(sessionModeSupplier.get());
    }

    /**
     * 将记忆上下文注入到 system prompt 中（替换 conversationHistory[0]）
     */
    private void updateSystemPromptWithMemory(String memoryContext) {
        LlmClient.Message systemMessage = LlmClient.Message.system(buildSystemPrompt(memoryContext));
        conversation.replaceSystemPrompt(systemMessage, "memory_context_refresh");
    }

    private String buildSystemPrompt(String memoryContext) {
        return promptAssembler.assemble(PromptMode.AGENT, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .memoryContext(memoryContext)
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());
    }

    private void reportAutomaticallySavedFacts(String submittedUserInput) {
        List<com.haocli.memory.MemoryEntry> saved = memoryManager.extractFactsFromUserTurn(submittedUserInput);
        if (!saved.isEmpty()) {
            renderer().stream().println("💾 自动提取并保存 " + saved.size()
                    + " 条项目事实（待核实；可用 /memory list 查看和删除）。");
        }
    }

    private void pruneHistoricalImagePayloads() {
        int messageCount = 0;
        int imageCount = 0;
        for (int i = 0; i < conversationHistory.size(); i++) {
            LlmClient.Message message = conversationHistory.get(i);
            int images = message.imagePartCount();
            if (images <= 0) {
                continue;
            }
            conversationHistory.set(i, message.withoutImageContent());
            messageCount++;
            imageCount += images;
        }
        if (imageCount > 0) {
            log.info("Pruned historical image payloads before new ReAct turn: messages={}, images={}",
                    messageCount, imageCount);
        }
    }

    private String buildSkillIndex() {
        if (skillRegistry == null) return "";
        try {
            return SkillIndexFormatter.format(skillRegistry.enabledSkills());
        } catch (Exception e) {
            log.warn("Failed to build skill index", e);
            return "";
        }
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context", e);
            return "";
        }
    }

    private String buildProjectMemoryContext() {
        try {
            return ProjectMemoryLoader.createDefault(Path.of(toolRegistry.getProjectPath())).loadForPrompt();
        } catch (Exception e) {
            log.warn("Failed to load HAO.md project memory", e);
            return "";
        }
    }

    /**
     * 获取对话历史（用于调试）
     */
    public List<LlmClient.Message> getConversationHistory() {
        return new ArrayList<>(conversationHistory);
    }

    public ConversationLedger getConversationLedger() {
        return conversation.ledger();
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    /** 用户输入里展开的 MCP resource 正文同样是外部内容，按会话记一次来源。 */
    private void recordExpandedMcpResources(String userInput, String submittedUserInput) {
        if (userInput == null || userInput.equals(submittedUserInput)) {
            return;
        }
        if (userInput.contains("<resource server=\"")) {
            memoryManager.getExternalContextTracker().record("mcp_resource_mention");
        }
    }

    /**
     * 自动写记忆路径：用户说“记住 + 复用已登录 Chrome”时直接保存一条登录态偏好。站点域名取自
     * 最近上下文，可能来自网页或 MCP 结果，所以会话接触过外部内容时跳过，交给模型按用户原文
     * 调用 save_memory（显式保存会在 metadata 里记录外部上下文来源）。
     */
    private void storeExplicitBrowserMemoryHint(String userInput) {
        if (memoryManager.blocksAutomaticMemory()) {
            log.info("Skip automatic browser-login memory hint: session has external context {}",
                    memoryManager.getExternalContextTracker().sources());
            return;
        }
        List<String> recentTexts = conversationHistory.stream()
                .map(LlmClient.Message::content)
                .filter(content -> content != null && !content.isBlank())
                .toList();
        String fact = ExplicitMemoryHints.browserLoginFact(userInput, recentTexts);
        if (fact != null && !fact.isBlank()) {
            memoryManager.storeFact(fact, "global");
        }
    }

    public String getContextStatus() {
        com.haocli.context.ContextProfile profile = memoryManager.getContextProfile();
        int window = profile.maxContextWindow();
        int triggerTokens = profile.compressionTriggerTokens();

        // 分类估算 token 占用
        int systemTokens = 0, userTokens = 0, assistantTokens = 0, toolTokens = 0;
        int systemCount = 0, userCount = 0, assistantCount = 0, toolCount = 0;
        for (LlmClient.Message msg : conversationHistory) {
            int t = com.haocli.memory.TokenBudget.estimateMessagesTokens(java.util.List.of(msg));
            switch (msg.role()) {
                case "system" -> { systemTokens += t; systemCount++; }
                case "user" -> { userTokens += t; userCount++; }
                case "assistant" -> { assistantTokens += t; assistantCount++; }
                case "tool" -> { toolTokens += t; toolCount++; }
            }
        }
        int messagesTokens = userTokens + assistantTokens + toolTokens;
        int toolsSchemaTokens = estimateToolsSchemaTokens();
        int total = systemTokens + messagesTokens + toolsSchemaTokens;
        double ratio = window > 0 ? (double) total / window : 0;
        int triggerRemaining = Math.max(0, triggerTokens - total);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("📊 Context Usage   %s   window: %s%n",
                modelLabel(), formatTokens(window)));
        sb.append("\n  ").append(progressBar(ratio, 30))
                .append(String.format("  %d%%  (%s / %s)%n",
                        (int) Math.round(ratio * 100), formatTokens(total), formatTokens(window)));
        sb.append("\n  当前占用细分:\n");
        sb.append(formatLine("System prompt",      systemTokens,    window, systemCount));
        sb.append(formatLine("Tools schema",       toolsSchemaTokens, window, -1));
        sb.append(formatLine("Conversation",       messagesTokens, window,
                userCount + assistantCount + toolCount));
        sb.append("    ─────────────────────────────────\n");
        sb.append(String.format("    合计:              %8s  (%4.1f%%)%n",
                formatTokens(total), ratio * 100));
        sb.append(String.format("%n  压缩阈值: %s (%d%%)   距压缩还有: %s%n",
                formatTokens(triggerTokens),
                (int) (profile.compressionTriggerRatio() * 100),
                formatTokens(triggerRemaining)));
        int clearTrigger = autoCompactionManager.toolResultClearTriggerTokens(triggerTokens);
        sb.append("  旧工具结果清理: ")
                .append(clearTrigger > 0
                        ? formatTokens(clearTrigger) + " 触发，保留最近 "
                                + autoCompactionManager.toolResultClearingConfig().keepRecent() + " 条"
                        : "关闭")
                .append("\n");
        sb.append("  自动压缩: ")
                .append(autoCompactionManager.isSessionMemoryEnabled()
                        ? "Session Memory 优先，完整摘要回退"
                        : "完整摘要（Session Memory 实验开关关闭）")
                .append("\n");
        sb.append("  MCP resources 自动索引: ")
                .append(profile.mcpResourceIndexEnabled() ? "开启" : "关闭（window 不足 32k）")
                .append("\n");
        sb.append("  prompt cache: ").append(profile.promptCacheMode()).append("\n");
        sb.append("\n");
        sb.append(memoryManager.getSystemStatus());
        return sb.toString();
    }

    private String modelLabel() {
        if (llmClient == null) return "(no model)";
        return llmClient.getModelName() + " (" + llmClient.getProviderName() + ")";
    }

    private int estimateToolsSchemaTokens() {
        try {
            return com.haocli.memory.MemoryEntry.estimateTokens(
                    new ObjectMapper().writeValueAsString(toolRegistry.getToolDefinitions()));
        } catch (Exception e) {
            return 0;
        }
    }

    private long estimateCurrentContextTokens() {
        return conversation.estimateRequestTokens();
    }


    private static String formatLine(String label, int tokens, int window, int count) {
        double pct = window > 0 ? (double) tokens / window * 100 : 0;
        String countLabel = count >= 0 ? String.format("  [%d 条]", count) : "";
        return String.format("    %-18s %8s  (%4.1f%%)%s%n",
                label + ":", formatTokens(tokens), pct, countLabel);
    }

    private static String progressBar(double ratio, int width) {
        ratio = Math.max(0, Math.min(1, ratio));
        int filled = (int) Math.round(ratio * width);
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '█' : '░');
        }
        bar.append("]");
        return bar.toString();
    }

    private static String formatTokens(int tokens) {
        if (tokens >= 1_000_000) return String.format("%.1fM", tokens / 1_000_000.0);
        if (tokens >= 1_000)     return String.format("%.1fk", tokens / 1_000.0);
        return String.valueOf(tokens);
    }

    /**
     * 获取工具注册表（用于同步项目路径等配置）
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /** 把本轮累计 Token、耗时、HITL 状态推送给 renderer 状态栏（数字来自 Agent 事件）。 */
    private void pushStatus(long inputTokens, long outputTokens, long cachedInputTokens, long elapsedMs,
                            String phase) {
        try {
            String model = llmClient == null ? "—" : com.haocli.llm.ModelCatalog.statusName(llmClient.getProviderName(), llmClient.getModelName());
            long contextWindow = llmClient == null ? 0L : llmClient.maxContextWindow();
            boolean hitl = Boolean.TRUE.equals(hitlEnabledSupplier.get());
            String cost = TokenUsageFormatter.estimatedCostCny(
                    llmClient, (int) inputTokens, (int) outputTokens, (int) cachedInputTokens);
            renderer().updateStatus(StatusInfo.tokens(
                    model,
                    contextWindow,
                    estimateCurrentContextTokens(),
                    inputTokens,
                    outputTokens,
                    cachedInputTokens,
                    cost,
                    hitl,
                    elapsedMs,
                    phase == null || phase.isBlank()
                            ? (inputTokens + outputTokens > 0 || elapsedMs > 0 ? "running" : "idle")
                            : phase).withSessionMode(sessionModeSupplier.get()));
        } catch (Exception e) {
            log.debug("status push failed", e);
        }
    }

    private String formatUserFacingResponse(String reasoningContent, String answer) {
        String normalizedReasoning = reasoningContent == null ? "" : reasoningContent.trim();
        String normalizedAnswer = answer == null ? "" : answer.trim();

        if (!renderer().rendersReasoning() || normalizedReasoning.isEmpty()) {
            return normalizedAnswer;
        }
        if (normalizedAnswer.isEmpty()) {
            return "🧠 思考过程:\n" + normalizedReasoning;
        }
        return "🧠 思考过程:\n" + normalizedReasoning + "\n\n▪ " + normalizedAnswer;
    }

    /** 内核需要的收尾动作（显示走事件，由 {@link TerminalEventRenderer} 负责）。 */
    private final class ReActHost implements AgentLoop.Host {
        private final String submittedUserInput;
        private final TerminalEventRenderer terminal;

        private ReActHost(String submittedUserInput, TerminalEventRenderer terminal) {
            this.submittedUserInput = submittedUserInput;
            this.terminal = terminal;
        }

        @Override
        public boolean answerStreamed() {
            return terminal.hasStreamedOutput();
        }

        @Override
        public String formatAnswer(String reasoningTranscript, String answer) {
            return formatUserFacingResponse(reasoningTranscript, answer);
        }

        @Override
        public boolean returnFinalResponseWhenStreamed() {
            return returnFinalResponseWhenStreamed;
        }

        @Override
        public void recordTokenUsage(AgentBudget budget) {
            memoryManager.recordTokenUsage(
                    budget.totalInputTokens(), budget.totalOutputTokens(), budget.totalCachedInputTokens());
        }

        @Override
        public void afterFinalAnswer() {
            reportAutomaticallySavedFacts(submittedUserInput);
        }
    }
}
