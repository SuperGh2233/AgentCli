package com.haocli.prompt;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 轻量的任务相关性：从任务文字里取关键词（英文/数字按词，中文按相邻两字），数候选文字里命中几个。
 * 只用来给 Skill 索引和 HAO.md 目录排序，不做语义理解；没有关键词时所有候选都是 0 分，排序退回原顺序。
 */
public final class TaskRelevance {

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "this", "that", "from", "into", "please", "help",
            "帮我", "一下", "这个", "那个", "我们", "你们", "可以", "需要", "如何", "怎么", "什么");

    private TaskRelevance() {
    }

    public static Set<String> keywords(String text) {
        Set<String> keywords = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return keywords;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder word = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c) && c < 0x2E80) {
                word.append(c);
                flushCjk(cjk, keywords);
            } else if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk.append(c);
                flushWord(word, keywords);
            } else {
                flushWord(word, keywords);
                flushCjk(cjk, keywords);
            }
        }
        return keywords;
    }

    /** candidate 里命中了几个关键词。 */
    public static int score(String candidate, Set<String> keywords) {
        if (candidate == null || candidate.isBlank() || keywords.isEmpty()) {
            return 0;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        int hits = 0;
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                hits++;
            }
        }
        return hits;
    }

    private static void flushWord(StringBuilder word, Set<String> out) {
        if (word.length() >= 3 && !STOP_WORDS.contains(word.toString())) {
            out.add(word.toString());
        }
        word.setLength(0);
    }

    private static void flushCjk(StringBuilder cjk, Set<String> out) {
        for (int i = 0; i + 2 <= cjk.length(); i++) {
            String bigram = cjk.substring(i, i + 2);
            if (!STOP_WORDS.contains(bigram)) {
                out.add(bigram);
            }
        }
        cjk.setLength(0);
    }
}
