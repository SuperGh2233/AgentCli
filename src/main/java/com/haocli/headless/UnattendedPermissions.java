package com.haocli.headless;

import java.util.ArrayList;
import java.util.List;

/**
 * 无人值守通道（Runtime API、后台任务）的权限：和 {@code -p} 同一套白名单。
 * 默认只读；{@code edit} 才能写文件；命令只放行逐字列出的。
 */
public record UnattendedPermissions(HeadlessOptions.PermissionMode mode, List<String> allowedCommands) {

    public static final UnattendedPermissions READ_ONLY =
            new UnattendedPermissions(HeadlessOptions.PermissionMode.READ_ONLY, List.of());

    public UnattendedPermissions {
        mode = mode == null ? HeadlessOptions.PermissionMode.READ_ONLY : mode;
        allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
    }

    /**
     * 从 {@code serve} 的启动参数里取 {@code --permission-mode} 和可重复的 {@code --allow-command}，
     * 其他参数（--http、--port 等）忽略。取值不合法时抛 {@link IllegalArgumentException}。
     */
    public static UnattendedPermissions fromArgs(String[] args) {
        HeadlessOptions.PermissionMode mode = null;
        List<String> commands = new ArrayList<>();
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if ("--permission-mode".equals(args[i])) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--permission-mode 缺少取值");
                    }
                    if (mode != null) {
                        throw new IllegalArgumentException("--permission-mode 只能出现一次");
                    }
                    mode = HeadlessOptions.parseMode(args[++i]);
                } else if ("--allow-command".equals(args[i])) {
                    if (i + 1 >= args.length || args[i + 1].trim().isEmpty()) {
                        throw new IllegalArgumentException("--allow-command 缺少命令");
                    }
                    commands.add(args[++i].trim());
                }
            }
        }
        return new UnattendedPermissions(mode, commands);
    }

    /** 从配置值（比如 HAOCLI_TASK_PERMISSION_MODE）读取模式；空值按只读。 */
    public static UnattendedPermissions fromModeValue(String value) {
        if (value == null || value.isBlank()) {
            return READ_ONLY;
        }
        return new UnattendedPermissions(HeadlessOptions.parseMode(value.trim()), List.of());
    }

    /** 启动时给用户看的一行说明。 */
    public String describe() {
        String modeText = mode == HeadlessOptions.PermissionMode.EDIT ? "edit（可读写文件）" : "read-only（只读）";
        String commandText = allowedCommands.isEmpty() ? "不执行任何命令" : "允许 " + allowedCommands.size() + " 条精确命令";
        return "权限: " + modeText + "，" + commandText;
    }
}
