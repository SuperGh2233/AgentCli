package com.paicli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ApprovalRequestDisplayTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void longCommandIsCompleteIncludingSpacesBackslashesAndUnicode() throws Exception {
        String command = "echo foo\\bar '中文😀'  ".repeat(100) + " ; echo END  ";
        var request = ApprovalRequest.of("execute_command",
                JSON.createObjectNode().put("command", command).toString(), null);
        assertArgumentRows(request.toDisplayText(), "command: \"" + command + "\"");
        assertEquals(command, JSON.readTree(request.arguments()).get("command").asText());
    }

    @Test
    void commandControlsAreVisibleAndNeverExecute() {
        String command = "echo\u001B[2J\r\n\tend\u009B31m";
        String display = ApprovalRequest.of("execute_command",
                JSON.createObjectNode().put("command", command).toString(), null).toDisplayText();
        assertArgumentRows(display, "command: \"echo<U+001B>[2J<U+000D><U+000A><U+0009>end<U+009B>31m\"");
        assertNoDynamicControls(display);
    }

    @Test
    void allC0C1AndBidiControlsHaveVisibleMarkers() {
        List<Integer> controls = new ArrayList<>();
        for (int cp = 0; cp <= 0x1F; cp++) controls.add(cp);
        for (int cp = 0x7F; cp <= 0x9F; cp++) controls.add(cp);
        controls.addAll(List.of(0x061C, 0x200E, 0x200F, 0x2028, 0x2029));
        for (int cp = 0x202A; cp <= 0x202E; cp++) controls.add(cp);
        for (int cp = 0x2066; cp <= 0x206F; cp++) controls.add(cp);
        for (int cp : controls) {
            String marker = String.format("<U+%04X>", cp);
            String raw = Character.toString(cp);
            String display = ApprovalRequest.of("execute_command",
                    JSON.createObjectNode().put("command", raw).toString(), null).toDisplayText();
            assertTrue(display.contains(marker), marker);
            assertNoDynamicControls(display);
        }
    }

    @Test
    void ordinaryUnicodePathsAndLiteralEscapesAreUnchanged() {
        String value = "中文 café Ελληνικά 😀 /tmp/资料 C:\\foo\\bar \\u001B";
        String display = ApprovalRequest.of("execute_command",
                JSON.createObjectNode().put("command", value).toString(), null).toDisplayText();
        assertArgumentRows(display, "command: \"" + value + "\"");
    }

    @Test
    void jsonKeysValuesNestedValuesAndInvalidFallbackAreSafe() {
        String raw = "\u001B[2J\r\n\t\u202E";
        var args = JSON.createObjectNode().put(raw, raw);
        args.set("nested", JSON.createObjectNode().put(raw, raw));
        String display = ApprovalRequest.of("write_file", args.toString(), null).toDisplayText();
        assertNoDynamicControls(display);
        assertTrue(display.contains("<U+001B>"));
        assertTrue(display.contains("<U+202E>"));
        String fallback = " invalid " + raw + " trailing ";
        display = ApprovalRequest.of("execute_command", fallback, null).toDisplayText();
        assertArgumentRows(display, " invalid <U+001B>[2J<U+000D><U+000A><U+0009><U+202E> trailing ");
        assertNoDynamicControls(display);
    }

    @Test
    void eachMetadataFieldAndMcpServerIsEncoded() {
        String raw = "\u001B[2J\r\n\t\u202E";
        String safe = "<U+001B>[2J<U+000D><U+000A><U+0009><U+202E>";
        var request = new ApprovalRequest("mcp__" + raw + "__run", "{}",
                raw, raw, raw, raw, raw);
        String display = request.toDisplayText();
        assertNoDynamicControls(display);
        for (String label : List.of("工具", "MCP server", "等级", "风险", "来源", "自动审查", "敏感页面")) {
            String row = display.lines().filter(line -> line.startsWith("│  " + label + ": "))
                    .findFirst().orElseThrow();
            assertTrue(row.contains("<U+001B>"), label);
        }
        assertTrue(display.contains(safe)); // 完整的执行理由明细
        assertEquals(raw, request.suggestion());
        assertEquals(raw, request.sensitiveNotice());
    }

    @Test
    void whitespaceControlsAreNotHiddenAsBlankInput() {
        var request = new ApprovalRequest("tool", "\r\n\t", "\t", "\r", "\n", "\t", "\r");
        String display = request.toDisplayText();
        assertNoDynamicControls(display);
        assertTrue(display.contains("来源: <U+0009>"));
        assertTrue(display.contains("敏感页面: <U+000D>"));
        assertTrue(display.contains("<U+000D><U+000A><U+0009>"));
    }

    @Test
    void otherLongStringsStillUsePreviewEvenOnExecuteCommand() {
        for (String tool : List.of("write_file", "execute_command")) {
            String display = ApprovalRequest.of(tool, JSON.createObjectNode()
                    .put("content", "x".repeat(200) + "HIDDEN_END").toString(), null).toDisplayText();
            assertFalse(display.contains("HIDDEN_END"));
            assertTrue(display.contains("210 字符"));
        }
        String display = ApprovalRequest.of("write_file", JSON.createObjectNode()
                .put("command", "x".repeat(200) + "HIDDEN_END").toString(), null).toDisplayText();
        assertFalse(display.contains("HIDDEN_END"));
    }

    private static void assertArgumentRows(String display, String expected) {
        // 独立按码点计算现有框宽，逐行比较，连行尾空格也必须保留。
        List<String> rows = new ArrayList<>();
        StringBuilder row = new StringBuilder();
        int width = 0;
        for (int cp : expected.codePoints().toArray()) {
            int cells = cp == 0x1F600 || (cp >= 0x2E80 && cp <= 0x9FFF) ? 2 : 1;
            if (width + cells > 52) {
                rows.add("│    " + row + " ".repeat(54 - width) + "│");
                row.setLength(0);
                width = 0;
            }
            row.appendCodePoint(cp);
            width += cells;
        }
        rows.add("│    " + row + " ".repeat(54 - width) + "│");
        assertEquals(rows, display.lines().filter(line -> line.startsWith("│    ")).toList());
    }

    private static void assertNoDynamicControls(String display) {
        display.codePoints().forEach(cp -> assertFalse(
                (cp < 0x20 && cp != '\n') || (cp >= 0x7F && cp <= 0x9F)
                        || cp == 0x061C || cp == 0x200E || cp == 0x200F
                        || (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x206F),
                () -> String.format("原始控制字符 U+%04X", cp)));
    }
}
