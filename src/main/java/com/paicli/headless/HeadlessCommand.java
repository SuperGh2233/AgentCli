package com.paicli.headless;

import com.paicli.config.PaiCliConfig;
import com.paicli.history.ConversationLedger;
import com.paicli.llm.LlmClient;
import com.paicli.llm.LlmClientFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 命令行入口：{@code java -jar paicli.jar -p "任务"}。解析参数、必要时从标准输入读任务、加载模型配置，
 * 再交给 {@link HeadlessRunner}。返回进程退出码。
 */
public final class HeadlessCommand {

    private HeadlessCommand() {
    }

    public static boolean isHeadless(String[] args) {
        return HeadlessOptions.isHeadless(args);
    }

    public static int run(String[] args, InputStream stdin, PrintStream out, PrintStream err) {
        HeadlessOptions options;
        try {
            options = HeadlessOptions.parse(args);
        } catch (IllegalArgumentException e) {
            err.println("❌ " + e.getMessage());
            err.println(HeadlessOptions.usage());
            return HeadlessRunner.EXIT_USAGE;
        }
        if (options.prompt() == null) {
            try {
                options = options.withPrompt(new String(stdin.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                err.println("❌ 读取标准输入失败: " + e.getMessage());
                return HeadlessRunner.EXIT_USAGE;
            }
        }
        if (options.prompt() == null || options.prompt().isBlank()) {
            err.println("❌ 任务为空：在 -p 后面写任务，或通过标准输入传入");
            return HeadlessRunner.EXIT_USAGE;
        }

        LlmClient llm = LlmClientFactory.createFromConfig(PaiCliConfig.load());
        if (llm == null) {
            err.println("❌ 未找到可用的 API Key（见 .env.example）");
            return HeadlessRunner.EXIT_USAGE;
        }
        ConversationLedger ledger;
        try {
            ledger = ConversationLedger.openDefault(Path.of(System.getProperty("user.home")));
        } catch (IOException e) {
            err.println("⚠️ 会话记录不可用，本次不保存: " + e.getMessage());
            ledger = ConversationLedger.disabled();
        }
        return HeadlessRunner.run(options, llm, Path.of(".").toAbsolutePath().normalize(), ledger, out, err);
    }
}
