package com.haocli.headless;

import com.haocli.config.HaoCliConfig;
import com.haocli.history.ConversationLedger;
import com.haocli.llm.LlmClient;
import com.haocli.llm.LlmClientFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 命令行入口：{@code java -jar haocli.jar -p "任务"}。解析参数、必要时从标准输入读任务、加载模型配置，
 * 再交给 {@link HeadlessRunner}。返回进程退出码。
 */
public final class HeadlessCommand {

    private HeadlessCommand() {
    }

    /**
     * 取出 {@code --harness-disable <模块,...>}（消融实验用），校验后写进系统属性，交给 Agent 读取；
     * 返回去掉这个参数后的其余参数。只能出现一次。
     */
    static String[] applyHarnessDisable(String[] args) {
        java.util.List<String> rest = new java.util.ArrayList<>();
        String value = null;
        for (int i = 0; i < args.length; i++) {
            if ("--harness-disable".equals(args[i])) {
                if (value != null) {
                    throw new IllegalArgumentException("--harness-disable 只能出现一次");
                }
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("--harness-disable 缺少取值");
                }
                value = args[++i];
                com.haocli.agent.HarnessFeatures.parse(value);
            } else {
                rest.add(args[i]);
            }
        }
        if (value != null) {
            System.setProperty(com.haocli.agent.HarnessFeatures.PROPERTY, value);
        }
        return rest.toArray(new String[0]);
    }

    public static boolean isHeadless(String[] args) {
        return HeadlessOptions.isHeadless(args);
    }

    public static int run(String[] args, InputStream stdin, PrintStream out, PrintStream err) {
        HeadlessOptions options;
        try {
            args = applyHarnessDisable(args);
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

        LlmClient llm = LlmClientFactory.createFromConfig(HaoCliConfig.load());
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
