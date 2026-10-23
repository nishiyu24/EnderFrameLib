package com.nishiyu.enderframelib.client.gui.desktop;

import com.nishiyu.enderframelib.client.ClientScreenInteractionManager;
import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.client.gui.desktop.LinuxDesktopScreen.DesktopLayout;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LinuxDesktopScreen のマウス操作（Applications メニューのトグル・メニュー外クリック・
 * ウィンドウのドラッグ）を、画面を開かずに押下・移動・解放の順で流して確かめる。
 */
public class LinuxDesktopScreenInputTest {

    private RenderPipeline.ILayoutBoxRenderer prevRenderer;
    private boolean prevUiSounds;
    private LinuxDesktopScreen screen;
    private SessionContext session;

    @BeforeEach
    public void setup() throws Exception {
        prevRenderer = UiDocumentCompiler.clientBoxRenderer;
        // クリック音は Minecraft の SoundManager を要するので、画面を開かない検証では鳴らさない
        prevUiSounds = ClientScreenInteractionManager.isEnableDefaultUiSounds();
        ClientScreenInteractionManager.setEnableDefaultUiSounds(false);
        UiDocumentCompiler.clientBoxRenderer = (rootBox, w, h, kf, ss) -> new DisplayListBuilder(w, h, kf, ss).render(rootBox);

        String html;
        try (InputStream is = getClass().getResourceAsStream("/assets/enderframelib/enderframe_ui/linux_desktop.html")) {
            html = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        screen = new LinuxDesktopScreen();
        ClientWebManager.clearSession(screen.getWebSessionId());
        session = ClientWebManager.getOrCreateSession(screen.getWebSessionId());
        session.loadLocalHtml(html, 854, 480);
        screen.registerWebActions();
        screen.layoutInitialWindows(854, 480);
        refresh();
    }

    @AfterEach
    public void teardown() {
        ClientWebManager.clearSession(screen.getWebSessionId());
        UiDocumentCompiler.clientBoxRenderer = prevRenderer;
        ClientScreenInteractionManager.setEnableDefaultUiSounds(prevUiSounds);
    }

    /** 状態を DOM へ流し、描画キューを処理してヒットテストを最新にする。 */
    private void refresh() {
        screen.state.syncToDom();
        session.elements();
    }

    private void click(double x, double y) {
        screen.mouseClicked(x, y, 0);
        refresh();
        screen.mouseReleased(x, y, 0);
        refresh();
    }

    private boolean menuOpen() {
        return "true".equals(screen.state.get("app_menu_open"));
    }

    /** id がそのまま一致する描画要素（要素本体）。 */
    private DisplayItem node(String id) {
        return session.elements().stream().filter(i -> id.equals(i.id())).findFirst().orElse(null);
    }

    private double[] center(String id) {
        DisplayItem item = node(id);
        assertNotNull(item, "#" + id + " が描画されていること");
        return new double[]{item.x() + item.translateX() + item.width() / 2.0, item.y() + item.translateY() + item.height() / 2.0};
    }

    @Test
    public void applicationsButtonTogglesMenu() {
        double[] btn = center("app-menu-btn");
        click(btn[0], btn[1]);
        assertTrue(menuOpen(), "1 回目のクリックで開く");
        click(btn[0], btn[1]);
        assertFalse(menuOpen(), "開いた状態でもう一度押すと閉じる（閉じてすぐ再オープンしない）");
    }

    @Test
    public void lastMenuItemLaunchesSystemMonitor() {
        double[] btn = center("app-menu-btn");
        click(btn[0], btn[1]);
        assertTrue(menuOpen());

        DisplayItem menu = node("app-menu");
        assertNotNull(menu);
        // System Monitor は 4 番目（最後）の項目。メニュー下端の少し上を押す
        double x = menu.x() + 60;
        double y = menu.y() + menu.height() - 30;
        assertTrue(y > 260, "4 番目の項目は旧判定の境界 y=260 より下にある: y=" + y);
        click(x, y);

        assertEquals("true", screen.state.get("sysmon_open"), "System Monitor が起動すること");
        assertFalse(menuOpen(), "起動後はメニューが閉じること");
    }

    @Test
    public void outsideClickClosesMenuWithoutClickingBehind() {
        double[] btn = center("app-menu-btn");
        click(btn[0], btn[1]);
        assertTrue(menuOpen());

        // メニューの外、ドック上の Notes を押す。閉じるだけで、エディタは起動しないこと
        DisplayItem notes = session.elements().stream()
                .filter(i -> i.events() != null && "win:toggle:editor".equals(i.events().get("onclick")))
                .findFirst().orElse(null);
        assertNotNull(notes, "ドックの Notes が描画されていること");
        // ドックの translate(-50%, 0) は要素本体（id が一致する NODE）が持つ
        DisplayItem dock = node("dock");
        assertTrue(dock.translateX() < 0, "ドックに translate(-50%, 0) が掛かっていること");
        double x = notes.x() + dock.translateX() + notes.width() / 2.0;
        double y = notes.y() + notes.height() / 2.0;
        click(x, y);

        assertFalse(menuOpen(), "メニュー外のクリックで閉じること");
        assertEquals("false", screen.state.get("editor_open"), "閉じるためのクリックが背面のドックに届かないこと");

        // 対照: メニューが閉じていれば同じ位置のクリックでエディタが起動する（押した位置がドックの Notes であること）
        click(x, y);
        assertEquals("true", screen.state.get("editor_open"), "同じ位置のクリックはドックの Notes に当たること");
    }

    private int[] countClicksOnDesktop() {
        int[] count = {0};
        session.rootNode().getElementById("desktop").addEventListener("click", e -> count[0]++, null);
        return count;
    }

    @Test
    public void titleBarClickWithoutMovingStillClicks() {
        int[] clicks = countClicksOnDesktop();
        LinuxDesktopScreen.DesktopWindowManager.WindowState docs = screen.windowManager().get("docs");
        click(docs.x + 60, docs.y + 12);
        assertEquals(1, clicks[0], "動かさずに離したら通常どおりクリックになる（対照）");
    }

    @Test
    public void draggingWindowMovesItAndDoesNotClickAtDrop() {
        int[] clicks = countClicksOnDesktop();
        LinuxDesktopScreen.DesktopWindowManager.WindowState docs = screen.windowManager().get("docs");
        int startX = docs.x;
        int startY = docs.y;
        double pressX = docs.x + 60;
        double pressY = docs.y + 12;

        screen.mouseClicked(pressX, pressY, 0);
        refresh();
        double targetX = pressX + 300;
        double targetY = pressY + 120;
        for (int i = 1; i <= 10; i++) {
            screen.mouseDragged(pressX + (targetX - pressX) * i / 10, pressY + (targetY - pressY) * i / 10, 0, 0, 0);
        }
        screen.mouseReleased(targetX, targetY, 0);
        refresh();

        assertEquals(startX + 300, docs.x, "ドラッグ量だけ横に動くこと");
        assertEquals(startY + 120, docs.y, "ドラッグ量だけ縦に動くこと");
        assertEquals(0, clicks[0], "ドラッグの解放でクリックを合成しないこと");

        // 抑止は 1 回限り。次の普通のクリック（ダブルクリック扱いにならない本文側）は届く
        click(docs.x + 100, docs.y + 100);
        assertEquals(1, clicks[0], "ドラッグ後の次のクリックは通常どおり届くこと");
    }

    // ------------------------------------------------------------------
    // ドラッグの滑らかさ（再レイアウトしない）と GNOME 風の端スナップ
    // ------------------------------------------------------------------

    private LinuxDesktopScreen.DesktopWindowManager.WindowState win(String id) {
        return screen.windowManager().get(id);
    }

    /** タイトルバーの左寄り（ボタンの無い所）を押して、steps 回に分けて (toX, toY) まで動かす。離さない。 */
    private void pressAndDrag(String id, double toX, double toY, int steps) {
        LinuxDesktopScreen.DesktopWindowManager.WindowState w = win(id);
        double pressX = w.x + 60;
        double pressY = w.y + 12;
        screen.mouseClicked(pressX, pressY, 0);
        refresh();
        for (int i = 1; i <= steps; i++) {
            screen.mouseDragged(pressX + (toX - pressX) * i / steps, pressY + (toY - pressY) * i / steps, 0, 0, 0);
        }
    }

    private void release(double x, double y) {
        screen.mouseReleased(x, y, 0);
        refresh();
    }

    @Test
    public void dragMovesWindowByPaintOffsetWithoutTouchingDom() {
        LinuxDesktopScreen.DesktopWindowManager.WindowState docs = win("docs");
        String domXBefore = screen.state.get("docs_x");
        double pressX = docs.x + 60;
        double pressY = docs.y + 12;
        int startX = docs.x;

        pressAndDrag("docs", pressX + 200, pressY + 50, 20);
        refresh();

        assertEquals(domXBefore, screen.state.get("docs_x"), "ドラッグ中は DOM の位置を変えない（毎フレーム再レイアウトしない）");
        DisplayItem shown = node("win-docs");
        assertNotNull(shown);
        assertEquals(200f, shown.translateX(), 0.01f, "描画上はカーソルに付いて動いていること");
        assertEquals(50f, shown.translateY(), 0.01f);

        // ヒットテストも描画上の位置に付いてくる（移動後の見出しの上で同じウィンドウに当たる）
        SessionContext.UIHitResult hit = session.hitTest(pressX + 200, pressY + 50);
        assertNotNull(hit);
        assertTrue(isInside(hit.element().baseId(), "win-docs"), "動かしたウィンドウに当たること: " + hit.element().id());

        release(pressX + 200, pressY + 50);
        assertEquals((startX + 200) + "px", screen.state.get("docs_x"), "離したら DOM に位置を確定する");
        DisplayItem committed = node("win-docs");
        assertEquals(0f, committed.translateX(), 0.01f, "確定後は描画上の平行移動を外す");
        assertEquals(startX + 200, committed.x(), 1.0f, "レイアウト上の位置が移動先になっていること");
    }

    private boolean isInside(String baseId, String ancestorId) {
        for (com.nishiyu.enderframelib.core.dom.node.HtmlNode n = session.rootNode().getNodeById(baseId); n != null; n = n.parent) {
            if (ancestorId.equals(n.getId())) return true;
        }
        return false;
    }

    @Test
    public void smallJitterIsNotADrag() {
        int[] clicks = countClicksOnDesktop();
        LinuxDesktopScreen.DesktopWindowManager.WindowState docs = win("docs");
        int startX = docs.x;
        double pressX = docs.x + 60;
        double pressY = docs.y + 12;
        screen.mouseClicked(pressX, pressY, 0);
        refresh();
        screen.mouseDragged(pressX + 2, pressY + 1, 0, 0, 0);
        release(pressX + 2, pressY + 1);
        assertEquals(startX, docs.x, "閾値未満の手ぶれでは動かさない");
        assertEquals(1, clicks[0], "手ぶれはクリックとして扱う");
    }

    @Test
    public void dragToLeftEdgeShowsPreviewAndTilesLeftHalf() {
        LinuxDesktopScreen.DesktopWindowManager.WindowState docs = win("docs");
        int origW = docs.width;
        pressAndDrag("docs", 1, 200, 20);
        refresh();
        assertEquals("true", screen.state.get("snap_preview_visible"), "端に触れたらプレビュー枠を出す");
        assertEquals("0px", screen.state.get("snap_x"));
        assertEquals("427px", screen.state.get("snap_w"), "左半分");
        DisplayItem preview = node("snap-preview");
        assertNotNull(preview, "プレビュー枠が描画されていること");

        release(1, 200);
        assertTrue(docs.isTiled);
        assertEquals(0, docs.x);
        assertEquals(DesktopLayout.TOP_BAR_HEIGHT, docs.y);
        assertEquals(427, docs.width);
        assertEquals(480 - DesktopLayout.TOP_BAR_HEIGHT - DesktopLayout.DOCK_HEIGHT, docs.height);
        assertEquals("false", screen.state.get("snap_preview_visible"), "離したらプレビュー枠を消す");
        assertNull(node("snap-preview"), "プレビュー枠は描画されない");
        assertEquals(origW, docs.prevW, "元の大きさを復元用に覚えている");
    }

    @Test
    public void dragToRightEdgeTilesRightHalf() {
        pressAndDrag("terminal", 853, 200, 20);
        release(853, 200);
        LinuxDesktopScreen.DesktopWindowManager.WindowState term = win("terminal");
        assertTrue(term.isTiled);
        assertEquals(427, term.x);
        assertEquals(427, term.width);
    }

    @Test
    public void dragToTopEdgeMaximizes() {
        pressAndDrag("docs", 400, 5, 20);
        refresh();
        assertEquals("true", screen.state.get("snap_preview_visible"));
        assertEquals("854px", screen.state.get("snap_w"));
        release(400, 5);
        LinuxDesktopScreen.DesktopWindowManager.WindowState docs = win("docs");
        assertTrue(docs.isMaximized);
        assertEquals("true", screen.state.get("docs_max"));
    }

    @Test
    public void leavingEdgeCancelsPreview() {
        pressAndDrag("docs", 1, 200, 10);
        refresh();
        assertEquals("true", screen.state.get("snap_preview_visible"));
        screen.mouseDragged(300, 200, 0, 0, 0);
        refresh();
        assertEquals("false", screen.state.get("snap_preview_visible"), "端から離れたらプレビューを消す");
        release(300, 200);
        assertFalse(win("docs").isTiled, "端から離れて離したら吸着しない");
    }

    @Test
    public void draggingMaximizedWindowRestoresSizeUnderCursor() {
        LinuxDesktopScreen.DesktopWindowManager.WindowState docs = win("docs");
        int origW = docs.width;
        int origH = docs.height;
        screen.windowManager().maximize("docs", 854, 480, DesktopLayout.TOP_BAR_HEIGHT, DesktopLayout.DOCK_HEIGHT);
        screen.syncAllStates();
        refresh();
        assertTrue(docs.isMaximized);

        // 最大化したタイトルバーの右寄り (幅の 75%) を掴んで下へ引き出す
        double pressX = 854 * 0.75;
        double pressY = DesktopLayout.TOP_BAR_HEIGHT + 12;
        screen.mouseClicked(pressX, pressY, 0);
        refresh();
        for (int i = 1; i <= 10; i++) {
            screen.mouseDragged(pressX, pressY + 15 * i, 0, 0, 0);
        }
        refresh();
        assertFalse(docs.isMaximized, "引き出したら最大化を解く");
        assertEquals(origW, docs.width, "元の幅に戻る");
        assertEquals(origH, docs.height, "元の高さに戻る");
        double cursorY = pressY + 150;
        assertTrue(pressX >= docs.x && pressX <= docs.x + docs.width, "カーソルはウィンドウの上にある");
        assertEquals(0.75, (pressX - docs.x) / docs.width, 0.02, "掴んだ位置の割合を保つ");
        assertTrue(cursorY >= docs.y && cursorY <= docs.y + 32, "カーソルはタイトルバーの上にある");

        release(pressX, cursorY);
        assertFalse(docs.isMaximized);
        assertEquals(docs.x + "px", screen.state.get("docs_x"));
    }

    @Test
    public void draggingTiledWindowRestoresOriginalSize() {
        LinuxDesktopScreen.DesktopWindowManager.WindowState docs = win("docs");
        int origW = docs.width;
        pressAndDrag("docs", 1, 200, 20);
        release(1, 200);
        assertTrue(docs.isTiled);

        pressAndDrag("docs", 400, 300, 20);
        release(400, 300);
        assertFalse(docs.isTiled, "タイルから引き出したら解除");
        assertEquals(origW, docs.width, "タイル前の幅に戻る");
    }

    /** 描画要素の mc-parent を辿った祖先の id（自身は含まない）。 */
    private static java.util.List<String> parentChain(DisplayItem item, java.util.Map<String, DisplayItem> byId) {
        java.util.List<String> chain = new java.util.ArrayList<>();
        DisplayItem cur = item;
        for (int guard = 0; cur != null && guard < 64; guard++) {
            String parent = cur.events() != null ? cur.events().get("mc-parent") : null;
            if (parent == null) break;
            chain.add(parent);
            cur = byId.get(parent);
        }
        return chain;
    }

    /** 描画要素の持ち主（またはその祖先）が data-bind の text で中身を作る要素なら、その data-bind の値。 */
    private String boundTextKey(String baseId) {
        for (com.nishiyu.enderframelib.core.dom.node.HtmlNode n = session.rootNode().getNodeById(baseId); n != null; n = n.parent) {
            String bind = n.attrs.get("data-bind");
            if (bind != null && bind.contains("text:")) return bind;
        }
        return null;
    }

    @Test
    public void boundTextsFollowTheirWindowWhileDragging() {
        // 書き換わる数字（Memory）とターミナルの出力は data-bind の text で中身を作り直す要素
        screen.windowManager().open("sysmon");
        screen.syncAllStates();
        screen.state.set("sys_mem", "433 MB / 16320 MB (2%)");
        refresh();

        java.util.Map<String, DisplayItem> byId = new java.util.HashMap<>();
        for (DisplayItem i : session.elements()) {
            assertFalse(i.id().isEmpty() || i.id().startsWith("_"), "描画要素の id が空の節から作られていないこと: " + i.id());
            byId.put(i.id(), i);
        }

        pressAndDrag("sysmon", win("sysmon").x + 260, win("sysmon").y + 112, 10);
        refresh();
        int memory = 0;
        int terminal = 0;
        for (DisplayItem i : session.elements()) {
            if (i.type() != com.nishiyu.enderframelib.core.paint.model.ElementType.TEXT) continue;
            String bound = boundTextKey(i.baseId());
            if (bound == null) continue;
            String window = isInside(i.baseId(), "win-sysmon") ? "win-sysmon"
                    : isInside(i.baseId(), "win-terminal") ? "win-terminal" : null;
            if (window == null) continue;
            assertTrue(parentChain(i, byId).contains(window),
                    bound + " の文字「" + i.text() + "」の親の連鎖に " + window + " があり、ウィンドウの移動に付いてくること: " + parentChain(i, byId));
            if (bound.contains("sys_mem")) memory++;
            if (bound.contains("terminal_output")) terminal++;
        }
        int checked = (memory > 0 ? 1 : 0) + (terminal > 0 ? 1 : 0);
        assertTrue(checked >= 2, "Memory の値とターミナルの出力の両方を確かめたこと: " + checked);
        assertEquals(200f, node("win-sysmon").translateX(), 0.01f, "ドラッグ中のウィンドウに平行移動が掛かっていること");
        release(win("sysmon").x + 60, win("sysmon").y + 12);
    }
}
