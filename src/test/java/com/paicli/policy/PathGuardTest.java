package com.paicli.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class PathGuardTest {

    @Test
    void allowsRelativePathInsideRoot(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(root.toString());
        Path resolved = guard.resolveSafe("src/Main.java");
        assertTrue(resolved.startsWith(root.toRealPath()));
        assertTrue(resolved.endsWith(Path.of("src", "Main.java")));
    }

    @Test
    void allowsAbsolutePathInsideRoot(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(root.toString());
        Path target = root.resolve("a/b.txt");
        Path resolved = guard.resolveSafe(target.toString());
        assertTrue(resolved.startsWith(root.toRealPath()));
    }

    @Test
    void allowsCurrentDirectory(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(root.toString());
        Path resolved = guard.resolveSafe(".");
        assertEquals(root.toRealPath(), resolved);
    }

    @Test
    void allowsNonExistingTargetForCreate(@TempDir Path root) throws Exception {
        PathGuard guard = new PathGuard(root.toString());
        Path resolved = guard.resolveSafe("nested/deeply/new-file.txt");
        assertTrue(resolved.startsWith(root.toRealPath()));
        assertFalse(Files.exists(resolved));
    }

    @Test
    void rejectsAbsolutePathOutsideRoot(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        PolicyException ex = assertThrows(PolicyException.class,
                () -> guard.resolveSafe("/etc/passwd"));
        assertTrue(ex.getMessage().contains("路径越界"));
    }

    @Test
    void rejectsParentTraversalEscape(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class,
                () -> guard.resolveSafe("../../etc/passwd"));
    }

    @Test
    void rejectsParentTraversalThroughLeadingDots(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class,
                () -> guard.resolveSafe(".."));
    }

    @Test
    void rejectsBlankPath(@TempDir Path root) {
        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class, () -> guard.resolveSafe(""));
        assertThrows(PolicyException.class, () -> guard.resolveSafe("   "));
        assertThrows(PolicyException.class, () -> guard.resolveSafe(null));
    }

    @Test
    void rejectsSymlinkEscapingRoot(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path outsideTarget = outside.resolve("secret.txt");
        Files.writeString(outsideTarget, "leak");

        Path linkInsideRoot = root.resolve("backdoor");
        try {
            Files.createSymbolicLink(linkInsideRoot, outside);
        } catch (UnsupportedOperationException | IOException e) {
            // 当前文件系统不支持符号链接（Windows 无管理员权限），跳过此用例
            return;
        }

        PathGuard guard = new PathGuard(root.toString());
        PolicyException ex = assertThrows(PolicyException.class,
                () -> guard.resolveSafe("backdoor/secret.txt"));
        assertTrue(ex.getMessage().contains("路径越界"));
    }

    @Test
    void rejectsDanglingSymlinkPointingOutsideRoot(@TempDir Path root, @TempDir Path outside) throws IOException {
        Path outsideTarget = outside.resolve("created-by-agent.txt");
        createSymlinkOrSkip(root.resolve("dangling"), outsideTarget);

        PathGuard guard = new PathGuard(root.toString());
        PolicyException ex = assertThrows(PolicyException.class,
                () -> guard.resolveSafe("dangling"));
        assertTrue(ex.getMessage().contains("路径越界"));
        assertFalse(Files.exists(outsideTarget));
    }

    @Test
    void rejectsPathBelowDanglingDirectorySymlink(@TempDir Path root, @TempDir Path outside) throws IOException {
        createSymlinkOrSkip(root.resolve("dangling-dir"), outside.resolve("missing-dir"));

        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class,
                () -> guard.resolveSafe("dangling-dir/sub/file.txt"));
    }

    @Test
    void rejectsDanglingSymlinkChainEndingOutsideRoot(@TempDir Path root, @TempDir Path outside) throws IOException {
        // a → b（项目内，本身也是悬空软链）→ 项目外不存在的文件
        createSymlinkOrSkip(root.resolve("b"), outside.resolve("new.txt"));
        createSymlinkOrSkip(root.resolve("a"), Path.of("b"));

        PathGuard guard = new PathGuard(root.toString());
        assertThrows(PolicyException.class, () -> guard.resolveSafe("a"));
    }

    @Test
    void allowsDanglingSymlinkPointingInsideRoot(@TempDir Path root) throws IOException {
        createSymlinkOrSkip(root.resolve("alias"), Path.of("real", "new.txt"));

        PathGuard guard = new PathGuard(root.toString());
        Path resolved = guard.resolveSafe("alias");
        // 返回的是链接展开后的真实目标，写入时不会再经过软链
        assertEquals(root.toRealPath().resolve("real").resolve("new.txt"), resolved);
    }

    @Test
    void rejectsSymlinkLoop(@TempDir Path root) throws IOException {
        createSymlinkOrSkip(root.resolve("x"), Path.of("y"));
        createSymlinkOrSkip(root.resolve("y"), Path.of("x"));

        PathGuard guard = new PathGuard(root.toString());
        PolicyException ex = assertThrows(PolicyException.class, () -> guard.resolveSafe("x/file.txt"));
        assertTrue(ex.getMessage().contains("循环"));
    }

    private static void createSymlinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            // 当前文件系统不支持符号链接（Windows 无管理员权限），跳过此用例
            assumeTrue(false, "symbolic links unsupported: " + e.getMessage());
        }
    }

    @Test
    void rejectNullRoot() {
        assertThrows(IllegalArgumentException.class, () -> new PathGuard(null));
        assertThrows(IllegalArgumentException.class, () -> new PathGuard(""));
        assertThrows(IllegalArgumentException.class, () -> new PathGuard("  "));
    }
}
