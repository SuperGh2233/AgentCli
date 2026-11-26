package com.paicli.hitl;

import com.paicli.browser.BrowserCheckResult;
import com.paicli.mcp.McpPermissionBinding;
import com.paicli.policy.AuditLog;
import com.paicli.mcp.protocol.McpToolDescriptor;
import com.paicli.tool.ToolOutput;
import com.paicli.tool.ToolRegistry;

import java.util.concurrent.TimeUnit;
import java.io.IOException;
import java.util.List;
import java.util.function.Function;

/**
 * HITL 工具注册表 - 在危险工具调用前插入人工审批
 *
 * 继承自 ToolRegistry，覆写 executeTool 方法，在执行危险操作之前
 * 通过 HitlHandler 向用户请求审批。
 *
 * 如果 HITL 未启用，行为与父类完全相同，无额外开销。
 *
 * HITL 拒绝 / 跳过路径会写一行 audit（approver=hitl），HITL 通过后由父类 ToolRegistry 写
 * allow / policy-deny / error，HITL 审批与策略拦截共用同一份 ~/.paicli/audit/ 文件。
 */
public class HitlToolRegistry extends ToolRegistry {

    private final HitlHandler hitlHandler;
    private volatile AutoApprovalReviewer autoApprovalReviewer;
    /** Optional: enabled only for interactive sessions after the rules UI has been reviewed. */
    private volatile PermissionRuleStore permissionRuleStore;
    /** Live MCP trust/config snapshot provider. Missing provider always disables permanent MCP ALLOW. */
    private volatile Function<String, McpPermissionBinding> mcpPermissionBindingProvider;
    /** auto 模式下同一轮连续被拦的次数；到阈值转人工，放行或新一轮输入时清零。 */
    private final java.util.concurrent.atomic.AtomicInteger consecutiveAutoDenials =
            new java.util.concurrent.atomic.AtomicInteger();
    static final int AUTO_ESCALATE_AFTER = 3;

    public HitlToolRegistry(HitlHandler hitlHandler) {
        super();
        this.hitlHandler = hitlHandler;
    }

    // MCP 工具集变化前，撤销该 server 及其各个工具的旧会话授权。
    @Override
    public synchronized void registerMcpToolOutput(McpToolDescriptor descriptor, Function<String, ToolOutput> invoker) {
        withMcpToolRegistryWriteLock(() -> {
            hitlHandler.clearApprovedAllForServer(descriptor.serverName());
            super.registerMcpToolOutput(descriptor, invoker);
        });
    }

    @Override
    public synchronized void unregisterMcpTool(String toolName) {
        withMcpToolRegistryWriteLock(() -> {
            hitlHandler.clearApprovedAllForServer(ApprovalPolicy.mcpServerName(toolName));
            super.unregisterMcpTool(toolName);
        });
    }

    @Override
    public synchronized void replaceMcpToolOutputsForServer(String serverName, List<McpToolDescriptor> newTools,
            Function<McpToolDescriptor, Function<String, ToolOutput>> invokerFactory) {
        withMcpToolRegistryWriteLock(() -> {
            hitlHandler.clearApprovedAllForServer(serverName);
            super.replaceMcpToolOutputsForServer(serverName, newTools, invokerFactory);
        });
    }

    /** auto 模式下审查 execute_command 的分类器；未设置时这些调用一律拦回给模型。 */
    public void setAutoApprovalReviewer(AutoApprovalReviewer reviewer) {
        this.autoApprovalReviewer = reviewer;
    }

    /** Stage 3B runtime seam. Not enabled for unattended workers or normal CLI startup yet. */
    public void setPermissionRuleStore(PermissionRuleStore store) {
        this.permissionRuleStore = store;
    }

    /** Inject the live MCP trust/config snapshot provider after McpServerManager is created. */
    public void setMcpPermissionBindingProvider(Function<String, McpPermissionBinding> provider) {
        this.mcpPermissionBindingProvider = provider;
    }

    private McpPermissionBinding currentMcpBinding(String toolName) {
        if (!ApprovalPolicy.isMcpTool(toolName)) return null;
        Function<String, McpPermissionBinding> provider = mcpPermissionBindingProvider;
        String serverName = ApprovalPolicy.mcpServerName(toolName);
        if (provider == null || serverName == null || serverName.isBlank()) return null;
        try {
            McpPermissionBinding binding = provider.apply(serverName);
            return binding != null && serverName.equals(binding.serverName()) ? binding : null;
        } catch (RuntimeException ignored) {
            // A failed live snapshot must never authorize an MCP tool.
            return null;
        }
    }

    private boolean isCurrentMcpProposal(PermissionRule proposal) {
        if (proposal == null || !ApprovalPolicy.isMcpTool(proposal.matcher())
                || proposal.bindingFingerprint() == null) return false;
        String serverName = ApprovalPolicy.mcpServerName(proposal.matcher());
        McpPermissionBinding binding = currentMcpBinding(proposal.matcher());
        return binding != null && binding.usableForPermanentAllow()
                && serverName.equals(binding.serverName())
                && proposal.bindingFingerprint().equals(binding.configFingerprint());
    }

    private ToolOutput executeParentTool(String name, String argumentsJson,
                                         McpRegisteredTool mcpToolSnapshot,
                                         McpPermissionBinding expectedBinding) {
        return executeParentTool(name, argumentsJson, mcpToolSnapshot, expectedBinding, null);
    }

    private ToolOutput executeParentTool(String name, String argumentsJson,
                                         McpRegisteredTool mcpToolSnapshot,
                                         McpPermissionBinding expectedBinding,
                                         Runnable beforeInvoke) {
        if (!ApprovalPolicy.isMcpTool(name)) {
            return beforeInvoke == null
                    ? super.doExecuteTool(name, argumentsJson)
                    : super.doExecuteToolWithBeforeInvoke(name, argumentsJson, beforeInvoke);
        }
        if (mcpToolSnapshot == null || !isCurrentMcpToolRegistration(name, mcpToolSnapshot)) {
            return ToolOutput.failure("[PERMISSION] MCP 工具注册已变化或不可用，未执行；请重新审批");
        }
        if (mcpPermissionBindingProvider != null) {
            McpPermissionBinding current = currentMcpBinding(name);
            if (expectedBinding == null || !expectedBinding.usableForPermanentAllow()
                    || !expectedBinding.equals(current)) {
                return ToolOutput.failure("[PERMISSION] MCP 配置、信任或运行状态已变化，未执行；请重新审批");
            }
        }
        return super.doExecuteMcpToolBound(name, argumentsJson, mcpToolSnapshot, beforeInvoke);
    }

    /** 每条顶层用户输入开始时调用，连续被拦的计数只在同一轮内累计。 */
    public void startAutoReviewTurn() {
        consecutiveAutoDenials.set(0);
    }

    @Override
    public String executeTool(String name, String argumentsJson) {
        return executeToolOutput(name, argumentsJson).text();
    }

    @Override
    public ToolOutput executeToolOutput(String name, String argumentsJson) {
        McpRegisteredTool mcpToolSnapshot = ApprovalPolicy.isMcpTool(name) ? mcpToolSnapshot(name) : null;
        // 参数验证始终先于规则裁决；包括未知/非法工具，不受用户保存的规则影响。
        if (argumentError(name, argumentsJson) != null) {
            return executeParentTool(name, argumentsJson, mcpToolSnapshot, currentMcpBinding(name));
        }
        McpPermissionBinding mcpBinding = currentMcpBinding(name);
        if (ApprovalPolicy.isMcpTool(name) && mcpPermissionBindingProvider != null
                && (mcpBinding == null || !mcpBinding.usableForPermanentAllow())) {
            // External config changes or trust loss invalidate even broad session grants.
            hitlHandler.clearApprovedAllForServer(ApprovalPolicy.mcpServerName(name));
        }
        PermissionRuleStore store = permissionRuleStore;
        if (store != null && ApprovalPolicy.requiresApproval(name)) {
            long start = System.nanoTime();
            PermissionRuleEvaluator.Evaluation decision;
            try {
                decision = PermissionRuleRuntime.evaluate(store, getProjectPath(), name, argumentsJson,
                        mcpBinding);
            } catch (IOException | IllegalArgumentException error) {
                String reason = "权限规则读取失败，已拒绝执行：" + error.getMessage();
                getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                        reason, elapsedMillis(start)));
                return ToolOutput.failure("[PERMISSION] " + reason);
            }
            if (decision.decision() == PermissionRuleEvaluator.Decision.DENY) {
                String reason = "规则 " + decision.matchedRule().id() + " 拒绝该工具调用";
                getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                        reason, elapsedMillis(start)));
                return ToolOutput.failure("[PERMISSION] " + reason);
            }
            if (decision.decision() == PermissionRuleEvaluator.Decision.ASK) {
                if (!hitlHandler.isConfirmationActive()) {
                    String reason = "规则 " + decision.matchedRule().id() + " 要求人工审批，但当前没有可用的审批通道";
                    getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                            reason, elapsedMillis(start)));
                    return ToolOutput.failure("[PERMISSION] " + reason);
                }
                BrowserCheckResult browser = checkBrowserTool(name, argumentsJson, true);
                if (browser.blocked()) return executeParentTool(name, argumentsJson, mcpToolSnapshot, mcpBinding);
                // Force this request through the prompt; never clear shared grants as a substitute.
                return executeAfterExplicitApproval(name, argumentsJson,
                        browser.requiresPerCallApproval() ? browser.sensitiveNotice() : null,
                        "权限规则 " + decision.matchedRule().id() + " 要求逐次确认",
                        null, mcpToolSnapshot, mcpBinding, true);
            }
            if (PermissionRuleRuntime.maySkipApproval(decision, name)) {
                BrowserCheckResult browser = checkBrowserTool(name, argumentsJson, true);
                if (!browser.requiresPerCallApproval()) {
                    // Parent ToolRegistry still enforces all hard guards and sandbox policy.
                    return executeParentTool(name, argumentsJson, mcpToolSnapshot, mcpBinding);
                }
            }
        }
        // 完整 HITL 确认全部危险工具；未开启时，默认只确认高危工具
        boolean needsApproval = hitlHandler.isEnabled()
                ? ApprovalPolicy.requiresApproval(name)
                : hitlHandler.isHighRiskConfirmationEnabled() && ApprovalPolicy.requiresConfirmationByDefault(name);
        // 参数不合法的调用注定失败：不弹确认框、不调审查模型、不计入 auto 连续被拦次数，直接走执行路径拿到校验错误
        if (!needsApproval || argumentError(name, argumentsJson) != null) {
            return executeParentTool(name, argumentsJson, mcpToolSnapshot, mcpBinding);
        }
        BrowserCheckResult browserCheck = checkBrowserTool(name, argumentsJson, true);
        if (browserCheck.blocked()) {
            return executeParentTool(name, argumentsJson, mcpToolSnapshot, mcpBinding);
        }
        String sensitiveNotice = browserCheck.requiresPerCallApproval() ? browserCheck.sensitiveNotice() : null;
        if (sensitiveNotice == null) {
            String mcpServer = ApprovalPolicy.mcpServerName(name);
            if (hitlHandler.isApprovedAllByTool(name) || hitlHandler.isApprovedAllByServer(mcpServer)) {
                return executeParentTool(name, argumentsJson, mcpToolSnapshot, mcpBinding);
            }
        }
        if (inAutoMode()) {
            return executeInAutoMode(name, argumentsJson, sensitiveNotice, mcpToolSnapshot, mcpBinding);
        }
        PermissionRule permanentAllowProposal = null;
        PermissionRuleStore activeStore = permissionRuleStore;
        if (activeStore != null && sensitiveNotice == null) {
            try {
                permanentAllowProposal = PermissionRuleRuntime.proposePermanentAllow(
                        activeStore, getProjectPath(), name, argumentsJson, mcpBinding);
            } catch (IOException | IllegalArgumentException ignored) {
                // No permanent-allow option is shown when a safe, project-scoped proposal cannot be built.
            }
        }
        return executeAfterExplicitApproval(name, argumentsJson, sensitiveNotice, null,
                permanentAllowProposal, mcpToolSnapshot, mcpBinding, false);
    }

    /**
     * auto 模式不打断用户：Shell 命令由分类器审查，放行就执行；不放行、审查失败，以及 MCP 工具、回滚快照、
     * 敏感页面改写这些不交给分类器的操作，都作为失败结果交回模型，让它换做法或向用户说明。
     * 同一轮连续被拦到 {@link #AUTO_ESCALATE_AFTER} 次才转人工审批，避免模型反复试探把任务卡住。
     */
    private ToolOutput executeInAutoMode(String name, String argumentsJson, String sensitiveNotice,
                                          McpRegisteredTool mcpToolSnapshot,
                                          McpPermissionBinding mcpBinding) {
        long start = System.nanoTime();
        String reason;
        if (sensitiveNotice != null) {
            reason = "敏感页面上的改写操作需要人工逐次确认";
        } else if (AUTO_REVIEWED_TOOLS.contains(name)) {
            AutoApprovalReviewer reviewer = autoApprovalReviewer;
            if (reviewer == null) {
                reason = "自动审查不可用";
            } else {
                AutoApprovalReviewer.Review review = reviewer.review(name, argumentsJson);
                if (review.allowed()) {
                    consecutiveAutoDenials.set(0);
                    getAuditLog().record(AuditLog.AuditEntry.allowByAutoClassifier(
                            name, argumentsJson, review.reason(), elapsedMillis(start)));
                    hitlHandler.onAutoApproved(name, review.reason());
                    // 路径围栏、命令黑名单仍在父类执行路径里生效，分类器放行绕不过
                    return executeParentTool(name, argumentsJson, mcpToolSnapshot, mcpBinding);
                }
                reason = review.reason();
            }
        } else if (ApprovalPolicy.mcpServerName(name) != null) {
            reason = "auto 模式不自动执行 MCP 工具，它的实际行为由外部 server 决定";
        } else {
            reason = "auto 模式不自动执行" + ("revert_turn".equals(name) ? "回滚快照，它会批量覆盖工作区文件" : "该操作");
        }

        int denials = consecutiveAutoDenials.incrementAndGet();
        if (denials >= AUTO_ESCALATE_AFTER) {
            consecutiveAutoDenials.set(0);
            return executeAfterExplicitApproval(name, argumentsJson, sensitiveNotice,
                    "自动审查已连续 " + denials + " 次未放行，本次：" + reason,
                    null, mcpToolSnapshot, mcpBinding, false);
        }
        getAuditLog().record(AuditLog.AuditEntry.denyByAutoClassifier(
                name, argumentsJson, reason, elapsedMillis(start)));
        hitlHandler.onAutoDenied(name, reason);
        return ToolOutput.failure("[AUTO] 自动审查未放行：" + reason
                + "。请换一种更安全、只读的做法；如果确实必须执行，请在回复里向用户说明原因，"
                + "由用户按 Shift+Tab 切到 ask 模式后再执行。");
    }

    /** auto 模式只让分类器审查 Shell 命令；MCP 工具的实际行为由第三方 server 决定，分类器只看得到名字和参数。 */
    private static final java.util.Set<String> AUTO_REVIEWED_TOOLS = java.util.Set.of("execute_command");

    private boolean inAutoMode() {
        return !hitlHandler.isEnabled() && hitlHandler.isHighRiskConfirmationEnabled();
    }

    private ToolOutput executeAfterExplicitApproval(String name, String argumentsJson, String sensitiveNotice,
                                                    String reviewNote, PermissionRule permanentAllowProposal,
                                                    McpRegisteredTool mcpToolSnapshot,
                                                    McpPermissionBinding initialBinding,
                                                    boolean forcePerCall) {
        long start = System.nanoTime();
        boolean mcpCall = ApprovalPolicy.isMcpTool(name);
        if (mcpCall && (mcpToolSnapshot == null || !isCurrentMcpToolRegistration(name, mcpToolSnapshot))) {
            String reason = "MCP 工具注册已变化或不可用，已阻止审批";
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                    reason, elapsedMillis(start)));
            return ToolOutput.failure("[PERMISSION] " + reason);
        }
        boolean bindingManaged = mcpCall && mcpPermissionBindingProvider != null;
        McpPermissionBinding approvalBinding = bindingManaged ? currentMcpBinding(name) : null;
        if (bindingManaged && (approvalBinding == null || !approvalBinding.usableForPermanentAllow()
                || initialBinding == null || !initialBinding.equals(approvalBinding))) {
            String reason = "MCP server 当前配置、信任或运行状态不可用于审批，已阻止执行";
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                    reason, elapsedMillis(start)));
            return ToolOutput.failure("[PERMISSION] " + reason);
        }
        if (bindingManaged && permanentAllowProposal != null
                && !permanentAllowProposal.bindingFingerprint().equals(approvalBinding.configFingerprint())) {
            String reason = "MCP 配置在生成永久授权候选后发生变化，已阻止执行";
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                    reason, elapsedMillis(start)));
            return ToolOutput.failure("[PERMISSION] " + reason + "；请重新审批");
        }
        ApprovalRequest request = ApprovalRequest.of(name, argumentsJson, reviewNote, null, sensitiveNotice,
                permanentAllowProposal, forcePerCall);
        ApprovalResult result = hitlHandler.requestApproval(request);

        boolean perCallOnly = forcePerCall || (sensitiveNotice != null && !sensitiveNotice.isBlank());
        if (perCallOnly && (result.isApprovedAllForTool() || result.isApprovedAllForServer()
                || result.decision() == ApprovalResult.Decision.PERMANENTLY_ALLOWED)) {
            String reason = "当前操作要求逐次确认，不允许会话级或永久放行";
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                    reason, elapsedMillis(start)));
            return ToolOutput.failure("[PERMISSION] " + reason);
        }

        if (result.isRejected()) {
            String reason = result.reason() != null && !result.reason().isBlank()
                    ? result.reason()
                    : "用户拒绝了此操作";
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, reason, elapsedMillis(start)));
            return ToolOutput.failure("[HITL] 操作已被拒绝：" + reason);
        }

        if (result.isSkipped()) {
            getAuditLog().record(AuditLog.AuditEntry.denyByHitl(
                    name, argumentsJson, "用户跳过", elapsedMillis(start)));
            return ToolOutput.failure("[HITL] 操作已被跳过");
        }

        if (mcpCall && !isCurrentMcpToolRegistration(name, mcpToolSnapshot)) {
            String reason = "MCP 工具在审批期间被替换，本次操作未执行";
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                    reason, elapsedMillis(start)));
            return ToolOutput.failure("[PERMISSION] " + reason + "；请重新审批");
        }
        if (bindingManaged && !approvalBinding.equals(currentMcpBinding(name))) {
            String reason = "MCP 配置、信任或运行状态在审批期间发生变化，本次操作未执行";
            getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                    reason, elapsedMillis(start)));
            return ToolOutput.failure("[PERMISSION] " + reason + "；请重新审批");
        }

        if (result.decision() == ApprovalResult.Decision.PERMANENTLY_ALLOWED) {
            PermissionRuleStore store = permissionRuleStore;
            PermissionRule proposal = request.permanentAllowProposal();
            if (store == null || proposal == null || proposal.effect() != PermissionRule.Effect.ALLOW) {
                return ToolOutput.failure("[PERMISSION] 当前操作没有有效的永久授权候选，未保存规则");
            }
            try {
                String realProject = java.nio.file.Path.of(getProjectPath()).toRealPath().toString();
                if (!realProject.equals(proposal.projectRoot())) {
                    return ToolOutput.failure("[PERMISSION] 项目路径已变化，永久授权未保存；请重新审批");
                }
                store.requireOutsideProject(java.nio.file.Path.of(realProject));
                if (ApprovalPolicy.isMcpTool(name) && !isCurrentMcpProposal(proposal)) {
                    String reason = "MCP 配置、信任或运行状态在审批期间发生变化，永久授权未保存，本次操作未执行";
                    getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                            reason, elapsedMillis(start)));
                    return ToolOutput.failure("[PERMISSION] " + reason + "；请重新审批");
                }
                if (mcpCall) {
                    boolean saved = store.addIf(proposal, () -> isCurrentMcpProposal(proposal)
                            && approvalBinding.equals(currentMcpBinding(name))
                            && isCurrentMcpToolRegistration(name, mcpToolSnapshot));
                    if (!saved) {
                        String reason = "MCP 配置、信任或工具注册在规则发布期间发生变化，永久授权未保存，本次操作未执行";
                        getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                                reason, elapsedMillis(start)));
                        return ToolOutput.failure("[PERMISSION] " + reason + "；请重新审批");
                    }
                } else {
                    store.add(proposal);
                }
                if (mcpCall && (!isCurrentMcpProposal(proposal)
                        || !approvalBinding.equals(currentMcpBinding(name))
                        || !isCurrentMcpToolRegistration(name, mcpToolSnapshot))) {
                    store.remove(proposal.id(), proposal.projectRoot());
                    String reason = "MCP 配置、信任或工具注册在保存期间发生变化，永久授权已撤销，本次操作未执行";
                    getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                            reason, elapsedMillis(start)));
                    return ToolOutput.failure("[PERMISSION] " + reason + "；请重新审批");
                }
            } catch (IOException | IllegalArgumentException error) {
                getAuditLog().record(AuditLog.AuditEntry.denyByPolicy(name, argumentsJson,
                        "永久授权保存失败：" + error.getMessage(), elapsedMillis(start)));
                return ToolOutput.failure("[PERMISSION] 永久授权保存失败，操作未执行：" + error.getMessage());
            }
        }

        // Complete the same handler's approval transaction only after all binding checks.
        // For MCP this callback runs under the captured registration's read lock.
        Runnable commitApproval = () -> hitlHandler.commitApproval(request, result);
        // 批准（含修改参数或刚刚明确确认的永久规则）- 父类仍执行所有硬策略
        String effectiveArgs = result.effectiveArguments(argumentsJson);
        return executeParentTool(name, effectiveArgs, mcpToolSnapshot, approvalBinding, commitApproval);
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    public HitlHandler getHitlHandler() {
        return hitlHandler;
    }
}
