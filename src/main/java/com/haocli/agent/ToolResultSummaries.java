package com.haocli.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.haocli.tool.ToolRegistry.ToolExecutionResult;
import com.haocli.util.AnsiStyle;

/** 工具执行后在终端显示的一行摘要（被拒绝的调用、搜索和抓取结果）。 */
final class ToolResultSummaries {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolResultSummaries() {
    }

    /** 需要显示时返回已带样式的一行，不需要显示时返回 null。 */
    static String displayLine(ToolExecutionResult result) {
        return result == null ? null : displayLine(result.name(), result.argumentsJson(), result.result());
    }

    /** 终端从工具事件里拿到的是名字、参数原文和结果文字，和上面那个入口算法相同。 */
    static String displayLine(String name, String argumentsJson, String result) {
        if (name == null) {
            return null;
        }
        String resultText = result == null ? "" : result;
        if (resultText.startsWith("🛡️ 工具调用已拒绝")) {
            return AnsiStyle.subtle("  → " + resultText);
        }
        String summary = switch (name) {
            case "web_search" -> webSearchSummary(argumentsJson, resultText);
            case "web_fetch" -> webFetchSummary(argumentsJson, resultText);
            default -> "";
        };
        return summary.isBlank() ? null : AnsiStyle.subtle("  → " + summary);
    }

    private static String webSearchSummary(String argumentsJson, String text) {
        boolean stepSearch = isStepSearchResult(text);
        if (text.startsWith("搜索失败") || text.startsWith("⚠️") || text.startsWith("🛡️")
                || text.contains("未找到相关结果")) {
            return compactOneLine(text, 120);
        }
        long count = text.lines().filter(line -> line.matches("^\\d+\\.\\s+.*")).count();
        String query = extractJsonArg(argumentsJson, "query");
        String label = query.isBlank() ? "搜索结果" : "搜索 \"" + query + "\"";
        if (stepSearch) {
            label = "StepSearch · " + label;
        }
        return count > 0
                ? label + " 返回 " + count + " 条结果"
                : label + " 已返回结果";
    }

    private static String webFetchSummary(String argumentsJson, String text) {
        boolean stepSearch = isStepSearchResult(text);
        String url = extractJsonArg(argumentsJson, "url");
        String target = url.isBlank() ? "页面" : compactOneLine(url.replaceFirst("^https?://", ""), 80);
        String verb = stepSearch ? "StepSearch · 抓取 " : "抓取 ";
        if (text.startsWith("抓取失败") || text.startsWith("❌") || text.startsWith("🛡️")) {
            return verb + target + " 失败: " + compactOneLine(text, 100);
        }
        String title = text.lines()
                .filter(line -> line.startsWith("📄 标题:"))
                .map(line -> line.substring("📄 标题:".length()).trim())
                .findFirst()
                .orElse("");
        String length = text.lines()
                .filter(line -> line.startsWith("📏 正文"))
                .findFirst()
                .orElse("");
        if (!title.isBlank() && !length.isBlank()) {
            return verb + target + " 完成: " + title + " · " + length.replace("📏 ", "");
        }
        if (!title.isBlank()) {
            return verb + target + " 完成: " + title;
        }
        return verb + target + " 完成";
    }

    private static boolean isStepSearchResult(String text) {
        return text != null && text.startsWith("🔍 [StepSearch]")
                || text != null && text.startsWith("🌐 [StepSearch]");
    }

    private static String extractJsonArg(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) {
            return "";
        }
        try {
            return MAPPER.readTree(json).path(key).asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private static String compactOneLine(String text, int maxLength) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String value = text.replace("\r\n", "\n")
                .replace('\r', '\n')
                .lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("")
                .replaceAll("\\s+", " ");
        return value.length() > maxLength ? value.substring(0, Math.max(0, maxLength - 3)) + "..." : value;
    }
}
