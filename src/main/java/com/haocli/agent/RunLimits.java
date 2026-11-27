package com.haocli.agent;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Function;

/**
 * 一轮任务的硬上限：模型请求次数、总 Token、墙钟时间（从开始到现在的实际耗时）。
 * 任何一项到了，内核就关掉工具、让模型整理已完成的部分，状态是 partial。
 *
 * <p>交互命令行有人看着，默认 {@link #UNLIMITED}；无人值守入口（{@code -p}、Runtime API、后台任务）
 * 默认用 {@link #unattended(Function)}：50 次请求、200 万 Token、30 分钟，环境变量可以改。
 *
 * <p>时间不是硬墙钟：它在每次模型请求之前检查，正在进行的那次请求或那批工具不会被打断。工具自己有超时
 * （单条命令默认 60 秒、一批工具 90 秒），所以最坏情况是上限再加一次模型请求和一批工具的时间。
 *
 * <p>只接到 ReAct 内核（{@link Agent#setRunLimits} → {@code AgentLoop}）。Plan、Team 还用旧的
 * {@code AgentBudget.fromSystemProperties()}，不受这里约束；目前无人值守入口都只走 ReAct，
 * Plan / Team 迁进内核后（路线丙阶段）自动覆盖。
 *
 * @param timeout null 表示不限时间
 */
public record RunLimits(int maxModelRequests, int maxTotalTokens, Duration timeout) {

    public static final int DEFAULT_UNATTENDED_MAX_TURNS = 50;
    public static final int DEFAULT_UNATTENDED_MAX_TOKENS = 2_000_000;
    public static final Duration DEFAULT_UNATTENDED_TIMEOUT = Duration.ofMinutes(30);

    public static final String ENV_MAX_TURNS = "HAOCLI_UNATTENDED_MAX_TURNS";
    public static final String ENV_MAX_TOKENS = "HAOCLI_UNATTENDED_MAX_TOKENS";
    public static final String ENV_TIMEOUT = "HAOCLI_UNATTENDED_TIMEOUT";

    public static final RunLimits UNLIMITED =
            new RunLimits(AgentBudget.UNLIMITED_ITERATIONS, Integer.MAX_VALUE, null);

    public RunLimits {
        if (maxModelRequests <= 0) {
            throw new IllegalArgumentException("模型请求次数上限必须是正整数");
        }
        if (maxTotalTokens <= 0) {
            throw new IllegalArgumentException("Token 上限必须是正整数");
        }
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("时间上限必须大于 0");
        }
    }

    /** 无人值守的默认上限，环境变量（{@link #ENV_MAX_TURNS} 等）可以改；取值不合法时报错，不悄悄用默认值。 */
    public static RunLimits unattended(Function<String, String> env) {
        Function<String, String> source = env == null ? key -> null : env;
        String turns = source.apply(ENV_MAX_TURNS);
        String tokens = source.apply(ENV_MAX_TOKENS);
        String timeout = source.apply(ENV_TIMEOUT);
        return new RunLimits(
                isBlank(turns) ? DEFAULT_UNATTENDED_MAX_TURNS : parsePositiveInt(turns, ENV_MAX_TURNS),
                isBlank(tokens) ? DEFAULT_UNATTENDED_MAX_TOKENS : parsePositiveInt(tokens, ENV_MAX_TOKENS),
                isBlank(timeout) ? DEFAULT_UNATTENDED_TIMEOUT : parseDuration(timeout, ENV_TIMEOUT));
    }

    public static RunLimits unattendedFromEnvironment() {
        return unattended(System::getenv);
    }

    public boolean hasTimeout() {
        return timeout != null;
    }

    public RunLimits withMaxModelRequests(int value) {
        return new RunLimits(value, maxTotalTokens, timeout);
    }

    public RunLimits withMaxTotalTokens(int value) {
        return new RunLimits(maxModelRequests, value, timeout);
    }

    public RunLimits withTimeout(Duration value) {
        return new RunLimits(maxModelRequests, maxTotalTokens, value);
    }

    /** 正整数；{@code name} 是参数名或环境变量名，只用于报错。 */
    public static int parsePositiveInt(String raw, String name) {
        try {
            int value = Integer.parseInt(raw.trim().replace("_", ""));
            if (value > 0) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // 下面统一报错
        }
        throw new IllegalArgumentException(name + " 必须是正整数: " + raw);
    }

    /** 时长：纯数字按秒算，也可以带单位 s / m / h，比如 {@code 90}、{@code 90s}、{@code 30m}、{@code 2h}。 */
    public static Duration parseDuration(String raw, String name) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        long multiplier = 1;
        if (value.endsWith("h")) {
            multiplier = 3600;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("m")) {
            multiplier = 60;
            value = value.substring(0, value.length() - 1);
        } else if (value.endsWith("s")) {
            value = value.substring(0, value.length() - 1);
        }
        try {
            long amount = Long.parseLong(value.trim());
            if (amount > 0) {
                return Duration.ofSeconds(Math.multiplyExact(amount, multiplier));
            }
        } catch (NumberFormatException | ArithmeticException ignored) {
            // 下面统一报错
        }
        throw new IllegalArgumentException(name + " 必须是正的时长（比如 90、90s、30m、2h）: " + raw);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
