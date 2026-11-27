package com.paicli.tool;

import com.paicli.llm.LlmClient;

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
    public enum FailureCode { NONE, EDIT_SOURCE_NOT_FOUND, INVALID_ARGUMENT, NOT_FOUND }

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
        return of(ToolStatus.DENIED, text);
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
