package com.haocli.agent;

import com.haocli.agent.event.AgentEvent;
import com.haocli.headless.HeadlessOptions;
import com.haocli.headless.HeadlessRunner;
import com.haocli.history.ConversationLedger;
import com.haocli.llm.LlmClient;
import com.haocli.plan.ExecutionPlan;
import com.haocli.plan.Planner;
import com.haocli.plan.Task;
import com.haocli.testing.ScriptedLlmClient;
import com.haocli.tool.ToolRegistry;
import com.haocli.tool.ToolStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.haocli.testing.ScriptedLlmClient.answer;
import static com.haocli.testing.ScriptedLlmClient.call;
import static com.haocli.testing.ScriptedLlmClient.toolCalls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每个执行入口都经过同一套确定性检查：ReAct、Plan 任务节点、Team 子 Agent、进程内 SDK、无界面模式。
 * 假模型在每个入口做同样两件越权的事：读项目外的密钥文件、执行黑名单命令。
 * 命令选 {@code sudo -n true}：在黑名单里；万一没拦住，没有密码时它也只会失败，不会破坏任何东西。
 */
@Timeout(30)
class ExecutionEntryPolicyTest {

    private static final String SECRET = "TOP-SECRET-ENTRY-9c1e";

    @TempDir
    Path sandbox;
    private Path project;

    private String oldMemoryDir;

    @org.junit.jupiter.api.AfterEach
    void restoreMemoryDir() {
        if (oldMemoryDir == null) System.clearProperty("haocli.memory.dir");
        else System.setProperty("haocli.memory.dir", oldMemoryDir);
    }

    @BeforeEach
    void layout() throws Exception {
        // 不碰用户真实的 ~/.haocli/memory
        oldMemoryDir = System.getProperty("haocli.memory.dir");
        System.setProperty("haocli.memory.dir", sandbox.resolve("memory").toString());
        project = Files.createDirectories(sandbox.resolve("project"));
        Files.writeString(sandbox.resolve("secret.txt"), SECRET);
        Files.writeString(project.resolve("readme.txt"), "公开内容");
    }

    private static ScriptedLlmClient attackerModel() {
        return ScriptedLlmClient.of(
                toolCalls(call("c1", "read_file", "{\"path\":\"../secret.txt\"}")),
                toolCalls(call("c2", "execute_command", "{\"command\":\"sudo -n true\"}")),
                answer("完成"));
    }

    private ToolRegistry registry() {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(project.toString());
        return registry;
    }

    /** 两个调用都有结果、都被拒绝，密钥从没进过发给模型的内容。 */
    private static void assertContained(ScriptedLlmClient model, String entry) {
        // 最后一次带工具结果的请求（之后可能还有不带工具的收尾 / 记忆提取请求）
        List<LlmClient.Message> last = List.of();
        for (List<LlmClient.Message> request : model.requests()) {
            if (request.stream().anyMatch(m -> "tool".equals(m.role()))) last = request;
        }
        List<String> results = new ArrayList<>();
        for (List<LlmClient.Message> request : model.requests()) {
            for (LlmClient.Message m : request) {
                assertFalse(m.content() != null && m.content().contains(SECRET), entry + "：密钥被发给了模型");
            }
        }
        for (LlmClient.Message m : last) {
            if ("tool".equals(m.role())) results.add(m.toolCallId() + "=" + m.content());
        }
        assertEquals(2, results.size(), entry + "：两个调用都要有结果（配对）: " + results);
        assertTrue(results.get(0).startsWith("c1=") && results.get(0).contains("拒绝"), entry + "：越界读取没被拒绝: " + results.get(0));
        assertTrue(results.get(1).startsWith("c2=") && results.get(1).contains("拒绝"), entry + "：黑名单命令没被拒绝: " + results.get(1));
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
    }

    @Test
    void reactAgent() {
        ScriptedLlmClient model = attackerModel();
        Agent agent = new Agent(model, registry());
        agent.setReturnFinalResponseWhenStreamed(true);
        agent.run("看看项目");
        assertContained(model, "ReAct");
    }

    @Test
    void planTaskNode() {
        ScriptedLlmClient model = attackerModel();
        Planner planner = new Planner(model) {
            @Override
            public ExecutionPlan createPlan(String goal) {
                ExecutionPlan plan = new ExecutionPlan("plan-security", goal);
                plan.addTask(new Task("task_1", "看看项目", Task.TaskType.FILE_READ));
                plan.computeExecutionOrder();
                return plan;
            }
        };
        PlanExecuteAgent agent = new PlanExecuteAgent(model, registry(), planner, null,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.execute(), quiet());
        agent.run("看看项目");
        assertContained(model, "Plan 任务节点");
    }

    @Test
    void teamSubAgentWorker() {
        ScriptedLlmClient model = attackerModel();
        SubAgent worker = new SubAgent("worker-1", AgentRole.WORKER, model, registry());
        worker.execute(AgentMessage.task("orchestrator", "看看项目"), quiet());
        assertContained(model, "Team 子 Agent");
    }

    @Test
    void inProcessSdk() {
        ScriptedLlmClient model = attackerModel();
        List<AgentEvent> events = new ArrayList<>();
        HaoRuntime.builder().model(model).projectRoot(project).onEvent(events::add).build().run("看看项目");
        assertContained(model, "SDK");
        assertTrue(events.stream().filter(AgentEvent.ToolCallFinished.class::isInstance)
                        .map(e -> ((AgentEvent.ToolCallFinished) e).status()).allMatch(s -> s == ToolStatus.DENIED),
                "SDK 事件里两个调用都是 DENIED");
    }

    @Test
    void headlessMode() {
        ScriptedLlmClient model = attackerModel();
        HeadlessRunner.run(HeadlessOptions.parse(new String[]{"-p", "看看项目", "--output-format", "json"}),
                model, project, ConversationLedger.disabled(), quiet(), quiet());
        assertContained(model, "-p");
    }
}
