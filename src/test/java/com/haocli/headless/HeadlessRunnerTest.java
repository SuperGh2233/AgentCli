package com.haocli.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haocli.history.ConversationLedger;
import com.haocli.llm.LlmClient;
import com.haocli.testing.ScriptedLlmClient;
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

/** 无界面模式端到端：假模型 + 临时项目目录，检查标准输出格式、退出码和工具白名单。不访问真实模型。 */
@Timeout(20)
class HeadlessRunnerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record Run(int exitCode, String out, String err) {
        List<JsonNode> jsonLines() throws Exception {
            List<JsonNode> lines = new ArrayList<>();
            for (String line : out.split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(MAPPER.readTree(line));
                }
            }
            return lines;
        }
    }

    private static Run run(ScriptedLlmClient llm, Path projectDir, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = HeadlessRunner.run(HeadlessOptions.parse(args), llm, projectDir, ConversationLedger.disabled(),
                new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void textModePrintsOnlyTheFinalAnswer(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "content-a");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                answer("a.txt 里是 content-a"));

        Run run = run(llm, projectDir, "-p", "读 a.txt");

        assertEquals(HeadlessRunner.EXIT_COMPLETED, run.exitCode());
        assertEquals("a.txt 里是 content-a", run.out().strip(), "标准输出只能有最终回答");
    }

    @Test
    void maxTurnsStopsToolUseAndReportsPartialWithExitCodeThree(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "content-a");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                answer("只读了 a.txt，还没来得及做别的"));

        Run run = run(llm, projectDir, "-p", "读完所有文件", "--max-turns", "1", "--output-format", "json");

        assertEquals(HeadlessRunner.EXIT_PARTIAL, run.exitCode(), run.out() + run.err());
        JsonNode result = run.jsonLines().get(0);
        assertEquals("partial", result.path("status").asText());
        assertEquals(2, result.path("model_requests").asInt(), "1 次正常请求 + 1 次不带工具的收尾请求");
        assertEquals("hard_iteration_limit", result.path("reason").asText(), "部分完成要写明是哪个上限");
        assertTrue(llm.toolRequests().get(1).isEmpty(), "收尾请求不能再给工具");
    }

    @Test
    void jsonModeWritesOneResultObject(@TempDir Path projectDir) throws Exception {
        ScriptedLlmClient llm = ScriptedLlmClient.of(answer("42"));

        Run run = run(llm, projectDir, "-p", "答案是多少", "--output-format", "json");

        assertEquals(0, run.exitCode());
        List<JsonNode> lines = run.jsonLines();
        assertEquals(1, lines.size(), run.out());
        JsonNode result = lines.get(0);
        assertEquals("result", result.path("type").asText());
        assertEquals("completed", result.path("status").asText());
        assertFalse(result.path("is_error").asBoolean());
        assertEquals("42", result.path("result").asText());
        assertEquals(1, result.path("model_requests").asInt());
        assertEquals(10, result.path("usage").path("input_tokens").asInt());
        assertEquals("scripted", result.path("model").asText());
        assertTrue(result.path("session_id").isNull(), "会话记录关闭时 session_id 为 null");
        assertEquals(0, result.path("tools").path("calls").asInt());
        assertTrue(result.path("tools").path("success_rate").isNull(), "没执行过工具时成功率是 null，不是 0");
        assertEquals(0, result.path("notices").asInt());
        assertTrue(result.path("reason").isNull(), "正常完成没有结束原因");
        JsonNode context = result.path("context");
        assertTrue(context.isObject(), "结果里要有上下文处理统计: " + result);
        for (String field : List.of("offloads", "trim_runs", "trimmed", "fold_runs", "folded", "clearing_runs",
                "cleared", "summaries", "emergency_runs", "tokens_saved")) {
            assertEquals(0, context.path(field).asInt(-1), field);
        }
    }

    @Test
    void streamJsonModeWritesEveryEventThenResult(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "content-a");
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_1", "read_file", "{\"path\":\"a.txt\"}")),
                answer("读完了"));

        Run run = run(llm, projectDir, "-p", "读 a.txt", "--output-format", "stream-json");

        List<String> types = run.jsonLines().stream().map(node -> node.path("type").asText()).toList();
        assertEquals(List.of("turn_started", "model_request_started", "usage", "tool_call_started",
                "tool_call_finished", "model_request_started", "usage", "turn_finished", "result"), types);
        JsonNode finishedTool = run.jsonLines().get(4);
        assertEquals("call_1", finishedTool.path("id").asText());
        assertTrue(finishedTool.path("success").asBoolean());
        assertEquals("success", finishedTool.path("status").asText());
        assertTrue(finishedTool.path("output").asText().contains("content-a"));
    }

    @Test
    void readOnlyModeHidesAndRefusesWriteTools(@TempDir Path projectDir) throws Exception {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_w", "write_file", "{\"path\":\"x.txt\",\"content\":\"x\"}")),
                answer("没写成"));

        Run run = run(llm, projectDir, "-p", "写 x.txt", "--output-format", "stream-json");

        assertEquals(0, run.exitCode());
        assertFalse(Files.exists(projectDir.resolve("x.txt")), "read-only 模式不能写文件");
        List<String> exposed = llm.toolRequests().get(0).stream().map(LlmClient.Tool::name).toList();
        assertFalse(exposed.contains("write_file"), "不允许的工具不暴露给模型: " + exposed);
        assertFalse(exposed.contains("execute_command"), "没有 --allow-command 时不暴露执行命令");
        assertTrue(exposed.contains("read_file"));
        JsonNode finishedTool = run.jsonLines().stream()
                .filter(node -> "tool_call_finished".equals(node.path("type").asText()))
                .findFirst().orElseThrow();
        assertFalse(finishedTool.path("success").asBoolean());
        assertEquals("denied", finishedTool.path("status").asText(), "被拒绝和执行失败要分开");
        // 没暴露的工具先被本轮工具策略（TurnToolPolicy）拒绝；无界面白名单本身由 denialReason 单独测。
        assertTrue(finishedTool.path("output").asText().contains("未向本轮模型开放"), finishedTool.toString());
    }

    @Test
    void editModeAllowsWritingFiles(@TempDir Path projectDir) throws Exception {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_w", "write_file", "{\"path\":\"x.txt\",\"content\":\"hello\"}")),
                answer("写好了"));

        Run run = run(llm, projectDir, "-p", "写 x.txt", "--permission-mode", "edit");

        assertEquals(0, run.exitCode());
        assertEquals("hello", Files.readString(projectDir.resolve("x.txt")));
        assertEquals("写好了", run.out().strip());
    }

    @Test
    void providerFailureExitsWithOneAndReportsError(@TempDir Path projectDir) throws Exception {
        ScriptedLlmClient llm = ScriptedLlmClient.of(answer("不会用到")).failOnRequest(1);

        Run run = run(llm, projectDir, "-p", "你好", "--output-format", "json");

        assertEquals(HeadlessRunner.EXIT_FAILED, run.exitCode());
        JsonNode result = run.jsonLines().get(0);
        assertEquals("failed", result.path("status").asText());
        assertTrue(result.path("is_error").asBoolean());
        assertTrue(result.path("result").asText().contains("调用 LLM 失败"));
        assertEquals("llm_unknown", result.path("reason").asText());
    }

    @Test
    void textModeFailureGoesToStderrNotStdout(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.of(answer("不会用到")).failOnRequest(1);

        Run run = run(llm, projectDir, "-p", "你好");

        assertEquals(HeadlessRunner.EXIT_FAILED, run.exitCode());
        assertTrue(run.out().isBlank(), "失败时标准输出不能有内容: " + run.out());
        assertTrue(run.err().contains("调用 LLM 失败"), run.err());
    }

    @Test
    void commandPolicyAllowsOnlyExactAllowlistedCommands() {
        HeadlessToolRegistry registry = new HeadlessToolRegistry(
                HeadlessOptions.PermissionMode.READ_ONLY, List.of("git status"));

        assertEquals(null, registry.denialReason("execute_command", "{\"command\":\"git status\"}"));
        assertTrue(registry.denialReason("execute_command", "{\"command\":\"git status && rm -rf x\"}") != null);
        assertTrue(registry.denialReason("execute_command", "{}") != null);
        assertTrue(registry.denialReason("revert_turn", "{}") != null);
        assertTrue(registry.denialReason("save_memory", "{}") != null);
        assertTrue(registry.denialReason("mcp__github__create_issue", "{}") != null);
        assertEquals(null, registry.denialReason("grep_code", "{}"));
        assertTrue(registry.getToolDefinitions().stream().anyMatch(tool -> "execute_command".equals(tool.name())),
                "有白名单时才暴露执行命令");
    }
}
