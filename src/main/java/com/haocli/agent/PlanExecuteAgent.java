package com.haocli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haocli.history.ConversationLedger;
import com.haocli.llm.LlmClient;
import com.haocli.llm.LlmTraceLogger;
import com.haocli.lsp.LspDiagnosticReport;
import com.haocli.memory.AutoCompactionManager;
import com.haocli.memory.MemoryManager;
import com.haocli.plan.*;
import com.haocli.prompt.PromptAssembler;
import com.haocli.prompt.PromptContext;
import com.haocli.prompt.PromptMode;
import com.haocli.prompt.ProjectMemoryLoader;
import com.haocli.runtime.CancellationContext;
import com.haocli.runtime.input.TurnInputManager;
import com.haocli.skill.SkillIndexFormatter;
import com.haocli.skill.SkillRegistry;
import com.haocli.util.AnsiStyle;
import com.haocli.tool.LoadedSkillMessages;
import com.haocli.tool.ToolRegistry;
import com.haocli.tool.ToolResultBoundary;
import com.haocli.tool.ToolRegistry.ToolExecutionResult;
import com.haocli.tool.ToolRegistry.ToolInvocation;
import com.haocli.tool.TurnToolPolicy;
import com.haocli.util.TerminalMarkdownRenderer;
import com.haocli.image.ImageReferenceParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Plan-and-Execute Agent - 先规划后执行
 */
public class PlanExecuteAgent {
    private static final Logger log = LoggerFactory.getLogger(PlanExecuteAgent.class);
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    /** 完成度低于一半时的失败最多触发几次重新规划；达到上限后停止执行剩余任务。 */
    static final String MAX_REPLANS_PROPERTY = "haocli.plan.max.replans";
    static final String MAX_REPLANS_ENV = "HAOCLI_PLAN_MAX_REPLANS";
    static final int DEFAULT_MAX_REPLANS = 2;
    private static final String CANCELLED_PLAN_MESSAGE = "⏹️ 已取消当前计划执行。";
    private record PlanRunOutcome(String result, boolean persistAssistantMessage,
                                  boolean autoExtractEligible) {
        static PlanRunOutcome executed(String result) {
            return new PlanRunOutcome(result, true, result != null && result.startsWith("✅"));
        }

        static PlanRunOutcome canceled(String result) {
            return new PlanRunOutcome(result, false, false);
        }

        static PlanRunOutcome failed(String result) {
            return new PlanRunOutcome(result, true, false);
        }
    }

    private record TaskRunResult(String result, boolean streamedOutput,
                                 TurnToolPolicy.TrustedUrlContext trustedUrls) {
        static TaskRunResult of(String result, boolean streamedOutput, TurnToolPolicy policy) {
            return new TaskRunResult(result, streamedOutput, policy.trustedUrlContext());
        }
    }

    private record TaskExecutionResult(Task task, String result, boolean streamedOutput,
                                       TurnToolPolicy.TrustedUrlContext trustedUrls, Exception error) {
        static TaskExecutionResult success(Task task, TaskRunResult taskRunResult) {
            return new TaskExecutionResult(task, taskRunResult.result(), taskRunResult.streamedOutput(),
                    taskRunResult.trustedUrls(), null);
        }

        static TaskExecutionResult failure(Task task, Exception error) {
            return new TaskExecutionResult(task, null, false, null, error);
        }

        boolean failed() {
            return error != null;
        }
    }

    public interface PlanReviewHandler {
        PlanReviewDecision review(String goal, ExecutionPlan plan);
    }

    public enum PlanReviewAction {
        EXECUTE,
        SUPPLEMENT,
        CANCEL
    }

    public record PlanReviewDecision(PlanReviewAction action, String feedback) {
        public static PlanReviewDecision execute() {
            return new PlanReviewDecision(PlanReviewAction.EXECUTE, null);
        }

        public static PlanReviewDecision supplement(String feedback) {
            return new PlanReviewDecision(PlanReviewAction.SUPPLEMENT, feedback);
        }

        public static PlanReviewDecision cancel() {
            return new PlanReviewDecision(PlanReviewAction.CANCEL, null);
        }
    }

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final Planner planner;
    private final PlanReviewHandler reviewHandler;
    private final MemoryManager memoryManager;
    private final AutoCompactionManager autoCompactionManager;
    private final PrintStream out;
    private ConversationLedger conversationLedger = ConversationLedger.disabled();
    private Supplier<String> externalContextSupplier = () -> "";
    private SkillRegistry skillRegistry;
    private TurnToolPolicy turnToolPolicy = TurnToolPolicy.forExplicitTask("");
    private String submittedPolicyInput = "";
    private PlanSteeringSafePoint planSteeringSafePoint;
    private volatile PlanExecutionObserver executionObserver;
    private final AtomicLong executionObservationFailures = new AtomicLong();
    private final PromptAssembler promptAssembler = PromptAssembler.createDefault();

    public PlanExecuteAgent(LlmClient llmClient) {
        this(llmClient, (goal, plan) -> PlanReviewDecision.execute());
    }

    public PlanExecuteAgent(LlmClient llmClient, PlanReviewHandler reviewHandler) {
        this(llmClient, new ToolRegistry(), null, null, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler);
    }

    public PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry,
                            MemoryManager memoryManager, PlanReviewHandler reviewHandler,
                            PrintStream out) {
        this(llmClient, toolRegistry, null, memoryManager, reviewHandler, out);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler) {
        this(llmClient, toolRegistry, planner, memoryManager, reviewHandler, null);
    }

    PlanExecuteAgent(LlmClient llmClient, ToolRegistry toolRegistry, Planner planner,
                     MemoryManager memoryManager, PlanReviewHandler reviewHandler, PrintStream out) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry != null ? toolRegistry : new ToolRegistry();
        this.out = out == null ? deferredSystemOut() : out;
        this.planner = planner != null ? planner : new Planner(llmClient, this.out);
        this.reviewHandler = reviewHandler == null ? (goal, plan) -> PlanReviewDecision.execute() : reviewHandler;
        this.memoryManager = memoryManager != null ? memoryManager : new MemoryManager(llmClient);
        this.autoCompactionManager = new AutoCompactionManager(llmClient);
        this.toolRegistry.setContextProfile(this.memoryManager.getContextProfile());
        this.toolRegistry.setCurrentModel(llmClient.getProviderName(), llmClient.getModelName());
        this.memoryManager.setProjectPath(this.toolRegistry.getProjectPath());
        this.memoryManager.setExternalContextTracker(this.toolRegistry.getExternalContextTracker());
        this.toolRegistry.setMemoryWriter((fact, scope, replaceId, keepBoth) ->
                this.memoryManager.storeFact(fact, scope, replaceId, keepBoth).describe());
        this.planner.setProjectMemorySupplier(this::buildProjectMemoryContext);
    }

    private static PrintStream deferredSystemOut() {
        return new PrintStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.out.write(b);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                System.out.write(b, off, len);
            }

            @Override
            public void flush() throws IOException {
                System.out.flush();
            }
        }, true, StandardCharsets.UTF_8);
    }

    public void setExternalContextSupplier(Supplier<String> externalContextSupplier) {
        this.externalContextSupplier = externalContextSupplier == null ? () -> "" : externalContextSupplier;
    }

    public void setSkillRegistry(SkillRegistry skillRegistry) {
        this.skillRegistry = skillRegistry;
    }

    /** 调用方先启动并标记 Turn 为 RUNNING；默认不安装，不涉及终端接线。 */
    public void configurePlanSteeringSafePoint(TurnInputManager manager, String turnId) {
        this.planSteeringSafePoint = new PlanSteeringSafePoint(manager, turnId);
    }

    public void clearPlanSteeringSafePoint() {
        this.planSteeringSafePoint = null;
    }

    public void setConversationLedger(ConversationLedger conversationLedger) {
        this.conversationLedger = conversationLedger == null
                ? ConversationLedger.disabled()
                : conversationLedger;
        this.planner.setConversationLedger(this.conversationLedger);
    }

    ConversationLedger getConversationLedger() {
        return conversationLedger;
    }

    /** Opt-in only; callbacks are captured per executed plan, including each replan. */
    public void setExecutionObserver(PlanExecutionObserver observer) {
        this.executionObserver = observer;
    }

    /** Cumulative sink failures. A benchmark caller must reject incomplete observation evidence. */
    public long getExecutionObservationFailures() {
        return executionObservationFailures.get();
    }

    private final class PlanObservation {
        private final PlanExecutionObserver observer;
        private final String executionId;
        private final long originNanos;
        private final AtomicBoolean failed = new AtomicBoolean();

        private PlanObservation(PlanExecutionObserver observer) {
            this.observer = observer;
            this.executionId = observer == null ? null : UUID.randomUUID().toString();
            this.originNanos = observer == null ? 0 : System.nanoTime();
        }

        private long now() {
            return observer == null ? 0 : System.nanoTime() - originNanos;
        }

        private void emit(Supplier<PlanExecutionObserver.Event> event) {
            if (observer == null || failed.get()) {
                return;
            }
            try {
                observer.onEvent(event.get());
            } catch (RuntimeException e) {
                if (failed.compareAndSet(false, true)) {
                    executionObservationFailures.incrementAndGet();
                    // Do not log exception text: an observer may include sensitive payloads in it.
                    log.warn("Plan execution observer failed; observation is incomplete ({})",
                            e.getClass().getName());
                }
            }
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

    /**
     * 运行任务（自动判断是否需要规划）
     */
    public String run(String userInput) {
        return run(userInput, userInput);
    }

    /** Use submittedUserInput for policy decisions and userInput for expanded task context. */
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

    private String runInternal(String userInput,
                               String submittedUserInput,
                               boolean explicitTaskEnvelope) {
        log.info("Plan run started: inputLength={}", userInput == null ? 0 : userInput.length());
        submittedPolicyInput = submittedUserInput == null ? "" : submittedUserInput;
        turnToolPolicy = policyFor(submittedUserInput, explicitTaskEnvelope);
        conversationLedger.appendMessage(
                "plan", "plan-agent", "user_input", LlmClient.Message.user(userInput));
        StreamState streamState = new StreamState();
        try {
            if (planCancelled()) {
                conversationLedger.appendEvent(
                        "run_cancelled", "plan", "plan-agent", "before_planning", Map.of());
                return CANCELLED_PLAN_MESSAGE;
            }
            PlanRunOutcome outcome = runWithPlan(userInput, streamState, explicitTaskEnvelope);
            if (outcome.persistAssistantMessage() && outcome.result() != null && !outcome.result().isBlank()) {
                conversationLedger.appendMessage(
                        "plan",
                        "plan-agent",
                        "run_result",
                        LlmClient.Message.assistant(outcome.result()));
            }
            if (outcome.autoExtractEligible() && !CancellationContext.isCancelled()) {
                int saved = memoryManager.extractFactsFromUserTurn(submittedUserInput).size();
                if (saved > 0) {
                    out.println("💾 自动提取并保存 " + saved
                            + " 条项目事实（待核实；可用 /memory list 查看和删除）。");
                }
            }
            if (streamState.hasStreamedOutput() && (outcome.result() == null || outcome.result().isBlank())) {
                return "";
            }
            return outcome.result();
        } catch (Exception e) {
            log.error("Plan run failed", e);
            String errorMessage = "❌ 执行失败: " + e.getMessage();
            conversationLedger.appendMessage(
                    "plan",
                    "plan-agent",
                    "run_error",
                    LlmClient.Message.assistant(errorMessage));
            return errorMessage;
        } finally {
            if (planSteeringSafePoint != null) planSteeringSafePoint.stopIfActive();
        }
    }

/**
     * 使用Plan-and-Execute模式执行
     */
    private PlanRunOutcome runWithPlan(String goal,
                                       StreamState streamState,
                                       boolean explicitTaskEnvelope) throws IOException {
        ExecutionPlan plan = planner.createPlan(goal);
        return reviewAndExecutePlan(plan, streamState, explicitTaskEnvelope, 0);
    }

    private PlanRunOutcome reviewAndExecutePlan(ExecutionPlan plan,
                                                StreamState streamState,
                                                boolean explicitTaskEnvelope,
                                                int replansUsed) throws IOException {
        while (true) {
            if (planCancelled()) return PlanRunOutcome.canceled(CANCELLED_PLAN_MESSAGE);
            PlanReviewDecision decision = reviewHandler.review(plan.getGoal(), plan);
            if (planCancelled()) return PlanRunOutcome.canceled(CANCELLED_PLAN_MESSAGE);
            if (decision == null || decision.action() == PlanReviewAction.EXECUTE) {
                return PlanRunOutcome.executed(executePlan(plan, streamState, explicitTaskEnvelope, replansUsed));
            }

            if (decision.action() == PlanReviewAction.CANCEL) {
                return PlanRunOutcome.canceled("⏹️ 已取消本次计划执行。");
            }

            String feedback = decision.feedback() == null ? "" : decision.feedback().trim();
            if (feedback.isEmpty()) {
                return PlanRunOutcome.executed(executePlan(plan, streamState, explicitTaskEnvelope, replansUsed));
            }

            out.println("📝 已收到补充要求，正在修改计划...\n");
            submittedPolicyInput = submittedPolicyInput + "\n补充要求：" + feedback;
            turnToolPolicy = policyFor(submittedPolicyInput, explicitTaskEnvelope);
            if (planSteeringSafePoint != null) {
                turnToolPolicy.tightenWithUserInput(planSteeringSafePoint.includedFeedback());
            }
            plan = planner.revisePlan(plan, feedback);
        }
    }

    private TurnToolPolicy policyFor(String submittedUserInput, boolean explicitTaskEnvelope) {
        return explicitTaskEnvelope
                ? TurnToolPolicy.forExplicitTask(
                        submittedUserInput,
                        toolRegistry.isSharedBrowserSession(),
                        toolRegistry.hasAgentOwnedCurrentBrowserPage())
                : TurnToolPolicy.fromUserInput(
                        submittedUserInput,
                        toolRegistry.isSharedBrowserSession(),
                        toolRegistry.hasAgentOwnedCurrentBrowserPage());
    }

    private String executePlan(ExecutionPlan plan,
                               StreamState streamState,
                               boolean explicitTaskEnvelope,
                               int replansUsed) throws IOException {
        log.info("Executing plan: goal='{}', taskCount={}", plan.getGoal(), plan.getAllTasks().size());
        out.println("🚀 开始执行计划...\n");

        plan.markStarted();
        PlanObservation observation = new PlanObservation(executionObserver);
        long planStart = observation.now();
        observation.emit(() -> new PlanExecutionObserver.PlanStarted(
                observation.executionId, planStart, plan.getId(),
                PlanExecutionObserver.TextFingerprint.of(plan.getGoal()),
                plan.getAllTasks().stream().map(task -> new PlanExecutionObserver.TaskNode(
                        task.getId(), task.getType(),
                        PlanExecutionObserver.TextFingerprint.of(task.getDescription()),
                        task.getDependencies())).toList(), plan.getExecutionOrder()));
        List<String> failureLines = new ArrayList<>();
        Map<String, Boolean> streamedTaskOutputs = new HashMap<>();
        Map<String, TurnToolPolicy.TrustedUrlContext> taskTrustedUrls = new HashMap<>();
        boolean stopAfterBatch = false;

        while (true) {
            if (planCancelled()) {
                plan.markCancelled();
                return CANCELLED_PLAN_MESSAGE;
            }
            List<Task> executableTasks = getExecutableTasksInOrder(plan);
            if (stopAfterBatch || executableTasks.isEmpty()) {
                String steeringStop = processPlanSteering(plan);
                if (steeringStop != null) return steeringStop;
                if (!stopAfterBatch && !getExecutableTasksInOrder(plan).isEmpty()) continue;
                if (planSteeringSafePoint != null) {
                    var decision = planSteeringSafePoint.finalizeOrContinue();
                    if (decision == PlanSteeringSafePoint.FinalizationDecision.CONTINUE) continue;
                    if (decision == PlanSteeringSafePoint.FinalizationDecision.CANCELLED) {
                        plan.markCancelled();
                        return CANCELLED_PLAN_MESSAGE;
                    }
                    planSteeringSafePoint.completeTurn();
                }
                break;
            }

            List<TaskExecutionResult> batchResults = executeTaskBatch(
                    plan, executableTasks, streamState, taskTrustedUrls, observation);
            // 取消会打断本轮正在执行的任务，这些中断不是任务本身失败，不能再触发重新规划
            if (CancellationContext.isCancelled()) {
                plan.markCancelled();
                return CANCELLED_PLAN_MESSAGE;
            }
            String replanReason = null;
            List<Task> failedTasks = new ArrayList<>();
            for (TaskExecutionResult batchResult : batchResults) {
                Task task = batchResult.task();

                if (!batchResult.failed()) {
                    task.markCompleted(batchResult.result());
                    taskTrustedUrls.put(task.getId(), batchResult.trustedUrls());
                    streamedTaskOutputs.put(task.getId(), batchResult.streamedOutput());
                    log.info("Task completed: {} status={} resultChars={}",
                            task.getId(), task.getStatus(), batchResult.result() == null ? 0 : batchResult.result().length());
                    if (batchResult.streamedOutput() || batchResult.result() == null || batchResult.result().isBlank()) {
                        out.println("✅ 完成 [" + task.getId() + "]\n");
                    } else {
                        out.println("✅ 完成 [" + task.getId() + "]: "
                                + batchResult.result().substring(0, Math.min(100, batchResult.result().length())) + "\n");
                    }
                    continue;
                }

                String errorMessage = failureMessage(batchResult.error());
                task.markFailed(errorMessage);
                log.warn("Task failed: {} error={}", task.getId(), errorMessage);
                out.println("❌ 失败 [" + task.getId() + "]: " + errorMessage + "\n");

                failureLines.add("任务 " + task.getId() + " 失败: " + errorMessage);
                failedTasks.add(task);

                if (!stopAfterBatch && replanReason == null && plan.getProgress() < 0.5) {
                    int maxReplans = maxReplans();
                    if (replansUsed < maxReplans) {
                        // 保留原来触发重规划的时机，但必须先写回整批结果。
                        replanReason = errorMessage;
                        continue;
                    }
                    // 与重规划触发点保持一致：不再启动任何新任务，只收完本批已返回的结果
                    String stopNotice = "已达到重新规划上限（" + maxReplans + " 次），停止执行剩余任务";
                    out.println("🛑 " + stopNotice + "。\n");
                    failureLines.add(stopNotice);
                    stopAfterBatch = true;
                    continue;
                }
            }
            if (stopAfterBatch) {
                for (Task skipped : plan.skipAllPending()) {
                    failureLines.add("任务 " + skipped.getId() + " 已跳过: 已达到重新规划上限");
                }
            } else if (replanReason == null) {
                for (Task task : failedTasks) {
                    for (Task skipped : plan.skipDependentsOf(task.getId())) {
                        failureLines.add("任务 " + skipped.getId() + " 已跳过: 依赖的任务 " + task.getId() + " 失败");
                    }
                }
            }
            String steeringStop = processPlanSteering(plan);
            if (steeringStop != null) return steeringStop;
            if (replanReason != null) {
                out.println("🔄 尝试重新规划（第 " + (replansUsed + 1) + "/" + maxReplans() + " 次）...\n");
                if (planSteeringSafePoint != null && !planSteeringSafePoint.includedFeedback().isBlank()) {
                    replanReason += "\n用户在执行中补充的要求（重规划仍须遵守）：\n"
                            + planSteeringSafePoint.includedFeedback();
                }
                ExecutionPlan replanned = planner.replan(plan, replanReason);
                return reviewAndExecutePlan(replanned, streamState, explicitTaskEnvelope, replansUsed + 1).result();
            }
        }

        if (!plan.isAllCompleted() && !plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划未能继续推进，存在未满足依赖的任务。";
        }

        if (plan.hasFailed()) {
            plan.markFailed();
            return "⚠️ 计划部分完成，有任务失败。\n" + buildPartialResult(plan, streamedTaskOutputs, failureLines);
        }

        String planSummary = buildFinalResult(plan, streamedTaskOutputs);

        plan.markCompleted();
        if (planSummary.isBlank()) {
            return "✅ 计划执行完成！";
        }
        return "✅ 计划执行完成！\n" + planSummary;
    }

    private boolean planCancelled() {
        return CancellationContext.isCancelled()
                || (planSteeringSafePoint != null && !planSteeringSafePoint.isRunning());
    }

    /** 只在所有批次结果写回后或最终收尾前调用；返回非空表示不得再启动任务。 */
    private String processPlanSteering(ExecutionPlan plan) {
        if (planSteeringSafePoint == null) return null;
        while (true) {
            var prepared = planSteeringSafePoint.reserve();
            if (!prepared.proceed() || CancellationContext.isCancelled()) {
                if (prepared.delivery() != null) planSteeringSafePoint.requeue(prepared);
                plan.markCancelled();
                return CANCELLED_PLAN_MESSAGE;
            }
            if (prepared.delivery() == null) return null;
            if (planCancelled()) {
                planSteeringSafePoint.requeue(prepared);
                plan.markCancelled();
                return CANCELLED_PLAN_MESSAGE;
            }
            turnToolPolicy.tightenWithUserInput(prepared.feedback());
            List<ExecutionPlan.PendingTaskRevision> revisions;
            try {
                revisions = planner.revisePendingTasks(plan, prepared.feedback());
            } catch (Planner.RevisionResponseException e) {
                planSteeringSafePoint.commit(prepared, conversationLedger);
                plan.markFailed();
                return "⚠️ 计划修订未应用，已停止后续任务：" + failureMessage(e);
            } catch (IOException | RuntimeException e) {
                planSteeringSafePoint.uncertain(prepared);
                conversationLedger.appendEvent("steering_delivery_uncertain", "plan", "plan-agent",
                        "runtime_steer", Map.of("requestId", prepared.requestId(), "feedback", prepared.feedback()));
                plan.markFailed();
                return "⚠️ 无法确认补充要求是否送达模型，已停止后续任务，不自动重发：" + failureMessage(e);
            }
            if (planCancelled()) {
                planSteeringSafePoint.commit(prepared, conversationLedger);
                plan.markCancelled();
                return CANCELLED_PLAN_MESSAGE;
            }
            try {
                if (!revisions.isEmpty()) plan.revisePendingTasks(revisions);
            } catch (RuntimeException e) {
                planSteeringSafePoint.commit(prepared, conversationLedger);
                plan.markFailed();
                return "⚠️ 计划修订未应用，已保留原 DAG 并停止后续任务：" + failureMessage(e);
            }
            planSteeringSafePoint.commit(prepared, conversationLedger);
        }
    }

    private List<Task> getExecutableTasksInOrder(ExecutionPlan plan) {
        Set<String> executableIds = plan.getExecutableTasks().stream()
                .map(Task::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        return plan.getExecutionOrder().stream()
                .filter(executableIds::contains)
                .map(plan::getTask)
                .toList();
    }

    private List<TaskExecutionResult> executeTaskBatch(ExecutionPlan plan, List<Task> executableTasks,
                                                       StreamState streamState,
                                                       Map<String, TurnToolPolicy.TrustedUrlContext> taskTrustedUrls,
                                                       PlanObservation observation) {
        if (executableTasks.size() == 1) {
            Task task = executableTasks.get(0);
            log.info("Executing single task: {} type={}", task.getId(), task.getType());
            out.println("▶️ 执行任务 [" + task.getId() + "]: " + task.getDescription());
            task.markStarted();

            try {
                return List.of(TaskExecutionResult.success(task, executeTask(
                        plan.getGoal(), plan, task, streamState, out, taskTrustedUrls, observation)));
            } catch (Exception e) {
                return List.of(TaskExecutionResult.failure(task, e));
            }
        }

        String parallelTaskIds = executableTasks.stream()
                .map(Task::getId)
                .collect(Collectors.joining(", "));
        log.info("Executing parallel batch: {}", parallelTaskIds);
        out.println("⚡ 本轮并行执行 " + executableTasks.size() + " 个任务: " + parallelTaskIds);

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(executableTasks.size(), 4), r -> {
            Thread t = new Thread(r, "haocli-plan-executor");
            t.setDaemon(true);
            return t;
        });
        try {
            Map<String, ByteArrayOutputStream> buffers = new LinkedHashMap<>();
            List<Future<TaskExecutionResult>> futures = new ArrayList<>();
            for (Task task : executableTasks) {
                out.println("▶️ 并行任务 [" + task.getId() + "]: " + task.getDescription());
                task.markStarted();
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                buffers.put(task.getId(), baos);
                PrintStream taskOut = new PrintStream(baos, true, StandardCharsets.UTF_8);
                futures.add(executor.submit(() -> {
                    try {
                        return TaskExecutionResult.success(task, executeTask(
                                plan.getGoal(), plan, task, streamState, taskOut, taskTrustedUrls, observation));
                    } catch (Exception e) {
                        return TaskExecutionResult.failure(task, e);
                    }
                }));
            }

            List<TaskExecutionResult> results = new ArrayList<>();
            for (Future<TaskExecutionResult> future : futures) {
                try {
                    results.add(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), e));
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    Exception error = cause instanceof Exception exception
                            ? exception
                            : new RuntimeException(cause);
                    results.add(TaskExecutionResult.failure(executableTasks.get(results.size()), error));
                }
            }

            // 按任务顺序 flush 各缓冲区到 stdout，避免并行输出交错
            for (Task task : executableTasks) {
                ByteArrayOutputStream buf = buffers.get(task.getId());
                if (buf != null && buf.size() > 0) {
                    out.print(buf.toString(StandardCharsets.UTF_8));
                    out.flush();
                }
            }

            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 执行单个任务（支持多轮工具调用）
     */
    private TaskRunResult executeTask(String goal, ExecutionPlan plan, Task task,
                                      StreamState streamState, PrintStream out,
                                      Map<String, TurnToolPolicy.TrustedUrlContext> taskTrustedUrls,
                                      PlanObservation observation) throws IOException {
        long entered = observation.now();
        observation.emit(() -> new PlanExecutionObserver.TaskEntered(
                observation.executionId, entered, task.getId(), Thread.currentThread().getId()));
        try {
            TaskRunResult result = executeObservedTask(
                    goal, plan, task, streamState, out, taskTrustedUrls, observation);
            long exited = observation.now();
            observation.emit(() -> new PlanExecutionObserver.TaskExited(
                    observation.executionId, exited, task.getId(), PlanExecutionObserver.ExitKind.RETURNED,
                    PlanExecutionObserver.TextFingerprint.of(result.result()), null));
            return result;
        } catch (IOException | RuntimeException | Error e) {
            long exited = observation.now();
            observation.emit(() -> new PlanExecutionObserver.TaskExited(
                    observation.executionId, exited, task.getId(), PlanExecutionObserver.ExitKind.THREW,
                    PlanExecutionObserver.TextFingerprint.of(null), e.getClass().getName()));
            throw e;
        }
    }

    private TaskRunResult executeObservedTask(String goal, ExecutionPlan plan, Task task,
                                              StreamState streamState, PrintStream out,
                                              Map<String, TurnToolPolicy.TrustedUrlContext> taskTrustedUrls,
                                              PlanObservation observation) throws IOException {
        List<TurnToolPolicy.TrustedUrlContext> dependencyUrls = task.getDependencies().stream()
                .map(taskTrustedUrls::get)
                .filter(Objects::nonNull)
                .toList();
        TurnToolPolicy taskToolPolicy = turnToolPolicy.forkWithTrustedUrls(dependencyUrls);
        try {
            return executeTaskWithPolicy(
                    goal, plan, task, streamState, out, dependencyUrls, taskToolPolicy, observation);
        } finally {
            taskToolPolicy.releaseBrowserLease();
        }
    }

    private TaskRunResult executeTaskWithPolicy(
            String goal, ExecutionPlan plan, Task task, StreamState streamState, PrintStream out,
            List<TurnToolPolicy.TrustedUrlContext> dependencyUrls,
            TurnToolPolicy taskToolPolicy, PlanObservation observation) throws IOException {
        String prompt = promptAssembler.assemble(PromptMode.PLAN, PromptContext.builder()
                .projectMemoryContext(buildProjectMemoryContext())
                .variable("taskType", task.getType())
                .variable("taskDescription", task.getDescription())
                .externalContext(buildExternalContext())
                .skillIndex(buildSkillIndex())
                .toolsEnabled(llmClient == null || llmClient.supportsTools())
                .build());

        // 注入长期记忆上下文
        String memoryContext = memoryManager.buildContextForQuery(
                task.getDescription(),
                memoryManager.getContextProfile().memoryContextTokens());
        String taskInput = buildTaskContext(goal, plan, task, dependencyUrls);
        if (!memoryContext.isEmpty()) {
            taskInput = taskInput + "\n\n" + memoryContext;
        }

        String actor = "task:" + task.getId();
        // 每个任务节点一份独立对话，经统一内核 AgentLoop 执行（压缩、截断保护、预算、取消和 ReAct 同一套）。
        ConversationContext conversation = new ConversationContext("plan", actor, autoCompactionManager, () -> 0L);
        conversation.attachLedger(conversationLedger);
        conversation.append(LlmClient.Message.system(prompt), "system_prompt");
        LlmClient.Message inputMessage = ImageReferenceParser.userMessage(
                taskInput, Path.of(toolRegistry.getProjectPath()));
        conversation.append(inputMessage, "task_input");
        long inputPrepared = observation.now();
        observation.emit(() -> new PlanExecutionObserver.TaskInputPrepared(
                observation.executionId, inputPrepared, task.getId(),
                PlanExecutionObserver.TextFingerprint.of(inputMessage.content()), inputMessage.imagePartCount(),
                task.getDependencies().stream().map(plan::getTask).filter(Objects::nonNull)
                        .map(dep -> new PlanExecutionObserver.DependencyInput(dep.getId(), dep.getStatus(),
                                PlanExecutionObserver.TextFingerprint.of(dep.getResult()))).toList()));

        TaskStreamRenderer streamRenderer = new TaskStreamRenderer(task.getId(), streamState, out);
        TaskRunEvents events = new TaskRunEvents(task.getId(), streamRenderer, out, observation);
        HarnessFeatures features = HarnessFeatures.fromSystem();
        AgentLoop loop = new AgentLoop(toolRegistry, conversation, features,
                DefaultTurnHooks.withLeading(() -> List.of(events.hook()), features, toolRegistry));
        AgentLoop.Turn turn = new AgentLoop.Turn(llmClient, taskToolPolicy, null, new TaskHost(),
                () -> memoryManager.getContextProfile().compressionTriggerTokens(), events, RunLimits.UNLIMITED);
        AgentLoop.Result result;
        try {
            result = loop.run(turn);
        } finally {
            streamRenderer.finish();
        }
        String toolOnly = events.toolResultsText();
        String text = switch (result.status()) {
            case COMPLETED -> {
                String content = result.answer();
                // 模型只调了工具、最后没写正文：沿用迁移前的做法，把工具结果当作任务结果
                yield (content == null || content.isBlank()) && !toolOnly.isEmpty() ? toolOnly : content;
            }
            case PARTIAL -> {
                String partial = result.answer();
                yield partial != null && !partial.contains("\n\n") && !toolOnly.isEmpty()
                        ? partial + "\n\n" + toolOnly : partial;
            }
            case CANCELLED -> "⏹️ 已取消任务 [" + task.getId() + "]。";
            case FAILED -> {
                // 保留原始异常：任务失败、重规划和评测审计都按迁移前的异常路径处理
                if (result.failure() != null) throw result.failure();
                throw new IOException(result.answer());
            }
        };
        return TaskRunResult.of(text, streamRenderer.hasStreamedOutput(), taskToolPolicy);
    }

    /** Plan 任务的流式输出由 TaskStreamRenderer 显示；结束时记录 Token。 */
    private final class TaskHost implements AgentLoop.Host {
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
            memoryManager.recordTokenUsage(
                    budget.totalInputTokens(),
                    budget.totalOutputTokens(),
                    budget.totalCachedInputTokens());
        }

        @Override
        public void afterFinalAnswer() {
        }
    }

    /**
     * 内核事件 → Plan 任务的终端显示和执行观察（评测审计用）。观察模块排在默认模块最前面：
     * 在 Skill 注入改写之前拿到原始工具结果，和迁移前一样。一个任务一个实例。
     */
    private final class TaskRunEvents implements com.haocli.agent.event.AgentEventListener {
        private final String taskId;
        private final TaskStreamRenderer renderer;
        private final PrintStream out;
        private final PlanObservation observation;
        private final List<LlmClient.ToolCall> visibleCalls = new ArrayList<>();
        private final StringBuilder toolResults = new StringBuilder();
        private int iterations;
        private boolean batchOpen;

        private TaskRunEvents(String taskId, TaskStreamRenderer renderer, PrintStream out,
                              PlanObservation observation) {
            this.taskId = taskId;
            this.renderer = renderer;
            this.out = out;
            this.observation = observation;
        }

        String toolResultsText() {
            return toolResults.toString().trim();
        }

        TurnHook hook() {
            return new TurnHook() {
                @Override
                public void beforeModelRequest(Context context) {
                    iterations++;
                }

                @Override
                public List<ToolExecutionResult> beforeAppendToolResults(List<ToolExecutionResult> results,
                                                                         Context context) {
                    int iteration = iterations;
                    long returned = observation.now();
                    observation.emit(() -> {
                        List<PlanExecutionObserver.ToolResult> snapshots = new ArrayList<>();
                        for (int i = 0; i < results.size(); i++) {
                            ToolExecutionResult result = results.get(i);
                            snapshots.add(new PlanExecutionObserver.ToolResult(i, result.id(), result.name(),
                                    PlanExecutionObserver.TextFingerprint.of(result.argumentsJson()),
                                    PlanExecutionObserver.TextFingerprint.of(result.result()), result.successful(),
                                    result.timedOut(), result.imageParts().size()));
                        }
                        return new PlanExecutionObserver.ToolBatchReturned(
                                observation.executionId, returned, taskId, iteration, snapshots);
                    });
                    return results;
                }

                @Override
                public void afterToolResults(List<ToolExecutionResult> results, Context context) {
                    for (ToolExecutionResult result : results) {
                        toolResults.append(result.result()).append("\n");
                    }
                }
            };
        }

        @Override
        public void onEvent(com.haocli.agent.event.AgentEvent event) {
            if (event instanceof com.haocli.agent.event.AgentEvent.ReasoningDelta delta) {
                renderer.onReasoningDelta(delta.text());
            } else if (event instanceof com.haocli.agent.event.AgentEvent.TextDelta delta) {
                renderer.onContentDelta(delta.text());
            } else if (event instanceof com.haocli.agent.event.AgentEvent.ToolCallStarted started) {
                if (!batchOpen) {
                    // 工具执行前收尾流式显示，避免人工确认提示“跨过”未换行的文字
                    renderer.resetBetweenIterations();
                    visibleCalls.clear();
                    batchOpen = true;
                }
                if (started.allowedByTurnPolicy()) {
                    visibleCalls.add(new LlmClient.ToolCall(started.id(),
                            new LlmClient.ToolCall.Function(started.name(), started.argumentsJson())));
                }
                if (started.lastInBatch()) {
                    printToolCalls(out, visibleCalls);
                    batchOpen = false;
                }
            } else if (event instanceof com.haocli.agent.event.AgentEvent.Notice notice && !notice.attachedToAnswer()) {
                out.println(AnsiStyle.subtle("  [" + taskId + "] " + notice.text()));
            }
        }
    }

    private String formatPartialResult(String description, String content) {
        String heading = "⚠️ 部分完成（" + description + "）";
        return content == null || content.isBlank() ? heading : heading + "\n\n" + content;
    }

    private String buildExternalContext() {
        if (!memoryManager.getContextProfile().mcpResourceIndexEnabled()) {
            return "";
        }
        try {
            String context = externalContextSupplier.get();
            return context == null ? "" : context.trim();
        } catch (Exception e) {
            log.warn("Failed to build external context for plan task", e);
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

    private String preview(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength) + "...";
    }

    private void appendTaskMessage(List<LlmClient.Message> messages, String actor,
                                   String source, LlmClient.Message message) {
        messages.add(message);
        conversationLedger.appendMessage("plan", actor, source, message);
    }

    private static void printToolCalls(PrintStream out, List<LlmClient.ToolCall> toolCalls) {
        Map<String, List<LlmClient.ToolCall>> grouped = new LinkedHashMap<>();
        for (LlmClient.ToolCall tc : toolCalls) {
            grouped.computeIfAbsent(tc.function().name(), k -> new ArrayList<>()).add(tc);
        }
        for (var group : grouped.entrySet()) {
            String toolName = group.getKey();
            List<LlmClient.ToolCall> calls = group.getValue();
            out.println(AnsiStyle.subtle("  " + toolLabel(toolName, calls.size())));
            for (LlmClient.ToolCall tc : calls) {
                String detail = extractKeyParam(toolName, tc.function().arguments());
                if (!detail.isEmpty()) {
                    out.println(AnsiStyle.subtle("    └ " + detail));
                }
            }
        }
    }

    private static String toolLabel(String toolName, int count) {
        return switch (toolName) {
            case "read_file" -> "📖 读取 " + count + " 个文件";
            case "write_file" -> "✏️ 写入 " + count + " 个文件";
            case "edit_file" -> "✏️ 编辑 " + count + " 个文件";
            case "list_dir" -> "📂 列出 " + count + " 个目录";
            case "execute_command" -> "⚡ 执行 " + count + " 条命令";
            case "create_project" -> "🏗️ 创建 " + count + " 个项目";
            case "search_code" -> "🔍 搜索代码 " + count + " 次";
            case "web_search" -> "🌐 联网搜索 " + count + " 次";
            case "web_fetch" -> "📰 抓取 " + count + " 个网页";
            case "save_memory" -> "💾 保存长期记忆 " + count + " 条";
            default -> toolName != null && toolName.startsWith("mcp__")
                    ? formatMcpLabel(toolName, count)
                    : "🔧 " + toolName + " × " + count;
        };
    }

    private static String formatMcpLabel(String toolName, int count) {
        String[] parts = toolName.split("__", 3);
        String display = parts.length == 3 ? parts[1] + "." + parts[2] : toolName;
        return count == 1
                ? "🔌 调用 MCP 工具 " + display
                : "🔌 调用 MCP 工具 " + display + " × " + count;
    }

    private static String extractKeyParam(String toolName, String argsJson) {
        try {
            JsonNode node = JSON_MAPPER.readTree(argsJson);
            String key = switch (toolName) {
                case "read_file", "write_file", "edit_file", "list_dir" -> "path";
                case "execute_command" -> "command";
                case "create_project" -> "name";
                case "search_code", "web_search" -> "query";
                case "web_fetch" -> "url";
                case "save_memory" -> "fact";
                default -> null;
            };
            if (key == null) {
                return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
            }
            String value = node.path(key).asText("");
            if (value.length() > 80) {
                value = value.substring(0, 77) + "...";
            }
            return value;
        } catch (Exception e) {
            return argsJson.length() > 80 ? argsJson.substring(0, 77) + "..." : argsJson;
        }
    }

    private static final class StreamState {
        private volatile boolean streamedOutput;

        private void markStreamed() {
            this.streamedOutput = true;
        }

        private boolean hasStreamedOutput() {
            return streamedOutput;
        }
    }

    private static final class TaskStreamRenderer implements LlmClient.StreamListener {
        private final String taskId;
        private final StreamState streamState;
        private final PrintStream out;
        private final StringBuilder pendingReasoning = new StringBuilder();
        private final StringBuilder lateReasoning = new StringBuilder();
        private TerminalMarkdownRenderer reasoningRenderer;
        private TerminalMarkdownRenderer contentRenderer;
        private boolean reasoningStarted;
        private boolean contentStarted;
        private boolean streamedOutput;

        private TaskStreamRenderer(String taskId, StreamState streamState, PrintStream out) {
            this.taskId = taskId;
            this.streamState = streamState;
            this.out = out;
        }

        @Override
        public synchronized void onReasoningDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (contentStarted) {
                lateReasoning.append(delta);
                return;
            }
            if (!reasoningStarted) {
                pendingReasoning.append(delta);
                if (pendingReasoning.toString().isBlank()) {
                    return;
                }
                out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                reasoningRenderer = new TerminalMarkdownRenderer(out);
                reasoningRenderer.append(pendingReasoning.toString());
                pendingReasoning.setLength(0);
                reasoningStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            } else {
                reasoningRenderer.append(delta);
            }
            out.flush();
        }

        @Override
        public synchronized void onContentDelta(String delta) {
            if (delta == null || delta.isEmpty()) {
                return;
            }
            if (!contentStarted) {
                if (reasoningStarted && reasoningRenderer != null) {
                    reasoningRenderer.finish();
                    out.println();
                } else if (pendingReasoning.length() > 0 && !pendingReasoning.toString().isBlank()) {
                    out.println(AnsiStyle.heading("🧠 任务思考 [" + taskId + "]"));
                    TerminalMarkdownRenderer r = new TerminalMarkdownRenderer(out);
                    r.append(pendingReasoning.toString());
                    r.finish();
                    out.println();
                    pendingReasoning.setLength(0);
                    reasoningStarted = true;
                }
                // content 可能只是 tool-call 前的叙述，也可能是最终回答，用"输出"避免误导。
                out.println(AnsiStyle.section("🤖 任务输出 [" + taskId + "]"));
                contentRenderer = new TerminalMarkdownRenderer(out);
                contentStarted = true;
                streamedOutput = true;
                streamState.markStreamed();
            }
            contentRenderer.append(delta);
            out.flush();
        }

        private synchronized void finish() {
            if (streamedOutput) {
                if (reasoningRenderer != null) {
                    reasoningRenderer.finish();
                }
                if (contentRenderer != null) {
                    contentRenderer.finish();
                }
                flushLateReasoning();
                out.println("\n");
            }
        }

        /**
         * 两次 iteration 之间（通常是一次 tool-call 分支完成后）调用：收尾当前渲染器并重置状态，
         * 让下一轮迭代能重新打印 🧠 / 🤖 标题，避免标题和内容被 HITL / 工具执行中断而错位。
         */
        private synchronized void resetBetweenIterations() {
            if (reasoningRenderer != null) {
                reasoningRenderer.finish();
                reasoningRenderer = null;
            }
            if (contentRenderer != null) {
                contentRenderer.finish();
                contentRenderer = null;
            }
            flushLateReasoning();
            pendingReasoning.setLength(0);
            reasoningStarted = false;
            contentStarted = false;
            if (streamedOutput) {
                out.println();
            }
        }

        private synchronized boolean hasStreamedOutput() {
            return streamedOutput;
        }

        private void flushLateReasoning() {
            String late = lateReasoning.toString().trim();
            if (late.isEmpty()) {
                lateReasoning.setLength(0);
                return;
            }
            out.println();
            out.println(AnsiStyle.heading("🧠 补充思考 [" + taskId + "]"));
            TerminalMarkdownRenderer renderer = new TerminalMarkdownRenderer(out);
            renderer.append(late);
            renderer.finish();
            lateReasoning.setLength(0);
        }
    }

    private String buildTaskContext(String goal, ExecutionPlan plan, Task task,
                                    List<TurnToolPolicy.TrustedUrlContext> dependencyUrls) {
        StringBuilder context = new StringBuilder();
        context.append("总目标：").append(goal).append("\n");
        context.append("当前任务：").append(task.getDescription()).append("\n");

        if (task.getDependencies().isEmpty()) {
            context.append("依赖任务：无\n");
        } else {
            context.append("依赖任务结果：\n");
            for (String depId : task.getDependencies()) {
                Task dep = plan.getTask(depId);
                if (dep == null) {
                    continue;
                }
                context.append("- ").append(dep.getId())
                        .append(" / ").append(dep.getDescription())
                        .append(" / 状态=").append(dep.getStatus())
                        .append("\n");
                if (dep.getResult() != null && !dep.getResult().isBlank()) {
                    context.append(dep.getResult()).append("\n");
                }
            }
        }

        Set<String> trustedDependencyUrls = dependencyUrls.stream()
                .flatMap(contextItem -> contextItem.urls().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!trustedDependencyUrls.isEmpty()) {
            context.append("依赖分支经 web_search 验证的 URL（可供当前任务抓取/导航）：\n");
            trustedDependencyUrls.forEach(url -> context.append("- ").append(url).append("\n"));
        }

        context.append("请执行此任务。如果是ANALYSIS或VERIFICATION类型，请基于以上上下文直接给出结果。");
        return context.toString();
    }

    /**
     * 有任务失败时的汇总：先列出已完成任务的结果（已流式展示过的不重复），
     * 再列失败和被跳过的任务，避免用户只看到失败信息而丢掉已经完成的工作。
     */
    private String buildPartialResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs,
                                      List<String> failureLines) {
        List<String> completed = plan.getExecutionOrder().stream()
                .map(plan::getTask)
                .filter(task -> task != null && task.getStatus() == Task.TaskStatus.COMPLETED)
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .map(task -> "[" + task.getId() + "] " + task.getResult())
                .toList();
        StringBuilder summary = new StringBuilder();
        if (!completed.isEmpty()) {
            summary.append("已完成的任务结果:\n").append(String.join("\n", completed)).append("\n");
        }
        summary.append(String.join("\n", failureLines));
        return summary.toString();
    }

    /** 异常消息可能为空（比如线程中断），失败原因至少要写出异常类型。 */
    static String failureMessage(Exception error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    static int maxReplans() {
        String configured = System.getProperty(MAX_REPLANS_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configured = System.getenv(MAX_REPLANS_ENV);
        }
        if (configured == null || configured.isBlank()) {
            return DEFAULT_MAX_REPLANS;
        }
        try {
            return Math.max(0, Integer.parseInt(configured.trim()));
        } catch (NumberFormatException e) {
            log.warn("忽略无效的 {}={}，使用默认值 {}", MAX_REPLANS_PROPERTY, configured, DEFAULT_MAX_REPLANS);
            return DEFAULT_MAX_REPLANS;
        }
    }

    private String buildFinalResult(ExecutionPlan plan, Map<String, Boolean> streamedTaskOutputs) {
        StringBuilder result = new StringBuilder();
        List<Task> leafTasks = plan.getAllTasks().stream()
                .filter(task -> task.getDependents().isEmpty())
                .toList();

        for (Task task : leafTasks) {
            if (Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId()))) {
                continue;
            }
            if (task.getResult() == null || task.getResult().isBlank()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append("\n");
            }
            result.append("[").append(task.getId()).append("] ").append(task.getResult());
        }

        if (!result.isEmpty()) {
            return result.toString();
        }

        return plan.getAllTasks().stream()
                .filter(task -> !Boolean.TRUE.equals(streamedTaskOutputs.get(task.getId())))
                .filter(task -> task.getResult() != null && !task.getResult().isBlank())
                .reduce((first, second) -> second)
                .map(Task::getResult)
                .orElse("");
    }

}
