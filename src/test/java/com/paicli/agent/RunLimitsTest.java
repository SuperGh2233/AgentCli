package com.paicli.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 无人值守上限：默认值、环境变量覆盖、时长写法、非法取值直接报错。 */
class RunLimitsTest {

    @Test
    void unattendedDefaultsAreFiftyRequestsTwoMillionTokensThirtyMinutes() {
        RunLimits limits = RunLimits.unattended(key -> null);

        assertEquals(50, limits.maxModelRequests());
        assertEquals(2_000_000, limits.maxTotalTokens());
        assertEquals(Duration.ofMinutes(30), limits.timeout());
        assertFalse(RunLimits.UNLIMITED.hasTimeout(), "交互命令行默认不限");
    }

    @Test
    void environmentVariablesOverrideDefaults() {
        Map<String, String> env = Map.of(
                RunLimits.ENV_MAX_TURNS, "10",
                RunLimits.ENV_MAX_TOKENS, "500_000",
                RunLimits.ENV_TIMEOUT, "90");

        RunLimits limits = RunLimits.unattended(env::get);

        assertEquals(10, limits.maxModelRequests());
        assertEquals(500_000, limits.maxTotalTokens());
        assertEquals(Duration.ofSeconds(90), limits.timeout(), "纯数字按秒");
    }

    @Test
    void durationsAcceptSecondsMinutesAndHours() {
        assertEquals(Duration.ofSeconds(90), RunLimits.parseDuration("90s", "x"));
        assertEquals(Duration.ofMinutes(30), RunLimits.parseDuration(" 30M ", "x"));
        assertEquals(Duration.ofHours(2), RunLimits.parseDuration("2h", "x"));
        for (String bad : new String[]{"", "0", "-5", "abc", "10d", "m"}) {
            assertThrows(IllegalArgumentException.class, () -> RunLimits.parseDuration(bad, "--timeout"), bad);
        }
    }

    @Test
    void invalidEnvironmentValueFailsLoudlyWithTheVariableName() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> RunLimits.unattended(Map.of(RunLimits.ENV_MAX_TURNS, "lots")::get));

        assertTrue(error.getMessage().contains(RunLimits.ENV_MAX_TURNS), error.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new RunLimits(0, 1, null));
        assertThrows(IllegalArgumentException.class, () -> new RunLimits(1, 1, Duration.ZERO));
    }
}
