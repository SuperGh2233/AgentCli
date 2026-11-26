package com.paicli.headless;

import com.paicli.llm.LlmClient;
import com.paicli.policy.AuditLog;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

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

    HeadlessToolRegistry(HeadlessOptions.PermissionMode mode, List<String> allowedCommands) {
        this.mode = mode;
        this.allowedCommands = List.copyOf(allowedCommands);
    }

    @Override
    public List<LlmClient.Tool> getToolDefinitions() {
        return super.getToolDefinitions().stream()
                .filter(tool -> isExposed(tool.name()))
                .toList();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        long start = System.nanoTime();
        String denial = denialReason(name, argumentsJson);
        if (denial != null) {
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(
                    name, argumentsJson, denial, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)));
            return ToolOutput.denied("无界面模式策略拒绝: " + denial);
        }
        return super.executeToolOutput(name, argumentsJson);
    }

    private boolean isExposed(String name) {
        if (READ_ONLY_TOOLS.contains(name)) {
            return true;
        }
        if (EDIT_TOOLS.contains(name)) {
            return mode == HeadlessOptions.PermissionMode.EDIT;
        }
        return "execute_command".equals(name) && !allowedCommands.isEmpty();
    }

    /** 允许时返回 null，否则返回给模型看的拒绝原因。 */
    String denialReason(String name, String argumentsJson) {
        String tool = name == null ? "" : name.trim();
        if (READ_ONLY_TOOLS.contains(tool)) {
            return null;
        }
        if (EDIT_TOOLS.contains(tool)) {
            return mode == HeadlessOptions.PermissionMode.EDIT
                    ? null
                    : "当前是 read-only 模式，不允许 " + tool + "；需要改文件请用 --permission-mode edit";
        }
        if ("execute_command".equals(tool)) {
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
