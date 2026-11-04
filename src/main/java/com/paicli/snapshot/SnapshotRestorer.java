package com.paicli.snapshot;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import com.paicli.snapshot.SideGitManager.TreeEntry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.text.Normalizer;
import java.util.*;

/** Path-based NIO fallback: NOFOLLOW rechecks reduce, but do not eliminate, TOCTOU races. */
final class SnapshotRestorer {
    private static final LinkOption[] NOFOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private final Path root;
    private final Repository repository;
    private final RestoreFileOps ops;
    private final Set<Path> createdDirectories = new HashSet<>();
    private final Set<String> touched = new HashSet<>();

    SnapshotRestorer(Path root, Repository repository, RestoreFileOps ops) throws IOException {
        this.root = root.toRealPath();
        this.repository = repository;
        this.ops = Objects.requireNonNull(ops);
    }

    static void validateEntry(String name, TreeEntry entry) throws IOException {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\")
                || name.contains(":") || name.indexOf('\0') >= 0) {
            throw new IOException("非法 Git tree path: " + name);
        }
        for (String component : name.split("/", -1)) {
            String deviceStem = component.split("\\.", 2)[0].stripTrailing().toUpperCase(Locale.ROOT);
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                    || component.equalsIgnoreCase(".git") || component.endsWith(" ") || component.endsWith(".")
                    || deviceStem.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]")
                    || component.chars().anyMatch(c -> c < 32 || "<>\"|?*".indexOf(c) >= 0)) {
                throw new IOException("非法 Git tree path: " + name);
            }
        }
        if (entry == null || entry.objectId() == null || !(FileMode.REGULAR_FILE.equals(entry.mode())
                || FileMode.EXECUTABLE_FILE.equals(entry.mode()) || FileMode.SYMLINK.equals(entry.mode()))) {
            throw new IOException("不支持的 Git 文件模式: " + name);
        }
    }

    RestoreResult restore(String targetId, String rollbackId, Map<String, TreeEntry> current,
                          Map<String, TreeEntry> target) {
        try {
            Map<String, String> collisionPaths = new HashMap<>();
            inspectObjects(current, collisionPaths);
            inspectObjects(target, collisionPaths);
            preflight(current, current);
            preflight(current, target);
        } catch (IOException | RuntimeException e) {
            return RestoreResult.failure("恢复预检失败，未修改项目文件：" + e.getMessage());
        }
        try {
            apply(current, target, false);
            return RestoreResult.success(targetId, List.copyOf(target.keySet()),
                    current.keySet().stream().filter(p -> !target.containsKey(p)).toList());
        } catch (IOException | RuntimeException forward) {
            try {
                // Include attempted mutations: an operation may fail after changing the filesystem.
                Map<String, TreeEntry> applied = new LinkedHashMap<>(current);
                for (String name : touched) {
                    if (target.containsKey(name)) applied.put(name, target.get(name));
                }
                apply(applied, current, true);
                for (Path directory : createdDirectories.stream()
                        .sorted(Comparator.comparingInt(Path::getNameCount).reversed()).toList()) {
                    if (current.keySet().stream().anyMatch(name -> path(name).startsWith(directory))) continue;
                    if (Files.isDirectory(directory, NOFOLLOW)) {
                        mutate(true, "cleanup-directory", directory);
                        Files.delete(directory);
                    }
                }
                return RestoreResult.failure("恢复失败，但已回滚到恢复前快照 " + rollbackId + "：" + describe(forward));
            } catch (IOException | RuntimeException rollback) {
                return RestoreResult.failure("恢复失败，且回滚失败；请人工检查。恢复前快照 " + rollbackId
                        + "；恢复错误：" + describe(forward) + "；回滚错误：" + describe(rollback));
            }
        }
    }

    private void inspectObjects(Map<String, TreeEntry> tree, Map<String, String> collisionPaths) throws IOException {
        for (var item : tree.entrySet()) {
            validateEntry(item.getKey(), item.getValue());
            String prefix = "";
            for (String component : item.getKey().split("/")) {
                prefix = prefix.isEmpty() ? component : prefix + "/" + component;
                String key = Normalizer.normalize(prefix, Normalizer.Form.NFC)
                        .toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
                key = Normalizer.normalize(key, Normalizer.Form.NFC);
                String previous = collisionPaths.putIfAbsent(key, prefix);
                if (previous != null && !previous.equals(prefix)) {
                    throw new IOException("Git 路径大小写或 Unicode 碰撞: " + previous + " / " + prefix);
                }
            }
            for (Path parent = path(item.getKey()).getParent(); !parent.equals(root); parent = parent.getParent()) {
                if (tree.containsKey(relative(parent))) throw new IOException("tree 文件与父目录冲突: " + parent);
            }
            var loader = repository.open(item.getValue().objectId(), Constants.OBJ_BLOB);
            try (var stream = loader.openStream()) {
                stream.transferTo(OutputStream.nullOutputStream());
            }
            if (FileMode.SYMLINK.equals(item.getValue().mode())) linkTarget(item.getValue());
            if (!FileMode.REGULAR_FILE.equals(item.getValue().mode())
                    && !Files.getFileStore(root).supportsFileAttributeView("posix")) {
                throw new IOException("当前平台不支持 POSIX 软链/执行位安全恢复（包括 Windows）");
            }
        }
    }

    private Path linkTarget(TreeEntry entry) throws IOException {
        var loader = repository.open(entry.objectId(), Constants.OBJ_BLOB);
        if (loader.getSize() == 0 || loader.getSize() > 16384) throw new IOException("软链目标长度不支持");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(loader.getBytes(16384))).toString();
        if (text.indexOf('\0') >= 0) throw new IOException("软链目标包含 NUL");
        return Path.of(text);
    }

    private void preflight(Map<String, TreeEntry> current, Map<String, TreeEntry> target) throws IOException {
        for (String name : target.keySet()) {
            Path file = path(name);
            Path parent = root;
            for (Path component : root.relativize(file.getParent())) {
                parent = parent.resolve(component);
                if (!Files.exists(parent, NOFOLLOW)) break;
                if (!Files.isDirectory(parent, NOFOLLOW)) {
                    if (!current.containsKey(relative(parent)) || target.containsKey(relative(parent))) {
                        throw new IOException("未跟踪或冲突的父路径: " + parent);
                    }
                    break;
                }
            }
            // Never inspect descendants through a parent symlink.
            if (!parentsAreDirectories(file)) continue;
            if (Files.isDirectory(file, NOFOLLOW)) inspectDirectory(file, current, target);
            else if (Files.exists(file, NOFOLLOW) && !current.containsKey(name)) {
                throw new IOException("目标将覆盖未跟踪文件: " + name);
            }
        }
        for (String name : current.keySet()) {
            Path file = path(name);
            // A tracked file may already be absent together with one or more parent
            // directories. That is a valid restore input; only existing parents must
            // be real directories (never symlinks or other non-directory entries).
            if (!existingParents(file)) continue;
            if (Files.isDirectory(file, NOFOLLOW)) throw new IOException("当前文件已变成目录: " + name);
            if (Files.exists(file, NOFOLLOW)) {
                boolean link = FileMode.SYMLINK.equals(current.get(name).mode());
                if (Files.isSymbolicLink(file) != link || (!link && !Files.isRegularFile(file, NOFOLLOW))) {
                    throw new IOException("当前文件类型与快照不一致: " + name);
                }
            }
        }
    }

    private void inspectDirectory(Path directory, Map<String, TreeEntry> current,
                                  Map<String, TreeEntry> target) throws IOException {
        checkParents(directory);
        if (!createdDirectories.contains(directory)
                && current.keySet().stream().noneMatch(name -> path(name).startsWith(directory) && !path(name).equals(directory))) {
            throw new IOException("拒绝覆盖无来源目录: " + directory);
        }
        try (var children = Files.newDirectoryStream(directory)) {
            for (Path child : children) {
                if (Files.isDirectory(child, NOFOLLOW)) {
                    if (!createdDirectories.contains(child)
                            && current.keySet().stream().noneMatch(name -> path(name).startsWith(child))) {
                        throw new IOException("拒绝删除未跟踪目录: " + child);
                    }
                    inspectDirectory(child, current, target);
                }
                else if (!current.containsKey(relative(child)) || target.containsKey(relative(child))) {
                    throw new IOException("目录含未跟踪、排除或仍需保留的内容: " + child);
                }
            }
        }
    }

    private void apply(Map<String, TreeEntry> from, Map<String, TreeEntry> to, boolean rollback) throws IOException {
        for (String name : from.keySet().stream().sorted(Comparator.comparingInt(String::length).reversed()).toList()) {
            if (!to.containsKey(name)) {
                Path file = path(name);
                if (!existingParents(file)) continue;
                checkParents(file);
                if (Files.isDirectory(file, NOFOLLOW)) {
                    if (to.keySet().stream().anyMatch(p -> p.startsWith(name + "/"))) continue;
                    // A forward file→directory transition may have created this directory.
                    if (!createdDirectories.contains(file)) throw new IOException("拒绝删除非本次创建目录: " + file);
                }
                touched.add(name);
                mutate(rollback, "delete", file);
                Files.deleteIfExists(file);
            }
        }
        for (var item : to.entrySet()) {
            Path file = path(item.getKey());
            touched.add(item.getKey());
            ensureParents(file, rollback);
            if (Files.isDirectory(file, NOFOLLOW)) removeEmptyTree(file, from, rollback);
            write(file, item.getValue(), rollback);
        }
    }

    private void ensureParents(Path file, boolean rollback) throws IOException {
        Path parent = file.getParent();
        if (parent.equals(root)) return;
        ensureParents(parent, rollback);
        if (!Files.exists(parent, NOFOLLOW)) {
            mutate(rollback, "mkdir", parent);
            Files.createDirectory(parent);
            createdDirectories.add(parent);
        } else if (!Files.isDirectory(parent, NOFOLLOW)) throw new IOException("父路径不是实际目录: " + parent);
    }

    private void removeEmptyTree(Path dir, Map<String, TreeEntry> from, boolean rollback) throws IOException {
        checkParents(dir);
        if (!createdDirectories.contains(dir)
                && from.keySet().stream().noneMatch(name -> path(name).startsWith(dir) && !path(name).equals(dir))) {
            throw new IOException("拒绝删除无来源目录: " + dir);
        }
        if (!Files.isDirectory(dir, NOFOLLOW)) throw new IOException("目录形态已变化: " + dir);
        try (var children = Files.newDirectoryStream(dir)) {
            for (Path child : children) {
                if (!Files.isDirectory(child, NOFOLLOW)) throw new IOException("拒绝删除目录内文件: " + child);
                removeEmptyTree(child, from, rollback);
            }
        }
        mutate(rollback, "rmdir", dir);
        Files.delete(dir); // Never recursively deletes files, including excluded children.
    }

    private void write(Path file, TreeEntry entry, boolean rollback) throws IOException {
        Path temp = null;
        Throwable failure = null;
        try {
            mutate(rollback, "temp", file);
            temp = Files.createTempFile(file.getParent(), ".paicli-restore-", ".tmp");
            if (FileMode.SYMLINK.equals(entry.mode())) {
                mutate(rollback, "unlink-temp", temp);
                Files.delete(temp);
                mutate(rollback, "symlink", temp);
                Files.createSymbolicLink(temp, linkTarget(entry));
            } else {
                mutate(rollback, "stream", temp);
                try (var input = repository.open(entry.objectId(), Constants.OBJ_BLOB).openStream();
                     var output = Files.newOutputStream(temp, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                    input.transferTo(output);
                }
                if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
                    checkParents(file);
                    Set<PosixFilePermission> permissions = EnumSet.noneOf(PosixFilePermission.class);
                    permissions.addAll(Files.getPosixFilePermissions(
                            Files.isRegularFile(file, NOFOLLOW) ? file : temp, NOFOLLOW));
                    permissions.removeAll(EnumSet.of(PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
                    if (FileMode.EXECUTABLE_FILE.equals(entry.mode())) {
                        permissions.add(PosixFilePermission.OWNER_EXECUTE);
                        if (permissions.contains(PosixFilePermission.GROUP_READ)) permissions.add(PosixFilePermission.GROUP_EXECUTE);
                        if (permissions.contains(PosixFilePermission.OTHERS_READ)) permissions.add(PosixFilePermission.OTHERS_EXECUTE);
                    }
                    mutate(rollback, "chmod", temp);
                    Files.setPosixFilePermissions(temp, permissions);
                }
            }
            mutate(rollback, "replace", file);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                mutate(rollback, "replace", file);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            if (temp != null) {
                try {
                    mutate(rollback, "cleanup-temp", temp);
                    Files.deleteIfExists(temp);
                } catch (IOException | RuntimeException cleanup) {
                    if (failure == null) throw cleanup;
                    failure.addSuppressed(cleanup);
                }
            }
        }
    }

    private void mutate(boolean rollback, String operation, Path path) throws IOException {
        ops.beforeMutation(rollback, operation, path);
        checkParents(path);
    }

    private boolean parentsAreDirectories(Path file) throws IOException {
        if (!root.equals(root.toRealPath()) || !Files.isDirectory(root, NOFOLLOW)) throw new IOException("项目根目录已变化");
        Path parent = root;
        for (Path component : root.relativize(file.getParent())) {
            parent = parent.resolve(component);
            if (!Files.isDirectory(parent, NOFOLLOW)) return false;
        }
        return true;
    }

    private void checkParents(Path file) throws IOException {
        if (!file.startsWith(root) || file.equals(root) || !parentsAreDirectories(file)) {
            throw new IOException("不安全的父路径: " + file);
        }
    }

    private boolean existingParents(Path file) throws IOException {
        Path parent = root;
        if (!root.equals(root.toRealPath()) || !Files.isDirectory(root, NOFOLLOW)) throw new IOException("项目根目录已变化");
        for (Path component : root.relativize(file.getParent())) {
            parent = parent.resolve(component);
            if (!Files.exists(parent, NOFOLLOW)) return false;
            if (!Files.isDirectory(parent, NOFOLLOW)) throw new IOException("不安全的父路径: " + parent);
        }
        return true;
    }

    private Path path(String name) { return root.resolve(name); }
    private String relative(Path file) { return root.relativize(file).toString().replace('\\', '/'); }
    private String describe(Throwable error) {
        String message = error.getMessage();
        for (Throwable cleanup : error.getSuppressed()) message += "；临时文件清理失败：" + cleanup.getMessage();
        return message;
    }
}
