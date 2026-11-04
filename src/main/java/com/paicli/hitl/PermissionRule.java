package com.paicli.hitl;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** One explicit, project-scoped permission rule. */
public record PermissionRule(
        String id,
        String projectRoot,
        Effect effect,
        Scope scope,
        String matcher,
        long createdAtEpochMillis,
        String reason
) {
    public enum Effect { DENY, ASK, ALLOW }
    public enum Scope { TOOL, MCP_SERVER, COMMAND_EXACT, COMMAND_PREFIX, PATH_EXACT, PATH_PREFIX }

    public PermissionRule {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("权限规则 id 非法");
        }
        projectRoot = normalizeProjectRoot(projectRoot);
        effect = Objects.requireNonNull(effect, "effect");
        scope = Objects.requireNonNull(scope, "scope");
        matcher = normalizeMatcher(scope, matcher);
        if (createdAtEpochMillis <= 0) throw new IllegalArgumentException("权限规则创建时间非法");
        if (reason != null) {
            reason = reason.strip();
            if (reason.length() > 512 || reason.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("权限规则说明过长或包含控制字符");
            }
            if (reason.isEmpty()) reason = null;
        }
    }

    public static PermissionRule create(String projectRoot, Effect effect, Scope scope,
                                        String matcher, String reason) {
        return new PermissionRule(UUID.randomUUID().toString(), projectRoot, effect, scope,
                matcher, System.currentTimeMillis(), reason);
    }

    static String normalizeProjectRoot(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("项目根目录不能为空");
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) throw new IllegalArgumentException("项目根目录必须是绝对路径");
            return path.normalize().toString();
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("项目根目录非法", e);
        }
    }

    static String normalizeMatcher(Scope scope, String value) {
        if (value == null || value.isBlank() || value.length() > 1024
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("权限规则匹配值不能为空、过长或包含控制字符");
        }
        String matcher = value.strip();
        return switch (scope) {
            case TOOL -> {
                if (!matcher.matches("[A-Za-z0-9_.:-]{1,256}")) {
                    throw new IllegalArgumentException("工具名匹配值非法");
                }
                yield matcher;
            }
            case MCP_SERVER -> {
                if (!matcher.matches("[A-Za-z0-9_.-]{1,128}")) {
                    throw new IllegalArgumentException("MCP server 名称非法");
                }
                yield matcher;
            }
            case COMMAND_EXACT, COMMAND_PREFIX -> {
                if (!CommandPrefixMatcher.isSafeSimpleCommand(matcher)) {
                    throw new IllegalArgumentException("命令规则只支持不含 Shell 控制语法的简单命令");
                }
                yield CommandPrefixMatcher.normalizeWhitespace(matcher);
            }
            case PATH_EXACT, PATH_PREFIX -> normalizeRelativePath(matcher);
        };
    }

    static String normalizeRelativePath(String value) {
        if (value == null || value.isBlank() || value.length() > 1024
                || value.startsWith("/") || value.startsWith("\\")
                || value.contains("\\") || value.contains(":")) {
            throw new IllegalArgumentException("路径规则必须是项目内相对路径");
        }
        String[] components = value.split("/", -1);
        for (String component : components) {
            String deviceStem = component.split("\\.", 2)[0].stripTrailing().toUpperCase(Locale.ROOT);
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                    || component.endsWith(".") || component.endsWith(" ")
                    || component.chars().anyMatch(c -> "<>\"|?*".indexOf(c) >= 0)
                    || deviceStem.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")) {
                throw new IllegalArgumentException("路径规则包含非法路径段");
            }
        }
        try {
            Path path = Path.of(value);
            if (path.isAbsolute()) throw new IllegalArgumentException("路径规则不能是绝对路径");
            String normalized = path.normalize().toString().replace('\\', '/');
            if (normalized.isEmpty() || normalized.equals(".")) {
                throw new IllegalArgumentException("不能对整个项目根目录设置路径授权");
            }
            return normalized;
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("路径规则非法", e);
        }
    }
}
