package com.paicli.cli;

import com.paicli.util.TerminalInputLock;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 审批/计划输入请求桥。worker 只发请求并等待；构造本对象的 UI 线程独占注入的读取器。
 * 不创建 Terminal、Reader 或线程；通过只置位回调唤醒 UI，由同一个 JLine 所有者调用 serviceNext。
 * 当前只由 opt-in 的交互式 inline ReAct 路径绑定；Plan/Team 保留旧审批路径。
 */
public final class TerminalInputGateway implements AutoCloseable {
    public enum Kind {
        APPROVAL_CHOICE, REJECTION_REASON, MODIFIED_ARGUMENTS, APPROVAL_SCOPE,
        PLAN_REVIEW, PLAN_SUPPLEMENT;

        private InputCoordinator.Mode mode() {
            return this == PLAN_REVIEW || this == PLAN_SUPPLEMENT
                    ? InputCoordinator.Mode.PLAN_REVIEW_INPUT : InputCoordinator.Mode.APPROVAL_INPUT;
        }
    }

    public enum Failure { DISABLED, BUSY, EOF, INTERRUPTED, TIMEOUT, CLOSED, READ_ERROR, RETRY }
    public enum DispatchStatus { IDLE, DELIVERED, FAILED, RETRY }
    public record Request(String id, Kind kind, String prompt) { }
    /** RETRY 的 text 保留原文供 UI 再次确认；禁止自动重新提交。 */
    public record Dispatch(DispatchStatus status, String requestId, String text, Failure failure) {
        public Dispatch(DispatchStatus status, String requestId, String text) {
            this(status, requestId, text, null);
        }
    }

    public enum InteractionState { NONE, BETWEEN_SUBPROMPTS, PENDING }
    public record UiState(InteractionState state, Request request) { }

    /** 内部唤醒，不是 EOF、Enter 或读取失败；草稿仍由读取器保管。 */
    public static final class InputRetryException extends RuntimeException {
        public InputRetryException() { super(null, null, false, false); }
    }

    @FunctionalInterface
    public interface InputReader {
        /** 已解码的一次输入；真实空行是 Enter，null 是 EOF，取消/错误必须抛异常。 */
        String read(Request request) throws Exception;
    }

    /** 失败不能伪装成空行，否则审批会把 EOF/超时当成 Enter 批准。 */
    public static final class InputUnavailableException extends RuntimeException {
        private final Failure failure;

        private InputUnavailableException(Failure failure) {
            super("输入请求未完成：" + failure);
            this.failure = failure;
        }

        public Failure failure() { return failure; }
    }

    /** 一整段交互保持模式，包括两个子提示之间；只允许创建它的 worker 发送子请求。 */
    public final class Session implements AutoCloseable {
        private final Thread requester = Thread.currentThread();
        private final InputCoordinator.Mode mode;
        private final InputCoordinator.Interaction interaction;
        private Pending pending;

        private Session(InputCoordinator.Mode mode) {
            this.mode = mode;
            this.interaction = coordinator.enterInteraction(mode);
        }

        public String read(Kind kind, String prompt, Duration timeout) {
            if (Thread.currentThread() != requester) throw new IllegalStateException("只有请求方可以等待输入");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(prompt, "prompt");
            long nanos = Objects.requireNonNull(timeout, "timeout").toNanos();
            if (nanos <= 0 || kind.mode() != mode) throw new IllegalArgumentException("输入类型或超时不合法");
            Pending request;
            synchronized (TerminalInputGateway.this) {
                if (closed || active != this) throw new InputUnavailableException(Failure.CLOSED);
                if (pending != null) throw new InputUnavailableException(Failure.BUSY);
                request = new Pending(new Request(UUID.randomUUID().toString(), kind, prompt), nanos);
                pending = request;
                TerminalInputGateway.this.notifyAll();
            }
            signalUi();
            try {
                synchronized (TerminalInputGateway.this) {
                    try {
                        while (!request.done) {
                            long remaining = request.remaining();
                            if (remaining <= 0) failSession(this, Failure.TIMEOUT);
                            else TimeUnit.NANOSECONDS.timedWait(TerminalInputGateway.this, remaining);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failSession(this, Failure.INTERRUPTED);
                        throw new InputUnavailableException(Failure.INTERRUPTED);
                    }
                    if (Thread.currentThread().isInterrupted()) {
                        failSession(this, Failure.INTERRUPTED);
                        throw new InputUnavailableException(Failure.INTERRUPTED);
                    }
                    if (request.failure != null) throw new InputUnavailableException(request.failure);
                    return request.line;
                }
            } finally {
                if (request.failure != null || Thread.currentThread().isInterrupted()) signalUi();
            }
        }

        @Override
        public void close() {
            boolean changed;
            synchronized (TerminalInputGateway.this) { changed = failSession(this, Failure.CLOSED); }
            if (changed) signalUi();
        }
    }

    private static final class Pending {
        private final Request request;
        private final long started = System.nanoTime();
        private final long timeout;
        private boolean claimed;
        private boolean done;
        private String line;
        private Failure failure;

        private Pending(Request request, long timeout) {
            this.request = request;
            this.timeout = timeout;
        }

        private long remaining() { return timeout - (System.nanoTime() - started); }
    }

    private final InputCoordinator coordinator;
    private final InputReader reader;
    private final Runnable uiWake;
    private final Thread owner = Thread.currentThread();
    private Session active;
    private boolean servicing;
    private boolean closed;

    public TerminalInputGateway(InputCoordinator coordinator, InputReader reader) {
        this(coordinator, reader, () -> { });
    }

    /** uiWake 只能置线程安全标志（例如 requestWake），不得读终端或阻塞。 */
    public TerminalInputGateway(InputCoordinator coordinator, InputReader reader, Runnable uiWake) {
        this.uiWake = Objects.requireNonNull(uiWake, "uiWake");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.reader = Objects.requireNonNull(reader, "reader");
        coordinator.capture(); // 构造线程必须也是 InputCoordinator 的事件所有者。
    }

    /** 只切换模式，绝不在 worker 读取或获取终端锁；冲突请求明确失败。 */
    public Session openInteraction(InputCoordinator.Mode mode) {
        Session session;
        synchronized (this) {
            if (closed) throw new InputUnavailableException(Failure.CLOSED);
            if (!coordinator.enabled()) throw new InputUnavailableException(Failure.DISABLED);
            if (Thread.currentThread() == owner) throw new IllegalStateException("UI 不能等待自己的输入请求");
            if (active != null) throw new InputUnavailableException(Failure.BUSY);
            session = new Session(mode);
            active = session;
            notifyAll();
        }
        signalUi();
        return session;
    }

    /**
     * UI 在子提示间隙有界等待，不获取终端锁、不读取终端。
     * BETWEEN_SUBPROMPTS 仍属交互模式，调用方必须继续等待，不能恢复 RUNNING 读行。
     * 返回状态快照；serviceNext 仍会核实请求是否有效。
     */
    public synchronized UiState awaitInteraction(Duration timeout) throws InterruptedException {
        requireOwner();
        if (servicing) throw new IllegalStateException("不能在读取器中等待交互");
        long nanos = Objects.requireNonNull(timeout, "timeout").toNanos();
        if (nanos < 0) throw new IllegalArgumentException("超时不能为负数");
        long started = System.nanoTime();
        while (active != null && active.pending == null && nanos > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, nanos);
            nanos = timeout.toNanos() - (System.nanoTime() - started);
        }
        if (active == null) return new UiState(InteractionState.NONE, null);
        return active.pending == null
                ? new UiState(InteractionState.BETWEEN_SUBPROMPTS, null)
                : new UiState(InteractionState.PENDING, active.pending.request);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("只有唯一 UI 所有者可以读取");
    }

    private void signalUi() {
        try { uiWake.run(); }
        catch (RuntimeException ignored) { /* 通知失败不能破坏请求或 worker 收尾。 */ }
    }

    /**
     * UI 安全交接点。没有请求或旧交互持锁时不读；不会在 worker 等待时持终端锁。
     * 读取和唤醒回调都在桥的监视器外。RETRY 的 null text 表示内部唤醒；
     * UI 应从 WakeableLineReader.takeSuspendedDraft 取回草稿，不能自动提交。
     */
    public Dispatch serviceNext() {
        requireOwner();
        if (servicing) throw new IllegalStateException("不能重入读取器");
        if (!TerminalInputLock.tryAcquire()) return new Dispatch(DispatchStatus.IDLE, null, null);
        servicing = true;
        boolean wake = false;
        try {
            Session session;
            Pending pending;
            InputCoordinator.ReadTicket ticket;
            synchronized (this) {
                session = active;
                if (session == null || session.pending == null || session.pending.claimed) {
                    return new Dispatch(DispatchStatus.IDLE, null, null);
                }
                pending = session.pending;
                if (pending.remaining() <= 0) {
                    wake = failSession(session, Failure.TIMEOUT);
                    return new Dispatch(DispatchStatus.FAILED, pending.request.id(), null, Failure.TIMEOUT);
                }
                pending.claimed = true;
                ticket = coordinator.capture();
            }
            String line = null;
            Failure failure = null;
            boolean retry = false;
            try {
                line = reader.read(pending.request);
                if (line == null) failure = Failure.EOF;
                if (Thread.currentThread().isInterrupted()) failure = Failure.INTERRUPTED;
            } catch (InputRetryException e) {
                retry = true;
            } catch (JLineInputAdapter.InputCancelledException e) {
                failure = Failure.INTERRUPTED;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failure = Failure.INTERRUPTED;
            } catch (Exception e) {
                failure = Failure.READ_ERROR;
            }
            synchronized (this) {
                // 只完成本次读取捕获的请求；超时后新开的请求绝不能收到迟到的 y/Enter。
                if (active != session || session.pending == null
                        || !pending.request.id().equals(session.pending.request.id()) || pending.done) {
                    // Session.read 可能在 UI 读行期间超时/关闭；把原因带回 UI，
                    // 让它隔离下一行，防止迟到的审批选项变成 Steer。
                    return new Dispatch(DispatchStatus.RETRY, pending.request.id(), line, pending.failure);
                }
                if (pending.remaining() <= 0) {
                    wake = failSession(session, Failure.TIMEOUT);
                    return new Dispatch(DispatchStatus.RETRY, pending.request.id(), line, Failure.TIMEOUT);
                }
                if (retry) {
                    pending.claimed = false;
                    return new Dispatch(DispatchStatus.RETRY, pending.request.id(), null);
                }
                if (failure != null) {
                    wake = failSession(session, failure);
                    return new Dispatch(DispatchStatus.FAILED, pending.request.id(), null, failure);
                }
                var routed = coordinator.submit(ticket, line);
                if (routed.route() != InputCoordinator.Route.INTERACTION || ticket.mode() != session.mode) {
                    wake = failSession(session, Failure.RETRY);
                    return new Dispatch(DispatchStatus.RETRY, pending.request.id(), line);
                }
                pending.line = line;
                pending.done = true;
                session.pending = null;
                notifyAll();
                return new Dispatch(DispatchStatus.DELIVERED, pending.request.id(), line);
            }
        } finally {
            servicing = false;
            TerminalInputLock.release();
            if (wake) signalUi();
        }
    }

    private boolean failSession(Session session, Failure failure) {
        if (active != session) return false;
        if (session.pending != null) {
            session.pending.failure = failure;
            session.pending.done = true;
            session.pending = null;
        }
        session.interaction.close();
        active = null;
        notifyAll();
        return true;
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
            if (active != null) failSession(active, Failure.CLOSED);
            notifyAll();
        }
        signalUi();
    }
}
