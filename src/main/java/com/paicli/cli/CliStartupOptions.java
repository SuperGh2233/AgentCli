package com.paicli.cli;

/** 启动参数；未知旧参数保留忽略行为，不处理微信或 serve 的专用入口。 */
record CliStartupOptions(boolean continueSession, String sessionId) {
    static CliStartupOptions parse(String[] args) {
        boolean latest = false;
        String id = null;
        if (args != null) {
            for (int i = 0; i < args.length; i++) {
                if ("--continue".equals(args[i])) {
                    if (latest || id != null) {
                        throw new IllegalArgumentException("--continue 和 --session 不能重复或混用");
                    }
                    latest = true;
                } else if ("--session".equals(args[i])) {
                    if (latest || id != null) {
                        throw new IllegalArgumentException("--continue 和 --session 不能重复或混用");
                    }
                    if (++i >= args.length || !safeId(args[i])) {
                        throw new IllegalArgumentException("--session 需要安全的非空会话 ID，不能包含路径");
                    }
                    id = args[i];
                }
            }
        }
        return new CliStartupOptions(latest, id);
    }

    private static boolean safeId(String id) {
        return id != null && id.matches("[A-Za-z0-9._-]+")
                && !id.startsWith("--") && !id.equals(".") && !id.equals("..");
    }

    boolean requested() {
        return continueSession || sessionId != null;
    }

    void requireCli(boolean useTui) {
        if (requested() && useTui) {
            throw new IllegalArgumentException("--continue / --session 仅支持 CLI，当前已选择全屏 TUI");
        }
    }
}
