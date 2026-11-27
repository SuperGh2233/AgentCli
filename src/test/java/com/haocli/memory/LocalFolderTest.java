package com.haocli.memory;

import com.haocli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 第 3 层局部折叠：只折叠被后面结果取代的输出；分段读不同范围不算；最近两条不动；配对不变。 */
class LocalFolderTest {

    private static List<LlmClient.Message> history(Object... callsAndResults) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        for (int i = 0; i < callsAndResults.length; i += 2) {
            String id = "c" + (i / 2);
            String[] call = (String[]) callsAndResults[i];
            history.add(call(id, call[0], call[1]));
            history.add(result(id, (String) callsAndResults[i + 1]));
        }
        return history;
    }

    private static String[] tool(String name, String args) {
        return new String[]{name, args};
    }

    private static boolean folded(LlmClient.Message message) {
        return message.content().startsWith(LocalFolder.MARKER);
    }

    @Test
    void foldsAnEarlierReadOfTheSameRangeAndAfterTheFileIsEdited() {
        List<LlmClient.Message> h = history(
                tool("read_file", "{\"path\":\"./src/A.java\"}"), big(500),
                tool("read_file", "{\"path\":\"src/B.java\",\"offset\":1,\"limit\":100}"), big(500),
                tool("read_file", "{\"path\":\"src/B.java\",\"offset\":101,\"limit\":100}"), big(500),
                tool("read_file", "{\"path\":\"src/A.java\"}"), big(500),
                tool("edit_file", "{\"path\":\"src/B.java\"}"), big(500),
                tool("list_dir", "{\"path\":\".\"}"), big(500),
                tool("list_dir", "{\"path\":\".\"}"), big(500));
        List<String> idsBefore = toolIds(h);

        LocalFolder.Result result = new LocalFolder().fold(h);

        assertTrue(folded(h.get(2)), "A.java 后来又完整读过");
        assertTrue(folded(h.get(4)) && folded(h.get(6)), "B.java 之后被编辑过，之前两段都过时");
        assertFalse(folded(h.get(8)), "A.java 最后一次读取是最新的");
        assertFalse(folded(h.get(12)) || folded(h.get(14)), "最近两条不动");
        assertEquals(3, result.count());
        assertEquals(idsBefore, toolIds(h));
    }

    @Test
    void differentRangesOfTheSameFileAreNotFolded() {
        List<LlmClient.Message> h = history(
                tool("read_file", "{\"path\":\"a.txt\",\"offset\":1,\"limit\":50}"), big(500),
                tool("read_file", "{\"path\":\"a.txt\",\"offset\":51,\"limit\":50}"), big(500),
                tool("execute_command", "{\"command\":\"ls\"}"), big(500),
                tool("execute_command", "{\"command\":\"pwd\"}"), big(500));

        assertEquals(0, new LocalFolder().fold(h).count());
    }

    @Test
    void foldsRepeatedCommandsAndIsIdempotent() {
        List<LlmClient.Message> h = history(
                tool("execute_command", "{\"command\":\"make test\"}"), big(500),
                tool("execute_command", "{\"command\":\"make test\"}"), big(500),
                tool("execute_command", "{\"command\":\"ls\"}"), big(500),
                tool("execute_command", "{\"command\":\"pwd\"}"), big(500));

        assertEquals(1, new LocalFolder().fold(h).count());
        assertTrue(folded(h.get(2)));
        assertEquals(0, new LocalFolder().fold(h).count(), "不重复折叠");
    }

    static LlmClient.Message call(String id, String tool, String args) {
        return LlmClient.Message.assistant("", List.of(
                new LlmClient.ToolCall(id, new LlmClient.ToolCall.Function(tool, args))));
    }

    static LlmClient.Message result(String id, String content) {
        return LlmClient.Message.tool(id, content);
    }

    static String big(int chars) {
        return "x".repeat(chars);
    }

    static List<String> toolIds(List<LlmClient.Message> history) {
        return history.stream().filter(m -> "tool".equals(m.role())).map(LlmClient.Message::toolCallId).toList();
    }
}
