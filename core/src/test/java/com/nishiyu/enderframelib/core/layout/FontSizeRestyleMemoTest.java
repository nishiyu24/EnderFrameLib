package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * B11: {@code font-size} だけが変わる全行の再スタイルで、形に依らない覚え（float の有無・書体の解決）を捨てない。
 * <ul>
 *   <li>形の世代（{@link LayoutBox#shapeEpoch()}）は display / position / float が変わったときだけ進む。以前は要素の箱を
 *       再スタイルするたびに進み、全部の float メモが毎フレーム外れていた。メモの正しさはテストの JVM で常に有効な
 *       {@code verifyFloatMemo}（食い違えば {@link AssertionError}）が見張る。</li>
 *   <li>書体の解決は、解決器が世代を持つ（{@link FontResolver#resolveGeneration()} が 0 以上）なら算出スタイルが覚える。</li>
 * </ul>
 */
class FontSizeRestyleMemoTest {

    private static String rows(int n, String extraRowStyle) {
        StringBuilder sb = new StringBuilder("<style>body{margin:0;font-size:14px}.row{padding:2px;" + extraRowStyle + "}</style><div id=list>");
        for (int i = 0; i < n; i++) sb.append("<div class=row id=r").append(i).append("><span>#").append(i).append("</span> text ").append(i).append("</div>");
        return sb.append("</div>").toString();
    }

    @Test
    void fontSizeOnlyRestyleKeepsTheShapeEpochAndFloatMemos() {
        StandaloneView v = new StandaloneView(rows(200, ""), 640, 480, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        HtmlElement list = (HtmlElement) v.ui().domDocument().getElementById("list");
        list.setAttribute("style", "font-size:15px");
        v.ui().flushInvalidation(); // 1 回目で状態を揃える
        int epoch = LayoutBox.shapeEpoch();
        long scans = BlockLayoutEngine.floatMemoScans;
        for (int i = 0; i < 6; i++) {
            list.setAttribute("style", (i & 1) == 0 ? "font-size:16px" : "font-size:15px");
            v.ui().flushInvalidation();
        }
        assertEquals(epoch, LayoutBox.shapeEpoch(), "font-size だけの再スタイルで形の世代が進んだ");
        assertEquals(scans, BlockLayoutEngine.floatMemoScans, "float メモが外れて子を走査し直した");

        // float を足すと世代が進み、メモは新しい形で取り直される（古いままなら verifyFloatMemo が AssertionError）
        HtmlElement row = (HtmlElement) v.ui().domDocument().getElementById("r3");
        row.setAttribute("style", "float:left;width:40px");
        v.ui().flushInvalidation();
        assertNotEquals(epoch, LayoutBox.shapeEpoch(), "float の変わり目で形の世代が進まない");
        assertTrue(BlockLayoutEngine.containsFloats(boxOf(v.ui().layoutRoot(), list)), "float を足したのにメモが古い");
        row.removeAttribute("style");
        v.ui().flushInvalidation();
        int afterRemoval = LayoutBox.shapeEpoch();
        assertTrue(!BlockLayoutEngine.containsFloats(boxOf(v.ui().layoutRoot(), list)), "float を外したのにメモが古い");

        // position と display の変わり目でも進む
        row.setAttribute("style", "position:absolute");
        v.ui().flushInvalidation();
        assertNotEquals(afterRemoval, LayoutBox.shapeEpoch(), "position の変わり目で形の世代が進まない");
        int afterPosition = LayoutBox.shapeEpoch();
        row.setAttribute("style", "display:none");
        v.ui().flushInvalidation();
        assertNotEquals(afterPosition, LayoutBox.shapeEpoch(), "display の変わり目で形の世代が進まない");
        v.close();
    }

    private static LayoutBox boxOf(LayoutBox b, Object node) {
        if (b.node == node) return b;
        for (LayoutBox c : b.children) {
            LayoutBox hit = boxOf(c, node);
            if (hit != null) return hit;
        }
        return null;
    }

    /** 数える解決器。{@code generation} が負なら覚えさせない。 */
    private static final class CountingResolver implements FontResolver {
        final AtomicLong calls = new AtomicLong();
        volatile long generation;

        CountingResolver(long generation) {
            this.generation = generation;
        }

        @Override
        public FontFace resolve(String fontFamily) {
            calls.incrementAndGet();
            return FontFace.FALLBACK;
        }

        @Override
        public long resolveGeneration() {
            return generation;
        }
    }

    private static long resolvesOverFontSizeFlips(CountingResolver fonts) {
        StandaloneView v = new StandaloneView(rows(200, ""), 640, 480, fonts);
        v.ui().flushInvalidation();
        HtmlElement list = (HtmlElement) v.ui().domDocument().getElementById("list");
        long before = fonts.calls.get();
        for (int i = 0; i < 6; i++) {
            list.setAttribute("style", (i & 1) == 0 ? "font-size:16px" : "font-size:15px");
            v.ui().flushInvalidation();
        }
        long n = fonts.calls.get() - before;
        v.close();
        return n;
    }

    @Test
    void faceIsResolvedOncePerStyleWhenTheResolverHasAGeneration() {
        long memo = resolvesOverFontSizeFlips(new CountingResolver(0));
        long plain = resolvesOverFontSizeFlips(new CountingResolver(-1));
        assertTrue(plain > 0, "前提: 測り直しで書体を引いている");
        assertTrue(memo * 4 < plain, "覚えが効いていない: 世代あり " + memo + " 回 / 世代なし " + plain + " 回");
    }

    @Test
    void faceMemoIsKeyedByResolverAndGeneration() {
        StandaloneView v = new StandaloneView(rows(3, ""), 320, 200, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        ComputedStyle style = v.ui().layoutRoot().style;
        CountingResolver a = new CountingResolver(0), b = new CountingResolver(0);
        style.cacheFace(a, 7, FontFace.FALLBACK);
        assertSame(FontFace.FALLBACK, style.peekFace(a, 7));
        assertNull(style.peekFace(a, 8), "世代が進んだら覚えを使わない");
        assertNull(style.peekFace(b, 7), "別の解決器の覚えは使わない");
        v.close();
    }

    @Test
    void systemFontsGenerationAdvancesWhenResolvedFacesAreDropped() {
        SystemFonts fonts = new SystemFonts(List.of(), Locale.ENGLISH);
        long g0 = fonts.resolveGeneration();
        assertTrue(g0 >= 0, "OS の書体の解決器は覚えてよい");
        assertEquals(g0, fonts.resolveGeneration(), "何も変わらなければ同じ世代");
        fonts.clearResolved();
        assertNotEquals(g0, fonts.resolveGeneration(), "覚えを捨てたら世代が進む");
        assertEquals(-1, FontResolver.FALLBACK.resolveGeneration(), "既定は覚えない");
    }
}
