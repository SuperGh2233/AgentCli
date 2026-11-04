package com.paicli.hitl;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionRuleEvaluatorTest {
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
        var allow = rule("server", PermissionRule.Effect.ALLOW, PermissionRule.Scope.MCP_SERVER, "chrome-devtools");

        assertEquals(PermissionRuleEvaluator.Decision.ALLOW,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("mcp__chrome-devtools__navigate_page", "chrome-devtools", null, null)).decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH,
                PermissionRuleEvaluator.evaluate(List.of(allow),
                        context("mcp__chrome-devtools-next__navigate_page", "chrome-devtools-next", null, null)).decision());
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
