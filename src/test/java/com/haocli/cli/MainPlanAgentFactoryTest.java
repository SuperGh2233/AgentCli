package com.haocli.cli;

import com.haocli.agent.Agent;
import com.haocli.agent.PlanExecuteAgent;
import com.haocli.history.ConversationLedger;
import com.haocli.llm.GLMClient;
import com.haocli.llm.LlmClient;
import com.haocli.memory.MemoryManager;
import com.haocli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertSame;

class MainPlanAgentFactoryTest {

    @Test
    void planModeReusesReactToolRegistryMemoryManagerAndLedger(@TempDir Path tempDir)
            throws Exception {
        LlmClient llmClient = new GLMClient("test-key");
        ToolRegistry sharedToolRegistry = new ToolRegistry();
        Agent reactAgent = new Agent(llmClient, sharedToolRegistry);
        ConversationLedger ledger =
                ConversationLedger.open(tempDir.resolve("history"), "shared-session");
        reactAgent.setConversationLedger(ledger);
        MemoryManager sharedMemoryManager = reactAgent.getMemoryManager();

        PlanExecuteAgent planAgent = Main.createPlanAgent(
                llmClient,
                reactAgent,
                (goal, plan) -> PlanExecuteAgent.PlanReviewDecision.cancel()
        );

        assertSame(sharedToolRegistry, readField(planAgent, "toolRegistry"));
        assertSame(sharedMemoryManager, readField(planAgent, "memoryManager"));
        assertSame(ledger, readField(planAgent, "conversationLedger"));
        assertSame(ledger, readField(readField(planAgent, "planner"), "conversationLedger"));
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }
}
