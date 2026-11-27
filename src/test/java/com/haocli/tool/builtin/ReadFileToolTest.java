package com.haocli.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haocli.tool.Tool;
import com.haocli.tool.ToolArguments;
import com.haocli.tool.ToolContext;
import com.haocli.tool.ToolOutput;
import com.haocli.tool.ToolRegistry;
import com.haocli.tool.ToolRegistry.ToolExecutionResult;
import com.haocli.tool.ToolRegistry.ToolInvocation;
import com.haocli.tool.ToolStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 甲-2：read_file 迁成独立的 Tool，状态由工具自己报告，给模型看的文字不变。 */
class ReadFileToolTest {

    private static ToolRegistry registry(Path projectDir) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(projectDir.toString());
        return registry;
    }

    @Test
    void fullAndRangedReadsKeepTheirText(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "one\ntwo\nthree\n");
        ToolRegistry registry = registry(projectDir);

        ToolOutput full = registry.executeToolOutput("read_file", "{\"path\":\"a.txt\"}");
        assertEquals(ToolStatus.SUCCESS, full.status());
        assertEquals("文件内容:\none\ntwo\nthree\n", full.text());

        ToolOutput ranged = registry.executeToolOutput("read_file", "{\"path\":\"a.txt\",\"offset\":2,\"limit\":1}");
        assertEquals(ToolStatus.SUCCESS, ranged.status());
        assertEquals("文件内容: a.txt (lines 2-2 of 3)\n    2 | two\n...(已截断，可用 offset=3 继续读取)", ranged.text());
    }

    @Test
    void offsetPastEndIsInvalidArgumentWithUnchangedText(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "one\ntwo\n");

        ToolOutput output = registry(projectDir).executeToolOutput("read_file", "{\"path\":\"a.txt\",\"offset\":9}");

        assertEquals(ToolStatus.FAILED, output.status());
        assertEquals(ToolOutput.FailureCode.INVALID_ARGUMENT, output.failureCode());
        assertEquals("文件内容: a.txt 共 2 行，offset 超出范围", output.text());
    }

    @Test
    void missingFileIsNotFoundAndDirectoryIsInvalidArgument(@TempDir Path projectDir) throws Exception {
        Files.createDirectory(projectDir.resolve("dir"));
        ToolRegistry registry = registry(projectDir);

        ToolOutput missing = registry.executeToolOutput("read_file", "{\"path\":\"nope.txt\"}");
        assertEquals(ToolStatus.FAILED, missing.status());
        assertEquals(ToolOutput.FailureCode.NOT_FOUND, missing.failureCode());
        assertEquals("读取文件失败: 不是普通文件", missing.text());

        ToolOutput directory = registry.executeToolOutput("read_file", "{\"path\":\"dir\"}");
        assertEquals(ToolOutput.FailureCode.INVALID_ARGUMENT, directory.failureCode());
        assertEquals("读取文件失败: 不是普通文件", directory.text());
    }

    @Test
    void batchPathKeepsTheToolReportedStatus(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "one\n");

        List<ToolExecutionResult> results = registry(projectDir).executeTools(List.of(
                new ToolInvocation("c1", "read_file", "{\"path\":\"a.txt\",\"offset\":5}"),
                new ToolInvocation("c2", "read_file", "{\"path\":\"a.txt\"}")));

        assertEquals(ToolStatus.FAILED, results.get(0).status());
        assertEquals(ToolOutput.FailureCode.INVALID_ARGUMENT, results.get(0).failureCode());
        assertEquals(ToolStatus.SUCCESS, results.get(1).status());
    }

    @Test
    void readOnlyAndResolvesThroughContext(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("a.txt"), "hi");
        ReadFileTool tool = new ReadFileTool(new ObjectMapper().createObjectNode());
        ToolContext context = new ToolContext() {
            public Path projectRoot() { return projectDir; }
            public Path resolvePath(String raw) { return projectDir.resolve(raw); }
            public boolean cancelled() { return false; }
        };

        assertTrue(tool.readOnly());
        assertEquals("read_file", tool.name());
        assertEquals("文件内容:\nhi", tool.execute(new ToolArguments(Map.of("path", "a.txt")), context).text());
    }

    @Test
    void customToolsRegisterThroughTheToolInterface(@TempDir Path projectDir) {
        ToolRegistry registry = registry(projectDir);
        JsonNode schema = new ObjectMapper().createObjectNode().put("type", "object");
        registry.registerTool(new Tool() {
            public String name() { return "echo_root"; }
            public String description() { return "echo"; }
            public JsonNode parameters() { return schema; }
            public ToolOutput execute(ToolArguments args, ToolContext context) {
                return ToolOutput.denied("root=" + context.projectRoot().getFileName());
            }
        });

        ToolOutput output = registry.executeToolOutput("echo_root", "{}");
        assertEquals(ToolStatus.DENIED, output.status(), "自定义工具报告的状态原样保留");
        assertTrue(output.text().startsWith("root="));
        assertTrue(registry.getToolDefinitions().stream().anyMatch(t -> t.name().equals("echo_root")));

        assertThrows(IllegalArgumentException.class, () -> registry.registerTool(new Tool() {
            public String name() { return "mcp__x__y"; }
            public String description() { return ""; }
            public JsonNode parameters() { return schema; }
            public ToolOutput execute(ToolArguments args, ToolContext context) { return ToolOutput.text(""); }
        }));
    }

    @Test
    void argumentsParseLikeTheOldRegistryHelpers() {
        ToolArguments args = new ToolArguments(Map.of("n", " 7 ", "bad", "x", "flag", "yes", "one", "1"));

        assertEquals(7, args.integer("n", 0));
        assertEquals(3, args.integer("bad", 3));
        assertEquals(3, args.integer("absent", 3));
        assertTrue(args.bool("flag", false));
        assertTrue(args.bool("one", false));
        assertFalse(args.bool("absent", false));
        assertFalse(args.has("absent"));
    }
}
