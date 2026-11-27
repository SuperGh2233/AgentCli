package com.haocli.agent;

import com.haocli.llm.LlmClient;
import com.haocli.lsp.LspDiagnosticReport;
import com.haocli.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 把编辑文件后积累的 LSP 诊断在下一次模型请求前加进对话。 */
final class LspDiagnosticsHook implements TurnHook {
    private static final Logger log = LoggerFactory.getLogger(LspDiagnosticsHook.class);
    private final ToolRegistry toolRegistry;

    LspDiagnosticsHook(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void beforeModelRequest(Context context) {
        LspDiagnosticReport report = toolRegistry.flushPendingLspDiagnostics();
        if (report == null || report.isEmpty()) {
            return;
        }
        context.conversation().append(LlmClient.Message.user(report.promptText()), "lsp_diagnostics");
        context.notice(report.displayText());
        log.info("Injected LSP diagnostics into ReAct conversation");
    }
}
