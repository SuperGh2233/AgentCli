package com.paicli.mcp.config;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class McpTrustStoreTest {
    @TempDir Path temp;

    private Path trustFile() throws IOException {
        Files.createDirectories(temp.resolve("state"));
        return temp.resolve("state/mcp-trust.json");
    }

    private Path projectConfig(String name) {
        return temp.resolve(name).resolve(".paicli/mcp.json");
    }

    private String fingerprint(char value) {
        return String.valueOf(value).repeat(64);
    }

    @Test
    void trustAndUntrustAreScopedByConfigPathAndServerName() throws Exception {
        McpTrustStore store = new McpTrustStore(trustFile());
        Path projectA = projectConfig("a");
        Path projectB = projectConfig("b");
        store.trust(projectA, "repo", fingerprint('a'));
        store.trust(projectB, "repo", fingerprint('b'));
        store.trust(projectA, "filesystem", fingerprint('c'));

        assertTrue(store.isTrusted(projectA, "repo", fingerprint('a')));
        assertFalse(store.isTrusted(projectA, "repo", fingerprint('b')));
        assertTrue(store.isTrusted(projectB, "repo", fingerprint('b')));

        assertTrue(store.untrust(projectA, "repo"));
        assertFalse(store.isTrusted(projectA, "repo", fingerprint('a')));
        assertTrue(store.isTrusted(projectB, "repo", fingerprint('b')));
        assertTrue(store.isTrusted(projectA, "filesystem", fingerprint('c')));
        assertFalse(store.untrust(projectA, "repo"));
    }

    @Test
    void corruptTrustFileFailsClosedAndIsNotOverwritten() throws Exception {
        Path file = trustFile();
        Files.writeString(file, "{not-json");
        McpTrustStore store = new McpTrustStore(file);

        assertFalse(store.isTrusted(projectConfig("a"), "repo", fingerprint('a')));
        assertThrows(IOException.class, () -> store.trust(projectConfig("a"), "repo", fingerprint('a')));
        assertEquals("{not-json", Files.readString(file));
    }

    @Test
    void symlinkedTrustFileFailsClosedAndCannotBeReplaced() throws Exception {
        Path target = temp.resolve("target.json");
        Files.writeString(target, "{\"trustedProjectServers\":[]}");
        Path file = temp.resolve("state/mcp-trust.json");
        Files.createDirectories(file.getParent());
        try {
            Files.createSymbolicLink(file, target);
        } catch (UnsupportedOperationException | IOException error) {
            Assumptions.assumeTrue(false, "symbolic links unavailable in this environment");
        }
        McpTrustStore store = new McpTrustStore(file);

        assertFalse(store.isTrusted(projectConfig("a"), "repo", fingerprint('a')));
        assertThrows(IOException.class, () -> store.trust(projectConfig("a"), "repo", fingerprint('a')));
        assertTrue(Files.isSymbolicLink(file));
    }

    @Test
    void trustFileAndDirectoryAreRestrictedToOwner() throws Exception {
        Path file = trustFile();
        McpTrustStore store = new McpTrustStore(file);
        store.trust(projectConfig("a"), "repo", fingerprint('a'));

        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE),
                Files.getPosixFilePermissions(file.getParent()));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file.resolveSibling(file.getFileName() + ".lock")));
    }

    @Test
    void hmacIsStablePrivateAndSensitiveToSecrets() throws Exception {
        Path file = trustFile();
        McpTrustStore store = new McpTrustStore(file);
        McpServerConfig config = new McpServerConfig();
        config.setCommand("node");
        config.setEnv(java.util.Map.of("TOKEN", "fixture-value-one"));
        String first = store.fingerprint(McpConfigLoader.fingerprintPayload(config), true);
        byte[] stored = Files.readAllBytes(file);
        assertTrue(first.matches("[0-9a-f]{64}"));
        assertFalse(first.contains(config.getEnv().get("TOKEN")));
        assertEquals(first, new McpTrustStore(file).fingerprint(McpConfigLoader.fingerprintPayload(config), true));
        assertArrayEquals(stored, Files.readAllBytes(file), "Existing key must never be regenerated");
        config.setEnv(java.util.Map.of("TOKEN", "fixture-value-two"));
        assertNotEquals(first, store.fingerprint(McpConfigLoader.fingerprintPayload(config), false));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                Files.getPosixFilePermissions(file));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE), Files.getPosixFilePermissions(file.getParent()));
    }

    @Test
    void legacyTrustMigratesKeyButNeverInheritsShaTrust() throws Exception {
        Path file = trustFile();
        Path project = projectConfig("legacy");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = mapper.createObjectNode();
        root.putArray("trustedProjectServers").addObject()
                .put("config", project.toString()).put("name", "repo").put("sha256", fingerprint('a'));
        Files.write(file, mapper.writeValueAsBytes(root));
        McpTrustStore store = new McpTrustStore(file);
        assertFalse(store.isTrusted(project, "repo", fingerprint('a')));
        byte[] before = Files.readAllBytes(file);
        assertThrows(IOException.class, () -> store.fingerprint(new byte[]{1}, false));
        assertArrayEquals(before, Files.readAllBytes(file));
        String hmac = store.fingerprint(new byte[]{1}, true);
        var migrated = mapper.readTree(Files.readAllBytes(file));
        assertEquals(32, java.util.Base64.getDecoder().decode(migrated.path("fingerprintKey").asText()).length);
        assertEquals(0, migrated.path("trustedProjectServers").size());
        assertFalse(store.isTrusted(project, "repo", fingerprint('a')));
        assertFalse(store.isTrusted(project, "repo", hmac));
        store.trust(project, "repo", hmac);
        assertTrue(store.isTrusted(project, "repo", hmac));
    }

    @Test
    void malformedKeysAndCorruptFilesAreNeverReplaced() throws Exception {
        Path file = trustFile();
        McpTrustStore store = new McpTrustStore(file);
        for (String key : java.util.List.of("null", "123", "\"bad\"", "\"AAAA\"")) {
            String json = "{\"trustedProjectServers\":[],\"fingerprintKey\":" + key + "}";
            Files.writeString(file, json);
            assertThrows(IOException.class, () -> store.fingerprint(new byte[]{1}, true));
            assertThrows(IOException.class, () -> store.trust(projectConfig("a"), "repo", fingerprint('a')));
            assertFalse(store.isTrusted(projectConfig("a"), "repo", fingerprint('a')));
            assertEquals(json, Files.readString(file));
        }
        Files.writeString(file, "{broken");
        assertThrows(IOException.class, () -> store.fingerprint(new byte[]{1}, true));
        assertEquals("{broken", Files.readString(file));
    }

    @Test
    void unsafeKeyStorageAndLockFailClosed() throws Exception {
        Path file = trustFile();
        McpTrustStore store = new McpTrustStore(file);
        Path lock = file.resolveSibling(file.getFileName() + ".lock");
        Files.createDirectory(lock);
        assertThrows(IOException.class, () -> store.fingerprint(new byte[]{1}, true));
        assertFalse(Files.exists(file));
        Files.delete(lock);
        Path target = temp.resolve("key-target.json");
        Files.writeString(target, "{\"trustedProjectServers\":[]}");
        Files.createSymbolicLink(file, target);
        assertThrows(IOException.class, () -> store.fingerprint(new byte[]{1}, true));
        assertEquals("{\"trustedProjectServers\":[]}", Files.readString(target));
    }
}
