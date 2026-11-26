package com.paicli.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.history.ConversationLedger;
import com.paicli.testing.ScriptedLlmClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.paicli.testing.ScriptedLlmClient.answer;
import static com.paicli.testing.ScriptedLlmClient.call;
import static com.paicli.testing.ScriptedLlmClient.step;
import static com.paicli.testing.ScriptedLlmClient.toolCalls;
import static com.paicli.testing.ScriptedLlmClient.truncated;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Harness 回归场景（评估报告 Phase 1 的“免费层评测”）：用假模型按固定脚本跑一组典型情况，
 * 经过完整的无界面链路（Agent 内核 → 工具策略 → 工具 → 事件 → 结果 JSON），对 harness 指标逐项精确断言。
 *
 * <p>假模型的回复是固定的，所以这里测的是 harness 本身：同样的模型输出，harness 发了几次请求、
 * 执行 / 拒绝 / 跳过了几个工具、给了几条提示、最后是什么状态。改动内核或工具层后这些数字变了，
 * 要么是有意的行为变化（同时改断言并在计划里写明），要么是回归。
 *
 * <p>跑完把每个场景的指标写到 {@code target/harness-metrics/scenarios.jsonl}（不含耗时，逐行可比），
 * 用 {@code diff} 对比两次提交即可。不调用真实模型、不访问网络。
 */
@Timeout(60)
class HarnessScenarioSuiteTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<ObjectNode> REPORT = new ArrayList<>();

    /** 预期的指标；Token 由假模型固定为每次请求 10 入 / 5 出，按请求数推算。 */
    private record Expect(String status, int exitCode, int modelRequests, int toolCalls, int succeeded,
                          int failed, int skipped, int notices) {
    }

    private interface Setup {
        void prepare(Path projectDir) throws IOException;
    }

    private record Scenario(String name, String description, Setup setup, Supplier<ScriptedLlmClient> llm,
                            List<String> extraArgs, Map<String, String> systemProperties, Expect expect) {
    }

    private static Setup files(Map<String, String> files) {
        return dir -> {
            for (Map.Entry<String, String> file : files.entrySet()) {
                Path target = dir.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.writeString(target, file.getValue());
            }
        };
    }

    private static List<Scenario> scenarios() {
        return List.of(
                new Scenario("direct_answer", "不需要工具，一次请求直接回答",
                        files(Map.of()),
                        () -> ScriptedLlmClient.of(answer("你好")),
                        List.of(), Map.of(),
                        new Expect("completed", 0, 1, 0, 0, 0, 0, 0)),

                new Scenario("read_then_answer", "读一个文件后回答",
                        files(Map.of("a.txt", "content-a")),
                        () -> ScriptedLlmClient.of(
                                toolCalls(call("c1", "read_file", "{\"path\":\"a.txt\"}")),
                                answer("读完了")),
                        List.of(), Map.of(),
                        new Expect("completed", 0, 2, 1, 1, 0, 0, 0)),

                new Scenario("parallel_reads", "一次回复里并行读三个文件",
                        files(Map.of("a.txt", "a", "b.txt", "b", "c.txt", "c")),
                        () -> ScriptedLlmClient.of(
                                toolCalls(
                                        call("c1", "read_file", "{\"path\":\"a.txt\"}"),
                                        call("c2", "read_file", "{\"path\":\"b.txt\"}"),
                                        call("c3", "read_file", "{\"path\":\"c.txt\"}")),
                                answer("三个都读了")),
                        List.of(), Map.of(),
                        new Expect("completed", 0, 2, 3, 3, 0, 0, 0)),

                new Scenario("recover_from_edit_failure", "第一次改文件找不到原文，改正后成功",
                        files(Map.of("a.txt", "hello world")),
                        () -> ScriptedLlmClient.of(
                                toolCalls(call("c1", "edit_file",
                                        "{\"path\":\"a.txt\",\"old_text\":\"goodbye\",\"new_text\":\"hi\"}")),
                                toolCalls(call("c2", "edit_file",
                                        "{\"path\":\"a.txt\",\"old_text\":\"hello\",\"new_text\":\"hi\"}")),
                                answer("改好了")),
                        List.of("--permission-mode", "edit"), Map.of(),
                        new Expect("completed", 0, 3, 2, 1, 1, 0, 0)),

                new Scenario("invalid_arguments_corrected", "缺参数被校验拦下，补上后成功",
                        files(Map.of("a.txt", "content-a")),
                        () -> ScriptedLlmClient.of(
                                toolCalls(call("c1", "read_file", "{}")),
                                toolCalls(call("c2", "read_file", "{\"path\":\"a.txt\"}")),
                                answer("读到了")),
                        List.of(), Map.of(),
                        new Expect("completed", 0, 3, 2, 1, 1, 0, 0)),

                new Scenario("truncated_tool_call_retried", "写文件参数被长度截断，跳过后模型缩短重试",
                        files(Map.of()),
                        () -> ScriptedLlmClient.ofSteps(
                                truncated(toolCalls(call("c1", "write_file",
                                        "{\"path\":\"big.txt\",\"content\":\"aaaa"))),
                                step(toolCalls(call("c2", "write_file",
                                        "{\"path\":\"big.txt\",\"content\":\"short\"}"))),
                                step(answer("分开写好了"))),
                        List.of("--permission-mode", "edit"), Map.of(),
                        new Expect("completed", 0, 3, 1, 1, 0, 1, 1)),

                new Scenario("repeated_failure_reminder", "同一个失败调用连续三次，触发换思路提醒",
                        files(Map.of("a.txt", "hello world")),
                        () -> ScriptedLlmClient.of(
                                toolCalls(call("c1", "edit_file",
                                        "{\"path\":\"a.txt\",\"old_text\":\"nope\",\"new_text\":\"x\"}")),
                                toolCalls(call("c2", "edit_file",
                                        "{\"path\":\"a.txt\",\"old_text\":\"nope\",\"new_text\":\"x\"}")),
                                toolCalls(call("c3", "edit_file",
                                        "{\"path\":\"a.txt\",\"old_text\":\"nope\",\"new_text\":\"x\"}")),
                                answer("换个办法")),
                        List.of("--permission-mode", "edit"), Map.of(),
                        new Expect("completed", 0, 4, 3, 0, 3, 0, 1)),

                new Scenario("policy_denied_in_read_only", "只读模式下写文件被拒绝",
                        files(Map.of()),
                        () -> ScriptedLlmClient.of(
                                toolCalls(call("c1", "write_file", "{\"path\":\"x.txt\",\"content\":\"x\"}")),
                                answer("写不了")),
                        List.of(), Map.of(),
                        new Expect("completed", 0, 2, 1, 0, 1, 0, 0)),

                new Scenario("budget_partial_result", "硬轮数上限触发，无工具收尾出部分结果",
                        files(Map.of("a.txt", "content-a")),
                        () -> ScriptedLlmClient.of(
                                toolCalls(call("c1", "read_file", "{\"path\":\"a.txt\"}")),
                                answer("只做了一部分")),
                        List.of(), Map.of("paicli.react.hard.max.iterations", "1"),
                        new Expect("partial", 3, 2, 1, 1, 0, 0, 1)),

                new Scenario("provider_failure", "供应商调用失败",
                        files(Map.of()),
                        () -> ScriptedLlmClient.of(answer("不会用到")).failOnRequest(1),
                        List.of(), Map.of(),
                        new Expect("failed", 1, 1, 0, 0, 0, 0, 0)),

                // 2026-11-26 修复：read_file 返回的“读取文件失败: …”以前不在 ToolRegistry.looksLikeFailureText
                // 的失败名单里，被算成成功（原场景名 known_issue_read_failure_counted_as_success，succeeded/failed 为 1/0）。
                new Scenario("read_missing_file_counted_as_failure", "读不存在的文件算失败",
                        files(Map.of()),
                        () -> ScriptedLlmClient.of(
                                toolCalls(call("c1", "read_file", "{\"path\":\"missing.txt\"}")),
                                answer("文件不存在")),
                        List.of(), Map.of(),
                        new Expect("completed", 0, 2, 1, 0, 1, 0, 0))
        );
    }

    @TestFactory
    Stream<DynamicTest> scenariosProduceExpectedHarnessMetrics() {
        return scenarios().stream().map(scenario -> DynamicTest.dynamicTest(
                scenario.name() + "：" + scenario.description(), () -> runAndAssert(scenario)));
    }

    private static void runAndAssert(Scenario scenario) throws Exception {
        Path projectDir = Files.createTempDirectory("harness-scenario-");
        scenario.setup().prepare(projectDir);
        List<String> args = new ArrayList<>(List.of("-p", scenario.description(), "--output-format", "json"));
        args.addAll(scenario.extraArgs());

        Map<String, String> previous = new java.util.HashMap<>();
        scenario.systemProperties().forEach((key, value) -> {
            previous.put(key, System.getProperty(key));
            System.setProperty(key, value);
        });
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode;
        try {
            exitCode = HeadlessRunner.run(HeadlessOptions.parse(args.toArray(String[]::new)),
                    scenario.llm().get(), projectDir, ConversationLedger.disabled(),
                    new PrintStream(out, true, StandardCharsets.UTF_8),
                    new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }

        JsonNode result = MAPPER.readTree(out.toString(StandardCharsets.UTF_8).strip());
        ObjectNode line = MAPPER.createObjectNode();
        line.put("scenario", scenario.name());
        line.put("exit_code", exitCode);
        line.put("status", result.path("status").asText());
        line.put("model_requests", result.path("model_requests").asInt());
        line.set("tools", result.path("tools"));
        line.put("notices", result.path("notices").asInt());
        line.put("input_tokens", result.path("usage").path("input_tokens").asLong());
        line.put("output_tokens", result.path("usage").path("output_tokens").asLong());
        synchronized (REPORT) {
            REPORT.add(line);
        }

        Expect expect = scenario.expect();
        String context = scenario.name() + " → " + line;
        assertEquals(expect.status(), line.path("status").asText(), context);
        assertEquals(expect.exitCode(), exitCode, context);
        assertEquals(expect.modelRequests(), line.path("model_requests").asInt(), context);
        assertEquals(expect.toolCalls(), line.path("tools").path("calls").asInt(), context);
        assertEquals(expect.succeeded(), line.path("tools").path("succeeded").asInt(), context);
        assertEquals(expect.failed(), line.path("tools").path("failed").asInt(), context);
        assertEquals(expect.skipped(), line.path("tools").path("skipped").asInt(), context);
        assertEquals(expect.notices(), line.path("notices").asInt(), context);
        int answeredRequests = "failed".equals(expect.status()) ? 0 : expect.modelRequests();
        assertEquals(answeredRequests * 10L, line.path("input_tokens").asLong(), context);
        assertEquals(answeredRequests * 5L, line.path("output_tokens").asLong(), context);
    }

    @AfterAll
    static void writeReport() throws IOException {
        Path dir = Path.of("target", "harness-metrics");
        Files.createDirectories(dir);
        List<String> lines;
        synchronized (REPORT) {
            lines = REPORT.stream()
                    .sorted(Comparator.comparing(node -> node.path("scenario").asText()))
                    .map(ObjectNode::toString)
                    .toList();
        }
        Files.write(dir.resolve("scenarios.jsonl"), lines, StandardCharsets.UTF_8);
    }
}
