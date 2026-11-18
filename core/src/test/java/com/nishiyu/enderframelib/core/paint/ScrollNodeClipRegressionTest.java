package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * T2（実文書の差分の経路 = 全体の作り直し）で見つけた 2 つの不具合の回帰テスト。どちらも、スクロールの中身を 1 つの節にまとめて
 * 動かす経路（節の移動量で表す）と、全体を作り直す経路（位置に焼き込む）で結果が違った。
 * <ol>
 *   <li>ヒットテスト: 動いた外側のスクロールの中の、入れ子のスクロールコンテナ（{@code overflow} の箱）の中身は、合成済みの
 *       クリップの矩形が作ったときの位置のままだったので、見えている位置で文字ではなく親の箱に当たった（{@code HitTestIndex} は、
 *       祖先が動くときクリップを連鎖の部品ごとに持ち主の今の姿勢で当てる）。</li>
 *   <li>描画: 凡例つきの fieldset の枠線は、凡例を避ける 3 つのクリップを連鎖の無い矩形（フレームの空間に固定）で作っていたので、
 *       fieldset を含むスクロールの中身が動くと、上の枠線が元の位置のクリップで切り落とされて消えた
 *       （{@code BoxDecorationPainter.drawBorderAroundLegend} は祖先の連鎖に fieldset 自身の矩形を足す）。</li>
 * </ol>
 */
class ScrollNodeClipRegressionTest {
    private static final int W = 320, H = 240;

    private static final String DOC = "<html><head><style>"
            + "body{margin:0;font:12px sans-serif;background:#fff}"
            + "#outer{position:absolute;left:10px;top:10px;width:280px;height:200px;overflow-y:auto;background:#eef}"
            + ".pad{height:60px;background:#dde;margin:4px}"
            + ".cell{display:inline-block;width:60px;overflow:hidden;white-space:nowrap;background:#fdd;margin:4px}"
            + "fieldset{margin:6px;border:2px solid #36c;border-radius:6px;padding:4px}"
            + "legend{padding:0 4px;color:#36c}"
            + "</style></head><body><div id='outer'>"
            + "<div class='pad'>top</div>"
            + "<div><span class='cell' id='c1'><b id='t1'>clipped wide text inside a small box</b></span>"
            + "<span class='cell' id='c2'><b id='t2'>another wide text</b></span></div>"
            + "<fieldset><legend>Legend</legend><div class='pad'>inside</div></fieldset>"
            + "<div class='pad'>a</div><div class='pad'>b</div><div class='pad'>c</div>"
            + "</div></body></html>";

    /** 毎フレームの画素とヒットテスト（格子）。{@code full} なら各フレームの前に全体の再レイアウトを強いる。 */
    private static List<String> run(boolean full, List<int[]> pixels) {
        List<String> hits = new ArrayList<>();
        try (StandaloneView v = new StandaloneView(DOC, W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(W, H, 1f)) {
            long t = 1000;
            v.frame(t, sw, true);
            v.mouseMove(150, 100, sw.sessionId());
            for (double notch : new double[]{-1, -1, 1}) {
                v.scroll(0, notch, sw.sessionId());
                for (int k = 0; k < 4; k++) {
                    if (full) v.ui().markInvalidation(UiView.InvalidationLevel.RELAYOUT);
                    v.frame(t += 60, sw, true);
                    pixels.add(sw.pixmap().pixels().clone());
                    StringBuilder h = new StringBuilder();
                    for (int y = 12; y < 210; y += 6) {
                        for (int x = 12; x < 290; x += 8) {
                            var hit = v.ui().hitTest(x, y);
                            h.append(hit == null ? "-" : hit.element().baseId()).append(' ');
                        }
                    }
                    hits.add(h.toString());
                }
            }
        }
        return hits;
    }

    @Test
    void scrolledContentMatchesFullRebuildInPixelsAndHits() {
        List<int[]> incrPx = new ArrayList<>(), fullPx = new ArrayList<>();
        List<String> incr = run(false, incrPx), full = run(true, fullPx);
        assertEquals(full.size(), incr.size());
        for (int f = 0; f < full.size(); f++) {
            assertEquals(full.get(f), incr.get(f), "フレーム " + f + " のヒットテストが全体の作り直しと違う");
            assertArrayEquals(fullPx.get(f), incrPx.get(f), "フレーム " + f + " の画素が全体の作り直しと違う（fieldset の上の枠線）");
        }
    }

    @Test
    void textInNestedClipIsHitWhereItIsDrawnAfterOuterScroll() {
        try (StandaloneView v = new StandaloneView(DOC, W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend sw = new SoftwareRenderBackend(W, H, 1f)) {
            long t = 1000;
            v.frame(t, sw, true);
            var before = v.ui().hitTest(30, 82);
            assertNotNull(before);
            v.mouseMove(150, 100, sw.sessionId());
            v.scroll(0, -1, sw.sessionId()); // 外側を下へ
            for (int k = 0; k < 10; k++) v.frame(t += 60, sw, true);
            float sy = v.ui().scrollState().getScrollY(v.root().ownerDocument.getElementById("outer"));
            assertTrue(sy >= 20, "外側がスクロールした前提: " + sy);
            // スクロールした分だけ上に描かれている文字の点に当たる（親の span ではなく、同じ文字の項目）
            var after = v.ui().hitTest(30, 82 - sy);
            assertNotNull(after);
            assertEquals(before.element().baseId(), after.element().baseId(), "見えている位置の文字に当たる");
        }
    }
}
