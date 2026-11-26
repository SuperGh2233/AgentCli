package com.paicli.cli;

import com.paicli.render.inline.InlineRenderer;
import com.paicli.runtime.CancellationContext;
import com.paicli.runtime.CancellationToken;
import com.paicli.runtime.input.DeliveryMode;
import com.paicli.runtime.input.TurnInputManager;
import com.paicli.runtime.input.TurnState;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.MaskingCallback;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.reader.Binding;
import org.jline.keymap.KeyMap;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.Callable;

/**
 * Opt-in 的交互式 ReAct 输入循环。UI 线程是唯一 JLine 读取者；
 * Agent worker 只能通过 TerminalInputGateway 请求审批输入或请求唤醒。
 *
 * Queue 在本阶段只保存并显示数量，不自动启动下一 Turn。
 */
public final class SteeringRunLoop {
    private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration UI_WAIT = Duration.ofMillis(20);
    private static final long STOP_WAIT_SECONDS = 2;

    public record RunResult(String response, String promptSeed, int promptCursor, boolean workerStopped) { }

    private SteeringRunLoop() { }

    public static RunResult run(WakeableLineReader reader,
                                InlineRenderer renderer,
                                TurnInputManager manager,
                                InputCoordinator coordinator,
                                String turnId,
                                Callable<String> task) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(renderer, "renderer");
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(coordinator, "coordinator");
        requireText(turnId, "turnId");
        Objects.requireNonNull(task, "task");
        if (!manager.enabled() || !coordinator.enabled()) {
            throw new IllegalStateException("Steering 输入循环未启用");
        }

        manager.startTurn(turnId);
        manager.markRunning(turnId);
        coordinator.beginRun(turnId);

        AtomicBoolean approvalReadFinished = new AtomicBoolean();
        JLineInputAdapter adapter = new JLineInputAdapter(reader, (request, line) -> {
            // 在 Gateway 唤醒审批 worker 前，先结束输入行并清除编辑态回显，避免下一子提示与清屏竞争。
            approvalReadFinished.set(true);
            renderer.afterInput();
            if (line != null) {
                renderer.clearAcceptedInput(line, request.prompt());
            }
        });
        TerminalInputGateway gateway = new TerminalInputGateway(coordinator, adapter, reader::requestWake);
        renderer.bindApprovalGateway(gateway, APPROVAL_TIMEOUT);

        AtomicBoolean escapeRequested = new AtomicBoolean();
        bindEscToInterrupt(reader, coordinator, escapeRequested);

        CancellationToken token = CancellationContext.startRun();
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "paicli-steering-react-runner");
            thread.setDaemon(true);
            return thread;
        });
        FutureTask<String> future = new FutureTask<>(task) {
            @Override
            protected void done() {
                reader.requestWake();
            }
        };

        String response = "";
        String runningDraft = null;
        int runningCursor = 0;
        boolean interrupted = false;
        boolean cancellationRequested = false;
        boolean workerStopped = false;
        boolean discardNextRunningLine = false;
        try {
            renderer.stream().println("任务运行中：直接输入补充要求，或使用 /steer、/queue、/inputs、/cancel。");
            executor.execute(future);

            while (!future.isDone()) {
                if (escapeRequested.get()) {
                    cancellationRequested = true;
                    cancelTask(manager, coordinator, gateway, token, future, executor);
                    break;
                }

                TerminalInputGateway.UiState uiState;
                try {
                    uiState = gateway.awaitInteraction(UI_WAIT);
                } catch (InterruptedException e) {
                    interrupted = true;
                    cancellationRequested = true;
                    cancelTask(manager, coordinator, gateway, token, future, executor);
                    break;
                }

                if (uiState.state() == TerminalInputGateway.InteractionState.PENDING) {
                    TerminalInputGateway.Dispatch dispatch;
                    approvalReadFinished.set(false);
                    boolean approvalInputStarted = false;
                    try {
                        renderer.beforeInput();
                        approvalInputStarted = true;
                        dispatch = gateway.serviceNext();
                    } finally {
                        if (approvalInputStarted && !approvalReadFinished.get()) {
                            renderer.afterInput();
                        }
                    }
                    if (dispatch.failure() == TerminalInputGateway.Failure.INTERRUPTED) {
                        requestInterrupt(manager, coordinator);
                        cancellationRequested = true;
                        cancelTask(manager, coordinator, gateway, token, future, executor);
                        break;
                    }
                    if (dispatch.failure() != null
                            && dispatch.failure() != TerminalInputGateway.Failure.INTERRUPTED
                            && dispatch.text() == null) {
                        discardNextRunningLine = true;
                        renderer.stream().println("审批输入未完成（" + dispatch.failure()
                                + "）；为防止迟到选项混入任务，下一行将被隔离，请随后重新输入。");
                    }
                    if (dispatch.status() == TerminalInputGateway.DispatchStatus.RETRY
                            && dispatch.text() == null) {
                        WakeableLineReader.Draft approvalDraft = reader.takeSuspendedDraft();
                        TerminalInputGateway.UiState next;
                        try {
                            next = gateway.awaitInteraction(Duration.ZERO);
                        } catch (InterruptedException e) {
                            interrupted = true;
                            cancellationRequested = true;
                            cancelTask(manager, coordinator, gateway, token, future, executor);
                            break;
                        }
                        if (!future.isDone()
                                && next.state() == TerminalInputGateway.InteractionState.PENDING
                                && approvalDraft != null
                                && (!approvalDraft.text().isEmpty() || approvalDraft.cursor() != 0)) {
                            adapter.restoreNextRead(approvalDraft);
                        }
                    } else if (dispatch.status() == TerminalInputGateway.DispatchStatus.RETRY
                            && dispatch.text() != null) {
                        renderer.stream().println("审批输入已过期，未用于批准；请在当前提示中重新输入。");
                    } else if (dispatch.status() == TerminalInputGateway.DispatchStatus.FAILED
                            && dispatch.failure() != null) {
                        renderer.stream().println("审批输入不可用（" + dispatch.failure()
                                + "），本次操作按拒绝处理。");
                    }
                    continue;
                }

                if (uiState.state() == TerminalInputGateway.InteractionState.BETWEEN_SUBPROMPTS) {
                    continue;
                }
                if (future.isDone()) {
                    break;
                }

                InputCoordinator.ReadTicket ticket = coordinator.capture();
                boolean inputStarted = false;
                try {
                    renderer.beforeInput();
                    inputStarted = true;
                    String line;
                    try {
                        if (runningDraft != null) {
                            reader.restoreCursorOnNextInit(runningCursor);
                        }
                        line = reader.readLine(
                                renderer.inputPrompt(),
                                renderer.inputRightPrompt(),
                                (MaskingCallback) null,
                                runningDraft);
                    } finally {
                        if (inputStarted) {
                            inputStarted = false;
                            renderer.afterInput();
                        }
                    }
                    runningDraft = null;
                    runningCursor = 0;
                    if (line == null) {
                        requestInterrupt(manager, coordinator);
                        cancellationRequested = true;
                        cancelTask(manager, coordinator, gateway, token, future, executor);
                        break;
                    }
                    renderer.clearAcceptedInput(line);
                    if (discardNextRunningLine) {
                        discardNextRunningLine = false;
                        renderer.stream().println("已隔离审批超时后的输入；请重新输入任务补充要求。");
                        continue;
                    }

                    InputCoordinator.Result routed = coordinator.submit(ticket, line);
                    if (routed.route() == InputCoordinator.Route.RETRY) {
                        // 模式在 Enter 前发生变化：原文只恢复为草稿，必须由用户再次确认。
                        runningDraft = line;
                        runningCursor = line.length();
                        renderer.stream().println(routed.notice());
                        continue;
                    }
                    if (routed.route() == InputCoordinator.Route.ACCEPTED) {
                        renderer.printSubmittedPrompt(Main.redactSensitiveInput(routed.text()));
                        renderer.stream().println(routed.notice());
                    } else if (routed.route() == InputCoordinator.Route.STATUS
                            || routed.route() == InputCoordinator.Route.REJECTED) {
                        renderer.stream().println(routed.notice());
                    }

                    if (isInterrupt(routed)) {
                        cancellationRequested = true;
                        cancelTask(manager, coordinator, gateway, token, future, executor);
                        break;
                    }
                } catch (WakeableLineReader.Wakeup e) {
                    WakeableLineReader.Draft draft = reader.takeSuspendedDraft();
                    if (draft != null) {
                        runningDraft = draft.text();
                        runningCursor = draft.cursor();
                    }
                    if (escapeRequested.get()) {
                        cancellationRequested = true;
                        cancelTask(manager, coordinator, gateway, token, future, executor);
                        break;
                    }
                } catch (UserInterruptException e) {
                    requestInterrupt(manager, coordinator);
                    cancellationRequested = true;
                    cancelTask(manager, coordinator, gateway, token, future, executor);
                    break;
                } catch (EndOfFileException e) {
                    requestInterrupt(manager, coordinator);
                    cancellationRequested = true;
                    cancelTask(manager, coordinator, gateway, token, future, executor);
                    break;
                } finally {
                    if (inputStarted) {
                        renderer.afterInput();
                    }
                }
            }

            if (!cancellationRequested && future.isDone() && !future.isCancelled()) {
                try {
                    response = future.get();
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    String message = cause == null || cause.getMessage() == null
                            ? "未知错误" : cause.getMessage();
                    response = "❌ 执行失败: " + message;
                } catch (InterruptedException e) {
                    interrupted = true;
                    cancellationRequested = true;
                    cancelTask(manager, coordinator, gateway, token, future, executor);
                    response = "⏹️ 已请求取消当前任务。";
                } catch (CancellationException e) {
                    cancellationRequested = true;
                    response = "⏹️ 已取消当前任务。";
                }
            } else if (cancellationRequested || future.isCancelled()) {
                response = "⏹️ 已请求取消当前任务。";
            }
        } finally {
            gateway.close();
            Main.bindEscToClearInput(reader);
            executor.shutdownNow();

            boolean stopped = false;
            try {
                stopped = executor.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }

            workerStopped = stopped;
            if (stopped) {
                renderer.bindApprovalGateway(null, null);
                reader.consumeWakeRequest();
            } else {
                // 保持已关闭的 gateway 绑定，防止迟到的 worker 回退到旧 raw Terminal Reader。
                renderer.stream().println("后台线程未停止，审批入口保持关闭；主程序将退出以避免双 Reader。");
            }
            TurnState state = manager.snapshot().state();
            if (state == TurnState.CANCELLING) {
                if (stopped) {
                    manager.finishCancellation(turnId);
                } else {
                    renderer.stream().println("取消仍在等待阻塞任务停止；队列保持暂停，未将任务标记为已停止。");
                }
            } else if (state == TurnState.STARTING || state == TurnState.RUNNING
                    || state == TurnState.WAITING_APPROVAL || state == TurnState.FINALIZING) {
                if (stopped) {
                    manager.failTurn(turnId);
                } else {
                    renderer.stream().println("任务线程尚未确认停止；当前 Turn 保持未完成状态。");
                }
            }

            try {
                InputCoordinator.Result ended = coordinator.endRun(turnId);
                if (ended.snapshot().queuedCount() > 0) {
                    renderer.stream().println("已保留 " + ended.snapshot().queuedCount()
                            + " 条排队任务；当前阶段不会自动启动下一任务。");
                }
            } catch (RuntimeException e) {
                renderer.stream().println("输入接收窗口未能正常关闭: " + e.getMessage());
            }
            CancellationContext.clear(token);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        return new RunResult(response, runningDraft, runningCursor, workerStopped);
    }

    private static boolean isInterrupt(InputCoordinator.Result result) {
        return result != null
                && result.acceptance() != null
                && result.acceptance().accepted()
                && result.acceptance().input() != null
                && result.acceptance().input().mode() == DeliveryMode.INTERRUPT;
    }

    private static void requestInterrupt(TurnInputManager manager, InputCoordinator coordinator) {
        if (manager.snapshot().state() == TurnState.CANCELLING) {
            return;
        }
        InputCoordinator.ReadTicket ticket = coordinator.capture();
        if (ticket.mode() == InputCoordinator.Mode.RUNNING_INPUT) {
            coordinator.escape(ticket);
        }
    }

    private static void cancelTask(TurnInputManager manager,
                                   InputCoordinator coordinator,
                                   TerminalInputGateway gateway,
                                   CancellationToken token,
                                   FutureTask<String> future,
                                   ExecutorService executor) {
        // 先关闭审批交互，恢复 RUNNING_INPUT 模式，再把取消写入 Turn 状态。
        // 这样 Ctrl+C 在审批子提示里也会取消整轮，而不是只把这次审批当作读错误。
        gateway.close();
        requestInterrupt(manager, coordinator);
        token.cancel();
        future.cancel(true);
        executor.shutdownNow();
    }

    private static void bindEscToInterrupt(WakeableLineReader reader,
                                           InputCoordinator coordinator,
                                           AtomicBoolean escapeRequested) {
        String widgetName = "paicli-steering-interrupt";
        reader.getWidgets().put(widgetName, () -> {
            InputCoordinator.ReadTicket ticket = coordinator.capture();
            if (ticket.mode() == InputCoordinator.Mode.RUNNING_INPUT) {
                InputCoordinator.Result result = coordinator.escape(ticket);
                if (result.route() == InputCoordinator.Route.ACCEPTED) {
                    escapeRequested.set(true);
                    reader.requestWake();
                    return true;
                }
            }
            reader.getBuffer().clear();
            reader.callWidget(LineReader.REDISPLAY);
            return true;
        });
        Reference reference = new Reference(widgetName);
        for (String mapName : new String[]{LineReader.MAIN, LineReader.EMACS, LineReader.VIINS}) {
            KeyMap<Binding> map = reader.getKeyMaps().get(mapName);
            if (map != null) {
                map.bind(reference, KeyMap.esc());
            }
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
