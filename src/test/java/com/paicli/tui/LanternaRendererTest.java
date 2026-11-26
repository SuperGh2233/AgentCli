package com.paicli.tui;

import com.paicli.mcp.elicitation.ElicitationPrompter;
import com.paicli.render.Renderer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LanternaRenderer 的轻量级测试 —— 覆盖类型契约与 GUI 可用性声明生命周期。
 * 真实 alternate-screen 交互仍需真实终端 smoke test。
 */
class LanternaRendererTest {

    @Test
    void implementsRendererInterface() {
        // 仅校验 LanternaRenderer 实现了 Renderer 契约（接口方法签名）。
        // 实例化需要真实终端，留给端到端手测 §8。
        assertTrue(Renderer.class.isAssignableFrom(LanternaRenderer.class));
    }

    @Test
    void hasPublicConstructorAcceptingLanternaWindow() throws Exception {
        // 构造函数签名校验
        var ctor = LanternaRenderer.class.getConstructor(LanternaWindow.class);
        assertNotNull(ctor);
    }

    @Test
    void rendererTracksGuiLifecycleForElicitationCapabilities() {
        AtomicBoolean guiAvailable = new AtomicBoolean(false);
        LanternaElicitation elicitation = new LanternaElicitation(new LanternaElicitation.Dialogs() {
            @Override public boolean available() { return guiAvailable.get(); }
            @Override public boolean isGuiThread() { return false; }
            @Override public void dispatch(Runnable task) { task.run(); }
            @Override public Runnable show(String text, LanternaElicitation.Step step,
                                           java.util.function.Consumer<String> answer) {
                return () -> { };
            }
        }, ElicitationPrompter.desktopBrowser(), Duration.ofMinutes(5));

        LanternaRenderer renderer = new LanternaRenderer(elicitation);
        assertTrue(renderer.supportedElicitationModes().isEmpty());
        guiAvailable.set(true);
        assertEquals(Set.of("form", "url"), renderer.supportedElicitationModes());
        renderer.close();
        assertTrue(renderer.supportedElicitationModes().isEmpty());
    }
}
