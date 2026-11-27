package com.haocli.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.haocli.tool.Tool;
import com.haocli.tool.ToolArguments;
import com.haocli.tool.ToolContext;
import com.haocli.tool.ToolOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * read_file：读项目内的文件，可按行分页。给模型看的文字和迁移前完全一样，只是状态改由工具自己报告：
 * 文件不存在 → FAILED + NOT_FOUND；不是普通文件、offset 超出行数 → FAILED + INVALID_ARGUMENT；
 * 读取出错 → FAILED。
 */
public final class ReadFileTool implements Tool {
    public static final int MAX_LINES = 2_000;
    private static final int DEFAULT_LIMIT = 200;

    private final JsonNode parameters;

    public ReadFileTool(JsonNode parameters) {
        this.parameters = parameters;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return "读取文件内容（仅限项目根目录之内）；可用 offset/limit 按行读取，避免把大文件整段塞进上下文";
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
            return read(safe, args);
        } catch (Exception e) {
            return ToolOutput.failure("读取文件失败: " + e.getMessage());
        }
    }

    /** 也给 load_skill 读参考文件用；那边只取文字。 */
    public static ToolOutput read(Path file, ToolArguments args) throws IOException {
        if (!Files.isRegularFile(file)) {
            return ToolOutput.failure(
                    Files.exists(file) ? ToolOutput.FailureCode.INVALID_ARGUMENT : ToolOutput.FailureCode.NOT_FOUND,
                    "读取文件失败: 不是普通文件");
        }
        boolean ranged = args.has("offset") || args.has("limit");
        if (!ranged) {
            return ToolOutput.text("文件内容:\n" + Files.readString(file));
        }

        int offset = Math.max(1, args.integer("offset", 1));
        int limit = Math.max(1, Math.min(args.integer("limit", DEFAULT_LIMIT), MAX_LINES));
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        int total = lines.size();
        if (offset > total) {
            return ToolOutput.failure(ToolOutput.FailureCode.INVALID_ARGUMENT,
                    "文件内容: " + file.getFileName() + " 共 " + total + " 行，offset 超出范围");
        }

        int from = offset - 1;
        int to = Math.min(from + limit, total);
        StringBuilder sb = new StringBuilder();
        sb.append("文件内容: ").append(file.getFileName())
                .append(" (lines ").append(offset).append("-").append(to)
                .append(" of ").append(total).append(")\n");
        for (int i = from; i < to; i++) {
            sb.append(String.format("%5d | %s%n", i + 1, lines.get(i)));
        }
        if (to < total) {
            sb.append("...(已截断，可用 offset=").append(to + 1).append(" 继续读取)");
        }
        return ToolOutput.text(sb.toString().trim());
    }
}
