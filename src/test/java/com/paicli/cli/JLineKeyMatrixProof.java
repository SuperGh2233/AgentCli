package com.paicli.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Isolated macOS PTY keyboard regression. NOT wired to PaiCLI Main/Agent.
 * All JLine I/O happens on this main thread.
 */
public final class JLineKeyMatrixProof {
    public static void main(String[] args) throws Exception {
        try (Terminal terminal = TerminalBuilder.builder().system(true)
                .provider("exec").dumb(false).type("xterm-256color")
                .encoding(StandardCharsets.UTF_8).build()) {
            var reader = new WakeableLineReader(terminal, "paicli-key-matrix");
            reader.option(LineReader.Option.BRACKETED_PASTE, true);
            reader.setVariable(LineReader.AMBIGUOUS_BINDING, 80L);
            var tabCount = new AtomicInteger();
            var shiftTabCount = new AtomicInteger();
            var escCount = new AtomicInteger();
            var ctrlCCount = new AtomicInteger();
            boolean directSigint = Boolean.getBoolean("paicli.keymatrix.directSigint");
            reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
                reader.applyRestoredCursor();
                mark("INPUT_READY");
                return true;
            });
            reader.getWidgets().put("matrix-tab", () -> {
                tabCount.incrementAndGet();
                mark("TAB_WIDGET");
                return true;
            });
            reader.getWidgets().put("matrix-shift-tab", () -> {
                shiftTabCount.incrementAndGet();
                mark("SHIFT_TAB_WIDGET");
                return true;
            });
            reader.getWidgets().put("matrix-esc", () -> {
                escCount.incrementAndGet();
                mark("ESC_WIDGET");
                return true;
            });
            reader.getKeyMaps().get(LineReader.MAIN).bind(
                    new Reference("matrix-tab"), "\t");
            reader.getKeyMaps().get(LineReader.MAIN).bind(
                    new Reference("matrix-shift-tab"), "\u001b[Z");
            reader.getKeyMaps().get(LineReader.MAIN).bind(
                    new Reference("matrix-esc"), "\u001b");
            // Diagnostic only: JLine SEND_BREAK maps to "abort", which just
            // clears the line and beeps, rather than throwing UserInterruptException.
            // Simulate the JLine INT handler separately from native OS SIGINT.
            var originalCtrlC = reader.getKeyMaps().get(LineReader.MAIN)
                    .getBound("\u0003");
            mark("CTRL_C_ORIGINAL_BINDING_" + originalCtrlC);
            if (!directSigint) {
                reader.getWidgets().put("matrix-ctrl-c", () -> {
                    ctrlCCount.incrementAndGet();
                    mark("CTRL_C_BYTE_SEEN");
                    terminal.raise(Terminal.Signal.INT);
                    return true;
                });
                reader.getKeyMaps().get(LineReader.MAIN).bind(
                        new Reference("matrix-ctrl-c"), "\u0003");
            }

            mark("READY");

            // ESC [ D must be an arrow-left sequence, NOT standalone ESC.
            String arrow = reader.readLine("arrow> ");
            check("abXc".equals(arrow), "left-arrow editing changed input: " + arrow);
            check(escCount.get() == 0, "arrow sequence triggered standalone Esc");
            mark("ARROW_PASS");

            // Wake during an incomplete SS3 arrow: finish parsing before suspension.
            Path wakeRequest = Path.of(System.getProperty("paicli.keymatrix.wakeRequest"));
            var wakeWorker = new Thread(() -> {
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (!Files.exists(wakeRequest) && System.nanoTime() < deadline) {
                        Thread.sleep(5);
                    }
                    if (!Files.exists(wakeRequest)) return;
                    reader.requestWake();
                    Files.writeString(Path.of(wakeRequest + ".requested"), "WAKE_REQUESTED\n");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "key-matrix-wake-worker");
            wakeWorker.setDaemon(true);
            wakeWorker.start();
            try {
                reader.readLine("split-arrow> ");
                throw new AssertionError("split arrow did not wake readLine");
            } catch (WakeableLineReader.Wakeup expected) {
                var draft = reader.takeSuspendedDraft();
                check(draft != null && "abc".equals(draft.text()) && draft.cursor() == 2,
                        "split arrow wake lost draft or cursor: " + draft);
                check(escCount.get() == 0, "split arrow triggered standalone Esc");
                check(reader.takeSuspendedDraft() == null, "suspended draft was not cleared");
                mark("SPLIT_ARROW_WAKE_PASS");
                reader.restoreCursorOnNextInit(draft.cursor());
                String restored = reader.readLine("restored> ", null, draft.text());
                check("abXc".equals(restored), "split arrow draft restore changed: " + restored);
                mark("SPLIT_ARROW_RESTORE_PASS");
            } finally {
                wakeWorker.join(1000);
                check(!wakeWorker.isAlive(), "wake worker did not exit");
            }

            // Standalone ESC must not consume later user text.
            String esc = reader.readLine("esc> ");
            check("z".equals(esc) && escCount.get() == 1,
                    "standalone ESC did not stay separate: " + esc);
            mark("ESC_PASS");

            String tabs = reader.readLine("tabs> ");
            check("ABC".equals(tabs), "Tab/ShiftTab changed draft: " + tabs);
            check(tabCount.get() == 1 && shiftTabCount.get() == 1,
                    "Tab/ShiftTab widget counts differ");
            mark("TABS_PASS");

            // Bracketed paste should preserve UTF-8 and embedded newline;
            // the final Enter submits the buffer rather than each pasted line.
            String paste = reader.readLine("paste> ");
            check("中\n文".equals(paste),
                    "bracketed multiline UTF-8 paste changed: " +
                            (paste == null ? "null" : paste.replace("\n", "<NL>")));
            mark("PASTE_PASS");

            if (Boolean.getBoolean("paicli.keymatrix.deferCtrlC")) {
                // Only use this option for isolated Ctrl+D / cleanup diagnosis.
                // It is deliberately NOT a successful Ctrl+C assertion.
                mark("CTRL_C_DEFERRED");
            } else {
                try {
                    reader.readLine("interrupt> ");
                    throw new AssertionError("Ctrl+C did not interrupt readLine");
                } catch (UserInterruptException expected) {
                    if (directSigint) {
                        check(ctrlCCount.get() == 0,
                                "OS SIGINT unexpectedly passed through byte widget");
                        mark("INTERRUPT_DIRECT_OS_SIGNAL_PASS");
                    } else {
                        check(ctrlCCount.get() == 1,
                                "Ctrl+C was not delivered to JLine widget exactly once");
                        mark("INTERRUPT_SIGNAL_PROXY_PASS");
                    }
                }
            }

            try {
                reader.readLine("eof> ");
                throw new AssertionError("Ctrl+D did not report EOF");
            } catch (EndOfFileException expected) {
                mark("EOF_PASS");
            }
            mark("INPUT_COMPLETE");
        }

        String ack = System.getProperty("paicli.keymatrix.exitAck");
        if (ack != null) {
            Files.writeString(Path.of(ack + ".ready"), "TERMINAL_CLOSED\n");
            mark("TERMINAL_CLOSED");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(Path.of(ack)) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            check(Files.exists(Path.of(ack)), "parent never acknowledged termios");
        }
        mark("PASS");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void mark(String marker) {
        System.out.println("\r\nKEY:" + marker);
        System.out.flush();
    }
}
