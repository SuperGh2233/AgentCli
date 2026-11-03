package com.paicli.tui;

import com.googlecode.lanterna.gui2.WindowBasedTextGUI;
import com.paicli.render.Renderer;
import com.paicli.tui.pane.CenterPane;
import com.paicli.tui.pane.StatusPane;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

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
    void publicRendererTracksGuiLifecycleForElicitationCapabilities() {
        LanternaWindow window = mock(LanternaWindow.class);
        RootPane root = mock(RootPane.class);
        when(window.getRootPane()).thenReturn(root);
        when(root.getCenterPane()).thenReturn(mock(CenterPane.class));
        when(root.getStatusPane()).thenReturn(mock(StatusPane.class));
        when(window.getGui()).thenReturn(mock(WindowBasedTextGUI.class));
        when(window.isGuiRunning()).thenReturn(false);

        LanternaRenderer renderer = new LanternaRenderer(window);
        assertTrue(renderer.supportedElicitationModes().isEmpty());
        when(window.isGuiRunning()).thenReturn(true);
        assertEquals(Set.of("form", "url"), renderer.supportedElicitationModes());
        renderer.close();
        assertTrue(renderer.supportedElicitationModes().isEmpty());
    }
}
