package com.paicli.headless;

import com.paicli.agent.Agent;
import com.paicli.testing.ScriptedLlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.paicli.testing.ScriptedLlmClient.answer;
import static com.paicli.testing.ScriptedLlmClient.call;
import static com.paicli.testing.ScriptedLlmClient.toolCalls;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runtime API / 后台任务执行器：白名单生效，流式回答也能拿到。 */
@Timeout(20)
class UnattendedTaskRunnerTest {

    private static UnattendedTaskRunner runner(ScriptedLlmClient llm, Path projectDir, UnattendedPermissions permissions) {
        return new UnattendedTaskRunner(() -> llm, projectDir, permissions, null);
    }

    @Test
    void streamedAnswerIsReturnedToTheCaller(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.of(answer("42")).streamingContent();

        assertEquals("42", runner(llm, projectDir, UnattendedPermissions.READ_ONLY).run("答案是多少"));
    }

    @Test
    void oldBareAgentPathReturnedEmptyForStreamedAnswers(@TempDir Path projectDir) {
        // 记录修复前的问题：原 Main.runHeadlessTask 直接返回 agent.run()，流式输出时是空串。
        ScriptedLlmClient llm = ScriptedLlmClient.of(answer("42")).streamingContent();
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectDir.toString());

        assertEquals("", new Agent(llm, registry).run("答案是多少"));
    }

    @Test
    void defaultPermissionsRefuseCommandsAndWrites(@TempDir Path projectDir) {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_cmd", "execute_command", "{\"command\":\"touch pwned.txt\"}")),
                toolCalls(call("call_w", "write_file", "{\"path\":\"x.txt\",\"content\":\"x\"}")),
                answer("都没成功"));

        String result = runner(llm, projectDir, UnattendedPermissions.READ_ONLY).run("执行命令并写文件");

        assertEquals("都没成功", result);
        assertFalse(Files.exists(projectDir.resolve("pwned.txt")), "默认不能执行命令");
        assertFalse(Files.exists(projectDir.resolve("x.txt")), "默认不能写文件");
        assertTrue(llm.toolRequests().get(0).stream().noneMatch(tool -> "execute_command".equals(tool.name())));
    }

    @Test
    void editModeCanWriteFiles(@TempDir Path projectDir) throws Exception {
        ScriptedLlmClient llm = ScriptedLlmClient.of(
                toolCalls(call("call_w", "write_file", "{\"path\":\"x.txt\",\"content\":\"hello\"}")),
                answer("写好了"));

        runner(llm, projectDir, new UnattendedPermissions(HeadlessOptions.PermissionMode.EDIT, List.of()))
                .run("写 x.txt");

        assertEquals("hello", Files.readString(projectDir.resolve("x.txt")));
    }

    @Test
    void permissionsAreParsedFromServeArguments() {
        UnattendedPermissions parsed = UnattendedPermissions.fromArgs(new String[]{
                "serve", "--http", "--port", "9000", "--permission-mode", "edit",
                "--allow-command", "git status", "--allow-command", " mvn -q test "});

        assertEquals(HeadlessOptions.PermissionMode.EDIT, parsed.mode());
        assertEquals(List.of("git status", "mvn -q test"), parsed.allowedCommands());
        assertEquals(UnattendedPermissions.READ_ONLY,
                UnattendedPermissions.fromArgs(new String[]{"serve", "--http"}), "不给参数时默认只读、不执行命令");
        assertThrows(IllegalArgumentException.class,
                () -> UnattendedPermissions.fromArgs(new String[]{"serve", "--http", "--permission-mode", "all"}));
        assertThrows(IllegalArgumentException.class,
                () -> UnattendedPermissions.fromArgs(new String[]{"serve", "--allow-command"}));
    }

    @Test
    void taskModeComesFromConfigValue() {
        assertEquals(UnattendedPermissions.READ_ONLY, UnattendedPermissions.fromModeValue(null));
        assertEquals(UnattendedPermissions.READ_ONLY, UnattendedPermissions.fromModeValue("read-only"));
        assertEquals(HeadlessOptions.PermissionMode.EDIT, UnattendedPermissions.fromModeValue(" edit ").mode());
        assertThrows(IllegalArgumentException.class, () -> UnattendedPermissions.fromModeValue("yolo"));
    }
}
