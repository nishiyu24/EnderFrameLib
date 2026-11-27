package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * B12: {@code position: sticky}（上の inset だけ）を含むスクロールコンテナも中身を 1 つの節にまとめ、スクロールだけの更新では
 * 表示リストを作り直さず、中身の節の移動に「貼り付きのずれの増減」（{@link com.nishiyu.enderframelib.core.paint.model.StickyShift}）を
 * sticky の箱の節へ足して済ませる。毎フレーム全体を作り直した面（参照）と、スクロールの毎フレームの画素と
 * ヒットテストが一致すること、作り直しを実際に省いたことを確かめる。親がコンテナ自身の sticky（ずっと貼り付く）と、
 * 親が中身の中の sticky（節の終わりで外れる）の両方を、貼り付く前・貼り付いている間・外れた後まで回す。
 */
class StickyScrollNodeTest {
    private static final int W = 320, H = 240;

    static String doc() {
        StringBuilder sections = new StringBuilder();
        for (int s = 0; s < 4; s++) {
            sections.append("<div class='sec' id='sec").append(s).append("'><div class='sh' id='sh").append(s).append("'>section ").append(s).append("</div>");
            for (int r = 0; r < 6; r++) sections.append("<div class='row'>row ").append(s).append('.').append(r).append("</div>");
            sections.append("</div>");
        }
        return "<html><head><style>"
                + "body{margin:0;font:12px sans-serif;background:#f4f4f8}"
                + "#sc{position:absolute;left:0;top:0;width:300px;height:220px;overflow-y:auto;background:#fff}"
                + ".nav{position:sticky;top:0;z-index:2;height:24px;background:rgba(20,30,60,.85);color:#fff;border-bottom:1px solid #000}"
                + ".nav span{display:inline-block;padding:4px 8px;background:#36c;border-radius:6px}"
                + ".sec{position:relative;margin:6px;border:1px solid #99a;background:#eef}"
                + ".sh{position:sticky;top:25px;z-index:1;height:18px;background:#fc6}"
                + ".row{height:22px;margin:2px;background:#dde}"
                + ".row:hover{background:#9c9}"
                + "</style></head><body><div id='sc'>"
                + "<div class='nav' id='nav'><span id='navbtn'>Menu</span> header</div>"
                + "<div class='row'>top 1</div><div class='row'>top 2</div>"
                + sections
                + "<div class='row'>bottom</div></div></body></html>";
    }

    record Run(List<int[]> pixels, List<String> hits, long translated) { }

    /** {@code stickyNode = false} は参照: 毎フレームの前に前回の木と記憶を捨てて全体を作り直す（節の移動を使わない）。 */
    static Run run(boolean stickyNode, RenderBackend backend) {
        long tr0 = UiView.STAT_SCROLL_TRANSLATED.sum();
        List<int[]> pixels = new ArrayList<>();
        List<String> hits = new ArrayList<>();
        int[][] probes = {{40, 8}, {150, 12}, {60, 32}, {200, 120}};
        try (StandaloneView v = new StandaloneView(doc(), W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            v.frame(t, backend, true);
            v.mouseMove(150, 150, backend.sessionId());
            for (int step = 0; step < 28; step++) {
                v.scroll(0, step < 22 ? -1 : 1, backend.sessionId()); // 下へ 22 段、戻りを 6 段
                for (int f = 0; f < 3; f++) {
                    if (!stickyNode) v.ui().markInvalidation(com.nishiyu.enderframelib.common.UiView.InvalidationLevel.RELAYOUT);
                    v.frame(t += 16, backend, true);
                    pixels.add(backend.pixmap().pixels().clone());
                }
                StringBuilder h = new StringBuilder();
                for (int[] p : probes) {
                    var hit = v.ui().hitTest(p[0], p[1]);
                    h.append(hit == null ? "-" : hit.baseId()).append(' ');
                }
                hits.add(h.toString());
            }
        }
        return new Run(pixels, hits, UiView.STAT_SCROLL_TRANSLATED.sum() - tr0);
    }

    static void assertSame(Run expected, Run actual) {
        assertEquals(expected.pixels().size(), actual.pixels().size());
        for (int i = 0; i < expected.pixels().size(); i++) {
            assertArrayEquals(expected.pixels().get(i), actual.pixels().get(i), "フレーム " + i + " の画素が作り直しと違う");
        }
        assertEquals(expected.hits(), actual.hits(), "ヒットテストが作り直しと違う");
    }

    @Test
    void stickyInsideNodeScrollMatchesRebuildOnCpu() {
        Run rebuild, node;
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            rebuild = run(false, cpu);
        }
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            node = run(true, cpu);
        }
        assertTrue(node.translated() > rebuild.translated(), "sticky を含むコンテナでも節の移動で済ませた: " + node.translated() + " vs " + rebuild.translated());
        assertSame(rebuild, node);
    }

    @Test
    void stickyInsideNodeScrollMatchesRebuildOnGl() {
        try {
            new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(8, 8).close();
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
        Run rebuild, node;
        try (var gl = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(W, H)) {
            rebuild = run(false, gl);
        }
        try (var gl = new com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend(W, H)) {
            node = run(true, gl);
        }
        assertTrue(node.translated() > rebuild.translated(), "GL でも節の移動で済ませた");
        assertSame(rebuild, node);
    }

    @Test
    void stickyShiftFormulaMatchesLayout() {
        // 式の単体: 親がコンテナ自身（下端は動かない）なら上の限界に貼り付き続け、親が中身の中なら包含ブロックの下端で外れる
        var fixedParent = new com.nishiyu.enderframelib.core.paint.model.StickyShift("n", 0f, 220f, 0f, 24f, 0f, false, 0f);
        assertEquals(0f, fixedParent.dyAt(0f));
        assertEquals(100f, fixedParent.dyAt(100f), "100 下へスクロールすると 100 だけ下げて上に留める");
        var movingParent = new com.nishiyu.enderframelib.core.paint.model.StickyShift("n", 25f, 200f, 30f, 18f, 0f, true, 0f);
        assertEquals(0f, movingParent.dyAt(0f), "まだ限界より下");
        assertEquals(15f, movingParent.dyAt(20f), "限界を越えたぶんだけ下げる");
        // 400 進むと貼り付く前の y は -370・包含ブロックの下端は -200。箱の下端を包含ブロックの下端に揃えた所（-218）で止まる
        assertEquals(152f, movingParent.dyAt(400f), "包含ブロックの下端より下へは出ない（一緒に画面の外へ去る）");
        assertEquals(15f - 0f, movingParent.extraAt(20f));
    }
}
