package com.paicli.cli;

import com.paicli.hitl.PermissionRule;
import com.paicli.hitl.PermissionRuleStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PermissionCommandTest {
    @TempDir Path temp;

    private Path project() throws Exception {
        return Files.createDirectories(temp.resolve("project"));
    }

    private PermissionRuleStore store() {
        return new PermissionRuleStore(temp.resolve("user-policy/permissions.json"));
    }

    private PermissionRule rule(String id, Path project, String matcher) throws Exception {
        return new PermissionRule(id, project.toRealPath().toString(), PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.PATH_EXACT, matcher, System.currentTimeMillis(), "test");
    }

    @Test
    void listShowsOnlyRulesForCurrentProject() throws Exception {
        Path current = project();
        Path other = Files.createDirectories(temp.resolve("other"));
        PermissionRuleStore store = store();
        store.add(rule("current-rule", current, "src/App.java"));
        store.add(rule("other-rule", other, "src/Other.java"));

        String result = Main.handlePermissionsCommand(store, "list", current);

        assertTrue(result.contains("current-rule"), result);
        assertTrue(result.contains("src/App.java"), result);
        assertFalse(result.contains("other-rule"), result);
        assertFalse(result.contains("src/Other.java"), result);
    }

    @Test
    void revokeCannotRemoveAnotherProjectsRule() throws Exception {
        Path current = project();
        Path other = Files.createDirectories(temp.resolve("other"));
        PermissionRuleStore store = store();
        store.add(rule("other-rule", other, "src/Other.java"));

        String result = Main.handlePermissionsCommand(store, "revoke other-rule", current);

        assertTrue(result.contains("未找到"), result);
        assertEquals(1, store.load().size());
        assertEquals("other-rule", store.load().get(0).id());
    }

    @Test
    void clearRequiresConfirmationAndPreservesOtherProjects() throws Exception {
        Path current = project();
        Path other = Files.createDirectories(temp.resolve("other"));
        PermissionRuleStore store = store();
        store.add(rule("current-rule", current, "src/App.java"));
        store.add(rule("other-rule", other, "src/Other.java"));

        String prompt = Main.handlePermissionsCommand(store, "clear", current);
        assertTrue(prompt.contains("--confirm"), prompt);
        assertEquals(2, store.load().size());

        String result = Main.handlePermissionsCommand(store, "clear --confirm", current);
        assertTrue(result.contains("1 条"), result);
        assertEquals(1, store.load().size());
        assertEquals("other-rule", store.load().get(0).id());
    }

    @Test
    void parserRecognizesPermissionManagementCommands() {
        var list = CliCommandParser.parse("/permissions");
        var revoke = CliCommandParser.parse("/permissions revoke rule-123");
        var clear = CliCommandParser.parse("/permissions clear --confirm");

        assertEquals(CliCommandParser.CommandType.PERMISSIONS, list.type());
        assertEquals("list", list.payload());
        assertEquals(CliCommandParser.CommandType.PERMISSIONS, revoke.type());
        assertEquals("revoke rule-123", revoke.payload());
        assertEquals(CliCommandParser.CommandType.PERMISSIONS, clear.type());
        assertEquals("clear --confirm", clear.payload());
    }
}
