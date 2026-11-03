package com.paicli.cli;

import org.junit.jupiter.api.Test;
import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import com.paicli.tui.TuiBootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CliStartupOptionsTest {
    @Test
    void absentAndLegacyArgumentsAreIgnored() {
        assertFalse(CliStartupOptions.parse(null).requested());
        assertFalse(CliStartupOptions.parse(new String[0]).requested());
        assertFalse(CliStartupOptions.parse(new String[]{"old", "--unknown", "value"}).requested());
        assertTrue(CliStartupOptions.parse(new String[]{"old", "--continue"}).continueSession());
    }

    @Test
    void parsesContinueAndExactId() {
        assertEquals(new CliStartupOptions(true, null), CliStartupOptions.parse(new String[]{"--continue"}));
        assertEquals(new CliStartupOptions(false, "123"), CliStartupOptions.parse(new String[]{"--session", "123"}));
        assertEquals("session-a._1", CliStartupOptions.parse(new String[]{"--session", "session-a._1"}).sessionId());
    }

    @Test
    void rejectsMissingBlankUnsafeDuplicateAndConflictingFlags() {
        for (String[] args : new String[][]{
                {"--session"}, {"--session", ""}, {"--session", " "}, {"--session", "../x"},
                {"--session", "/tmp/x"}, {"--session", "x\\y"}, {"--session", ".."}, {"--session", "."},
                {"--session", "a\nb"}, {"--session", "--unknown"},
                {"--continue", "--continue"}, {"--session", "a", "--session", "b"},
                {"--continue", "--session", "a"}, {"--session", "a", "--continue"}}) {
            assertThrows(IllegalArgumentException.class, () -> CliStartupOptions.parse(args), java.util.Arrays.toString(args));
        }
    }

    @Test
    void actualTuiRejectsFlagsButSmallTerminalFallbackAllowsThem() {
        String previous = System.getProperty("paicli.renderer");
        try {
            System.setProperty("paicli.renderer", "lanterna");
            Terminal terminal = mock(Terminal.class);
            when(terminal.getSize()).thenReturn(new Size(120, 40));
            assertTrue(TuiBootstrap.shouldUseTui(terminal));
            for (String[] args : new String[][]{{"--continue"}, {"--session", "a"}}) {
                CliStartupOptions options = CliStartupOptions.parse(args);
                assertThrows(IllegalArgumentException.class, () -> options.requireCli(TuiBootstrap.shouldUseTui(terminal)));
            }
            assertDoesNotThrow(() -> CliStartupOptions.parse(new String[0]).requireCli(true));
            when(terminal.getSize()).thenReturn(new Size(40, 10));
            assertFalse(TuiBootstrap.shouldUseTui(terminal));
            assertDoesNotThrow(() -> CliStartupOptions.parse(new String[]{"--continue"})
                    .requireCli(TuiBootstrap.shouldUseTui(terminal)));
        } finally {
            if (previous == null) System.clearProperty("paicli.renderer");
            else System.setProperty("paicli.renderer", previous);
        }
    }
}
