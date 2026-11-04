package com.paicli.hitl;

/** Immutable facts used by the permission evaluator; construction performs no I/O. */
public record PermissionContext(
        String projectRoot,
        String toolName,
        String serverName,
        String command,
        String projectRelativePath,
        String configFingerprint,
        boolean current,
        boolean trusted,
        boolean ready
) {
    public PermissionContext(String projectRoot, String toolName, String serverName,
                             String command, String projectRelativePath) {
        this(projectRoot, toolName, serverName, command, projectRelativePath, null, false, false, false);
    }

    public boolean mcpBindingUsable() {
        return configFingerprint != null && configFingerprint.matches("[0-9a-f]{64}")
                && current && trusted && ready;
    }

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
