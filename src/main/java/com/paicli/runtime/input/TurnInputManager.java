package com.paicli.runtime.input;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.paicli.runtime.input.InputAcceptance.Code.*;

/**
 * 单个会话的内存输入管理器。所有状态和队列共用本对象的监视器；不读终端、不写 History、
 * 不发送模型请求，也不执行取消。调用方只在安全点取消息，并在请求提交后 commit。
 * 一次只允许占用一批消息；未发送的批次才能 requeue，不能据超时猜测请求未送达。
 * 发送结果不明时由调用方显式 markDeliveryUnknown，保留不确定状态且不重新入队。
 * 去重与状态查询在本管理器生命周期内保留，未实现落盘和跨进程恢复。
 */
public final class TurnInputManager {
    public static final String ENABLED_PROPERTY = "paicli.steering.enabled";

    /** 每 Turn 累计 Steer 数、待启动 Queue 数、每条内容的 UTF-8 字节数。 */
    public record Limits(int maxSteersPerTurn, int maxQueued, int maxContentBytes) {
        public Limits {
            if (maxSteersPerTurn <= 0 || maxQueued <= 0 || maxContentBytes <= 0) {
                throw new IllegalArgumentException("输入上限必须为正数");
            }
        }

        public static Limits defaults() {
            return new Limits(20, 50, 16 * 1024);
        }
    }

    public enum Finalization { CONTINUE, FINALIZE }

    public record Snapshot(String turnId, TurnState state, int pendingCount,
                           int reservedCount, int queuedCount, boolean queuePaused) {
    }

    public record InputView(RuntimeInput input, InputStatus status,
                            String deliveryTurnId, String requestId) {
    }

    /** 只能由管理器创建的交付批次；旧批次、其他管理器的批次不能再使用。 */
    public static final class Delivery {
        private final String turnId;
        private final List<RuntimeInput> inputs;

        private Delivery(String turnId, List<RuntimeInput> inputs) {
            this.turnId = turnId;
            this.inputs = List.copyOf(inputs);
        }

        public String turnId() { return turnId; }
        public List<RuntimeInput> inputs() { return inputs; }
    }

    private final String sessionId;
    private final boolean enabled;
    private final Limits limits;
    private final ArrayDeque<RuntimeInput> pending = new ArrayDeque<>();
    private final ArrayDeque<RuntimeInput> queued = new ArrayDeque<>();
    private final Map<String, InputView> messages = new HashMap<>();
    private final Set<String> usedTurnIds = new HashSet<>();
    private String turnId;
    private TurnState state = TurnState.IDLE;
    private long sequence;
    private int acceptedSteers;
    private boolean queuePaused;
    private Delivery reserved;

    /** 默认关闭；系统属性仅在构造时读取，构造管理器不会启用任何 Agent Loop。 */
    public TurnInputManager(String sessionId) {
        this(sessionId, Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false")),
                Limits.defaults());
    }

    public TurnInputManager(String sessionId, boolean enabled, Limits limits) {
        requireText(sessionId, "sessionId");
        this.sessionId = sessionId;
        this.enabled = enabled;
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public boolean enabled() { return enabled; }

    public synchronized Snapshot snapshot() {
        return new Snapshot(turnId, state, pending.size(),
                reserved == null ? 0 : reserved.inputs.size(), queued.size(), queuePaused);
    }

    public synchronized Optional<InputView> input(String messageId) {
        return Optional.ofNullable(messages.get(messageId));
    }

    /** 输入线程只保管消息；身份、接收窗口、去重、限额和 sequence 在同一锁内处理。 */
    public synchronized InputAcceptance accept(String sessionId, String expectedTurnId,
                                                String messageId, String source,
                                                String content, DeliveryMode mode) {
        if (!enabled) return reject(DISABLED);
        if (blank(sessionId) || blank(expectedTurnId) || blank(messageId)
                || blank(source) || blank(content) || mode == null) {
            return reject(INVALID_INPUT);
        }
        if (!this.sessionId.equals(sessionId) || !Objects.equals(turnId, expectedTurnId)) {
            return reject(STALE_TURN);
        }
        InputView prior = messages.get(messageId);
        if (prior != null) return new InputAcceptance(DUPLICATE, prior.input(), prior.status());
        if (!accepting()) return reject(STALE_TURN);
        if (content.getBytes(StandardCharsets.UTF_8).length > limits.maxContentBytes()) {
            return reject(TOO_LARGE);
        }
        if (mode == DeliveryMode.STEER && acceptedSteers >= limits.maxSteersPerTurn()) {
            return reject(STEER_LIMIT);
        }
        if (mode == DeliveryMode.ENQUEUE && queued.size() >= limits.maxQueued()) {
            return reject(QUEUE_FULL);
        }
        RuntimeInput input = new RuntimeInput(messageId, sessionId, expectedTurnId,
                ++sequence, source, content, mode, Instant.now());
        InputStatus status;
        switch (mode) {
            case STEER -> {
                acceptedSteers++;
                pending.addLast(input);
                status = InputStatus.PENDING_DELIVERY;
            }
            case ENQUEUE -> {
                queued.addLast(input);
                status = InputStatus.ACCEPTED;
            }
            case INTERRUPT -> {
                state = TurnState.CANCELLING;
                queuePaused = true;
                status = InputStatus.INTERRUPT_REQUESTED;
            }
            default -> throw new IllegalStateException("未知交付方式");
        }
        messages.put(messageId, new InputView(input, status,
                mode == DeliveryMode.ENQUEUE ? null : turnId, null));
        return new InputAcceptance(ACCEPTED, input, status);
    }

    /** 开始由调用方提供初始输入的任务；已有 Queue 保持原样。 */
    public synchronized void startTurn(String newTurnId) {
        checkNewTurn(newTurnId);
        begin(newTurnId);
    }

    /**
     * 前一 Turn 收尾完成后才可取 Queue；每次只启动队首一条。
     * 取出的消息先变成新 Turn 的待交付输入，仍需 reserve/commit，不提前声称已发送。
     */
    public synchronized Optional<RuntimeInput> startNextQueuedTurn(String newTurnId) {
        requireEnabled();
        if (!terminal() || queuePaused || queued.isEmpty()) return Optional.empty();
        checkNewTurn(newTurnId);
        begin(newTurnId);
        RuntimeInput input = queued.removeFirst();
        pending.addLast(input);
        update(input, InputStatus.PENDING_DELIVERY, null);
        return Optional.of(input);
    }

    public synchronized void markRunning(String expectedTurnId) {
        checkTurn(expectedTurnId);
        if (state != TurnState.STARTING && state != TurnState.WAITING_APPROVAL) {
            throw new IllegalStateException("只有启动或审批结束时才能进入 RUNNING");
        }
        state = TurnState.RUNNING;
    }

    public synchronized void waitForApproval(String expectedTurnId) {
        checkState(expectedTurnId, TurnState.RUNNING);
        requireNoReservation();
        state = TurnState.WAITING_APPROVAL;
    }

    /** 仅由执行线程在安全点调用；包含新 Turn 的 Queue 初始输入和本轮 Steer。 */
    public synchronized Optional<Delivery> reserveForRequest(String expectedTurnId) {
        checkState(expectedTurnId, TurnState.RUNNING);
        requireNoReservation();
        if (pending.isEmpty()) return Optional.empty();
        reserved = new Delivery(turnId, List.copyOf(pending));
        pending.clear();
        for (RuntimeInput input : reserved.inputs) update(input, InputStatus.DELIVERY_RESERVED, null);
        return Optional.of(reserved);
    }

    /**
     * 调用方确认请求已提交后调用。中断请求不会抹掉已经提交的事实。
     * INCLUDED_IN_REQUEST 不表示模型已经执行；发送结果不明时不得 requeue 来重发。
     */
    public synchronized void commit(Delivery delivery, String requestId) {
        requireText(requestId, "requestId");
        checkReservation(delivery);
        for (RuntimeInput input : delivery.inputs) update(input, InputStatus.INCLUDED_IN_REQUEST, requestId);
        reserved = null;
    }

    /**
     * 仅由调用方确认发送结果不明时调用，不把普通网络异常自动归为不确定。
     * requestId 是本次请求的关联 ID，不表示服务端已确认接收。释放占用但不重发，
     * 保留 DELIVERY_UNCERTAIN 供查询；任务仍需由调用方失败或取消收尾。
     */
    public synchronized void markDeliveryUnknown(Delivery delivery, String requestId) {
        requireText(requestId, "requestId");
        checkReservation(delivery);
        for (RuntimeInput input : delivery.inputs) update(input, InputStatus.DELIVERY_UNCERTAIN, requestId);
        reserved = null;
    }

    /** 请求尚未发出时退回整批，放在占用后新接收的消息前面；旧批次不能重复使用。 */
    public synchronized void requeue(Delivery delivery) {
        checkReservation(delivery);
        List<RuntimeInput> inputs = delivery.inputs;
        for (int i = inputs.size() - 1; i >= 0; i--) {
            RuntimeInput input = inputs.get(i);
            pending.addFirst(input);
            update(input, InputStatus.PENDING_DELIVERY, null);
        }
        reserved = null;
    }

    /** 和 accept 共用锁：消息要么阻止结束，要么明确被拒绝为 STALE_TURN。 */
    public synchronized Finalization finalizeOrContinue(String expectedTurnId) {
        checkState(expectedTurnId, TurnState.RUNNING);
        if (!pending.isEmpty() || reserved != null) return Finalization.CONTINUE;
        state = TurnState.FINALIZING;
        return Finalization.FINALIZE;
    }

    public synchronized void completeTurn(String expectedTurnId) {
        checkState(expectedTurnId, TurnState.FINALIZING);
        state = TurnState.COMPLETED;
    }

    /** 仅在执行方确认已停止后调用；先提交、退回或标记发送结果不明，解除批次占用。 */
    public synchronized void finishCancellation(String expectedTurnId) {
        checkState(expectedTurnId, TurnState.CANCELLING);
        stop(TurnState.CANCELLED);
    }

    public synchronized void failTurn(String expectedTurnId) {
        checkTurn(expectedTurnId);
        if (terminal() || state == TurnState.IDLE) throw new IllegalStateException("任务已结束");
        stop(TurnState.FAILED);
    }

    /** 取消或失败后必须显式恢复 Queue；恢复不会自动启动任务。 */
    public synchronized void resumeQueue() {
        requireEnabled();
        if (!terminal()) throw new IllegalStateException("必须先确认前一任务已结束");
        queuePaused = false;
    }

    private void stop(TurnState terminalState) {
        requireNoReservation();
        for (RuntimeInput input : pending) update(input, InputStatus.CANCELLED, null);
        pending.clear();
        queuePaused = true;
        state = terminalState;
    }

    private void checkNewTurn(String newTurnId) {
        requireEnabled();
        requireText(newTurnId, "turnId");
        if (state != TurnState.IDLE && !terminal()) throw new IllegalStateException("前一任务尚未结束");
        if (usedTurnIds.contains(newTurnId)) throw new IllegalArgumentException("turnId 不得重复使用");
    }

    private void begin(String newTurnId) {
        usedTurnIds.add(newTurnId);
        turnId = newTurnId;
        acceptedSteers = 0;
        state = TurnState.STARTING;
    }

    private boolean accepting() {
        return state == TurnState.STARTING || state == TurnState.RUNNING
                || state == TurnState.WAITING_APPROVAL;
    }

    private boolean terminal() {
        return state == TurnState.COMPLETED || state == TurnState.CANCELLED || state == TurnState.FAILED;
    }

    private void checkReservation(Delivery delivery) {
        if (delivery == null || reserved != delivery) throw new IllegalStateException("交付批次已失效或不属于本管理器");
    }

    private void requireNoReservation() {
        if (reserved != null) throw new IllegalStateException("请先提交、退回或标记已占用消息的发送结果不明");
    }

    private void update(RuntimeInput input, InputStatus status, String requestId) {
        messages.put(input.messageId(), new InputView(input, status, turnId, requestId));
    }

    private void checkTurn(String expectedTurnId) {
        requireEnabled();
        requireText(expectedTurnId, "expectedTurnId");
        if (turnId == null || !turnId.equals(expectedTurnId)) throw new IllegalStateException("Turn 不匹配");
    }

    private void checkState(String expectedTurnId, TurnState expectedState) {
        checkTurn(expectedTurnId);
        if (state != expectedState) throw new IllegalStateException("当前状态不允许此操作: " + state);
    }

    private void requireEnabled() {
        if (!enabled) throw new IllegalStateException("Steering 尚未启用");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static void requireText(String value, String field) {
        if (blank(value)) throw new IllegalArgumentException(field + " 不能为空");
    }

    private static InputAcceptance reject(InputAcceptance.Code code) {
        return new InputAcceptance(code, null, null);
    }
}
