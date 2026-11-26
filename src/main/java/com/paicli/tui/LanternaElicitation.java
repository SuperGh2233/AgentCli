package com.paicli.tui;

import com.paicli.mcp.elicitation.ElicitationPrompter;
import com.paicli.mcp.elicitation.ElicitationRequest;
import com.paicli.mcp.elicitation.ElicitationResponse;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Worker-side bridge. Only dispatched tasks may touch widgets. */
final class LanternaElicitation {
    enum Step { FIELD, REVIEW, URL }
    interface Dialogs {
        boolean available();
        boolean isGuiThread();
        void dispatch(Runnable task);
        // Called on GUI thread; closing the returned handle must also happen there.
        Runnable show(String text, Step step, Consumer<String> answer);
    }

    private final Dialogs dialogs;
    private final ElicitationPrompter.BrowserOpener browser;
    private final long timeoutNanos;

    LanternaElicitation(Dialogs dialogs, ElicitationPrompter.BrowserOpener browser, Duration timeout) {
        this.dialogs = dialogs;
        this.browser = browser;
        this.timeoutNanos = timeout.toNanos();
    }

    Set<String> supportedModes() {
        try { return dialogs != null && dialogs.available() ? Set.of("form", "url") : Set.of(); }
        catch (RuntimeException e) { return Set.of(); }
    }

    ElicitationResponse prompt(ElicitationRequest request) {
        try {
            if (request == null || supportedModes().isEmpty() || dialogs.isGuiThread()
                    || Thread.currentThread().isInterrupted()) return ElicitationResponse.cancel();
            long deadline = System.nanoTime() + timeoutNanos;
            StringBuilder transcript = new StringBuilder();
            ElicitationResponse response = ElicitationPrompter.prompt(request, transcript,
                    prompt -> read(transcript + prompt, request.mode() == ElicitationRequest.Mode.URL
                            ? Step.URL : "确认> ".equals(prompt) ? Step.REVIEW : Step.FIELD, deadline),
                    uri -> {
                        if (!live(deadline)) throw new IOException("Interaction ended");
                        browser.open(uri);
                    });
            return live(deadline) ? response : ElicitationResponse.cancel();
        } catch (RuntimeException e) {
            return ElicitationResponse.cancel();
        }
    }

    private boolean live(long deadline) {
        return !Thread.currentThread().isInterrupted() && System.nanoTime() < deadline && dialogs.available();
    }

    private String read(String text, Step step, long deadline) throws IOException {
        CompletableFuture<String> result = new CompletableFuture<>();
        AtomicBoolean expired = new AtomicBoolean();
        // This handle is read and written only by dispatched tasks, in GUI queue order.
        Runnable[] close = {null};
        try {
            dialogs.dispatch(() -> {
                if (expired.get() || !dialogs.available()) { result.complete(null); return; }
                try {
                    close[0] = dialogs.show(text, step, value -> {
                        if (!expired.get()) result.complete(value);
                    });
                } catch (RuntimeException e) { result.complete(null); }
            });
            while (live(deadline)) {
                try {
                    return result.get(Math.min(TimeUnit.MILLISECONDS.toNanos(50),
                            Math.max(1, deadline - System.nanoTime())), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) { /* Recheck lifecycle and deadline. */ }
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            throw new IOException("Dialog failed", e);
        } finally {
            expired.set(true);
            result.cancel(false);
            try { dialogs.dispatch(() -> { if (close[0] != null) close[0].run(); }); }
            catch (RuntimeException ignored) { /* GUI is unavailable; response stays cancelled. */ }
        }
    }
}
