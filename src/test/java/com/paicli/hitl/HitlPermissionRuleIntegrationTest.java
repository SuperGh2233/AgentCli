package com.paicli.hitl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 3B1: permission evaluation is optional and never replaces the hard tool policies. */
class HitlPermissionRuleIntegrationTest {
    @TempDir Path temp;

    private Path project() throws Exception {
        return Files.createDirectories(temp.resolve("project"));
    }

    private PermissionRuleStore store() {
        return new PermissionRuleStore(temp.resolve("policy/permissions.json"));
    }

    private static String writeArguments(String path) {
        return "{\"path\":\"" + path + "\",\"content\":\"written\"}";
    }

    private PermissionRule rule(Path root, String id, PermissionRule.Effect effect,
                                PermissionRule.Scope scope, String matcher) throws Exception {
        return new PermissionRule(id, root.toRealPath().toString(), effect,
                scope, matcher, 1_783_000_000_000L, "integration test");
    }

    @Test
    void denyRuleBlocksWriteEvenWhenSessionCacheAllowsTheTool() throws Exception {
        Path root = project();
        TestHandler handler = new TestHandler(ApprovalResult.approve(), true);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        PermissionRuleStore rules = store();
        rules.add(rule(root, "deny-write", PermissionRule.Effect.DENY,
                PermissionRule.Scope.TOOL, "write_file"));
        registry.setPermissionRuleStore(rules);

        String result = registry.executeTool("write_file", writeArguments("forbidden.txt"));

        assertTrue(result.contains("deny-write"), result);
        assertFalse(Files.exists(root.resolve("forbidden.txt")));
        assertEquals(0, handler.requestCount);
    }

    @Test
    void askRuleClearsBroadSessionGrantAndRequiresExplicitApproval() throws Exception {
        Path root = project();
        TestHandler handler = new TestHandler(ApprovalResult.reject("not permitted"), true);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        PermissionRuleStore rules = store();
        rules.add(rule(root, "ask-write", PermissionRule.Effect.ASK,
                PermissionRule.Scope.TOOL, "write_file"));
        registry.setPermissionRuleStore(rules);

        String result = registry.executeTool("write_file", writeArguments("ask.txt"));

        assertTrue(result.contains("not permitted"), result);
        assertEquals(1, handler.requestCount);
        assertFalse(handler.cachedToolGrant);
        assertFalse(Files.exists(root.resolve("ask.txt")));
    }

    @Test
    void scopedAllowSkipsHitlButParentStillExecutesFileToolNormally() throws Exception {
        Path root = project();
        Files.createDirectories(root.resolve("src"));
        TestHandler handler = new TestHandler(ApprovalResult.reject("should not prompt"), false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        PermissionRuleStore rules = store();
        rules.add(rule(root, "allow-src", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.PATH_PREFIX, "src"));
        registry.setPermissionRuleStore(rules);

        String result = registry.executeTool("write_file", writeArguments("src/safe.txt"));

        assertFalse(result.contains("PERMISSION"), result);
        assertEquals("written", Files.readString(root.resolve("src/safe.txt")));
        assertEquals(0, handler.requestCount);
    }

    @Test
    void permanentAllowSavesOnlyTheExactApprovedProjectFile() throws Exception {
        Path root = project();
        TestHandler handler = new TestHandler(ApprovalResult.allowPermanently(), false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        PermissionRuleStore rules = store();
        registry.setPermissionRuleStore(rules);

        String result = registry.executeTool("write_file", writeArguments("notes.txt"));

        assertEquals("written", Files.readString(root.resolve("notes.txt")));
        assertEquals(1, rules.load().size());
        PermissionRule saved = rules.load().get(0);
        assertEquals(PermissionRule.Scope.PATH_EXACT, saved.scope());
        assertEquals("notes.txt", saved.matcher());
        assertEquals(root.toRealPath().toString(), saved.projectRoot());
        assertEquals(PermissionRule.Scope.PATH_EXACT, handler.lastRequest.permanentAllowProposal().scope());
        assertFalse(result.contains("PERMISSION"), result);
    }

    @Test
    void permanentAllowProposalUsesExactCommandNotACommandPrefix() throws Exception {
        Path root = project();
        PermissionRule proposal = PermissionRuleRuntime.proposePermanentAllow(store(), root.toString(),
                "execute_command", "{\"command\":\"git status\"}");

        assertNotNull(proposal);
        assertEquals(PermissionRule.Scope.COMMAND_EXACT, proposal.scope());
        assertEquals("git status", proposal.matcher());
        assertFalse(PermissionRuleRuntime.maySkipApproval(
                new PermissionRuleEvaluator.Evaluation(PermissionRuleEvaluator.Decision.ALLOW, proposal),
                "write_file"));
    }

    @Test
    void broadToolLevelAllowCannotDisableConfirmationForFileWrites() throws Exception {
        Path root = project();
        TestHandler handler = new TestHandler(ApprovalResult.reject("broad grant ignored"), false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        PermissionRuleStore rules = store();
        rules.add(rule(root, "broad-write", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.TOOL, "write_file"));
        registry.setPermissionRuleStore(rules);

        String result = registry.executeTool("write_file", writeArguments("not-written.txt"));

        assertTrue(result.contains("broad grant ignored"), result);
        assertEquals(1, handler.requestCount);
        assertFalse(Files.exists(root.resolve("not-written.txt")));
    }

    @Test
    void corruptStoreFailsClosedRatherThanApplyingAnOldAllow() throws Exception {
        Path root = project();
        Files.createDirectories(root.resolve("src"));
        TestHandler handler = new TestHandler(ApprovalResult.approve(), false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        PermissionRuleStore rules = store();
        rules.add(rule(root, "allow-src", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.PATH_PREFIX, "src"));
        Files.writeString(rules.file(), "{ invalid");
        registry.setPermissionRuleStore(rules);

        String result = registry.executeTool("write_file", writeArguments("src/file.txt"));

        assertTrue(result.contains("[PERMISSION]"), result);
        assertFalse(Files.exists(root.resolve("src/file.txt")));
        assertEquals(0, handler.requestCount);
    }

    @Test
    void commandPrefixAllowDoesNotMatchMultilineOrCompoundCommands() throws Exception {
        Path root = project();
        PermissionRuleStore rules = store();
        rules.add(rule(root, "safe-git", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.COMMAND_PREFIX, "git status"));

        var safe = PermissionRuleRuntime.evaluate(rules, root.toString(),
                "execute_command", "{\"command\":\"git status --short\"}");
        var multiline = PermissionRuleRuntime.evaluate(rules, root.toString(),
                "execute_command", "{\"command\":\"git status\\nrm -rf /tmp/example\"}");
        var compound = PermissionRuleRuntime.evaluate(rules, root.toString(),
                "execute_command", "{\"command\":\"git status && echo changed\"}");

        assertEquals(PermissionRuleEvaluator.Decision.ALLOW, safe.decision());
        assertTrue(PermissionRuleRuntime.maySkipApproval(safe, "execute_command"));
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH, multiline.decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH, compound.decision());
    }

    @Test
    void mcpAllowCannotSkipApprovalBeforeConfigFingerprintSupport() throws Exception {
        Path root = project();
        PermissionRuleStore rules = store();
        rules.add(rule(root, "mcp-scope", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.MCP_SERVER, "browser"));

        var result = PermissionRuleRuntime.evaluate(rules, root.toString(),
                "mcp__browser__navigate", "{}");

        assertEquals(PermissionRuleEvaluator.Decision.ALLOW, result.decision());
        assertFalse(PermissionRuleRuntime.maySkipApproval(result, "mcp__browser__navigate"));
    }

    @Test
    void escapingPathCannotMatchStoredPathPrefixAllow() throws Exception {
        Path root = project();
        PermissionRuleStore rules = store();
        rules.add(rule(root, "allow-src", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.PATH_PREFIX, "src"));

        var escaped = PermissionRuleRuntime.evaluate(rules, root.toString(),
                "write_file", writeArguments("../escape.txt"));

        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH, escaped.decision());
    }

    @Test
    void symlinkToOutsideCannotInheritProjectPathAllow() throws Exception {
        Path root = project();
        Path outside = Files.createDirectory(temp.resolve("external"));
        Files.createDirectories(root.resolve("src"));
        Files.createSymbolicLink(root.resolve("src/link"), outside);
        PermissionRuleStore rules = store();
        rules.add(rule(root, "allow-src", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.PATH_PREFIX, "src"));

        var result = PermissionRuleRuntime.evaluate(rules, root.toString(),
                "write_file", writeArguments("src/link/escape.txt"));

        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH, result.decision());
        assertFalse(Files.exists(outside.resolve("escape.txt")));
    }

    @Test
    void withoutConfiguredStoreNormalHitlBehaviorIsPreserved() throws Exception {
        Path root = project();
        TestHandler handler = new TestHandler(ApprovalResult.reject("normal HITL"), false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());

        String result = registry.executeTool("write_file", writeArguments("existing.txt"));

        assertTrue(result.contains("normal HITL"), result);
        assertEquals(1, handler.requestCount);
        assertFalse(Files.exists(root.resolve("existing.txt")));
    }

    private static final class TestHandler implements HitlHandler {
        private final ApprovalResult answer;
        private ApprovalRequest lastRequest;
        private boolean cachedToolGrant;
        private int requestCount;

        TestHandler(ApprovalResult answer, boolean cachedToolGrant) {
            this.answer = answer;
            this.cachedToolGrant = cachedToolGrant;
        }

        @Override public ApprovalResult requestApproval(ApprovalRequest request) {
            requestCount++;
            lastRequest = request;
            return answer;
        }

        @Override public boolean isEnabled() { return true; }
        @Override public void setEnabled(boolean enabled) { }
        @Override public boolean isApprovedAllByTool(String name) { return cachedToolGrant; }
        @Override public void clearApprovedAll() { cachedToolGrant = false; }
    }
}
