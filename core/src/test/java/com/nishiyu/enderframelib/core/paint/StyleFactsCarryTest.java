package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * K4d（継承の変更で作り直した {@link ComputedStyle} への派生値 {@code PaintOrder.StyleFacts} の引き継ぎ）。
 *
 * <p>検証は 3 つ: ①<b>効果</b>: 継承する値（color）だけが変わる再スタイルで、派生値を新しく算出しない②<b>正しさ</b>: 引き継いだ値＝最初から作った値
 * （乱数の組）、表示リスト＝最初から作った結果③<b>変異</b>: 非継承の宣言を 1 つでも変えると引き継がない。</p>
 */
class StyleFactsCarryTest {
    /** 派生値が読む非継承の宣言（値の候補つき）。 */
    private static final String[][] NON_INHERITED = {
            {CssProperty.DISPLAY, "block", "none", "flex"},
            {CssProperty.Z_INDEX, "auto", "3", "-1"},
            {CssProperty.OPACITY, "1", "0.5"},
            {CssProperty.TRANSFORM, "none", "rotate(5deg)"},
            {CssProperty.FILTER, "none", "blur(2px)"},
            {CssProperty.CLIP_PATH, "none", "circle(50%)"},
            {CssProperty.BACKDROP_FILTER, "none", "blur(4px)"},
            {CssProperty.ISOLATION, "auto", "isolate"},
            {CssProperty.MIX_BLEND_MODE, "normal", "multiply"},
            {CssProperty.OVERFLOW, "visible", "hidden", "scroll", "auto"},
            {CssProperty.ANIMATION_NAME, "none", "spin"},
            {CssProperty.BACKGROUND_COLOR, "#fff", "#000"},
            {CssProperty.WIDTH, "10px", "20px", "50%"},
    };
    /** 継承する宣言。引き継ぎの鍵ではない（変わっても引き継ぐ）。 */
    private static final String[][] INHERITED = {
            {CssProperty.COLOR, "#112233", "#223344", "red"},
            {CssProperty.FONT_WEIGHT, "400", "700"},
            {CssProperty.LINE_HEIGHT, "normal", "20px"},
            {CssProperty.WHITE_SPACE, "normal", "pre"},
            {CssProperty.DIRECTION, "ltr", "rtl"},
    };

    private static Map<String, String> randomMap(Random r, String[][] table) {
        Map<String, String> m = new HashMap<>();
        for (String[] row : table) {
            if (r.nextInt(3) > 0) m.put(row[0], row[1 + r.nextInt(row.length - 1)]);
        }
        return m;
    }

    private static ComputedStyle style(Map<String, String> m) {
        return ComputedStyle.of(new HashMap<>(m), 16f, 16f);
    }

    // ---- ② 正しさ ----

    @Test
    void carriedFactsEqualFreshFactsForRandomPairs() {
        Random r = new Random(4242);
        int carried = 0, refused = 0;
        for (int i = 0; i < 3000; i++) {
            Map<String, String> nonInh = randomMap(r, NON_INHERITED);
            Map<String, String> before = new HashMap<>(nonInh);
            before.putAll(randomMap(r, INHERITED));
            Map<String, String> after = new HashMap<>(r.nextBoolean() ? nonInh : randomMap(r, NON_INHERITED));
            after.putAll(randomMap(r, INHERITED));

            ComputedStyle prev = style(before);
            PaintOrder.factsOf(prev);
            ComputedStyle next = style(after);
            next.carryDerivedFrom(prev);
            PaintOrder.StyleFacts fromCarry = PaintOrder.factsOf(next);
            PaintOrder.StyleFacts fresh = PaintOrder.factsOf(style(after));
            assertEquals(fresh, fromCarry, "手順 " + i + ": 引き継いだ値が最初から作った値と違う before=" + before + " after=" + after);
            if (next.derivedFacts() == prev.derivedFacts()) carried++; else refused++;
        }
        assertTrue(carried > 300 && refused > 300, "両方の経路を通っていない: 引き継ぎ " + carried + " 見送り " + refused);
    }

    // ---- ① 効果・③ 変異 ----

    @Test
    void inheritedOnlyChangeSharesThePreviousFacts() {
        Map<String, String> base = new HashMap<>(Map.of(CssProperty.Z_INDEX, "3", CssProperty.OPACITY, "0.5", CssProperty.COLOR, "#112233"));
        ComputedStyle prev = style(base);
        PaintOrder.StyleFacts f = PaintOrder.factsOf(prev);
        Map<String, String> recolored = new HashMap<>(base);
        recolored.put(CssProperty.COLOR, "#223344");
        ComputedStyle next = style(recolored);
        assertNull(next.derivedFacts());
        next.carryDerivedFrom(prev);
        assertSame(f, next.derivedFacts(), "継承する値だけの変更なのに引き継いでいない");
    }

    @Test
    void anyNonInheritedChangeRefusesToCarry() {
        for (String[] row : NON_INHERITED) {
            Map<String, String> base = new HashMap<>();
            for (String[] other : NON_INHERITED) base.put(other[0], other[1]);
            ComputedStyle prev = style(base);
            PaintOrder.factsOf(prev);
            for (int v = 2; v < row.length; v++) {
                Map<String, String> changed = new HashMap<>(base);
                changed.put(row[0], row[v]);
                ComputedStyle next = style(changed);
                next.carryDerivedFrom(prev);
                assertNull(next.derivedFacts(), row[0] + ": " + row[v] + " に変えたのに前回の値を引き継いだ");
                assertEquals(PaintOrder.factsOf(style(changed)), PaintOrder.factsOf(next), row[0]);
            }
        }
        ComputedStyle a = style(Map.of(CssProperty.OPACITY, "0.5"));
        ComputedStyle b = style(Map.of(CssProperty.OPACITY, "0.5"));
        b.carryDerivedFrom(a); // 前回が算出していなければ何もしない
        assertNull(b.derivedFacts());
        PaintOrder.factsOf(a);
        PaintOrder.StyleFacts own = PaintOrder.factsOf(b);
        b.carryDerivedFrom(a); // 自分で持っているなら上書きしない
        assertSame(own, b.derivedFacts());
    }

    // ---- ①②: 実際の再スタイル（body の color の往復） ----

    private static final int W = 800, H = 600;

    private static String doc(int rows) {
        StringBuilder sb = new StringBuilder("<html><head><style>body { margin: 8px } li { list-style: none; padding: 4px;"
                + " background: #eef; opacity: 0.9; z-index: 2; position: relative } ul { padding: 0; margin: 0 }</style></head><body><ul id=\"list\">");
        for (int i = 0; i < rows; i++) sb.append("<li style=\"width:").append(100 + i).append("px\">Row item #").append(i).append("</li>");
        return sb.append("</ul></body></html>").toString();
    }

    private static String sig(DisplayItem i) {
        return i.id() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height() + "|" + i.text()
                + "|" + i.color() + "|" + i.bgColor() + "|" + i.opacity();
    }

    @Test
    void colorToggleComputesNoNewFactsAndMatchesFreshList() {
        int rows = 200;
        try (StandaloneView v = new StandaloneView(doc(rows), W, H, FontResolver.FALLBACK)) {
            UiView ui = v.ui();
            ui.flushInvalidation();
            HtmlElement host = (HtmlElement) v.root().getNodeById("list");
            // 準備: 全箱の派生値を作る
            host.setAttribute("style", "color:#112233");
            ui.flushInvalidation();
            long before = DisplayListBuilder.styleFactsComputed();
            int steps = 6;
            for (int i = 0; i < steps; i++) {
                host.setAttribute("style", (i & 1) == 0 ? "color:#223344" : "color:#112233");
                ui.flushInvalidation();
            }
            long computed = DisplayListBuilder.styleFactsComputed() - before;
            // 行ごとに宣言が違う（スタイルの共有に当たらない）。変更元は 1。全箱（200 行＋）を作り直す版は 1 手 200 回以上。引き継げば数回に収まる
            assertTrue(computed <= 20, "継承だけの再スタイルで派生値を新しく算出した回数: " + computed + "（" + steps + " 手）");
            List<DisplayItem> actual = ui.renderCache();
            List<DisplayItem> expected = new DisplayListBuilder(W, H, ui.document().sheet.keyframes, ui.scrollState())
                    .render(ui.layoutRoot(), false);
            assertEquals(expected.size(), actual.size(), "項目数");
            for (int k = 0; k < expected.size(); k++) assertEquals(sig(expected.get(k)), sig(actual.get(k)), "項目 " + k);
        }
    }
}
