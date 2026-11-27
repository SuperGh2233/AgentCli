package com.haocli.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.haocli.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 压缩第 3 层：局部折叠。不调用模型，把已经过时的局部片段折叠成一行。
 *
 * <ul>
 *   <li>某个文件的 read_file 结果：之后同一范围又被读取过，或同一文件被写入、编辑过</li>
 *   <li>某条 execute_command 的输出：之后同一条命令又执行过</li>
 *   <li>list_dir / glob_files / grep_code / search_code：之后又用完全相同的参数调用过</li>
 * </ul>
 *
 * <p>以后面的结果为准，前面那次只留一行说明。只替换内容、不删消息；最近 {@value #KEEP_RECENT_TOOL_RESULTS}
 * 条工具结果不动。只在上下文超过摘要阈值的 {@value #TRIGGER_RATIO} 倍时才做（改旧消息会让前缀缓存失效）。</p>
 */
public final class LocalFolder {
    private static final Logger log = LoggerFactory.getLogger(LocalFolder.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String MARKER = "[局部折叠]";
    static final double TRIGGER_RATIO = 0.5;
    static final int KEEP_RECENT_TOOL_RESULTS = 2;
    static final int MIN_FOLDABLE_CHARS = 200;

    public int triggerTokens(int compactionTriggerTokens) {
        if (compactionTriggerTokens <= 0) return 0;
        return Math.max(1, (int) Math.floor(compactionTriggerTokens * TRIGGER_RATIO));
    }

    public Result foldIfNeeded(List<LlmClient.Message> history, int compactionTriggerTokens) {
        int trigger = triggerTokens(compactionTriggerTokens);
        if (history == null || history.isEmpty() || trigger <= 0) return Result.none();
        if (TokenBudget.estimateMessagesTokens(history) < trigger) return Result.none();
        return fold(history);
    }

    /** 不看阈值立即折叠。 */
    public Result fold(List<LlmClient.Message> history) {
        if (history == null || history.isEmpty()) return Result.none();
        Map<String, Call> calls = callsById(history);
        List<Integer> toolIndices = new ArrayList<>();
        List<Call> toolCalls = new ArrayList<>();
        for (int i = 0; i < history.size(); i++) {
            LlmClient.Message message = history.get(i);
            if ("tool".equals(message.role())) {
                toolIndices.add(i);
                toolCalls.add(calls.get(message.toolCallId()));
            }
        }
        int end = Math.max(0, toolIndices.size() - KEEP_RECENT_TOOL_RESULTS);
        int folded = 0;
        for (int n = 0; n < end; n++) {
            Call call = toolCalls.get(n);
            if (call == null || call.key() == null) continue;
            int index = toolIndices.get(n);
            LlmClient.Message message = history.get(index);
            String content = message.content();
            if (content == null || content.length() < MIN_FOLDABLE_CHARS || content.startsWith(MARKER)
                    || content.startsWith(ToolResultClearer.PLACEHOLDER_PREFIX)) {
                continue;
            }
            if (!supersededLater(call, toolCalls, n)) continue;
            history.set(index, LlmClient.Message.tool(message.toolCallId(), placeholder(call)));
            folded++;
        }
        if (folded > 0) {
            log.info("local folding: {} superseded tool result(s)", folded);
        }
        return new Result(folded);
    }

    private static boolean supersededLater(Call call, List<Call> calls, int position) {
        for (int m = position + 1; m < calls.size(); m++) {
            Call later = calls.get(m);
            if (later == null) continue;
            // 同一文件之后被写入或编辑过，之前读到的内容（任何范围）都过时了。
            if (later.writeKey() != null && call.key().startsWith(later.writeKey() + "#")) {
                return true;
            }
            if (later.key() != null && later.key().equals(call.key())) return true;
        }
        return false;
    }

    private static String placeholder(Call call) {
        String reason = switch (call.name()) {
            case "read_file" -> "之后同一文件又被读取或修改过";
            case "execute_command" -> "之后同一条命令又执行过";
            default -> "之后又用相同参数调用过";
        };
        return MARKER + " 工具 " + call.name() + "（" + call.describe() + "）的这次输出已过时：" + reason
                + "，以后面的结果为准，这里已折叠以节省空间。";
    }

    private static Map<String, Call> callsById(List<LlmClient.Message> history) {
        Map<String, Call> calls = new HashMap<>();
        for (LlmClient.Message message : history) {
            if (message.toolCalls() == null) continue;
            for (LlmClient.ToolCall toolCall : message.toolCalls()) {
                if (toolCall == null || toolCall.id() == null || toolCall.function() == null) continue;
                calls.put(toolCall.id(), Call.of(toolCall.function().name(), toolCall.function().arguments()));
            }
        }
        return calls;
    }

    /**
     * @param key      判断“同一件事”的键；同键的后一次结果让前一次过时
     * @param writeKey 写入 / 编辑类调用影响的文件键，让之前对它的读取过时
     */
    record Call(String name, String key, String writeKey, String describe) {
        static Call of(String name, String argumentsJson) {
            String tool = name == null ? "" : name;
            JsonNode args = parse(argumentsJson);
            return switch (tool) {
                case "read_file" -> {
                    // 范围也算进键里：分段读不同的行不算过时，读完全相同的范围才算。
                    String path = normalizePath(args.path("path").asText(""));
                    String range = args.path("offset").asText("") + ":" + args.path("limit").asText("");
                    yield new Call(tool, path.isEmpty() ? null : "file:" + path + "#" + range, null, path);
                }
                case "write_file", "edit_file" -> {
                    String path = normalizePath(args.path("path").asText(""));
                    yield new Call(tool, null, path.isEmpty() ? null : "file:" + path, path);
                }
                case "execute_command" -> {
                    String command = args.path("command").asText("").trim();
                    yield new Call(tool, command.isEmpty() ? null : "cmd:" + command, null, abbreviate(command));
                }
                case "list_dir", "glob_files", "grep_code", "search_code" -> {
                    String canonical = args.isObject() ? args.toString() : String.valueOf(argumentsJson);
                    yield new Call(tool, "call:" + tool + ":" + canonical, null, abbreviate(canonical));
                }
                default -> new Call(tool, null, null, "");
            };
        }

        private static JsonNode parse(String json) {
            try {
                return MAPPER.readTree(json == null || json.isBlank() ? "{}" : json);
            } catch (Exception e) {
                return MAPPER.createObjectNode();
            }
        }

        private static String normalizePath(String path) {
            String p = path.trim().replace('\\', '/');
            while (p.startsWith("./")) p = p.substring(2);
            while (p.length() > 1 && p.endsWith("/")) p = p.substring(0, p.length() - 1);
            return p;
        }

        private static String abbreviate(String text) {
            return text.length() <= 80 ? text : text.substring(0, 77) + "...";
        }
    }

    public record Result(int foldedToolResults) {
        public static Result none() {
            return new Result(0);
        }

        public int count() {
            return foldedToolResults;
        }
    }
}
