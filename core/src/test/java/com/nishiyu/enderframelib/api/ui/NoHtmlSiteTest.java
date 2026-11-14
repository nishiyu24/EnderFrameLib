package com.nishiyu.enderframelib.api.ui;

import com.nishiyu.enderframelib.api.EnderEngine;
import com.nishiyu.enderframelib.api.WebViewSurface;
import com.nishiyu.enderframelib.api.dom.DOMRect;
import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.nishiyu.enderframelib.api.ui.UI.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * HTML ファイルおよび HTML 文字列を一切使わず、Java の軽量 DOM ビルダー ({@link UI}) と {@link State} だけで
 * 1 から Web サイト（ヘッダ・タブ遷移・状態管理・検索リスト・テーマ切替・タイマー・描画）を構築して検証するテストケース。
 */
class NoHtmlSiteTest {

    @Test
    void buildsAndInteractsWithFullSiteWithoutAnyHtml(@TempDir Path dir) throws Exception {
        try (WebViewSurface surface = EnderEngine.empty().size(960, 640).clearColor(0xFFFFFFFF).build()) {
            Element body = surface.dom().getBody();
            assertNotNull(body);
            assertEquals(0, body.getChildElementCount(), "初期状態の body は空であること");

            var activeTab = State.of("home");
            var count = State.of(0);
            var items = State.of(List.of("Pickaxe", "Lantern", "Compass"));
            var query = State.of("");
            var darkMode = State.of(true);

            var filteredItems = State.derive(() -> {
                String q = query.get().trim().toLowerCase();
                if (q.isEmpty()) return items.get();
                return items.get().stream()
                        .filter(it -> it.toLowerCase().contains(q))
                        .toList();
            }, items, query);

            var footerText = State.derive(
                    () -> "tab=" + activeTab.get()
                            + " | count=" + count.get()
                            + " | items=" + items.get().size()
                            + " | theme=" + (Boolean.TRUE.equals(darkMode.get()) ? "dark" : "light"),
                    activeTab, count, items, darkMode
            );

            // body 全体のスタイルも HTML なしで Java から直接設定
            Runnable applyBodyTheme = () -> {
                boolean dark = Boolean.TRUE.equals(darkMode.get());
                setStyle(body, Map.of(
                        "margin", "0",
                        "fontFamily", "sans-serif",
                        "background", dark ? "#0f172a" : "#f8fafc",
                        "color", dark ? "#f8fafc" : "#0f172a"
                ));
            };
            applyBodyTheme.run();
            darkMode.onChange(v -> applyBodyTheme.run());

            render(body,
                    div(id("app"),
                            sx("display", "flex", "flexDirection", "column", "width", "960px", "height", "640px"),
                            Header(activeTab, items, darkMode),
                            mainTag(
                                    sx("flex", "1", "padding", "24px", "display", "flex", "flexDirection", "column", "gap", "16px"),
                                    watch(activeTab, tab -> switch (tab) {
                                        case "catalog" -> CatalogPage(items, filteredItems, query);
                                        case "settings" -> SettingsPage(darkMode);
                                        default -> HomePage(count, darkMode);
                                    })
                            ),
                            Footer(footerText, darkMode)
                    )
            );

            // 1. 初期描画と PNG 出力・ピクセル検証
            assertTrue(surface.frame(0, true), "初回フレームが描画されること");
            Path png = dir.resolve("no-html-site.png");
            surface.writePng(png);
            byte[] pngBytes = Files.readAllBytes(png);
            assertTrue(pngBytes.length > 8 && pngBytes[0] == (byte) 0x89 && pngBytes[1] == (byte) 'P');

            // テーマ背景 (#0f172a -> R=15, G=23, B=42) が描画されていること
            Element previewBox = $("#preview-box", body);
            assertNotNull(previewBox);
            DOMRect previewRect = previewBox.getBoundingClientRect();
            assertFalse(previewRect.isEmpty(), "preview-box がレイアウトされていること");

            int[] pixels = surface.pixels();
            int pxInsidePreview = samplePixel(pixels, surface.pixelWidth(), previewRect);
            assertEquals(0x38, (pxInsidePreview >> 16) & 0xFF, 2, "preview-box の初期色 (#38bdf8) R");
            assertEquals(0xbd, (pxInsidePreview >> 8) & 0xFF, 2, "preview-box の初期色 (#38bdf8) G");
            assertEquals(0xf8, pxInsidePreview & 0xFF, 2, "preview-box の初期色 (#38bdf8) B");

            // 2. Home タブ: ボタンをクリックしてリアクティブ更新を検証
            assertEquals("Count: 0", $("#count-text", body).getTextContent());
            clickElement(surface, $("#btn-inc", body), 16);
            assertEquals("Count: 1", $("#count-text", body).getTextContent());

            clickElement(surface, $("#btn-inc", body), 48);
            assertEquals("Count: 2", $("#count-text", body).getTextContent());

            // 3. Catalog タブへクリック遷移（watch による動的ページ切り替え）
            clickElement(surface, $("#tab-catalog", body), 80);
            assertNull($("#btn-inc", body), "Home ページがアンマウントされていること");
            assertNotNull($("#search-input", body), "Catalog ページがマウントされていること");
            assertEquals(3, $$(".catalog-item", body).size());

            // アイテム追加ボタンをクリック
            clickElement(surface, $("#btn-add-item", body), 112);
            assertEquals(4, $$(".catalog-item", body).size());

            // 検索入力欄をクリックして "lan" をキー入力し、動的フィルタリングを検証
            clickElement(surface, $("#search-input", body), 144);
            assertEquals("search-input", surface.focusedId());
            assertTrue(surface.charTyped('l'));
            assertTrue(surface.charTyped('a'));
            assertTrue(surface.charTyped('n'));
            surface.frame(176, false);
            assertEquals(1, $$(".catalog-item", body).size());
            assertEquals("Lantern", $(".catalog-item-name", body).getTextContent());

            // 4. Settings タブへ遷移し、テーマをダーク → ライトへ切り替え
            clickElement(surface, $("#tab-settings", body), 208);
            assertNotNull($("#btn-theme", body));
            clickElement(surface, $("#btn-theme", body), 240);

            String footer = $("#site-footer", body).getTextContent();
            assertTrue(footer.contains("tab=settings"), footer);
            assertTrue(footer.contains("count=2"), footer);
            assertTrue(footer.contains("items=4"), footer);
            assertTrue(footer.contains("theme=light"), footer);

            // 背景ピクセルがライトテーマ (#f8fafc) に変わったことを検証
            Element mainEl = $("main", body);
            int bgPixel = samplePixel(surface.pixels(), surface.pixelWidth(), mainEl.getBoundingClientRect());
            assertEquals(0xf8, (bgPixel >> 16) & 0xFF, 2, "ライトテーマ背景 R");
            assertEquals(0xfa, (bgPixel >> 8) & 0xFF, 2, "ライトテーマ背景 G");
            assertEquals(0xfc, bgPixel & 0xFF, 2, "ライトテーマ背景 B");
        }
    }

    @Test
    void standaloneViewWithoutHtmlRunsTimersAndBuildsDom() throws Exception {
        StandaloneView view = new StandaloneView(640, 360);
        try {
            Element body = view.document().getBody();
            var ticks = State.of(0);
            var bars = map(3, i -> State.of(20 + i * 20));

            setStyle(body, Map.of("margin", "0", "background", "#111827", "color", "#ffffff"));
            render(body,
                    div(id("dashboard"), sx("padding", "20px", "display", "flex", "flexDirection", "column", "gap", "12px"),
                            h1(id("title"), "Pure Java UI"),
                            p(id("ticks"), ticks.map(t -> "Ticks: " + t)),
                            div(id("bars"), sx("display", "flex", "flexDirection", "column", "gap", "8px"),
                                    map(bars, w -> div(
                                            className("progress-bar"),
                                            sx("height", "16px", "background", "#22d3ee", "borderRadius", "6px",
                                                    "width", w.map(px -> px + "px"))
                                    ))
                            )
                    )
            );

            view.timers().setInterval(() -> {
                ticks.update(t -> t + 1);
                for (int i = 0; i < bars.size(); i++) {
                    int idx = i;
                    bars.get(i).update(w -> w + (idx + 1) * 10);
                }
            }, 15);

            try (var cpu = new SoftwareRenderBackend(640, 360, 1f)) {
                view.frame(0, cpu, true);
                assertEquals("Ticks: 0", $("#ticks", view.root()).getTextContent());
                assertEquals(3, $$(".progress-bar", view.root()).size());

                // UiTimers.runDueTimers() は System.currentTimeMillis() 基準で発火するため実時間を進める
                long deadline = System.currentTimeMillis() + 500;
                while (System.currentTimeMillis() < deadline
                        && "Ticks: 0".equals($("#ticks", view.root()).getTextContent())) {
                    Thread.sleep(20);
                    view.frame(System.currentTimeMillis(), cpu, false);
                }
                assertNotEquals("Ticks: 0", $("#ticks", view.root()).getTextContent());
                assertFalse($("#title", view.root()).getBoundingClientRect().isEmpty());
            }
        } finally {
            view.close();
        }
    }

    @Test
    void standaloneViewThemeSwitchUpdatesBodyDirectlyOnButtonClick() {
        StandaloneView view = new StandaloneView(640, 360);
        try {
            Element body = view.document().getBody();
            var activeTab = State.of("home");
            var darkMode = State.of(true);

            Runnable applyTheme = () -> {
                boolean dark = Boolean.TRUE.equals(darkMode.get());
                setStyle(body, Map.of(
                        "margin", "0",
                        "background", dark ? "#0f172a" : "#f1f5f9",
                        "color", dark ? "#f8fafc" : "#0f172a"
                ));
            };
            applyTheme.run();
            darkMode.onChange(v -> applyTheme.run());

            render(body,
                    div(id("shell"),
                            sx("display", "flex", "flexDirection", "column", "gap", "12px", "padding", "16px"),
                            button(id("tab-settings"), type("button"),
                                    sx("width", "120px", "height", "32px"),
                                    onClick(e -> activeTab.set("settings")),
                                    "Settings"),
                            watch(activeTab, tab -> "settings".equals(tab)
                                    ? button(id("btn-switch-theme"), type("button"),
                                            sx("width", "180px", "height", "36px"),
                                            onClick(e -> darkMode.update(d -> !Boolean.TRUE.equals(d))),
                                            darkMode.when("Switch to Light Theme", "Switch to Dark Theme"))
                                    : div(id("home-view"), "Home"))
                    )
            );

            try (var cpu = new SoftwareRenderBackend(640, 360, 1f)) {
                String session = cpu.sessionId();
                view.frame(0, cpu, true);
                assertEquals("#0f172a", body.getStyle().getPropertyValue("background"));

                clickStandaloneElement(view, cpu, session, $("#tab-settings", body), 16);
                Element themeBtn = $("#btn-switch-theme", body);
                assertNotNull(themeBtn);
                assertEquals("Switch to Light Theme", themeBtn.getTextContent());

                clickStandaloneElement(view, cpu, session, themeBtn, 48);
                assertEquals("#f1f5f9", body.getStyle().getPropertyValue("background"));
                assertEquals("#0f172a", body.getStyle().getPropertyValue("color"));
                assertEquals("Switch to Dark Theme", themeBtn.getTextContent());
            }
        } finally {
            view.close();
        }
    }

    private static void clickStandaloneElement(StandaloneView view, SoftwareRenderBackend cpu, String session, Element target, long nowMs) {
        assertNotNull(target);
        DOMRect r = target.getBoundingClientRect();
        assertFalse(r.isEmpty());
        view.mouseMove(r.x() + r.width() / 2.0, r.y() + r.height() / 2.0, session);
        view.frame(nowMs, cpu, false);
        view.mouseButton(true, session);
        view.mouseButton(false, session);
        view.frame(nowMs + 16, cpu, false);
    }

    // ---- サイトを構成する React 関数コンポーネント群 (HTML ゼロ) ----

    private static VNode Header(State<String> activeTab, State<List<String>> items, State<Boolean> darkMode) {
        return header(
                sx("display", "flex", "alignItems", "center", "gap", "12px",
                        "padding", "14px 24px",
                        "background", darkMode.when("#1e293b", "#e2e8f0")),
                span(sx("fontSize", "18px", "fontWeight", "bold", "flex", "1"), "No-HTML React Site"),
                span(id("header-count"),
                        sx("fontSize", "12px", "padding", "4px 10px", "borderRadius", "999px",
                                "background", "#0284c7", "color", "#ffffff"),
                        items.map(list -> list.size() + " items")),
                NavTab("tab-home", "Overview", "home", activeTab),
                NavTab("tab-catalog", "Catalog", "catalog", activeTab),
                NavTab("tab-settings", "Settings", "settings", activeTab)
        );
    }

    private static VNode NavTab(String tabId, String label, String key, State<String> activeTab) {
        var isActive = activeTab.map(t -> t.equals(key));
        return button(
                id(tabId),
                type("button"),
                sx("padding", "8px 14px", "border", "0", "borderRadius", "8px", "cursor", "pointer",
                        "background", isActive.when("#38bdf8", "#334155"),
                        "color", isActive.when("#0f172a", "#f8fafc"),
                        "fontWeight", "bold"),
                onClick(e -> activeTab.set(key)),
                label
        );
    }

    private static VNode HomePage(State<Integer> count, State<Boolean> darkMode) {
        return section(
                sx("padding", "20px", "borderRadius", "14px",
                        "background", darkMode.when("#1e293b", "#ffffff"),
                        "display", "flex", "flexDirection", "column", "gap", "14px"),
                h2("100% Pure Java + React DSL"),
                p(id("count-text"), count.map(c -> "Count: " + c)),
                div(sx("display", "flex", "gap", "10px"),
                        button(id("btn-inc"), type("button"),
                                sx("padding", "10px 18px", "border", "0", "borderRadius", "8px",
                                        "background", "#2563eb", "color", "#ffffff"),
                                onClick(e -> count.update(c -> c + 1)),
                                "Increment"),
                        button(id("btn-reset"), type("button"),
                                sx("padding", "10px 18px", "border", "0", "borderRadius", "8px",
                                        "background", "#475569", "color", "#ffffff"),
                                onClick(e -> count.set(0)),
                                "Reset")
                ),
                div(id("preview-box"),
                        sx("width", count.map(c -> (120 + c * 20) + "px"),
                                "height", "48px",
                                "borderRadius", "10px",
                                "background", count.map(c -> c == 0 ? "#38bdf8" : "#f59e0b")))
        );
    }

    private static VNode CatalogPage(State<List<String>> items, State<List<String>> filteredItems, State<String> query) {
        return section(
                sx("display", "flex", "flexDirection", "column", "gap", "12px"),
                div(sx("display", "flex", "gap", "10px", "alignItems", "center"),
                        input(id("search-input"), type("text"), placeholder("Filter items..."),
                                sx("padding", "8px 12px", "borderRadius", "8px", "border", "1px solid #64748b",
                                        "width", "260px", "background", "#0f172a", "color", "#ffffff"),
                                onInputValue(query::set)),
                        button(id("btn-add-item"), type("button"),
                                sx("padding", "8px 14px", "border", "0", "borderRadius", "8px",
                                        "background", "#10b981", "color", "#052e16", "fontWeight", "bold"),
                                onClick(e -> items.update(prev -> {
                                    List<String> next = new ArrayList<>(prev);
                                    next.add("Item #" + (prev.size() + 1));
                                    return next;
                                })),
                                "Add Item")
                ),
                div(sx("display", "flex", "flexDirection", "column", "gap", "8px"),
                        each(filteredItems, name -> div(
                                className("catalog-item"),
                                sx("display", "flex", "justifyContent", "space-between", "alignItems", "center",
                                        "padding", "10px 14px", "borderRadius", "10px", "background", "#1e293b", "color", "#f8fafc"),
                                span(className("catalog-item-name"), name)
                        ))
                )
        );
    }

    private static VNode SettingsPage(State<Boolean> darkMode) {
        return section(
                sx("padding", "20px", "borderRadius", "14px",
                        "background", darkMode.when("#1e293b", "#ffffff"),
                        "display", "flex", "flexDirection", "column", "gap", "12px"),
                h2("Settings"),
                button(id("btn-theme"), type("button"),
                        sx("padding", "10px 18px", "border", "0", "borderRadius", "8px",
                                "background", "#8b5cff", "color", "#ffffff", "width", "200px"),
                        onClick(e -> darkMode.update(d -> !Boolean.TRUE.equals(d))),
                        darkMode.when("Switch to Light", "Switch to Dark"))
        );
    }

    private static VNode Footer(State<String> footerText, State<Boolean> darkMode) {
        return footer(
                id("site-footer"),
                sx("padding", "12px 24px", "fontSize", "12px",
                        "background", darkMode.when("#1e293b", "#e2e8f0")),
                footerText
        );
    }

    private static void clickElement(WebViewSurface surface, Element target, long nowMs) {
        assertNotNull(target, "クリック対象要素が見つかりません");
        DOMRect r = target.getBoundingClientRect();
        assertFalse(r.isEmpty(), "要素のサイズが 0 です");
        double cx = r.x() + r.width() / 2.0;
        double cy = r.y() + r.height() / 2.0;
        surface.mouseMove(cx, cy);
        surface.frame(nowMs, false);
        surface.mouseButton(true);
        surface.mouseButton(false);
        surface.frame(nowMs + 16, false);
    }

    private static int samplePixel(int[] pixels, int stride, DOMRect rect) {
        int x = Math.max(0, Math.min(stride - 1, Math.round(rect.x() + rect.width() / 2f)));
        int y = Math.max(0, Math.round(rect.y() + rect.height() / 2f));
        return pixels[y * stride + x];
    }
}
