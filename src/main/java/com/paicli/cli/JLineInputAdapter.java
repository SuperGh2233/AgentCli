package com.paicli.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.MaskingCallback;
import org.jline.reader.UserInterruptException;

import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 用于唯一 UI 所有者的安全交接点；注入既有 JLine，不创建 Reader/线程，不读取 raw 字节。
 * 审批请求暂用行输入；内部唤醒保留草稿，Ctrl+C 单独作为取消处理。
 */
public final class JLineInputAdapter implements TerminalInputGateway.InputReader {
    private final LineReader reader;
    private final BiConsumer<TerminalInputGateway.Request, String> afterRead;
    private final Thread owner = Thread.currentThread();
    private boolean reading;
    private WakeableLineReader.Draft nextDraft;

    public JLineInputAdapter(LineReader reader) {
        this(reader, null);
    }

    /** afterRead 在 UI Reader 已消费输入、但 Gateway 尚未唤醒 worker 时同步执行。 */
    public JLineInputAdapter(LineReader reader,
                             BiConsumer<TerminalInputGateway.Request, String> afterRead) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.afterRead = afterRead;
    }

    @Override
    public String read(TerminalInputGateway.Request request) {
        Objects.requireNonNull(request, "request");
        WakeableLineReader.Draft draft = nextDraft;
        nextDraft = null;
        try {
            if (draft != null && reader instanceof WakeableLineReader wakeable) {
                wakeable.restoreCursorOnNextInit(draft.cursor());
            }
            String line = readLine(request.prompt(), draft == null ? null : draft.text());
            if (afterRead != null) {
                afterRead.accept(request, line);
            }
            return line;
        } catch (WakeableLineReader.Wakeup e) {
            // 草稿留在读取器中，UI 收到 RETRY 后仍可取回文本和光标。
            throw new TerminalInputGateway.InputRetryException();
        } catch (UserInterruptException e) {
            throw new InputCancelledException(e.getPartialLine());
        }
    }

    /** 只允许 UI 所有者在一次内部唤醒后设置下一次审批读取的草稿。 */
    public void restoreNextRead(WakeableLineReader.Draft draft) {
        if (Thread.currentThread() != owner || reading) {
            throw new IllegalStateException("审批草稿只能由 UI 所有者在两次读取之间恢复");
        }
        nextDraft = Objects.requireNonNull(draft, "draft");
    }

    public static final class InputCancelledException extends RuntimeException {
        private final String partialLine;

        public InputCancelledException(String partialLine) {
            super("终端输入被用户中断", null, false, false);
            this.partialLine = partialLine == null ? "" : partialLine;
        }

        public String partialLine() { return partialLine; }
    }

    /** seed 只能由同一 UI 所有者在前一次读取结束后提供；不能拿它冒充异步草稿恢复。 */
    public String readLine(String prompt, String seed) {
        if (Thread.currentThread() != owner || reading) {
            throw new IllegalStateException("JLine 只能由同一个 UI 所有者顺序读取，不能重入");
        }
        reading = true;
        try {
            return reader.readLine(prompt, null, (MaskingCallback) null, seed);
        } catch (EndOfFileException e) {
            return null;
        } finally {
            // UserInterruptException 保持取消语义，交给调用方退出或让输入桥按读取失败拒绝审批。
            reading = false;
        }
    }
}
