package com.haocli.tool;

import java.util.Map;

/**
 * 校验后的工具参数。值都按字符串保存，由工具自己解析；解析失败时回落到默认值，
 * 和迁移前 {@code ToolRegistry} 里的语义一致（评测复算依赖这套语义）。
 */
public final class ToolArguments {
    private final Map<String, String> values;

    public ToolArguments(Map<String, String> values) {
        this.values = values == null ? Map.of() : Map.copyOf(values);
    }

    public boolean has(String name) {
        return values.containsKey(name);
    }

    /** 没有这个参数时返回 null。 */
    public String string(String name) {
        return values.get(name);
    }

    public String string(String name, String fallback) {
        return values.getOrDefault(name, fallback);
    }

    public int integer(String name, int fallback) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean bool(String name, boolean fallback) {
        String value = values.get(name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String v = value.trim();
        return "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
    }

    public Map<String, String> asMap() {
        return values;
    }
}
