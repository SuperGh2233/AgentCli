package com.haocli.tool;

import com.haocli.llm.LlmClient;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Typed result returned by a tool implementation.
 *
 * <p>{@code status} 由做事的一方如实报告（docs/adr/ADR-0002-result-semantics.md）；{@link #successful()} 按状态算，
 * 除 {@link ToolStatus#SUCCESS} 以外都是失败。旧的 boolean 构造函数保留：true 记 SUCCESS，false 记 FAILED。</p>
 *
 * <p>{@code discoveredUrls} is deliberately separate from the human-readable
 * text. Only tools that own a structured URL source (currently the built-in
 * {@code web_search} provider) may populate it; callers must never recover URL
 * authority by scraping {@link #text()}.</p>
 *
 * @param retryable 失败后原样重试是否可能成功（比如网络抖动）；成功时没有意义
 */
public record ToolOutput(String text,
                         List<LlmClient.ContentPart> imageParts,
                         ToolStatus status,
                         List<String> discoveredUrls,
                         FailureCode failureCode,
                         boolean retryable) {
    /**
     * 不成功的原因。前几个是普通失败；DENY_ 开头的是拒绝（status = DENIED）由哪一层确定性检查做出，
     * 写进事件和 -p 的 JSON（{@code reason}），方便统计和以后把检查合成一条链时核对行为没变。
     */
    public enum FailureCode {
        NONE, EDIT_SOURCE_NOT_FOUND, INVALID_ARGUMENT, NOT_FOUND,
        /** PathGuard：路径越界、符号链接逃逸、无法解析。 */
        DENY_PATH,
        /** CommandGuard 黑名单，或要求沙箱但沙箱不可用。 */
        DENY_COMMAND,
        /** 资源上限，比如单次写入超过 5MB。 */
        DENY_LIMIT,
        /** 本轮工具策略（TurnToolPolicy）：网址来源、标题输入、浏览器租约等。 */
        DENY_TURN_POLICY,
        /** 无人值守通道（-p、Runtime API、SDK）的白名单。 */
        DENY_UNATTENDED,
        /** 权限规则、MCP 注册或信任状态变化。 */
        DENY_PERMISSION,
        /** auto 模式的审查模型没有放行。 */
        DENY_AUTO_REVIEW,
        /** 用户在确认框里拒绝。 */
        DENY_HUMAN,
        /** 网络策略（协议、主机、限流前的检查）。 */
        DENY_NETWORK,
        /** 其他确定性策略（浏览器会话检查等）。 */
        DENY_POLICY
    }

    public ToolOutput {
        status = status == null ? ToolStatus.FAILED : status;
        failureCode = failureCode == null ? FailureCode.NONE : failureCode;
        text = text == null ? "" : text;
        imageParts = imageParts == null ? List.of() : List.copyOf(imageParts);
        discoveredUrls = discoveredUrls == null
                ? List.of()
                : discoveredUrls.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(url -> !url.isEmpty())
                .distinct()
                .toList();
    }

    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts,
                      boolean successful, List<String> discoveredUrls, FailureCode failureCode) {
        this(text, imageParts, successful ? ToolStatus.SUCCESS : ToolStatus.FAILED, discoveredUrls, failureCode, false);
    }

    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts,
                      boolean successful, List<String> discoveredUrls) {
        this(text, imageParts, successful, discoveredUrls, FailureCode.NONE);
    }

    /** Backward-compatible constructor for successful text/image tools. */
    public ToolOutput(String text, List<LlmClient.ContentPart> imageParts) {
        this(text, imageParts, true, List.of());
    }

    public static ToolOutput text(String text) {
        return new ToolOutput(text, List.of(), true, List.of());
    }

    public static ToolOutput failure(String text) {
        return new ToolOutput(text, List.of(), false, List.of());
    }

    public static ToolOutput failure(FailureCode code, String text) {
        return new ToolOutput(text, List.of(), ToolStatus.FAILED, List.of(), code, false);
    }

    public static ToolOutput failure(String text, List<LlmClient.ContentPart> imageParts) {
        return new ToolOutput(text, imageParts, false, List.of());
    }

    /** 被策略、权限、人工确认、自动审查或白名单拒绝。 */
    public static ToolOutput denied(String text) {
        return denied(FailureCode.DENY_POLICY, text);
    }

    /** 被确定性检查拒绝；code 说明是哪一层（DENY_*）。 */
    public static ToolOutput denied(FailureCode code, String text) {
        return new ToolOutput(text, List.of(), ToolStatus.DENIED, List.of(), code, false);
    }

    /** 没有执行（用户选了跳过、启动前被拦下等）。 */
    public static ToolOutput skipped(String text) {
        return of(ToolStatus.SKIPPED, text);
    }

    public static ToolOutput cancelled(String text) {
        return of(ToolStatus.CANCELLED, text);
    }

    /** 执行了但结果不确定，比如可能有副作用的命令超时被强制终止。 */
    public static ToolOutput unknown(String text) {
        return of(ToolStatus.UNKNOWN, text);
    }

    public static ToolOutput of(ToolStatus status, String text) {
        return new ToolOutput(text, List.of(), status, List.of(), FailureCode.NONE, false);
    }

    public static ToolOutput discovered(String text, Collection<String> discoveredUrls) {
        return new ToolOutput(text, List.of(), true,
                discoveredUrls == null ? List.of() : List.copyOf(discoveredUrls));
    }

    public boolean successful() {
        return status.successful();
    }

    /** 换文字，状态、图片、网址来源、失败码不变（比如浏览器结果脱敏后）。 */
    public ToolOutput withText(String newText) {
        return new ToolOutput(newText, imageParts, status, discoveredUrls, failureCode, retryable);
    }

    /** 换状态，其余不变；成功变失败时清掉网址来源（失败结果不能授予新网址）。 */
    public ToolOutput withStatus(ToolStatus newStatus) {
        List<String> urls = newStatus == ToolStatus.SUCCESS ? discoveredUrls : List.of();
        return new ToolOutput(text, imageParts, newStatus, urls, failureCode, retryable);
    }

    public boolean hasImageParts() {
        return !imageParts.isEmpty();
    }
}
