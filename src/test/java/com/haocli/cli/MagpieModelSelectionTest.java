package com.haocli.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** /model 解析：magpie 和 workbuddy/ 开头的模型归到 magpie；官方 DeepSeek 不受影响。 */
class MagpieModelSelectionTest {

    @Test
    void routesMagpieAndWorkBuddyModels() {
        assertEquals("magpie", Main.resolveModelSelection("magpie").provider());
        Main.ModelSelection workbuddy = Main.resolveModelSelection("workbuddy/deepseek-v4.1-flash");
        assertEquals("magpie", workbuddy.provider());
        assertEquals("workbuddy/deepseek-v4.1-flash", workbuddy.model());
        assertEquals("deepseek", Main.resolveModelSelection("deepseek-flash").provider());
    }
}
