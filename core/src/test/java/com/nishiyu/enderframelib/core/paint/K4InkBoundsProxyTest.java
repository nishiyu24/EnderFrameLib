package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * K4（表示リストを窓＋変更で評価）: 窓の外の部分木の O(1) の再利用（インク境界の代理。{@link InkBounds}・{@code FragmentPaint.translatedOutsideWindow}）。
 *
 * <p>検証は 3 つ: ①<b>効果</b>: 窓の外で平行移動しただけの部分木を作り直さない（増幅率）②<b>正しさ</b>: どの手でも、記憶つきの表示リスト＝最初から作った
 * 表示リスト（窓へ入る・出る・跨ぐ・位置指定の子孫・入れ子・乱数の手順）③<b>境界</b>: {@code InkBounds} の外接矩形と十分条件の単体。</p>
 */
class K4InkBoundsProxyTest {
    private static final int W = 800, H = 600;

    /** 窓付き（持ち主 800 以上）になる行数。窓の外の行は項目を作らない。 */
    private static final int ROWS = 500;

    private static String doc(int rows, int spacer, boolean nested, boolean absChild) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; margin: 0 }"
                + " li { padding: 4px; border-bottom: 1px solid #ccc; background: #eef } .rel { position: relative }"
                + " .ab { position: absolute; left: 0; top: -2500px; width: 50px; height: 20px; background: #f00 }"
                + "</style></head><body>");
        sb.append("<div id=\"sp\" style=\"height:").append(spacer).append("px\"></div><ul id=\"list\">");
        for (int i = 1; i <= rows; i++) {
            sb.append("<li id=\"item-").append(i).append('"');
            if (absChild && i % 7 == 0) sb.append(" class=\"rel\"");
            sb.append('>');
            if (nested) sb.append("<div><span>Row </span><b>#").append(i).append("</b></div>");
            else sb.append("Row item #").append(i).append(" with description text");
            if (absChild && i % 7 == 0) sb.append("<div class=\"ab\"></div>");
            sb.append("</li>");
        }
        sb.append("</ul></body></html>");
        return sb.toString();
    }

    private static String itemSig(DisplayItem i) {
        return i.id() + "|" + i.baseId() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                + "|" + i.text() + "|" + i.color() + "|" + i.bgColor() + "|" + i.radius() + "|" + i.opacity();
    }

    private static void assertMatchesFresh(StandaloneView view, String step) {
        UiView ui = view.ui();
        ui.flushInvalidation();
        List<DisplayItem> actual = ui.renderCache();
        List<DisplayItem> expected = new DisplayListBuilder(W, H, ui.document().sheet.keyframes, ui.scrollState()).render(ui.layoutRoot(), false);
        if (expected.size() != actual.size()) {
            java.util.Set<String> e = new java.util.TreeSet<>(), a = new java.util.TreeSet<>();
            for (DisplayItem i : expected) e.add(itemSig(i));
            for (DisplayItem i : actual) a.add(itemSig(i));
            java.util.Set<String> onlyFresh = new java.util.TreeSet<>(e), onlyMemo = new java.util.TreeSet<>(a);
            onlyFresh.removeAll(a);
            onlyMemo.removeAll(e);
            assertEquals(expected.size(), actual.size(), step + ": 表示項目数 最初から作った結果だけ=" + onlyFresh + " 記憶だけ=" + onlyMemo);
        }
        for (int i = 0; i < expected.size(); i++) assertEquals(itemSig(expected.get(i)), itemSig(actual.get(i)), step + ": 項目 " + i);
    }

    /** 経路の記録（箱ごとの段）。1 回の組み立てぶんだけ溜める。 */
    private static final class Paths {
        final Map<LayoutBox, Integer> last = new IdentityHashMap<>();

        void attach(StandaloneView v) {
            v.ui().paintBuilder().setPathRecorder((box, level) -> {
                if (box == null) last.clear();
                else last.put(box, level);
            });
        }

        long count(int level) { return last.values().stream().filter(l -> l == level).count(); }
    }

    private static HtmlElement el(StandaloneView v, String id) { return (HtmlElement) v.root().getNodeById(id); }

    private static LayoutBox boxOf(LayoutBox b, String id) {
        if (b.node != null && !b.isAnonymous && id.equals(b.node.getId())) return b;
        for (LayoutBox c : b.children) {
            LayoutBox f = boxOf(c, id);
            if (f != null) return f;
        }
        return null;
    }

    // ---- ① 効果 ----

    @Test
    void insertedRowShiftsCulledRowsWithoutRebuildingThem() {
        try (StandaloneView v = new StandaloneView(doc(ROWS, 0, false, false), W, H, FontResolver.FALLBACK)) {
            Paths p = new Paths();
            v.ui().flushInvalidation();
            p.attach(v);
            HtmlNode list = v.root().getNodeById("list");
            HtmlNode anchor = v.root().getNodeById("item-250");
            HtmlNode last = null;
            long maxRebuilt = 0, minHits = Long.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                if ((i & 1) == 0) {
                    HtmlElement added = (HtmlElement) v.root().ownerDocument.createElement("li");
                    added.updateTextContent("inserted " + i);
                    list.insertBefore(added, anchor);
                    last = added;
                } else {
                    list.removeChild(last);
                }
                assertMatchesFresh(v, "挿入・削除 " + i);
                maxRebuilt = Math.max(maxRebuilt, p.count(FragmentPaint.PATH_OWN) + p.count(FragmentPaint.PATH_REBUILD));
                minHits = Math.min(minHits, p.count(FragmentPaint.PATH_HIT));
            }
            // 変更元は 2（挿入・削除する行と、その親）。後続の 250 行（窓の外）は平行移動しただけなので作り直さない。増幅率 ≤ 10 → 作り直し ≤ 20 箱
            assertTrue(maxRebuilt <= 20, "窓の外の後続の行を作り直している: 作り直し " + maxRebuilt);
            assertTrue(minHits >= 200, "窓の外の後続の行を O(1) で再利用していない: 再利用 " + minHits);
        }
    }

    @Test
    void nestedRowsAreAlsoReusedAsOneUnit() {
        try (StandaloneView v = new StandaloneView(doc(ROWS, 0, true, false), W, H, FontResolver.FALLBACK)) {
            Paths p = new Paths();
            v.ui().flushInvalidation();
            p.attach(v);
            HtmlNode list = v.root().getNodeById("list");
            HtmlNode anchor = v.root().getNodeById("item-100");
            HtmlElement added = (HtmlElement) v.root().ownerDocument.createElement("li");
            added.updateTextContent("inserted");
            list.insertBefore(added, anchor);
            assertMatchesFresh(v, "入れ子の行の挿入");
            long rebuilt = p.count(FragmentPaint.PATH_OWN) + p.count(FragmentPaint.PATH_REBUILD);
            assertTrue(rebuilt <= 20, "入れ子の行（li > div > span・b）を部分木ごと再利用していない: 作り直し " + rebuilt);
        }
    }

    // ---- ② 正しさ（窓へ入る・出る・跨ぐ） ----

    @Test
    void rowsEnteringAndLeavingTheWindowMatchFreshBuild() {
        for (boolean nested : new boolean[] {false, true}) {
            try (StandaloneView v = new StandaloneView(doc(ROWS, 6000, nested, false), W, H, FontResolver.FALLBACK)) {
                assertMatchesFresh(v, "初回 nested=" + nested);
                // 行が窓の外 → 窓を跨ぐ → 窓の中 → 再び外へ平行移動する（行の中身は変わらない）
                for (int spacer : new int[] {5000, 4000, 2500, 1500, 1300, 1200, 1000, 800, 500, 100, 0, 700, 1500, 3000, 6000, 20, 6000}) {
                    el(v, "sp").setAttribute("style", "height:" + spacer + "px");
                    assertMatchesFresh(v, "spacer=" + spacer + " nested=" + nested);
                }
            }
        }
    }

    @Test
    void rowsEnteringTheWindowAreDrawnNotLeftEmpty() {
        try (StandaloneView v = new StandaloneView(doc(ROWS, 6000, false, false), W, H, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            assertTrue(v.ui().paintBuilder().isWindowed(), "この文書は窓付き（持ち主が多い）");
            int outside = v.ui().renderCache().size();
            assertTrue(outside < 20, "窓の外の行は項目を作らない: " + outside);
            el(v, "sp").setAttribute("style", "height:0px");
            v.ui().flushInvalidation();
            int inside = v.ui().renderCache().size();
            assertTrue(inside > 200, "窓へ入った行（平行移動しただけの空の記憶）は作り直されて項目を持つ: " + inside);
        }
    }

    @Test
    void absolutelyPositionedDescendantsThatEscapeTheRowAreNotProxied() {
        // 行の位置指定の子孫は平行移動の外（上へ 2,500px 飛び出す）。インク境界は作らず、窓へ入る・出るたびに最初から作った結果と一致する
        try (StandaloneView v = new StandaloneView(doc(ROWS, 3500, false, true), W, H, FontResolver.FALLBACK)) {
            assertMatchesFresh(v, "初回");
            for (int spacer : new int[] {3000, 2000, 1000, 100, 0, 2600, 3500, 2200, 3500}) {
                el(v, "sp").setAttribute("style", "height:" + spacer + "px");
                assertMatchesFresh(v, "abs spacer=" + spacer);
            }
        }
    }

    @Test
    void randomEditsOfALargeListMatchFreshBuildEveryStep() {
        for (long seed = 1; seed <= 6; seed++) {
            Random rnd = new Random(seed);
            boolean nested = (seed & 1) == 0;
            try (StandaloneView v = new StandaloneView(doc(ROWS, 0, nested, seed % 3 == 0), W, H, FontResolver.FALLBACK)) {
                assertMatchesFresh(v, "seed " + seed + " 初回");
                HtmlNode list = v.root().getNodeById("list");
                for (int step = 0; step < 40; step++) {
                    String what;
                    switch (rnd.nextInt(5)) {
                        case 0 -> {
                            HtmlNode anchor = v.root().getNodeById("item-" + (1 + rnd.nextInt(ROWS)));
                            if (anchor == null || anchor.parent != list) { what = "skip"; break; }
                            HtmlElement added = (HtmlElement) v.root().ownerDocument.createElement("li");
                            added.setAttribute("id", "n" + seed + "_" + step);
                            added.updateTextContent("inserted " + step);
                            list.insertBefore(added, anchor);
                            what = "insert";
                        }
                        case 1 -> {
                            HtmlNode victim = v.root().getNodeById("item-" + (1 + rnd.nextInt(ROWS)));
                            if (victim == null || victim.parent != list) { what = "skip"; break; }
                            list.removeChild(victim);
                            what = "remove";
                        }
                        case 2 -> {
                            HtmlElement row = el(v, "item-" + (1 + rnd.nextInt(ROWS)));
                            if (row == null) { what = "skip"; break; }
                            row.setAttribute("style", "height:" + (10 + rnd.nextInt(90)) + "px;background:#" + Integer.toHexString(0xaaa + rnd.nextInt(0x555)));
                            what = "style";
                        }
                        case 3 -> {
                            el(v, "sp").setAttribute("style", "height:" + (rnd.nextInt(8) * 700) + "px");
                            what = "spacer";
                        }
                        default -> {
                            HtmlElement row = el(v, "item-" + (1 + rnd.nextInt(ROWS)));
                            if (row == null) { what = "skip"; break; }
                            row.setAttribute("style", rnd.nextBoolean() ? "display:none" : "");
                            what = "display";
                        }
                    }
                    assertMatchesFresh(v, "seed " + seed + " step " + step + " " + what);
                }
            }
        }
    }

    @Test
    void smallUnwindowedListsTranslateAndMatchFreshBuild() {
        // 窓付きでない文書（窓の外の代理は使わない）でも、平行移動した後続の行は作り直される（古い位置の項目を出さない）
        for (long seed = 1; seed <= 4; seed++) {
            Random rnd = new Random(100 + seed);
            try (StandaloneView v = new StandaloneView(doc(60, 0, seed % 2 == 0, seed == 3), W, H, FontResolver.FALLBACK)) {
                assertMatchesFresh(v, "small seed " + seed + " 初回");
                HtmlNode list = v.root().getNodeById("list");
                for (int step = 0; step < 30; step++) {
                    HtmlNode anchor = v.root().getNodeById("item-" + (1 + rnd.nextInt(60)));
                    if (rnd.nextInt(3) == 0 && anchor != null && anchor.parent == list) {
                        list.removeChild(anchor);
                    } else if (anchor != null && anchor.parent == list) {
                        HtmlElement added = (HtmlElement) v.root().ownerDocument.createElement("li");
                        added.setAttribute("style", "height:" + (10 + rnd.nextInt(40)) + "px");
                        list.insertBefore(added, anchor);
                    } else {
                        el(v, "sp").setAttribute("style", "height:" + rnd.nextInt(900) + "px");
                    }
                    assertMatchesFresh(v, "small seed " + seed + " step " + step);
                }
            }
        }
    }

    @Test
    void translationOnlyKeepsTheBoxOwnPaintStampButBumpsTheSubtreeStamp() {
        try (StandaloneView v = new StandaloneView(doc(40, 0, false, false), W, H, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            LayoutBox row = boxOf(v.ui().layoutRoot(), "item-30");
            long own0 = row.ownPaintStamp(), sub0 = row.subtreePaintStamp();
            float y0 = row.y();
            // 前の行を挿入する: 後続の行は親相対の位置だけが動く（形は同じ）
            HtmlElement added = (HtmlElement) v.root().ownerDocument.createElement("li");
            added.updateTextContent("inserted");
            v.root().getNodeById("list").insertBefore(added, v.root().getNodeById("item-5"));
            v.ui().flushInvalidation();
            assertTrue(row.y() > y0, "後続の行は平行移動する");
            assertEquals(own0, row.ownPaintStamp(), "平行移動だけでは箱自身の印を進めない（窓の外の部分木を再利用できる）");
            assertTrue(row.subtreePaintStamp() > sub0, "親が下りて確認できるよう、部分木の印は進める");
            // 形（高さ）が変わると箱自身の印が進む
            el(v, "item-30").setAttribute("style", "height:77px");
            v.ui().flushInvalidation();
            assertTrue(row.ownPaintStamp() > own0, "形が変わった箱は自身の印が進む");
            assertMatchesFresh(v, "形の変更");
        }
    }

    // ---- ③ インク境界の単体 ----

    @Test
    void inkBoundsAreTheRelativeUnionOfTheSubtreeGeometry() {
        try (StandaloneView v = new StandaloneView(doc(20, 0, true, false), W, H, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            LayoutBox row = boxOf(v.ui().layoutRoot(), "item-5");
            assertNotNull(row);
            float[] ink = InkBounds.compute(row);
            assertNotNull(ink, "入れ子の通常の行はインク境界を作れる");
            assertEquals(0f, ink[0], 0.001f, "左は箱の左端");
            assertEquals(0f, ink[1], 0.001f, "上は箱の上端");
            assertTrue(ink[2] >= row.w() - 0.001f && ink[3] >= row.h() - 0.001f, "右下は箱の大きさ以上");
        }
    }

    @Test
    void inkBoundsAreRefusedForSubtreesThatDoNotTranslateRigidly() {
        try (StandaloneView v = new StandaloneView(doc(20, 0, false, true), W, H, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            LayoutBox plain = boxOf(v.ui().layoutRoot(), "item-5");
            LayoutBox withAbs = boxOf(v.ui().layoutRoot(), "item-7");
            assertNotNull(InkBounds.compute(plain), "位置指定の子孫の無い行は作れる");
            assertNull(InkBounds.compute(withAbs), "位置指定の子孫を含む部分木は作らない（平行移動で動かない）");
            LayoutBox text = plain.children.stream().filter(c -> c.node != null && "#text".equals(c.node.tag)).findFirst().orElse(null);
            if (text != null) assertNull(InkBounds.compute(text), "文字・無名の箱は窓の判定を親から受けるので作らない");
        }
    }

    @Test
    void stillOutsideIsAConservativeNegationOfTheWindowTest() {
        float[] ink = {0f, 0f, 100f, 40f}; // 箱の左上からの相対
        float m = DisplayListBuilder.CULL_MARGIN;
        assertTrue(InkBounds.stillOutside(ink, 0f, H + m + 1f, W, H, m), "窓の下（余白の外）");
        assertTrue(InkBounds.stillOutside(ink, 0f, -40f - m - 1f, W, H, m), "窓の上");
        assertTrue(InkBounds.stillOutside(ink, W + m + 1f, 0f, W, H, m), "窓の右");
        assertTrue(InkBounds.stillOutside(ink, -100f - m - 1f, 0f, W, H, m), "窓の左");
        assertEquals(false, InkBounds.stillOutside(ink, 0f, H + m, W, H, m), "余白の縁に触れたら窓の中（窓の判定は y <= rootH + margin）");
        assertEquals(false, InkBounds.stillOutside(ink, 10f, 10f, W, H, m), "窓の中");
        assertEquals(false, InkBounds.stillOutside(null, 0f, H + m + 5000f, W, H, m), "境界が無ければ使わない");
        assertTrue(InkState.FREE.outside(0f, 0f, W, H, m), "項目を持つ箱が無い部分木は位置に依らない");
    }
}
