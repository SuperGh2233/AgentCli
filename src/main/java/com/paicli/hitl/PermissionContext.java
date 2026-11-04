package com.paicli.hitl;

/** Immutable facts used by the permission evaluator; construction performs no I/O. */
public record PermissionContext(
        String projectRoot,
        String toolName,
        String serverName,
        String command,
        String projectRelativePath
) {
    public PermissionContext {
        projectRoot = PermissionRule.normalizeProjectRoot(projectRoot);
        if (toolName == null || toolName.isBlank() || toolName.length() > 256
                || toolName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("工具名非法");
        }
        if (serverName != null && (serverName.isBlank() || serverName.length() > 128
                || serverName.codePoints().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException("MCP server 名称非法");
        }
        if (command != null && command.length() > 32_768) {
            throw new IllegalArgumentException("命令过长");
        }
        if (projectRelativePath != null) {
            projectRelativePath = PermissionRule.normalizeRelativePath(projectRelativePath);
        }
    }
}
