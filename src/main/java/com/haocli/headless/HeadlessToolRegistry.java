package com.haocli.headless;

import com.haocli.llm.LlmClient;
import com.haocli.policy.AuditLog;
import com.haocli.tool.ToolOutput;
import com.haocli.tool.ToolRegistry;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 无界面模式的工具注册表：没有人能确认，所以按白名单放行，其余一律拒绝。
 *
 * <p>不允许的工具不暴露给模型（模型也就不会去调），执行时再按同一份规则检查一次。
 * PathGuard、CommandGuard、命令沙箱这些原有检查照常生效，这里只是在它们之前多加一层。
 */
final class HeadlessToolRegistry extends ToolRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    static final Set<String> READ_ONLY_TOOLS = Set.of(
            "read_file", "list_dir", "glob_files", "grep_code", "search_code",
            "web_search", "web_fetch", "load_skill");
    static final Set<String> EDIT_TOOLS = Set.of("write_file", "edit_file", "create_project");

    private final HeadlessOptions.PermissionMode mode;
    private final List<String> allowedCommands;
    /** 嵌入方（进程内运行时）显式注册的工具：名字 → 是否只读。 */
    private final java.util.Map<String, Boolean> embedderTools = new java.util.concurrent.ConcurrentHashMap<>();

    HeadlessToolRegistry(HeadlessOptions.PermissionMode mode, List<String> allowedCommands) {
        this.mode = mode;
        this.allowedCommands = List.copyOf(allowedCommands);
        // 无人值守白名单是检查链上的一道检查，不再重写执行和工具列表方法
        addGate(new ToolGate() {
            @Override
            public boolean exposes(String toolName) {
                return isExposed(toolName);
            }

            @Override
            public ToolOutput check(String toolName, String argumentsJson) {
                String denial = denialReason(toolName, argumentsJson);
                return denial == null ? null
                        : ToolOutput.denied(ToolOutput.FailureCode.DENY_UNATTENDED, "无界面模式策略拒绝: " + denial);
            }
        });
    }

    /**
     * 嵌入方自己写的工具：只读的始终可用；有副作用的只在 edit 模式可用（和内置写文件工具同一条规则）。
     * PathGuard、审计、参数校验照常在调度层执行。
     */
    void registerEmbedderTool(com.haocli.tool.Tool tool) {
        registerTool(tool);
        embedderTools.put(tool.name(), tool.readOnly());
    }

    private boolean isExposed(String name) {
        if (READ_ONLY_TOOLS.contains(name)) {
            return true;
        }
        Boolean embedderReadOnly = embedderTools.get(name);
        if (embedderReadOnly != null) {
            return embedderReadOnly || mode != HeadlessOptions.PermissionMode.READ_ONLY;
        }
        if (EDIT_TOOLS.contains(name)) {
            return mode != HeadlessOptions.PermissionMode.READ_ONLY;
        }
        return "execute_command".equals(name)
                && (mode == HeadlessOptions.PermissionMode.SANDBOX || !allowedCommands.isEmpty());
    }

    /** 允许时返回 null，否则返回给模型看的拒绝原因。 */
    String denialReason(String name, String argumentsJson) {
        String tool = name == null ? "" : name.trim();
        if (READ_ONLY_TOOLS.contains(tool)) {
            return null;
        }
        Boolean embedderReadOnly = embedderTools.get(tool);
        if (embedderReadOnly != null) {
            return embedderReadOnly || mode != HeadlessOptions.PermissionMode.READ_ONLY
                    ? null
                    : "当前是 read-only 模式，不允许有副作用的工具 " + tool;
        }
        if (EDIT_TOOLS.contains(tool)) {
            return mode != HeadlessOptions.PermissionMode.READ_ONLY
                    ? null
                    : "当前是 read-only 模式，不允许 " + tool + "；需要改文件请用 --permission-mode edit";
        }
        if ("execute_command".equals(tool)) {
            if (mode == HeadlessOptions.PermissionMode.SANDBOX) {
                return null;
            }
            String command = commandOf(argumentsJson);
            for (String allowed : allowedCommands) {
                if (!command.isEmpty() && command.equals(allowed)) {
                    return null;
                }
            }
            return "无界面模式默认不执行命令；需要时用 --allow-command 精确列出允许的命令";
        }
        return "无界面模式不允许这个工具: " + tool;
    }

    private static String commandOf(String argumentsJson) {
        try {
            return MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson)
                    .path("command").asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }
}
