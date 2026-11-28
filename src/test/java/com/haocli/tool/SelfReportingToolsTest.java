package com.haocli.tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 甲阶段第二批：write_file、create_project、search_code、save_memory、revert_turn 自己报告状态。
 * 这些工具的失败文字（“写入文件失败”“创建项目失败”“代码检索失败”“保存长期记忆失败”“恢复快照失败”）
 * 不在旧的前缀名单里，以前都被算成成功。给模型看的文字不变。
 */
class SelfReportingToolsTest {

    private static ToolRegistry registry(Path dir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(dir.toString());
        return registry;
    }

    @Test
    void writeFileReportsSuccessAndRealFailures(@TempDir Path dir) throws Exception {
        ToolRegistry registry = registry(dir);

        ToolOutput ok = registry.executeToolOutput("write_file", "{\"path\":\"a/b.txt\",\"content\":\"hi\"}");
        assertEquals(ToolStatus.SUCCESS, ok.status());
        assertEquals("文件已写入: a/b.txt", ok.text());

        Files.createDirectory(dir.resolve("adir"));
        ToolOutput failed = registry.executeToolOutput("write_file", "{\"path\":\"adir\",\"content\":\"hi\"}");
        assertEquals(ToolStatus.FAILED, failed.status(), failed.text());
        assertTrue(failed.text().startsWith("写入文件失败: "), "给模型看的文字不变: " + failed.text());
    }

    @Test
    void createProjectFailureIsAFailure(@TempDir Path dir) throws Exception {
        ToolRegistry registry = registry(dir);
        assertEquals(ToolStatus.SUCCESS,
                registry.executeToolOutput("create_project", "{\"name\":\"demo\",\"type\":\"node\"}").status());

        Files.writeString(dir.resolve("blocked"), "a file, not a directory");
        ToolOutput failed = registry.executeToolOutput("create_project", "{\"name\":\"blocked\",\"type\":\"node\"}");
        assertEquals(ToolStatus.FAILED, failed.status(), failed.text());
        assertTrue(failed.text().startsWith("创建项目失败: "));
    }

    @Test
    void saveMemoryValidatesAndReportsMissingWriter(@TempDir Path dir) {
        ToolRegistry registry = registry(dir);

        ToolOutput blank = registry.executeToolOutput("save_memory", "{\"fact\":\"   \"}");
        assertEquals(ToolStatus.FAILED, blank.status());
        assertEquals(ToolOutput.FailureCode.INVALID_ARGUMENT, blank.failureCode());
        assertEquals("保存长期记忆失败: fact 不能为空", blank.text());

        ToolOutput noWriter = registry.executeToolOutput("save_memory", "{\"fact\":\"用中文回复\"}");
        assertEquals(ToolStatus.FAILED, noWriter.status());
        assertEquals("保存长期记忆失败: 记忆保存器未初始化", noWriter.text());

        registry.setMemoryWriter((fact, scope, replaceId, keepBoth) -> "检测到冲突，请用户选择");
        ToolOutput conflict = registry.executeToolOutput("save_memory", "{\"fact\":\"用中文回复\"}");
        assertEquals(ToolStatus.SUCCESS, conflict.status(), "冲突待用户选择是正常结果，不是失败");
    }

    @Test
    void searchCodeWithoutIndexIsAFailureNotAnEmptySuccess(@TempDir Path dir) {
        String previous = System.getProperty("haocli.rag.dir");
        System.setProperty("haocli.rag.dir", dir.resolve("rag").toString());
        try {
            ToolOutput output = registry(dir).executeToolOutput("search_code", "{\"query\":\"登录\"}");
            assertEquals(ToolStatus.FAILED, output.status(), output.text());
        } finally {
            if (previous == null) System.clearProperty("haocli.rag.dir");
            else System.setProperty("haocli.rag.dir", previous);
        }
    }
}
