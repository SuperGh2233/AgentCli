package com.haocli.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.haocli.tool.Tool;
import com.haocli.tool.ToolArguments;
import com.haocli.tool.ToolContext;
import com.haocli.tool.ToolOutput;

import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/**
 * glob_files：按 glob 找项目内的文件。文字和迁移前一样；没找到算成功（结果为空也是答案），
 * pattern 为空 → FAILED + INVALID_ARGUMENT，遍历出错 → FAILED（以前都被算成成功）。
 */
public final class GlobFilesTool implements Tool {
    static final int MAX_RESULTS = 200;

    private final JsonNode parameters;

    public GlobFilesTool(JsonNode parameters) {
        this.parameters = parameters;
    }

    @Override
    public String name() {
        return "glob_files";
    }

    @Override
    public String description() {
        return "按文件名 glob 查找项目内文件（只读、实时、尊重常见忽略目录）；适合先定位候选文件，例如 **/*Service.java";
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
        String pattern = args.string("pattern");
        if (pattern == null || pattern.isBlank()) {
            return ToolOutput.failure(ToolOutput.FailureCode.INVALID_ARGUMENT, "文件匹配失败: pattern 不能为空");
        }
        Path root = context.resolvePath(args.string("path", "."));
        int maxResults = ProjectFiles.clamp(args.integer("max_results", 50), 1, MAX_RESULTS);
        Path projectRoot = context.projectRoot();
        PathMatcher matcher = projectRoot.getFileSystem()
                .getPathMatcher("glob:" + ProjectFiles.normalizeGlob(pattern));
        PathMatcher fileNameMatcher = projectRoot.getFileSystem()
                .getPathMatcher("glob:" + ProjectFiles.normalizeFileNameGlob(pattern));
        List<String> matches = new ArrayList<>();

        try {
            ProjectFiles.walk(root, projectRoot, path -> {
                if (matches.size() >= maxResults) {
                    return;
                }
                Path relative = projectRoot.relativize(path);
                if (matcher.matches(relative) || fileNameMatcher.matches(path.getFileName())) {
                    matches.add(relative.toString());
                }
            });
        } catch (Exception e) {
            return ToolOutput.failure("文件匹配失败: " + e.getMessage());
        }

        if (matches.isEmpty()) {
            return ToolOutput.text("未找到匹配文件: " + pattern);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配文件 ").append(matches.size()).append(" 个");
        if (matches.size() >= maxResults) {
            sb.append("（已达到上限 ").append(maxResults).append("）");
        }
        sb.append(":\n");
        for (int i = 0; i < matches.size(); i++) {
            sb.append(i + 1).append(". ").append(matches.get(i)).append("\n");
        }
        return ToolOutput.text(sb.toString().trim());
    }
}
