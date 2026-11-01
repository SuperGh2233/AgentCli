package com.paicli.cli;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Phase2B-3b MANUAL smoke test for an actual macOS Terminal controlling TTY.
 * Never used by PaiCLI Main. No LLM, tool execution or production integration.
 *
 * Invoke through tools/run-jline-native-ctrl-check.py from a REAL Terminal.
 */
public final class JLineNativeCtrlProof {
    public static void main(String[] args) throws Exception {
        String output = System.getProperty("paicli.nativectrl.stateFile");
        if (output == null || output.isBlank()) {
            throw new IllegalArgumentException("missing native Ctrl+C test state file");
        }
        Path state = Path.of(output);
        Files.writeString(state, "CTRL_C_PENDING\n", StandardCharsets.UTF_8);

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true).provider("exec").dumb(false)
                .type(System.getenv().getOrDefault("TERM", "xterm-256color"))
                .encoding(StandardCharsets.UTF_8).build()) {
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal).appName("paicli-native-ctrl-smoke").build();
            System.out.println("Native macOS Terminal test (no Codex/simulated PTY).");
            System.out.println("1. Press Ctrl+C once on the next prompt.");
            System.out.println("   Do not press Enter first. If it hangs, press Enter to fail.");
            System.out.flush();

            try {
                String unexpected = reader.readLine("ctrl+c> ");
                Files.writeString(state, "CTRL_C_NOT_INTERRUPTED: " + unexpected + "\n",
                        StandardCharsets.UTF_8);
                throw new AssertionError("real Terminal Ctrl+C did not interrupt readLine");
            } catch (UserInterruptException expected) {
                Files.writeString(state, "CTRL_C_INTERRUPT_OK\n", StandardCharsets.UTF_8);
                System.out.println("Ctrl+C caught as UserInterruptException.");
            }

            System.out.println("2. On an EMPTY prompt, press Ctrl+D once (EOF).");
            System.out.flush();
            try {
                String unexpected = reader.readLine("ctrl+d> ");
                Files.writeString(state, "CTRL_D_NOT_EOF: " + unexpected + "\n",
                        StandardCharsets.UTF_8);
                throw new AssertionError("real Terminal Ctrl+D did not report EOF");
            } catch (EndOfFileException expected) {
                Files.writeString(state, "CTRL_C_OK_CTRL_D_OK_TERMINAL_CLOSING\n",
                        StandardCharsets.UTF_8);
                System.out.println("Ctrl+D caught as EndOfFileException.");
            }
        }
        Files.writeString(state, "PASS_NATIVE_CTRL_C_AND_CTRL_D\n", StandardCharsets.UTF_8);
        System.out.println("PASS_NATIVE_CTRL_C_AND_CTRL_D");
    }
}
