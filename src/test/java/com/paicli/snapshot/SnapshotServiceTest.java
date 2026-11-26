package com.paicli.snapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SnapshotServiceTest {

    @Test
    void missingProjectRootDisablesSnapshotsInsteadOfThrowing(@TempDir Path tempDir) throws Exception {
        Path missing = tempDir.resolve("does-not-exist");

        try (SnapshotService service = SnapshotService.forProject(missing)) {
            assertFalse(service.available());
            assertNull(service.manager());

            // 每轮前后的快照直接跳过，不报错
            service.snapshotBeforeTurn("turn-1", "summary");
            service.snapshotAfterTurnAsync("turn-1", "summary");
            assertEquals("ok", service.runTurn("agent", "hello", () -> "ok"));

            assertTrue(service.listSnapshots(10).isEmpty());
            RestoreResult restore = service.restorePreTurn(1);
            assertFalse(restore.success());
            assertTrue(restore.message().contains("快照功能不可用"), restore.message());
            assertTrue(service.status().contains("不可用"), service.status());
            assertTrue(service.clean().contains("暂无"));
        }
    }

    @Test
    void existingProjectRootKeepsSnapshotsAvailable(@TempDir Path tempDir) {
        try (SnapshotService service = SnapshotService.forProject(tempDir)) {
            assertTrue(service.available());
            assertNotNull(service.manager());
        }
    }
}
