package com.paicli.agent;

import com.paicli.llm.LlmClient;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.testing.ScriptedLlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.paicli.testing.ScriptedLlmClient.answer;
import static com.paicli.testing.ScriptedLlmClient.call;
import static com.paicli.testing.ScriptedLlmClient.toolCalls;
import static com.paicli.testing.ToolProtocolAssertions.assertEveryRequestPaired;
import static com.paicli.testing.ToolProtocolAssertions.assertToolCallsPaired;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 0 阶段基线：用假模型回放 ReAct 循环，固定“调模型 → 执行工具 → 写回历史”的可观察行为。
 *
 * <p>后续把循环抽成统一内核（升级计划第 2 阶段）时，这组测试必须原样通过。只断言对外可见、
 * 重构后也应保持的行为：发出几次请求、每次请求里的消息顺序、工具结果的配对和内容、返回给用户的结果。
 * 不调用真实模型，不访问网络。
 */
@Timeout(20)
class ReActLoopReplayTest {

    private static Agent newAgent(ScriptedLlmClient llm, Path projectDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectDir.toString());
        return new Agent(llm, registry);
    }

    @Test
    void plainAnswerUsesOneRequestAndEndsHistoryWithAssistant(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.of(answer("你好，我在。"));
        Agent agent = newAgent(llm, projectDir);

        String result = agent.run("打个招呼");

        assertEquals(1, llm.requests().size());
        List<LlmClient.Message> request = llm.request(1);
        assertEquals("system", request.get(0).role(), "第一条消息应是 system prompt");
        LlmClient.Message last = request.get(request.size() - 1);
        assertEquals("user", last.role());
        assertEquals("打个招呼", last.content());
        assertFalse(llm.toolRequests().get(0).isEmpty(), "普通请求应暴露工具");

        assertTrue(result.contains("你好，我在。"), result);
        List<LlmClient.Message> history = agent.getConversationHistory();
        LlmClient.Message lastInHistory = history.get(history.size() - 1);
        assertEquals("assistant", lastInHistory.role());
        assertEquals("你好，我在。", lastInHistory.content());
    }

    @Test
    void singleToolCallResultIsWrappedAndPairedInNextRequest(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("hello.txt"), "hello-content");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_1", "read_file", "{\"path\":\"hello.txt\"}")),
                answer("文件里写的是 hello-content"));
        Agent agent = newAgent(llm, projectDir);

        String result = agent.run("读一下 hello.txt");

        assertEquals(2, llm.requests().size());
        List<LlmClient.Message> second = llm.request(2);
        LlmClient.Message toolResult = second.get(second.size() - 1);
        assertEquals("tool", toolResult.role());
        assertEquals("call_1", toolResult.toolCallId());
        assertTrue(toolResult.content().contains("hello-content"), toolResult.content());
        assertTrue(toolResult.content().contains("trust=\"untrusted-data\""),
                "工具结果必须用不可信数据标记包起来: " + toolResult.content());

        LlmClient.Message assistantCall = second.get(second.size() - 2);
        assertEquals("assistant", assistantCall.role());
        assertNotNull(assistantCall.toolCalls());
        assertEquals("call_1", assistantCall.toolCalls().get(0).id());

        assertTrue(result.contains("hello-content"), result);
        assertEveryRequestPaired(llm.requests());
        assertToolCallsPaired(agent.getConversationHistory());
    }

    @Test
    void parallelReadOnlyToolResultsKeepOriginalCallOrder(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "content-of-a");
        Files.writeString(projectDir.resolve("b.txt"), "content-of-b");
        Files.writeString(projectDir.resolve("c.txt"), "content-of-c");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(
                        call("call_c", "read_file", "{\"path\":\"c.txt\"}"),
                        call("call_a", "read_file", "{\"path\":\"a.txt\"}"),
                        call("call_b", "read_file", "{\"path\":\"b.txt\"}")),
                answer("三个文件都读完了"));
        Agent agent = newAgent(llm, projectDir);

        agent.run("同时读 c、a、b 三个文件");

        assertEquals(2, llm.requests().size());
        List<LlmClient.Message> toolMessages = llm.request(2).stream()
                .filter(message -> "tool".equals(message.role()))
                .toList();
        assertEquals(List.of("call_c", "call_a", "call_b"),
                toolMessages.stream().map(LlmClient.Message::toolCallId).toList(),
                "并行执行的只读工具，结果仍按模型给出的调用顺序写回");
        assertTrue(toolMessages.get(0).content().contains("content-of-c"));
        assertTrue(toolMessages.get(1).content().contains("content-of-a"));
        assertTrue(toolMessages.get(2).content().contains("content-of-b"));
        assertEveryRequestPaired(llm.requests());
    }

    @Test
    void unknownToolFailureIsReturnedToModelAndLoopContinues(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_x", "no_such_tool", "{}")),
                answer("这个工具不存在，换个办法"));
        Agent agent = newAgent(llm, projectDir);

        String result = agent.run("用一个不存在的工具");

        assertEquals(2, llm.requests().size(), "工具失败不应终止循环，要把失败交还模型");
        List<LlmClient.Message> second = llm.request(2);
        LlmClient.Message toolResult = second.get(second.size() - 1);
        assertEquals("tool", toolResult.role());
        assertEquals("call_x", toolResult.toolCallId());
        assertFalse(toolResult.content().isBlank());
        assertTrue(result.contains("换个办法"), result);
        assertEveryRequestPaired(llm.requests());
    }

    @Test
    void invalidToolArgumentsAreReportedWithoutReadingAnything(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("secret.txt"), "should-not-leak");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_bad", "read_file", "{}")),
                answer("缺少 path 参数，已改正"));
        Agent agent = newAgent(llm, projectDir);

        agent.run("读文件但忘了给路径");

        assertEquals(2, llm.requests().size());
        List<LlmClient.Message> second = llm.request(2);
        LlmClient.Message toolResult = second.get(second.size() - 1);
        assertEquals("tool", toolResult.role());
        assertEquals("call_bad", toolResult.toolCallId());
        assertFalse(toolResult.content().isBlank());
        assertFalse(toolResult.content().contains("should-not-leak"));
        assertEveryRequestPaired(llm.requests());
    }

    @Test
    void multiStepRunKeepsEveryRequestToolProtocolComplete(@TempDir Path projectDir) throws Exception {
        Files.createDirectories(projectDir.resolve("docs"));
        Files.writeString(projectDir.resolve("docs/notes.md"), "step-two-content");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_list", "list_dir", "{\"path\":\"docs\"}")),
                toolCalls(call("call_read", "read_file", "{\"path\":\"docs/notes.md\"}")),
                answer("docs 下只有 notes.md，内容是 step-two-content"));
        Agent agent = newAgent(llm, projectDir);

        String result = agent.run("看看 docs 目录并读里面的文件");

        assertEquals(3, llm.requests().size());
        assertEquals(0, llm.remainingResponses());
        assertTrue(llm.request(2).get(llm.request(2).size() - 1).content().contains("notes.md"),
                "list_dir 的结果应出现在第二次请求里");
        assertTrue(llm.request(3).get(llm.request(3).size() - 1).content().contains("step-two-content"));
        assertEquals(1, llm.request(3).stream()
                        .filter(message -> "user".equals(message.role())
                                && "看看 docs 目录并读里面的文件".equals(message.content()))
                        .count(),
                "用户原话在整个循环里只出现一次");
        assertTrue(result.contains("step-two-content"), result);
        assertEveryRequestPaired(llm.requests());
        assertToolCallsPaired(agent.getConversationHistory());
    }

    @Test
    void providerFailureReturnsErrorWithoutLeavingDanglingToolCalls(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("hello.txt"), "hello-content");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                        toolCalls(call("call_1", "read_file", "{\"path\":\"hello.txt\"}")))
                .failOnRequest(2);
        Agent agent = newAgent(llm, projectDir);

        String result = agent.run("读一下 hello.txt");

        assertEquals(2, llm.requests().size());
        assertTrue(result.contains("调用 LLM 失败"), result);
        assertToolCallsPaired(agent.getConversationHistory());
    }

    @Test
    void cancellationAfterModelResponseSkipsToolExecution(@TempDir Path projectDir) {
        CancellationToken token = CancellationContext.startRun();
        try {
            ScriptedLlmClient llm = ScriptedLlmClient.of(
                            toolCalls(call("call_write", "write_file",
                                    "{\"path\":\"should-not-exist.txt\",\"content\":\"x\"}")),
                            answer("不应该走到这一步"))
                    .onRequest((number, messages) -> {
                        if (number == 1) {
                            token.cancel();
                        }
                    });
            Agent agent = newAgent(llm, projectDir);

            String result = agent.run("写一个文件");

            assertTrue(result.contains("已取消"), result);
            assertEquals(1, llm.requests().size(), "取消后不能再发请求");
            assertFalse(Files.exists(projectDir.resolve("should-not-exist.txt")),
                    "取消发生在模型回复之后、工具执行之前，写文件不能执行");
            assertToolCallsPaired(agent.getConversationHistory());
        } finally {
            CancellationContext.clear(token);
        }
    }

    @Test
    void secondRunReusesHistoryFromFirstRun(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.of(answer("第一轮回答"), answer("第二轮回答"));
        Agent agent = newAgent(llm, projectDir);

        agent.run("第一轮问题");
        String second = agent.run("第二轮问题");

        assertEquals(2, llm.requests().size());
        List<LlmClient.Message> request = llm.request(2);
        int firstQuestion = indexOf(request, "user", "第一轮问题");
        int firstAnswer = indexOf(request, "assistant", "第一轮回答");
        int secondQuestion = indexOf(request, "user", "第二轮问题");
        assertTrue(firstQuestion >= 0 && firstAnswer > firstQuestion && secondQuestion > firstAnswer,
                "第二轮请求应按顺序带上第一轮的问答");
        assertTrue(second.contains("第二轮回答"), second);
    }

    private static int indexOf(List<LlmClient.Message> messages, String role, String content) {
        for (int i = 0; i < messages.size(); i++) {
            LlmClient.Message message = messages.get(i);
            if (role.equals(message.role()) && content.equals(message.content())) {
                return i;
            }
        }
        return -1;
    }
}
