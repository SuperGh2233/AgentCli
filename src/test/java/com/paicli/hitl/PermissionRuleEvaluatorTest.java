package com.paicli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionRuleEvaluatorTest {
    private static final String FINGERPRINT = "a".repeat(64);
    private static final String MCP_TOOL = "mcp__chrome-devtools__navigate_page";
    private static final String ROOT = Path.of(System.getProperty("java.io.tmpdir"), "permission-demo")
            .toAbsolutePath().normalize().toString();

    private PermissionRule rule(String id, PermissionRule.Effect effect, PermissionRule.Scope scope,
                               String matcher) {
        return new PermissionRule(id, ROOT, effect, scope, matcher, Instant.parse("2026-11-04T00:00:00Z").toEpochMilli(), null);
    }

    private PermissionContext context(String tool, String server, String command, String path) {
        return new PermissionContext(ROOT, tool, server, command, path);
    }

    @Test
    void denyAlwaysWinsOverAskAndAllow() {
        var allow = rule("allow", PermissionRule.Effect.ALLOW, PermissionRule.Scope.TOOL, "execute_command");
        var ask = rule("ask", PermissionRule.Effect.ASK, PermissionRule.Scope.COMMAND_PREFIX, "git status");
        var deny = rule("deny", PermissionRule.Effect.DENY, PermissionRule.Scope.COMMAND_PREFIX, "git status");

        var result = PermissionRuleEvaluator.evaluate(List.of(allow, ask, deny),
                context("execute_command", null, "git status --short", null));

        assertEquals(PermissionRuleEvaluator.Decision.DENY, result.decision());
        assertEquals("deny", result.matchedRule().id());
    }

    @Test
    void askWinsOverAllowEvenWhenAllowIsMoreSpecific() {
        var allow = rule("allow", PermissionRule.Effect.ALLOW, PermissionRule.Scope.COMMAND_PREFIX, "git status");
        var ask = rule("ask", PermissionRule.Effect.ASK, PermissionRule.Scope.TOOL, "execute_command");

        var result = PermissionRuleEvaluator.evaluate(List.of(allow, ask),
                context("execute_command", null, "git status", null));

        assertEquals(PermissionRuleEvaluator.Decision.ASK, result.decision());
        assertEquals("ask", result.matchedRule().id());
    }

    @Test
    void rulesAreIsolatedByProjectRoot() {
        var allow = rule("allow", PermissionRule.Effect.ALLOW, PermissionRule.Scope.TOOL, "write_file");
        var otherProject = new PermissionContext(Path.of("/workspace/other").toString(),
                "write_file", null, null, "src/App.java");

        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow), otherProject).decision());
    }

    @Test
    void commandPrefixRequiresTokenBoundaryAndRejectsShellSyntax() {
        var allow = rule("allow", PermissionRule.Effect.ALLOW, PermissionRule.Scope.COMMAND_PREFIX, "git status");

        assertEquals(PermissionRuleEvaluator.Decision.ALLOW,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("execute_command", null, "git   status --short", null)).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("execute_command", null, "git statusx", null)).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("execute_command", null, "git status && rm -rf /", null)).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("execute_command", null, "git status\nrm -rf /", null)).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("write_file", null, "git status", null)).decision());
    }

    @Test
    void exactCommandRuleDoesNotPermitAdditionalArguments() {
        var allow = rule("exact-command", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.COMMAND_EXACT, "git status");

        assertEquals(PermissionRuleEvaluator.Decision.ALLOW,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("execute_command", null, "git   status", null)).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("execute_command", null, "git status --short", null)).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("execute_command", null, "git status\nrm -rf /", null)).decision());
    }

    @Test
    void exactPathRuleDoesNotPermitSiblingOrDescendantPaths() {
        var allow = rule("exact-path", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.PATH_EXACT, "src/App.java");

        assertEquals(PermissionRuleEvaluator.Decision.ALLOW,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("write_file", null, null, "src/App.java")).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("write_file", null, null, "src/App.java/child")).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("write_file", null, null, "src/App2.java")).decision());
    }

    @Test
    void pathPrefixUsesSeparatorBoundaryAndDoesNotMatchSiblingNames() {
        var allow = rule("allow", PermissionRule.Effect.ALLOW, PermissionRule.Scope.PATH_PREFIX, "src/app");

        assertEquals(PermissionRuleEvaluator.Decision.ALLOW,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("write_file", null, null, "src/app/Main.java")).decision());
        assertEquals(PermissionRuleEvaluator.Decision.ALLOW,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("edit_file", null, null, "src/app")).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("write_file", null, null, "src/application/Main.java")).decision());
    }

    @Test
    void mcpServerRuleMatchesOnlyTheExactServer() {
        for (var effect : List.of(PermissionRule.Effect.DENY, PermissionRule.Effect.ASK)) {
            var server = rule("server", effect, PermissionRule.Scope.MCP_SERVER, "chrome-devtools");
            assertEquals(PermissionRuleEvaluator.Decision.valueOf(effect.name()),
                    PermissionRuleEvaluator.evaluate(List.of(server),
                            context(MCP_TOOL, "chrome-devtools", null, null)).decision());
            assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                    PermissionRuleEvaluator.evaluate(List.of(server),
                            context("mcp__chrome-devtools-next__navigate_page", "chrome-devtools-next", null, null)).decision());
        }
    }

    @Test
    void legacyMcpServerAllowIsReadableButNeverMatches() {
        var legacy = rule("server", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.MCP_SERVER, "chrome-devtools");
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(legacy),
                        context(MCP_TOOL, "chrome-devtools", null, null)).decision());
        assertThrows(IllegalArgumentException.class, () -> PermissionRule.create(ROOT,
                PermissionRule.Effect.ALLOW, PermissionRule.Scope.MCP_SERVER, "chrome-devtools", null, FINGERPRINT));
    }

    private PermissionContext mcpContext(String fingerprint, boolean current, boolean trusted, boolean ready) {
        return new PermissionContext(ROOT, MCP_TOOL, "chrome-devtools", null, null,
                fingerprint, current, trusted, ready);
    }

    @Test
    void mcpToolAllowRequiresUsableMatchingBindingAndExactTool() {
        var allow = PermissionRule.create(ROOT, PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.TOOL, MCP_TOOL, null, FINGERPRINT);
        var usable = mcpContext(FINGERPRINT, true, true, true);
        assertTrue(usable.mcpBindingUsable());
        assertEquals(PermissionRuleEvaluator.Decision.ALLOW,
                PermissionRuleEvaluator.evaluate(List.of(allow), usable).decision());

        for (var unavailable : List.of(
                context(MCP_TOOL, "chrome-devtools", null, null),
                mcpContext(null, true, true, true),
                mcpContext(FINGERPRINT, false, true, true),
                mcpContext(FINGERPRINT, true, false, true),
                mcpContext(FINGERPRINT, true, true, false))) {
            assertFalse(unavailable.mcpBindingUsable());
            assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                    PermissionRuleEvaluator.evaluate(List.of(allow), unavailable).decision());
        }
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        mcpContext("b".repeat(64), true, true, true)).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow), new PermissionContext(ROOT,
                        MCP_TOOL + "_next", "chrome-devtools", null, null,
                        FINGERPRINT, true, true, true)).decision());
    }

    @Test
    void mcpToolDenyAndAskDoNotRequireBindingAndKeepPrecedence() {
        var allow = PermissionRule.create(ROOT, PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.TOOL, MCP_TOOL, null, FINGERPRINT);
        var ask = rule("ask", PermissionRule.Effect.ASK, PermissionRule.Scope.TOOL, MCP_TOOL);
        var deny = rule("deny", PermissionRule.Effect.DENY, PermissionRule.Scope.TOOL, MCP_TOOL);
        var missing = context(MCP_TOOL, "chrome-devtools", null, null);
        assertEquals(PermissionRuleEvaluator.Decision.ASK,
                PermissionRuleEvaluator.evaluate(List.of(allow, ask), missing).decision());
        assertEquals(PermissionRuleEvaluator.Decision.DENY,
                PermissionRuleEvaluator.evaluate(List.of(allow, ask, deny), missing).decision());
        var usable = mcpContext(FINGERPRINT, true, true, true);
        assertEquals(PermissionRuleEvaluator.Decision.ASK,
                PermissionRuleEvaluator.evaluate(List.of(allow, ask), usable).decision());
        assertEquals(PermissionRuleEvaluator.Decision.DENY,
                PermissionRuleEvaluator.evaluate(List.of(allow, ask, deny), usable).decision());
    }

    @Test
    void legacyUnboundMcpAllowIsReadableButNeverMatchesAndMalformedBindingsAreRejected() {
        var legacy = rule("legacy", PermissionRule.Effect.ALLOW, PermissionRule.Scope.TOOL, MCP_TOOL);
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(legacy),
                        mcpContext(FINGERPRINT, true, true, true)).decision());
        for (String invalid : List.of("", "a".repeat(63), "a".repeat(65), "A".repeat(64),
                "g".repeat(64), " " + FINGERPRINT)) {
            assertThrows(IllegalArgumentException.class, () -> PermissionRule.create(ROOT,
                    PermissionRule.Effect.ALLOW, PermissionRule.Scope.TOOL, MCP_TOOL, null, invalid));
            var context = mcpContext(invalid, true, true, true);
            assertFalse(context.mcpBindingUsable());
        }
    }

    @Test
    void rejectsBindingOutsideMcpToolAllow() {
        for (var scope : PermissionRule.Scope.values()) {
            String matcher = switch (scope) {
                case TOOL -> "write_file";
                case MCP_SERVER -> "chrome-devtools";
                case COMMAND_EXACT, COMMAND_PREFIX -> "git status";
                case PATH_EXACT, PATH_PREFIX -> "src/App.java";
            };
            for (var effect : PermissionRule.Effect.values()) {
                assertThrows(IllegalArgumentException.class, () -> PermissionRule.create(ROOT,
                        effect, scope, matcher, null, FINGERPRINT));
            }
        }
        for (var effect : List.of(PermissionRule.Effect.DENY, PermissionRule.Effect.ASK)) {
            assertThrows(IllegalArgumentException.class, () -> PermissionRule.create(ROOT,
                    effect, PermissionRule.Scope.TOOL, MCP_TOOL, null, FINGERPRINT));
        }
    }

    @Test
    void jsonKeepsLegacyRulesButLegacyMcpAllowsRemainInert() throws Exception {
        var json = new ObjectMapper();
        var legacy = rule("legacy", PermissionRule.Effect.ALLOW, PermissionRule.Scope.TOOL, "write_file");
        var node = json.valueToTree(legacy);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("bindingFingerprint");
        var loaded = json.treeToValue(node, PermissionRule.class);
        assertNull(loaded.bindingFingerprint());
        assertEquals(PermissionRuleEvaluator.Decision.ALLOW, PermissionRuleEvaluator.evaluate(
                List.of(loaded), context("write_file", null, null, null)).decision());
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("matcher", MCP_TOOL);
        var legacyMcpTool = json.treeToValue(node, PermissionRule.class);
        assertNull(legacyMcpTool.bindingFingerprint());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH, PermissionRuleEvaluator.evaluate(
                List.of(legacyMcpTool), mcpContext(FINGERPRINT, true, true, true)).decision());
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("bindingFingerprint", FINGERPRINT);
        assertEquals(FINGERPRINT, json.treeToValue(node, PermissionRule.class).bindingFingerprint());
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("scope", "MCP_SERVER");
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).put("matcher", "chrome-devtools");
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).remove("bindingFingerprint");
        var legacyMcpServer = json.treeToValue(node, PermissionRule.class);
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH, PermissionRuleEvaluator.evaluate(
                List.of(legacyMcpServer), context(MCP_TOOL, "chrome-devtools", null, null)).decision());
    }

    @Test
    void exactToolRuleIsSelectedDeterministicallyForSameEffect() {
        var tool = rule("tool", PermissionRule.Effect.ALLOW, PermissionRule.Scope.TOOL, "execute_command");
        var prefix = rule("prefix", PermissionRule.Effect.ALLOW, PermissionRule.Scope.COMMAND_PREFIX, "git status");

        var result = PermissionRuleEvaluator.evaluate(List.of(prefix, tool),
                context("execute_command", null, "git status", null));

        assertEquals(PermissionRuleEvaluator.Decision.ALLOW, result.decision());
        assertEquals("tool", result.matchedRule().id());
    }

    @Test
    void rejectsUnsafeCommandAndPathMatchers() {
        assertThrows(IllegalArgumentException.class, () -> rule("bad", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.COMMAND_PREFIX, "git status && rm"));
        assertThrows(IllegalArgumentException.class, () -> rule("bad", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.PATH_PREFIX, "../outside"));
        assertThrows(IllegalArgumentException.class, () -> rule("bad", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.PATH_PREFIX, "."));
        assertThrows(IllegalArgumentException.class, () -> rule("bad", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.PATH_PREFIX, "CON.txt"));
    }
}
