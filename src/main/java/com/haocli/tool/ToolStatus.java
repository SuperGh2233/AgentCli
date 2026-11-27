package com.haocli.tool;

import java.util.Locale;

/**
 * 一次工具调用的结果状态（docs/adr/ADR-0002-result-semantics.md）。除 {@link #SUCCESS} 以外都算失败，
 * 所以 {@code successful()} 的含义和以前一样，这里只是把失败分得更细。
 */
public enum ToolStatus {
    /** 工具执行成功。 */
    SUCCESS,
    /** 工具执行了但失败（文件不存在、命令非零退出、参数不合法等）。 */
    FAILED,
    /** 被策略、权限规则、人工确认、自动审查或无界面白名单拒绝，没有执行。 */
    DENIED,
    /** 只读工具超时；没有副作用，可以直接重试。 */
    TIMEOUT,
    /** 用户或调用方取消。 */
    CANCELLED,
    /** 没有执行：用户在确认框里选了跳过、回复被截断、启动前被拦下等。 */
    SKIPPED,
    /** 执行了但结果不确定（比如可能有副作用的工具超时）；不要盲目重试，先核实。 */
    UNKNOWN;

    public boolean successful() {
        return this == SUCCESS;
    }

    /** 对外 JSON 里的写法：success / failed / denied / timeout / cancelled / skipped / unknown。 */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
