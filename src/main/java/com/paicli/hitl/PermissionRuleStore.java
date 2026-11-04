package com.paicli.hitl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * User-level permission rules with private permissions, process/file locking and atomic replacement.
 * A corrupt or unsafe store is an error; callers must not treat it as an empty allow-list.
 */
public final class PermissionRuleStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Object PROCESS_LOCK = new Object();
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_BYTES = 1_048_576;
    private static final int MAX_RULES = 10_000;
    private static final Set<PosixFilePermission> PRIVATE_DIR =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> PRIVATE_FILE =
            PosixFilePermissions.fromString("rw-------");

    private final Path file;

    public PermissionRuleStore(Path file) {
        if (file == null) throw new IllegalArgumentException("权限规则文件路径不能为空");
        Path absolute = file.toAbsolutePath().normalize();
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        try {
            if (absolute.getParent() != null && absolute.getParent().startsWith(home)) {
                Path current = home;
                for (Path component : home.relativize(absolute.getParent())) {
                    current = current.resolve(component);
                    if (Files.isSymbolicLink(current)) {
                        throw new IOException("用户权限规则目录不能经过符号链接");
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("权限规则存储路径不安全", e);
        }
        Path ancestor = absolute.getParent();
        try {
            while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
                ancestor = ancestor.getParent();
            }
            if (ancestor == null) throw new IOException("没有可解析的权限规则存储祖先目录");
            this.file = ancestor.toRealPath().resolve(ancestor.relativize(absolute)).normalize();
        } catch (IOException e) {
            throw new IllegalArgumentException("权限规则存储路径无法解析", e);
        }
    }

    public static PermissionRuleStore defaultStore() {
        return new PermissionRuleStore(Path.of(System.getProperty("user.home"),
                ".paicli", "security", "permissions.json"));
    }

    public Path file() {
        return file;
    }

    /** Refuse a user-level policy file that could be modified through the active project's file tools. */
    public void requireOutsideProject(Path projectRoot) throws IOException {
        if (projectRoot == null) throw new IOException("项目根目录不能为空");
        Path realRoot = projectRoot.toRealPath();
        if (file.toAbsolutePath().normalize().startsWith(realRoot)) {
            throw new IOException("权限规则存储必须位于当前项目根目录之外");
        }
    }

    public List<PermissionRule> load() throws IOException {
        synchronized (PROCESS_LOCK) {
            return withFileLock(this::readRules);
        }
    }

    /** Add or replace one rule by id while holding the cross-process lock. */
    public List<PermissionRule> add(PermissionRule rule) throws IOException {
        if (rule == null) throw new IllegalArgumentException("权限规则不能为空");
        synchronized (PROCESS_LOCK) {
            return withFileLock(() -> {
                List<PermissionRule> rules = readRules();
                rules.removeIf(existing -> existing.id().equals(rule.id()));
                if (rules.size() >= MAX_RULES) throw new IOException("权限规则数量达到上限");
                rules.add(rule);
                writeRules(rules);
                return List.copyOf(rules);
            });
        }
    }

    public boolean remove(String id) throws IOException {
        if (id == null || id.isBlank()) return false;
        synchronized (PROCESS_LOCK) {
            return withFileLock(() -> {
                List<PermissionRule> rules = readRules();
                boolean removed = rules.removeIf(rule -> rule.id().equals(id));
                if (removed) writeRules(rules);
                return removed;
            });
        }
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws IOException;
    }

    private <T> T withFileLock(LockedOperation<T> operation) throws IOException {
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

    private List<PermissionRule> readRules() throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new ArrayList<>();
        secureFile(file);
        long size = Files.size(file);
        if (size <= 0 || size > MAX_BYTES) throw new IOException("权限规则文件大小非法");
        JsonNode root;
        try {
            root = JSON.readTree(Files.readAllBytes(file));
        } catch (IOException e) {
            throw new IOException("权限规则文件损坏；为安全起见未加载任何规则", e);
        }
        if (root == null || !root.isObject() || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                || !root.path("rules").isArray()) {
            throw new IOException("权限规则文件 schema 非法或版本不支持；未加载任何规则");
        }
        ArrayNode array = (ArrayNode) root.path("rules");
        if (array.size() > MAX_RULES) throw new IOException("权限规则数量达到上限");
        List<PermissionRule> rules = new ArrayList<>(array.size());
        Set<String> ids = new HashSet<>();
        for (JsonNode node : array) {
            PermissionRule rule;
            try {
                rule = JSON.treeToValue(node, PermissionRule.class);
            } catch (Exception e) {
                throw new IOException("权限规则条目非法；为安全起见未加载任何规则", e);
            }
            if (!ids.add(rule.id())) throw new IOException("权限规则存在重复 id");
            rules.add(rule);
        }
        return rules;
    }

    private void writeRules(List<PermissionRule> rules) throws IOException {
        if (rules.size() > MAX_RULES) throw new IOException("权限规则数量达到上限");
        Set<String> ids = new HashSet<>();
        for (PermissionRule rule : rules) {
            if (rule == null || !ids.add(rule.id())) throw new IOException("权限规则为空或 id 重复");
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        ArrayNode array = root.putArray("rules");
        for (PermissionRule rule : rules) array.add(JSON.valueToTree(rule));
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        if (bytes.length > MAX_BYTES) throw new IOException("权限规则文件超过 1MB");
        Path temp = null;
        try {
            temp = Files.createTempFile(file.getParent(), ".permissions-", ".tmp",
                    PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
        } catch (UnsupportedOperationException e) {
            temp = Files.createTempFile(file.getParent(), ".permissions-", ".tmp");
            secureFile(temp);
        }
        try {
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
        } catch (AtomicMoveNotSupportedException e) {
            throw new IOException("当前文件系统不支持权限规则原子替换；拒绝非原子保存", e);
        } finally {
            if (temp != null) Files.deleteIfExists(temp);
        }
    }

    private static void ensurePrivateDirectory(Path directory) throws IOException {
        if (directory == null) throw new IOException("权限规则目录不能为空");
        for (Path current = directory; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) throw new IOException("权限规则目录不能包含符号链接");
        }
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PRIVATE_DIR));
            } catch (UnsupportedOperationException e) {
                Files.createDirectories(directory);
            }
        }
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            throw new IOException("权限规则存储位置不是安全目录");
        }
        secureDirectory(directory);
    }

    private static void createPrivateFile(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_FILE));
            } catch (UnsupportedOperationException e) {
                Files.createFile(path);
            } catch (FileAlreadyExistsException ignored) {
                // A concurrent process created it; validate below.
            }
        }
        secureFile(path);
    }

    private static void secureDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("权限规则目录类型不安全");
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(path, PRIVATE_DIR);
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PRIVATE_DIR)) {
                throw new IOException("权限规则目录权限无法设为 0700");
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
        if (!acl.getAcl().equals(List.of(entry))) throw new IOException("权限规则目录 ACL 无法收紧");
    }

    private static void secureFile(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("权限规则文件或锁文件类型不安全");
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(path, PRIVATE_FILE);
            if (!Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).equals(PRIVATE_FILE)) {
                throw new IOException("权限规则文件权限无法设为 0600");
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
        if (!acl.getAcl().equals(List.of(entry))) throw new IOException("权限规则文件 ACL 无法收紧");
    }
}
