package com.haocli.context;

import com.haocli.llm.DeepSeekClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 上下文预算：压缩阈值按 min(模型窗口, 预算) 算；写错直接报错。 */
class ContextBudgetTest {

    @AfterEach
    void clear() {
        System.clearProperty(ContextProfile.BUDGET_PROPERTY);
    }

    @Test
    void budgetCapsTheEffectiveWindowOfALargeModel() {
        ContextProfile unlimited = ContextProfile.from(new DeepSeekClient("test-key"));
        System.setProperty(ContextProfile.BUDGET_PROPERTY, "128000");
        ContextProfile budgeted = ContextProfile.from(new DeepSeekClient("test-key"));

        assertEquals(1_000_000, unlimited.maxContextWindow());
        assertEquals(128_000, budgeted.maxContextWindow());
        assertEquals(128_000, ContextProfile.configuredBudgetTokens());
        assertTrue(budgeted.compressionTriggerTokens() < 128_000);
        assertTrue(budgeted.compressionTriggerTokens() < unlimited.compressionTriggerTokens() / 5);
    }

    @Test
    void budgetLargerThanTheModelWindowChangesNothing() {
        System.setProperty(ContextProfile.BUDGET_PROPERTY, "5000000");

        assertEquals(1_000_000, ContextProfile.from(new DeepSeekClient("test-key")).maxContextWindow());
    }

    @Test
    void invalidBudgetIsRejected() {
        System.setProperty(ContextProfile.BUDGET_PROPERTY, "128k");
        assertThrows(IllegalArgumentException.class, ContextProfile::configuredBudgetTokens);
        System.setProperty(ContextProfile.BUDGET_PROPERTY, "0");
        assertThrows(IllegalArgumentException.class, ContextProfile::configuredBudgetTokens);
    }

    @Test
    void unsetMeansNoBudget() {
        assertEquals(0, ContextProfile.configuredBudgetTokens());
    }
}
