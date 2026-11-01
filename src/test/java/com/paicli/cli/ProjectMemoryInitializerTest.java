package com.paicli.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectMemoryInitializerTest {

    @TempDir
    Path tempDir;

    @Test
    void generatesConcisePaiCliProjectMemory() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# PaiCLI\n\nJava Agent CLI");
        Files.writeString(tempDir.resolve("AGENTS.md"), "项目名：PaiCLI\n改命令入口要联动");
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, false);

        String content = Files.readString(tempDir.resolve("PAI.md"));
        assertTrue(result.written());
        assertTrue(content.contains("# PAI.md"));
        assertTrue(content.contains("PaiCLI 是面向商业使用的 Java Agent CLI 产品"));
        assertTrue(content.contains("mvn test -Pquick"));
        assertTrue(content.contains("不要为某个模式创建孤立能力"));
        assertTrue(content.lines().count() < 45, content);
    }

    @Test
    void generatesHaoCliProjectMemoryAsIndependentProject() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# Hao CLI\n\nJava Agent CLI");
        Files.writeString(tempDir.resolve("AGENTS.md"), "项目名：PaiCLI\n改命令入口要联动");
        Files.writeString(tempDir.resolve("pom.xml"), "<project></project>");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, false);

        String content = Files.readString(tempDir.resolve("PAI.md"));
        assertTrue(result.written());
        assertTrue(content.contains("## What This Is\n\nHao CLI 是"), content);
        assertTrue(content.contains("Hao CLI 是独立开发的 Java Agent CLI"), content);
        assertFalse(content.contains("衍生开发"), content);
        assertFalse(content.contains("面向商业使用"), content);
        assertFalse(content.contains("对标 Claude Code"), content);
        assertTrue(content.contains("mvn test -Pquick"));
        assertTrue(content.contains("不要为某个模式创建孤立能力"));
        assertTrue(content.lines().count() < 45, content);
    }

    @Test
    void doesNotOverwriteExistingFileWithoutForce() throws Exception {
        Files.writeString(tempDir.resolve("PAI.md"), "existing");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, false);

        assertFalse(result.written());
        assertTrue(Files.readString(tempDir.resolve("PAI.md")).equals("existing"));
    }

    @Test
    void forceOverwritesExistingFile() throws Exception {
        Files.writeString(tempDir.resolve("README.md"), "# PaiCLI\n");
        Files.writeString(tempDir.resolve("PAI.md"), "existing");

        ProjectMemoryInitializer.InitResult result = ProjectMemoryInitializer.initialize(tempDir, true);

        assertTrue(result.written());
        assertTrue(Files.readString(tempDir.resolve("PAI.md")).contains("# PAI.md"));
    }
}
