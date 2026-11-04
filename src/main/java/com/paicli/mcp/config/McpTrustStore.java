package com.paicli.mcp.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 项目级 MCP server 的确认记录（{@code ~/.paicli/mcp-trust.json}）。
 *
 * <p>按“项目配置文件绝对路径 + server 名 + 配置内容指纹”记录。文件损坏、权限不安全、
 * 锁失败或符号链接一律按未信任处理；更新和撤销均使用锁与原子替换。
 */
public final class McpTrustStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TRUSTED = "trustedProjectServers";
    private static final int MAX_BYTES = 1_048_576;
    private static final Object PROCESS_LOCK = new Object();
    private static final Set<PosixFilePermission> PRIVATE_DIR =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            PosixFilePermissions.fromString("rw-------");

    private final Path file;

    public McpTrustStore(Path file) {
        if (file == null) throw new IllegalArgumentException("MCP trust 文件路径不能为空");
        this.file = file.toAbsolutePath().normalize();
        if (this.file.getParent() == null) throw new IllegalArgumentException("MCP trust 文件必须有父目录");
    }

    public static McpTrustStore defaultStore() {
        return new McpTrustStore(Path.of(System.getProperty("user.home"), ".paicli", "mcp-trust.json"));
    }

    public boolean isTrusted(Path configFile, String serverName, String fingerprint) {
        if (!validKey(configFile, serverName, fingerprint)) return false;
        String path = normalize(configFile);
        try {
            return withFileLock(() -> {
                for (JsonNode entry : readRoot().path(TRUSTED)) {
                    if (path.equals(entry.path("config").asText())
                            && serverName.equals(entry.path("name").asText())
                            && fingerprint.equals(entry.path("sha256").asText())) return true;
                }
                return false;
            });
        } catch (IOException | RuntimeException error) {
            System.err.println("⚠️ MCP trust 状态不可安全读取，按未信任处理: " + error.getMessage());
            return false;
        }
    }

    /** 同一项目同名 server 只保留最新一次确认的指纹。 */
    public void trust(Path configFile, String serverName, String fingerprint) throws IOException {
        requireKey(configFile, serverName, fingerprint);
        String path = normalize(configFile);
        withFileLock(() -> {
            ObjectNode root = readRoot();
            ArrayNode next = MAPPER.createArrayNode();
            for (JsonNode entry : root.path(TRUSTED)) {
                if (!(path.equals(entry.path("config").asText())
                        && serverName.equals(entry.path("name").asText()))) next.add(entry);
            }
            next.addObject().put("config", path).put("name", serverName).put("sha256", fingerprint);
            root.set(TRUSTED, next);
            write(root);
            return null;
        });
    }

    /** 撤销指定项目配置中的 server 信任，不影响其他项目或同名 server。 */
    public boolean untrust(Path configFile, String serverName) throws IOException {
        if (configFile == null || serverName == null || serverName.isBlank()
                || serverName.length() > 128 || serverName.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("MCP trust 撤销目标非法");
        }
        String path = normalize(configFile);
        return withFileLock(() -> {
            ObjectNode root = readRoot();
            ArrayNode next = MAPPER.createArrayNode();
            boolean removed = false;
            for (JsonNode entry : root.path(TRUSTED)) {
                boolean match = path.equals(entry.path("config").asText())
                        && serverName.equals(entry.path("name").asText());
                if (match) removed = true;
                else next.add(entry);
            }
            if (removed) {
                root.set(TRUSTED, next);
                write(root);
            }
            return removed;
        });
    }

    private static boolean validKey(Path configFile, String serverName, String fingerprint) {
        return configFile != null && serverName != null && !serverName.isBlank()
                && serverName.length() <= 128
                && serverName.codePoints().noneMatch(Character::isISOControl)
                && fingerprint != null && fingerprint.matches("[0-9a-f]{64}");
    }

    private static void requireKey(Path configFile, String serverName, String fingerprint) {
        if (!validKey(configFile, serverName, fingerprint)) {
            throw new IllegalArgumentException("MCP trust 记录的配置路径、server 名或指纹非法");
        }
    }

    private static String normalize(Path configFile) {
        return configFile.toAbsolutePath().normalize().toString();
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws IOException;
    }

    private <T> T withFileLock(LockedOperation<T> operation) throws IOException {
        synchronized (PROCESS_LOCK) {
            Path parent = file.getParent();
            ensurePrivateDirectory(parent);
            Path lockFile = file.resolveSibling(file.getFileName() + ".lock");
            createPrivateFile(lockFile);
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 var ignored = channel.lock()) {
                secureFile(lockFile);
                return operation.run();
            }
        }
    }

    private ObjectNode readRoot() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return MAPPER.createObjectNode();
        secureFile(file);
        long size = Files.size(file);
        if (size <= 0 || size > MAX_BYTES) throw new IOException("MCP trust 文件大小非法");
        JsonNode root;
        try {
            root = MAPPER.readTree(Files.readAllBytes(file));
        } catch (IOException error) {
            throw new IOException("MCP trust 文件损坏；按未信任处理，拒绝覆盖", error);
        }
        if (!(root instanceof ObjectNode object) || !root.path(TRUSTED).isArray()) {
            throw new IOException("MCP trust 文件 schema 非法；按未信任处理");
        }
        for (JsonNode entry : root.path(TRUSTED)) {
            try {
                if (!entry.isObject() || !entry.path("config").isTextual()
                        || !entry.path("name").isTextual()
                        || !entry.path("sha256").isTextual()
                        || !validKey(Path.of(entry.path("config").asText()),
                                entry.path("name").asText(), entry.path("sha256").asText())) {
                    throw new IOException("MCP trust 文件包含非法记录；按未信任处理");
                }
            } catch (RuntimeException error) {
                throw new IOException("MCP trust 文件包含非法路径或记录；按未信任处理", error);
            }
        }
        return object;
    }

    private void write(ObjectNode root) throws IOException {
        byte[] bytes = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        if (bytes.length > MAX_BYTES) throw new IOException("MCP trust 文件超过 1MB");
        Path temp = null;
        try {
            try {
                temp = Files.createTempFile(file.getParent(), ".mcp-trust-", ".tmp",
                        PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
            } catch (UnsupportedOperationException error) {
                temp = Files.createTempFile(file.getParent(), ".mcp-trust-", ".tmp");
                secureFile(temp);
            }
            secureFile(temp);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            secureFile(file);
        } catch (AtomicMoveNotSupportedException error) {
            throw new IOException("当前文件系统不支持 MCP trust 原子替换；拒绝非原子保存", error);
        } finally {
            if (temp != null) Files.deleteIfExists(temp);
        }
    }

    private static void ensurePrivateDirectory(Path directory) throws IOException {
        if (directory == null) throw new IOException("MCP trust 目录不能为空");
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("MCP trust 目录不能包含符号链接");
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PRIVATE_DIR));
            } catch (UnsupportedOperationException error) {
                Files.createDirectories(directory);
            }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new IOException("MCP trust 存储位置不是安全目录");
        }
        secureDirectory(directory);
    }

    private static void createPrivateFile(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
            } catch (UnsupportedOperationException error) {
                Files.createFile(path);
            } catch (FileAlreadyExistsException ignored) {
                // A concurrent process created it; validate below.
            }
        }
        secureFile(path);
    }

    private static void secureDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("MCP trust 目录类型不安全");
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(path, PRIVATE_DIR);
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PRIVATE_DIR)) {
                throw new IOException("MCP trust 目录权限无法设为 0700");
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("文件系统不支持安全 ACL");
        UserPrincipal owner = acl.getOwner();
        AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(entry));
        if (!acl.getAcl().equals(List.of(entry))) throw new IOException("MCP trust 目录 ACL 无法收紧");
    }

    private static void secureFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("MCP trust 文件或锁文件类型不安全");
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(path, PRIVATE_FILE);
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PRIVATE_FILE)) {
                throw new IOException("MCP trust 文件权限无法设为 0600");
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("文件系统不支持安全 ACL");
        UserPrincipal owner = acl.getOwner();
        AclEntry entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
        acl.setAcl(List.of(entry));
        if (!acl.getAcl().equals(List.of(entry))) throw new IOException("MCP trust 文件 ACL 无法收紧");
    }
}
