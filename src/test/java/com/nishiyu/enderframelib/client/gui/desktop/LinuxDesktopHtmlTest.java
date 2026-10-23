package com.nishiyu.enderframelib.client.gui.desktop;

import com.nishiyu.enderframelib.client.EnderFrameStandalone;
import com.nishiyu.enderframelib.client.HitTestIndex;
import com.nishiyu.enderframelib.client.SessionContext;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.client.gui.desktop.LinuxDesktopScreen.DesktopLayout;
import com.nishiyu.enderframelib.client.gui.desktop.LinuxDesktopScreen.DesktopWindowManager;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.Bindings;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

public class LinuxDesktopHtmlTest {

    private static String desktopHtml;

    @BeforeAll
    public static void loadHtml() throws Exception {
        try (InputStream is = LinuxDesktopHtmlTest.class.getResourceAsStream("/assets/enderframelib/enderframe_ui/linux_desktop.html")) {
            assertNotNull(is, "linux_desktop.html が classpath に存在すること");
            desktopHtml = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void testHtmlParsingAndLayout() {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(desktopHtml, "", 854, 480);
        assertNotNull(doc, "HTML がパースできること");
        assertNotNull(doc.root, "ルートノードが存在すること");

        LayoutBox rootBox = compiler.layoutDocument(
                doc, 854, 480, Map.of(), new ScrollState(), EnderFrameStandalone.defaultFontResolver(), new InteractionState()
        );
        assertNotNull(rootBox, "レイアウトツリーが構築できること");
        assertEquals(854, (int) rootBox.w);
        assertEquals(480, (int) rootBox.h);
    }

    /** LinuxDesktopScreen#syncAllStates と同じ形の状態を DOM に適用してレイアウトした結果。 */
    private record Laid(Document doc, LayoutBox root, List<DisplayItem> items) {}

    private static Laid layoutWithState(String activeTab, boolean menuOpen) {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(desktopHtml, "", 854, 480);

        Map<String, String> values = new HashMap<>();
        for (String tab : List.of("overview", "desktop", "terminal", "web", "commands")) {
            values.put("doc_tab_" + tab, String.valueOf(tab.equals(activeTab)));
        }
        values.put("app_menu_open", String.valueOf(menuOpen));
        values.put("clock_time", "12:34");
        DesktopWindowManager wm = new DesktopWindowManager();
        for (String id : List.of("docs", "terminal", "editor", "sysmon")) wm.registerWindow(id, id, 0, 0, 10, 10);
        wm.open("terminal");
        wm.open("docs");
        DesktopLayout.placeInitialWindows(wm, 854, 480);
        for (DesktopWindowManager.WindowState win : wm.allWindows()) {
            values.put(win.id + "_open", String.valueOf(win.isVisible()));
            values.put(win.id + "_active", String.valueOf(win.id.equals(wm.activeWindowId())));
            values.put(win.id + "_max", "false");
            values.put(win.id + "_x", win.x + "px");
            values.put(win.id + "_y", win.y + "px");
            values.put(win.id + "_w", win.width + "px");
            values.put(win.id + "_h", win.height + "px");
            values.put(win.id + "_z", String.valueOf(win.zIndex));
            values.put(win.id + "_dock_active", String.valueOf(win.isVisible()));
        }
        new Bindings.ReactiveDomBinder().sync(doc.root, values, Map.of(), Set.of(Bindings.TemplateDeps.ALWAYS));

        LayoutBox rootBox = compiler.layoutDocument(
                doc, 854, 480, Map.of(), new ScrollState(), EnderFrameStandalone.defaultFontResolver(), new InteractionState()
        );
        List<DisplayItem> items = new DisplayListBuilder(854, 480, doc.sheet.keyframes, new ScrollState()).render(rootBox);
        return new Laid(doc, rootBox, items);
    }

    private static void collect(LayoutBox box, Predicate<LayoutBox> filter, List<LayoutBox> out) {
        if (box == null) return;
        if (box.node != null && filter.test(box)) out.add(box);
        for (LayoutBox child : box.children) collect(child, filter, out);
    }

    private static List<LayoutBox> find(LayoutBox root, Predicate<LayoutBox> filter) {
        List<LayoutBox> out = new ArrayList<>();
        collect(root, filter, out);
        return out;
    }

    private static LayoutBox byId(LayoutBox root, String id) {
        List<LayoutBox> found = find(root, b -> id.equals(b.node.getId()));
        assertFalse(found.isEmpty(), "#" + id + " の箱があること");
        return found.get(0);
    }

    private static LayoutBox byClass(LayoutBox root, String cls) {
        List<LayoutBox> found = find(root, b -> b.node.hasClass(cls));
        assertFalse(found.isEmpty(), "." + cls + " の箱があること");
        return found.get(0);
    }

    @Test
    public void testEveryDocTabStacksContentVertically() {
        for (String tab : List.of("overview", "desktop", "terminal", "web", "commands")) {
            Laid laid = layoutWithState(tab, false);
            List<LayoutBox> pages = find(laid.root(), b -> b.node.hasClass("docs-page") && b.w > 0 && b.h > 0);
            assertEquals(1, pages.size(), tab + ": 表示中のページは 1 つだけ");
            LayoutBox page = pages.get(0);
            LayoutBox content = byClass(laid.root(), "docs-content");

            List<LayoutBox> blocks = page.children.stream().filter(c -> c.node != null && c.node.tag != null
                    && !c.node.tag.startsWith("#") && c.h > 0).toList();
            assertTrue(blocks.size() >= 3, tab + ": 見出し・本文・カード/表が並ぶこと");
            for (int i = 1; i < blocks.size(); i++) {
                LayoutBox prev = blocks.get(i - 1);
                LayoutBox cur = blocks.get(i);
                assertTrue(cur.y >= prev.y + prev.h - 0.5f,
                        tab + ": <" + cur.node.tag + "> は前の <" + prev.node.tag + "> の下に積まれること (縦並び)");
                assertEquals(page.x, cur.x, 1.0f, tab + ": 子要素の左端がそろうこと");
            }
            assertTrue(page.w <= content.w + 0.5f, tab + ": ページが本文領域の幅に収まること");
        }
    }

    @Test
    public void testClockAndDockAreHorizontallyCentered() {
        Laid laid = layoutWithState("overview", false);

        LayoutBox clock = byClass(laid.root(), "top-center");
        float[] clockTr = DisplayListBuilderAccess.translate(clock);
        assertEquals(427f, clock.x + clockTr[0] + clock.w / 2f, 1.5f, "時計がトップパネルの中央にあること");

        LayoutBox dock = byId(laid.root(), "dock");
        float[] dockTr = DisplayListBuilderAccess.translate(dock);
        assertEquals(427f, dock.x + dockTr[0] + dock.w / 2f, 1.5f, "ドックが画面下部の中央にあること");

        DisplayItem dockItem = laid.items().stream().filter(i -> "dock".equals(i.id())).findFirst().orElse(null);
        assertNotNull(dockItem, "ドックの描画要素があること");
        assertEquals(-dock.w / 2f, dockItem.translateX(), 1.0f, "描画要素に translate(-50%, 0) が反映されること");
    }

    @Test
    public void testAppMenuBoundsCoverAllItems() {
        Laid laid = layoutWithState("overview", true);
        LayoutBox menu = byId(laid.root(), "app-menu");
        DisplayLayoutRect menuRect = DisplayLayoutRect.of(laid.items(), "app-menu");
        assertNotNull(menuRect.rect(), "開いたメニューの描画要素があること");

        List<LayoutBox> menuItems = find(menu, b -> b.node.hasClass("menu-item"));
        assertEquals(4, menuItems.size());
        LayoutBox last = menuItems.get(3);
        assertTrue(last.y + last.h > 260, "4 番目の項目は y=260 より下まで伸びる（旧判定ではメニュー外扱いだった）");
        for (LayoutBox item : menuItems) {
            double cx = item.x + item.w / 2.0;
            double cy = item.y + item.h / 2.0;
            assertFalse(DesktopLayout.shouldCloseAppMenu(cx, cy, menuRect.rect(), DisplayLayoutRect.of(laid.items(), "app-menu-btn").rect()),
                    "メニュー項目の上の押下でメニューを閉じないこと: y=" + cy);
        }

        DesktopLayout.Rect button = DisplayLayoutRect.of(laid.items(), "app-menu-btn").rect();
        assertNotNull(button, "Applications ボタンの描画要素があること");
        assertFalse(DesktopLayout.shouldCloseAppMenu(button.x() + button.width() / 2, button.y() + button.height() / 2, menuRect.rect(), button),
                "Applications ボタンの押下では閉じず、onclick でトグルさせること");
        assertTrue(DesktopLayout.shouldCloseAppMenu(600, 300, menuRect.rect(), button), "メニュー外では閉じること");
    }

    @Test
    public void testOpenAppMenuItemsWinHitTestOverWindows() {
        Laid laid = layoutWithState("overview", true);
        HitTestIndex index = new HitTestIndex(laid.items());
        List<LayoutBox> menuItems = find(byId(laid.root(), "app-menu"), b -> b.node.hasClass("menu-item"));
        assertEquals(4, menuItems.size());
        for (LayoutBox item : menuItems) {
            double cx = item.x + 40;
            double cy = item.y + item.h / 2.0;
            SessionContext.UIHitResult hit = index.hitTest("desktop_hit", cx, cy);
            assertNotNull(hit, "メニュー項目の上で何かに当たること");
            HtmlNode node = laid.doc().root.getNodeById(hit.element().baseId());
            boolean inMenuItem = false;
            for (HtmlNode n = node; n != null; n = n.parent) {
                if (n.hasClass("menu-item")) { inMenuItem = true; break; }
            }
            assertTrue(inMenuItem, "z-index: 1005 のメニュー項目が背面のウィンドウより優先して当たること: ("
                    + cx + "," + cy + ") -> " + hit.element().id());
        }
    }

    /** 描画要素の id から外形を引く（LinuxDesktopScreen#elementRect と同じく id が一致する要素本体）。 */
    private record DisplayLayoutRect(DesktopLayout.Rect rect) {
        static DisplayLayoutRect of(List<DisplayItem> items, String id) {
            return new DisplayLayoutRect(DesktopLayout.Rect.of(
                    items.stream().filter(i -> id.equals(i.id())).findFirst().orElse(null)));
        }
    }

    /** 箱の transform の平行移動量（描画と同じ解釈）。 */
    private static final class DisplayListBuilderAccess {
        static float[] translate(LayoutBox box) {
            return com.nishiyu.enderframelib.client.renderer.TransformParseTestBridge.translate(
                    box.style.get(CssProperty.TRANSFORM), box.w, box.h);
        }
    }

    @Test
    public void testHtmlAttributeConventions() {
        assertFalse(desktopHtml.contains("data-action="), "すべての操作は onclick を使用し、data-action は残さないこと");
        assertFalse(desktopHtml.contains("style.zIndex:"), "CSS プロパティ名に合わせて style.z-index を使用すること");
        assertTrue(desktopHtml.contains("onclick=\"menu:toggle\""));
        assertTrue(desktopHtml.contains("onclick=\"win:close:terminal\""));
        assertTrue(desktopHtml.contains("onclick=\"win:min:terminal\""));
        assertTrue(desktopHtml.contains("onclick=\"win:max:terminal\""));
        assertTrue(desktopHtml.contains("onclick=\"win:toggle:terminal\""));
        assertTrue(desktopHtml.contains("onclick=\"win:close:docs\""));
        assertTrue(desktopHtml.contains("onclick=\"win:toggle:docs\""));
        assertTrue(desktopHtml.contains("onclick=\"doc:tab:overview\""));
        assertTrue(desktopHtml.contains("onclick=\"app:launch:docs\""));
        assertTrue(desktopHtml.contains("style.z-index:terminal_z"));
        assertTrue(desktopHtml.contains("style.z-index:docs_z"));
    }

    @Test
    public void testPlaceholderHasPointerEventsNone() {
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        String html = "<input id=\"test-input\" placeholder=\"hello\">";
        Document doc = compiler.parseDocument(html, "", 854, 480);
        LayoutBox rootBox = compiler.layoutDocument(
                doc, 854, 480, Map.of(), new ScrollState(), EnderFrameStandalone.defaultFontResolver()
        );
        List<DisplayItem> items = new com.nishiyu.enderframelib.client.renderer.DisplayListBuilder(
                854, 480, doc.sheet.keyframes, new ScrollState()
        ).render(rootBox);
        DisplayItem phItem = items.stream()
                .filter(i -> i.id().equals("test-input_ph"))
                .findFirst()
                .orElse(null);
        assertNotNull(phItem, "test-input の placeholder 要素が存在すること");
        assertNotNull(phItem.events(), "placeholder の events が存在すること");
        assertEquals("none", phItem.events().get("pointer-events"), "placeholder はクリックを透過するため pointer-events: none であること");
    }

    @Test
    public void testStandaloneAwtRendering() {
        // AWT / Graphics2D によるオフスクリーンレンダリングが例外なく完了するかテスト
        BufferedImage img = EnderFrameStandalone.renderToImage(desktopHtml, 854, 480);
        assertNotNull(img, "画像が正常に生成されること");
        assertEquals(854, img.getWidth());
        assertEquals(480, img.getHeight());
    }

    @Test
    public void testRenderToFile() throws Exception {
        java.io.File outDir = new java.io.File("build/test-output");
        outDir.mkdirs();
        java.io.File outFile = new java.io.File(outDir, "linux_desktop_preview.png");
        EnderFrameStandalone.renderToFile(desktopHtml, 854, 480, outFile);
        assertTrue(outFile.exists(), "PNG画像が生成されていること");
    }
}
