package com.haocli.sdk.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haocli.agent.HaoRuntime;
import com.haocli.agent.event.AgentEvent;
import com.haocli.headless.HeadlessOptions;
import com.haocli.headless.UnattendedPermissions;
import com.haocli.testing.ScriptedLlmClient;
import com.haocli.tool.Tool;
import com.haocli.tool.ToolArguments;
import com.haocli.tool.ToolContext;
import com.haocli.tool.ToolOutput;
import com.haocli.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.haocli.testing.ScriptedLlmClient.answer;
import static com.haocli.testing.ScriptedLlmClient.call;
import static com.haocli.testing.ScriptedLlmClient.toolCalls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HaoRuntime SDK 的验收示例：放在 com.haocli.agent 之外的包里，只用公开 API，不启动命令行、终端界面或 MCP，
 * 不调用真实模型（假模型按脚本回复）。这也是嵌入方的最小用法示范。
 */
@Timeout(20)
class HaoRuntimeExampleTest {

    /** 嵌入方自己的工具：按订单号查状态（只读）。 */
    static final class OrderStatusTool implements Tool {
        final List<String> queried = new ArrayList<>();

        public String name() { return "order_status"; }
        public String description() { return "按订单号查询订单状态"; }
        public JsonNode parameters() {
            try {
                return new ObjectMapper().readTree("""
                        {"type":"object","properties":{"order_id":{"type":"string","description":"订单号"}},
                         "required":["order_id"]}""");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        @Override public boolean readOnly() { return true; }
        public ToolOutput execute(ToolArguments args, ToolContext context) {
            String id = args.string("order_id");
            queried.add(id);
            return "42".equals(id) ? ToolOutput.text("订单 42：已发货")
                    : ToolOutput.failure(ToolOutput.FailureCode.NOT_FOUND, "订单不存在: " + id);
        }
    }

    @Test
    void embedderRunsAMultiStepToolTaskWithoutTheCli(@TempDir Path workspace) {
        OrderStatusTool tool = new OrderStatusTool();
        ScriptedLlmClient model = ScriptedLlmClient.of(
                toolCalls(call("c1", "order_status", "{\"order_id\":\"41\"}")),
                toolCalls(call("c2", "order_status", "{\"order_id\":\"42\"}")),
                answer("订单 42 已发货"));
        List<AgentEvent> events = new ArrayList<>();

        HaoRuntime runtime = HaoRuntime.builder()
                .model(model)
                .projectRoot(workspace)
                .tool(tool)
                .context(task -> "订单号都是数字；查不到时换相邻订单号再试一次")
                .onEvent(events::add)
                .build();
        HaoRuntime.RunResult result = runtime.run("查一下订单状态");

        assertEquals(HaoRuntime.Status.COMPLETED, result.status());
        assertEquals("订单 42 已发货", result.answer());
        assertNull(result.reason());
        assertEquals(3, result.modelRequests());
        assertEquals(List.of("41", "42"), tool.queried);
        assertTrue(model.request(1).get(0).content().contains("查不到时换相邻订单号"), "上下文提供者的内容进了系统提示词");
        List<ToolStatus> statuses = events.stream().filter(AgentEvent.ToolCallFinished.class::isInstance)
                .map(e -> ((AgentEvent.ToolCallFinished) e).status()).toList();
        assertEquals(List.of(ToolStatus.FAILED, ToolStatus.SUCCESS), statuses, "工具自己报告的状态原样进事件");
        assertTrue(events.get(events.size() - 1) instanceof AgentEvent.TurnFinished);
    }

    @Test
    void defaultPermissionsAreReadOnly(@TempDir Path workspace) throws Exception {
        ScriptedLlmClient model = ScriptedLlmClient.of(
                toolCalls(call("c1", "write_file", "{\"path\":\"x.txt\",\"content\":\"hi\"}")),
                answer("写不了"));
        List<AgentEvent> events = new ArrayList<>();
        HaoRuntime runtime = HaoRuntime.builder().model(model).projectRoot(workspace).onEvent(events::add).build();

        assertEquals(HaoRuntime.Status.COMPLETED, runtime.run("写个文件").status());
        assertFalse(Files.exists(workspace.resolve("x.txt")));
        assertTrue(model.toolRequests().get(0).stream().noneMatch(t -> t.name().equals("write_file")),
                "只读权限下不暴露写文件工具");
        assertTrue(events.stream().anyMatch(e -> e instanceof AgentEvent.ToolCallFinished f
                && f.status() == ToolStatus.DENIED), "模型硬要调用也会被确定性策略拒绝");

        ScriptedLlmClient model2 = ScriptedLlmClient.of(
                toolCalls(call("c1", "write_file", "{\"path\":\"x.txt\",\"content\":\"hi\"}")),
                answer("写好了"));
        HaoRuntime editing = HaoRuntime.builder().model(model2).projectRoot(workspace)
                .permissions(new UnattendedPermissions(HeadlessOptions.PermissionMode.EDIT, List.of())).build();
        assertEquals("写好了", editing.run("写个文件").answer());
        assertEquals("hi", Files.readString(workspace.resolve("x.txt")));
    }

    @Test
    void cancelStopsTheRunAndSandboxIsRejected(@TempDir Path workspace) {
        ScriptedLlmClient model = ScriptedLlmClient.of(toolCalls(call("c1", "list_dir", "{\"path\":\".\"}")),
                answer("不会到这里"));
        HaoRuntime[] holder = new HaoRuntime[1];
        model.onRequest((number, messages) -> holder[0].cancel());
        holder[0] = HaoRuntime.builder().model(model).projectRoot(workspace).build();

        HaoRuntime.RunResult result = holder[0].run("列目录");
        assertEquals(HaoRuntime.Status.CANCELLED, result.status());

        assertThrows(IllegalArgumentException.class, () -> HaoRuntime.builder().model(model).projectRoot(workspace)
                .permissions(new UnattendedPermissions(HeadlessOptions.PermissionMode.SANDBOX, List.of())).build());
    }
}
