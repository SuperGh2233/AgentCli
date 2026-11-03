package com.paicli.mcp.oauth;

import com.fasterxml.jackson.core.type.TypeReference;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/** 用户目录内的凭证。整个读改写持有进程锁和文件锁，原子替换失败则拒绝保存。 */
public final class OAuthTokenStore {
    public record Token(String resource, String issuer, String clientId, String tokenEndpoint,
                        String accessToken, String refreshToken, long expiresAt) {
        @Override public String toString() { return "OAuthToken[redacted]"; }
    }
    private static final Object LOCK = new Object();
    private static final Set<PosixFilePermission> DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
    private final Path file;

    public OAuthTokenStore(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        // macOS 的 /tmp、/var 本来就是 symlink；在创建私有目录之前固定真实祖先路径。
        Path ancestor = absolute.getParent();
        try {
            while (!Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) ancestor = ancestor.getParent();
            this.file = ancestor.toRealPath().resolve(ancestor.relativize(absolute));
        } catch (IOException e) { throw new IllegalArgumentException("OAuth 凭证目录无法解析"); }
    }
    public static OAuthTokenStore defaultStore() {
        return new OAuthTokenStore(Path.of(System.getProperty("paicli.mcp.oauth.tokens",
                Path.of(System.getProperty("user.home"), ".paicli", "oauth", "mcp-oauth-tokens.json").toString())));
    }

    public void requireOutsideProject(Path project) throws IOException {
        if (file.startsWith(project.toRealPath()))
            throw new IOException("OAuth 凭证不能存进项目，请更改 paicli.mcp.oauth.tokens");
    }

    public Token find(String resource, String issuer, String clientId) throws IOException {
        synchronized (LOCK) { return locked(tokens -> tokens.stream()
                .filter(t -> resource.equals(t.resource()) && issuer.equals(t.issuer()) && clientId.equals(t.clientId())).findFirst().orElse(null), false); }
    }
    public void save(Token token) throws IOException {
        synchronized (LOCK) { locked(tokens -> {
            tokens.removeIf(t -> t.resource().equals(token.resource()) && t.issuer().equals(token.issuer()) && t.clientId().equals(token.clientId()));
            tokens.add(token); return null;
        }, true); }
    }
    public void delete(String resource, String issuer, String clientId) throws IOException {
        synchronized (LOCK) { locked(tokens -> {
            tokens.removeIf(t -> resource.equals(t.resource()) && issuer.equals(t.issuer()) && clientId.equals(t.clientId())); return null;
        }, true); }
    }
    public void deleteAllIssuers(String resource, String clientId) throws IOException {
        synchronized (LOCK) { locked(tokens -> {
            tokens.removeIf(t -> resource.equals(t.resource()) && clientId.equals(t.clientId())); return null;
        }, true); }
    }
    @FunctionalInterface private interface Operation<T> { T run(List<Token> tokens) throws IOException; }

    private <T> T locked(Operation<T> operation, boolean write) throws IOException {
        try {
            Path parent = file.getParent();
            // 不跟随任何目录 symlink，避免把凭证写到攻击者替换的路径。
            for (Path p = parent; p != null; p = p.getParent())
                if (Files.isSymbolicLink(p)) throw new IOException();
            boolean createdParent = !Files.exists(parent);
            if (createdParent) {
                try { Files.createDirectories(parent, PosixFilePermissions.asFileAttribute(DIR)); }
                catch (UnsupportedOperationException e) { Files.createDirectories(parent); }
            }
            if (createdParent) secure(parent, true);
            else verifyPrivateDirectory(parent);
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            createPrivate(lockFile);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var ignored = channel.lock()) {
                List<Token> tokens = new ArrayList<>();
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    secure(file, false);
                    if (Files.size(file) > 1_048_576) throw new IOException();
                    tokens.addAll(OAuthHttp.JSON.readValue(Files.readAllBytes(file), new TypeReference<List<Token>>() {}));
                    for (Token t : tokens) {
                        if (t.resource() == null || t.issuer() == null || t.clientId() == null || t.tokenEndpoint() == null
                                || t.accessToken() == null) throw new IOException();
                    }
                }
                T result = operation.run(tokens);
                if (write) {
                    Path temp = null;
                    try {
                        temp = Files.createTempFile(parent, ".mcp-oauth-", ".tmp");
                        secure(temp, false);
                        byte[] bytes = OAuthHttp.JSON.writeValueAsBytes(tokens);
                        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                            var buffer = java.nio.ByteBuffer.wrap(bytes);
                            while (buffer.hasRemaining()) out.write(buffer);
                            out.force(true);
                        }
                        Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        secure(file, false);
                    } finally { if (temp != null) Files.deleteIfExists(temp); }
                }
                return result;
            }
        } catch (Exception e) { throw new IOException("OAuth 凭证存储失败：请使用属主私有且支持 POSIX/ACL 与原子替换的本地目录"); }
    }

    private static void verifyPrivateDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw new IOException();
        var posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            if (!posix.readAttributes().permissions().equals(DIR)) throw new IOException();
            return;
        }
        var acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null || acl.getAcl().isEmpty()) throw new IOException();
        var owner = acl.getOwner();
        if (acl.getAcl().stream().anyMatch(e -> e.type() == AclEntryType.ALLOW && !e.principal().equals(owner))) throw new IOException();
    }

    private static void createPrivate(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try { Files.createFile(path, PosixFilePermissions.asFileAttribute(FILE)); }
            catch (UnsupportedOperationException e) { Files.createFile(path); }
            catch (FileAlreadyExistsException ignored) { }
        }
        secure(path, false);
    }

    private static void secure(Path path, boolean directory) throws IOException {
        if (Files.isSymbolicLink(path) || (directory ? !Files.isDirectory(path) : !Files.isRegularFile(path)))
            throw new IOException();
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> permissions = directory ? DIR : FILE;
            Files.setPosixFilePermissions(path, permissions);
            if (!Files.getPosixFilePermissions(path).equals(permissions)) throw new IOException();
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException();
        AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(entry));
        if (!acl.getAcl().equals(List.of(entry))) throw new IOException();
    }
}
