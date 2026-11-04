package com.paicli.snapshot;

import com.paicli.snapshot.SideGitManager.TreeEntry;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SnapshotRestorerTest {
    @TempDir Path temp;

    private TreeEntry blob(Repository repo, String text, FileMode mode) throws Exception {
        try (var inserter = repo.newObjectInserter()) {
            var id = inserter.insert(Constants.OBJ_BLOB, text.getBytes(StandardCharsets.UTF_8));
            inserter.flush();
            return new TreeEntry(id, mode);
        }
    }

    private Path project() throws Exception { return Files.createDirectory(temp.resolve("project")); }
    private Git repository() throws Exception { return Git.init().setDirectory(temp.resolve("objects").toFile()).call(); }
    private void posix() throws Exception {
        assumeTrue(Files.getFileStore(temp).supportsFileAttributeView("posix"));
    }

    @Test void replacesTrackedSymlinkParentWithoutTouchingOutside() throws Exception {
        posix();
        Path root = project(), outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("sentinel"), "safe");
        Files.createSymbolicLink(root.resolve("dir"), outside);
        try (Git git = repository()) {
            var repo = git.getRepository();
            var current = Map.of("dir", blob(repo, outside.toString(), FileMode.SYMLINK));
            var target = Map.of("dir/sentinel", blob(repo, "restored", FileMode.REGULAR_FILE));
            var result = new SnapshotRestorer(root, repo, RestoreFileOps.NONE).restore("target", "before", current, target);
            assertTrue(result.success(), result.message());
            assertFalse(Files.isSymbolicLink(root.resolve("dir")));
            assertEquals("restored", Files.readString(root.resolve("dir/sentinel")));
            assertEquals("safe", Files.readString(outside.resolve("sentinel")));
        }
    }

    @Test void refusesUntrackedSymlinkParentBeforeAnyWrite() throws Exception {
        posix();
        Path root = project(), outside = Files.createDirectory(temp.resolve("outside"));
        Files.writeString(outside.resolve("sentinel"), "safe");
        Files.createSymbolicLink(root.resolve("dir"), outside);
        try (Git git = repository()) {
            var repo = git.getRepository();
            var target = Map.of("dir/sentinel", blob(repo, "bad", FileMode.REGULAR_FILE));
            AtomicInteger mutations = new AtomicInteger();
            var result = new SnapshotRestorer(root, repo, (r, o, p) -> mutations.incrementAndGet())
                    .restore("target", "before", Map.of(), target);
            assertFalse(result.success());
            assertEquals(0, mutations.get());
            assertTrue(Files.isSymbolicLink(root.resolve("dir")));
            assertEquals("safe", Files.readString(outside.resolve("sentinel")));
        }
    }

    @Test void restoresSymlinkAndExecutableWithoutWritingLinkTarget() throws Exception {
        posix();
        Path root = project(), outside = temp.resolve("sentinel");
        Files.writeString(outside, "safe");
        Files.writeString(root.resolve("link"), "plain");
        Files.writeString(root.resolve("run"), "new");
        Files.setPosixFilePermissions(root.resolve("run"), java.util.Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        try (Git git = repository()) {
            var repo = git.getRepository();
            var current = Map.of("link", blob(repo, "plain", FileMode.REGULAR_FILE),
                    "run", blob(repo, "new", FileMode.REGULAR_FILE));
            var target = Map.of("link", blob(repo, outside.toString(), FileMode.SYMLINK),
                    "run", blob(repo, "script", FileMode.EXECUTABLE_FILE));
            var result = new SnapshotRestorer(root, repo, RestoreFileOps.NONE).restore("t", "b", current, target);
            assertTrue(result.success(), result.message());
            assertEquals(outside, Files.readSymbolicLink(root.resolve("link")));
            assertEquals("safe", Files.readString(outside));
            assertTrue(Files.getPosixFilePermissions(root.resolve("run")).contains(PosixFilePermission.OWNER_EXECUTE));
            assertFalse(Files.getPosixFilePermissions(root.resolve("run")).contains(PosixFilePermission.GROUP_READ));
            assertFalse(Files.getPosixFilePermissions(root.resolve("run")).contains(PosixFilePermission.OTHERS_READ));
            var reverse = new SnapshotRestorer(root, repo, RestoreFileOps.NONE).restore("b", "t", target, current);
            assertTrue(reverse.success(), reverse.message());
            assertFalse(Files.isSymbolicLink(root.resolve("link")));
            assertFalse(Files.getPosixFilePermissions(root.resolve("run")).contains(PosixFilePermission.OWNER_EXECUTE));
            assertEquals("safe", Files.readString(outside));
        }
    }

    @Test void restoresNestedTrackedFileWhenItsParentDirectoryIsMissing() throws Exception {
        Path root = project();
        try (Git git = repository()) {
            var repo = git.getRepository();
            var current = Map.of("nested/deep/file.txt", blob(repo, "current", FileMode.REGULAR_FILE));
            var target = Map.of("nested/deep/file.txt", blob(repo, "target", FileMode.REGULAR_FILE));
            var result = new SnapshotRestorer(root, repo, RestoreFileOps.NONE)
                    .restore("target", "current", current, target);
            assertTrue(result.success(), result.message());
            assertEquals("target", Files.readString(root.resolve("nested/deep/file.txt")));
        }
    }

    @Test void transitionsFileToDirectoryAndBack() throws Exception {
        Path root = project();
        Files.writeString(root.resolve("node"), "file");
        try (Git git = repository()) {
            var repo = git.getRepository();
            var file = Map.of("node", blob(repo, "file", FileMode.REGULAR_FILE));
            var directory = Map.of("node/deep/child", blob(repo, "child", FileMode.REGULAR_FILE));
            var result = new SnapshotRestorer(root, repo, RestoreFileOps.NONE).restore("t", "b", file, directory);
            assertTrue(result.success(), result.message());
            assertEquals("child", Files.readString(root.resolve("node/deep/child")));
            result = new SnapshotRestorer(root, repo, RestoreFileOps.NONE).restore("t", "b", directory, file);
            assertTrue(result.success(), result.message());
            assertEquals("file", Files.readString(root.resolve("node")));
        }
    }

    @Test void refusesDirectoryWithExcludedOrUntrackedChildren() throws Exception {
        Path root = project();
        Files.createDirectory(root.resolve("node"));
        Files.writeString(root.resolve("node/tracked"), "tracked");
        Files.writeString(root.resolve("node/ignored"), "keep");
        try (Git git = repository()) {
            var repo = git.getRepository();
            var result = new SnapshotRestorer(root, repo, RestoreFileOps.NONE).restore("t", "b",
                    Map.of("node/tracked", blob(repo, "tracked", FileMode.REGULAR_FILE)),
                    Map.of("node", blob(repo, "file", FileMode.REGULAR_FILE)));
            assertFalse(result.success());
            assertEquals("keep", Files.readString(root.resolve("node/ignored")));
            assertEquals("tracked", Files.readString(root.resolve("node/tracked")));
        }
    }

    @Test void rejectsUnsupportedModeMissingBlobAndMalformedPathsBeforeWrite() throws Exception {
        Path root = project();
        Files.writeString(root.resolve("safe"), "original");
        try (Git git = repository()) {
            var repo = git.getRepository();
            var good = blob(repo, "new", FileMode.REGULAR_FILE);
            FileMode unknown = FileMode.fromBits(0140000);
            assertEquals(0140000, unknown.getBits());
            assertEquals(Constants.OBJ_BAD, unknown.getObjectType());
            assertNotSame(FileMode.GITLINK, unknown);
            for (FileMode mode : new FileMode[]{FileMode.GITLINK, unknown}) {
                Map<String, TreeEntry> target = new LinkedHashMap<>();
                target.put("safe", good);
                target.put("bad", new TreeEntry(good.objectId(), mode));
                AtomicInteger mutations = new AtomicInteger();
                var result = new SnapshotRestorer(root, repo, (r, o, p) -> mutations.incrementAndGet())
                        .restore("t", "b", Map.of(), target);
                assertFalse(result.success());
                assertEquals(0, mutations.get(), "unsupported mode must fail before mutation");
            }
            for (String path : new String[]{"", "/abs", "a//b", "a/./b", "a/../b", "a/", "a\\b", "C:foo", "//server/share",
                    "CON", "prn.txt", "dir/AUX", "NUL.log", "COM1", "COM9.txt", "LPT1", "LPT9.log",
                    "CON .txt", "trailing.", "trailing ", "dir./file", "dir /file"}) {
                assertThrows(java.io.IOException.class, () -> SnapshotRestorer.validateEntry(path, good), path);
                AtomicInteger mutations = new AtomicInteger();
                assertFalse(new SnapshotRestorer(root, repo, (r, o, p) -> mutations.incrementAndGet())
                        .restore("t", "b", Map.of(), Map.of(path, good)).success());
                assertEquals(0, mutations.get(), path);
            }
            var missing = new TreeEntry(org.eclipse.jgit.lib.ObjectId.fromString("1111111111111111111111111111111111111111"), FileMode.REGULAR_FILE);
            assertFalse(new SnapshotRestorer(root, repo, (r, o, p) -> fail("mutation"))
                    .restore("t", "b", Map.of("safe", good), Map.of("missing", missing)).success());
            assertEquals("original", Files.readString(root.resolve("safe")));
        }
    }

    @Test void refusesUntrackedEmptyDirectoryAndPreservesIt() throws Exception {
        Path root = project();
        Files.createDirectories(root.resolve("node/untracked/empty"));
        Files.writeString(root.resolve("node/tracked"), "before");
        try (Git git = repository()) {
            var repo = git.getRepository();
            var result = new SnapshotRestorer(root, repo, (r, o, p) -> fail("preflight must reject"))
                    .restore("t", "b", Map.of("node/tracked", blob(repo, "before", FileMode.REGULAR_FILE)),
                            Map.of("node", blob(repo, "replacement", FileMode.REGULAR_FILE)));
            assertFalse(result.success());
            assertTrue(Files.isDirectory(root.resolve("node/untracked/empty")));
            assertEquals("before", Files.readString(root.resolve("node/tracked")));
        }
    }

    @Test void rejectsCaseUnicodeAndParentAliasesInEitherTreeBeforeMutation() throws Exception {
        Path root = project();
        try (Git git = repository()) {
            var repo = git.getRepository();
            var entry = blob(repo, "content", FileMode.REGULAR_FILE);
            for (String[] names : new String[][]{{"Foo", "foo"}, {"é", "e\u0301"},
                    {"Foo/a", "foo/b"}, {"File", "file/a"}, {"Straße", "STRASSE"}}) {
                var aliases = Map.of(names[0], entry, names[1], entry);
                for (boolean inCurrent : new boolean[]{false, true}) {
                    AtomicInteger mutations = new AtomicInteger();
                    var result = new SnapshotRestorer(root, repo, (r, o, p) -> mutations.incrementAndGet())
                            .restore("t", "b", inCurrent ? aliases : Map.of(), inCurrent ? Map.of() : aliases);
                    assertFalse(result.success(), String.join(" / ", names));
                    assertTrue(result.message().contains("碰撞"), result.message());
                    assertEquals(0, mutations.get());
                }
            }
        }
    }

    @Test void rejectsCrossSnapshotParentAliasesBeforeMutationAndPreservesOriginal() throws Exception {
        Path root = project();
        try (Git git = repository()) {
            var repo = git.getRepository();
            var before = blob(repo, "original", FileMode.REGULAR_FILE);
            var after = blob(repo, "replacement", FileMode.REGULAR_FILE);
            for (String[] names : new String[][]{{"Foo", "foo"}, {"é", "e\u0301"}}) {
                Path original = root.resolve(names[0]).resolve("a");
                Files.createDirectories(original.getParent());
                Files.writeString(original, "original");
                AtomicInteger mutations = new AtomicInteger();
                var result = new SnapshotRestorer(root, repo, (r, o, p) -> mutations.incrementAndGet())
                        .restore("t", "b", Map.of(names[0] + "/a", before), Map.of(names[1] + "/b", after));
                assertFalse(result.success());
                assertTrue(result.message().contains("碰撞"), result.message());
                assertEquals(0, mutations.get());
                assertEquals("original", Files.readString(original));
                assertFalse(Files.exists(root.resolve(names[1]).resolve("b")));
            }
        }
    }

    @Test void allowsExactPrefixesAndPathsSharedAcrossSnapshots() throws Exception {
        Path root = project();
        Files.createDirectory(root.resolve("shared"));
        Files.writeString(root.resolve("shared/a"), "before");
        Files.writeString(root.resolve("shared/keep"), "before");
        try (Git git = repository()) {
            var repo = git.getRepository();
            var before = blob(repo, "before", FileMode.REGULAR_FILE);
            var after = blob(repo, "after", FileMode.REGULAR_FILE);
            var result = new SnapshotRestorer(root, repo, RestoreFileOps.NONE).restore("t", "b",
                    Map.of("shared/a", before, "shared/keep", before),
                    Map.of("shared/b", after, "shared/keep", after));
            assertTrue(result.success(), result.message());
            assertFalse(Files.exists(root.resolve("shared/a")));
            assertEquals("after", Files.readString(root.resolve("shared/b")));
            assertEquals("after", Files.readString(root.resolve("shared/keep")));
        }
    }

    @Test void refusesEmptyDirectoryAddedAfterPreflightAndRollsBackTrackedChild() throws Exception {
        Path root = project();
        Files.createDirectories(root.resolve("node"));
        Files.writeString(root.resolve("node/tracked"), "before");
        try (Git git = repository()) {
            var repo = git.getRepository();
            RestoreFileOps hook = (r, o, p) -> {
                if (!r && o.equals("delete")) Files.createDirectory(root.resolve("node/new-empty"));
            };
            var result = new SnapshotRestorer(root, repo, hook).restore("t", "b",
                    Map.of("node/tracked", blob(repo, "before", FileMode.REGULAR_FILE)),
                    Map.of("node", blob(repo, "replacement", FileMode.REGULAR_FILE)));
            assertFalse(result.success());
            assertTrue(result.message().contains("已回滚"), result.message());
            assertTrue(Files.isDirectory(root.resolve("node/new-empty")));
            assertEquals("before", Files.readString(root.resolve("node/tracked")));
        }
    }

    @Test void rollsBackTargetOnlyPathWhenFailureOccursBeforeReplace() throws Exception {
        Path root = project();
        try (Git git = repository()) {
            var repo = git.getRepository();
            RestoreFileOps hook = (r, o, p) -> {
                if (!r && o.equals("replace")) throw new java.io.IOException("before replace");
            };
            var result = new SnapshotRestorer(root, repo, hook).restore("t", "b", Map.of(),
                    Map.of("new/deep/file", blob(repo, "new", FileMode.REGULAR_FILE)));
            assertFalse(result.success());
            assertTrue(result.message().contains("已回滚"), result.message());
            assertFalse(Files.exists(root.resolve("new")));
        }
    }

    @Test void rollsBackPartialApplyAndReportsRollbackFailureSeparately() throws Exception {
        posix();
        Path root = project();
        try (Git git = repository()) {
            var repo = git.getRepository();
            Map<String, TreeEntry> current = new LinkedHashMap<>(), target = new LinkedHashMap<>();
            current.put("a", blob(repo, "before-a", FileMode.EXECUTABLE_FILE));
            current.put("b", blob(repo, "before-b", FileMode.REGULAR_FILE));
            Path outside = temp.resolve("outside-target");
            Files.writeString(outside, "safe");
            current.put("link", blob(repo, outside.toString(), FileMode.SYMLINK));
            target.put("a", blob(repo, "after-a", FileMode.REGULAR_FILE));
            target.put("b", blob(repo, "after-b", FileMode.REGULAR_FILE));
            for (boolean failRollback : new boolean[]{false, true}) {
                Files.writeString(root.resolve("a"), "before-a");
                Files.writeString(root.resolve("b"), "before-b");
                Files.deleteIfExists(root.resolve("link"));
                Files.createSymbolicLink(root.resolve("link"), outside);
                AtomicInteger forward = new AtomicInteger(), rollback = new AtomicInteger();
                RestoreFileOps hook = (r, operation, path) -> {
                    if (r) {
                        rollback.incrementAndGet();
                        if (failRollback) throw new java.io.IOException("rollback-fault");
                    } else if (operation.equals("replace") && forward.incrementAndGet() == 2) {
                        throw new java.io.IOException("forward-once");
                    }
                };
                var result = new SnapshotRestorer(root, repo, hook).restore("target", "pre-restore", current, target);
                assertFalse(result.success());
                assertTrue(rollback.get() > 0);
                if (failRollback) {
                    assertTrue(result.message().contains("回滚失败"), result.message());
                    assertTrue(result.message().contains("rollback-fault"));
                } else {
                    assertTrue(result.message().contains("已回滚"), result.message());
                    assertEquals("before-a", Files.readString(root.resolve("a")));
                    assertEquals("before-b", Files.readString(root.resolve("b")));
                    assertTrue(Files.getPosixFilePermissions(root.resolve("a")).contains(PosixFilePermission.OWNER_EXECUTE));
                    assertEquals(outside, Files.readSymbolicLink(root.resolve("link")));
                    assertEquals("safe", Files.readString(outside));
                    try (var files = Files.list(root)) { assertEquals(3, files.count()); }
                }
            }
        }
    }
}
