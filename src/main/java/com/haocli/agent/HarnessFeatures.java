package com.haocli.agent;

import com.haocli.memory.AutoCompactionManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 哪些 harness 模块开着（消融实验用）。默认全开；关掉的模块不执行、也不往对话里加任何东西。
 *
 * <p>上下文处理策略 {@code context} = 五层压缩 + 重复调用提醒，可一次关掉，也可按层关：
 * {@code context.offload}（1 大结果落盘）、{@code context.trim}（2 历史裁剪）、{@code context.fold}（3 局部折叠）、
 * {@code context.clearing}（4 清理旧工具结果）、{@code context.summary}（5 摘要压缩）、{@code context.runaway}
 * （重复调用提醒）。另有 {@code lsp}（LSP 诊断注入）。上下文超长时的紧急压缩只使用开着的层。</p>
 *
 * <p>来源：系统属性 {@value #PROPERTY}（{@code -p --harness-disable} 会设置它），其次环境变量 {@value #ENV}。
 * 写错名字直接报错，不悄悄当成全开，免得评测记录和实际配置对不上。</p>
 */
public record HarnessFeatures(boolean contextOffload, boolean contextTrim, boolean contextFold,
                              boolean contextClearing, boolean contextSummary,
                              boolean runawayReminder, boolean lspDiagnostics) {

    public static final String PROPERTY = "haocli.harness.disable";
    public static final String ENV = "HAOCLI_HARNESS_DISABLE";
    public static final HarnessFeatures ALL_ON = new HarnessFeatures(true, true, true, true, true, true, true);

    static final String OFFLOAD = "context.offload";
    static final String TRIM = "context.trim";
    static final String FOLD = "context.fold";
    static final String CLEARING = "context.clearing";
    static final String SUMMARY = "context.summary";
    static final String RUNAWAY = "context.runaway";
    static final String LSP = "lsp";

    private static final List<String> ALL = List.of(OFFLOAD, TRIM, FOLD, CLEARING, SUMMARY, RUNAWAY, LSP);
    private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("context", List.of(OFFLOAD, TRIM, FOLD, CLEARING, SUMMARY, RUNAWAY));
        for (String name : ALL) {
            GROUPS.put(name, List.of(name));
        }
    }

    /** 逗号或空格分隔的模块名；空字符串或 {@code none} 表示全开。 */
    public static HarnessFeatures parse(String disableList) {
        Set<String> off = new LinkedHashSet<>();
        if (disableList != null) {
            for (String raw : disableList.split("[,\\s]+")) {
                String name = raw.trim().toLowerCase(Locale.ROOT);
                if (name.isEmpty() || name.equals("none")) {
                    continue;
                }
                List<String> members = GROUPS.get(name);
                if (members == null) {
                    throw new IllegalArgumentException("不认识的 harness 模块: " + raw
                            + "（可用: " + String.join("、", GROUPS.keySet()) + "）");
                }
                off.addAll(members);
            }
        }
        return new HarnessFeatures(!off.contains(OFFLOAD), !off.contains(TRIM), !off.contains(FOLD),
                !off.contains(CLEARING), !off.contains(SUMMARY), !off.contains(RUNAWAY), !off.contains(LSP));
    }

    public static HarnessFeatures fromSystem() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            value = System.getenv(ENV);
        }
        return parse(value);
    }

    /** 第 2–5 层（在对话上做的压缩；第 1 层落盘在工具结果产生时由 ToolRegistry 做）。 */
    public AutoCompactionManager.Layers compactionLayers() {
        return new AutoCompactionManager.Layers(contextTrim, contextFold, contextClearing, contextSummary);
    }

    /** 关掉的模块（细分名字），按固定顺序；全开时是空列表。 */
    public List<String> disabledNames() {
        boolean[] on = {contextOffload, contextTrim, contextFold, contextClearing, contextSummary,
                runawayReminder, lspDiagnostics};
        List<String> names = new ArrayList<>();
        for (int i = 0; i < ALL.size(); i++) {
            if (!on[i]) names.add(ALL.get(i));
        }
        return names;
    }
}
