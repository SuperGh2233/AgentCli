package com.haocli.agent;

import com.haocli.agent.event.AgentEvent;
import com.haocli.llm.LlmClient;
import com.haocli.memory.AutoCompactionManager;
import com.haocli.testing.ScriptedLlmClient;
import com.haocli.tool.ToolRegistry;
import com.haocli.tool.ToolRegistry.ToolExecutionResult;
import com.haocli.tool.TurnToolPolicy;
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
import static com.haocli.testing.ScriptedLlmClient.step;
import static com.haocli.testing.ScriptedLlmClient.toolCalls;
import static com.haocli.testing.ScriptedLlmClient.truncated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 最小内核收敛：模块由组装方注入、单个模块出错不打断运行；Team 的子 Agent 经同一个 AgentLoop 执行，
 * 对话互相隔离，并且拿到内核统一的保护（截断的工具调用不执行、planner / reviewer 不带工具）。
 */
@Timeout(20)
class UnifiedKernelTest {

    private static final AgentLoop.Host HOST = new AgentLoop.Host() {
        public boolean answerStreamed() { return true; }
        public String formatAnswer(String reasoning, String answer) { return answer; }
        public boolean returnFinalResponseWhenStreamed() { return true; }
        public void recordTokenUsage(AgentBudget budget) { }
        public void afterFinalAnswer() { }
    };

    private static ToolRegistry registry(Path dir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(dir.toString());
        return registry;
    }

    private static ConversationContext conversation(ScriptedLlmClient llm, String input) {
        ConversationContext c = new ConversationContext("react", "agent", new AutoCompactionManager(llm), () -> 0L);
        c.messages().add(LlmClient.Message.system("system"));
        c.append(LlmClient.Message.user(input), "user_input");
        return c;
    }

    /** 记录调用顺序；可选在某一步抛错或乱改工具结果。 */
    private static final class ProbeHook implements TurnHook {
        private final String name;
        private final List<String> log;
        private final boolean fail;
        private final boolean dropResults;

        ProbeHook(String name, List<String> log, boolean fail, boolean dropResults) {
            this.name = name;
            this.log = log;
            this.fail = fail;
            this.dropResults = dropResults;
        }

        @Override
        public void beforeModelRequest(Context context) {
            log.add(name + ":before");
            if (fail) throw new IllegalStateException("boom");
        }

        @Override
        public List<ToolExecutionResult> beforeAppendToolResults(List<ToolExecutionResult> results, Context context) {
            if (fail) throw new IllegalStateException("boom");
            return dropResults ? List.of() : results;
        }

        @Override
        public void afterToolResults(List<ToolExecutionResult> results, Context context) {
            log.add(name + ":after");
            if (fail) throw new IllegalStateException("boom");
        }
    }

    @Test
    void injectedHooksRunInOrderAndAFailingOrMisbehavingHookCannotBreakPairing(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "content-a");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                answer("读完了"));
        ConversationContext c = conversation(llm, "读 a.txt");
        List<String> log = new ArrayList<>();
        AgentLoop loop = new AgentLoop(registry(dir), c, HarnessFeatures.ALL_ON, () -> List.of(
                new ProbeHook("a", log, true, false),
                new ProbeHook("b", log, false, true)));
        AgentLoop.Turn turn = new AgentLoop.Turn(llm, TurnToolPolicy.fromUserInput("读 a.txt"), null, HOST,
                () -> Integer.MAX_VALUE, null);

        AgentLoop.Result result = loop.run(turn);

        assertEquals(AgentLoop.Status.COMPLETED, result.status());
        assertEquals("读完了", result.answer());
        assertEquals(List.of("a:before", "b:before", "a:after", "b:after", "a:before", "b:before"), log,
                "模块按注入顺序调用；a 每一步都抛错也不影响后面的模块和本轮运行");
        List<LlmClient.Message> second = llm.request(2);
        assertTrue(second.stream().anyMatch(m -> "tool".equals(m.role()) && "call_1".equals(m.toolCallId())
                        && m.content().contains("content-a")),
                "b 想把工具结果丢掉被拒绝：调用和结果仍然配对");
    }

    @Test
    void turnWithToolsDisabledSendsNoToolDefinitions(@TempDir Path dir) {
        ScriptedLlmClient llm = ScriptedLlmClient.of(answer("规划完成"));
        AgentLoop loop = new AgentLoop(registry(dir), conversation(llm, "规划"), HarnessFeatures.ALL_ON);
        AgentLoop.Turn turn = new AgentLoop.Turn(llm, TurnToolPolicy.fromUserInput("规划"), null, HOST,
                () -> Integer.MAX_VALUE, null, RunLimits.UNLIMITED, false);

        assertEquals(AgentLoop.Status.COMPLETED, loop.run(turn).status());
        assertTrue(llm.toolRequests().get(0).isEmpty());
    }

    @Test
    void defaultHooksStillFollowHarnessFeatures(@TempDir Path dir) {
        List<Class<?>> none = DefaultTurnHooks.create(HarnessFeatures.parse("context,lsp"), registry(dir))
                .stream().<Class<?>>map(Object::getClass).toList();
        assertEquals(List.of(ResourceFailureHook.class, SkillInjectionHook.class), none, "消融开关仍然生效");
    }

    private static String run(SubAgent agent, String task) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        return agent.execute(AgentMessage.task("orchestrator", task),
                new PrintStream(out, true, StandardCharsets.UTF_8)).content();
    }

    @Test
    void workerExecutesToolsThroughTheKernelAndPlannerGetsNoTools(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "content-a");
        ScriptedLlmClient workerLlm = ScriptedLlmClient.of(
                toolCalls(call("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                answer("a.txt 是 content-a"));
        SubAgent worker = new SubAgent("worker-1", AgentRole.WORKER, workerLlm, registry(dir));

        assertEquals("a.txt 是 content-a", run(worker, "读 a.txt"));
        assertFalse(workerLlm.toolRequests().get(0).isEmpty(), "worker 带工具");
        assertTrue(workerLlm.request(2).stream().anyMatch(m -> "tool".equals(m.role())
                && m.content().contains("content-a")));

        ScriptedLlmClient plannerLlm = ScriptedLlmClient.of(answer("{\"steps\":[]}"));
        SubAgent planner = new SubAgent("planner", AgentRole.PLANNER, plannerLlm, registry(dir));
        run(planner, "规划一下");
        assertTrue(plannerLlm.toolRequests().get(0).isEmpty(), "planner 的请求不带工具（评测审计约定）");
    }

    @Test
    void subAgentsKeepIsolatedHistories(@TempDir Path dir) {
        ToolRegistry shared = registry(dir);
        ScriptedLlmClient llmA = ScriptedLlmClient.of(answer("A1"), answer("A2"));
        ScriptedLlmClient llmB = ScriptedLlmClient.of(answer("B1"));
        SubAgent a = new SubAgent("worker-a", AgentRole.WORKER, llmA, shared);
        SubAgent b = new SubAgent("worker-b", AgentRole.WORKER, llmB, shared);

        run(a, "任务-甲");
        run(b, "任务-乙");
        run(a, "任务-丙");

        List<LlmClient.Message> aSecond = llmA.request(2);
        assertTrue(aSecond.stream().anyMatch(m -> m.content() != null && m.content().contains("任务-甲")));
        assertFalse(aSecond.stream().anyMatch(m -> m.content() != null && m.content().contains("任务-乙")),
                "共享工具注册表，但对话互不可见");
        assertFalse(llmB.request(1).stream().anyMatch(m -> m.content() != null && m.content().contains("任务-甲")));
    }

    @Test
    void subAgentNoLongerExecutesTruncatedToolCalls(@TempDir Path dir) {
        ScriptedLlmClient llm = ScriptedLlmClient.ofSteps(
                truncated(toolCalls(call("call_1", "write_file", "{\"path\":\"x.txt\",\"content\":\"半截"))),
                step(answer("改用小块写入")));
        SubAgent worker = new SubAgent("worker-1", AgentRole.WORKER, llm, registry(dir));

        assertEquals("改用小块写入", run(worker, "写 x.txt"));
        assertFalse(Files.exists(dir.resolve("x.txt")), "被长度截断的工具调用不执行（以前子 Agent 没有这层保护）");
        assertTrue(llm.request(2).stream().anyMatch(m -> "tool".equals(m.role())
                && m.content().contains("工具未执行")), "每个截断的调用仍然回一条结果，保持配对");
    }
}
