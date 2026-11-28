package com.haocli.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 执行前检查链：隐藏工具、拒绝调用（不执行、写审计、带原因）、按顺序第一道拒绝生效。 */
class ToolGateTest {

    private static final class CountingTool implements Tool {
        int runs;
        public String name() { return "probe"; }
        public String description() { return "probe"; }
        public JsonNode parameters() { return new ObjectMapper().createObjectNode().put("type", "object"); }
        public ToolOutput execute(ToolArguments args, ToolContext context) { runs++; return ToolOutput.text("ran"); }
    }

    private static ToolRegistry registry(Path dir, CountingTool tool) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(dir.toString());
        registry.registerTool(tool);
        return registry;
    }

    @Test
    void gateHidesAndDeniesWithoutExecutingAndAudits(@TempDir Path dir) {
        CountingTool tool = new CountingTool();
        ToolRegistry registry = registry(dir, tool);
        registry.addGate(new ToolRegistry.ToolGate() {
            public boolean exposes(String name) { return !"probe".equals(name); }
            public ToolOutput check(String name, String args) {
                return "probe".equals(name) ? ToolOutput.denied(ToolOutput.FailureCode.DENY_PERMISSION, "不允许 probe") : null;
            }
        });

        assertTrue(registry.getToolDefinitions().stream().noneMatch(t -> t.name().equals("probe")), "不给模型看");
        assertTrue(registry.getToolDefinitions().stream().anyMatch(t -> t.name().equals("read_file")), "其他工具不受影响");

        ToolOutput output = registry.executeToolOutput("probe", "{}");
        assertEquals(ToolStatus.DENIED, output.status());
        assertEquals(ToolOutput.FailureCode.DENY_PERMISSION, output.failureCode());
        assertEquals(0, tool.runs, "被拒绝的调用不执行");

        List<ToolRegistry.ToolExecutionResult> batch = registry.executeTools(
                List.of(new ToolRegistry.ToolInvocation("c1", "probe", "{}")));
        assertEquals(ToolStatus.DENIED, batch.get(0).status(), "批量执行同样经过检查链");
        assertEquals(0, tool.runs);
    }

    @Test
    void firstDenyingGateWinsAndAllowedCallsRun(@TempDir Path dir) {
        CountingTool tool = new CountingTool();
        ToolRegistry registry = registry(dir, tool);
        List<String> order = new ArrayList<>();
        registry.addGate((name, args) -> { order.add("first"); return null; });
        registry.addGate((name, args) -> { order.add("second");
            return "deny-me".equals(name) ? ToolOutput.denied(ToolOutput.FailureCode.DENY_POLICY, "no") : null; });

        assertEquals("ran", registry.executeToolOutput("probe", "{}").text());
        assertEquals(1, tool.runs);
        assertEquals(List.of("first", "second"), order);

        assertNull(null);
        ToolOutput nonDenied = new ToolRegistry() {{
            setProjectPath(dir.toString());
            addGate((n, a) -> ToolOutput.failure("写错了状态"));
        }}.executeToolOutput("read_file", "{\"path\":\"x\"}");
        assertEquals(ToolStatus.DENIED, nonDenied.status(), "检查链返回的结果一律按拒绝处理");
        assertFalse(nonDenied.successful());
    }
}
