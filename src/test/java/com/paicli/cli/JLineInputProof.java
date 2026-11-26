package com.paicli.cli;

import com.paicli.runtime.input.TurnInputManager;
import org.jline.keymap.KeyMap;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.impl.DumbTerminal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Standalone PTY probe; never starts Main, an Agent, a provider, or a tool. */
public final class JLineInputProof {
    private static final String DRAFT = "草稿中文🚀";

    public static void main(String[] args) throws Exception {
        boolean fake = args.length > 1 && args[1].equals("fake");
        Thread owner = Thread.currentThread();
        var reads = new AtomicInteger();
        try (Terminal terminal = fake
                ? new DumbTerminal("proof", "xterm-256color", System.in, System.out, StandardCharsets.UTF_8)
                : TerminalBuilder.builder().system(true).provider("exec").dumb(false)
                    .type("xterm-256color").encoding(StandardCharsets.UTF_8).build()) {
            var original = terminal.getAttributes();
            var reader = LineReaderBuilder.builder().terminal(terminal).build();
            reader.getWidgets().put(LineReader.CALLBACK_INIT, () -> {
                check(Thread.currentThread() == owner, "JLine entered by another reader thread");
                reads.incrementAndGet(); mark("INPUT_READY"); return true;
            });
            reader.option(LineReader.Option.BRACKETED_PASTE, true);
            reader.setVariable(LineReader.AMBIGUOUS_BINDING, 80L);
            var adapter = new JLineInputAdapter(reader);
            var manager = new TurnInputManager("proof", true, TurnInputManager.Limits.defaults());
            manager.startTurn("turn");
            manager.markRunning("turn");
            var coordinator = new InputCoordinator("proof", manager, true);
            coordinator.beginRun("turn");
            mark("TERMINAL=" + terminal.getClass().getSimpleName());
            switch (args[0]) {
                case "utf8" -> {
                    mark("READY");
                    var ticket = coordinator.capture();
                    String text = adapter.readLine("> ", null);
                    check("/steer 完整中文🚀命令".equals(text), "UTF-8 changed: " + text);
                    check(coordinator.submit(ticket, text).acceptance().accepted(), "not accepted");
                    check(manager.snapshot().pendingCount() == 1, "missing input");
                }
                case "keys" -> {
                    var escapes = new AtomicInteger();
                    bind(reader, "proof-esc", "\033", () -> {
                        escapes.incrementAndGet(); reader.getBuffer().clear(); mark("ESC");
                    });
                    mark("READY");
                    check("甲中乙粘贴\n中文🚀".equals(adapter.readLine("> ", null)), "arrows/paste changed");
                    check(escapes.get() == 0, "arrow/paste became Esc");
                    mark("ESC_READY");
                    check("保留".equals(adapter.readLine("> ", null)), "Esc did not clear draft");
                    check(escapes.get() == 1, "standalone Esc not classified");
                }
                case "ownership" -> {
                    var rejected = new AtomicInteger();
                    Thread worker = new Thread(() -> {
                        try { adapter.readLine("forbidden> ", null); }
                        catch (IllegalStateException expected) { rejected.incrementAndGet(); }
                    }, "proof-non-owner");
                    worker.start(); worker.join(1000);
                    check(!worker.isAlive() && rejected.get() == 1, "worker entered JLine");
                    bind(reader, "proof-reentry", "\035", () -> {
                        try { adapter.readLine("forbidden> ", null); }
                        catch (IllegalStateException expected) { rejected.incrementAndGet(); }
                        mark("REENTRY_REJECTED");
                    });
                    check("唯一读者".equals(adapter.readLine("> ", null)), "owner input changed");
                    check(rejected.get() == 2 && reads.get() == 1, "multiple JLine reads");
                    mark("SINGLE_READER");
                }
                case "tabs" -> {
                    var tabs = new AtomicInteger();
                    bind(reader, "proof-tab", "\t", () -> { tabs.incrementAndGet(); mark("TAB"); });
                    bind(reader, "proof-shift-tab", "\033[Z", () -> { tabs.addAndGet(10); mark("SHIFT_TAB"); });
                    check("草稿中文".equals(adapter.readLine("> ", null)), "Tab changed draft");
                    check(tabs.get() == 11, "Tab/ShiftTab not distinct");
                }
                case "handoff" -> handoffProbe(adapter, manager, coordinator);
                case "approval-blocked", "run-complete" -> blockedProbe(args[0], reader, adapter, manager, coordinator);
                case "eof" -> {
                    mark("READY");
                    check(adapter.readLine("> ", null) == null, "EOF became Enter");
                }
                case "ctrl-c" -> {
                    bind(reader, "proof-check", "\035", () -> mark("CHECKPOINT"));
                    mark("READY");
                    try { adapter.readLine("> ", null); throw new AssertionError("Ctrl+C returned a line"); }
                    catch (UserInterruptException e) { check("取消前草稿".equals(e.getPartialLine()), "draft changed"); }
                }
                case "plan", "plan-line" -> planProbe(args[0], reader, adapter, manager, coordinator);
                default -> throw new IllegalArgumentException(args[0]);
            }
            var restored = terminal.getAttributes();
            check(original.getInputFlags().equals(restored.getInputFlags())
                    && original.getOutputFlags().equals(restored.getOutputFlags())
                    && original.getControlFlags().equals(restored.getControlFlags())
                    && original.getLocalFlags().equals(restored.getLocalFlags())
                    && original.getControlChars().equals(restored.getControlChars()), "JLine attributes not restored");
            mark("PASS");
            // macOS revokes the slave when its controlling session exits. Let the parent
            // inspect real termios before exit; this file handshake never reads terminal input.
            String ack = System.getProperty("paicli.proof.exitAck");
            if (ack != null) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!Files.exists(Path.of(ack)) && System.nanoTime() < deadline) Thread.sleep(10);
                check(Files.exists(Path.of(ack)), "parent did not verify terminal attributes");
            }
        }
    }

    private static void blockedProbe(String scenario, LineReader reader, JLineInputAdapter adapter,
                                     TurnInputManager manager, InputCoordinator coordinator) throws Exception {
        var checkpoint = new CountDownLatch(1);
        var returned = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        bind(reader, "proof-check", "\035", () -> {
            check(!returned.get(), "read returned unexpectedly");
            check(scenario.equals("run-complete") || DRAFT.equals(reader.getBuffer().toString()), "draft not ready");
            checkpoint.countDown();
        });
        try (var gateway = new TerminalInputGateway(coordinator, request -> {
            throw new AssertionError("blocked UI must not start another reader");
        })) {
            Thread worker = new Thread(() -> {
                try {
                    check(checkpoint.await(3, TimeUnit.SECONDS), "no input checkpoint");
                    if (scenario.equals("approval-blocked")) {
                        try (var session = gateway.openInteraction(InputCoordinator.Mode.APPROVAL_INPUT)) {
                            mark("APPROVAL_PENDING");
                            try {
                                session.read(TerminalInputGateway.Kind.APPROVAL_CHOICE, "approve> ", Duration.ofMillis(250));
                                throw new AssertionError("approval unexpectedly delivered");
                            } catch (TerminalInputGateway.InputUnavailableException e) {
                                check(e.failure() == TerminalInputGateway.Failure.TIMEOUT, "wrong failure");
                            }
                        }
                        check(!returned.get(), "readLine unexpectedly woke");
                        check(manager.snapshot().pendingCount() == 0, "approval became steer");
                        mark("GATE_NO_WAKE");
                    } else {
                        // A finished task has no mechanism to end the owner's existing readLine.
                        check(!returned.get(), "readLine already ended");
                        mark("RUN_NO_WAKE");
                    }
                } catch (Throwable e) { failure.set(e); mark("WORKER_FAILED"); }
            }, "proof-worker-no-terminal-access");
            worker.start();
            try {
                mark("READY");
                String line = adapter.readLine("> ", null);
                check(scenario.equals("run-complete") && line == null, "unexpected user submission");
            } catch (UserInterruptException e) {
                check(scenario.equals("approval-blocked") && DRAFT.equals(e.getPartialLine()), "draft lost on cleanup");
            } finally {
                returned.set(true);
                gateway.close();
                worker.join(4000);
            }
            check(!worker.isAlive(), "worker stuck");
            if (failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    private static void handoffProbe(JLineInputAdapter adapter, TurnInputManager manager,
                                     InputCoordinator coordinator) throws Exception {
        // Explicit safe point only: seed restoration is not an asynchronous handoff.
        var result = new AtomicReference<String>();
        var failure = new AtomicReference<Throwable>();
        try (var gateway = new TerminalInputGateway(coordinator, adapter)) {
            Thread worker = new Thread(() -> {
                try (var session = gateway.openInteraction(InputCoordinator.Mode.APPROVAL_INPUT)) {
                    result.set(session.read(TerminalInputGateway.Kind.APPROVAL_CHOICE, "approve> ", Duration.ofSeconds(3)));
                } catch (Throwable e) { failure.set(e); }
            }, "proof-approval-worker");
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (worker.isAlive() && System.nanoTime() < deadline) {
                gateway.serviceNext(); Thread.yield();
            }
            worker.join(1000);
            check(!worker.isAlive() && failure.get() == null && "y".equals(result.get()), "approval not delivered");
            check(manager.snapshot().pendingCount() == 0, "approval became steer");
            check(DRAFT.concat("继续").equals(adapter.readLine("> ", DRAFT)), "seed draft changed");
        }
    }

    private static void planProbe(String scenario, LineReader reader, JLineInputAdapter adapter,
                                  TurnInputManager manager, InputCoordinator coordinator) throws Exception {
        // Probe-only Plan keymap; it does not migrate Main's raw review handler.
        var views = new AtomicInteger();
        bind(reader, "proof-arrow", "\033[A", () -> mark("CHECKPOINT"));
        bind(reader, "proof-view", "\017", () -> { views.incrementAndGet(); mark("VIEW"); });
        bind(reader, "proof-supplement", "I", () -> mark("SUPPLEMENT"));
        var result = new AtomicReference<String>();
        var failure = new AtomicReference<Throwable>();
        try (var gateway = new TerminalInputGateway(coordinator, adapter)) {
            Thread worker = new Thread(() -> {
                try (var session = gateway.openInteraction(InputCoordinator.Mode.PLAN_REVIEW_INPUT)) {
                    result.set(session.read(TerminalInputGateway.Kind.PLAN_SUPPLEMENT, "PROOF:READY\n补充> ",
                            Duration.ofSeconds(5)));
                } catch (Throwable e) { failure.set(e); }
            }, "proof-plan-worker");
            worker.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (worker.isAlive() && System.nanoTime() < deadline) {
                gateway.serviceNext();
                Thread.yield();
            }
            worker.join(1000);
            check(!worker.isAlive(), "plan worker stuck");
            if (failure.get() != null) throw new AssertionError(failure.get());
            check((scenario.equals("plan") ? "先检查中文🚀" : "补充中文步骤").equals(result.get()), "plan input lost");
            check(PlanReviewInputParser.parse(result.get()).type() == PlanReviewInputParser.DecisionType.SUPPLEMENT,
                    "wrong plan decision");
            check(views.get() == (scenario.equals("plan") ? 1 : 0), "wrong view count");
            check(manager.snapshot().pendingCount() == 0, "plan became steer");
            check(coordinator.capture().mode() == InputCoordinator.Mode.RUNNING_INPUT, "plan mode leaked");
        }
    }

    private static void bind(LineReader reader, String name, String sequence, Runnable action) {
        reader.getWidgets().put(name, () -> { action.run(); return true; });
        reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference(name), sequence);
    }

    private static void mark(String value) { System.out.println("\r\nPROOF:" + value); System.out.flush(); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
