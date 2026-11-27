package com.haocli.headless;

import com.haocli.agent.HarnessFeatures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** -p --harness-disable：取出参数、校验、写进系统属性；其余参数原样交给 HeadlessOptions。 */
class HarnessDisableFlagTest {

    @AfterEach
    void clear() {
        System.clearProperty(HarnessFeatures.PROPERTY);
    }

    @Test
    void extractsTheFlagAndSetsTheProperty() {
        String[] rest = HeadlessCommand.applyHarnessDisable(
                new String[]{"-p", "task", "--harness-disable", "context", "--output-format", "json"});

        assertArrayEquals(new String[]{"-p", "task", "--output-format", "json"}, rest);
        assertEquals("context", System.getProperty(HarnessFeatures.PROPERTY));
        assertEquals(4, HarnessFeatures.fromSystem().disabledNames().size());
    }

    @Test
    void withoutTheFlagNothingChanges() {
        String[] args = {"-p", "task"};
        assertArrayEquals(args, HeadlessCommand.applyHarnessDisable(args));
        assertNull(System.getProperty(HarnessFeatures.PROPERTY));
    }

    @Test
    void rejectsUnknownModulesAndDuplicates() {
        assertThrows(IllegalArgumentException.class,
                () -> HeadlessCommand.applyHarnessDisable(new String[]{"-p", "t", "--harness-disable", "memory"}));
        assertThrows(IllegalArgumentException.class, () -> HeadlessCommand.applyHarnessDisable(
                new String[]{"-p", "t", "--harness-disable", "lsp", "--harness-disable", "context"}));
        assertNull(System.getProperty(HarnessFeatures.PROPERTY));
    }
}
