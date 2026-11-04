package com.paicli.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PermissionRuleStoreTest {
    @TempDir Path temp;

    private PermissionRule rule(String id, PermissionRule.Effect effect, String matcher) {
        return new PermissionRule(id, temp.toString(), effect, PermissionRule.Scope.TOOL,
                matcher, Instant.parse("2026-11-04T00:00:00Z").toEpochMilli(), "test rule");
    }

    private PermissionRuleStore store() {
        return new PermissionRuleStore(temp.resolve("security/permissions.json"));
    }

    @Test
    void addLoadReplaceAndRemoveRules() throws Exception {
        PermissionRuleStore store = store();
        assertTrue(store.load().isEmpty());

        var first = rule("rule-1", PermissionRule.Effect.ALLOW, "write_file");
        assertEquals(1, store.add(first).size());
        assertEquals(first, store.load().get(0));

        var replacement = rule("rule-1", PermissionRule.Effect.DENY, "write_file");
        assertEquals(1, store.add(replacement).size());
        assertEquals(replacement, store.load().get(0));

        assertTrue(store.remove("rule-1"));
        assertFalse(store.remove("rule-1"));
        assertTrue(store.load().isEmpty());
    }

    @Test
    void corruptStoreFailsClosedAndIsNotOverwritten() throws Exception {
        PermissionRuleStore store = store();
        store.add(rule("rule-1", PermissionRule.Effect.ALLOW, "write_file"));
        Files.writeString(store.file(), "{ broken");
        byte[] before = Files.readAllBytes(store.file());

        assertThrows(IOException.class, store::load);
        assertThrows(IOException.class, () -> store.add(rule("rule-2", PermissionRule.Effect.ALLOW, "edit_file")));
        assertArrayEquals(before, Files.readAllBytes(store.file()));
    }

    @Test
    void refusesSymlinkedRuleFileWithoutTouchingTarget() throws Exception {
        PermissionRuleStore store = store();
        store.add(rule("rule-1", PermissionRule.Effect.ALLOW, "write_file"));
        Path outside = temp.resolve("outside.json");
        Files.writeString(outside, "outside-safe");
        Files.delete(store.file());
        Files.createSymbolicLink(store.file(), outside);

        assertThrows(IOException.class, store::load);
        assertEquals("outside-safe", Files.readString(outside));
    }

    @Test
    void refusesStoreInsideActiveProjectRoot() throws Exception {
        PermissionRuleStore store = store();
        assertThrows(IOException.class, () -> store.requireOutsideProject(temp));
        assertDoesNotThrow(() -> store.requireOutsideProject(Files.createDirectory(temp.resolve("project"))));
    }

    @Test
    void securesDirectoryAndRuleFileOnPosixFileSystems() throws Exception {
        PermissionRuleStore store = store();
        store.add(rule("rule-1", PermissionRule.Effect.ALLOW, "write_file"));
        if (Files.getFileStore(temp).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE),
                    Files.getPosixFilePermissions(store.file().getParent()));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(store.file()));
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(store.file().resolveSibling("permissions.json.lock")));
        }
    }

    @Test
    void rejectsSymlinkedSecurityDirectory() throws Exception {
        PermissionRuleStore store = store();
        Path outside = Files.createDirectory(temp.resolve("outside-security"));
        Files.createSymbolicLink(store.file().getParent(), outside);

        assertThrows(IOException.class, () -> store.add(rule("rule-1", PermissionRule.Effect.ALLOW, "write_file")));
        assertFalse(Files.exists(outside.resolve("permissions.json")));
    }

    @Test
    void concurrentAddsDoNotLoseRules() throws Exception {
        PermissionRuleStore store = store();
        var executor = Executors.newFixedThreadPool(4);
        try {
            var futures = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> executor.submit(() -> store.add(rule("rule-" + i,
                            PermissionRule.Effect.ALLOW, "tool_" + i))))
                    .toList();
            for (var future : futures) future.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(8, store.load().size());
    }

    @Test
    void clearProjectPreservesRulesFromOtherProjects() throws Exception {
        PermissionRuleStore store = store();
        store.add(rule("local-1", PermissionRule.Effect.ALLOW, "write_file"));
        String otherProject = temp.resolve("another-project").toAbsolutePath().normalize().toString();
        store.add(new PermissionRule("other-1", otherProject, PermissionRule.Effect.ALLOW,
                PermissionRule.Scope.TOOL, "edit_file", System.currentTimeMillis(), "other project"));

        assertEquals(1, store.clearProject(temp.toString()));
        assertEquals(1, store.load().size());
        assertEquals("other-1", store.load().get(0).id());
        assertFalse(store.remove("other-1", temp.toString()));
    }

    @Test
    void rejectsDuplicateIdsWhenReadingFile() throws Exception {
        PermissionRuleStore store = store();
        store.add(rule("rule-1", PermissionRule.Effect.ALLOW, "write_file"));
        ObjectMapper mapper = new ObjectMapper();
        var root = mapper.readTree(Files.readString(store.file()));
        ArrayNode rules = (ArrayNode) root.path("rules");
        rules.add(rules.get(0).deepCopy());
        Files.writeString(store.file(), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root));

        assertThrows(IOException.class, store::load);
    }
}
