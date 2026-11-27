package com.haocli.testing;

import com.haocli.llm.LlmClient;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 检查对话里的工具调用协议是否完整：每条带工具调用的助手消息后面，必须紧跟着
 * 每个 tool_call_id 恰好一条工具结果，中间不能插入别的消息，也不能出现没有对应调用的工具结果。
 *
 * <p>供应商接口（OpenAI 兼容格式）对这一点要求严格，缺一条结果下一次请求就会被拒绝。
 */
public final class ToolProtocolAssertions {

    private ToolProtocolAssertions() {
    }

    public static void assertToolCallsPaired(List<LlmClient.Message> messages) {
        Set<String> seenToolResults = new HashSet<>();
        for (int i = 0; i < messages.size(); i++) {
            LlmClient.Message message = messages.get(i);
            if ("tool".equals(message.role())) {
                if (!seenToolResults.contains(message.toolCallId())) {
                    fail("第 " + i + " 条工具结果没有紧跟在对应的工具调用后面: " + message.toolCallId());
                }
                continue;
            }
            if (!"assistant".equals(message.role()) || message.toolCalls() == null
                    || message.toolCalls().isEmpty()) {
                continue;
            }
            Set<String> expected = new LinkedHashSet<>();
            for (LlmClient.ToolCall call : message.toolCalls()) {
                if (!expected.add(call.id())) {
                    fail("同一条助手消息里出现重复的 tool_call_id: " + call.id());
                }
            }
            Set<String> answered = new LinkedHashSet<>();
            int j = i + 1;
            while (j < messages.size() && "tool".equals(messages.get(j).role())) {
                String id = messages.get(j).toolCallId();
                if (!expected.contains(id)) {
                    fail("第 " + j + " 条工具结果的 id 不属于前面的工具调用: " + id);
                }
                if (!answered.add(id)) {
                    fail("tool_call_id 有重复的工具结果: " + id);
                }
                j++;
            }
            assertEquals(expected, answered, "第 " + i + " 条助手消息的工具调用没有全部拿到结果");
            seenToolResults.addAll(answered);
            i = j - 1;
        }
    }

    /** 对每次请求的快照逐一检查。 */
    public static void assertEveryRequestPaired(List<List<LlmClient.Message>> requests) {
        for (int i = 0; i < requests.size(); i++) {
            try {
                assertToolCallsPaired(requests.get(i));
            } catch (AssertionError e) {
                throw new AssertionError("第 " + (i + 1) + " 次请求: " + e.getMessage(), e);
            }
        }
    }
}
