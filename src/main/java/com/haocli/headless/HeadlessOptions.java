package com.haocli.headless;

import com.haocli.agent.RunLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 无界面模式的启动参数：{@code -p "任务" [--output-format text|json|stream-json]
 * [--permission-mode read-only|edit] [--allow-command "精确命令"]... [--max-turns N] [--max-tokens N] [--timeout 30m]}。
 *
 * @param prompt          任务；null 表示从标准输入读取（{@code -p} 后面不跟内容或跟 {@code -}）
 * @param allowedCommands 允许执行的精确命令（逐字比较），默认一条都不允许
 * @param limits          本次任务的上限；null 时用无人值守默认值（环境变量可改）
 */
public record HeadlessOptions(String prompt, OutputFormat outputFormat, PermissionMode permissionMode,
                              List<String> allowedCommands, RunLimits limits) {

    public enum OutputFormat {
        /** 只把最终回答写到标准输出。 */
        TEXT,
        /** 结束时写一个 JSON 对象。 */
        JSON,
        /** 每个事件一行 JSON（JSONL），最后一行是结果。 */
        STREAM_JSON
    }

    public enum PermissionMode {
        /** 只允许读文件、搜索代码、联网读取等只读工具（默认）。 */
        READ_ONLY,
        /** 另外允许写文件、改文件、创建项目。 */
        EDIT,
        /**
         * 只给一次性的隔离容器用（比如 Terminal-Bench 的任务容器）：读写文件之外，允许执行任意命令。
         * PathGuard、CommandGuard、审计照常生效。只在容器里才能启用，见 {@link #parseMode}。
         */
        SANDBOX
    }

    /** 在宿主机上强行启用 sandbox 模式的开关（只用于自己负责隔离的场景）。 */
    static final String ALLOW_SANDBOX_ENV = "HAOCLI_ALLOW_SANDBOX_MODE";

    /** 是否在容器里；测试可替换。 */
    static java.util.function.BooleanSupplier containerCheck = HeadlessOptions::detectContainer;

    static boolean detectContainer() {
        return java.nio.file.Files.exists(java.nio.file.Path.of("/.dockerenv"))
                || java.nio.file.Files.exists(java.nio.file.Path.of("/run/.containerenv"))
                || "1".equals(System.getenv(ALLOW_SANDBOX_ENV));
    }

    public HeadlessOptions {
        outputFormat = outputFormat == null ? OutputFormat.TEXT : outputFormat;
        permissionMode = permissionMode == null ? PermissionMode.READ_ONLY : permissionMode;
        allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
        limits = limits == null ? RunLimits.unattendedFromEnvironment() : limits;
    }

    public HeadlessOptions(String prompt, OutputFormat outputFormat, PermissionMode permissionMode,
                           List<String> allowedCommands) {
        this(prompt, outputFormat, permissionMode, allowedCommands, null);
    }

    public static boolean isHeadless(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("-p".equals(arg) || "--print".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    public static HeadlessOptions parse(String[] args) {
        return parse(args, System::getenv);
    }

    /** 上限的优先级：命令行参数 > 环境变量 > 默认值。 */
    static HeadlessOptions parse(String[] args, Function<String, String> env) {
        RunLimits limits = RunLimits.unattended(env);
        boolean turnsSeen = false;
        boolean tokensSeen = false;
        boolean timeoutSeen = false;
        String prompt = null;
        boolean printSeen = false;
        OutputFormat format = null;
        PermissionMode mode = null;
        List<String> commands = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-p", "--print" -> {
                    if (printSeen) {
                        throw new IllegalArgumentException("-p 只能出现一次");
                    }
                    printSeen = true;
                    if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        String value = args[++i];
                        prompt = "-".equals(value) ? null : value;
                    }
                }
                case "--output-format" -> {
                    if (format != null) {
                        throw new IllegalArgumentException("--output-format 只能出现一次");
                    }
                    format = parseFormat(requireValue(args, ++i, arg));
                }
                case "--permission-mode" -> {
                    if (mode != null) {
                        throw new IllegalArgumentException("--permission-mode 只能出现一次");
                    }
                    mode = parseMode(requireValue(args, ++i, arg));
                }
                case "--allow-command" -> {
                    String command = requireValue(args, ++i, arg).trim();
                    if (command.isEmpty()) {
                        throw new IllegalArgumentException("--allow-command 不能是空命令");
                    }
                    commands.add(command);
                }
                case "--max-turns" -> {
                    turnsSeen = once(turnsSeen, arg);
                    limits = limits.withMaxModelRequests(RunLimits.parsePositiveInt(requireValue(args, ++i, arg), arg));
                }
                case "--max-tokens" -> {
                    tokensSeen = once(tokensSeen, arg);
                    limits = limits.withMaxTotalTokens(RunLimits.parsePositiveInt(requireValue(args, ++i, arg), arg));
                }
                case "--timeout" -> {
                    timeoutSeen = once(timeoutSeen, arg);
                    limits = limits.withTimeout(RunLimits.parseDuration(requireValue(args, ++i, arg), arg));
                }
                default -> throw new IllegalArgumentException("无界面模式不认识的参数: " + arg);
            }
        }
        if (!printSeen) {
            throw new IllegalArgumentException("缺少 -p");
        }
        return new HeadlessOptions(prompt, format, mode, commands, limits);
    }

    public HeadlessOptions withPrompt(String newPrompt) {
        return new HeadlessOptions(newPrompt, outputFormat, permissionMode, allowedCommands, limits);
    }

    private static boolean once(boolean seen, String flag) {
        if (seen) {
            throw new IllegalArgumentException(flag + " 只能出现一次");
        }
        return true;
    }

    public static String usage() {
        return """
                用法: java -jar haocli.jar -p "任务" [选项]
                  -p, --print <任务>          执行一次任务后退出；不写任务或写 - 时从标准输入读取
                  --output-format <格式>      text（默认，只输出最终回答）| json | stream-json（每个事件一行）
                  --permission-mode <模式>    read-only（默认，只读工具）| edit（另外允许写文件和改文件）
                                              | sandbox（另外允许任意命令，只能在隔离容器里用）
                  --allow-command <命令>      允许执行的精确命令，可重复；默认不允许执行任何命令
                  --max-turns <次数>          最多发多少次模型请求，默认 50（环境变量 HAOCLI_UNATTENDED_MAX_TURNS）
                  --max-tokens <数量>         输入加输出 Token 总上限，默认 2000000（HAOCLI_UNATTENDED_MAX_TOKENS）
                  --timeout <时长>            运行时间上限，默认 30m；纯数字按秒，可带 s/m/h（HAOCLI_UNATTENDED_TIMEOUT）
                  到达任一上限时停止调用工具、整理已完成的部分，退出码 3
                退出码: 0 完成，1 调用模型失败，2 参数或配置错误，3 只完成了一部分，130 已取消""";
    }

    private static String requireValue(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " 缺少取值");
        }
        return args[index];
    }

    private static OutputFormat parseFormat(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "text" -> OutputFormat.TEXT;
            case "json" -> OutputFormat.JSON;
            case "stream-json" -> OutputFormat.STREAM_JSON;
            default -> throw new IllegalArgumentException("--output-format 只能是 text、json 或 stream-json: " + value);
        };
    }

    static PermissionMode parseMode(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "read-only" -> PermissionMode.READ_ONLY;
            case "edit" -> PermissionMode.EDIT;
            case "sandbox" -> {
                if (!containerCheck.getAsBoolean()) {
                    throw new IllegalArgumentException("--permission-mode sandbox 允许执行任意命令，只能在隔离容器里使用"
                            + "（没有检测到 /.dockerenv 或 /run/.containerenv）；确实由你自己负责隔离时设置 "
                            + ALLOW_SANDBOX_ENV + "=1");
                }
                yield PermissionMode.SANDBOX;
            }
            default -> throw new IllegalArgumentException("--permission-mode 只能是 read-only、edit 或 sandbox: " + value);
        };
    }
}
