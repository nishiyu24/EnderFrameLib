package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.LayoutMetrics;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.RenderKeys;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.GradientSpec;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * showcase.html を Web ブラウザ（Chrome）とマイクラ内で同じ見た目に描けることの検証。
 *
 * ゲーム内のビューポートはブラウザと同じく「実ピクセル ÷ デバイスピクセル比」で決まる
 * （{@code EnderScreenDelegate.WebViewport}）。ここでは最も狭い場合の 854×480 を Chrome の同じ大きさと比べる。
 * 字幅に依存しない幾何（グリッドの列・商品カード）は Chrome の実測値と一致すること、字幅に依存する部分
 * （ヘッダー・バッジ）は重なり・はみ出し・折り返しが起きないことを、字幅の狭い書体（{@link UniformFontFace}、
 * {@code minecraft:uniform}）と 1.5 倍ほど広い {@link FontResolver#FALLBACK} の両方で確かめる。
 * ゲーム内の実際の書体（システムの書体）で Chrome と同じ位置に並ぶことは {@link ShowcaseChromeParityTest} が確かめる。
 *
 * Chrome の実測値は、showcase.html を 854×480 の iframe に読み込んで {@code getBoundingClientRect} で採った
 * （Chrome headless、Windows）。
 */
public class ShowcaseWebComparisonTest {

    private static final int W = 854, H = 480;
    private static final float EPS = 0.5f;

    private static String showcaseHtml;
    private static UiDocumentCompiler compiler;

    @BeforeAll
    static void setUp() throws Exception {
        Path path = Path.of("src/main/resources/data/enderframelib/enderframe_ui/showcase.html");
        showcaseHtml = Files.readString(path, StandardCharsets.UTF_8);
        compiler = new UiDocumentCompiler();
    }

    private static RenderResult renderAt(int width, int height, FontResolver fonts) {
        Document doc = compiler.parseDocument(showcaseHtml, "", width, height);
        LayoutBox rootBox = compiler.layoutDocument(
                doc, width, height, Map.of(), new ScrollState(), fonts, new InteractionState());
        DisplayListBuilder renderer = new DisplayListBuilder(width, height, doc.sheet.keyframes, new ScrollState());
        List<DisplayItem> items = renderer.render(rootBox);
        return new RenderResult(rootBox, items);
    }

    private static RenderResult inGame() {
        return renderAt(W, H, UniformFontFace.RESOLVER);
    }

    /** ゲーム内の書体と、それより 1.5 倍ほど広い見積もり。 */
    private static final Map<String, FontResolver> FONTS = Map.of(
            "uniform", UniformFontFace.RESOLVER,
            "fallback", FontResolver.FALLBACK);

    record RenderResult(LayoutBox rootBox, List<DisplayItem> items) {
        List<LayoutBox> byClass(String cls) {
            List<LayoutBox> out = new ArrayList<>();
            collect(rootBox, b -> b.node != null && hasClass(b, cls), out);
            return out;
        }

        LayoutBox one(String cls) {
            List<LayoutBox> found = byClass(cls);
            assertFalse(found.isEmpty(), "." + cls + " が存在すること");
            return found.get(0);
        }

        LayoutBox byId(String id) {
            List<LayoutBox> out = new ArrayList<>();
            collect(rootBox, b -> b.node != null && id.equals(b.node.attrs.get("id")), out);
            assertFalse(out.isEmpty(), "#" + id + " が存在すること");
            return out.get(0);
        }

        List<DisplayItem> texts() {
            return items.stream().filter(i -> i.type() == ElementType.TEXT && i.text() != null).toList();
        }

        private static boolean hasClass(LayoutBox b, String cls) {
            String attr = b.node.attrs.get("class");
            if (attr == null) return false;
            for (String c : attr.trim().split("\\s+")) if (c.equals(cls)) return true;
            return false;
        }

        private static void collect(LayoutBox b, java.util.function.Predicate<LayoutBox> p, List<LayoutBox> out) {
            if (p.test(b)) out.add(b);
            for (LayoutBox c : b.children) collect(c, p, out);
        }
    }

    private static boolean overlaps(LayoutBox a, LayoutBox b) {
        return a.x < b.x + b.w - EPS && b.x < a.x + a.w - EPS && a.y < b.y + b.h - EPS && b.y < a.y + a.h - EPS;
    }

    private static void assertInside(LayoutBox inner, LayoutBox outer, String what) {
        assertTrue(inner.x >= outer.x - EPS && inner.x + inner.w <= outer.x + outer.w + EPS
                        && inner.y >= outer.y - EPS && inner.y + inner.h <= outer.y + outer.h + EPS,
                () -> String.format("%s: [%.1f,%.1f %.1fx%.1f] が [%.1f,%.1f %.1fx%.1f] の内側にあること",
                        what, inner.x, inner.y, inner.w, inner.h, outer.x, outer.y, outer.w, outer.h));
    }

    /** 箱の中のテキストが 1 行に収まっている（断片がすべて同じ行にある）。 */
    private static void assertSingleLine(LayoutBox box, String what) {
        List<Float> lineTops = new ArrayList<>();
        collectFragmentTops(box, lineTops);
        assertFalse(lineTops.isEmpty(), what + " にテキストがあること");
        float first = lineTops.get(0);
        for (float y : lineTops) {
            assertEquals(first, y, 1.0f, what + " のテキストが折り返さず 1 行に収まること");
        }
    }

    private static void collectFragmentTops(LayoutBox b, List<Float> out) {
        for (LayoutBox.InlineFragment f : b.inlineFragments) if (f.isText() && !f.text.isBlank()) out.add(f.y);
        for (LayoutBox c : b.children) collectFragmentTops(c, out);
    }

    @Nested
    @DisplayName("1. ヘッダー: 検索バーとアクションが重ならない")
    class HeaderTest {

        @Test
        @DisplayName("検索バーの中身は検索バーの枠内に収まり、ヘッダーの項目どうしは重ならない")
        void searchBarDoesNotCollide() {
            FONTS.forEach(this::searchBarDoesNotCollideWith);
        }

        private void searchBarDoesNotCollideWith(String name, FontResolver fonts) {
            RenderResult r = renderAt(W, H, fonts);
            LayoutBox header = r.byId("header_panel");
            LayoutBox brand = r.one("brand-logo");
            LayoutBox search = r.one("search-wrapper");
            LayoutBox actions = r.one("header-actions");

            for (LayoutBox child : search.getFlowChildren()) {
                assertInside(child, search, name + ": 検索バーの子 <" + child.node.tag + ">");
            }
            assertFalse(overlaps(brand, search), name + ": ロゴと検索バーが重ならない");
            assertFalse(overlaps(search, actions), name + ": 検索バーとアクションが重ならない");
            assertFalse(overlaps(brand, actions), name + ": ロゴとアクションが重ならない");
            for (LayoutBox item : List.of(brand, search, actions)) {
                assertInside(item, header, name + ": ヘッダーの項目");
            }
            for (LayoutBox badge : r.byClass("badge-pill")) {
                assertInside(badge, actions, name + ": バッジ");
            }
            // 検索入力は内容（padding と border）より細く潰れない
            LayoutBox input = r.byId("test_input");
            assertTrue(input.w >= input.horizontalDecoration() - EPS, name + ": 検索入力の幅 " + input.w);
        }

        @Test
        @DisplayName("ゲーム内の書体ではブラウザと同じく 1 行に並び、検索入力が十分な幅を持つ")
        void headerIsSingleRowInGame() {
            RenderResult r = inGame();
            LayoutBox brand = r.one("brand-logo");
            LayoutBox search = r.one("search-wrapper");
            LayoutBox actions = r.one("header-actions");

            // 左から ロゴ → 検索バー → アクション の順に 1 行（Chrome の実測と同じ並び）
            assertTrue(brand.x + brand.w <= search.x + EPS);
            assertTrue(search.x + search.w <= actions.x + EPS);
            float searchMid = search.y + search.h / 2;
            assertTrue(searchMid > actions.y && searchMid < actions.y + actions.h, "検索バーとアクションが同じ行にある");
            // アクションはヘッダーの右端に揃う（justify-content: space-between）
            LayoutBox header = r.byId("header_panel");
            assertEquals(header.x + header.w - header.bR - header.pR, actions.x + actions.w, 1.0f);

            LayoutBox select = r.byId("mode_select");
            assertEquals(128f, select.w, EPS, "select は指定幅 128px のまま縮まない（Chrome 実測 128）");
            LayoutBox input = r.byId("test_input");
            assertTrue(input.w >= 60, "検索入力は 60px 以上（Chrome 実測 114.9px）: " + input.w);
        }
    }

    @Nested
    @DisplayName("2. バッジのテキストが折り返さない")
    class BadgeWrappingTest {

        @Test
        @DisplayName("Wishlist / Cart / Account と商品バッジは 1 行に収まる")
        void badgesStayOnOneLine() {
            FONTS.forEach(this::badgesStayOnOneLineWith);
        }

        private void badgesStayOnOneLineWith(String name, FontResolver fonts) {
            RenderResult r = renderAt(W, H, fonts);
            List<LayoutBox> pills = r.byClass("badge-pill");
            assertEquals(3, pills.size());
            for (LayoutBox pill : pills) assertSingleLine(pill, name + ": ヘッダーのバッジ");
            List<LayoutBox> productBadges = r.byClass("product-badge");
            assertEquals(4, productBadges.size());
            for (LayoutBox badge : productBadges) assertSingleLine(badge, name + ": 商品バッジ");

            // 「(4)」「$419.97」がバッジの先頭行と同じ行に描かれる
            DisplayItem wishlist = r.texts().stream().filter(i -> i.text().contains("Wishlist")).findFirst().orElseThrow();
            DisplayItem count = r.texts().stream().filter(i -> i.text().contains("(4)")).findFirst().orElseThrow();
            assertEquals(wishlist.y(), count.y(), 1, name + ": (4) が Wishlist と同じ行");
            DisplayItem price = r.texts().stream().filter(i -> i.text().contains("$419.97") && i.y() < 80).findFirst().orElseThrow();
            DisplayItem cart = r.texts().stream().filter(i -> i.text().contains("Cart") && i.y() < 80).findFirst().orElseThrow();
            assertEquals(cart.y(), price.y(), 1, name + ": $419.97 が Cart と同じ行");
        }
    }

    @Nested
    @DisplayName("3. 3 カラムグリッドと商品カードの幅")
    class GridTest {

        @Test
        @DisplayName("列と商品カードの幅が Chrome の実測（190 / 396 / 220、カード 180）と一致する")
        void columnsMatchChrome() {
            FONTS.forEach(this::columnsMatchChromeWith);
        }

        private void columnsMatchChromeWith(String name, FontResolver fonts) {
            RenderResult r = renderAt(W, H, fonts);
            LayoutBox grid = r.one("web-grid");
            List<LayoutBox> cols = grid.getFlowChildren();
            assertEquals(3, cols.size());
            float[][] chrome = {{12, 190}, {214, 396}, {622, 220}};
            for (int i = 0; i < 3; i++) {
                assertEquals(chrome[i][0], cols.get(i).x, EPS, name + ": 列 " + i + " の x");
                assertEquals(chrome[i][1], cols.get(i).w, EPS, name + ": 列 " + i + " の幅");
            }
            assertTrue(cols.get(2).x + cols.get(2).w <= W - 12 + EPS, name + ": 右列が画面からはみ出さない");

            List<LayoutBox> cards = r.byClass("product-card");
            assertEquals(4, cards.size());
            float[] chromeCardX = {227, 417, 227, 417};
            for (int i = 0; i < 4; i++) {
                assertEquals(180f, cards.get(i).w, EPS, name + ": カード " + i + " の幅（Chrome 実測 180）");
                assertEquals(chromeCardX[i], cards.get(i).x, EPS, name + ": カード " + i + " の x");
            }
        }

        @Test
        @DisplayName("ゲーム内の書体では商品カードの上段（バッジとタグ）が 1 行に並ぶ")
        void cardMetaRowFits() {
            RenderResult r = inGame();
            for (LayoutBox meta : r.byClass("card-meta")) {
                List<LayoutBox> kids = meta.getFlowChildren();
                assertEquals(2, kids.size());
                assertFalse(overlaps(kids.get(0), kids.get(1)), "バッジとタグが重ならない");
                for (LayoutBox k : kids) assertInside(k, meta, "カード上段の項目");
                assertSingleLine(kids.get(1), "カードのタグ");
            }
        }
    }

    @Nested
    @DisplayName("4. 記号がブラウザとマイクラの両方でテキストとして描ける")
    class SymbolTest {

        @Test
        @DisplayName("表示テキストにサロゲートペア（カラー絵文字になる面 1 の文字）が無く、使う記号は BMP のテキスト記号")
        void noSupplementaryPlaneEmoji() {
            RenderResult r = inGame();
            for (DisplayItem item : r.items()) {
                if (item.text() == null) continue;
                item.text().codePoints().forEach(cp -> assertTrue(Character.isBmpCodePoint(cp),
                        () -> String.format("'%s' に U+%X（ブラウザではカラー絵文字、マイクラでは単色）が含まれない", item.text(), cp)));
            }
            String all = String.join("\n", r.texts().stream().map(DisplayItem::text).toList());
            for (String symbol : List.of("♥", "☺", "⚿", "✓", "→", "★", "☆")) {
                assertTrue(all.contains(symbol), "記号 " + symbol + " が描画される");
            }
        }
    }

    @Nested
    @DisplayName("5. フォームコントロール")
    class FormControlsTest {

        @Test
        @DisplayName("select / input / range / checkbox / radio / button が専用の部品として生成される")
        void formControlsGeneration() {
            RenderResult r = inGame();
            for (ElementType type : List.of(ElementType.SELECT, ElementType.INPUT, ElementType.RANGE,
                    ElementType.CHECKBOX, ElementType.RADIO, ElementType.BUTTON)) {
                assertTrue(r.items().stream().anyMatch(i -> i.type() == type), type + " が生成されること");
            }
        }

        @Test
        @DisplayName("select の表示ラベル（選択肢 ▾）が箱の内側に収まり、ブラウザのように欠けない")
        void selectLabelFits() {
            RenderResult r = inGame();
            DisplayItem select = r.items().stream().filter(i -> i.type() == ElementType.SELECT).findFirst().orElseThrow();
            assertEquals("All Departments ▾", select.text());
            LayoutBox box = r.byId("mode_select");
            FontStyle font = new FontStyle(box.style.fontSizePx(), LayoutMetrics.isBold(box.style), false, 0);
            float label = UniformFontFace.FACE.advance(select.text(), font);
            assertTrue(label <= box.w - box.horizontalDecoration() + EPS,
                    "ラベル幅 " + label + " が select の内容幅 " + (box.w - box.horizontalDecoration()) + " に収まる");
        }
    }

    @Nested
    @DisplayName("6. CSS グラデーション")
    class GradientTest {

        @Test
        @DisplayName("斜めの 2 色グラデーションは内容を 1 枚の矩形で塗り、隅の色が CSS の値と一致する")
        void diagonalGradientIsOneExactQuad() {
            RenderResult r = inGame();
            // header: linear-gradient(135deg, #1e293b, #0f172a)。135deg は左上が始点、右下が終点ちょうど
            LayoutBox header = r.byId("header_panel");
            float px = header.x + header.bL, py = header.y + header.bT;
            float pw = header.w - header.bL - header.bR, ph = header.h - header.bT - header.bB;
            List<DisplayItem> tiles = r.items().stream()
                    .filter(i -> i.id() != null && i.id().startsWith("header_panel_bgl0_"))
                    .toList();
            assertFalse(tiles.isEmpty());
            // パディングボックスを覆うのは 1 枚だけ（継ぎ目が無い）。他のタイルは background-repeat で
            // 枠線の下（1px）に掛かるだけで、枠線が上に重なるので見えない（ブラウザと同じ）
            List<DisplayItem> covering = new ArrayList<>();
            for (DisplayItem t : tiles) {
                float ix = Math.max(t.x(), px), iy = Math.max(t.y(), py);
                float ax = Math.min(t.x() + t.width(), px + pw), ay = Math.min(t.y() + t.height(), py + ph);
                if (ax - ix > 1.5f && ay - iy > 1.5f) covering.add(t);
            }
            assertEquals(1, covering.size(), "パディングボックスを塗るタイルは 1 枚");
            // 2 色の線形グラデーションは、シェーダーが画素ごとに計算する記述（t = dx*x + dy*y + offset）で 1 枚に描く
            String encoded = covering.get(0).events().get(RenderKeys.SHADER_GRADIENT);
            assertNotNull(encoded);
            String[] f = encoded.split(",");
            assertEquals("L", f[0]);
            float dx = Float.parseFloat(f[1]), dy = Float.parseFloat(f[2]), off = Float.parseFloat(f[3]);
            assertEquals(0xFF1E293B, Integer.parseInt(f[4]), "始点の色 #1e293b");
            assertEquals(0xFF0F172A, Integer.parseInt(f[5]), "終点の色 #0f172a");
            // 135deg は左上が始点（t = 0）、右下が終点（t = 1）ちょうど
            assertEquals(0f, dx * covering.get(0).x() + dy * covering.get(0).y() + off, 0.02f);
            assertEquals(1f, dx * (covering.get(0).x() + covering.get(0).width())
                    + dy * (covering.get(0).y() + covering.get(0).height()) + off, 0.02f);
        }
    }

    @Nested
    @DisplayName("7. タイポグラフィ")
    class TypographyTest {

        @Test
        @DisplayName("font-weight: 600 は太字として測り、描く（単一面の書体では合成太字。CSS Fonts 4 §5.2）")
        void semiboldIsSynthesizedBold() {
            assertTrue(LayoutMetrics.isBoldWeight("600"));
            assertTrue(LayoutMetrics.isBoldWeight("bold"));
            assertFalse(LayoutMetrics.isBoldWeight("500"));
            assertFalse(LayoutMetrics.isBoldWeight("normal"));

            RenderResult r = inGame();
            LayoutBox titleBox = r.one("product-title");
            assertTrue(LayoutMetrics.isBold(titleBox.style), "商品名（600）は太字で測る");
            String textId = titleBox.children.get(0).node.attrs.get("id");
            List<DisplayItem> words = r.texts().stream().filter(i -> i.id().startsWith(textId)).toList();
            assertFalse(words.isEmpty());
            for (DisplayItem word : words) {
                assertTrue(LayoutMetrics.isBoldWeight(word.events().get("font-weight")), "'" + word.text() + "' は太字で描かれる");
            }
        }
    }
}
