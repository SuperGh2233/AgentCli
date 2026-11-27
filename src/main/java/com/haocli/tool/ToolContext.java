package com.haocli.tool;

import java.nio.file.Path;

/** 调度层交给工具的运行环境。 */
public interface ToolContext {
    Path projectRoot();

    /** 走 PathGuard；越界抛 {@link com.haocli.policy.PolicyException}，调度层记为 DENIED。 */
    Path resolvePath(String raw);

    boolean cancelled();
}
