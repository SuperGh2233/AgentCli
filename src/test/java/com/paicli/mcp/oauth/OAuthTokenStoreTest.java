package com.paicli.mcp.oauth;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class OAuthTokenStoreTest {
    @TempDir Path temp;
    OAuthTokenStore.Token token(String issuer, String access) { return new OAuthTokenStore.Token("https://mcp.example/mcp",issuer,"client","https://as.example/token",access,"refresh",0); }
    @Test void secureAtomicReplacementAndIssuerBinding() throws Exception {
        Path file=temp.resolve("private/tokens.json");var store=new OAuthTokenStore(file);
        store.save(token("https://as.example", "one"));Object before=Files.readAttributes(file,BasicFileAttributes.class).fileKey();
        store.save(token("https://other.example", "two"));
        assertEquals("one",store.find("https://mcp.example/mcp","https://as.example","client").accessToken());
        assertEquals("two",store.find("https://mcp.example/mcp","https://other.example","client").accessToken());
        assertNull(store.find("https://different.example/mcp","https://as.example","client"));
        assertNull(store.find("https://mcp.example/mcp","https://as.example","different-client"));
        assertNotEquals(before, Files.readAttributes(file,BasicFileAttributes.class).fileKey());
        assertEquals("rw-------",PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        assertEquals("rwx------",PosixFilePermissions.toString(Files.getPosixFilePermissions(file.getParent())));
        store.delete("https://mcp.example/mcp","https://as.example","client");
        assertNull(store.find("https://mcp.example/mcp","https://as.example","client"));
        assertNotNull(store.find("https://mcp.example/mcp","https://other.example","client"));
        store.deleteAllIssuers("https://mcp.example/mcp","client");
        assertNull(store.find("https://mcp.example/mcp","https://other.example","client"));
        try(var files=Files.list(file.getParent())) { assertFalse(files.anyMatch(p->p.toString().endsWith(".tmp"))); }
    }
    @Test void defaultStoreCreatesDedicatedPrivateDirectoryUnderExisting755Root() throws Exception {
        String previousHome=System.getProperty("user.home");String previousFile=System.getProperty("paicli.mcp.oauth.tokens");
        Path root=Files.createDirectory(temp.resolve(".paicli"));
        Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwxr-xr-x"));
        try {
            System.setProperty("user.home",temp.toString());System.clearProperty("paicli.mcp.oauth.tokens");
            var store=OAuthTokenStore.defaultStore();store.save(token("issuer","secret"));
            assertTrue(Files.isRegularFile(root.resolve("oauth/mcp-oauth-tokens.json")));
            assertEquals("rwx------",PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve("oauth"))));
            assertEquals("rwxr-xr-x",PosixFilePermissions.toString(Files.getPosixFilePermissions(root)));
        } finally {
            System.setProperty("user.home",previousHome);
            if(previousFile==null) System.clearProperty("paicli.mcp.oauth.tokens");else System.setProperty("paicli.mcp.oauth.tokens",previousFile);
        }
    }
    @Test void refusesSharedParentWithoutChangingPermissions() throws Exception {
        Path shared=Files.createDirectory(temp.resolve("shared"));Files.setPosixFilePermissions(shared,PosixFilePermissions.fromString("rwxr-xr-x"));
        assertThrows(IOException.class,()->new OAuthTokenStore(shared.resolve("tokens.json")).save(token("issuer","secret")));
        assertEquals("rwxr-xr-x",PosixFilePermissions.toString(Files.getPosixFilePermissions(shared)));
        assertFalse(Files.exists(shared.resolve("tokens.json")));
    }
    @Test void refusesSymlinkAndCorruptionAndProjectLocation() throws Exception {
        Path file=temp.resolve("private/tokens.json");var store=new OAuthTokenStore(file);store.save(token("issuer","secret"));
        Files.delete(file);Files.createSymbolicLink(file,temp.resolve("elsewhere"));
        assertThrows(IOException.class,()->store.save(token("issuer","secret")));
        Files.delete(file);Files.writeString(file,"corrupt secret");
        assertFalse(assertThrows(IOException.class,()->store.find("r","i","c")).getMessage().contains("corrupt secret"));
        assertThrows(IOException.class,()->store.requireOutsideProject(temp));
    }
}
