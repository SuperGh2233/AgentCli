package com.haocli.policy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 路径围栏：所有文件类工具调用必须先经过它。
 *
 * 定位：HITL 之前的 LLM 输入合法性检查，不是沙箱（不提供进程隔离）。
 *
 * 解决三类越界场景：
 * 1. 绝对路径直接逃出项目根（LLM 给出 /etc/passwd）
 * 2. 相对路径用 .. 穿越（../../etc/passwd）
 * 3. 符号链接逃逸（项目内的软链指向外部目录，包括指向外部尚不存在文件的悬空软链）
 *
 * 设计要点：
 * - 拒绝时抛 {@link PolicyException}，由调用方决定怎么呈现给用户和 LLM
 * - 不存在的路径也能校验（write_file 创建新文件场景）：向上找最近的存在祖先解析 realPath，再把剩余段接回
 * - 校验通过后返回的 Path 是已规范化的绝对路径，调用方直接拿来读写
 */
public class PathGuard {

    /** 和 Linux 内核的 MAXSYMLINKS 一致，超过就当作循环链接拒绝。 */
    private static final int MAX_SYMLINK_HOPS = 40;

    private final Path rootPath;

    public PathGuard(String root) {
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException("项目根路径不能为空");
        }
        Path candidate = Paths.get(root).toAbsolutePath().normalize();
        // macOS 上 /var/folders → /private/var/folders 这类符号链接，必须先把根本身展开成真实路径，
        // 否则后面 resolveRealPath 会把目标展开成 /private/... 但根仍是 /var/...，startsWith 永远 false。
        Path real = candidate;
        try {
            if (Files.exists(candidate)) {
                real = candidate.toRealPath();
            }
        } catch (IOException ignored) {
        }
        this.rootPath = real;
    }

    public Path getRootPath() {
        return rootPath;
    }

    /**
     * 校验路径是否在项目根之内，返回安全的绝对路径。
     */
    public Path resolveSafe(String input) {
        if (input == null || input.isBlank()) {
            throw new PolicyException("路径不能为空");
        }

        Path raw = Paths.get(input);
        Path resolved = raw.isAbsolute()
                ? raw.normalize()
                : rootPath.resolve(raw).normalize();

        Path realResolved = resolveRealPath(resolved);

        if (!realResolved.startsWith(rootPath)) {
            throw new PolicyException(
                    "路径越界: " + input + " 不在项目根 " + rootPath + " 之内");
        }
        return realResolved;
    }

    /**
     * 向上找到最近的存在祖先，调用 toRealPath 解析其中的符号链接，再把剩余段接回。
     *
     * 这样做的目的：write_file 给一个尚不存在的目标路径时，仍能识别出"路径中段是个软链且指向外部"的越界情况。
     *
     * 悬空软链（链接本身存在、指向的目标不存在）要单独处理：Files.exists 默认跟随链接，会把它当成"不存在"
     * 继续往上找，结果返回的路径里还留着这个链接，写入时操作系统再跟随它，就会在项目外创建文件。
     * 所以向上找时不跟随链接；找到的是软链就读出它的目标，接上剩余段后重新解析，直到返回的路径里不含软链。
     * 解析失败时直接拒绝，不退回未解析的路径。
     */
    private Path resolveRealPath(Path target) {
        Path current = target.toAbsolutePath().normalize();
        for (int hops = 0; hops <= MAX_SYMLINK_HOPS; hops++) {
            Path existing = current;
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            if (existing == null) {
                return current;
            }
            Path remainder = existing.relativize(current);
            try {
                if (!Files.isSymbolicLink(existing)) {
                    return existing.toRealPath().resolve(remainder).normalize();
                }
                // 链接目标是相对路径时，相对的是链接所在目录；先把该目录解析成真实路径再接上目标。
                Path linkDir = existing.getParent().toRealPath();
                Path linkTarget = Files.readSymbolicLink(existing);
                current = linkDir.resolve(linkTarget).resolve(remainder).normalize();
            } catch (IOException e) {
                throw new PolicyException("无法解析路径: " + target + "（" + e.getMessage() + "）");
            }
        }
        throw new PolicyException("符号链接层级过深或存在循环: " + target);
    }
}
