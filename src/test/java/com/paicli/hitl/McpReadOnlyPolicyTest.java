package com.paicli.hitl;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class McpReadOnlyPolicyTest {
    private static final String SERVER = "a".repeat(64);
    private static final String TOOL = "b".repeat(64);

    private McpReadOnlyPolicy.Rule rule(String server, String tool, String identity, String fingerprint, boolean readOnly) {
        return new McpReadOnlyPolicy.Rule(server, tool, identity, fingerprint, readOnly);
    }

    private McpReadOnlyPolicy policy() {
        return new McpReadOnlyPolicy(List.of(rule("files", "read_file", SERVER, TOOL, true)));
    }

    @Test void defaultAndMissingRulesNeverMatch() {
        assertFalse(McpReadOnlyPolicy.DISABLED.matches("files", "read_file", SERVER, TOOL));
        assertFalse(new McpReadOnlyPolicy(null).matches("files", "read_file", SERVER, TOOL));
    }

    @Test void exactUserDeclarationMatches() {
        assertTrue(policy().matches("files", "read_file", SERVER, TOOL));
    }

    @Test void sameToolDifferentServerAndCaseNeverMatch() {
        for (String server : List.of("other", "Files", "files-more")) {
            assertFalse(policy().matches(server, "read_file", SERVER, TOOL));
        }
        for (String tool : List.of("read", "read_file_more", "READ_FILE", "write_file")) {
            assertFalse(policy().matches("files", tool, SERVER, TOOL));
        }
    }

    @Test void reconfigurationAndToolListChangesRequireNewEvidence() {
        assertFalse(policy().matches("files", "read_file", "c".repeat(64), TOOL));
        assertFalse(policy().matches("files", "read_file", SERVER, "c".repeat(64)));
        assertFalse(policy().matches("files", "read_file", null, TOOL));
        assertFalse(policy().matches("files", "read_file", SERVER, null));
    }

    @Test void invalidRuleClosesEntirePolicy() {
        var valid = rule("files", "read_file", SERVER, TOOL, true);
        var invalid = Arrays.asList(null,
                rule("files", "read_file", SERVER, TOOL, false),
                rule("*", "read_file", SERVER, TOOL, true),
                rule("files", "read_*", SERVER, TOOL, true),
                rule("files__other", "read_file", SERVER, TOOL, true),
                rule("files", "read__file", SERVER, TOOL, true),
                rule(" files", "read_file", SERVER, TOOL, true),
                rule("files", "read_file", "unverified", TOOL, true),
                rule("files", "read_file", SERVER, "B".repeat(64), true));
        for (var bad : invalid) {
            assertFalse(new McpReadOnlyPolicy(Arrays.asList(valid, bad))
                    .matches("files", "read_file", SERVER, TOOL));
        }
    }

    @Test void annotationsAndToolNamesDoNotGrantApproval() {
        assertTrue(ApprovalPolicy.requiresApproval("mcp__files__read_file"));
        assertTrue(ApprovalPolicy.requiresConfirmationByDefault("mcp__files__read_file"));
        assertFalse(McpReadOnlyPolicy.DISABLED.matches("files", "read_file", SERVER, TOOL));
    }

    @Test void defaultRegistryStillRequestsApprovalForReadNamedTool() {
        int[] approvals = {0};
        int[] executions = {0};
        HitlHandler handler = new HitlHandler() {
            public boolean isEnabled() { return true; }
            public void setEnabled(boolean enabled) { }
            public ApprovalResult requestApproval(ApprovalRequest request) {
                approvals[0]++;
                return ApprovalResult.reject("expected");
            }
        };
        var registry = new HitlToolRegistry(handler);
        var schema = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");
        registry.registerMcpTool(new com.paicli.mcp.protocol.McpToolDescriptor("files", "read_file",
                "mcp__files__read_file", "readOnlyHint=true", schema), args -> {
            executions[0]++;
            return "unexpected";
        });
        assertTrue(registry.executeTool("mcp__files__read_file", "{}").contains("[HITL]"));
        assertEquals(1, approvals[0]);
        assertEquals(0, executions[0]);
    }
}
