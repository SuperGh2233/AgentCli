package com.paicli.cli;

import com.paicli.util.TerminalInputLock;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 审批/计划输入请求桥。worker 只发请求并等待；构造本对象的 UI 线程独占注入的读取器。
 * 不创建 Terminal、Reader 或线程，不唤醒阻塞中的 JLine，也尚未接入 Main。
 * 生产接线前必须确认旧读者已停止，并让同一个 JLine 所有者调用 serviceNext。
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
    public record Dispatch(DispatchStatus status, String requestId, String text) { }

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
            synchronized (TerminalInputGateway.this) {
                if (closed || active != this) throw new InputUnavailableException(Failure.CLOSED);
                if (pending != null) throw new InputUnavailableException(Failure.BUSY);
                Pending request = new Pending(new Request(UUID.randomUUID().toString(), kind, prompt), nanos);
                pending = request;
                try {
                    while (!request.done) {
                        long remaining = request.remaining();
                        if (remaining <= 0) {
                            failSession(this, Failure.TIMEOUT);
                        } else {
                            TimeUnit.NANOSECONDS.timedWait(TerminalInputGateway.this, remaining);
                        }
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
        }

        @Override
        public void close() {
            synchronized (TerminalInputGateway.this) { failSession(this, Failure.CLOSED); }
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
    private final Thread owner = Thread.currentThread();
    private Session active;
    private boolean servicing;
    private boolean closed;

    public TerminalInputGateway(InputCoordinator coordinator, InputReader reader) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.reader = Objects.requireNonNull(reader, "reader");
        coordinator.capture(); // 构造线程必须也是 InputCoordinator 的事件所有者。
    }

    /** 只切换模式，绝不在 worker 读取或获取终端锁；冲突请求明确失败。 */
    public synchronized Session openInteraction(InputCoordinator.Mode mode) {
        if (closed) throw new InputUnavailableException(Failure.CLOSED);
        if (!coordinator.enabled()) throw new InputUnavailableException(Failure.DISABLED);
        if (Thread.currentThread() == owner) throw new IllegalStateException("UI 不能等待自己的输入请求");
        if (active != null) throw new InputUnavailableException(Failure.BUSY);
        active = new Session(mode);
        return active;
    }

    /**
     * UI 安全交接点。没有请求或旧交互持锁时不读；不会在 worker 等待时持终端锁。
     * 读取回调在桥的监视器外，超时/关闭仍可解除 worker 等待。它们不能唤醒回调本身。
     */
    public Dispatch serviceNext() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("只有唯一 UI 所有者可以读取");
        if (servicing) throw new IllegalStateException("不能重入读取器");
        if (!TerminalInputLock.tryAcquire()) return new Dispatch(DispatchStatus.IDLE, null, null);
        servicing = true;
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
                    failSession(session, Failure.TIMEOUT);
                    return new Dispatch(DispatchStatus.FAILED, pending.request.id(), null);
                }
                pending.claimed = true;
                ticket = coordinator.capture();
            }
            String line = null;
            Failure failure = null;
            try {
                line = reader.read(pending.request);
                if (line == null) failure = Failure.EOF;
                if (Thread.currentThread().isInterrupted()) failure = Failure.INTERRUPTED;
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
                    return new Dispatch(DispatchStatus.RETRY, pending.request.id(), line);
                }
                if (pending.remaining() <= 0) {
                    failSession(session, Failure.TIMEOUT);
                    return new Dispatch(DispatchStatus.RETRY, pending.request.id(), line);
                }
                if (failure != null) {
                    failSession(session, failure);
                    return new Dispatch(DispatchStatus.FAILED, pending.request.id(), null);
                }
                var routed = coordinator.submit(ticket, line);
                if (routed.route() != InputCoordinator.Route.INTERACTION || ticket.mode() != session.mode) {
                    failSession(session, Failure.RETRY);
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
        }
    }

    private void failSession(Session session, Failure failure) {
        if (active != session) return;
        if (session.pending != null) {
            session.pending.failure = failure;
            session.pending.done = true;
            session.pending = null;
        }
        session.interaction.close();
        active = null;
        notifyAll();
    }

    @Override
    public synchronized void close() {
        closed = true;
        if (active != null) failSession(active, Failure.CLOSED);
    }
}
