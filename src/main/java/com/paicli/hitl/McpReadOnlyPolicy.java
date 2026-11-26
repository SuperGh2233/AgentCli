package com.paicli.hitl;

import java.util.List;

/**
 * MCP 只读免审批规则的纯匹配接口，尚未接入执行链，生产行为仍默认关闭。
 * 身份和工具描述的 SHA-256 必须由可信注册代码提供，不能取自 server 注解或模型参数。
 * 后续接线必须让核对与实际执行使用同一个不可变注册实例；匹配不替代其他安全检查。
 */
public final class McpReadOnlyPolicy {
    public static final McpReadOnlyPolicy DISABLED = new McpReadOnlyPolicy(List.of());

    /**
     * 用户明确声明工具只读。serverIdentity 应包含 transport、展开后配置、来源及项目真实路径；
     * toolFingerprint 应包含原始参数 Schema、描述和工具名。这里只接受 SHA-256 十六进制值。
     * 本接口不读取项目配置，也不提供运行时开启入口。
     */
    public record Rule(String server, String tool, String serverIdentity,
                       String toolFingerprint, boolean readOnly) { }

    private final List<Rule> rules;

    /** 任意规则无效时整组关闭；不支持通配符、大小写折叠或名称推断。 */
    public McpReadOnlyPolicy(List<Rule> rules) {
        this.rules = rules != null && rules.stream().allMatch(McpReadOnlyPolicy::valid)
                ? List.copyOf(rules) : List.of();
    }

    public boolean matches(String server, String exactTool, String serverIdentity, String toolFingerprint) {
        if (!name(server) || !name(exactTool) || !digest(serverIdentity) || !digest(toolFingerprint)) {
            return false;
        }
        return rules.stream().anyMatch(r -> r.server().equals(server) && r.tool().equals(exactTool)
                && r.serverIdentity().equals(serverIdentity) && r.toolFingerprint().equals(toolFingerprint));
    }

    private static boolean valid(Rule rule) {
        return rule != null && rule.readOnly() && name(rule.server()) && name(rule.tool())
                && digest(rule.serverIdentity()) && digest(rule.toolFingerprint());
    }

    private static boolean name(String value) {
        // 保守拒绝命名分隔符，避免现有 mcp__server__tool 字符串发生歧义。
        return value != null && value.matches("[A-Za-z0-9_-]+") && !value.contains("__");
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
