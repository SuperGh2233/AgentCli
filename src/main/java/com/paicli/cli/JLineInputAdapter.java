package com.paicli.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.MaskingCallback;

import java.util.Objects;

/**
 * 仅用于已停止其他读取的安全交接点；不提供运行中异步唤醒，Main 尚未接入。
 * 注入既有 JLine，不创建 Reader/线程，不读取 raw 字节。所有请求暂用行输入。
 */
public final class JLineInputAdapter implements TerminalInputGateway.InputReader {
    private final LineReader reader;
    private final Thread owner = Thread.currentThread();
    private boolean reading;

    public JLineInputAdapter(LineReader reader) {
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public String read(TerminalInputGateway.Request request) {
        return readLine(Objects.requireNonNull(request, "request").prompt(), null);
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
