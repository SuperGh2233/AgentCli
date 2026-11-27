package com.haocli.tool.builtin;

import com.haocli.tool.ToolOutput;
import com.haocli.tool.ToolRegistry;
import com.haocli.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 甲-3：list_dir、glob_files、grep_code 迁成独立的 Tool；给模型看的文字不变，失败由工具如实报告。 */
class SearchToolsStatusTest {

    private static ToolRegistry registry(Path projectDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectDir.toString());
        return registry;
    }

    @Test
    void listDirReportsMissingAndNonDirectoryAsFailures(@TempDir Path projectDir) throws Exception {
        Files.createDirectory(projectDir.resolve("src"));
        Files.writeString(projectDir.resolve("a.txt"), "x");
        ToolRegistry registry = registry(projectDir);

        ToolOutput ok = registry.executeToolOutput("list_dir", "{\"path\":\".\"}");
        assertEquals(ToolStatus.SUCCESS, ok.status());
        assertTrue(ok.text().startsWith("目录内容:\n"), ok.text());
        assertTrue(ok.text().contains("[D] src") && ok.text().contains("[F] a.txt"), ok.text());

        ToolOutput missing = registry.executeToolOutput("list_dir", "{\"path\":\"nope\"}");
        assertEquals(ToolStatus.FAILED, missing.status());
        assertEquals(ToolOutput.FailureCode.NOT_FOUND, missing.failureCode());
        assertEquals("目录为空或不存在", missing.text(), "给模型看的文字不变");

        ToolOutput file = registry.executeToolOutput("list_dir", "{\"path\":\"a.txt\"}");
        assertEquals(ToolOutput.FailureCode.INVALID_ARGUMENT, file.failureCode());
        assertEquals("目录为空或不存在", file.text());
    }

    @Test
    void listDirOutsideProjectIsDenied(@TempDir Path projectDir) {
        ToolOutput outside = registry(projectDir).executeToolOutput("list_dir", "{\"path\":\"../..\"}");
        assertEquals(ToolStatus.DENIED, outside.status(), outside.text());
    }

    @Test
    void globNoMatchIsSuccessButBlankPatternIsInvalid(@TempDir Path projectDir) throws Exception {
        Files.createDirectories(projectDir.resolve("src/main"));
        Files.writeString(projectDir.resolve("src/main/AService.java"), "class AService {}");
        ToolRegistry registry = registry(projectDir);

        ToolOutput hit = registry.executeToolOutput("glob_files", "{\"pattern\":\"**/*Service.java\"}");
        assertEquals(ToolStatus.SUCCESS, hit.status());
        assertEquals("匹配文件 1 个:\n1. src/main/AService.java", hit.text());

        ToolOutput none = registry.executeToolOutput("glob_files", "{\"pattern\":\"**/*.kt\"}");
        assertEquals(ToolStatus.SUCCESS, none.status(), "没找到也是有效结果");
        assertEquals("未找到匹配文件: **/*.kt", none.text());

        ToolOutput blank = registry.executeToolOutput("glob_files", "{\"pattern\":\"  \"}");
        assertEquals(ToolStatus.FAILED, blank.status());
        assertEquals(ToolOutput.FailureCode.INVALID_ARGUMENT, blank.failureCode());
        assertEquals("文件匹配失败: pattern 不能为空", blank.text());
    }

    @Test
    void grepHitNoMatchBlankAndBrokenRegex(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("A.java"), "class A {\n  int needle = 1;\n}\n");
        ToolRegistry registry = registry(projectDir);

        ToolOutput hit = registry.executeToolOutput("grep_code", "{\"pattern\":\"needle\"}");
        assertEquals(ToolStatus.SUCCESS, hit.status());
        assertTrue(hit.text().startsWith("匹配结果 1 条"), hit.text());
        assertTrue(hit.text().contains("A.java:2"), hit.text());
        assertTrue(hit.text().contains("suggested_reads:\n- read_file {\"path\":\"A.java\",\"offset\":1,\"limit\":80}"),
                hit.text());

        ToolOutput none = registry.executeToolOutput("grep_code", "{\"pattern\":\"haystack\"}");
        assertEquals(ToolStatus.SUCCESS, none.status());
        assertEquals("未找到匹配内容: haystack", none.text());

        ToolOutput blank = registry.executeToolOutput("grep_code", "{\"pattern\":\"\"}");
        assertEquals(ToolOutput.FailureCode.INVALID_ARGUMENT, blank.failureCode());
        assertEquals("代码搜索失败: pattern 不能为空", blank.text());

        ToolOutput broken = registry.executeToolOutput("grep_code", "{\"pattern\":\"(unclosed\",\"regex\":true}");
        assertEquals(ToolStatus.FAILED, broken.status(), broken.text());
        assertTrue(broken.text().startsWith("代码搜索失败: "), broken.text());
    }

    @Test
    void migratedToolsAreReadOnly() {
        com.fasterxml.jackson.databind.node.ObjectNode schema =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        assertTrue(new ListDirTool(schema).readOnly());
        assertTrue(new GlobFilesTool(schema).readOnly());
        assertTrue(new GrepCodeTool(schema).readOnly());
    }
}
