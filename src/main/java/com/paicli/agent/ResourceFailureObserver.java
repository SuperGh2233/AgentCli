package com.paicli.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.paicli.tool.ToolRegistry.ToolExecutionResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** 独立、默认关闭的观察器；不注入模型消息，也不参与 AgentBudget。 */
public final class ResourceFailureObserver {
    public static final String ENABLED_PROPERTY = "paicli.stagnation.resource.observer.enabled";

    public enum Notice { REPEATED_EDIT_SOURCE_NOT_FOUND }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Path root;
    private final boolean enabled;
    private String previous;
    private int streak;
    private boolean noticed;

    public ResourceFailureObserver(Path root) {
        this(root, Boolean.getBoolean(ENABLED_PROPERTY));
    }

    public ResourceFailureObserver(Path root, boolean enabled) {
        this.root = root;
        this.enabled = enabled;
    }

    /** 每步至多计一次；混合或不能确认的结果打断计数，不推断语义进展。 */
    public Optional<Notice> observe(List<ToolExecutionResult> results) {
        if (!enabled || noticed) return Optional.empty();
        String key = null;
        if (results != null && !results.isEmpty()) {
            for (ToolExecutionResult result : results) {
                String candidate = key(result);
                if (candidate == null || (key != null && !key.equals(candidate))) {
                    key = null;
                    break;
                }
                key = candidate;
            }
        }
        streak = key == null ? 0 : key.equals(previous) ? streak + 1 : 1;
        previous = key;
        if (streak < RunawayGuard.DEFAULT_REMIND_AFTER) return Optional.empty();
        noticed = true;
        return Optional.of(Notice.REPEATED_EDIT_SOURCE_NOT_FOUND);
    }

    private String key(ToolExecutionResult result) {
        if (result == null || result.successful() || result.timedOut()
                || !"edit_file".equals(result.name()) || result.result() == null
                || !result.result().startsWith("工具执行失败: old_text 在文件中不存在: ")) return null;
        try {
            JsonNode parsed = MAPPER.readTree(result.argumentsJson());
            if (!(parsed instanceof ObjectNode args) || !args.path("path").isTextual()
                    || !args.path("old_text").isTextual() || args.path("old_text").asText().isEmpty()
                    || !args.path("new_text").isTextual()) return null;
            Path safeRoot = root.toRealPath();
            Path raw = Path.of(args.path("path").asText());
            Path lexical = safeRoot.resolve(raw).normalize();
            if (!lexical.startsWith(safeRoot)) return null;
            Path safe = lexical.toRealPath(); // 解析失败就跳过，不做宽松回退。
            if (!safe.startsWith(safeRoot) || !Files.isRegularFile(safe)) return null;
            ObjectNode signature = args.deepCopy();
            signature.remove("new_text"); // 源文本没匹配到时，替换文本不会参与匹配。
            signature.put("path", lexical.toString()); // 保留大小写，不合并符号链接别名。
            return RunawayGuard.actionKey(result.name(), MAPPER.writeValueAsString(signature));
        } catch (Exception ignored) {
            return null;
        }
    }
}
