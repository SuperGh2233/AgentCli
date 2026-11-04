package com.paicli.hitl;

import com.paicli.mcp.elicitation.ElicitationRequest;
import com.paicli.mcp.elicitation.ElicitationResponse;

import java.util.Objects;

/**
 * Delegates HITL interaction to the currently active UI implementation.
 *
 * <p>The registry is created before the UI mode is selected, so this wrapper
 * lets CLI keep using {@link TerminalHitlHandler} while TUI can swap in a
 * Lanterna-backed handler without rebuilding the tool registry.
 */
public final class SwitchableHitlHandler implements HitlHandler {

    private volatile HitlHandler delegate;
    private volatile boolean highRiskConfirmation;
    /** Preserve the handler that actually presented this thread's approval until validation commits it. */
    private final ThreadLocal<PendingApproval> pendingApproval = new ThreadLocal<>();

    public SwitchableHitlHandler(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public void setDelegate(HitlHandler delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    public HitlHandler getDelegate() {
        return delegate;
    }

    @Override
    public ApprovalResult requestApproval(ApprovalRequest request) {
        pendingApproval.remove();
        HitlHandler selected = delegate;
        ApprovalResult result = selected.requestApproval(request);
        pendingApproval.set(new PendingApproval(selected, request, result));
        return result;
    }

    @Override
    public void commitApproval(ApprovalRequest request, ApprovalResult result) {
        PendingApproval pending = pendingApproval.get();
        pendingApproval.remove();
        if (pending != null && pending.request() == request && pending.result() == result) {
            pending.handler().commitApproval(request, result);
        }
    }

    private record PendingApproval(HitlHandler handler, ApprovalRequest request, ApprovalResult result) {}

    @Override
    public java.util.Set<String> supportedModes() { return delegate.supportedModes(); }

    @Override
    public ElicitationResponse requestElicitation(ElicitationRequest request) {
        return delegate.requestElicitation(request);
    }

    @Override
    public boolean isEnabled() {
        return delegate.isEnabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        delegate.setEnabled(enabled);
    }

    @Override
    public void onAutoApproved(String toolName, String reason) {
        delegate.onAutoApproved(toolName, reason);
    }

    @Override
    public void onAutoDenied(String toolName, String reason) {
        delegate.onAutoDenied(toolName, reason);
    }

    @Override
    public boolean isHighRiskConfirmationEnabled() {
        return highRiskConfirmation;
    }

    @Override
    public void setHighRiskConfirmationEnabled(boolean enabled) {
        this.highRiskConfirmation = enabled;
    }

    @Override
    public boolean isApprovedAllByTool(String toolName) {
        return delegate.isApprovedAllByTool(toolName);
    }

    @Override
    public boolean isApprovedAllByServer(String serverName) {
        return delegate.isApprovedAllByServer(serverName);
    }

    @Override
    public void clearApprovedAll() {
        delegate.clearApprovedAll();
    }

    @Override
    public void clearApprovedAllForServer(String serverName) {
        delegate.clearApprovedAllForServer(serverName);
    }
}
