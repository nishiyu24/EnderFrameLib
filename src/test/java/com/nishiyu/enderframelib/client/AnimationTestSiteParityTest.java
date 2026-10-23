package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.CssAnimator;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
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
 * animation_test.html を Web 標準ブラウザ（Chrome 等）の挙動・見た目と照らし合わせて検証するテストスイート。
 * <p>
 * レイアウト幾何（Bounding Box）、CSS スタイルシート読み込みカバレッジ、
 * Flex/Grid コンテキスト、アニメーション仕様（AnimationSpec）、
 * および HTML 文字エンティティのデコード結果を網羅的に検証し、
 * Web サイト表示との差異を検出・報告する。
 */
public class AnimationTestSiteParityTest {

    private static final Path ANIMATION_TEST_PATH = Path.of("src/main/resources/data/enderframelib/enderframe_ui/animation_test.html");
    private static final int VIEWPORT_W = 1280;
    private static final int VIEWPORT_H = 800;

    private static String html;
    private static UiDocumentCompiler compiler;
    private static Document doc;
    private static LayoutBox rootBox;
    private static List<DisplayItem> displayItems;
    private static List<LayoutBox> allBoxes;

    @BeforeAll
    static void setUp() throws Exception {
        assertTrue(Files.exists(ANIMATION_TEST_PATH), "animation_test.html が存在すること");
        html = Files.readString(ANIMATION_TEST_PATH, StandardCharsets.UTF_8);
        assertFalse(html.isBlank());

        compiler = new UiDocumentCompiler();
        doc = compiler.parseDocument(html, "", VIEWPORT_W, VIEWPORT_H);
        assertNotNull(doc);

        ScrollState scrollState = new ScrollState();
        InteractionState interactionState = new InteractionState();
        FontResolver fonts = EnderFrameStandalone.defaultFontResolver();

        rootBox = compiler.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), scrollState, fonts, interactionState);
        assertNotNull(rootBox);

        DisplayListBuilder builder = new DisplayListBuilder(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, scrollState);
        displayItems = builder.render(rootBox);
        assertNotNull(displayItems);

        allBoxes = new ArrayList<>();
        collectBoxes(rootBox, allBoxes);
    }

    private static void collectBoxes(LayoutBox b, List<LayoutBox> out) {
        out.add(b);
        for (LayoutBox c : b.children) {
            collectBoxes(c, out);
        }
    }

    private static List<LayoutBox> findBoxesByClass(String clsName) {
        List<LayoutBox> result = new ArrayList<>();
        for (LayoutBox b : allBoxes) {
            if (b.node != null && b.node.attrs != null) {
                String c = b.node.attrs.get("class");
                if (c != null && List.of(c.trim().split("\\s+")).contains(clsName)) {
                    result.add(b);
                }
            }
        }
        return result;
    }

    private static LayoutBox findBoxByClass(String clsName) {
        List<LayoutBox> found = findBoxesByClass(clsName);
        assertFalse(found.isEmpty(), "クラス ." + clsName + " を持つ LayoutBox が存在すること");
        return found.get(0);
    }

    private static boolean hasRuleForSelector(String selector) {
        return doc.sheet.rules.stream().anyMatch(r -> r.selector != null && r.selector.trim().equals(selector));
    }

    // =========================================================================
    // 1. スタイルシート完全ロード検証（16KB 切り捨て問題の検出）
    // =========================================================================
    @Nested
    @DisplayName("1. スタイルシート完全ロード検証（CSS カバレッジ）")
    class StylesheetLoadingCoverageTest {

        @Test
        @DisplayName("1-1. <style> タグ内の CSS 全文（約20KB）が切り捨てられずに authorCss に保持されていること")
        void testAuthorCssNotTruncated() {
            int styleStart = html.indexOf("<style>");
            int styleEnd = html.indexOf("</style>");
            assertTrue(styleStart >= 0 && styleEnd > styleStart);
            int expectedCssLength = styleEnd - (styleStart + 7);

            // Webサイト上では約20KBのCSS全体が評価される。
            // HtmlParser がテキストノードを 16384 文字で切り捨てる場合、このアサーションで差異が検出される。
            assertTrue(doc.authorCss.length() >= expectedCssLength - 100,
                    String.format("authorCss が途中で切り捨てられています (実際: %d chars, 期待値: 約 %d chars)",
                            doc.authorCss.length(), expectedCssLength));
        }

        @Test
        @DisplayName("1-2. 16KB 以降に定義された重要ルール（.trans-button, .mc-item-card 等）がスタイルシートに登録されていること")
        void testLateRulesParsedIntoStyleSheet() {
            // 728行目以降のCSSルール群
            boolean hasTransButton = hasRuleForSelector(".trans-button");
            boolean hasTransLiftCard = hasRuleForSelector(".trans-lift-card");
            boolean hasMcItemCard = hasRuleForSelector(".mc-item-card");
            boolean hasToastPill = hasRuleForSelector(".toast-pill");

            assertAll("16KB 以降のルールがスタイルシートに存在すること",
                    () -> assertTrue(hasTransButton, "Rule '.trans-button' がスタイルシートに登録されていること"),
                    () -> assertTrue(hasTransLiftCard, "Rule '.trans-lift-card' がスタイルシートに登録されていること"),
                    () -> assertTrue(hasMcItemCard, "Rule '.mc-item-card' がスタイルシートに登録されていること"),
                    () -> assertTrue(hasToastPill, "Rule '.toast-pill' がスタイルシートに登録されていること")
            );
        }

        @Test
        @DisplayName("1-3. CSS Keyframes（14定義）がすべてパース・保持されていること")
        void testAllKeyframesPresent() {
            String[] expectedKeyframes = {
                    "kfSpin", "kfSpinReverse", "kfBob", "kfPulse", "kfSwing",
                    "kfBounceHop", "kfBeacon", "kfColorCycle", "kfTextFlash",
                    "kfSlideLane", "kfPingPong", "kfStaggerIn", "kfFillStep", "kfShimmer", "kfItemHover"
            };
            for (String kf : expectedKeyframes) {
                assertTrue(doc.sheet.keyframes.containsKey(kf), "@keyframes " + kf + " がパースされていること");
            }
        }
    }

    // =========================================================================
    // 2. レイアウト寸法・Flexbox 幾何の Web パリティ検証
    // =========================================================================
    @Nested
    @DisplayName("2. レイアウト寸法・Flexbox 幾何の Web パリティ検証")
    class LayoutGeometryParityTest {

        @Test
        @DisplayName("2-1. .trans-button がパディング（10px）を保持し、Chrome 同等（約 34px）の高さを持つこと")
        void testTransButtonHeight() {
            LayoutBox btn = findBoxByClass("trans-button");
            // Webサイト上: Chrome 実測値 height = 34.0px (padding: 10px 0 + font-size + border)
            // CSS が脱落した未適用状態: height = 17.0px (padding: 0)
            assertEquals(34.0f, btn.h, 4.0f,
                    String.format(".trans-button の高さが Web（34px）と乖離しています (実際: %.1fpx)", btn.h));
            assertEquals(10.0f, btn.pT, 1.0f, ".trans-button の paddingTop が 10px であること");
        }

        @Test
        @DisplayName("2-2. .mc-item-card が横並び Flexbox（高さ約 66px）として描画され、アイコン枠が 36x36px の正方形を維持すること")
        void testMcItemCardFlexLayout() {
            LayoutBox card = findBoxByClass("mc-item-card");
            LayoutBox itemBox = findBoxByClass("mc-item-box");

            // Webサイト上: Chrome 実測値 card height = 66.5px, mc-item-box = 36.0 x 36.0px (横並び)
            // CSS 脱落状態: card height = 36.4px, mc-item-box width = 384.7px (親幅いっぱい、縦積み崩壊)
            assertEquals(66.5f, card.h, 6.0f,
                    String.format(".mc-item-card の高さが Web（約66.5px）と乖離しています (実際: %.1fpx)", card.h));
            assertEquals(36.0f, itemBox.w, 2.0f,
                    String.format(".mc-item-box の幅が 36px の正方形ではなく親幅いっぱいに広がっています (実際: %.1fpx)", itemBox.w));
            assertEquals(36.0f, itemBox.h, 2.0f,
                    String.format(".mc-item-box の高さが 36px を維持していません (実際: %.1fpx)", itemBox.h));
        }

        @Test
        @DisplayName("2-3. .toast-pill が横並び Flexbox（高さ約 30px）としてレイアウトされること")
        void testToastPillFlexLayout() {
            LayoutBox toast = findBoxByClass("toast-pill");
            // Webサイト上: Chrome 実測値 height = 30.4px (padding: 6px 12px)
            // CSS 脱落状態: height = 18.2px (padding: 0)
            assertEquals(30.4f, toast.h, 4.0f,
                    String.format(".toast-pill の高さが Web（約30.4px）と乖離しています (実際: %.1fpx)", toast.h));
        }

        @Test
        @DisplayName("2-4. ヘッダー (.lab-header) の高さが Web（56px）と一致し、テキストの予期せぬ 2 行折り返しが発生しないこと")
        void testHeaderHeightAndNoWrapping() {
            LayoutBox header = findBoxByClass("lab-header");
            LayoutBox brandName = findBoxByClass("brand-name");

            // Webサイト上: Chrome 実測値 header height = 56.0px, brand-name height = 20.9px (1行)
            // エンジン現状: brand-name が "EnderFrame " と "Lab" に 2 行折り返し、header height = 90.1px に肥大化
            assertEquals(56.0f, header.h, 6.0f,
                    String.format(".lab-header の高さが Web（56px）より大幅に肥大化しています (実際: %.1fpx)", header.h));
            assertEquals(20.9f, brandName.h, 3.0f,
                    String.format(".brand-name が 1 行に収まらず 2 行に折り返しています (実際: %.1fpx)", brandName.h));
        }
    }

    // =========================================================================
    // 3. グリッドとスクロール制約の Web パリティ検証
    // =========================================================================
    @Nested
    @DisplayName("3. グリッドとスクロール制約の Web パリティ検証")
    class GridAndScrollParityTest {

        @Test
        @DisplayName("3-1. .lab-grid の 3 カラム (.lab-col) が均等に分割されていること")
        void testGridColumnsEqual() {
            List<LayoutBox> cols = findBoxesByClass("lab-col");
            assertEquals(3, cols.size(), ".lab-col が 3 列存在すること");
            float w0 = cols.get(0).w;
            for (int i = 1; i < cols.size(); i++) {
                assertEquals(w0, cols.get(i).w, 1.0f,
                        String.format("カラム #%d の幅 (%.1f) がカラム #0 の幅 (%.1f) と一致しません", i, cols.get(i).w, w0));
            }
        }

        @Test
        @DisplayName("3-2. .lab-col がグリッドの行（ビューポートから決まる高さ）に制約され、フッターが画面外に押し出されないこと")
        void testColHeightBoundedByViewport() {
            LayoutBox col0 = findBoxesByClass("lab-col").get(0);
            LayoutBox grid = findBoxByClass("lab-grid");
            LayoutBox footer = findBoxByClass("lab-footer");

            // 800px のビューポートでは 800 - 12*2(padding) - 56(header) - 12*2(gap) - 36.4(footer) = 659.6px。
            // flex: 1; min-height: 0 のグリッドの auto 行は確定した高さに収まり、
            // min-height: 0; overflow-y: auto の列は行の高さになって中身がスクロールする
            // 修正前: col0.h = 855.2px に縦伸びし、グリッドの外へ突き抜けていた
            assertEquals(grid.h, col0.h, 0.5f,
                    String.format(".lab-col の高さがグリッドの行に制約されずに突き抜けています (実際: %.1fpx, グリッド: %.1fpx)", col0.h, grid.h));
            assertEquals(659.6f, col0.h, 1.0f, String.format(".lab-col の高さ (実際: %.1fpx)", col0.h));
            assertTrue(col0.scrollHeight > col0.h, ".lab-col の中身はスクロールで見えること");
            assertTrue(footer.y >= grid.y + grid.h, ".lab-footer がグリッドの下にあること");
            assertTrue(footer.y + footer.h <= VIEWPORT_H,
                    String.format(".lab-footer がビューポート (800px) の外にはみ出しています (実際下端: %.1fpx)", footer.y + footer.h));
        }

        @Test
        @DisplayName("3-3. Chrome 実測（ウィンドウ内高さ 705px）と同じく .lab-col = 564.6px、フッター y = 656.6px になること")
        void testColHeightMatchesChromeMeasurement() {
            // レビューの Chrome 実測値 (col 564.6px / footer y 656.6px) は 656.6 + 36.4 + 12 = 705px の
            // ビューポートでの値。同じ高さでレイアウトして一致を確かめる
            int chromeH = 705;
            Document d = compiler.parseDocument(html, "", VIEWPORT_W, chromeH);
            LayoutBox root = compiler.layoutDocument(d, VIEWPORT_W, chromeH, Map.of(), new ScrollState(),
                    EnderFrameStandalone.defaultFontResolver(), new InteractionState());
            List<LayoutBox> boxes = new ArrayList<>();
            collectBoxes(root, boxes);
            LayoutBox col0 = boxes.stream().filter(b -> hasClass(b, "lab-col")).findFirst().orElseThrow();
            LayoutBox footer = boxes.stream().filter(b -> hasClass(b, "lab-footer")).findFirst().orElseThrow();

            assertEquals(564.6f, col0.h, 1.0f, String.format(".lab-col の高さ (実際: %.1fpx)", col0.h));
            assertEquals(656.6f, footer.y, 1.0f, String.format(".lab-footer の y (実際: %.1fpx)", footer.y));
        }

        private static boolean hasClass(LayoutBox b, String clsName) {
            String c = b.node == null || b.node.attrs == null ? null : b.node.attrs.get("class");
            return c != null && List.of(c.trim().split(" +")).contains(clsName);
        }
    }

    // =========================================================================
    // 4. アニメーション仕様・補間モデルの検証
    // =========================================================================
    @Nested
    @DisplayName("4. アニメーション仕様・補間モデルの検証")
    class AnimationParityTest {

        @Test
        @DisplayName("4-1. 主要アニメーション要素に正しい AnimationSpec が設定されていること")
        void testAnimationSpecsAssigned() {
            // スピナー
            DisplayItem spinner = displayItems.stream()
                    .filter(i -> i.animDef() != null && i.animDef().durationMs() == 1200 && i.animDef().direction() == AnimationSpec.Direction.NORMAL)
                    .findFirst().orElse(null);
            assertNotNull(spinner, "1200ms normal のスピナーアニメーションが DisplayItem に設定されていること");

            // タイミングファンクションレーン
            long runnerCount = displayItems.stream()
                    .filter(i -> i.animDef() != null && i.animDef().durationMs() == 2000 && i.animDef().direction() == AnimationSpec.Direction.ALTERNATE)
                    .count();
            assertTrue(runnerCount >= 6, "タイミング関数テスト用ランナー（6種）にアニメーションが設定されていること");

            // 方向テスト
            boolean hasReverse = displayItems.stream()
                    .anyMatch(i -> i.animDef() != null && i.animDef().direction() == AnimationSpec.Direction.REVERSE);
            boolean hasAltReverse = displayItems.stream()
                    .anyMatch(i -> i.animDef() != null && i.animDef().direction() == AnimationSpec.Direction.ALTERNATE_REVERSE);
            assertTrue(hasReverse, "reverse 方向のアニメーション要素が存在すること");
            assertTrue(hasAltReverse, "alternate-reverse 方向のアニメーション要素が存在すること");
        }

        @Test
        @DisplayName("4-2. 複合 transform (translateY + scale) を持つ kfBounceHop が正しく両チャネルの値を補間できること")
        void testCompoundTransformAnimation() {
            DisplayItem bounceItem = displayItems.stream()
                    .filter(i -> i.animDef() != null && i.animDef().durationMs() == 1200 && i.animDef().frames().size() == 4)
                    .findFirst().orElse(null);
            assertNotNull(bounceItem, "kfBounceHop を持つ DisplayItem が存在すること");

            String sessionId = "test_compound_parity";
            ClientWebManager.clearSession(sessionId);
            long start = 1_000_000L;

            // 0% 時点
            CssAnimator.AnimatedValues v0 = CssAnimator.getValuesAt(sessionId, bounceItem, start);
            assertEquals(0.0f, v0.ty(), 0.1f, "0% 時点の translateY は 0px であること");
            assertEquals(1.15f, v0.sx(), 0.05f, "0% 時点の scaleX は 1.15 であること");
            assertEquals(0.85f, v0.sy(), 0.05f, "0% 時点の scaleY は 0.85 であること");

            ClientWebManager.clearSession(sessionId);
        }
        @Test
        @DisplayName("4-3. ★ DYNAMIC COLOR ★ の文字色が親 span の kfTextFlash（color）で #38bdf8 ↔ #f43f5e に変化すること")
        void testInheritedTextColorAnimation() {
            // animation は継承されないが、親の color を補間すると継承した子テキストの色も変わる（Chrome と同じ）
            DisplayItem text = displayItems.stream()
                    .filter(i -> i.type() == ElementType.TEXT && i.text() != null && i.text().contains("DYNAMIC"))
                    .findFirst().orElse(null);
            assertNotNull(text, "DYNAMIC COLOR のテキストが描画されていること");
            assertNotNull(text.animDef(), "テキストに AnimationSpec があること");
            assertTrue((text.animDef().animatedChannels() & AnimationSpec.CH_COLOR) != 0,
                    "テキストの DisplayItem に親の color アニメーションが届いていること");
            assertEquals(1500, text.animDef().durationMs(), "kfTextFlash の 1.5s であること");

            String sessionId = "test_text_flash_parity";
            ClientWebManager.clearSession(sessionId);
            long start = 1_000_000L;
            int c0 = CssAnimator.getValuesAt(sessionId, text, start).color();
            int c50 = CssAnimator.getValuesAt(sessionId, text, start + 750).color();
            int c25 = CssAnimator.getValuesAt(sessionId, text, start + 375).color();
            ClientWebManager.clearSession(sessionId);

            assertEquals(0x38bdf8, c0 & 0xFFFFFF, String.format("0%% は #38bdf8 (実際: #%06x)", c0 & 0xFFFFFF));
            assertEquals(0xf43f5e, c50 & 0xFFFFFF, String.format("50%% は #f43f5e (実際: #%06x)", c50 & 0xFFFFFF));
            assertNotEquals(c0, c25, "途中の時刻では色が補間されていること");
            assertNotEquals(c50, c25, "途中の時刻では色が補間されていること");
        }

        @Test
        @DisplayName("4-4. 子孫が自分で color を指定していれば、祖先の color アニメーションは届かないこと")
        void testExplicitColorBlocksInheritedAnimation() {
            String src = "<style>@keyframes kf{0%{color:#ff0000}100%{color:#0000ff}}"
                    + ".a{animation:kf 1s linear infinite}.b{color:#00ff00}</style>"
                    + "<div class='a'>inherit<span class='b'>fixed</span></div>";
            Document d = compiler.parseDocument(src, "", 400, 200);
            ScrollState scroll = new ScrollState();
            LayoutBox root = compiler.layoutDocument(d, 400, 200, Map.of(), scroll,
                    EnderFrameStandalone.defaultFontResolver(), new InteractionState());
            List<DisplayItem> items = new DisplayListBuilder(400, 200, d.sheet.keyframes, scroll).render(root);
            DisplayItem inherit = items.stream().filter(i -> i.type() == ElementType.TEXT && "inherit".equals(i.text().strip())).findFirst().orElseThrow();
            DisplayItem fixed = items.stream().filter(i -> i.type() == ElementType.TEXT && "fixed".equals(i.text().strip())).findFirst().orElseThrow();
            assertTrue((inherit.animDef().animatedChannels() & AnimationSpec.CH_COLOR) != 0, "継承する文字には届くこと");
            assertEquals(0, fixed.animDef().animatedChannels() & AnimationSpec.CH_COLOR, "color を指定した子孫には届かないこと");
        }
    }

    // =========================================================================
    // 5. 特殊文字・HTML エンティティのデコード検証
    // =========================================================================
    @Nested
    @DisplayName("5. 特殊文字・HTML エンティティのデコード検証")
    class TextEntitiesParityTest {

        @Test
        @DisplayName("5-1. Unicode 特殊記号（▲, ·, ♦, ⚔, ★, ✓, ↺ 等）が文字化けせず保持されていること")
        void testUnicodeEntitiesDecoded() {
            List<String> texts = displayItems.stream()
                    .filter(i -> i.text() != null && !i.text().isBlank())
                    .map(DisplayItem::text)
                    .toList();

            assertAll("主要な記号・エンティティがデコードされていること",
                    () -> assertTrue(texts.stream().anyMatch(t -> t.contains("▲")), "&#9650; (▲) が含まれること"),
                    () -> assertTrue(texts.stream().anyMatch(t -> t.contains("·")), "&middot; (·) が含まれること"),
                    () -> assertTrue(texts.stream().anyMatch(t -> t.contains("♦")), "&#9830; (♦) が含まれること"),
                    () -> assertTrue(texts.stream().anyMatch(t -> t.contains("★")), "&starf; (★) が含まれること"),
                    () -> assertTrue(texts.stream().anyMatch(t -> t.contains("✓")), "&#10003; (✓) が含まれること")
            );
        }
    }
}
