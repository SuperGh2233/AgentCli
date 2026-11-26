package com.paicli.cli;

import com.paicli.runtime.input.TurnInputManager;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Phase2B-3 isolated feasibility proof. It is NOT wired to Main or Agent.
 * Only the constructing UI thread touches JLine or consumes terminal input.
 * Workers only publish an internal wake flag and wait for the gateway result.
 */
public final class JLineWakeupProof {
    private static final String DRAFT = "草稿中文🚀";

    // WakeableLineReader is now a standalone, still-unwired production component.
    // This proof drives that exact code in a real PTY.

    public static void main(String[] args) throws Exception {
        String shutdownLogPrefix = System.getProperty("paicli.proof.exitAck");
        if (shutdownLogPrefix != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                // A shutdown hook fires only after JVM shutdown has begun.
                // Observe JLine's other hooks without changing their lifecycle.
                try {
                    // Allow other JVM shutdown hooks to start, otherwise a
                    // snapshot can miss the hook we are trying to diagnose.
                    Thread.sleep(850);
                    StringBuilder report = new StringBuilder(
                            "shutdownHookReached=true sampledAfterMs=850\n");
                    for (var entry : Thread.getAllStackTraces().entrySet()) {
                        Thread t = entry.getKey();
                        if (!t.isAlive()) continue;
                        report.append(t.getName()).append(" [")
                                .append(t.getState()).append("] daemon=")
                                .append(t.isDaemon()).append("\n");
                        StackTraceElement[] frames = entry.getValue();
                        for (int i = 0; i < Math.min(frames.length, 12); i++) {
                            report.append("  at ").append(frames[i]).append("\n");
                        }
                    }
                    Files.writeString(Path.of(shutdownLogPrefix + ".shutdown-threads"),
                            report.toString());
                } catch (Exception e) {
                    // Never interfere with JVM shutdown for diagnostics.
                }
            }, "proof-shutdown-observer"));
        }
        try (Terminal terminal = TerminalBuilder.builder().system(true).provider("exec")
                .dumb(false).type("xterm-256color").encoding(StandardCharsets.UTF_8).build()) {
            var original = terminal.getAttributes();
            var reader = new WakeableLineReader(terminal, "paicli-phase2b3-proof");
            reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
                reader.applyRestoredCursor();
                mark("INPUT_READY");
                return true;
            });
            reader.option(LineReader.Option.BRACKETED_PASTE, true);
            reader.setVariable(LineReader.AMBIGUOUS_BINDING, 80L);

            var manager = new TurnInputManager("wakeup-proof", true, TurnInputManager.Limits.defaults());
            manager.startTurn("turn");
            manager.markRunning("turn");
            var coordinator = new InputCoordinator("wakeup-proof", manager, true);
            coordinator.beginRun("turn");
            var adapter = new JLineInputAdapter(reader);
            var checkpoint = new CountDownLatch(1);
            bind(reader, "proof-checkpoint", "\035", () -> {
                check(DRAFT.equals(reader.getBuffer().toString()), "draft changed before wake");
                reader.getBuffer().cursor(2); // cursor in the middle of the Chinese draft
                checkpoint.countDown();
                mark("DRAFT_READY");
            });

            var wakePublished = new CountDownLatch(1);
            var reply = new AtomicReference<String>();
            var failure = new AtomicReference<Throwable>();
            try (var gateway = new TerminalInputGateway(coordinator, request -> {
                mark("APPROVAL_UI");
                return adapter.read(request);
            }, reader::requestWake)) {
                var worker = new Thread(() -> {
                    try {
                        check(checkpoint.await(3, TimeUnit.SECONDS), "no draft checkpoint");
                        try (var session = gateway.openInteraction(InputCoordinator.Mode.APPROVAL_INPUT)) {
                            mark("APPROVAL_PENDING");
                            mark("WAKE_FLAG_SET");
                            wakePublished.countDown();
                            reply.set(session.read(TerminalInputGateway.Kind.APPROVAL_CHOICE,
                                    "approve> ", Duration.ofSeconds(5)));
                        }
                    } catch (Throwable e) {
                        failure.set(e);
                        mark("WORKER_FAILED");
                    }
                }, "proof-worker-without-terminal-reader");
                worker.setDaemon(true);
                worker.start();

                mark("READY");
                try {
                    adapter.readLine("> ", null);
                    throw new AssertionError("the command line was not awakened");
                } catch (WakeableLineReader.Wakeup expected) {
                    WakeableLineReader.Draft suspended = reader.takeSuspendedDraft();
                    check(suspended != null && DRAFT.equals(suspended.text()),
                            "wake lost draft");
                    check(suspended.cursor() == 2, "wake lost cursor");
                    check(manager.snapshot().pendingCount() == 0, "approval became Steer");
                    check(wakePublished.await(3, TimeUnit.SECONDS), "wake marker not published");
                    mark("WAKE_WITHOUT_ENTER");
                    // Open may wake before the first subprompt is published.
                    // Wait without terminal input and remain in approval mode.
                    TerminalInputGateway.Dispatch delivery;
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    do {
                        gateway.awaitInteraction(Duration.ofMillis(100));
                        delivery = gateway.serviceNext();
                        if (delivery.status() == TerminalInputGateway.DispatchStatus.RETRY) {
                            var draft = reader.takeSuspendedDraft();
                            check(draft != null && draft.text().isEmpty(),
                                    "approval draft needs explicit review");
                        }
                    } while ((delivery.status() == TerminalInputGateway.DispatchStatus.IDLE
                            || delivery.status() == TerminalInputGateway.DispatchStatus.RETRY)
                            && System.nanoTime() < until);
                    check(delivery.status() == TerminalInputGateway.DispatchStatus.DELIVERED,
                            "approval not delivered");
                    worker.join(2000);
                    check(!worker.isAlive() && failure.get() == null && "y".equals(reply.get()),
                            "worker did not receive its own approval");
                    check(manager.snapshot().pendingCount() == 0, "approval leaked to Steer");
                    String command = readRestored(adapter, reader, "> ", suspended);
                    check("草稿续中文🚀".equals(command), "draft or cursor not restored: " + command);
                    mark("DRAFT_RESTORED");
                } finally {
                    gateway.close();
                    worker.join(1000);
                }
            }
            // A second, completely empty read tests the first-character wait:
            // worker publishes a wake event AFTER UI has entered readLine;
            // the Python PTY driver sends no input for this entire stage.
            var emptyReady = new CountDownLatch(1);
            var emptyFailure = new AtomicReference<Throwable>();
            reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
                reader.applyRestoredCursor();
                mark("EMPTY_INPUT_READY");
                emptyReady.countDown();
                return true;
            });
            var emptyObserved = new CountDownLatch(1);
            var emptyPublished = new CountDownLatch(1);
            try (var emptyGateway = new TerminalInputGateway(coordinator, adapter, reader::requestWake)) {
                var emptyWorker = new Thread(() -> {
                    try {
                        check(emptyReady.await(3, TimeUnit.SECONDS), "empty prompt not initialized");
                        Thread.sleep(150); // ensure the UI entered its idle input wait
                        try (var session = emptyGateway.openInteraction(InputCoordinator.Mode.APPROVAL_INPUT)) {
                            mark("EMPTY_WAKE_FLAG_SET");
                            emptyPublished.countDown();
                            check(emptyObserved.await(3, TimeUnit.SECONDS), "empty wake not observed");
                        }
                    } catch (Throwable e) {
                        emptyFailure.set(e);
                        mark("EMPTY_WORKER_FAILED");
                    }
                }, "proof-empty-draft-wake-worker");
                emptyWorker.setDaemon(true);
                emptyWorker.start();
                try {
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    boolean observed = false;
                    while (!observed && System.nanoTime() < until) {
                        try {
                            adapter.readLine("empty> ", null);
                            throw new AssertionError("empty command line was not awakened");
                        } catch (WakeableLineReader.Wakeup expected) {
                            WakeableLineReader.Draft empty = reader.takeSuspendedDraft();
                            check(empty != null && empty.text().isEmpty() && empty.cursor() == 0,
                                    "empty wake changed the draft or cursor");
                            // Closing the previous gateway may leave a coalesced
                            // lifecycle wake. It is not this open event.
                            if (emptyGateway.awaitInteraction(Duration.ZERO).state()
                                    == TerminalInputGateway.InteractionState.NONE) continue;
                            check(emptyPublished.await(3, TimeUnit.SECONDS), "empty wake marker missing");
                            check(emptyGateway.awaitInteraction(Duration.ZERO).state()
                                            == TerminalInputGateway.InteractionState.BETWEEN_SUBPROMPTS,
                                    "open interaction disappeared before first subprompt");
                            mark("EMPTY_WAKE_WITHOUT_ENTER");
                            observed = true;
                        }
                    }
                    check(observed, "empty interaction wake not observed");
                } finally {
                    emptyObserved.countDown();
                    emptyWorker.join(1000);
                }
                check(!emptyWorker.isAlive() && emptyFailure.get() == null,
                        "empty wake worker failed");
            }

            // No new driver input or protocol step: approval must leave a
            // completely idle read through the timeout lifecycle callback.
            var timeoutOpened = new CountDownLatch(1);
            var timeoutFailure = new AtomicReference<Throwable>();
            try (var timeoutGateway = new TerminalInputGateway(coordinator, adapter, reader::requestWake)) {
                var timeoutWorker = new Thread(() -> {
                    try (var session = timeoutGateway.openInteraction(InputCoordinator.Mode.APPROVAL_INPUT)) {
                        timeoutOpened.countDown();
                        try {
                            session.read(TerminalInputGateway.Kind.APPROVAL_CHOICE,
                                    "timeout> ", Duration.ofMillis(350));
                            throw new AssertionError("timeout unexpectedly received approval");
                        } catch (TerminalInputGateway.InputUnavailableException expected) {
                            check(expected.failure() == TerminalInputGateway.Failure.TIMEOUT,
                                    "wrong timeout failure");
                        }
                    } catch (Throwable e) { timeoutFailure.set(e); }
                }, "proof-timeout-worker");
                timeoutWorker.setDaemon(true);
                timeoutWorker.start();
                try {
                    check(timeoutOpened.await(2, TimeUnit.SECONDS), "timeout interaction not opened");
                    check(timeoutGateway.awaitInteraction(Duration.ofSeconds(1)).state()
                                    == TerminalInputGateway.InteractionState.PENDING,
                            "timeout subprompt not published");
                    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    int wakes = 0;
                    while (timeoutGateway.awaitInteraction(Duration.ZERO).state()
                            != TerminalInputGateway.InteractionState.NONE && System.nanoTime() < until) {
                        var dispatch = timeoutGateway.serviceNext();
                        check(dispatch.status() == TerminalInputGateway.DispatchStatus.RETRY,
                                "timeout did not wake approval read");
                        var draft = reader.takeSuspendedDraft();
                        check(draft != null && draft.text().isEmpty(), "timeout lost empty draft");
                        wakes++;
                    }
                    timeoutWorker.join(1000);
                    check(wakes > 0 && !timeoutWorker.isAlive() && timeoutFailure.get() == null,
                            "timeout worker did not finish");
                    mark("TIMEOUT_WAKE_WITHOUT_ENTER");
                } finally {
                    timeoutGateway.close();
                    timeoutWorker.join(1000);
                }
            }

            var restored = terminal.getAttributes();
            check(original.getInputFlags().equals(restored.getInputFlags())
                    && original.getOutputFlags().equals(restored.getOutputFlags())
                    && original.getControlFlags().equals(restored.getControlFlags())
                    && original.getLocalFlags().equals(restored.getLocalFlags())
                    && original.getControlChars().equals(restored.getControlChars()),
                    "JLine attributes were not restored");
            // The system-level PTY comparison must happen only AFTER
            // terminal.close() restores the device. The previous proof sent
            // PASS while still inside this try-with-resources block.
            mark("JLINE_INPUT_COMPLETE");
        }
        // Keep the JVM process alive so the parent can inspect the same PTY
        // before macOS revokes it on controlling-session exit (ENOTTY).
        String ack = System.getProperty("paicli.proof.exitAck");
        if (ack != null) {
            // File handshake is independent of terminal output, which may be
            // unavailable once JLine closes the terminal writer.
            Files.writeString(Path.of(ack + ".ready"), "TERMINAL_CLOSED\n");
        }
        mark("TERMINAL_CLOSED");
        if (ack != null) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!Files.exists(Path.of(ack)) && System.nanoTime() < deadline) Thread.sleep(10);
            check(Files.exists(Path.of(ack)), "parent did not acknowledge PTY attributes");
        }
        mark("PASS");
        if (ack != null) {
            // Capture a bounded snapshot immediately before main returns.
            // Do NOT call System.exit: that would hide a non-daemon thread leak.
            StringBuilder threads = new StringBuilder();
            var live = Thread.getAllStackTraces();
            live.entrySet().stream()
                    .filter(entry -> entry.getKey().isAlive()
                            && !entry.getKey().isDaemon())
                    .sorted(java.util.Comparator.comparing(entry -> entry.getKey().getName()))
                    .forEach(entry -> {
                        Thread t = entry.getKey();
                        threads.append(t.getName()).append(" [")
                                .append(t.getState()).append("] id=")
                                .append(t.getId()).append("\n");
                        StackTraceElement[] frames = entry.getValue();
                        for (int i = 0; i < Math.min(8, frames.length); i++) {
                            threads.append("  at ").append(frames[i]).append("\n");
                        }
                    });
            Files.writeString(Path.of(ack + ".threads"), threads.toString());
        }
    }

    private static String readRestored(JLineInputAdapter adapter, WakeableLineReader reader,
                                       String prompt, WakeableLineReader.Draft draft) {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < until) {
            reader.restoreCursorOnNextInit(draft.cursor());
            try {
                return adapter.readLine(prompt, draft.text());
            } catch (WakeableLineReader.Wakeup expected) {
                draft = reader.takeSuspendedDraft();
                check(draft != null, "lifecycle wake lost restored draft");
            }
        }
        throw new AssertionError("lifecycle wakes did not settle");
    }

    private static void bind(LineReader reader, String name, String sequence, Runnable action) {
        reader.getWidgets().put(name, () -> { action.run(); return true; });
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference(name), sequence);
    }

    private static void mark(String value) {
        System.out.println("\r\nPROOF:" + value);
        System.out.flush();
    }

    private static void check(boolean ok, String why) {
        if (!ok) throw new AssertionError(why);
    }
}
