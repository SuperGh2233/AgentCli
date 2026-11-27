package com.haocli.headless;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.haocli.agent.event.AgentEvent;
import com.haocli.tool.ToolResultOffloader;
import com.haocli.tool.ToolStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 上下文处理策略各层的统计：从事件累计，写进结果 JSON 和 stream-json。 */
class HarnessMetricsContextStatsTest {

    @Test
    void countsEachLayerFromEvents() {
        HarnessMetrics.Collector collector = new HarnessMetrics.Collector();
        collector.onEvent(new AgentEvent.ToolCallFinished("1", "execute_command", true,
                ToolResultOffloader.OFFLOAD_MARKER + "\ntool: execute_command", ToolStatus.SUCCESS));
        collector.onEvent(new AgentEvent.ToolCallFinished("2", "read_file", true, "小结果", ToolStatus.SUCCESS));
        collector.onEvent(new AgentEvent.ContextCompacted("automatic", 2, 0, 0, false, 50_000, 40_000));
        collector.onEvent(new AgentEvent.ContextCompacted("automatic", 1, 3, 4, false, 60_000, 45_000));
        collector.onEvent(new AgentEvent.ContextCompacted("emergency", 0, 0, 0, true, 90_000, 20_000));

        HarnessMetrics.ContextStats stats = collector.contextStats();

        assertEquals(new HarnessMetrics.ContextStats(1, 2, 3, 1, 3, 1, 4, 1, 1, 10_000 + 15_000 + 70_000), stats);
        ObjectNode node = AgentEventJson.MAPPER.createObjectNode();
        stats.writeTo(node);
        assertEquals(2, node.path("context").path("trim_runs").asInt());
        assertEquals(95_000, node.path("context").path("tokens_saved").asLong());
    }

    @Test
    void compactionEventAndTurnReasonAreWrittenToStreamJson() {
        ObjectNode compacted = AgentEventJson.toJson(
                new AgentEvent.ContextCompacted("automatic", 1, 2, 3, true, 1000, 400));
        assertEquals("context_compacted", compacted.path("type").asText());
        assertEquals("automatic", compacted.path("trigger").asText());
        assertEquals(3, compacted.path("cleared_tool_results").asInt());
        assertEquals(true, compacted.path("summarized").asBoolean());
        assertEquals(400, compacted.path("after_tokens").asLong());

        ObjectNode failed = AgentEventJson.toJson(new AgentEvent.TurnFinished(
                "t", AgentEvent.TurnStatus.FAILED, "x", 1, 1, 0, 0, 0, "llm_billing"));
        assertEquals("llm_billing", failed.path("reason").asText());
        ObjectNode completed = AgentEventJson.toJson(new AgentEvent.TurnFinished(
                "t", AgentEvent.TurnStatus.COMPLETED, "x", 1, 1, 0, 0, 0));
        assertEquals(false, completed.has("reason"), "完成时不写 reason，旧格式不变");
    }
}
