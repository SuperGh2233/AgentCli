package com.haocli.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具接口：内置工具和进程内入口注册的自定义工具都实现它（PLAN-20261126 甲阶段）。
 *
 * <p>调度层（{@link ToolRegistry}）统一负责参数校验、策略检查、审计、不可信数据包装和大结果落盘；
 * 工具只管执行并如实报告状态。抛 {@link com.haocli.policy.PolicyException} 记为
 * {@link ToolStatus#DENIED}，抛其他异常记为 {@link ToolStatus#FAILED}。</p>
 */
public interface Tool {
    String name();

    String description();

    /** JSON Schema；调度层执行前按它校验参数。 */
    JsonNode parameters();

    /** 没有副作用、可以和同类调用并行。 */
    default boolean readOnly() {
        return false;
    }

    ToolOutput execute(ToolArguments args, ToolContext context) throws Exception;
}
