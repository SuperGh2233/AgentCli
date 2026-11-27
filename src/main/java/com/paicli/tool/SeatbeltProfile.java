package com.paicli.tool;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * macOS Seatbelt 后端：把 {@link CommandSandbox} 的统一策略（系统目录只读、仅工作区可写、
 * 无网络）翻译成 {@code sandbox-exec -p} 的 profile 文本。
 *
 * <p>profile 作为单独一个 argv 传给 {@code sandbox-exec}，命令本身也作为单独一个 argv 交给
 * {@code /bin/bash -c}，两者都不会拼进另一层 shell 字符串。</p>
 */
final class SeatbeltProfile {
    private static final Path DEV_NULL = Path.of("/dev/null");
    /**
     * 只放行这些路径本身（literal），不含子树。根目录 "/" 本身必须可读：较新的 macOS 上
     * dyld / bash 启动时会读取根目录条目，缺了它探针直接以 SIGABRT（exit 134）退出。
     */
    private static final List<Path> SYSTEM_READ_LITERAL_PATHS = List.of(
            Path.of("/"),
            Path.of("/dev/null"),
            Path.of("/dev/random"),
            Path.of("/dev/urandom"),
            Path.of("/dev/zero")
    );

    /**
     * {@code /usr/bin/python3}、{@code git} 这类 Xcode 命令行工具 shim 启动时要读
     * {@code /var/select/developer_dir}（没有就回退到默认位置）。{@code /var} 本身是指向
     * {@code /private/var} 的符号链接，只放行这条链接的元数据和 developer_dir 这一个链接，
     * 不放行 {@code /private/var} 下的任何子树。
     */
    private static final Path VAR_SYMLINK = Path.of("/var");
    private static final Path XCODE_DEVELOPER_DIR_LINK = Path.of("/private/var/select/developer_dir");

    private SeatbeltProfile() {
    }

    static List<String> arguments(Path executable, String profile, String command) {
        return List.of(
                executable.toString(),
                "-p",
                profile,
                "/bin/bash",
                "-c",
                command);
    }

    static String build(Path workspace, Collection<Path> readableRoots) {
        StringBuilder profile = new StringBuilder();
        profile.append("(version 1)\n")
                .append("(deny default)\n")
                .append("(deny network*)\n")
                .append("(allow process*)\n")
                .append("(allow sysctl-read)\n")
                // macOS language runtimes use Mach services for basic process/runtime setup.
                // This remains broader than container/VM IPC isolation; network and filesystem
                // policy are still independently denied/restricted by this Seatbelt profile.
                .append("(allow mach-lookup)\n")
                .append("(allow file-read*\n");
        for (Path readableRoot : readableRoots) {
            appendPathFilters(profile, readableRoot);
        }
        for (Path readablePath : SYSTEM_READ_LITERAL_PATHS) {
            if (Files.exists(readablePath)) {
                appendLiteralFilter(profile, readablePath);
            }
        }
        appendLiteralFilter(profile, XCODE_DEVELOPER_DIR_LINK);
        profile.append(")\n");
        appendAncestorMetadata(profile, workspace, readableRoots);
        profile.append("(allow file-write*\n");
        appendPathFilters(profile, workspace);
        profile.append("  (literal ").append(seatbeltString(DEV_NULL)).append(")\n");
        profile.append(")\n");
        return profile.toString();
    }

    /**
     * 可读目录的各级父目录只放行元数据（literal，不含子树、不能读内容或列目录）。JDK 等运行时
     * 启动时会逐级查看自己所在路径；装在 {@code ~/Library/Java} 这类目录下时，缺了父目录的
     * 元数据，{@code java -version} 直接以 SIGSEGV（exit 139）退出。
     */
    private static void appendAncestorMetadata(StringBuilder profile,
                                               Path workspace,
                                               Collection<Path> readableRoots) {
        Set<Path> ancestors = new LinkedHashSet<>();
        if (Files.exists(VAR_SYMLINK, LinkOption.NOFOLLOW_LINKS)) {
            ancestors.add(VAR_SYMLINK);
        }
        collectAncestors(ancestors, workspace);
        for (Path readableRoot : readableRoots) {
            collectAncestors(ancestors, readableRoot);
        }
        profile.append("(allow file-read-metadata\n");
        for (Path ancestor : ancestors) {
            appendLiteralFilter(profile, ancestor);
        }
        profile.append(")\n");
    }

    private static void collectAncestors(Set<Path> ancestors, Path path) {
        for (Path parent = path.getParent(); parent != null; parent = parent.getParent()) {
            ancestors.add(parent);
        }
    }

    private static void appendPathFilters(StringBuilder profile, Path path) {
        String literal = seatbeltString(path);
        profile.append("  (literal ").append(literal).append(")\n")
                .append("  (subpath ").append(literal).append(")\n");
    }

    private static void appendLiteralFilter(StringBuilder profile, Path path) {
        profile.append("  (literal ").append(seatbeltString(path)).append(")\n");
    }

    private static String seatbeltString(Path path) {
        String value = path.toString();
        CommandSandbox.rejectControlCharacters(path, "sandbox profile path");
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }
}
