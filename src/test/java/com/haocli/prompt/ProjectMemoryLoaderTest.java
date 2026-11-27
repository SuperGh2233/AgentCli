package com.haocli.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsUserProjectAndLocalMemoryInOrder() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot.resolve(".haocli"));
        Files.writeString(userDir.resolve("HAO.md"), "- user rule");
        Files.writeString(projectRoot.resolve("HAO.md"), "- project rule");
        Files.writeString(projectRoot.resolve(".haocli").resolve("HAO.md"), "- dot project rule");
        Files.writeString(projectRoot.resolve("HAO.local.md"), "- local rule");

        String context = new ProjectMemoryLoader(userDir, projectRoot).loadForPrompt();

        assertTrue(context.contains("## HAO.md 项目记忆"));
        assertTrue(context.indexOf("user rule") < context.indexOf("project rule"));
        assertTrue(context.indexOf("project rule") < context.indexOf("dot project rule"));
        assertTrue(context.indexOf("dot project rule") < context.indexOf("local rule"));
    }

    @Test
    void expandsRelativeImportsInsideAllowedRootOnly() throws Exception {
        Path userDir = tempDir.resolve("user");
        Path projectRoot = tempDir.resolve("project");
        Files.createDirectories(userDir);
        Files.createDirectories(projectRoot.resolve("docs"));
        Files.writeString(projectRoot.resolve("docs").resolve("rules.md"), "- imported rule");
        Files.writeString(projectRoot.resolve("HAO.md"), """
                @docs/rules.md
                @../outside.md
                - root rule
                """);
        Files.writeString(tempDir.resolve("outside.md"), "- outside rule");

        String context = new ProjectMemoryLoader(userDir, projectRoot).loadForPrompt();

        assertTrue(context.contains("- imported rule"));
        assertTrue(context.contains("- root rule"));
        assertFalse(context.contains("- outside rule"));
    }

    @Test
    void returnsEmptyContextWhenNoMemoryFilesExist() {
        String context = new ProjectMemoryLoader(tempDir.resolve("missing-user"), tempDir.resolve("missing-project"))
                .loadForPrompt();

        assertTrue(context.isEmpty());
    }

    @Test
    void overBudgetMemoryShowsAnOutlineInsteadOfCuttingBlindly() throws Exception {
        Path projectRoot = tempDir.resolve("big");
        Files.createDirectories(projectRoot);
        StringBuilder text = new StringBuilder();
        for (int section = 1; section <= 40; section++) {
            text.append("## 第 ").append(section).append(" 节\n");
            for (int line = 0; line < 20; line++) {
                text.append("- 规则 ").append(section).append('.').append(line).append(" ").append("说明".repeat(10)).append('\n');
            }
        }
        Files.writeString(projectRoot.resolve("HAO.md"), text.toString());

        String context = new ProjectMemoryLoader(null, projectRoot).loadForPrompt();

        assertTrue(context.length() <= 24_000 + 200, "仍在预算附近: " + context.length());
        assertTrue(context.contains("## 第 1 节"), "开头部分原样放进来");
        assertTrue(context.contains("### 未展开的部分"));
        assertTrue(context.contains("read_file"));
        assertTrue(context.matches("(?s).*HAO\\.md 第 \\d+ 行：## 第 40 节.*"), "最后一节出现在目录里，带行号");
        assertFalse(context.contains("字符预算截断"), "不再生硬截断");
    }

    @Test
    void memoryWithinBudgetHasNoOutline() throws Exception {
        Path projectRoot = tempDir.resolve("small");
        Files.createDirectories(projectRoot);
        Files.writeString(projectRoot.resolve("HAO.md"), "## 规则\n- 用中文回答");

        String context = new ProjectMemoryLoader(null, projectRoot).loadForPrompt();

        assertTrue(context.contains("用中文回答"));
        assertFalse(context.contains("未展开的部分"));
    }
}
