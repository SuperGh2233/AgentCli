package com.haocli.agent;

import com.haocli.tool.ToolRegistry.ToolExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/** 观察工具结果里的资源类失败（缺目录、缺依赖等），只记日志，不改结果、不改对话。 */
final class ResourceFailureHook implements TurnHook {
    private static final Logger log = LoggerFactory.getLogger(ResourceFailureHook.class);
    private final ResourceFailureObserver observer;

    ResourceFailureHook(Path projectRoot) {
        this.observer = new ResourceFailureObserver(projectRoot);
    }

    @Override
    public List<ToolExecutionResult> beforeAppendToolResults(List<ToolExecutionResult> results, Context context) {
        observer.observe(results).ifPresent(notice -> log.info("Resource failure observation: {}", notice));
        return results;
    }
}
