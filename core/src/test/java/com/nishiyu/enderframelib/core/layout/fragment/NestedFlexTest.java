package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2a 判定基準 2: 入れ子 Flex（row と column を交互に、各段で stretch・flex-grow による pass 2 が連鎖する文書）で、
 * 再測定が深さに対して指数的に増えないこと・内在サイズの計算が変わった箱あたり 1 回以下・pass 1 と pass 2 がスロット内で
 * 互いを追い出さないこと。
 *
 * <p>M2b-2 で {@code align-content: normal}（＝stretch）を入れたので、折り返す row の容器が親の column に伸ばされると行が伸び、
 * 短い項目は「内容の行の高さ」と「伸びた行の高さ」の 2 つの stretch の制約で測られる（仮測定と合わせて 1 フレームに最大 3 回。
 * 仕様どおりの 3 つの異なる制約）。上限はそれに合わせて箱あたり 3 回・平均 1.75 回にした（M2a は 2 回・1.5 回。実測は深さ 3〜8 で
 * 平均 1.33〜1.59 回・最大 3 回で深さに依らず一定、追い出し 0）。</p>
 */
class NestedFlexTest {
    /** 1 フレームに 1 つの箱を整形してよい回数（仮測定・内容の行への stretch・伸びた行への stretch）。 */
    private static final int MAX_LAYOUTS_PER_BOX = 3;
    /** 箱あたりの整形の平均の上限。 */
    private static final double MAX_AVG_LAYOUTS = 1.75;

    /** 整形する箱の数（容器 2^0..2^(d-1) ＋ 葉 2^d ＋ 根・html・body）。 */
    private static int laidOutBoxes(int depth) {
        return (1 << (depth + 1)) - 1 + 3;
    }

    @Test
    void scratchAndResizeAreLinearInDepth() {
        double prevPerBox = 0;
        for (int depth = 3; depth <= 8; depth++) {
            SpikeView v = new SpikeView(SpikeView.nestedFlex(depth, 2, true), 900, 600);
            LayoutCache cache = v.layout.session().cache();
            cache.trackPerBox(true);
            v.frame();
            int boxes = laidOutBoxes(depth);
            LayoutCache.Stats st = cache.stats();
            assertEquals(0, st.slotEvictions, "depth " + depth + ": " + st);
            double perBox = (double) st.layoutComputes / boxes;
            assertTrue(perBox <= MAX_AVG_LAYOUTS, "depth " + depth + " 初回の整形が箱あたり " + perBox + " 回: " + st);
            assertMaxPerBox(v.boxRoot, cache, MAX_LAYOUTS_PER_BOX, 1, "depth " + depth + " 初回");
            prevPerBox = perBox;

            // 幅の変更: 全ての箱が新しい制約になるが、内在サイズは幅に依らないので全て当たる
            v.layout.layout(v.boxRoot, 899, 600);
            st = cache.stats();
            assertEquals(0, st.minMaxComputes, "depth " + depth + " 幅の変更で内在サイズを計算し直した: " + st);
            assertEquals(0, st.slotEvictions);
            assertTrue((double) st.layoutComputes / boxes <= MAX_AVG_LAYOUTS, "depth " + depth + " 幅の変更: " + st);
            assertMaxPerBox(v.boxRoot, cache, MAX_LAYOUTS_PER_BOX, 0, "depth " + depth + " 幅の変更");
        }
        assertTrue(prevPerBox <= MAX_AVG_LAYOUTS);
    }

    @Test
    void leafChangeRecomputesBoundedAndSecondFrameHitsEverything() {
        for (int depth = 3; depth <= 8; depth++) {
            SpikeView v = new SpikeView(SpikeView.nestedFlex(depth, 2, true), 900, 600);
            LayoutCache cache = v.layout.session().cache();
            cache.trackPerBox(true);
            v.frame();
            String leaf = "leaf-" + "0".repeat(depth + 1);
            ((HtmlText) v.byId(leaf).children.get(0)).setData("leaf changed to a noticeably longer text");
            v.frame();
            LayoutCache.Stats st = cache.stats();
            assertEquals(0, st.slotEvictions, "depth " + depth + ": " + st);
            assertMaxPerBox(v.boxRoot, cache, MAX_LAYOUTS_PER_BOX, 1, "depth " + depth + " 葉の変更");
            assertTrue(st.minMaxComputes <= depth + 2, "depth " + depth + " 内在サイズの計算は祖先の数まで: " + st);

            // 何も変えないフレームは根で当たる（pass 1 と pass 2 の両方がスロットに残っている）
            LayoutFragment before = v.layout.root();
            v.frame();
            assertEquals(0, cache.stats().layoutComputes, "depth " + depth + ": " + cache.stats());
            assertSame(before, v.layout.root());

            // 同じ葉を元へ戻すと、前回の結果のどれかに当たり得るが、少なくとも再測定は箱あたり上限以下
            ((HtmlText) v.byId(leaf).children.get(0)).setData("leaf " + "0".repeat(depth + 1) + " text");
            v.frame();
            assertMaxPerBox(v.boxRoot, cache, MAX_LAYOUTS_PER_BOX, 1, "depth " + depth + " 戻し");
        }
    }

    /** 全ての箱で、整形の回数が {@code maxLayout} 以下・内在サイズの計算が {@code maxMinMax} 以下。 */
    private static void assertMaxPerBox(BoxNode b, LayoutCache cache, int maxLayout, int maxMinMax, String what) {
        int[] c = cache.perBox(b);
        assertTrue(c[0] <= maxLayout, what + ": " + b + " を " + c[0] + " 回整形");
        assertTrue(c[1] <= maxMinMax, what + ": " + b + " の内在サイズを " + c[1] + " 回計算");
        for (int i = 0, n = b.children.size(); i < n; i++) assertMaxPerBox(b.children.get(i), cache, maxLayout, maxMinMax, what);
    }
}
