package com.paicli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paicli.mcp.McpPermissionBinding;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.tool.ToolOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    void askRuleBypassesBroadSessionGrantAndRequiresExplicitApproval() throws Exception {
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
        assertTrue(handler.cachedToolGrant,
                "ASK must bypass the existing grant for this call without mutating session-wide state");
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
    void legacyMcpAllowRulesRemainInertWithoutDisablingOtherRulesOrRevocation() throws Exception {
        Path root = project();
        PermissionRuleStore rules = store();
        rules.add(rule(root, "legacy-server", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.MCP_SERVER, "browser"));
        rules.add(rule(root, "legacy-tool", PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.TOOL, "mcp__browser__navigate"));
        rules.add(rule(root, "deny-write", PermissionRule.Effect.DENY,
                PermissionRule.Scope.TOOL, "write_file"));

        var serverAllow = PermissionRuleRuntime.evaluate(rules, root.toString(),
                "mcp__browser__navigate", "{}");
        var toolAllow = PermissionRuleRuntime.evaluate(rules, root.toString(),
                "mcp__browser__navigate", "{}",
                new McpPermissionBinding("browser", "a".repeat(64), true, true, true, true));
        var deny = PermissionRuleRuntime.evaluate(rules, root.toString(),
                "write_file", writeArguments("blocked.txt"));

        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH, serverAllow.decision());
        assertEquals(PermissionRuleEvaluator.Decision.NO_MATCH, toolAllow.decision());
        assertEquals(PermissionRuleEvaluator.Decision.DENY, deny.decision());
        assertEquals(3, rules.load().size());
        assertTrue(rules.remove("legacy-server", root.toRealPath().toString()));
        assertEquals(2, rules.load().size());
        assertEquals(2, rules.clearProject(root.toRealPath().toString()));
        assertTrue(rules.load().isEmpty());
    }

    @Test
    void exactMcpAllowSkipsApprovalOnlyWithCurrentTrustedReadyBinding() throws Exception {
        Path root = project();
        String fingerprint = "a".repeat(64);
        PermissionRuleStore rules = store();
        rules.add(PermissionRule.create(root.toRealPath().toString(), PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.TOOL, "mcp__browser__navigate", "exact MCP tool", fingerprint));
        TestHandler handler = new TestHandler(ApprovalResult.reject("must not prompt"), true);
        HitlToolRegistry registry = mcpRegistry(root, handler, rules,
                new McpPermissionBinding("browser", fingerprint, true, true, true, true));

        String result = registry.executeTool("mcp__browser__navigate", "{}");

        assertEquals("mcp executed", result);
        assertEquals(0, handler.requestCount);
    }

    @Test
    void staleMcpBindingClearsSessionGrantAndBlocksExecution() throws Exception {
        Path root = project();
        String fingerprint = "a".repeat(64);
        TestHandler handler = new TestHandler(ApprovalResult.approve(), true);
        HitlToolRegistry registry = mcpRegistry(root, handler, store(),
                new McpPermissionBinding("browser", fingerprint, true, true, true, false));
        handler.cachedToolGrant = true;

        String result = registry.executeTool("mcp__browser__navigate", "{}");

        assertTrue(result.contains("[PERMISSION]"), result);
        assertFalse(handler.cachedToolGrant);
        assertEquals(0, handler.requestCount);
    }

    @Test
    void permanentMcpAllowIsBoundToExactToolAndCurrentFingerprint() throws Exception {
        Path root = project();
        String fingerprint = "b".repeat(64);
        PermissionRuleStore rules = store();
        TestHandler handler = new TestHandler(ApprovalResult.allowPermanently(), false);
        HitlToolRegistry registry = mcpRegistry(root, handler, rules,
                new McpPermissionBinding("browser", fingerprint, true, true, true, true));

        String result = registry.executeTool("mcp__browser__navigate", "{}");

        assertEquals("mcp executed", result);
        assertEquals(1, rules.load().size());
        PermissionRule saved = rules.load().get(0);
        assertEquals(PermissionRule.Scope.TOOL, saved.scope());
        assertEquals("mcp__browser__navigate", saved.matcher());
        assertEquals(fingerprint, saved.bindingFingerprint());
        assertEquals("HMAC-SHA256 bbbbbbbbbbbb…",
                ApprovalRequest.bindingDescription(handler.lastRequest.permanentAllowProposal()));
    }

    @Test
    void mcpConfigChangeDuringApprovalPreventsRuleSaveAndToolExecution() throws Exception {
        Path root = project();
        String first = "c".repeat(64);
        String second = "d".repeat(64);
        AtomicInteger calls = new AtomicInteger();
        TestHandler handler = new TestHandler(ApprovalResult.allowPermanently(), false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        registry.setPermissionRuleStore(store());
        registry.setMcpPermissionBindingProvider(server -> new McpPermissionBinding(
                server, calls.incrementAndGet() <= 2 ? first : second, true, true, true, true));
        registry.registerMcpToolOutput(mcpDescriptor(), ignored -> ToolOutput.text("mcp executed"));

        String result = registry.executeTool("mcp__browser__navigate", "{}");

        assertTrue(result.contains("在审批期间发生变化"), result);
        assertEquals(0, store().load().size());
        assertEquals(1, handler.requestCount);
    }

    @Test
    void replacingMcpToolDuringApprovalDoesNotCommitOldSessionGrant() throws Exception {
        Path root = project();
        String fingerprint = "f".repeat(64);
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicInteger replacementCalls = new AtomicInteger();
        TestHandler handler = new TestHandler(ApprovalResult.approveAll(), false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        registry.setPermissionRuleStore(store());
        registry.setMcpPermissionBindingProvider(server ->
                new McpPermissionBinding(server, fingerprint, true, true, true, true));
        registry.registerMcpToolOutput(mcpDescriptor(), ignored -> {
            oldCalls.incrementAndGet();
            return ToolOutput.text("old invoker");
        });
        handler.beforeAnswer = () -> registry.registerMcpToolOutput(mcpDescriptor(), ignored -> {
            replacementCalls.incrementAndGet();
            return ToolOutput.text("replacement invoker");
        });

        String result = registry.executeTool("mcp__browser__navigate", "{}");

        assertTrue(result.contains("被替换"), result);
        assertEquals(0, oldCalls.get());
        assertEquals(0, replacementCalls.get());
        assertFalse(handler.cachedToolGrant, "stale approval must not leave a session grant behind");
        assertTrue(store().load().isEmpty(), "replacement must not leave a permanent allow behind");
    }

    @Test
    void explicitAskRuleCannotBeSatisfiedBySessionWideApproval() throws Exception {
        Path root = project();
        PermissionRuleStore rules = store();
        rules.add(rule(root, "ask-mcp", PermissionRule.Effect.ASK,
                PermissionRule.Scope.TOOL, "mcp__browser__navigate"));
        TestHandler handler = new TestHandler(ApprovalResult.approveAll(), true);
        HitlToolRegistry registry = mcpRegistry(root, handler, rules,
                new McpPermissionBinding("browser", "8".repeat(64), true, true, true, true));
        handler.cachedToolGrant = true;

        String result = registry.executeTool("mcp__browser__navigate", "{}");

        assertTrue(result.contains("逐次确认"), result);
        assertTrue(handler.lastRequest.forcePerCall());
        assertEquals(1, handler.requestCount);
        assertTrue(handler.cachedToolGrant,
                "explicit ASK bypasses the existing grant for this call without mutating shared session state");
    }

    @Test
    void explicitAskRuleRejectsPermanentAllowDecision() throws Exception {
        Path root = project();
        PermissionRuleStore rules = store();
        rules.add(rule(root, "ask-mcp-no-permanent", PermissionRule.Effect.ASK,
                PermissionRule.Scope.TOOL, "mcp__browser__navigate"));
        TestHandler handler = new TestHandler(ApprovalResult.allowPermanently(), false);
        HitlToolRegistry registry = mcpRegistry(root, handler, rules,
                new McpPermissionBinding("browser", "7".repeat(64), true, true, true, true));

        String result = registry.executeTool("mcp__browser__navigate", "{}");

        assertTrue(result.contains("不允许会话级或永久放行"), result);
        assertTrue(rules.load().stream().noneMatch(rule -> rule.effect() == PermissionRule.Effect.ALLOW));
    }

    @Test
    void mcpRegistrationReplacementWaitsForInFlightBoundInvocation() throws Exception {
        Path root = project();
        String fingerprint = "9".repeat(64);
        CountDownLatch invocationStarted = new CountDownLatch(1);
        CountDownLatch finishInvocation = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1);
        CountDownLatch replacementFinished = new CountDownLatch(1);
        AtomicInteger oldCalls = new AtomicInteger();
        AtomicReference<String> result = new AtomicReference<>();
        TestHandler handler = new TestHandler(ApprovalResult.approveAll(), false);
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        registry.setMcpPermissionBindingProvider(server ->
                new McpPermissionBinding(server, fingerprint, true, true, true, true));
        registry.registerMcpToolOutput(mcpDescriptor(), ignored -> {
            oldCalls.incrementAndGet();
            invocationStarted.countDown();
            try {
                if (!finishInvocation.await(5, TimeUnit.SECONDS)) {
                    return ToolOutput.failure("test invocation timed out");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return ToolOutput.failure("test invocation interrupted");
            }
            return ToolOutput.text("old invocation completed");
        });

        Thread invocation = new Thread(() -> result.set(
                registry.executeTool("mcp__browser__navigate", "{}")), "mcp-invocation-test");
        invocation.start();
        assertTrue(invocationStarted.await(5, TimeUnit.SECONDS));

        Thread replacement = new Thread(() -> {
            replacementStarted.countDown();
            registry.registerMcpToolOutput(mcpDescriptor(), ignored -> ToolOutput.text("replacement"));
            replacementFinished.countDown();
        }, "mcp-registration-replacement-test");
        replacement.start();
        assertTrue(replacementStarted.await(5, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (replacement.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(Thread.State.WAITING, replacement.getState(),
                "replacement must wait while the captured MCP invoker is running");

        finishInvocation.countDown();
        invocation.join(5_000);
        replacement.join(5_000);

        assertFalse(invocation.isAlive());
        assertFalse(replacement.isAlive());
        assertTrue(replacementFinished.await(1, TimeUnit.SECONDS));
        assertEquals("old invocation completed", result.get());
        assertEquals(1, oldCalls.get());
        assertFalse(handler.cachedToolGrant,
                "the replacement must revoke the grant after waiting for the in-flight invocation");
    }

    @Test
    void mcpPermanentAllowProposalRequiresUsableMatchingBinding() throws Exception {
        Path root = project();
        String fingerprint = "e".repeat(64);
        assertNull(PermissionRuleRuntime.proposePermanentAllow(store(), root.toString(),
                "mcp__browser__navigate", "{}", null));
        assertNull(PermissionRuleRuntime.proposePermanentAllow(store(), root.toString(),
                "mcp__browser__navigate", "{}",
                new McpPermissionBinding("browser", fingerprint, true, true, true, false)));
        assertNull(PermissionRuleRuntime.proposePermanentAllow(store(), root.toString(),
                "mcp__browser__navigate", "{}",
                new McpPermissionBinding("other", fingerprint, true, true, true, true)));
        PermissionRule proposal = PermissionRuleRuntime.proposePermanentAllow(store(), root.toString(),
                "mcp__browser__navigate", "{}",
                new McpPermissionBinding("browser", fingerprint, true, true, true, true));
        assertNotNull(proposal);
        assertEquals(PermissionRule.Scope.TOOL, proposal.scope());
        assertEquals("mcp__browser__navigate", proposal.matcher());
        assertEquals(fingerprint, proposal.bindingFingerprint());
    }

    private HitlToolRegistry mcpRegistry(Path root, TestHandler handler, PermissionRuleStore rules,
                                         McpPermissionBinding binding) {
        HitlToolRegistry registry = new HitlToolRegistry(handler);
        registry.setProjectPath(root.toString());
        registry.setPermissionRuleStore(rules);
        registry.setMcpPermissionBindingProvider(ignored -> binding);
        registry.registerMcpToolOutput(mcpDescriptor(), ignored -> ToolOutput.text("mcp executed"));
        return registry;
    }

    private static McpToolDescriptor mcpDescriptor() {
        return new McpToolDescriptor("browser", "navigate", "mcp__browser__navigate",
                "Navigate", new ObjectMapper().createObjectNode());
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
        private Runnable beforeAnswer;

        TestHandler(ApprovalResult answer, boolean cachedToolGrant) {
            this.answer = answer;
            this.cachedToolGrant = cachedToolGrant;
        }

        @Override public ApprovalResult requestApproval(ApprovalRequest request) {
            requestCount++;
            lastRequest = request;
            if (beforeAnswer != null) beforeAnswer.run();
            return answer;
        }

        @Override public boolean isEnabled() { return true; }
        @Override public void setEnabled(boolean enabled) { }
        @Override public boolean isApprovedAllByTool(String name) { return cachedToolGrant; }
        @Override public void commitApproval(ApprovalRequest request, ApprovalResult result) {
            if (request != null && result != null && !request.forcePerCall()
                    && (result.isApprovedAllForTool() || result.isApprovedAllForServer())) {
                cachedToolGrant = true;
            }
        }
        @Override public void clearApprovedAll() { cachedToolGrant = false; }
        @Override public void clearApprovedAllForServer(String serverName) { cachedToolGrant = false; }
    }
}
