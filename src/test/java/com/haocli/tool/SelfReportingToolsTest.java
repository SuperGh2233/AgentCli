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
    void webFetchBrowserAndEditReportTheirOwnStatus(@TempDir Path dir) throws Exception {
        ToolRegistry registry = registry(dir);

        ToolOutput denied = registry.executeToolOutput("web_fetch", "{\"url\":\"file:///etc/passwd\"}");
        assertEquals(ToolStatus.DENIED, denied.status(), "网络策略拒绝记为 DENIED: " + denied.text());
        assertTrue(denied.text().startsWith("❌ 网络访问被拒绝: "));

        ToolOutput blank = registry.executeToolOutput("web_fetch", "{\"url\":\" \"}");
        assertEquals(ToolOutput.FailureCode.INVALID_ARGUMENT, blank.failureCode());

        ToolOutput browser = registry.executeToolOutput("browser_status", "{}");
        assertEquals(ToolStatus.FAILED, browser.status());
        assertEquals("浏览器连接器未初始化，无法查看浏览器状态", browser.text());

        Files.writeString(dir.resolve("e.txt"), "hello world");
        ToolOutput edited = registry.executeToolOutput("edit_file",
                "{\"path\":\"e.txt\",\"old_text\":\"world\",\"new_text\":\"haocli\"}");
        assertEquals(ToolStatus.SUCCESS, edited.status());
        assertEquals("hello haocli", Files.readString(dir.resolve("e.txt")));
        ToolOutput missing = registry.executeToolOutput("edit_file",
                "{\"path\":\"e.txt\",\"old_text\":\"absent\",\"new_text\":\"x\"}");
        assertEquals(ToolStatus.FAILED, missing.status());
        assertEquals(ToolOutput.FailureCode.EDIT_SOURCE_NOT_FOUND, missing.failureCode(), "失败码照旧");
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
