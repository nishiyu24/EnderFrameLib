package com.nishiyu.enderframelib.client.gui.desktop;

import com.nishiyu.enderframelib.client.gui.desktop.LinuxDesktopScreen.DesktopLayout;
import com.nishiyu.enderframelib.client.gui.desktop.LinuxDesktopScreen.DesktopLayout.Rect;
import com.nishiyu.enderframelib.client.gui.desktop.LinuxDesktopScreen.TickThrottle;
import com.nishiyu.enderframelib.client.gui.desktop.LinuxDesktopScreen.DesktopWindowManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class DesktopLayoutTest {

    // linux_desktop.html の Applications ボタンとメニュー（top: 32px / left: 8px / width: 280px）に相当
    private static final Rect BUTTON = new Rect(12, 4, 80, 20);
    private static final Rect MENU = new Rect(8, 32, 280, 290);

    @Test
    public void appMenuStaysOpenWhenPressingItsOwnButton() {
        assertFalse(DesktopLayout.shouldCloseAppMenu(40, 14, MENU, BUTTON),
                "ボタン上の押下で閉じると、解放時の onclick で再び開いてトグルできない");
    }

    @Test
    public void appMenuStaysOpenInsideMenuIncludingLastItem() {
        assertFalse(DesktopLayout.shouldCloseAppMenu(100, 50, MENU, BUTTON));
        // 旧実装は webY > 260 で閉じていたため 4 番目の System Monitor を押せなかった
        assertFalse(DesktopLayout.shouldCloseAppMenu(100, 290, MENU, BUTTON));
    }

    @Test
    public void appMenuClosesOutside() {
        assertTrue(DesktopLayout.shouldCloseAppMenu(500, 200, MENU, BUTTON));
        assertTrue(DesktopLayout.shouldCloseAppMenu(100, 330, MENU, BUTTON));
        assertTrue(DesktopLayout.shouldCloseAppMenu(300, 10, MENU, BUTTON), "トップパネルのボタン以外では閉じる");
        assertTrue(DesktopLayout.shouldCloseAppMenu(100, 100, null, BUTTON), "メニューの矩形が無ければ閉じる");
    }

    private static DesktopWindowManager desktopWindows() {
        DesktopWindowManager wm = new DesktopWindowManager();
        wm.registerWindow("docs", "Docs", 0, 0, 10, 10);
        wm.registerWindow("terminal", "Terminal", 0, 0, 10, 10);
        wm.registerWindow("editor", "Editor", 0, 0, 10, 10);
        wm.registerWindow("sysmon", "SysMon", 0, 0, 10, 10);
        wm.open("terminal");
        wm.open("docs");
        return wm;
    }

    @Test
    public void initialWindowsAvoidIconsAndKeepTitleBarsVisible() {
        int[][] sizes = {{854, 480}, {640, 360}, {1280, 720}, {1920, 1080}};
        for (int[] size : sizes) {
            assertInitialLayout(size[0], size[1]);
        }
    }

    private static void assertInitialLayout(int w, int h) {
        DesktopWindowManager wm = desktopWindows();
        DesktopLayout.placeInitialWindows(wm, w, h);

        for (DesktopWindowManager.WindowState win : wm.allWindows()) {
            assertTrue(win.x >= DesktopLayout.ICON_COLUMN_RIGHT, win.id + " がデスクトップアイコン列 (x < 96) に重ならないこと: x=" + win.x);
            assertTrue(win.y >= DesktopLayout.TOP_BAR_HEIGHT, win.id + " がトップパネルの下にあること");
            assertTrue(win.x + win.width <= w, win.id + " が画面の右端からはみ出さないこと");
            assertTrue(win.y + win.height <= h - DesktopLayout.DOCK_HEIGHT, win.id + " がドックに掛からないこと");
            assertEquals(win.x, win.prevX, "最大化からの復元位置も初期配置に合わせる");
        }

        DesktopWindowManager.WindowState docs = wm.get("docs");
        DesktopWindowManager.WindowState term = wm.get("terminal");
        assertTrue(docs.zIndex > term.zIndex, "ドキュメントが前面");
        // 背面のターミナルのタイトルバー（上端 32px）が前面のドキュメントに隠れないこと
        Rect termTitle = new Rect(term.x, term.y, term.width, 32);
        assertFalse(termTitle.intersects(Rect.of(docs)), "ターミナルのタイトルバーがドキュメントに隠れないこと");
        assertNotEquals(docs.x, term.x, "2 つのウィンドウが同じ位置に重ならないこと");
    }

    @Test
    public void tickThrottleRefreshesOncePerIntervalOnlyWhileActive() {
        TickThrottle throttle = new TickThrottle(20);

        int refreshes = 0;
        for (int i = 0; i < 100; i++) {
            if (throttle.tick(false)) refreshes++;
        }
        assertEquals(0, refreshes, "非表示の間は更新しない");

        assertTrue(throttle.tick(true), "表示された最初のティックで即座に更新する");
        refreshes = 1;
        for (int i = 1; i < 100; i++) {
            if (throttle.tick(true)) refreshes++;
        }
        assertEquals(5, refreshes, "100 ティック (5 秒) で 5 回だけ更新する");

        throttle.tick(false);
        assertTrue(throttle.tick(true), "再表示したら即座に更新する");
    }
}
