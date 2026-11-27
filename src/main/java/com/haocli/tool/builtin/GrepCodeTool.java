package com.haocli.tool.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.haocli.tool.Tool;
import com.haocli.tool.ToolArguments;
import com.haocli.tool.ToolContext;
import com.haocli.tool.ToolOutput;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * grep_code：在项目内按关键字或正则搜代码（优先 ripgrep，没有就用 Java 扫描）。文字和迁移前一样；
 * 没找到算成功，pattern 为空 → FAILED + INVALID_ARGUMENT，搜索本身出错且没有任何命中（比如正则写错）→ FAILED
 * （以前都被算成成功）。
 */
public final class GrepCodeTool implements Tool {
    static final int MAX_RESULTS = 200;
    static final int MAX_CONTEXT_LINES = 5;
    static final int DEFAULT_MAX_CHARS = 24_000;
    static final int MAX_MAX_CHARS = 60_000;
    static final int DEFAULT_HEAD_LIMIT = 20;

    private final JsonNode parameters;

    public GrepCodeTool(JsonNode parameters) {
        this.parameters = parameters;
    }

    @Override
    public String name() {
        return "grep_code";
    }

    @Override
    public String description() {
        return "在项目内按关键字或正则实时搜索代码（只读、优先 ripgrep、返回文件和行号）；适合精确符号/字符串定位，找到后再 read_file 读取上下文";
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
        String query = args.string("pattern");
        if (query == null || query.isBlank()) {
            return ToolOutput.failure(ToolOutput.FailureCode.INVALID_ARGUMENT, "代码搜索失败: pattern 不能为空");
        }
        Path root = context.resolvePath(args.string("path", "."));
        Path projectRoot = context.projectRoot();
        int maxResults = ProjectFiles.clamp(args.integer("max_results", 50), 1, MAX_RESULTS);
        int contextLines = ProjectFiles.clamp(args.integer("context_lines", 0), 0, MAX_CONTEXT_LINES);
        boolean regex = args.bool("regex", false);
        boolean caseSensitive = args.bool("case_sensitive", true);
        int headLimit = ProjectFiles.clamp(args.integer("head_limit", DEFAULT_HEAD_LIMIT), 1, 50);
        int maxChars = ProjectFiles.clamp(args.integer("max_chars", DEFAULT_MAX_CHARS), 1_000, MAX_MAX_CHARS);
        CodeSearchRequest request = new CodeSearchRequest(
                query,
                root,
                projectRoot,
                args.string("glob"),
                regex,
                caseSensitive,
                contextLines,
                maxResults,
                headLimit
        );
        CodeSearchResult result = new RipgrepCodeSearchEngine(ProjectFiles.EXCLUDED_DIRS).search(request);

        if (!result.partialReason().isBlank() && result.matches().isEmpty()) {
            return ToolOutput.failure("代码搜索失败: " + result.partialReason());
        }
        if (result.matches().isEmpty()) {
            return ToolOutput.text("未找到匹配内容: " + query);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("匹配结果 ").append(result.matches().size()).append(" 条")
                .append(" (engine=").append(result.engine()).append(")");
        if (result.partial()) {
            sb.append("（partial: ").append(result.partialReason()).append("）");
        }
        sb.append(":\n");
        boolean truncatedByChars = false;
        int rendered = 0;
        for (int i = 0; i < result.matches().size(); i++) {
            GrepMatch match = result.matches().get(i);
            String matchHeader = (i + 1) + ". " + match.file() + ":" + match.lineNumber() + "\n";
            if (sb.length() + matchHeader.length() > maxChars) {
                truncatedByChars = true;
                break;
            }
            sb.append(i + 1).append(". ").append(match.file()).append(":").append(match.lineNumber()).append("\n");
            for (ContextLine line : match.context()) {
                String marker = line.lineNumber() == match.lineNumber() ? ">" : " ";
                String contextLine = String.format("   %s%5d | %s%n", marker, line.lineNumber(), line.text());
                if (sb.length() + contextLine.length() > maxChars) {
                    truncatedByChars = true;
                    break;
                }
                sb.append(contextLine);
            }
            rendered++;
            if (truncatedByChars) {
                break;
            }
        }
        if (truncatedByChars) {
            sb.append("\npartial: true（已达到 max_chars=").append(maxChars).append("，请缩小 path/glob/pattern 或提高 offset 后 read_file）");
        } else if (result.partial()) {
            sb.append("\npartial: true（").append(result.partialReason()).append("，请缩小 path/glob/pattern 继续搜索）");
        }
        appendSuggestedReads(sb, result.matches().subList(0, Math.min(rendered, result.matches().size())));
        return ToolOutput.text(sb.toString().trim());
    }

    private static void appendSuggestedReads(StringBuilder sb, List<GrepMatch> matches) {
        if (matches.isEmpty()) {
            return;
        }
        sb.append("\nsuggested_reads:");
        Set<String> seen = new LinkedHashSet<>();
        for (GrepMatch match : matches) {
            if (seen.size() >= 3 || !seen.add(match.file())) {
                continue;
            }
            int offset = Math.max(1, match.lineNumber() - 20);
            sb.append("\n- read_file {\"path\":\"")
                    .append(match.file().replace("\\", "\\\\").replace("\"", "\\\""))
                    .append("\",\"offset\":").append(offset)
                    .append(",\"limit\":80}");
        }
    }
}
