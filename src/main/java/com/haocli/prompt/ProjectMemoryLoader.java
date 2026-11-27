package com.haocli.prompt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads Hao CLI project memory files that are intended to be versioned and
 * injected into the system prompt at session start.
 */
public class ProjectMemoryLoader {
    private static final Logger log = LoggerFactory.getLogger(ProjectMemoryLoader.class);
    private static final int MAX_TOTAL_CHARS = 24_000;
    /** 给“未展开的部分”目录留的空间。 */
    private static final int OUTLINE_RESERVE_CHARS = 3_000;
    private static final int MIN_PARTIAL_CHARS = 400;
    private static final int MAX_OUTLINE_ENTRIES = 40;
    private static final int MAX_IMPORT_DEPTH = 3;

    private final Path userConfigDir;
    private final Path projectRoot;

    public ProjectMemoryLoader(Path userConfigDir, Path projectRoot) {
        this.userConfigDir = userConfigDir == null ? null : userConfigDir.toAbsolutePath().normalize();
        this.projectRoot = projectRoot == null
                ? Path.of(".").toAbsolutePath().normalize()
                : projectRoot.toAbsolutePath().normalize();
    }

    public static ProjectMemoryLoader createDefault(Path projectRoot) {
        return new ProjectMemoryLoader(Path.of(System.getProperty("user.home"), ".haocli"), projectRoot);
    }

    /**
     * 渐进式加载：预算内的内容原样放进来；放不下的部分不硬截断，而是列出目录（文件、行号、标题），
     * 模型需要时用 read_file 按行号去读。
     */
    public String loadForPrompt() {
        List<MemorySource> sources = sources();
        StringBuilder body = new StringBuilder();
        Set<Path> importStack = new HashSet<>();
        List<String> outline = new ArrayList<>();
        int budget = MAX_TOTAL_CHARS - OUTLINE_RESERVE_CHARS;

        for (MemorySource source : sources) {
            if (!Files.isRegularFile(source.path())) {
                continue;
            }
            String content = readWithImports(source.path(), source.importRoot(), importStack, 0).trim();
            if (content.isBlank()) {
                continue;
            }
            String header = (body.isEmpty() ? "" : "\n\n") + "### " + label(source.path()) + "\n\n";
            if (outline.isEmpty() && body.length() + header.length() + content.length() <= MAX_TOTAL_CHARS) {
                body.append(header).append(content);
                continue;
            }
            // 放不下：在行边界放进能放的部分，其余（以及后面所有文件）只给目录。
            int shownLines = 0;
            int room = budget - body.length() - header.length();
            if (outline.isEmpty() && room >= MIN_PARTIAL_CHARS) {
                String part = cutAtLine(content, room);
                body.append(header).append(part);
                shownLines = (int) part.chars().filter(c -> c == '\n').count() + 1;
            }
            outline.addAll(outlineOf(source.path(), shownLines));
        }

        if (body.isEmpty() && outline.isEmpty()) {
            return "";
        }
        StringBuilder section = new StringBuilder("## HAO.md 项目记忆\n\n").append(body);
        if (!outline.isEmpty()) {
            if (!body.isEmpty()) section.append("\n\n");
            section.append("### 未展开的部分\n\n以下内容因上下文预算（")
                    .append(MAX_TOTAL_CHARS).append(" 字符）没有放进来；需要时用 read_file 按路径和行号读取：\n");
            int shown = Math.min(outline.size(), MAX_OUTLINE_ENTRIES);
            for (int i = 0; i < shown; i++) {
                section.append(outline.get(i)).append('\n');
            }
            if (outline.size() > shown) {
                section.append("- …另有 ").append(outline.size() - shown).append(" 个标题未列出\n");
            }
        }
        return section.toString().trim();
    }

    /** 文件里第 shownLines 行之后的标题；没有标题时给一行“从第几行起未展开”。行号按原文件计。 */
    private List<String> outlineOf(Path file, int shownLines) {
        List<String> entries = new ArrayList<>();
        String path = label(file);
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean hasImports = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (parseImport(line) != null) hasImports = true;
                if (i + 1 > shownLines && line.startsWith("#")) {
                    entries.add("- " + path + " 第 " + (i + 1) + " 行：" + line);
                }
            }
            if (entries.isEmpty()) {
                entries.add("- " + path + "（共 " + lines.size() + " 行，从第 " + (shownLines + 1) + " 行起未展开）");
            }
            if (hasImports) {
                entries.add("- " + path + " 含 @import，导入的文件也可以单独读取");
            }
        } catch (IOException e) {
            entries.add("- " + path + "（读取目录失败，可直接用 read_file 打开）");
        }
        return entries;
    }

    private static String cutAtLine(String content, int maxChars) {
        if (content.length() <= maxChars) return content;
        int cut = content.lastIndexOf('\n', Math.max(0, maxChars));
        return (cut > 0 ? content.substring(0, cut) : content.substring(0, Math.max(0, maxChars))).stripTrailing();
    }

    private List<MemorySource> sources() {
        List<MemorySource> sources = new ArrayList<>();
        if (userConfigDir != null) {
            sources.add(new MemorySource(userConfigDir.resolve("HAO.md"), userConfigDir));
        }
        sources.add(new MemorySource(projectRoot.resolve("HAO.md"), projectRoot));
        sources.add(new MemorySource(projectRoot.resolve(".haocli").resolve("HAO.md"), projectRoot));
        sources.add(new MemorySource(projectRoot.resolve("HAO.local.md"), projectRoot));
        sources.add(new MemorySource(projectRoot.resolve(".haocli").resolve("HAO.local.md"), projectRoot));
        return sources;
    }

    private String readWithImports(Path file, Path importRoot, Set<Path> importStack, int depth) {
        Path normalized = file.toAbsolutePath().normalize();
        if (depth > MAX_IMPORT_DEPTH) {
            log.warn("Skipping HAO.md import beyond depth {}: {}", MAX_IMPORT_DEPTH, normalized);
            return "";
        }
        if (!normalized.startsWith(importRoot) || !Files.isRegularFile(normalized)) {
            log.warn("Skipping HAO.md import outside allowed root or missing file: {}", normalized);
            return "";
        }
        if (!importStack.add(normalized)) {
            log.warn("Skipping cyclic HAO.md import: {}", normalized);
            return "";
        }

        try {
            StringBuilder out = new StringBuilder();
            for (String line : Files.readAllLines(normalized, StandardCharsets.UTF_8)) {
                String importPath = parseImport(line);
                if (importPath == null) {
                    out.append(line).append("\n");
                    continue;
                }
                Path imported = normalized.getParent().resolve(importPath).normalize();
                String importedContent = readWithImports(imported, importRoot, importStack, depth + 1).trim();
                if (!importedContent.isBlank()) {
                    out.append(importedContent).append("\n");
                }
            }
            return out.toString();
        } catch (IOException e) {
            log.warn("Failed to read HAO.md memory file: {}", normalized, e);
            return "";
        } finally {
            importStack.remove(normalized);
        }
    }

    private static String parseImport(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (!trimmed.startsWith("@") || trimmed.length() < 2 || trimmed.contains(" ")) {
            return null;
        }
        String path = trimmed.substring(1).trim();
        if (path.startsWith("/") || path.contains("..")) {
            return null;
        }
        return path;
    }

    private static String label(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private record MemorySource(Path path, Path importRoot) {
        private MemorySource {
            path = path.toAbsolutePath().normalize();
            importRoot = importRoot.toAbsolutePath().normalize();
        }
    }
}
