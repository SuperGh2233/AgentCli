package com.haocli.headless;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.haocli.agent.Agent;
import com.haocli.agent.event.AgentEvent;
import com.haocli.history.ConversationLedger;
import com.haocli.llm.LlmClient;
import com.haocli.runtime.CancellationContext;
import com.haocli.runtime.CancellationToken;
import com.haocli.tool.CommandSandboxMode;
import com.haocli.tool.CommandSandboxStatus;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 跑一次无界面任务：组装只读或可写的工具注册表和静默渲染器，订阅 Agent 事件，按输出格式写到标准输出。
 * 诊断信息（沙箱提示、意外错误）写到标准错误，标准输出只放约定格式的内容。
 */
public final class HeadlessRunner {

    public static final int EXIT_COMPLETED = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_PARTIAL = 3;
    public static final int EXIT_CANCELLED = 130;

    private HeadlessRunner() {
    }

    public static int run(HeadlessOptions options, LlmClient llm, Path projectDir, ConversationLedger ledger,
                          PrintStream out, PrintStream err) {
        HeadlessToolRegistry registry = new HeadlessToolRegistry(options.permissionMode(), options.allowedCommands());
        registry.setProjectPath(projectDir.toAbsolutePath().normalize().toString());
        CommandSandboxStatus sandbox = registry.configureCommandSandbox(
                CommandSandboxMode.fromConfiguration(), Path.of(registry.getProjectPath()));
        if (sandbox != null && sandbox.message() != null && !sandbox.message().isBlank()) {
            err.println(sandbox.message());
        }

        Agent agent = new Agent(llm, registry);
        agent.setRenderer(new SilentRenderer());
        agent.setConversationLedger(ledger);
        agent.setRunLimits(options.limits());
        AtomicReference<AgentEvent.TurnFinished> finished = new AtomicReference<>();
        HarnessMetrics.Collector metrics = new HarnessMetrics.Collector();
        boolean streamEvents = options.outputFormat() == HeadlessOptions.OutputFormat.STREAM_JSON;
        agent.setEventListener(event -> {
            metrics.onEvent(event);
            if (event instanceof AgentEvent.TurnFinished turnFinished) {
                finished.set(turnFinished);
            }
            if (streamEvents) {
                out.println(AgentEventJson.toJson(event).toString());
                out.flush();
            }
        });

        CancellationToken token = CancellationContext.startRun();
        try {
            agent.run(options.prompt());
        } catch (RuntimeException e) {
            err.println("❌ 执行失败: " + e.getMessage());
            writeError(options, out, "执行失败: " + e.getMessage(), ledger, llm);
            return EXIT_FAILED;
        } finally {
            CancellationContext.clear(token);
        }

        AgentEvent.TurnFinished result = finished.get();
        if (result == null) {
            writeError(options, out, "没有拿到执行结果", ledger, llm);
            return EXIT_FAILED;
        }
        switch (options.outputFormat()) {
            case TEXT -> {
                if (result.status() == AgentEvent.TurnStatus.COMPLETED || result.status() == AgentEvent.TurnStatus.PARTIAL) {
                    out.println(result.answer() == null ? "" : result.answer());
                } else {
                    err.println(result.answer());
                }
            }
            case JSON, STREAM_JSON -> out.println(resultJson(result, ledger, llm, metrics.snapshot()).toString());
        }
        out.flush();
        return exitCode(result.status());
    }

    static int exitCode(AgentEvent.TurnStatus status) {
        return switch (status) {
            case COMPLETED -> EXIT_COMPLETED;
            case PARTIAL -> EXIT_PARTIAL;
            case CANCELLED -> EXIT_CANCELLED;
            case FAILED -> EXIT_FAILED;
        };
    }

    /** json 模式的整体结果，也是 stream-json 的最后一行。 */
    static ObjectNode resultJson(AgentEvent.TurnFinished result, ConversationLedger ledger, LlmClient llm,
                                 HarnessMetrics metrics) {
        ObjectNode node = AgentEventJson.MAPPER.createObjectNode();
        node.put("type", "result");
        node.put("status", AgentEventJson.status(result.status()));
        node.put("is_error", result.status() == AgentEvent.TurnStatus.FAILED
                || result.status() == AgentEvent.TurnStatus.CANCELLED);
        node.put("result", result.answer());
        node.put("turn_id", result.turnId());
        putSession(node, ledger);
        node.put("provider", llm.getProviderName());
        node.put("model", llm.getModelName());
        node.put("model_requests", result.modelRequests());
        node.put("duration_ms", result.durationMs());
        ObjectNode usage = node.putObject("usage");
        usage.put("input_tokens", result.inputTokens());
        usage.put("output_tokens", result.outputTokens());
        usage.put("cached_input_tokens", result.cachedInputTokens());
        // 工具统计和提示数（新增字段，旧字段不变）。
        metrics.writeToolStats(node);
        // 本次关掉的 harness 模块（消融实验记录）；全开时是空数组。
        com.fasterxml.jackson.databind.node.ArrayNode disabled = node.putArray("harness_disabled");
        com.haocli.agent.HarnessFeatures.fromSystem().disabledNames().forEach(disabled::add);
        return node;
    }

    private static void writeError(HeadlessOptions options, PrintStream out, String message,
                                   ConversationLedger ledger, LlmClient llm) {
        if (options.outputFormat() == HeadlessOptions.OutputFormat.TEXT) {
            return;
        }
        ObjectNode node = AgentEventJson.MAPPER.createObjectNode();
        node.put("type", "result");
        node.put("status", "failed");
        node.put("is_error", true);
        node.put("result", message);
        putSession(node, ledger);
        node.put("provider", llm.getProviderName());
        node.put("model", llm.getModelName());
        out.println(node.toString());
        out.flush();
    }

    private static void putSession(ObjectNode node, ConversationLedger ledger) {
        if (ledger != null && ledger.isEnabled() && ledger.sessionId() != null) {
            node.put("session_id", ledger.sessionId());
        } else {
            node.putNull("session_id");
        }
    }
}
