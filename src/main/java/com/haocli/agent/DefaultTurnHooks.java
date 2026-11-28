package com.haocli.agent;

import com.haocli.tool.ToolRegistry;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 内核外的组装点：按 {@link HarnessFeatures} 决定本次运行挂哪些模块、按什么顺序。
 * {@link AgentLoop} 只认 {@link TurnHook} 接口，不知道具体有哪些模块；ReAct、Plan 节点、子 Agent 和
 * 进程内运行时都从这里拿同一套默认模块，也可以在前面再加自己的。每次运行都要新建实例（模块可以带本轮状态），
 * 所以对外给的是 {@link Supplier}。
 */
final class DefaultTurnHooks {

    private DefaultTurnHooks() {
    }

    /** 默认模块，按调用顺序；关掉的模块不创建。 */
    static List<TurnHook> create(HarnessFeatures features, ToolRegistry toolRegistry) {
        HarnessFeatures f = features == null ? HarnessFeatures.ALL_ON : features;
        List<TurnHook> hooks = new ArrayList<>();
        if (f.lspDiagnostics()) {
            hooks.add(new LspDiagnosticsHook(toolRegistry));
        }
        com.haocli.memory.AutoCompactionManager.Layers layers = f.compactionLayers();
        if (layers.any()) {
            hooks.add(new ContextCompactionHook(layers));
        }
        // 能力类模块，不参与消融：去掉会改变工具语义（load_skill）或只是观察。
        hooks.add(new ResourceFailureHook(Path.of(toolRegistry.getProjectPath())));
        hooks.add(new SkillInjectionHook(toolRegistry));
        if (f.runawayReminder()) {
            hooks.add(new RunawayReminderHook());
        }
        return hooks;
    }

    static Supplier<List<TurnHook>> supplier(HarnessFeatures features, ToolRegistry toolRegistry) {
        return () -> create(features, toolRegistry);
    }

    /** 默认模块前面再加几个（比如观察类模块，要在压缩之前看到对话）。 */
    static Supplier<List<TurnHook>> withLeading(Supplier<List<TurnHook>> leading,
                                                HarnessFeatures features, ToolRegistry toolRegistry) {
        return () -> {
            List<TurnHook> hooks = new ArrayList<>(leading.get());
            hooks.addAll(create(features, toolRegistry));
            return hooks;
        };
    }
}
