package com.paicli.cli;

import com.paicli.agent.Agent;
import com.paicli.history.ConversationLedger;
import com.paicli.history.SessionResume;
import com.paicli.hitl.SwitchableHitlHandler;
import com.paicli.llm.LlmClient;
import com.paicli.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StartupSessionResumeTest {
    @TempDir Path temp;

    private Agent agent(ConversationLedger ledger) {
        ToolRegistry registry = new ToolRegistry();
        registry.setProjectPath(temp.resolve("project").toString());
        Agent agent = new Agent(mock(LlmClient.class), registry);
        agent.setConversationLedger(ledger);
        SessionResume.recordSessionStart(ledger, registry.getProjectPath());
        return agent;
    }

    private ConversationLedger session(String id, String project, boolean complete, long modified) throws Exception {
        ConversationLedger ledger = ConversationLedger.open(temp.resolve("history"), id);
        if (project != null) SessionResume.recordSessionStart(ledger, temp.resolve(project).toString());
        ledger.appendMessage("react", "agent", "user_input", LlmClient.Message.user(id));
        if (complete) ledger.appendMessage("react", "agent", "llm_response", LlmClient.Message.assistant("answer-" + id));
        Files.setLastModifiedTime(ledger.file(), FileTime.fromMillis(modified));
        return ledger;
    }

    @Test
    void latestUsesExistingOrderingExcludesCurrentAndSkipsInvalidSessions() throws Exception {
        session("older", "project", true, 1000);
        ConversationLedger latest = session("latest", "project", true, 2000);
        session("unfinished-only", "project", false, 3000);
        session("foreign", "other", true, 4000);
        session("headless", null, true, 5000);
        ConversationLedger current = ConversationLedger.open(temp.resolve("history"), "current");
        Agent agent = agent(current);
        current.appendMessage("react", "agent", "llm_response", LlmClient.Message.assistant("current answer"));
        SwitchableHitlHandler hitl = mock(SwitchableHitlHandler.class);
        var result = ResumeCommandHandler.handleStartup(agent, new CliStartupOptions(true, null), hitl);
        assertTrue(result.restored(), result.output());
        assertEquals("latest", agent.getConversationHistory().get(1).content());
        verify(hitl).clearApprovedAll();
        var checkpoint = current.readAll().stream()
                .filter(e -> ConversationLedger.VIEW_CHECKPOINT_EVENT.equals(e.event())).findFirst().orElseThrow();
        assertEquals("latest", checkpoint.metadata().get("resumedFrom"));
        assertEquals(2, SessionResume.loadExact(latest.file().getParent(), temp.resolve("project").toString(),
                "excluded", "current").orElseThrow().messages().size());
    }

    @Test
    void numericIdIsExactAndUnfinishedLastTurnIsDropped() throws Exception {
        ConversationLedger numeric = session("123", "project", true, 1000);
        numeric.appendMessage("react", "agent", "user_input", LlmClient.Message.user("unfinished"));
        session("newest", "project", true, 5000);
        Agent agent = agent(ConversationLedger.open(temp.resolve("history"), "current"));
        var result = ResumeCommandHandler.handleStartup(agent, CliStartupOptions.parse(new String[]{"--session", "123"}),
                mock(SwitchableHitlHandler.class));
        assertTrue(result.restored(), result.output());
        assertEquals(List.of("123", "answer-123"), agent.getConversationHistory().subList(1, 3).stream()
                .map(LlmClient.Message::content).toList());
        assertEquals(3, agent.getConversationHistory().size());
        assertTrue(result.output().contains("没有恢复"));
    }

    @Test
    void rejectsForeignHeadlessMissingEmptyAndCurrentWithoutChangingHistoryOrApprovals() throws Exception {
        session("foreign", "other", true, 1000);
        session("headless", null, true, 1000);
        session("empty", "project", false, 1000);
        Agent agent = agent(ConversationLedger.open(temp.resolve("history"), "current"));
        List<LlmClient.Message> before = List.copyOf(agent.getConversationHistory());
        SwitchableHitlHandler hitl = mock(SwitchableHitlHandler.class);
        for (String id : List.of("foreign", "headless", "missing", "empty", "current")) {
            var result = ResumeCommandHandler.handleStartup(agent, new CliStartupOptions(false, id), hitl);
            assertFalse(result.restored(), id);
            assertTrue(result.output().contains("❌"));
            assertEquals(before, agent.getConversationHistory());
        }
        verifyNoInteractions(hitl);
    }

    @Test
    void noPriorSessionOrUnavailableLedgerFailsClearly() throws Exception {
        Agent agent = agent(ConversationLedger.open(temp.resolve("history"), "current"));
        var hitl = mock(SwitchableHitlHandler.class);
        assertFalse(ResumeCommandHandler.handleStartup(agent, new CliStartupOptions(true, null), hitl).restored());
        agent.setConversationLedger(ConversationLedger.disabled());
        var result = ResumeCommandHandler.handleStartup(agent, new CliStartupOptions(true, null), hitl);
        assertFalse(result.restored());
        assertTrue(result.output().contains("不可用"));
        verifyNoInteractions(hitl);
    }
}
