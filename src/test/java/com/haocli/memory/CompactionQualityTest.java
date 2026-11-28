package com.haocli.memory;

import com.haocli.llm.LlmClient;
import com.haocli.testing.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 上下文质量的确定性评估：一段长任务对话依次经过第 2–4 层（裁剪、折叠、清理），
 * 检查关键信息是否保留、工具调用和结果是否始终配对，并量出节省的 Token。不调用模型（摘要层不在这里触发）。
 */
class CompactionQualityTest {

    private static final String GOAL = "目标：把 OrderService 的金额计算改成 BigDecimal。约束：不能改 public 接口；"
            + "测试必须用 mvn -q test 跑通；不要动 legacy/ 目录。";
    private static final String DECISION = "决定：舍入方式用 HALF_EVEN，和财务系统一致。";
    private static final String NEXT = "下一步：改完 src/main/java/shop/OrderService.java 后补 OrderServiceTest 的边界用例。";

    private static List<LlmClient.Message> longTaskConversation() {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        history.add(LlmClient.Message.user(GOAL));
        for (int i = 0; i < 12; i++) {
            String id = "call_" + i;
            String path = i % 3 == 0 ? "src/main/java/shop/OrderService.java" : "src/main/java/shop/Util" + i + ".java";
            history.add(LlmClient.Message.assistant("读 " + path, List.of(new LlmClient.ToolCall(id,
                    new LlmClient.ToolCall.Function("read_file", "{\"path\":\"" + path + "\"}")))));
            history.add(LlmClient.Message.tool(id, "文件内容:\n" + ("class X { double amount; }\n").repeat(400)));
            if (i == 4) history.add(LlmClient.Message.user(DECISION));
        }
        history.add(LlmClient.Message.assistant(NEXT));
        return history;
    }

    private static void assertPaired(List<LlmClient.Message> history) {
        Set<String> calls = new HashSet<>();
        Set<String> results = new HashSet<>();
        for (LlmClient.Message m : history) {
            if (m.toolCalls() != null) m.toolCalls().forEach(c -> calls.add(c.id()));
            if ("tool".equals(m.role())) {
                assertTrue(calls.contains(m.toolCallId()), "工具结果前面必须有对应的调用: " + m.toolCallId());
                results.add(m.toolCallId());
            }
        }
        assertEquals(calls, results, "每个调用都有结果");
    }

    @Test
    void cheapLayersKeepUserIntentAndPairingWhileSavingTokens() {
        List<LlmClient.Message> history = longTaskConversation();
        int before = TokenBudget.estimateMessagesTokens(history);
        int messagesBefore = history.size();
        AutoCompactionManager manager = new AutoCompactionManager(ScriptedLlmClient.of());

        // 触发线设在当前大小附近：第 2–4 层都会动，摘要层关掉（不调模型）
        AutoCompactionManager.Result result = manager.compactIfNeeded(history, before,
                new AutoCompactionManager.Layers(true, true, true, false));
        int after = TokenBudget.estimateMessagesTokens(history);

        assertTrue(result.changed(), "这么长的对话应该触发压缩: " + result);
        assertTrue(after < before * 0.6, "至少省 40%: before=" + before + " after=" + after);
        assertEquals(messagesBefore, history.size(), "只替换内容，不删消息");
        assertPaired(history);
        String all = String.join("\n", history.stream().map(m -> m.content() == null ? "" : m.content()).toList());
        for (String key : List.of(GOAL, DECISION, NEXT)) {
            assertTrue(all.contains(key), "用户目标、约束、决定、下一步原样保留: " + key);
        }
        assertTrue(all.contains("src/main/java/shop/OrderService.java"), "文件路径仍可见（调用参数不动）");
        String newest = history.get(history.size() - 2).content();
        assertTrue(newest.contains("class X"), "最近的工具结果不被压掉");
        System.out.printf("compaction quality: tokens %d -> %d (saved %.0f%%), trimmed=%d folded=%d cleared=%d%n",
                before, after, 100.0 * (before - after) / before, result.trimmed(), result.folded(),
                result.clearedToolResults());
    }

    @Test
    void emergencyCompactionAlsoKeepsPairing() {
        List<LlmClient.Message> history = longTaskConversation();
        AutoCompactionManager manager = new AutoCompactionManager(ScriptedLlmClient.of());

        AutoCompactionManager.Result result = manager.compactEmergency(history,
                new AutoCompactionManager.Layers(true, true, true, false));

        assertTrue(result.changed());
        assertPaired(history);
        assertTrue(history.get(1).content().equals(GOAL), "第一条用户消息不动");
    }
}
