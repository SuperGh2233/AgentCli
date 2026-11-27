package com.haocli.llm.core;

import com.haocli.llm.LlmClient;
import com.haocli.testing.ScriptedLlmClient;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class LegacyModelClientTest {

    @Test
    void exchangePassesMessagesThroughAndReturnsRawResponseUnchanged() throws Exception {
        // answer() 的思考内容是 null；来回转换会变成空串，exchange 必须原样返回旧回复。
        LlmClient.ChatResponse scripted = ScriptedLlmClient.answer("好");
        ScriptedLlmClient llm = ScriptedLlmClient.ofSteps(new ScriptedLlmClient.Step(scripted, "stop"));
        List<LlmClient.Message> messages = List.of(LlmClient.Message.system("s"), LlmClient.Message.user("u"));
        List<StreamEvent> events = new ArrayList<>();

        LegacyModelClient.Exchange exchange = new LegacyModelClient(llm).exchange(messages, null, events::add);

        assertSame(scripted, exchange.raw());
        assertEquals(messages, llm.request(1), "对话原样交给原 Client，不经过转换");
        assertEquals(StopReason.END_TURN, exchange.response().stopReason());
        assertEquals("stop", exchange.response().rawFinishReason());
        assertInstanceOf(StreamEvent.Completed.class, events.get(events.size() - 1));
    }

    @Test
    void lengthFinishReasonIsReportedAsMaxTokens() throws Exception {
        ScriptedLlmClient llm = ScriptedLlmClient.ofSteps(ScriptedLlmClient.truncated(
                ScriptedLlmClient.toolCalls(ScriptedLlmClient.call("c", "write_file", "{\"path\":\"a"))));

        LegacyModelClient.Exchange exchange = new LegacyModelClient(llm)
                .exchange(List.of(LlmClient.Message.user("写")), null, event -> { });

        assertEquals(StopReason.MAX_TOKENS, exchange.response().stopReason());
    }
}
