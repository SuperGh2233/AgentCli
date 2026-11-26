package com.paicli.agent;

import com.paicli.agent.event.AgentEvent;
import com.paicli.llm.LlmClient;
import com.paicli.memory.AutoCompactionManager;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.testing.ScriptedLlmClient;
import com.paicli.tool.ToolRegistry;
import com.paicli.tool.TurnToolPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.paicli.testing.ScriptedLlmClient.answer;
import static com.paicli.testing.ScriptedLlmClient.call;
import static com.paicli.testing.ScriptedLlmClient.toolCalls;
import static com.paicli.testing.ScriptedLlmClient.step;
import static com.paicli.testing.ScriptedLlmClient.truncated;
import static com.paicli.testing.ToolProtocolAssertions.assertToolCallsPaired;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直接测内核 {@link AgentLoop}，不经过 {@link Agent}：结果状态、事件顺序和收尾回调的调用时机。
 * 终端怎么根据事件显示由 {@code TerminalEventRendererTest} 测；ReAct 的端到端行为由 {@code ReActLoopReplayTest} 等覆盖。
 */
@Timeout(20)
class AgentLoopTest {

    /** 记录内核对调用方的每次回调。 */
    private static final class RecordingHost implements AgentLoop.Host {
        final List<String> calls = new ArrayList<>();
        boolean streamed;

        @Override
        public boolean answerStreamed() {
            return streamed;
        }

        @Override
        public String formatAnswer(String reasoningTranscript, String answer) {
            calls.add("format");
            return "formatted:" + answer;
        }

        @Override
        public boolean returnFinalResponseWhenStreamed() {
            return false;
        }

        @Override
        public void recordTokenUsage(AgentBudget budget) {
            calls.add("tokens:" + budget.totalInputTokens());
        }

        @Override
        public void afterFinalAnswer() {
            calls.add("afterFinalAnswer");
        }
    }

    private record Fixture(AgentLoop loop, ConversationContext conversation, RecordingHost host,
                           List<AgentEvent> events, AgentLoop.Turn turn) {
        List<Class<?>> eventTypes() {
            return events.stream().<Class<?>>map(Object::getClass).toList();
        }

        <T extends AgentEvent> List<T> eventsOf(Class<T> type) {
            return events.stream().filter(type::isInstance).map(type::cast).toList();
        }
    }

    private static Fixture fixture(ScriptedLlmClient llm, Path projectDir, String userInput) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectDir.toString());
        ConversationContext conversation = new ConversationContext(
                "react", "agent", new AutoCompactionManager(llm), () -> 0L);
        conversation.messages().add(LlmClient.Message.system("system"));
        conversation.append(LlmClient.Message.user(userInput), "user_input");
        RecordingHost host = new RecordingHost();
        List<AgentEvent> events = new ArrayList<>();
        AgentLoop.Turn turn = new AgentLoop.Turn(llm, TurnToolPolicy.fromUserInput(userInput), null,
                host, () -> Integer.MAX_VALUE, events::add);
        return new Fixture(new AgentLoop(registry, conversation), conversation, host, events, turn);
    }

    @Test
    void finalAnswerCompletesAndRunsClosingHooksInOrder(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "content-a");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                answer("读完了"));
        Fixture f = fixture(llm, projectDir, "读 a.txt");

        AgentLoop.Result result = f.loop().run(f.turn());

        assertEquals(AgentLoop.Status.COMPLETED, result.status());
        assertEquals("formatted:读完了", result.text());
        assertEquals(List.of("tokens:20", "afterFinalAnswer", "format"), f.host().calls);
        assertEquals(2, f.eventsOf(AgentEvent.ModelRequestStarted.class).size(), "每次模型请求前一条");
        List<LlmClient.Message> messages = f.conversation().messages();
        assertEquals("assistant", messages.get(messages.size() - 1).role());
        assertToolCallsPaired(messages);
    }

    @Test
    void providerFailureIsReportedAsFailed(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.of(answer("不会用到")).failOnRequest(1);
        Fixture f = fixture(llm, projectDir, "你好");

        AgentLoop.Result result = f.loop().run(f.turn());

        assertEquals(AgentLoop.Status.FAILED, result.status());
        assertTrue(result.text().startsWith("❌ 调用 LLM 失败"), result.text());
        assertEquals(List.of(AgentEvent.TurnStarted.class, AgentEvent.ModelRequestStarted.class,
                AgentEvent.TurnFinished.class), f.eventTypes(), "终端靠 TurnFinished 收尾已打开的流式显示");
        assertTrue(f.host().calls.stream().noneMatch("afterFinalAnswer"::equals), "失败时不能跑正常收尾");
    }

    @Test
    void cancellationBeforeFirstRequestSendsNothing(@TempDir Path projectDir) {
        CancellationToken token = CancellationContext.startRun();
        try {
            token.cancel();
            ScriptedLlmClient llm = ScriptedLlmClient.of(answer("不会用到"));
            Fixture f = fixture(llm, projectDir, "你好");

            AgentLoop.Result result = f.loop().run(f.turn());

            assertEquals(AgentLoop.Status.CANCELLED, result.status());
            assertEquals(AgentLoop.CANCELLED_MESSAGE, result.text());
            assertEquals(0, llm.requests().size());
        } finally {
            CancellationContext.clear(token);
        }
    }

    @Test
    void hardIterationLimitProducesPartialResultWithoutTools(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "content-a");
        String old = System.getProperty("paicli.react.hard.max.iterations");
        System.setProperty("paicli.react.hard.max.iterations", "1");
        try {
            ScriptedLlmClient llm = ScriptedLlmClient.of(
                    toolCalls(call("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                    answer("已读 a.txt，还没来得及做别的"));
            Fixture f = fixture(llm, projectDir, "读 a.txt 然后做很多事");

            AgentLoop.Result result = f.loop().run(f.turn());

            assertEquals(AgentLoop.Status.PARTIAL, result.status());
            assertTrue(result.text().contains("⚠️ 部分完成"), result.text());
            assertEquals(2, llm.requests().size());
            assertTrue(llm.toolRequests().get(1).isEmpty(), "收尾请求不能再给工具");
            assertTrue(f.host().calls.stream().noneMatch("afterFinalAnswer"::equals),
                    "部分结果不触发自动提取记忆（和重构前一致）");
            assertToolCallsPaired(f.conversation().messages());
        } finally {
            if (old == null) {
                System.clearProperty("paicli.react.hard.max.iterations");
            } else {
                System.setProperty("paicli.react.hard.max.iterations", old);
            }
        }
    }

    @Test
    void truncatedToolCallIsNotExecutedAndModelIsAskedToRetry(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.ofSteps(
                truncated(toolCalls(call("call_w", "write_file", "{\"path\":\"big.txt\",\"content\":\"abc"))),
                step(answer("我分几次写")));
        Fixture f = fixture(llm, projectDir, "写一个大文件");

        AgentLoop.Result result = f.loop().run(f.turn());

        assertEquals(AgentLoop.Status.COMPLETED, result.status());
        assertFalse(Files.exists(projectDir.resolve("big.txt")), "被截断的写文件调用不能执行");
        assertEquals(2, llm.requests().size());
        List<LlmClient.Message> second = llm.request(2);
        LlmClient.Message toolResult = second.get(second.size() - 1);
        assertEquals("tool", toolResult.role());
        assertEquals("call_w", toolResult.toolCallId());
        assertEquals(AgentLoop.TRUNCATED_TOOL_CALL_RESULT, toolResult.content());
        assertTrue(f.eventsOf(AgentEvent.Notice.class).stream()
                        .anyMatch(n -> n.text().contains("长度上限") && !n.attachedToAnswer()), f.events().toString());
        assertTrue(f.eventsOf(AgentEvent.ToolCallStarted.class).isEmpty(), "没执行的调用不显示成工具调用");
        assertToolCallsPaired(f.conversation().messages());
    }

    @Test
    void truncatedFinalAnswerCarriesNotice(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.ofSteps(truncated(answer("第一部分……")));
        Fixture f = fixture(llm, projectDir, "写一篇长文");

        AgentLoop.Result result = f.loop().run(f.turn());

        assertEquals(AgentLoop.Status.COMPLETED, result.status());
        assertTrue(result.text().startsWith("formatted:第一部分……"), result.text());
        assertTrue(result.text().endsWith(AgentLoop.TRUNCATED_ANSWER_NOTICE), result.text());
        AgentEvent.Notice notice = f.eventsOf(AgentEvent.Notice.class).get(0);
        assertTrue(notice.attachedToAnswer(), "回答没流式显示时提示已拼进返回文字，终端不能再打印一遍");
    }

    @Test
    void streamedAnswerSendsTrailingNoticeSeparately(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.ofSteps(truncated(answer("第一部分……")));
        Fixture f = fixture(llm, projectDir, "写一篇长文");
        f.host().streamed = true;

        AgentLoop.Result result = f.loop().run(f.turn());

        assertEquals("", result.text(), "流式显示过的回答不再放进返回文字");
        assertEquals("第一部分……", result.answer());
        AgentEvent.Notice notice = f.eventsOf(AgentEvent.Notice.class).get(0);
        assertFalse(notice.attachedToAnswer());
        assertTrue(notice.styledText().contains(AgentLoop.TRUNCATED_ANSWER_NOTICE), "终端按带样式的原文打印");
    }

    @Test
    void toolCallsAreMarkedWithTurnPolicyAndBatchEnd(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "content-a");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("c1", "read_file", "{\"path\":\"a.txt\"}"),
                        call("c2", "no_such_tool", "{}")),
                answer("好"));
        Fixture f = fixture(llm, projectDir, "读 a.txt");

        f.loop().run(f.turn());

        List<AgentEvent.ToolCallStarted> started = f.eventsOf(AgentEvent.ToolCallStarted.class);
        assertEquals(2, started.size());
        assertTrue(started.get(0).allowedByTurnPolicy());
        assertFalse(started.get(0).lastInBatch());
        assertFalse(started.get(1).allowedByTurnPolicy(), "没向本轮开放的工具不放行，终端不显示");
        assertTrue(started.get(1).lastInBatch());
    }

    @Test
    void normalFinishReasonsKeepPreviousBehaviour(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "content-a");
        ScriptedLlmClient llm = ScriptedLlmClient.ofSteps(
                new ScriptedLlmClient.Step(toolCalls(call("call_1", "read_file", "{\"path\":\"a.txt\"}")), "tool_calls"),
                new ScriptedLlmClient.Step(answer("读完了"), "stop"));
        Fixture f = fixture(llm, projectDir, "读 a.txt");

        AgentLoop.Result result = f.loop().run(f.turn());

        assertEquals("formatted:读完了", result.text());
        assertTrue(llm.request(2).get(llm.request(2).size() - 1).content().contains("content-a"));
    }

    @Test
    void eventsFollowTheDocumentedOrder(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "content-a");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                answer("读完了"));
        Fixture f = fixture(llm, projectDir, "读 a.txt");
        List<AgentEvent> events = f.events();

        f.loop().run(f.turn());

        assertEquals(List.of(
                AgentEvent.TurnStarted.class,
                AgentEvent.ModelRequestStarted.class,
                AgentEvent.Usage.class,
                AgentEvent.ToolCallStarted.class,
                AgentEvent.ToolCallFinished.class,
                AgentEvent.ModelRequestStarted.class,
                AgentEvent.Usage.class,
                AgentEvent.TurnFinished.class), f.eventTypes());
        AgentEvent.ToolCallFinished tool = (AgentEvent.ToolCallFinished) events.get(4);
        assertEquals("call_1", tool.id());
        assertTrue(tool.success());
        AgentEvent.TurnFinished finished = (AgentEvent.TurnFinished) events.get(7);
        assertEquals(((AgentEvent.TurnStarted) events.get(0)).turnId(), finished.turnId());
        assertEquals(AgentEvent.TurnStatus.COMPLETED, finished.status());
        assertEquals("读完了", finished.answer(), "answer 是原始回答，不经过 Host 格式化");
        assertEquals(2, finished.modelRequests());
        assertEquals(20, finished.inputTokens());
    }

    @Test
    void failingListenerDoesNotBreakTheTurn(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.of(answer("好"));
        Fixture f = fixture(llm, projectDir, "你好");
        AgentLoop.Turn turn = new AgentLoop.Turn(llm, f.turn().toolPolicy(), null, f.host(),
                () -> Integer.MAX_VALUE, event -> {
                    throw new IllegalStateException("listener bug");
                });

        AgentLoop.Result result = f.loop().run(turn);

        assertEquals(AgentLoop.Status.COMPLETED, result.status());
        assertEquals("好", result.answer());
    }

    @Test
    void truncationNoticesAreEmittedWithoutAnsiCodes(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.ofSteps(
                truncated(toolCalls(call("call_w", "write_file", "{\"path\":\"big.txt\",\"content\":\"abc"))),
                step(answer("好")));
        Fixture f = fixture(llm, projectDir, "写大文件");
        List<AgentEvent> events = f.events();

        f.loop().run(f.turn());

        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallSkipped skipped
                && "call_w".equals(skipped.id()) && "output_truncated".equals(skipped.reason())), events.toString());
        AgentEvent.Notice notice = events.stream()
                .filter(AgentEvent.Notice.class::isInstance).map(AgentEvent.Notice.class::cast)
                .findFirst().orElseThrow();
        assertTrue(notice.text().contains("长度上限"), notice.text());
        assertFalse(notice.text().contains("\u001B"), "事件文字不能带终端颜色代码");
        assertTrue(events.stream().noneMatch(AgentEvent.ToolCallStarted.class::isInstance), "没执行的调用不发开始事件");
    }
}
