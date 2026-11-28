package com.haocli.memory;

import com.haocli.llm.LlmClient;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话记忆摘要的并发和一致性（确定性：后台任务由测试手动执行，不依赖线程调度）：
 * 过期结果不覆盖新状态、增量更新带上旧摘要、不同对话互不影响、摘要失败时回退。
 */
class SessionMemoryConcurrencyTest {

    /** 把后台任务攒起来，由测试决定什么时候、按什么顺序执行。 */
    private static final class ManualExecutor implements Executor {
        final Deque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }
    }

    private static final class Stub extends SessionMemoryCompactor {
        String next = "SUMMARY";
        boolean fail;
        final List<String> previousSummaries = new ArrayList<>();

        Stub(Executor executor) {
            super(null, true, new Config(0.50, 1, 1, 10_000), executor);
        }

        @Override
        protected String summarizeIncrement(String previousSummary, List<LlmClient.Message> messages) throws IOException {
            previousSummaries.add(previousSummary);
            if (fail) throw new IOException("summary model down");
            return next;
        }
    }

    private static List<LlmClient.Message> history(String tag, int rounds, int chars) {
        List<LlmClient.Message> history = new ArrayList<>();
        history.add(LlmClient.Message.system("system"));
        appendRounds(history, tag, 0, rounds, chars);
        return history;
    }

    private static void appendRounds(List<LlmClient.Message> history, String tag, int from, int rounds, int chars) {
        for (int i = from; i < from + rounds; i++) {
            history.add(LlmClient.Message.user(tag + "-Q" + i + " " + "u".repeat(chars)));
            history.add(LlmClient.Message.assistant(tag + "-A" + i + " " + "a".repeat(chars)));
        }
    }

    private static int triggerJustAbove(List<LlmClient.Message> history) {
        return TokenBudget.estimateMessagesTokens(history) + 200;
    }

    private static void grow(List<LlmClient.Message> history) {
        history.add(LlmClient.Message.user("newest question " + "x".repeat(1_000)));
        history.add(LlmClient.Message.assistant("newest answer"));
    }

    private static String joined(List<LlmClient.Message> history) {
        return String.join("\n", history.stream().map(m -> m.content() == null ? "" : m.content()).toList());
    }

    @Test
    void resultFinishingAfterClearIsDiscarded() {
        ManualExecutor executor = new ManualExecutor();
        Stub compactor = new Stub(executor);
        List<LlmClient.Message> history = history("H", 6, 400);
        int trigger = triggerJustAbove(history);

        assertTrue(compactor.prepareIfNeeded(history, trigger));
        compactor.clear(history);                 // 比如手动 /compact 或对话被别的层改写
        compactor.next = "STALE";
        executor.tasks.poll().run();              // 旧任务这时才跑完

        grow(history);
        List<LlmClient.Message> before = List.copyOf(history);
        assertFalse(compactor.compactIfReady(history, trigger), "重置之后完成的旧结果不能被用上");
        assertEquals(before, history, "没有可用摘要时不改对话（交给完整压缩）");
    }

    @Test
    void newerSummaryWinsEvenWhenTheOlderOneFinishesLast() {
        ManualExecutor executor = new ManualExecutor();
        Stub compactor = new Stub(executor);
        List<LlmClient.Message> history = history("H", 6, 400);
        int trigger = triggerJustAbove(history);

        assertTrue(compactor.prepareIfNeeded(history, trigger));   // 旧任务
        Runnable older = executor.tasks.poll();
        compactor.clear(history);
        assertTrue(compactor.prepareIfNeeded(history, trigger));   // 新任务
        Runnable newer = executor.tasks.poll();

        compactor.next = "NEW-SUMMARY";
        newer.run();
        compactor.next = "OLD-SUMMARY";
        older.run();                                               // 乱序：旧的最后完成

        grow(history);
        assertTrue(compactor.compactIfReady(history, trigger));
        String text = joined(history);
        assertTrue(text.contains("NEW-SUMMARY"), text.substring(0, Math.min(200, text.length())));
        assertFalse(text.contains("OLD-SUMMARY"), "过期摘要不能覆盖新状态");
    }

    @Test
    void incrementalUpdateBuildsOnThePreviousSummary() {
        Stub compactor = new Stub(Runnable::run);
        List<LlmClient.Message> history = history("H", 6, 400);

        compactor.next = "FIRST";
        assertTrue(compactor.prepareIfNeeded(history, triggerJustAbove(history)));
        assertNull(compactor.previousSummaries.get(0), "第一次是全量摘要");

        appendRounds(history, "H", 6, 30, 2_000);
        compactor.next = "SECOND";
        assertTrue(compactor.prepareIfNeeded(history, triggerJustAbove(history)));
        assertEquals("FIRST", compactor.previousSummaries.get(1), "第二次只总结新增部分，并在旧摘要上更新");
    }

    @Test
    void separateConversationsKeepSeparateSummaries() {
        Stub compactor = new Stub(Runnable::run);
        List<LlmClient.Message> a = history("A", 6, 400);
        List<LlmClient.Message> b = history("B", 6, 400);
        int triggerA = triggerJustAbove(a);
        int triggerB = triggerJustAbove(b);

        compactor.next = "SUMMARY-OF-A";
        assertTrue(compactor.prepareIfNeeded(a, triggerA));
        compactor.next = "SUMMARY-OF-B";
        assertTrue(compactor.prepareIfNeeded(b, triggerB));

        grow(a);
        grow(b);
        assertTrue(compactor.compactIfReady(a, triggerA));
        assertTrue(compactor.compactIfReady(b, triggerB));
        assertTrue(joined(a).contains("SUMMARY-OF-A") && !joined(a).contains("SUMMARY-OF-B"));
        assertTrue(joined(b).contains("SUMMARY-OF-B") && !joined(b).contains("SUMMARY-OF-A"));
    }

    @Test
    void summaryFailureFallsBackWithoutTouchingHistory() {
        Stub compactor = new Stub(Runnable::run);
        compactor.fail = true;
        List<LlmClient.Message> history = history("H", 6, 400);
        int trigger = triggerJustAbove(history);

        assertTrue(compactor.prepareIfNeeded(history, trigger), "任务照常安排");
        grow(history);
        List<LlmClient.Message> before = List.copyOf(history);
        assertFalse(compactor.compactIfReady(history, trigger), "摘要失败：快速路径不可用，交给完整压缩");
        assertEquals(before, history);
    }
}
