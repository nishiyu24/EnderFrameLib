package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.common.TestItems;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 描くフレームのチャンクのカリング（M3b-4c-2b-2c。{@link FrameComposer}・{@code SceneAdapterBox#paintChildren}）。
 *
 * <ul>
 *   <li>長いリストは見えるチャンクだけを組む（文書のスクロールでも、中身をスクロール 0 で組む内側のコンテナのスクロールでも）。</li>
 *   <li>描いた画素は、カリングしない列（利用者の平行移動があるフレームは全部を組む）で描いた画素と同じ。外へ塗る影・フィルター・外側の
 *   リストの印・切り抜く箱の中の固定配置も含む（包含矩形は塗る範囲）。</li>
 * </ul>
 */
class FrameCullingTest {
    private static final int W = 240, H = 180;

    private static String list(int rows, boolean inner) {
        StringBuilder sb = new StringBuilder("<style>html,body{height:100%} body{margin:0;overflow-y:auto} li{height:18px;border-bottom:1px solid #ccc} li:nth-child(3n){background:#cde}</style>");
        sb.append(inner ? "<div id=s style='height:120px;overflow-y:auto'><ul style='margin:0'>" : "<ul style='margin:0'>");
        for (int i = 0; i < rows; i++) sb.append("<li>row ").append(i).append("</li>");
        sb.append(inner ? "</ul></div>" : "</ul>");
        return sb.append("<div><span id=z></span></div>").toString();
    }

    /** {@code v} の今の状態を新しい描画器で全面に描いた画素（{@code unculled} なら利用者の平行移動でカリングを切る）。 */
    private static int[] paint(StandaloneView v, boolean unculled, long t) {
        UiView ui = v.ui();
        if (unculled) ui.setPaintOffset("z", 0.5f, 0f); // 中身の無い節: 描く絵は同じで、組む列は全部
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f)) {
            b.render(ui.renderFrame(List.of(), null, t));
            return b.pixmap().pixels().clone();
        } finally {
            if (unculled) ui.clearPaintOffset("z");
        }
    }

    private static float scrollY(StandaloneView v) {
        return (float) v.ui().dynamicState().scroll.snapshot().values().stream().mapToDouble(xy -> xy[1]).max().orElse(0);
    }

    private static int composedCount(StandaloneView v, long t) {
        return new FrameComposer().compose(v.ui().renderFrame(List.of(), null, t)).items().size();
    }

    @Test
    void longListsComposeOnlyTheVisibleChunks() {
        for (boolean inner : new boolean[]{false, true}) {
            try (StandaloneView v = new StandaloneView(list(2000, inner), W, H, FontResolver.FALLBACK)) {
                int all = TestItems.of(v.ui()).size();
                int culled = composedCount(v, 0);
                assertTrue(culled * 20 < all, (inner ? "内側" : "文書") + ": 見えるチャンクだけ " + culled + " / " + all);
                assertTrue(java.util.Arrays.equals(paint(v, true, 0), paint(v, false, 0)), "画素 = カリングしない列");
                float before = scrollY(v);
                // 真ん中までスクロールしても見える分だけで、入ってきた行が描かれる
                for (int i = 0; i < 40; i++) v.ui().handleScrollLocally(20, 20, 0, -40); // 内側ならコンテナ、そうでなければ文書
                v.ui().flushInvalidation();
                assertTrue(scrollY(v) > before + 500f, "前提: スクロールした " + scrollY(v));
                int scrolled = composedCount(v, 16);
                assertTrue(scrolled * 20 < all, "スクロールしても見えるチャンクだけ " + scrolled);
                assertTrue(java.util.Arrays.equals(paint(v, true, 16), paint(v, false, 16)), "スクロール後の画素 = カリングしない列");
            }
        }
    }

    @Test
    void scrollingRecomposesAndMatchesAFreshUnculledPaint() {
        try (StandaloneView v = new StandaloneView(list(600, true), W, H, FontResolver.FALLBACK);
             SoftwareRenderBackend b = new SoftwareRenderBackend(W, H, 1f)) {
            long t = 1000;
            v.frame(t, b, true);
            for (int step = 0; step < 12; step++) { // チャンク（32 行）の境目を何度もまたぐ
                v.ui().handleScrollLocally(20, 20, 0, -7);
                v.frame(t += 16, b, true);
                assertTrue(java.util.Arrays.equals(paint(v, true, t), b.pixmap().pixels()), "差分で描いた画素 = カリングしない全面: " + step);
            }
        }
    }

    /**
     * 先頭のチャンク（32 個の 10px の箱。0〜320px）の最後の箱を、枠は外でも中へ塗る箱にし、文書を 330px スクロールしてチャンクごと上の外へ出す:
     * ぼかしの大きい影・切り抜く箱の中の固定配置（固定配置は根の層の子に置かれ、外の箱と一緒に飛ばさない）。包含矩形が塗る範囲を含まないと、カリングで飛ばして画素が欠ける。
     * （ずらした影・フィルターは、枠が外の項目・層を描画側がカリングに依らず描かない〔走査器は項目の矩形に影のぼかしと字の余白だけを足して
     * 飛ばす。描き直す範囲と同じ規則〕ので、ここでは確かめられない。包含矩形はフィルターの広がりも含む。）
     */
    @Test
    void inkOutsideTheBoxStillPaints() {
        String[] cases = {
                "<div style='margin-left:10px;width:60px;height:10px;background:#08f;box-shadow:0 0 40px 10px #f00'></div>",
                "<div style='width:50px;height:10px;overflow:hidden'><div style='position:fixed;left:170px;top:20px;width:30px;height:30px;background:#fc0'></div></div>"};
        java.util.function.IntPredicate[] inks = {
                p -> ((p >> 16) & 0xFF) > 0x40 && ((p >> 8) & 0xFF) < 0x20 && (p & 0xFF) < 0x20,
                p -> (p & 0xFFFFFF) == 0xFFCC00};
        for (int ci = 0; ci < cases.length; ci++) {
            StringBuilder sb = new StringBuilder("<style>html,body{height:100%} body{margin:0;overflow-y:auto}</style>");
            for (int i = 0; i < 31; i++) sb.append("<div style='height:10px'></div>");
            sb.append(cases[ci]);
            for (int i = 0; i < 200; i++) sb.append("<div style='height:20px'></div>"); // 背景なし（後の兄弟が影の上を塗らない）
            sb.append("<div><span id=z></span></div>");
            try (StandaloneView v = new StandaloneView(sb.toString(), W, H, FontResolver.FALLBACK)) {
                assertTrue(v.ui().hasFrame()); // シーンを組んでから（スクロールの当たりはシーンで取る）
                v.ui().handleScrollLocally(20, 20, 0, -11); // 文書を 330px（1 段 30px）
                v.ui().flushInvalidation();
                assertEquals(330f, scrollY(v), 0.5f, "前提: 先頭のチャンクがビューポートの上の外");
                assertTrue(composedCount(v, 0) < TestItems.of(v.ui()).size(), "前提: 先頭のチャンクを飛ばす");
                int[] ref = paint(v, true, 0), got = paint(v, false, 0);
                boolean seen = false;
                for (int p : ref) seen |= inks[ci].test(p);
                assertTrue(seen, "前提: カリングしない絵に外へ塗る色がある: " + cases[ci]);
                int diff = 0;
                for (int i = 0; i < ref.length; i++) if (ref[i] != got[i]) diff++;
                assertEquals(0, diff, "カリングで外へ塗る画素が欠けた: " + cases[ci]);
            }
        }
    }

    /**
     * 中身をスクロール 0 で組むコンテナの中の sticky の見出しは、組む位置（貼り付く前）と描く位置（貼り付いた後）が違う。見出しの子（文字）を
     * 組む位置で飛ばすと、貼り付いた見出しの文字が欠ける（2b-2c の作業で見つけた。sticky の下はカリングしない）。
     */
    @Test
    void stickyHeadersKeepTheirContentWhenStuckFarFromTheirPlace() {
        StringBuilder sb = new StringBuilder("<style>body{margin:0} #s{height:150px;overflow-y:auto} .h{position:sticky;top:0;height:20px;background:#fc6;font-size:16px}"
                + " .r{height:20px;border-bottom:1px solid #ccc}</style><div id=s>");
        for (int g = 0; g < 3; g++) {
            sb.append("<div><div class=h>HEAD ").append(g).append("</div>");
            for (int i = 0; i < 80; i++) sb.append("<div class=r>row ").append(i).append("</div>");
            sb.append("</div>");
        }
        sb.append("</div><div><span id=z></span></div>");
        try (StandaloneView v = new StandaloneView(sb.toString(), W, H, FontResolver.FALLBACK)) {
            assertTrue(v.ui().hasFrame());
            for (int step = 0; step < 6; step++) {
                v.ui().handleScrollLocally(20, 20, 0, -10); // 300px ずつ（1 つ目の見出しは貼り付いたまま、行は何チャンクも先）
                v.ui().flushInvalidation();
                assertTrue(java.util.Arrays.equals(paint(v, true, step), paint(v, false, step)), "貼り付いた見出しの画素 = カリングしない列: " + step);
            }
        }
    }

    @Test
    void userPaintOffsetsComposeEverything() {
        try (StandaloneView v = new StandaloneView(list(400, false), W, H, FontResolver.FALLBACK)) {
            UiView ui = v.ui();
            int all = TestItems.of(ui).size();
            ui.setPaintOffset("z", 1f, 0f);
            List<DisplayItem> items = new FrameComposer().compose(ui.renderFrame(List.of(), null, 0)).items();
            assertEquals(all, items.size(), "利用者の平行移動があるフレームは全部を組む");
        }
    }
}
