package com.haocli.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.haocli.tool.Tool;
import com.haocli.tool.ToolArguments;
import com.haocli.tool.ToolContext;
import com.haocli.tool.ToolOutput;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * list_dir：列出项目内一个目录的直接子项。文字和迁移前一样；状态改由工具报告：
 * 路径不存在 → FAILED + NOT_FOUND，不是目录 → FAILED + INVALID_ARGUMENT（以前这两种都被算成成功），
 * 列目录出错 → FAILED。越界照旧由 PathGuard 抛异常，调度层记为 DENIED。
 */
public final class ListDirTool implements Tool {
    private final JsonNode parameters;

    public ListDirTool(JsonNode parameters) {
        this.parameters = parameters;
    }

    @Override
    public String name() {
        return "list_dir";
    }

    @Override
    public String description() {
        return "列出目录内容（仅限项目根目录之内）";
    }

    @Override
    public JsonNode parameters() {
        return parameters;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolOutput execute(ToolArguments args, ToolContext context) {
        Path safe = context.resolvePath(args.string("path"));
        try {
            File[] files = safe.toFile().listFiles();
            if (files == null) {
                return ToolOutput.failure(
                        Files.exists(safe) ? ToolOutput.FailureCode.INVALID_ARGUMENT : ToolOutput.FailureCode.NOT_FOUND,
                        "目录为空或不存在");
            }
            StringBuilder sb = new StringBuilder("目录内容:\n");
            for (File f : files) {
                sb.append(f.isDirectory() ? "[D] " : "[F] ")
                        .append(f.getName())
                        .append("\n");
            }
            return ToolOutput.text(sb.toString());
        } catch (Exception e) {
            return ToolOutput.failure("列出目录失败: " + e.getMessage());
        }
    }
}
