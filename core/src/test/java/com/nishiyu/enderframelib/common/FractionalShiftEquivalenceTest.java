package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Q1: 行の高さが小数でも、部分木の平行移動（小数ぶん）が「全部置き直した結果」とビット単位で一致すること。
 * 増分で行を挿入・削除した後の箱の木を、同じ最終 HTML を新規に開いた木と比べる。
 */
class FractionalShiftEquivalenceTest {

    private static final String STYLE = """
            <style>
              body { margin: 8px; font-size: 13px; }
              .row { padding: 3px 6px; margin: 1px 0; line-height: 1.37; }
              .big { font-size: 17px; line-height: 1.3; }
              .badge { font-size: 80%; }
            </style>""";

    private static String rowInner(int i) {
        return "Row " + i + " <span class=\"badge\">b" + i + "</span><span class=\"e\"></span>"
                + (i % 4 == 0 ? "<br>" : "") + " text text text";
    }

    private static String rowHtml(int i) {
        String cls = i % 3 == 0 ? "row big" : "row";
        return "<div id=\"r" + i + "\" class=\"" + cls + "\">" + rowInner(i) + "</div>";
    }

    private static String doc(List<Integer> ids) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html><html><head>").append(STYLE).append("</head><body><div id=\"list\">");
        for (int id : ids) sb.append(rowHtml(id));
        return sb.append("</div></body></html>").toString();
    }

    private static StandaloneView open(List<Integer> ids) {
        StandaloneView v = new StandaloneView(doc(ids), 640, 480, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        return v;
    }

    private static HtmlElement newRow(int id) {
        HtmlElement el = new HtmlElement("div");
        el.setAttribute("id", "r" + id);
        el.setAttribute("class", id % 3 == 0 ? "row big" : "row");
        el.setInnerHTML(rowInner(id));
        return el;
    }

    private static void collect(LayoutBox b, List<float[]> out, List<String> texts) {
        out.add(new float[] {b.x, b.y, b.w, b.h});
        for (LayoutBox.InlineFragment f : b.inlineFragments) {
            out.add(new float[] {f.x, f.y, f.w, f.h, f.baseline, f.descent});
            texts.add(f.text);
        }
        for (LayoutBox c : b.children) collect(c, out, texts);
    }

    private static void assertSameGeometry(LayoutBox expected, LayoutBox actual, String msg) {
        List<float[]> e = new ArrayList<>(), a = new ArrayList<>();
        List<String> et = new ArrayList<>(), at = new ArrayList<>();
        collect(expected, e, et);
        collect(actual, a, at);
        assertEquals(e.size(), a.size(), msg + ": 箱・断片の数");
        assertEquals(et, at, msg + ": 断片の文字");
        for (int i = 0; i < e.size(); i++) {
            assertArrayEquals(e.get(i), a.get(i), msg + ": #" + i + "（ビット一致）");
        }
    }

    @Test
    void lineHeightsLieOnTheLayoutUnitGrid() {
        StandaloneView v = open(List.of(0, 1, 2, 3));
        LayoutBox root = v.ui().layoutRoot();
        List<LayoutBox> stack = new ArrayList<>(List.of(root));
        int checked = 0;
        while (!stack.isEmpty()) {
            LayoutBox b = stack.remove(stack.size() - 1);
            if (b.lineHeightPx > 0) {
                assertTrue(LayoutUnit.isOnGrid(b.lineHeightPx), "lineHeightPx=" + b.lineHeightPx);
                checked++;
            }
            stack.addAll(b.children);
        }
        assertTrue(checked > 0);
    }

    @Test
    void layoutUnitSnapAndGrid() {
        assertEquals(0f, LayoutUnit.snap(0f));
        assertEquals(20.796875f, LayoutUnit.snap(20.8f)); // 20.8*64 = 1331.2 -> 1331/64
        assertTrue(LayoutUnit.isOnGrid(20.796875f));
        assertFalse(LayoutUnit.isOnGrid(20.8f));
        assertFalse(LayoutUnit.isOnGrid(Float.NaN));
        assertFalse(LayoutUnit.isOnGrid(1e9f));
        // 高さ 2^17 px を超える文書（2,000 行のリスト）でも格子の倍数は厳密。上限は 2^18
        assertTrue(LayoutUnit.isOnGrid(130978.640625f));
        assertTrue(LayoutUnit.isOnGrid(200000.5f));
        assertFalse(LayoutUnit.isOnGrid(262144f));
        float tall = 130978.640625f, d = LayoutUnit.snap(21.97f);
        assertEquals((double) tall + d, (double) (tall + d), "高い文書でも和は厳密（倍精度の和と一致）");
        assertEquals(1e9f, LayoutUnit.snap(1e9f));
        // 格子の倍数の和は float で厳密（結合的）
        float a = LayoutUnit.snap(13.37f), b = LayoutUnit.snap(21.97f), c = LayoutUnit.snap(7.77f);
        assertEquals((a + b) + c, a + (b + c));
    }

    @Test
    void fractionalInsertAndRemoveShiftsAndMatchesFullLayout() {
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 300; i++) ids.add(i);
        StandaloneView v = open(ids);
        HtmlNode list = v.root().getNodeById("list");
        long shifted0 = LayoutBox.layoutsShifted();

        // 先頭近くに小数の高さの行を挿入（後続の全行が小数ぶん下へ動く）
        HtmlElement inserted = newRow(1000);
        list.insertBefore(inserted, v.root().getNodeById("r20"));
        v.ui().flushInvalidation();
        ids.add(20, 1000);
        assertSameGeometry(open(ids).ui().layoutRoot(), v.ui().layoutRoot(), "挿入後");
        long afterInsert = LayoutBox.layoutsShifted() - shifted0;
        assertTrue(afterInsert > 100, "小数ぶんの移動でも後続の行を平行移動する: shifted=" + afterInsert);

        // 削除（逆向きの小数移動）
        list.removeChild(inserted);
        v.ui().flushInvalidation();
        ids.remove(Integer.valueOf(1000));
        assertSameGeometry(open(ids).ui().layoutRoot(), v.ui().layoutRoot(), "削除後");
    }

    @Test
    void randomInsertRemoveSequencesMatchFullLayout() {
        Random rnd = new Random(20261112L);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 80; i++) ids.add(i);
        StandaloneView v = open(ids);
        HtmlNode list = v.root().getNodeById("list");
        int next = 5000;
        for (int step = 0; step < 25; step++) {
            if (rnd.nextBoolean() && ids.size() > 10) {
                int at = rnd.nextInt(ids.size());
                int id = ids.remove(at);
                list.removeChild(v.root().getNodeById("r" + id));
            } else {
                int at = rnd.nextInt(ids.size());
                int id = next++;
                HtmlNode before = v.root().getNodeById("r" + ids.get(at));
                list.insertBefore(newRow(id), before);
                ids.add(at, id);
            }
            v.ui().flushInvalidation();
            assertSameGeometry(open(ids).ui().layoutRoot(), v.ui().layoutRoot(), "step " + step);
        }
    }

    @Test
    void emMarginsAndHorizontalFlexGridShiftsMatchFullLayout() {
        String html = """
                <!DOCTYPE html>
                <html>
                <head>
                <style>
                  html { font-size: 13px; }
                  body { margin: 0.37em 0.63rem; font-size: 13px; }
                  #wrap { width: 100%; display: flex; flex-direction: column; align-items: center; }
                  #banner { width: 200px; line-height: 1.37; padding: 0.31em 0.47em; margin: 0.23em 0; }
                  #flexBox {
                    display: flex; flex-direction: row; gap: 0.43em;
                    padding: 0.37em 0.61rem; margin: 0.29em 0.53rem;
                    width: 320px;
                  }
                  #gridBox {
                    display: grid; grid-template-columns: 140px 140px; gap: 0.39em;
                    padding: 0.41em 0.57em; margin: 0.27em 0.49rem;
                    width: 320px;
                  }
                  .tile { width: 120px; padding: 0.27em 0.51em; margin: 0.19em 0.33rem; line-height: 1.37; }
                </style>
                </head>
                <body>
                  <div id="wrap">
                    <div id="banner">Banner line</div>
                    <div id="flexBox">
                      Direct text
                      <div class="tile">F1 <span>s1</span></div>
                      <div class="tile">F2 <span>s2</span></div>
                    </div>
                    <div id="gridBox">
                      Grid text
                      <div class="tile">G1</div>
                      <div class="tile">G2</div>
                      <div class="tile">G3</div>
                    </div>
                  </div>
                </body>
                </html>""";

        int[] widths = {640, 639, 637, 634, 641, 640};
        StandaloneView v = new StandaloneView(html, widths[0], 480, FontResolver.FALLBACK);
        v.ui().flushInvalidation();

        // em / rem padding・margin が LayoutUnit 格子に乗っていることを検証
        List<LayoutBox> stack = new ArrayList<>(List.of(v.ui().layoutRoot()));
        int checked = 0;
        while (!stack.isEmpty()) {
            LayoutBox b = stack.remove(stack.size() - 1);
            assertTrue(LayoutUnit.isOnGrid(b.pT), "pT=" + b.pT);
            assertTrue(LayoutUnit.isOnGrid(b.pL), "pL=" + b.pL);
            assertTrue(LayoutUnit.isOnGrid(b.mT), "mT=" + b.mT);
            assertTrue(LayoutUnit.isOnGrid(b.mL), "mL=" + b.mL);
            assertTrue(LayoutUnit.isOnGrid(b.rowGap), "rowGap=" + b.rowGap);
            assertTrue(LayoutUnit.isOnGrid(b.columnGap), "columnGap=" + b.columnGap);
            checked++;
            stack.addAll(b.children);
        }
        assertTrue(checked >= 6);

        // 水平・垂直両方での FLEX / GRID 部分木平行移動（直下 #text 節を含む）と全体再計算とのビット一致を検証
        long shiftedBefore = LayoutBox.layoutsShifted();
        HtmlNode banner = v.root().getNodeById("banner");
        for (int i = 1; i < widths.length; i++) {
            if (i == 2) {
                banner.setTextContent("Banner updated wrapping line two");
            } else if (i == 4) {
                banner.setTextContent("Short");
            }
            v.ui().recomputeLayout(widths[i], 480);

            StandaloneView fresh = new StandaloneView(html, widths[i], 480, FontResolver.FALLBACK);
            fresh.ui().flushInvalidation();
            if (i >= 4) {
                fresh.root().getNodeById("banner").setTextContent("Short");
                fresh.ui().flushInvalidation();
            } else if (i >= 2) {
                fresh.root().getNodeById("banner").setTextContent("Banner updated wrapping line two");
                fresh.ui().flushInvalidation();
            }
            assertSameGeometry(fresh.ui().layoutRoot(), v.ui().layoutRoot(), "width=" + widths[i]);
        }
        assertTrue(LayoutBox.layoutsShifted() > shiftedBefore,
                "FLEX / GRID コンテナを含む部分木平行移動が行われたこと");
    }
}
