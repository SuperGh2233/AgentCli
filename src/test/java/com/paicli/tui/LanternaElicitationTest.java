package com.paicli.tui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.googlecode.lanterna.TerminalSize;
import com.googlecode.lanterna.gui2.*;
import com.paicli.mcp.elicitation.*;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class LanternaElicitationTest {
    private static final ElicitationResponse.Action ACCEPT = ElicitationResponse.Action.ACCEPT;
    private static final ElicitationResponse.Action CANCEL = ElicitationResponse.Action.CANCEL;
    private static final ElicitationResponse.Action DECLINE = ElicitationResponse.Action.DECLINE;

    private ElicitationRequest form() throws Exception {
        return new ElicitationRequest("测试服务器", "key", ElicitationRequest.Mode.FORM, "请确认姓名",
                new ObjectMapper().readTree("""
                {"type":"object","properties":{"name":{"type":"string","title":"姓名标题",
                "description":"字段说明","minLength":2}},"required":["name"]}
                """), null);
    }

    private ElicitationRequest url() {
        return new ElicitationRequest("测试服务器", "key", ElicitationRequest.Mode.URL, "请打开授权页面",
                null, URI.create("https://xn--bcher-kva.example/path?full=value#fragment"));
    }

    private static class Fake implements LanternaElicitation.Dialogs {
        volatile boolean available = true;
        boolean guiThread;
        boolean dispatched;
        boolean fail;
        int closes;
        final List<String> texts = new ArrayList<>();
        final Queue<String> answers = new LinkedList<>();
        Consumer<String> pending;
        Runnable onShow;
        Fake(String... values) { answers.addAll(Arrays.asList(values)); }
        public boolean available() { return available; }
        public boolean isGuiThread() { return guiThread; }
        public void dispatch(Runnable task) {
            assertFalse(dispatched);
            dispatched = true;
            try { task.run(); } finally { dispatched = false; }
        }
        public Runnable show(String text, LanternaElicitation.Step step, Consumer<String> answer) {
            assertTrue(dispatched, "widgets must only be accessed in dispatch");
            if (fail) throw new IllegalStateException();
            texts.add(text);
            pending = answer;
            if (onShow != null) onShow.run();
            if (!answers.isEmpty()) answer.accept(answers.remove());
            return () -> { assertTrue(dispatched); closes++; };
        }
    }

    private LanternaElicitation bridge(Fake fake, AtomicInteger opens) {
        return new LanternaElicitation(fake, uri -> opens.incrementAndGet(), Duration.ofSeconds(2));
    }

    @Test void formAcceptShowsContextAndReview() throws Exception {
        Fake f = new Fake("Alice", "submit");
        var result = bridge(f, new AtomicInteger()).prompt(form());
        assertEquals(ACCEPT, result.action());
        assertEquals("Alice", result.content().path("name").asText());
        assertTrue(f.texts.get(0).contains("测试服务器"));
        assertTrue(f.texts.get(0).contains("请确认姓名"));
        assertTrue(f.texts.get(0).contains("姓名标题"));
        assertTrue(f.texts.get(0).contains("字段说明"));
        assertTrue(f.texts.get(1).contains("Alice"));
        assertTrue(f.texts.get(1).contains("[edit]"));
        assertEquals(2, f.closes);
    }

    @Test void formEditUsesSharedReview() throws Exception {
        Fake f = new Fake("Alice", "edit", "Bob", "submit");
        assertEquals("Bob", bridge(f, new AtomicInteger()).prompt(form()).content().path("name").asText());
        assertEquals(4, f.texts.size());
    }

    @Test void validationUsesSharedPrompter() throws Exception {
        Fake f = new Fake("x", "Alice", "submit");
        assertEquals(ACCEPT, bridge(f, new AtomicInteger()).prompt(form()).action());
        assertTrue(f.texts.get(1).contains("输入无效"));
    }

    @Test void formDecline() throws Exception {
        assertEquals(DECLINE, bridge(new Fake("Alice", "decline"), new AtomicInteger()).prompt(form()).action());
    }

    @Test void formReviewCancel() throws Exception {
        assertEquals(CANCEL, bridge(new Fake("Alice", "cancel"), new AtomicInteger()).prompt(form()).action());
    }

    @Test void fieldWindowCloseCancels() throws Exception {
        assertEquals(CANCEL, bridge(new Fake((String) null), new AtomicInteger()).prompt(form()).action());
    }

    @Test void urlRequiresOpenAndPreservesFullWarning() {
        Fake f = new Fake("open");
        AtomicInteger opens = new AtomicInteger();
        assertEquals(ACCEPT, bridge(f, opens).prompt(url()).action());
        assertEquals(1, opens.get());
        assertTrue(f.texts.get(0).contains(url().url().toASCIIString()));
        assertTrue(f.texts.get(0).contains("目标主机：xn--bcher-kva.example"));
        assertTrue(f.texts.get(0).contains("ASCII 域名"));
        assertTrue(f.texts.get(0).contains("不会预取或读取页面"));
    }

    @Test void urlDeclineNeverOpens() {
        AtomicInteger opens = new AtomicInteger();
        assertEquals(DECLINE, bridge(new Fake("decline"), opens).prompt(url()).action());
        assertEquals(0, opens.get());
    }

    @Test void urlCancelAndCloseNeverOpen() {
        for (String answer : Arrays.asList("cancel", null)) {
            AtomicInteger opens = new AtomicInteger();
            assertEquals(CANCEL, bridge(new Fake(answer), opens).prompt(url()).action());
            assertEquals(0, opens.get());
        }
    }

    @Test void browserFailureCancels() {
        var b = new LanternaElicitation(new Fake("open"), uri -> { throw new Exception(); }, Duration.ofSeconds(1));
        assertEquals(CANCEL, b.prompt(url()).action());
    }

    @Test void missingGuiDoesNotAdvertiseOrDispatch() {
        Fake f = new Fake("open"); f.available = false;
        var b = bridge(f, new AtomicInteger());
        assertTrue(b.supportedModes().isEmpty());
        assertEquals(CANCEL, b.prompt(url()).action());
        assertTrue(f.texts.isEmpty());
        assertEquals(CANCEL, new LanternaElicitation(null, uri -> fail(), Duration.ZERO).prompt(url()).action());
    }

    @Test void guiThreadInvocationIsRejected() {
        Fake f = new Fake("open"); f.guiThread = true;
        assertEquals(CANCEL, bridge(f, new AtomicInteger()).prompt(url()).action());
        assertTrue(f.texts.isEmpty());
    }

    @Test void dialogFailureCancels() {
        Fake f = new Fake(); f.fail = true;
        assertEquals(CANCEL, bridge(f, new AtomicInteger()).prompt(url()).action());
    }

    @Test void guiClosesWhileWaiting() {
        Fake f = new Fake(); f.onShow = () -> f.available = false;
        assertEquals(CANCEL, bridge(f, new AtomicInteger()).prompt(url()).action());
        assertEquals(1, f.closes);
    }

    @Test void timeoutClosesAndIgnoresLateOpen() {
        Fake f = new Fake(); AtomicInteger opens = new AtomicInteger();
        var b = new LanternaElicitation(f, uri -> opens.incrementAndGet(), Duration.ofMillis(20));
        assertEquals(CANCEL, b.prompt(url()).action());
        f.pending.accept("open");
        assertEquals(0, opens.get());
        assertEquals(1, f.closes);
    }

    @Test void interruptedWaitRestoresFlagAndCloses() {
        Fake f = new Fake(); f.onShow = () -> Thread.currentThread().interrupt();
        try {
            assertEquals(CANCEL, bridge(f, new AtomicInteger()).prompt(url()).action());
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, f.closes);
        } finally { Thread.interrupted(); }
    }

    @Test void interruptDuringFutureWait() throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        Fake f = new Fake(); f.onShow = shown::countDown;
        AtomicBoolean restored = new AtomicBoolean();
        AtomicReference<ElicitationResponse> response = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            response.set(bridge(f, new AtomicInteger()).prompt(url()));
            restored.set(Thread.currentThread().isInterrupted());
        });
        worker.start();
        assertTrue(shown.await(1, TimeUnit.SECONDS));
        worker.interrupt(); worker.join(2000);
        assertFalse(worker.isAlive());
        assertEquals(CANCEL, response.get().action());
        assertTrue(restored.get());
    }

    @Test void queuedDispatchAfterTimeoutDoesNotShow() {
        Queue<Runnable> tasks = new ArrayDeque<>();
        Fake f = new Fake("open") {
            @Override public void dispatch(Runnable task) { tasks.add(task); }
        };
        var b = new LanternaElicitation(f, uri -> fail(), Duration.ofMillis(10));
        assertEquals(CANCEL, b.prompt(url()).action());
        while (!tasks.isEmpty()) tasks.remove().run();
        assertTrue(f.texts.isEmpty());
    }

    @Test void realDispatchRunsDialogsOnSeparateGuiExecutor() throws Exception {
        ExecutorService gui = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> guiThread = new AtomicReference<>();
        AtomicInteger opens = new AtomicInteger();
        LanternaElicitation.Dialogs dialogs = new LanternaElicitation.Dialogs() {
            public boolean available() { return true; }
            public boolean isGuiThread() { return Thread.currentThread() == guiThread.get(); }
            public void dispatch(Runnable task) { gui.execute(() -> { guiThread.set(Thread.currentThread()); task.run(); }); }
            public Runnable show(String text, LanternaElicitation.Step step, Consumer<String> answer) {
                assertTrue(isGuiThread()); answer.accept("open");
                return () -> assertTrue(isGuiThread());
            }
        };
        try {
            var b = new LanternaElicitation(dialogs, uri -> {
                assertFalse(dialogs.isGuiThread()); opens.incrementAndGet();
            }, Duration.ofSeconds(2));
            assertEquals(ACCEPT, b.prompt(url()).action());
            assertEquals(1, opens.get());
            gui.submit(() -> {}).get(1, TimeUnit.SECONDS);
        } finally { gui.shutdownNow(); }
    }

    @Test void rendererAdvertisesExactlyBothOnlyWhileAvailable() {
        Fake f = new Fake("open");
        LanternaRenderer renderer = new LanternaRenderer(bridge(f, new AtomicInteger()));
        assertEquals(Set.of("form", "url"), renderer.supportedElicitationModes());
        f.available = false;
        assertTrue(renderer.supportedElicitationModes().isEmpty());
        assertEquals(CANCEL, renderer.promptElicitation(url()).action());
        f.available = true; renderer.close();
        assertTrue(renderer.supportedElicitationModes().isEmpty());
        assertEquals(CANCEL, renderer.promptElicitation(url()).action());
    }

    @Test void nullRequestFailsClosed() {
        assertEquals(CANCEL, bridge(new Fake(), new AtomicInteger()).prompt(null).action());
    }

    @Test void widgetHasScrollableFullTextBoundedInputAndSafeUrlFocus() {
        String longText = "https://example.org/" + "x".repeat(4000);
        List<String> answers = new ArrayList<>();
        ElicitationDialog dialog = new ElicitationDialog(longText, LanternaElicitation.Step.URL, new TerminalSize(100, 40), answers::add);
        Panel panel = (Panel) dialog.getComponent();
        TextBox context = (TextBox) panel.getChildrenList().get(1);
        assertTrue(context.isReadOnly());
        assertEquals(longText, context.getText());
        assertTrue(((Button) dialog.getFocusedInteractable()).getLabel().contains("Cancel"));
        dialog.close();
        dialog.close();
        assertEquals(1, answers.size());
        assertNull(answers.get(0));
        ElicitationDialog form = new ElicitationDialog("body", LanternaElicitation.Step.FIELD, new TerminalSize(100, 40), value -> {});
        TextBox input = (TextBox) form.getFocusedInteractable();
        assertThrows(IllegalStateException.class, () -> input.setText("x".repeat(2049)));
    }
}
