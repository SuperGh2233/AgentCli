package com.paicli.hitl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.policy.PathGuard;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Adapts one validated tool invocation to project-scoped permission rules.
 * A malformed or escaping path never produces an allow-path match.
 */
final class PermissionRuleRuntime {
    private static final ObjectMapper JSON = new ObjectMapper();

    private PermissionRuleRuntime() { }

    static PermissionRuleEvaluator.Evaluation evaluate(PermissionRuleStore store, String projectPath,
                                                      String toolName, String argumentsJson) throws IOException {
        Path projectRoot = Path.of(projectPath).toRealPath();
        store.requireOutsideProject(projectRoot);
        JsonNode args;
        try {
            args = JSON.readTree(argumentsJson);
        } catch (IOException | IllegalArgumentException error) {
            throw new IOException("权限规则无法解析工具参数", error);
        }
        if (args == null || !args.isObject()) {
            throw new IOException("权限规则要求工具参数为 JSON 对象");
        }

        String command = null;
        if ("execute_command".equals(toolName) && args.path("command").isTextual()) {
            command = args.path("command").asText();
        }

        String relativePath = null;
        if (("write_file".equals(toolName) || "edit_file".equals(toolName))
                && args.path("path").isTextual()) {
            try {
                Path candidate = new PathGuard(projectRoot.toString()).resolveSafe(args.path("path").asText());
                if (!candidate.equals(projectRoot)) {
                    relativePath = projectRoot.relativize(candidate).toString().replace('\\', '/');
                }
            } catch (RuntimeException ignored) {
                // The real tool's PathGuard still rejects an invalid path.
                // Never create a stored path allow-match from a rejected path.
            }
        }
        PermissionContext context = new PermissionContext(projectRoot.toString(), toolName,
                ApprovalPolicy.mcpServerName(toolName), command, relativePath);
        return PermissionRuleEvaluator.evaluate(store.load(), context);
    }

    /**
     * Broad tool-level grants never remove approval. MCP allows also require a
     * configuration fingerprint, which is deferred to the interactive UI phase.
     * Only scoped command/path matchers can skip HITL in this opt-in runtime.
     */
    static boolean maySkipApproval(PermissionRuleEvaluator.Evaluation evaluation, String toolName) {
        if (evaluation.decision() != PermissionRuleEvaluator.Decision.ALLOW) return false;
        return switch (evaluation.matchedRule().scope()) {
            case MCP_SERVER, TOOL -> false;
            case COMMAND_PREFIX -> "execute_command".equals(toolName);
            case PATH_PREFIX -> "write_file".equals(toolName) || "edit_file".equals(toolName);
        };
    }
}
