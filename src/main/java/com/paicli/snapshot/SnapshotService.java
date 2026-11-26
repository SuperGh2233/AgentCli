package com.paicli.snapshot;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class SnapshotService implements AutoCloseable {
    /** 项目根目录无法解析时为 null：这时快照功能关闭，其余功能照常工作。 */
    private final SideGitManager manager;
    private final String unavailableReason;
    private final ExecutorService executor;
    private volatile Future<?> lastAsyncTask;

    public SnapshotService(SideGitManager manager) {
        this(manager, null);
    }

    private SnapshotService(SideGitManager manager, String unavailableReason) {
        this.manager = manager;
        this.unavailableReason = unavailableReason;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-snapshot-writer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 项目根目录不存在或无法解析为真实路径时，不抛异常，而是返回一个关闭了快照功能的服务：
     * 快照只是兜底功能，不能因为它让切换项目目录、恢复会话这些操作失败。
     */
    public static SnapshotService forProject(Path projectRoot) {
        try {
            return new SnapshotService(new SideGitManager(projectRoot));
        } catch (IllegalArgumentException e) {
            return new SnapshotService(null, e.getMessage() + ": " + projectRoot);
        }
    }

    public boolean available() {
        return manager != null;
    }

    public <T> T runTurn(String mode, String input, ThrowingSupplier<T> supplier) throws Exception {
        String turnId = turnId(mode);
        String summary = summarize(mode, input);
        snapshotBeforeTurn(turnId, summary);
        try {
            return supplier.get();
        } finally {
            snapshotAfterTurnAsync(turnId, summary);
        }
    }

    public void snapshotBeforeTurn(String turnId, String summary) {
        if (manager == null || !manager.config().enabled()) {
            return;
        }
        try {
            manager.preTurnSnapshot(turnId, summary);
        } catch (Exception e) {
            System.err.println("⚠️ pre-turn 快照失败: " + e.getMessage());
        }
    }

    public void snapshotAfterTurnAsync(String turnId, String summary) {
        if (manager == null || !manager.config().enabled()) {
            return;
        }
        lastAsyncTask = executor.submit(() -> {
            try {
                manager.postTurnSnapshot(turnId, summary);
            } catch (Exception e) {
                System.err.println("⚠️ post-turn 快照失败: " + e.getMessage());
            }
        });
    }

    public List<TurnSnapshot> listSnapshots(int limit) throws Exception {
        if (manager == null) {
            return List.of();
        }
        awaitIdle();
        return manager.listSnapshots(limit);
    }

    public RestoreResult restorePreTurn(int offset) throws Exception {
        if (manager == null) {
            return RestoreResult.failure("快照功能不可用：" + unavailableReason);
        }
        awaitIdle();
        return manager.restorePreTurn(offset);
    }

    public String status() {
        if (manager == null) {
            return "📸 Side-Git 快照状态\n   状态: 不可用（" + unavailableReason + "）";
        }
        return manager.formatStatus();
    }

    public String clean() {
        if (manager == null) {
            return "📭 暂无 Side-Git 快照目录";
        }
        return manager.cleanSnapshots();
    }

    /** 快照功能不可用（见 {@link #available()}）时返回 null。 */
    public SideGitManager manager() {
        return manager;
    }

    public void awaitIdle() throws Exception {
        Future<?> task = lastAsyncTask;
        if (task != null) {
            task.get(60, TimeUnit.SECONDS);
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private static String turnId(String mode) {
        String safeMode = mode == null || mode.isBlank() ? "turn" : mode.toLowerCase().replaceAll("[^a-z0-9_-]", "-");
        return safeMode + "-" + Instant.now().toEpochMilli();
    }

    private static String summarize(String mode, String input) {
        String normalized = input == null ? "" : input.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 120) {
            normalized = normalized.substring(0, 120) + "...";
        }
        return "mode=" + (mode == null ? "turn" : mode) + "\ninput=" + normalized;
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
