package com.haocli.agent;

import com.haocli.tool.ToolRegistry.ToolExecutionResult;

import java.util.List;

/**
 * 挂在 ReAct 内核上的模块（最小内核原则：上下文压缩、诊断注入、Skill 注入、提醒都通过它接入，实现不写进循环）。
 *
 * <p>内核只在固定位置调用：发模型请求之前、工具结果追加进对话之前和之后、模型报上下文超长时。按注册顺序调用。
 * 每次运行新建一组实例，模块可以在实例里保存本轮状态。</p>
 */
interface TurnHook {

    /** 发模型请求之前（检查预算之前）。可以往对话里追加消息或压缩对话。 */
    default void beforeModelRequest(Context context) {
    }

    /**
     * 本批工具结果追加进对话之前，可以改写结果（比如 load_skill）；返回要追加的结果，不能改变条数和顺序，
     * 否则工具调用和结果对不上。
     */
    default List<ToolExecutionResult> beforeAppendToolResults(List<ToolExecutionResult> results, Context context) {
        return results;
    }

    /** 本批工具结果已经追加进对话之后。 */
    default void afterToolResults(List<ToolExecutionResult> results, Context context) {
    }

    /** 模型报上下文超长时调用（每轮最多一次）；返回 true 表示对话变小了，内核会重试这次请求。 */
    default boolean onContextOverflow(Context context) {
        return false;
    }

    /** 内核交给模块的能力：只能改对话、发提示，不能跳过内核的取消、预算和策略检查。 */
    interface Context {
        ConversationContext conversation();

        int compressionTriggerTokens();

        /** 发一条提示事件；终端按带颜色的原文打印。 */
        void notice(String styledLine);
    }
}
