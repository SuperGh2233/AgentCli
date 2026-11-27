package com.haocli.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 消融开关：分组名一次关一组，细分名关单项，写错名字直接报错。 */
class HarnessFeaturesTest {

    @Test
    void blankOrNoneMeansEverythingOn() {
        assertEquals(HarnessFeatures.ALL_ON, HarnessFeatures.parse(null));
        assertEquals(HarnessFeatures.ALL_ON, HarnessFeatures.parse(" "));
        assertEquals(HarnessFeatures.ALL_ON, HarnessFeatures.parse("none"));
        assertEquals(List.of(), HarnessFeatures.ALL_ON.disabledNames());
    }

    @Test
    void contextGroupTurnsOffTheWholeContextStrategyOnly() {
        HarnessFeatures features = HarnessFeatures.parse("context");

        assertFalse(features.contextOffload());
        assertFalse(features.contextClearing());
        assertFalse(features.contextSummary());
        assertFalse(features.runawayReminder());
        assertTrue(features.lspDiagnostics());
        assertEquals(List.of("context.offload", "context.clearing", "context.summary", "context.runaway"),
                features.disabledNames());
    }

    @Test
    void individualModulesCanBeCombined() {
        HarnessFeatures features = HarnessFeatures.parse("context.summary, LSP");

        assertTrue(features.contextOffload());
        assertTrue(features.contextClearing());
        assertFalse(features.contextSummary());
        assertTrue(features.runawayReminder());
        assertFalse(features.lspDiagnostics());
        assertEquals(List.of("context.summary", "lsp"), features.disabledNames());
    }

    @Test
    void unknownNamesAreRejected() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> HarnessFeatures.parse("context,memory"));
        assertTrue(error.getMessage().contains("memory"), error.getMessage());
    }

    @Test
    void loopOnlyCreatesHooksForEnabledModules() {
        AgentLoop all = new AgentLoop(new com.haocli.tool.ToolRegistry(), conversation(), HarnessFeatures.ALL_ON);
        assertEquals(List.of(LspDiagnosticsHook.class, ContextCompactionHook.class, RunawayReminderHook.class),
                all.createHooks().stream().map(Object::getClass).toList());

        AgentLoop none = new AgentLoop(new com.haocli.tool.ToolRegistry(), conversation(),
                HarnessFeatures.parse("context,lsp"));
        assertEquals(List.of(), none.createHooks());
    }

    private static ConversationContext conversation() {
        return new ConversationContext("react", "agent",
                new com.haocli.memory.AutoCompactionManager(null), () -> 0L);
    }
}
