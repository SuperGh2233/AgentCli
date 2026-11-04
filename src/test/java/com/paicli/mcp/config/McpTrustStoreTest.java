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
}
