package com.paicli.headless;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 无界面模式的启动参数：{@code -p "任务" [--output-format text|json|stream-json]
 * [--permission-mode read-only|edit] [--allow-command "精确命令"]...}。
 *
 * @param prompt          任务；null 表示从标准输入读取（{@code -p} 后面不跟内容或跟 {@code -}）
 * @param allowedCommands 允许执行的精确命令（逐字比较），默认一条都不允许
 */
public record HeadlessOptions(String prompt, OutputFormat outputFormat, PermissionMode permissionMode,
                              List<String> allowedCommands) {

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
        EDIT
    }

    public HeadlessOptions {
        outputFormat = outputFormat == null ? OutputFormat.TEXT : outputFormat;
        permissionMode = permissionMode == null ? PermissionMode.READ_ONLY : permissionMode;
        allowedCommands = allowedCommands == null ? List.of() : List.copyOf(allowedCommands);
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
                default -> throw new IllegalArgumentException("无界面模式不认识的参数: " + arg);
            }
        }
        if (!printSeen) {
            throw new IllegalArgumentException("缺少 -p");
        }
        return new HeadlessOptions(prompt, format, mode, commands);
    }

    public HeadlessOptions withPrompt(String newPrompt) {
        return new HeadlessOptions(newPrompt, outputFormat, permissionMode, allowedCommands);
    }

    public static String usage() {
        return """
                用法: java -jar paicli.jar -p "任务" [选项]
                  -p, --print <任务>          执行一次任务后退出；不写任务或写 - 时从标准输入读取
                  --output-format <格式>      text（默认，只输出最终回答）| json | stream-json（每个事件一行）
                  --permission-mode <模式>    read-only（默认，只读工具）| edit（另外允许写文件和改文件）
                  --allow-command <命令>      允许执行的精确命令，可重复；默认不允许执行任何命令
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
            default -> throw new IllegalArgumentException("--permission-mode 只能是 read-only 或 edit: " + value);
        };
    }
}
