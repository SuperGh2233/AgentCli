package com.paicli.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SideGitManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesRootSymlinkAndRestoresThroughIndependentPreRestoreCommit() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path alias = Files.createSymbolicLink(tempDir.resolve("alias"), project);
        Files.writeString(project.resolve("a"), "original-a");
        Files.writeString(project.resolve("b"), "original-b");
        AtomicInteger replaces = new AtomicInteger();
        SnapshotConfig config = new SnapshotConfig(true, tempDir.resolve("snapshots"), 50, List.of(".git"));
        SideGitManager manager = new SideGitManager(alias, config,
                (rollback, operation, path) -> {
                    if (!rollback && operation.equals("replace") && replaces.incrementAndGet() == 2) {
                        throw new java.io.IOException("one-shot");
                    }
                });
        assertEquals(project.toRealPath(), manager.projectRoot());
        assertNotEquals(manager.gitDir(), new SideGitManager(project, config).gitDir(),
                "快照存储身份应保留传入的软链接路径，避免迁移旧目录");
        manager.preTurnSnapshot("turn", "before");
        Files.writeString(project.resolve("a"), "current-a");
        Files.writeString(project.resolve("b"), "current-b");
        RestoreResult result = manager.restorePreTurn(1);
        assertFalse(result.success());
        assertTrue(result.message().contains("已回滚"), result.message());
        assertEquals("current-a", Files.readString(project.resolve("a")));
        assertEquals("current-b", Files.readString(project.resolve("b")));
        assertTrue(manager.listSnapshots(10).stream().anyMatch(s -> s.phase() == SnapshotPhase.PRE_RESTORE));
    }

    @Test
    void managerReplacesTrackedSymlinkParentAndLeavesIgnoredChildrenUntouched() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.getFileStore(tempDir).supportsFileAttributeView("posix"));
        Path project = Files.createDirectory(tempDir.resolve("project"));
        Path outside = Files.createDirectory(tempDir.resolve("outside"));
        Files.writeString(outside.resolve("child"), "outside-safe");
        Files.createDirectory(project.resolve("dir"));
        Files.writeString(project.resolve("dir/child"), "before");
        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, tempDir.resolve("snapshots"), 50, List.of(".git", "ignored")));
        manager.preTurnSnapshot("turn", "before");
        Files.delete(project.resolve("dir/child"));
        Files.delete(project.resolve("dir"));
        Files.createSymbolicLink(project.resolve("dir"), outside);
        Files.createDirectories(project.resolve("ignored/empty"));
        RestoreResult result = manager.restorePreTurn(1);
        assertTrue(result.success(), result.message());
        assertFalse(Files.isSymbolicLink(project.resolve("dir")));
        assertEquals("before", Files.readString(project.resolve("dir/child")));
        assertEquals("outside-safe", Files.readString(outside.resolve("child")));
        assertTrue(Files.isDirectory(project.resolve("ignored/empty")));
    }

    @Test
    void restoresTrackedFilesToPreTurnSnapshot() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        Files.writeString(project.resolve("a.txt"), "before");

        SideGitManager manager = new SideGitManager(project,
                new SnapshotConfig(true, snapshots, 50, List.of(".git", "target", "*.class")));
        manager.preTurnSnapshot("turn-1", "before task");

        Files.writeString(project.resolve("a.txt"), "after");
        Files.writeString(project.resolve("new.txt"), "new file");
        manager.postTurnSnapshot("turn-1", "after task");

        RestoreResult result = manager.restorePreTurn(1);

        assertTrue(result.success());
        assertEquals("before", Files.readString(project.resolve("a.txt")));
        assertFalse(Files.exists(project.resolve("new.txt")));
        assertTrue(Files.exists(manager.gitDir().resolve("config")));
    }

    @Test
    void serviceWritesPostTurnSnapshotAsynchronously() throws Exception {
        Path project = tempDir.resolve("project");
        Path snapshots = tempDir.resolve("snapshots");
        Files.createDirectories(project);
        SnapshotConfig config = new SnapshotConfig(true, snapshots, 50, List.of(".git", "target"));
        SnapshotService service = new SnapshotService(new SideGitManager(project, config));

        String output = service.runTurn("react", "write file", () -> {
            Files.writeString(project.resolve("a.txt"), "created");
            return "ok";
        });
        service.awaitIdle();

        assertEquals("ok", output);
        List<TurnSnapshot> all = service.listSnapshots(10);
        assertEquals(2, all.size());
        assertEquals(SnapshotPhase.POST_TURN, all.get(0).phase());
        assertEquals(SnapshotPhase.PRE_TURN, all.get(1).phase());
    }
}
