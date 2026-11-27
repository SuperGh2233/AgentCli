package com.haocli.headless;

import com.haocli.agent.RunLimits;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

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

    @Test
    void limitsDefaultToUnattendedValuesAndFlagsOverrideEnvironment() {
        HeadlessOptions defaults = HeadlessOptions.parse(new String[]{"-p", "x"}, key -> null);
        assertEquals(RunLimits.unattended(key -> null), defaults.limits());

        Map<String, String> env = Map.of(RunLimits.ENV_MAX_TURNS, "10", RunLimits.ENV_MAX_TOKENS, "9000");
        HeadlessOptions options = HeadlessOptions.parse(new String[]{
                "-p", "x", "--max-turns", "3", "--timeout", "2m"}, env::get);

        assertEquals(3, options.limits().maxModelRequests(), "参数优先于环境变量");
        assertEquals(9000, options.limits().maxTotalTokens(), "没给参数的用环境变量");
        assertEquals(Duration.ofMinutes(2), options.limits().timeout());
        assertEquals(options.limits(), options.withPrompt("y").limits(), "换任务不丢上限");
    }

    @Test
    void invalidOrRepeatedLimitFlagsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--max-turns", "0"}, key -> null));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--max-tokens", "many"}, key -> null));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--timeout", "1d"}, key -> null));
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessOptions.parse(new String[]{"-p", "x", "--max-turns", "1", "--max-turns", "2"}, key -> null));
    }
}
