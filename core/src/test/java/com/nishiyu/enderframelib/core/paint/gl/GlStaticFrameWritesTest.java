package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * GPU レコードの書き込みの収支。同じ内容のフレームを描き直したとき、レコードの値は変わらないので書き込み（フィールド単位の「変更」）は 0 になる。
 * 以前は、プリミティブのクリップ欄を図形ごとに「なし → 個数」と二度書いて毎フレーム全図形ぶんが「変更」になり、永続バッファが常に汚れていた
 * （showcase の hover で 1 フレーム 2,607 件。修正後 143 件）。
 */
class GlStaticFrameWritesTest {
    private static final long T0 = 10_000L;

    private static final String DOC = "<html><body style='margin:0;font:12px sans-serif'>"
            + "<div style='position:absolute;left:10px;top:10px;width:120px;height:60px;background:#3366cc;border-radius:8px'></div>"
            + "<div style='position:absolute;left:30px;top:90px;width:90px;height:30px;overflow:hidden;background:#ddd;border-radius:6px'>"
            + "<div style='width:200px;height:20px;background:#888'>clipped text inside</div></div>"
            + "<div style='position:absolute;left:150px;top:10px;width:60px;height:60px;background:#aa2222;border:3px solid #000'>t</div>"
            + "</body></html>";

    private static void requireGl() {
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(8, 8)) {
            // 作れれば良い
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
    }

    @Test
    void redrawingTheSameFrameWritesNothing() {
        requireGl();
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(240, 180)) {
            SoftwareRendering.renderHtml(DOC, 240, 180, T0, g);
            long shapes1 = g.rectTreeShapes();
            assertTrue(shapes1 > 0, "新経路が使われる");
            long afterFirst = g.rectTreeChangedWrites();
            assertTrue(afterFirst > 0, "初回は値が書き込まれる");
            // 3 面のリングが一巡するまで描き直し、最後の 1 回の収支を見る
            long before = 0;
            for (int i = 0; i < 4; i++) {
                before = g.rectTreeChangedWrites();
                SoftwareRendering.renderHtml(DOC, 240, 180, T0, g);
            }
            long delta = g.rectTreeChangedWrites() - before;
            assertTrue(g.rectTreeShapes() > shapes1, "描き直しでも図形は積まれている");
            assertEquals(0, delta, "同じ内容のフレームでは GPU レコードの値は 1 つも変わらない");
        }
    }
}
