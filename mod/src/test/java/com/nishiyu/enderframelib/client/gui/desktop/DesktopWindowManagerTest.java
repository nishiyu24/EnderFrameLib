package com.nishiyu.enderframelib.client.gui.desktop;

import com.nishiyu.enderframelib.client.gui.desktop.LinuxDesktopScreen.DesktopWindowManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DesktopWindowManagerTest {

    private DesktopWindowManager wm;

    @BeforeEach
    public void setup() {
        wm = new DesktopWindowManager();
        wm.registerWindow("term", "Terminal", 100, 50, 400, 300);
        wm.registerWindow("notes", "Notes", 200, 100, 350, 250);
    }

    @Test
    public void testOpenAndFocus() {
        assertFalse(wm.get("term").isOpen);
        assertFalse(wm.get("notes").isOpen);

        wm.open("term");
        assertTrue(wm.get("term").isOpen);
        assertTrue(wm.get("term").isVisible());
        assertEquals("term", wm.activeWindowId());

        wm.open("notes");
        assertTrue(wm.get("notes").isOpen);
        assertEquals("notes", wm.activeWindowId());
        assertTrue(wm.get("notes").zIndex > wm.get("term").zIndex, "後から開いたウィンドウの Z-Index が高くなること");
    }

    @Test
    public void testMinimizeAndRestore() {
        wm.open("term");
        wm.open("notes");
        assertEquals("notes", wm.activeWindowId());

        wm.minimize("notes");
        assertTrue(wm.get("notes").isOpen);
        assertTrue(wm.get("notes").isMinimized);
        assertFalse(wm.get("notes").isVisible());
        // アクティブが term に移る
        assertEquals("term", wm.activeWindowId());

        // 再度 open または focus でリストア
        wm.open("notes");
        assertFalse(wm.get("notes").isMinimized);
        assertTrue(wm.get("notes").isVisible());
        assertEquals("notes", wm.activeWindowId());
    }

    @Test
    public void testToggleMaximize() {
        wm.open("term");
        DesktopWindowManager.WindowState win = wm.get("term");
        int origX = win.x;
        int origY = win.y;
        int origW = win.width;
        int origH = win.height;

        // 最大化
        wm.toggleMaximize("term", 854, 480, 28, 48);
        assertTrue(win.isMaximized);
        assertEquals(0, win.x);
        assertEquals(28, win.y);
        assertEquals(854, win.width);
        assertEquals(480 - 28 - 48, win.height);

        // 元に戻す
        wm.toggleMaximize("term", 854, 480, 28, 48);
        assertFalse(win.isMaximized);
        assertEquals(origX, win.x);
        assertEquals(origY, win.y);
        assertEquals(origW, win.width);
        assertEquals(origH, win.height);
    }

    @Test
    public void testMoveClamping() {
        wm.open("term");
        DesktopWindowManager.WindowState win = wm.get("term");

        // 通常移動
        wm.move("term", 20, 30, 854, 480, 28);
        assertEquals(120, win.x);
        assertEquals(80, win.y);

        // 画面上部（トップバー以上）へのめり込み防止
        wm.move("term", 0, -200, 854, 480, 28);
        assertTrue(win.y >= 28, "TopBar より上には移動しないこと");

        // 画面外への極端な移動防止
        wm.move("term", 2000, 2000, 854, 480, 28);
        assertTrue(win.x < 854, "画面右端に見失わない範囲でクランプされること");
        assertTrue(win.y < 480, "画面下端に見失わない範囲でクランプされること");
    }

    @Test
    public void testZIndexNormalization() {
        wm.open("term");
        for (int i = 0; i < 600; i++) {
            wm.focus("term");
            wm.focus("notes");
        }
        assertTrue(wm.get("notes").zIndex < 500, "Z-Index は 500 未満に正規化されること");
        assertTrue(wm.get("notes").zIndex > wm.get("term").zIndex, "フォーカス順序が保たれていること");
    }

    @Test
    public void testFindTopWindowAt() {
        wm.open("term");  // (100, 50, 400, 300) -> [100..500, 50..350]
        wm.open("notes"); // (200, 100, 350, 250) -> [200..550, 100..350] - zIndex 高い

        // 両方が重なっている点 (250, 150) では前面の notes が取得される
        DesktopWindowManager.WindowState found = wm.findTopWindowAt(250, 150);
        assertNotNull(found);
        assertEquals("notes", found.id);

        // term だけの領域 (150, 60) では term が取得される
        found = wm.findTopWindowAt(150, 60);
        assertNotNull(found);
        assertEquals("term", found.id);

        // 何もない領域 (10, 10) では null
        found = wm.findTopWindowAt(10, 10);
        assertNull(found);
    }
}
