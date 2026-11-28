package com.haocli.tool;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.haocli.agent.event.AgentEvent;
import com.haocli.headless.AgentEventJson;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 拒绝结果带上是哪一层确定性检查做的决定（FailureCode.DENY_*），写进事件 JSON 的 reason。
 * 拦截行为和给模型看的文字都不变，只是多了可统计的原因。
 */
class DenialReasonTest {

    private static ToolRegistry registry(Path dir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(dir.toString());
        return registry;
    }

    @Test
    void eachDeterministicCheckReportsItsOwnReason(@TempDir Path root) throws Exception {
        Path project = Files.createDirectories(root.resolve("project"));
        Files.writeString(root.resolve("secret.txt"), "secret");
        ToolRegistry registry = registry(project);

        ToolOutput path = registry.executeToolOutput("read_file", "{\"path\":\"../secret.txt\"}");
        assertEquals(ToolStatus.DENIED, path.status());
        assertEquals(ToolOutput.FailureCode.DENY_PATH, path.failureCode());
        assertTrue(path.text().startsWith("🛡️ 策略拒绝: "), "文字不变");

        ToolOutput command = registry.executeToolOutput("execute_command", "{\"command\":\"sudo -n true\"}");
        assertEquals(ToolStatus.DENIED, command.status());
        assertEquals(ToolOutput.FailureCode.DENY_COMMAND, command.failureCode());

        String huge = "x".repeat(5 * 1024 * 1024 + 1);
        ToolOutput limit = registry.executeToolOutput("write_file",
                "{\"path\":\"big.txt\",\"content\":\"" + huge + "\"}");
        assertEquals(ToolOutput.FailureCode.DENY_LIMIT, limit.failureCode());
        assertFalse(Files.exists(project.resolve("big.txt")));

        ToolOutput network = registry.executeToolOutput("web_fetch", "{\"url\":\"file:///etc/passwd\"}");
        assertEquals(ToolOutput.FailureCode.DENY_NETWORK, network.failureCode());
    }

    @Test
    void reasonIsWrittenToEventJsonOnlyWhenPresent() {
        ObjectNode denied = AgentEventJson.toJson(new AgentEvent.ToolCallFinished("c1", "read_file", false,
                "🛡️ 策略拒绝: 越界", ToolStatus.DENIED, ToolOutput.FailureCode.DENY_PATH));
        assertEquals("denied", denied.path("status").asText());
        assertEquals("deny_path", denied.path("reason").asText());

        ObjectNode ok = AgentEventJson.toJson(new AgentEvent.ToolCallFinished("c2", "read_file", true,
                "文件内容", ToolStatus.SUCCESS));
        assertFalse(ok.has("reason"), "成功时不写 reason，旧格式不变");
    }

    @Test
    void plainDeniedDefaultsToGenericPolicyReason() {
        assertEquals(ToolOutput.FailureCode.DENY_POLICY, ToolOutput.denied("被拒绝").failureCode());
        assertEquals(ToolStatus.DENIED, ToolOutput.denied(ToolOutput.FailureCode.DENY_HUMAN, "x").status());
    }
}
