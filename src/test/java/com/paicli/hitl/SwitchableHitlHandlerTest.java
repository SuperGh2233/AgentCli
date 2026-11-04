package com.paicli.hitl;

import com.paicli.mcp.elicitation.ElicitationRequest;
import com.paicli.mcp.elicitation.ElicitationResponse;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SwitchableHitlHandlerTest {

    @Test
    void delegatesToCurrentHandlerAndForwardsSessionStateOperations() {
        StubHitlHandler first = new StubHitlHandler(ApprovalResult.reject("first"));
        StubHitlHandler second = new StubHitlHandler(ApprovalResult.approve());
        second.elicitationResult = new ElicitationResponse(ElicitationResponse.Action.ACCEPT, null);
        SwitchableHitlHandler handler = new SwitchableHitlHandler(first);
        ApprovalRequest request = ApprovalRequest.of("write_file", "{}", null, null, null);

        assertTrue(handler.requestApproval(request).isRejected());
        var elicitation = new ElicitationRequest("demo", "key", ElicitationRequest.Mode.URL,
                "Open provider", null, java.net.URI.create("https://example.test/"));
        assertEquals(ElicitationResponse.Action.CANCEL, handler.requestElicitation(elicitation).action());

        handler.setDelegate(second);

        assertTrue(handler.requestApproval(request).isApproved());
        assertEquals(ElicitationResponse.Action.ACCEPT, handler.requestElicitation(elicitation).action());
        assertEquals(1, first.elicitationCount);
        assertEquals(1, second.elicitationCount);
        assertEquals(1, first.requestCount);
        assertEquals(1, second.requestCount);

        handler.setEnabled(true);
        handler.clearApprovedAll();
        handler.clearApprovedAllForServer("chrome-devtools");

        assertTrue(second.enabled);
        assertEquals(1, second.clearAllCount);
        assertEquals("chrome-devtools", second.lastClearedServer);
    }

    @Test
    void commitsApprovalToTheHandlerThatPresentedItEvenAfterDelegateSwitch() {
        StubHitlHandler first = new StubHitlHandler(ApprovalResult.approveAll());
        StubHitlHandler second = new StubHitlHandler(ApprovalResult.approveAll());
        SwitchableHitlHandler handler = new SwitchableHitlHandler(first);
        ApprovalRequest request = ApprovalRequest.of("write_file", "{}", null);

        ApprovalResult result = handler.requestApproval(request);
        handler.setDelegate(second);
        handler.commitApproval(request, result);

        assertEquals(1, first.commitCount);
        assertEquals(0, second.commitCount);
    }

    private static final class StubHitlHandler implements HitlHandler {
        private final ApprovalResult result;
        private boolean enabled;
        private int requestCount;
        private int commitCount;
        private int elicitationCount;
        private ElicitationResponse elicitationResult = ElicitationResponse.cancel();
        private int clearAllCount;
        private String lastClearedServer;

        private StubHitlHandler(ApprovalResult result) {
            this.result = result;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requestCount++;
            return result;
        }

        @Override
        public void commitApproval(ApprovalRequest request, ApprovalResult result) {
            commitCount++;
        }

        @Override
        public ElicitationResponse requestElicitation(ElicitationRequest request) {
            elicitationCount++;
            return elicitationResult;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Override
        public void clearApprovedAll() {
            clearAllCount++;
        }

        @Override
        public void clearApprovedAllForServer(String serverName) {
            lastClearedServer = serverName;
        }
    }
}
