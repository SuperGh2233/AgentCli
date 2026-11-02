package com.paicli.cli;

import org.jline.keymap.KeyMap;
import org.jline.reader.Buffer;
import org.jline.reader.LineReader;
import org.jline.reader.Widget;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单一终端读取者使用的 JLine 唤醒组件。worker 只能 requestWake()；
 * 唤醒、缓冲快照及恢复均由创建本对象的 UI 线程执行。
 *
 * 仅在 paicli.steering.enabled=true 且交互式 inline ReAct 路径使用；其它模式继续使用原读取路径。
 */
public final class WakeableLineReader extends LineReaderImpl {
    public record Draft(String text, int cursor) {
        public Draft {
            Objects.requireNonNull(text, "text");
            if (cursor < 0 || cursor > text.length()) {
                throw new IllegalArgumentException("草稿光标越界");
            }
        }
    }

    /** 只代表内部 UI 事件暂停读行，不代表 Ctrl+C、EOF 或模型取消。 */
    public static final class Wakeup extends RuntimeException {
        private Wakeup() {
            super(null, null, false, false);
        }
    }

    private static final long IDLE_PEEK_MILLIS = 20;
    private final Thread owner = Thread.currentThread();
    private final AtomicBoolean wakeRequested = new AtomicBoolean();
    private Draft suspended;
    private int cursorOnNextInit = -1;

    public WakeableLineReader(Terminal terminal, String appName) throws IOException {
        super(Objects.requireNonNull(terminal, "terminal"),
                Objects.requireNonNull(appName, "appName"));
        Widget previousInit = getWidgets().get(LineReader.CALLBACK_INIT);
        getWidgets().put(LineReader.CALLBACK_INIT, () -> {
            boolean accepted = previousInit == null || previousInit.apply();
            applyRestoredCursor();
            return accepted;
        });
    }

    /** worker 线程安全；严禁在这里访问 JLine/Terminal 的任何方法。 */
    public void requestWake() {
        wakeRequested.set(true);
    }

    /** 清理由 UI 已处理的唤醒标志，避免完成事件泄漏到下一次空闲提示。 */
    public boolean consumeWakeRequest() {
        requireUiOwner();
        return wakeRequested.getAndSet(false);
    }

    /** 调用方捕获 Wakeup 后，由 UI 线程取出并清除这次保留的草稿。 */
    public Draft takeSuspendedDraft() {
        requireUiOwner();
        Draft draft = suspended;
        suspended = null;
        return draft;
    }

    /** 在重新 readLine 前设置，并在 JLine 的 CALLBACK_INIT 内调用 applyRestoredCursor。 */
    public void restoreCursorOnNextInit(int position) {
        requireUiOwner();
        if (position < 0) throw new IllegalArgumentException("草稿光标越界");
        cursorOnNextInit = position;
    }

    /** 必须由同一个 UI 线程在 JLine CALLBACK_INIT 调用，不得由 worker 触发。 */
    public void applyRestoredCursor() {
        requireUiOwner();
        if (cursorOnNextInit >= 0) {
            getBuffer().cursor(Math.min(cursorOnNextInit, getBuffer().length()));
            cursorOnNextInit = -1;
        }
    }

    private void requireUiOwner() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("JLine 输入和草稿只能由创建它的 UI 线程操作");
        }
    }

    @Override
    protected <T> T doReadBinding(KeyMap<T> keys, KeyMap<T> local) {
        requireUiOwner();
        while (true) {
            // 先用同一个 BindingReader 做短暂 peek。若输入空闲，worker 的
            // wake 可以立即交付；若已有任何输入字节，则必须先让 JLine
            // 完整解码该键位（包括 ESC/CSI/SS3 多字节序列）。
            boolean wasLocked = lock.isHeldByCurrentThread();
            if (wasLocked) lock.unlock();
            try {
                int next = bindingReader.peekCharacter(IDLE_PEEK_MILLIS);
                if (next == NonBlockingReader.READ_EXPIRED) {
                    if (wakeRequested.getAndSet(false)) {
                        Buffer buffer = getBuffer().copy();
                        suspended = new Draft(buffer.toString(), buffer.cursor());
                        throw new Wakeup();
                    }
                    continue;
                }

                // 不要在这里因 wakeRequested 提前退出：peek 已确认有真实输入，
                // readBinding 必须消费完整的键序列。worker 的 wake 标志会保留，
                // 当前 binding 由 UI 线程正常处理；下一次空闲 peek 再保存草稿。
                // block=true 与 JLine 正常读键语义一致；上面的限时 peek 保证
                // 没有输入时仍能周期性检查 wake，不需要第二个终端 reader。
                T binding = bindingReader.readBinding(keys, local, true);
                if (binding != null || next == NonBlockingReader.EOF) return binding;
            } finally {
                if (wasLocked) lock.lock();
            }
        }
    }
}
