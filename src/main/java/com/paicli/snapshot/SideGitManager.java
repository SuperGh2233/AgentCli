package com.paicli.snapshot;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class SideGitManager {
    private static final PersonIdent SNAPSHOT_IDENT = new PersonIdent("PaiCLI Snapshot", "snapshot@paicli.local");

    private final Path projectRoot;
    private final Path snapshotIdentityRoot;
    private final SnapshotConfig config;
    private final Path gitDir;
    private final RestoreFileOps restoreFileOps;

    public SideGitManager(Path projectRoot) {
        this(projectRoot, SnapshotConfig.fromEnvironment());
    }

    public SideGitManager(Path projectRoot, SnapshotConfig config) {
        this(projectRoot, config, RestoreFileOps.NONE);
    }

    SideGitManager(Path projectRoot, SnapshotConfig config, RestoreFileOps restoreFileOps) {
        Path requestedRoot = projectRoot == null ? Path.of(System.getProperty("user.dir")) : projectRoot;
        this.snapshotIdentityRoot = requestedRoot.toAbsolutePath().normalize();
        this.projectRoot = normalizeProjectRoot(requestedRoot);
        this.config = config == null ? SnapshotConfig.fromEnvironment() : config;
        this.restoreFileOps = restoreFileOps;
        this.gitDir = this.config.snapshotsRoot()
                .resolve(hash(parentKey(this.snapshotIdentityRoot)))
                .resolve(hash(this.snapshotIdentityRoot.toString()))
                .resolve(".git");
    }

    public synchronized TurnSnapshot preTurnSnapshot(String turnId, String summary) throws IOException, GitAPIException {
        return createSnapshot(SnapshotPhase.PRE_TURN, turnId, summary);
    }

    public synchronized TurnSnapshot postTurnSnapshot(String turnId, String summary) throws IOException, GitAPIException {
        return createSnapshot(SnapshotPhase.POST_TURN, turnId, summary);
    }

    public synchronized TurnSnapshot preRestoreSnapshot(String turnId, String summary) throws IOException, GitAPIException {
        return createSnapshot(SnapshotPhase.PRE_RESTORE, turnId, summary);
    }

    public synchronized TurnSnapshot createSnapshot(SnapshotPhase phase, String turnId, String summary)
            throws IOException, GitAPIException {
        if (!config.enabled()) {
            return null;
        }
        try (Git git = openGit()) {
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call();
            String message = phase.label() + " " + safeTurnId(turnId)
                    + (summary == null || summary.isBlank() ? "" : "\n\n" + summary.trim());
            RevCommit commit = git.commit()
                    .setAllowEmpty(true)
                    .setAuthor(SNAPSHOT_IDENT)
                    .setCommitter(SNAPSHOT_IDENT)
                    .setMessage(message)
                    .call();
            return toSnapshot(commit);
        }
    }

    public synchronized List<TurnSnapshot> listSnapshots(int limit) throws IOException, GitAPIException {
        if (!config.enabled() || !Files.exists(gitDir.resolve("config"))) {
            return List.of();
        }
        int max = limit <= 0 ? config.maxSnapshots() : limit;
        List<TurnSnapshot> snapshots = new ArrayList<>();
        try (Git git = openGit()) {
            for (RevCommit commit : git.log().setMaxCount(max).call()) {
                snapshots.add(toSnapshot(commit));
            }
        }
        return snapshots;
    }

    public synchronized List<TurnSnapshot> listPreTurnSnapshots(int limit) throws IOException, GitAPIException {
        return listSnapshots(limit <= 0 ? config.maxSnapshots() * 4 : limit).stream()
                .filter(snapshot -> snapshot.phase() == SnapshotPhase.PRE_TURN)
                .limit(limit <= 0 ? config.maxSnapshots() : limit)
                .toList();
    }

    public synchronized RestoreResult restorePreTurn(int offset) throws IOException, GitAPIException {
        if (!config.enabled()) {
            return RestoreResult.failure("快照功能已关闭");
        }
        int normalizedOffset = Math.max(1, offset);
        List<TurnSnapshot> preTurns = listPreTurnSnapshots(Math.max(normalizedOffset, config.maxSnapshots()));
        if (preTurns.size() < normalizedOffset) {
            return RestoreResult.failure("找不到最近第 " + normalizedOffset + " 个 pre-turn 快照");
        }
        TurnSnapshot target = preTurns.get(normalizedOffset - 1);
        TurnSnapshot current = preRestoreSnapshot("restore-" + Instant.now().toEpochMilli(),
                "Before restoring " + target.shortCommitId());
        try (Git git = openGit(); Repository repository = git.getRepository()) {
            if (current == null) {
                return RestoreResult.failure("恢复失败：未取得 pre-restore 回滚快照，未修改项目文件");
            }
            Map<String, TreeEntry> targetTree = treeEntries(repository, ObjectId.fromString(target.commitId()));
            Map<String, TreeEntry> currentTree = treeEntries(repository, ObjectId.fromString(current.commitId()));
            return new SnapshotRestorer(projectRoot, repository, restoreFileOps)
                    .restore(target.commitId(), current.commitId(), currentTree, targetTree);
        } catch (IOException | RuntimeException e) {
            return RestoreResult.failure("恢复失败：" + e.getMessage());
        }
    }

    public synchronized String formatStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("📸 Side-Git 快照状态\n");
        sb.append("   状态: ").append(config.enabled() ? "启用" : "关闭").append('\n');
        sb.append("   项目根: ").append(projectRoot).append('\n');
        sb.append("   Side-Git: ").append(gitDir).append('\n');
        sb.append("   最大展示/保留数: ").append(config.maxSnapshots()).append('\n');
        sb.append("   排除: ").append(String.join(", ", config.excludes())).append('\n');
        try {
            List<TurnSnapshot> snapshots = listSnapshots(1);
            if (snapshots.isEmpty()) {
                sb.append("   最近快照: 暂无");
            } else {
                TurnSnapshot latest = snapshots.get(0);
                sb.append("   最近快照: ")
                        .append(latest.phase().label())
                        .append(" ")
                        .append(latest.shortCommitId())
                        .append(" ")
                        .append(latest.createdAt());
            }
        } catch (Exception e) {
            sb.append("   最近快照: 读取失败 - ").append(e.getMessage());
        }
        return sb.toString();
    }

    public synchronized String cleanSnapshots() {
        if (!Files.exists(gitDir)) {
            return "📭 暂无 Side-Git 快照目录";
        }
        try {
            deleteRecursively(gitDir.getParent());
            return "🧹 已清理当前项目的 Side-Git 快照目录: " + gitDir.getParent();
        } catch (IOException e) {
            return "❌ 清理快照失败: " + e.getMessage();
        }
    }

    public Path gitDir() {
        return gitDir;
    }

    public Path projectRoot() {
        return projectRoot;
    }

    public SnapshotConfig config() {
        return config;
    }

    private Git openGit() throws IOException, GitAPIException {
        Files.createDirectories(gitDir.getParent());
        if (!Files.exists(gitDir.resolve("config"))) {
            Git.init()
                    .setGitDir(gitDir.toFile())
                    .setDirectory(projectRoot.toFile())
                    .call()
                    .close();
        }
        writeExcludeFile();
        Repository repository = new FileRepositoryBuilder()
                .setGitDir(gitDir.toFile())
                .setWorkTree(projectRoot.toFile())
                .build();
        return new Git(repository);
    }

    private void writeExcludeFile() throws IOException {
        Path info = gitDir.resolve("info");
        Files.createDirectories(info);
        StringBuilder sb = new StringBuilder("# Managed by PaiCLI side-history snapshots\n");
        for (String exclude : config.excludes()) {
            sb.append(exclude).append('\n');
        }
        Files.writeString(info.resolve("exclude"), sb.toString(), StandardCharsets.UTF_8);
    }

    private TurnSnapshot toSnapshot(RevCommit commit) {
        String firstLine = Optional.ofNullable(commit.getShortMessage()).orElse("");
        String[] parts = firstLine.split("\\s+", 2);
        SnapshotPhase phase = parsePhase(parts.length > 0 ? parts[0] : "");
        String turnId = parts.length > 1 ? parts[1] : "";
        return new TurnSnapshot(
                commit.getId().name(),
                phase,
                turnId,
                Instant.ofEpochSecond(commit.getCommitTime()),
                firstLine
        );
    }

    private SnapshotPhase parsePhase(String value) {
        for (SnapshotPhase phase : SnapshotPhase.values()) {
            if (phase.label().equals(value)) {
                return phase;
            }
        }
        return SnapshotPhase.POST_TURN;
    }

    record TreeEntry(ObjectId objectId, FileMode mode) { }

    private Map<String, TreeEntry> treeEntries(Repository repository, ObjectId commitId) throws IOException {
        Map<String, TreeEntry> entries = new LinkedHashMap<>();
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit commit = walk.parseCommit(commitId);
            try (TreeWalk treeWalk = new TreeWalk(repository)) {
                treeWalk.addTree(commit.getTree());
                treeWalk.setRecursive(true);
                while (treeWalk.next()) {
                    String path = treeWalk.getPathString();
                    TreeEntry entry = new TreeEntry(treeWalk.getObjectId(0), treeWalk.getFileMode(0));
                    if (!isExcluded(path)) {
                        SnapshotRestorer.validateEntry(path, entry);
                        entries.put(path, entry);
                    }
                }
            }
        }
        return entries;
    }

    private boolean isExcluded(String relative) {
        String normalized = relative.replace('\\', '/');
        for (String raw : config.excludes()) {
            String pattern = raw == null ? "" : raw.trim().replace('\\', '/');
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.endsWith("/")) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (normalized.equals(pattern) || normalized.startsWith(pattern + "/")) {
                return true;
            }
            if (pattern.contains("*")) {
                PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
                Path fileName = Path.of(normalized).getFileName();
                if (fileName != null && matcher.matches(fileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Path normalizeProjectRoot(Path path) {
        Path root = path == null ? Path.of(System.getProperty("user.dir")) : path;
        try {
            return root.toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("项目根目录无法解析为真实路径", e);
        }
    }

    private static String parentKey(Path path) {
        Path parent = path.getParent();
        return parent == null ? path.toString() : parent.toString();
    }

    private static String safeTurnId(String turnId) {
        return turnId == null || turnId.isBlank() ? "turn-" + Instant.now().toEpochMilli() : turnId.trim();
    }

    private static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
