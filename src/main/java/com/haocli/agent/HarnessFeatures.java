package com.haocli.agent;

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
 * <p>名字：{@code context}（上下文处理策略，一次关掉下面四项）、{@code context.offload}（大结果落盘）、
 * {@code context.clearing}（清理旧工具结果）、{@code context.summary}（摘要压缩）、
 * {@code context.runaway}（重复调用提醒）、{@code lsp}（LSP 诊断注入）。</p>
 *
 * <p>来源：系统属性 {@value #PROPERTY}（{@code -p --harness-disable} 会设置它），其次环境变量 {@value #ENV}。
 * 写错名字直接报错，不悄悄当成全开，免得评测记录和实际配置对不上。</p>
 */
public record HarnessFeatures(boolean contextOffload, boolean contextClearing, boolean contextSummary,
                              boolean runawayReminder, boolean lspDiagnostics) {

    public static final String PROPERTY = "haocli.harness.disable";
    public static final String ENV = "HAOCLI_HARNESS_DISABLE";
    public static final HarnessFeatures ALL_ON = new HarnessFeatures(true, true, true, true, true);

    static final String OFFLOAD = "context.offload";
    static final String CLEARING = "context.clearing";
    static final String SUMMARY = "context.summary";
    static final String RUNAWAY = "context.runaway";
    static final String LSP = "lsp";

    private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();
    static {
        GROUPS.put("context", List.of(OFFLOAD, CLEARING, SUMMARY, RUNAWAY));
        for (String name : List.of(OFFLOAD, CLEARING, SUMMARY, RUNAWAY, LSP)) {
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
        return new HarnessFeatures(!off.contains(OFFLOAD), !off.contains(CLEARING), !off.contains(SUMMARY),
                !off.contains(RUNAWAY), !off.contains(LSP));
    }

    public static HarnessFeatures fromSystem() {
        String value = System.getProperty(PROPERTY);
        if (value == null || value.isBlank()) {
            value = System.getenv(ENV);
        }
        return parse(value);
    }

    /** 关掉的模块（细分名字），按固定顺序；全开时是空列表。 */
    public List<String> disabledNames() {
        List<String> names = new ArrayList<>();
        if (!contextOffload) names.add(OFFLOAD);
        if (!contextClearing) names.add(CLEARING);
        if (!contextSummary) names.add(SUMMARY);
        if (!runawayReminder) names.add(RUNAWAY);
        if (!lspDiagnostics) names.add(LSP);
        return names;
    }
}
