package com.paicli.cli;

import com.paicli.runtime.input.DeliveryMode;
import com.paicli.runtime.input.InputAcceptance;
import com.paicli.runtime.input.TurnInputManager;
import com.paicli.runtime.input.TurnState;

import java.util.Objects;
import java.util.UUID;

/**
 * Phase2A 的输入模式与事件路由组件，尚未接入 Main。
 * 不读 Terminal/System.in，不开线程，不管理行编辑，不写 History，也不负责执行或结束任务。
 * 后续适配器必须由同一个 JLine 所有者完成读取、按键解码及调用 capture/submit/escape。
 * 审批线程只申请/释放输入模式；模式切换时适配器必须唤醒读取并保存草稿。
 */
public final class InputCoordinator {
    public enum Mode { IDLE_INPUT, RUNNING_INPUT, APPROVAL_INPUT, PLAN_REVIEW_INPUT }
    public enum Route { LEGACY, FORWARD, INTERACTION, ACCEPTED, STATUS, REJECTED, RETRY, IGNORED }

    /** text 原样返回，尤其 RETRY 时须保留供用户再次确认，不能自动重新提交。 */
    public record Result(Route route, String text, InputAcceptance acceptance,
                         TurnInputManager.Snapshot snapshot, String notice) { }

    /** 一次读取的模式和目标任务；私有构造且只能消费一次，避免跨模式误投或重复提交。 */
    public static final class ReadTicket {
        private final InputCoordinator coordinator;
        private final long revision;
        private final Mode mode;
        private final String turnId;
        private boolean consumed;

        private ReadTicket(InputCoordinator coordinator, long revision, Mode mode, String turnId) {
            this.coordinator = coordinator;
            this.revision = revision;
            this.mode = mode;
            this.turnId = turnId;
        }

        public Mode mode() { return mode; }
    }

    public final class Interaction implements AutoCloseable {
        private final Mode mode;

        private Interaction(Mode mode) { this.mode = mode; }

        @Override
        public void close() {
            synchronized (InputCoordinator.this) {
                if (interaction == this) {
                    interaction = null;
                    revision++;
                }
            }
        }
    }

    private static final String RECEIPT = "不代表模型已收到/执行；消息仅保存在本进程内存中。";
    private final String sessionId;
    private final TurnInputManager manager;
    private final boolean interactive;
    private final Thread owner = Thread.currentThread();
    private String turnId;
    private long revision;
    private Interaction interaction;

    public InputCoordinator(String sessionId, TurnInputManager manager, boolean interactive) {
        if (sessionId == null || sessionId.isBlank()) throw new IllegalArgumentException("sessionId 不能为空");
        this.sessionId = sessionId;
        this.manager = Objects.requireNonNull(manager, "manager");
        this.interactive = interactive;
    }

    public boolean enabled() { return interactive && manager.enabled(); }

    /** 任务状态仍由执行方管理；非交互或开关关闭时调用方继续走旧路径。 */
    public synchronized boolean beginRun(String expectedTurnId) {
        requireOwner();
        if (!enabled()) return false;
        var snapshot = manager.snapshot();
        if (turnId != null || interaction != null || expectedTurnId == null
                || !expectedTurnId.equals(snapshot.turnId()) || snapshot.state() != TurnState.RUNNING) {
            throw new IllegalStateException("只能绑定已进入 RUNNING 的当前任务，且不能覆盖现有输入模式");
        }
        turnId = expectedTurnId;
        revision++;
        return true;
    }

    /** 不清空、不交付、不自动开启下一任务；Phase3 接入前必须报告未交付消息。 */
    public synchronized Result endRun(String expectedTurnId) {
        requireOwner();
        if (turnId == null || !turnId.equals(expectedTurnId) || interaction != null) {
            throw new IllegalStateException("任务不匹配或审批/计划审阅尚未结束");
        }
        turnId = null;
        revision++;
        return result(Route.STATUS, "", null, "输入接收已结束。" + RECEIPT);
    }

    /** 可由任务线程调用；只切换模式，不读取终端或在任务线程调用 JLine。 */
    public synchronized Interaction enterInteraction(Mode mode) {
        if (!enabled() || interaction != null
                || (mode != Mode.APPROVAL_INPUT && mode != Mode.PLAN_REVIEW_INPUT)) {
            throw new IllegalStateException("当前不能开始审批/计划审阅输入");
        }
        interaction = new Interaction(mode);
        revision++;
        return interaction;
    }

    public synchronized ReadTicket capture() {
        requireOwner();
        Mode mode = interaction != null ? interaction.mode
                : turnId == null ? Mode.IDLE_INPUT : Mode.RUNNING_INPUT;
        return new ReadTicket(this, revision, mode, turnId);
    }

    public synchronized Result submit(ReadTicket ticket, String line) {
        return route(ticket, Objects.requireNonNull(line, "line"), false);
    }

    /** 仅接收 JLine 已识别的独立 Esc；方向键、Tab、粘贴和 Ctrl+C 留给原有输入层。 */
    public synchronized Result escape(ReadTicket ticket) {
        return route(ticket, "\033", true);
    }

    private Result route(ReadTicket ticket, String text, boolean escape) {
        requireOwner();
        if (!enabled()) return result(Route.LEGACY, text, null, "");
        if (ticket == null || ticket.coordinator != this || ticket.consumed || ticket.revision != revision) {
            return result(Route.RETRY, text, null, "输入模式或任务已变化，请保留原输入并重新确认。");
        }
        ticket.consumed = true;
        if (ticket.mode == Mode.APPROVAL_INPUT || ticket.mode == Mode.PLAN_REVIEW_INPUT) {
            return result(Route.INTERACTION, text, null, "输入属于当前审批/计划审阅。");
        }
        if (ticket.mode == Mode.IDLE_INPUT) return result(Route.FORWARD, text, null, "");
        String content = text;
        DeliveryMode deliveryMode = escape ? DeliveryMode.INTERRUPT : DeliveryMode.STEER;
        if (escape) {
            content = "Esc";
        } else {
            String stripped = text.strip();
            if (stripped.isEmpty()) return result(Route.IGNORED, text, null, "");
            if (stripped.startsWith("/")) {
                int split = 0;
                while (split < stripped.length() && !Character.isWhitespace(stripped.charAt(split))) split++;
                String command = stripped.substring(0, split);
                content = stripped.substring(split).strip();
                if (command.equals("/inputs") && content.isEmpty()) {
                    return result(Route.STATUS, text, null, "当前输入状态。" + RECEIPT);
                }
                if (command.equals("/cancel") && content.isEmpty()) {
                    deliveryMode = DeliveryMode.INTERRUPT;
                    content = "/cancel";
                } else if ((command.equals("/queue") || command.equals("/steer")) && !content.isEmpty()) {
                    deliveryMode = command.equals("/queue") ? DeliveryMode.ENQUEUE : DeliveryMode.STEER;
                } else {
                    return result(Route.REJECTED, text, null, "运行中支持 /steer 文本、/queue 文本、/inputs、/cancel。");
                }
            }
        }
        InputAcceptance acceptance = manager.accept(sessionId, ticket.turnId, UUID.randomUUID().toString(),
                "cli", content, deliveryMode);
        return result(acceptance.accepted() ? Route.ACCEPTED : Route.REJECTED, text, acceptance,
                (acceptance.accepted() ? "已接收。" : "未接收：" + acceptance.code() + "。") + RECEIPT);
    }

    private Result result(Route route, String text, InputAcceptance acceptance, String notice) {
        var snapshot = manager.snapshot();
        if (route == Route.STATUS) {
            notice += " 待交付 " + snapshot.pendingCount() + " 条，已保留待发 " + snapshot.reservedCount()
                    + " 条，排队 " + snapshot.queuedCount() + " 条"
                    + (snapshot.queuePaused() ? "（队列已暂停）。" : "。");
        }
        return new Result(route, text, acceptance, snapshot, notice);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("输入事件只能由唯一的输入所有者处理");
    }
}
