package com.paicli.hitl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/** Pure, deterministic permission-rule evaluation. No filesystem or environment access. */
public final class PermissionRuleEvaluator {
    public enum Decision { NO_MATCH, DENY, ASK, ALLOW }

    public record Evaluation(Decision decision, PermissionRule matchedRule) {
        public Evaluation {
            if (decision == null) throw new IllegalArgumentException("decision 不能为空");
            if ((decision == Decision.NO_MATCH) != (matchedRule == null)) {
                throw new IllegalArgumentException("matchedRule 与 decision 不一致");
            }
        }

        public static Evaluation noMatch() {
            return new Evaluation(Decision.NO_MATCH, null);
        }
    }

    private PermissionRuleEvaluator() { }

    public static Evaluation evaluate(Collection<PermissionRule> rules, PermissionContext context) {
        if (rules == null || rules.isEmpty() || context == null) return Evaluation.noMatch();
        List<PermissionRule> matches = new ArrayList<>();
        for (PermissionRule rule : rules) {
            if (rule != null && rule.projectRoot().equals(context.projectRoot()) && matches(rule, context)) {
                matches.add(rule);
            }
        }
        if (matches.isEmpty()) return Evaluation.noMatch();
        matches.sort(Comparator
                .comparingInt((PermissionRule rule) -> effectPriority(rule.effect())).reversed()
                .thenComparing(Comparator.comparingInt((PermissionRule rule) -> scopePriority(rule.scope())).reversed())
                .thenComparing(Comparator.comparingInt((PermissionRule rule) -> rule.matcher().length()).reversed())
                .thenComparing(PermissionRule::id));
        PermissionRule winner = matches.get(0);
        return new Evaluation(Decision.valueOf(winner.effect().name()), winner);
    }

    private static boolean matches(PermissionRule rule, PermissionContext context) {
        return switch (rule.scope()) {
            case TOOL -> rule.matcher().equals(context.toolName())
                    && (rule.effect() != PermissionRule.Effect.ALLOW
                    || !ApprovalPolicy.isMcpTool(rule.matcher())
                    || (rule.bindingFingerprint() != null && context.mcpBindingUsable()
                    && rule.bindingFingerprint().equals(context.configFingerprint())));
            // Legacy server-wide ALLOW records are readable but never grant access.
            case MCP_SERVER -> rule.effect() != PermissionRule.Effect.ALLOW
                    && rule.matcher().equals(context.serverName());
            case COMMAND_EXACT -> "execute_command".equals(context.toolName())
                    && CommandPrefixMatcher.matchesExact(context.command(), rule.matcher());
            case COMMAND_PREFIX -> "execute_command".equals(context.toolName())
                    && CommandPrefixMatcher.matches(context.command(), rule.matcher());
            case PATH_EXACT -> rule.matcher().equals(context.projectRelativePath());
            case PATH_PREFIX -> context.projectRelativePath() != null
                    && (context.projectRelativePath().equals(rule.matcher())
                    || context.projectRelativePath().startsWith(rule.matcher() + "/"));
        };
    }

    private static int effectPriority(PermissionRule.Effect effect) {
        return switch (effect) {
            case DENY -> 3;
            case ASK -> 2;
            case ALLOW -> 1;
        };
    }

    private static int scopePriority(PermissionRule.Scope scope) {
        return switch (scope) {
            case TOOL -> 5;
            case MCP_SERVER -> 4;
            case COMMAND_EXACT, PATH_EXACT -> 3;
            case COMMAND_PREFIX, PATH_PREFIX -> 2;
        };
    }
}
