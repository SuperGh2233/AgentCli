package com.paicli.headless;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeadlessOptionsTest {

    @Test
    void defaultsAreTextAndReadOnlyWithNoCommands() {
        HeadlessOptions options = HeadlessOptions.parse(new String[]{"-p", "总结 README"});

        assertEquals("总结 README", options.prompt());
        assertEquals(HeadlessOptions.OutputFormat.TEXT, options.outputFormat());
        assertEquals(HeadlessOptions.PermissionMode.READ_ONLY, options.permissionMode());
        assertEquals(List.of(), options.allowedCommands());
    }

    @Test
    void allOptionsAreParsed() {
        HeadlessOptions options = HeadlessOptions.parse(new String[]{
                "--print", "修 bug", "--output-format", "stream-json", "--permission-mode", "edit",
                "--allow-command", "mvn -q test", "--allow-command", " git status "});

        assertEquals("修 bug", options.prompt());
        assertEquals(HeadlessOptions.OutputFormat.STREAM_JSON, options.outputFormat());
        assertEquals(HeadlessOptions.PermissionMode.EDIT, options.permissionMode());
        assertEquals(List.of("mvn -q test", "git status"), options.allowedCommands());
    }

    @Test
    void missingOrDashPromptMeansReadStdin() {
        assertNull(HeadlessOptions.parse(new String[]{"-p"}).prompt());
        assertNull(HeadlessOptions.parse(new String[]{"-p", "-"}).prompt());
        assertNull(HeadlessOptions.parse(new String[]{"-p", "--output-format", "json"}).prompt());
    }

    @Test
    void invalidArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--output-format", "xml"}));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--permission-mode", "yolo"}));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--allow-command", "  "}));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--continue"}));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "-p", "y"}));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--output-format"}));
    }

    @Test
    void headlessIsDetectedOnlyByPrintFlag() {
        assertTrue(HeadlessOptions.isHeadless(new String[]{"-p", "x"}));
        assertTrue(HeadlessOptions.isHeadless(new String[]{"--output-format", "json", "--print"}));
        assertFalse(HeadlessOptions.isHeadless(new String[]{"--continue"}));
        assertFalse(HeadlessOptions.isHeadless(null));
    }
}
