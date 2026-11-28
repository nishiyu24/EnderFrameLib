package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.SceneSource;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * GPU の描画器の描かないフレーム（M3b-4b-4b。MOD の面 {@code MinecraftHostSurface} が旧 {@code StaticFrameGate} の代わりに使う
 * {@link LwjglGlRenderBackend#needsRepaint}）: 描かないと答えたフレームも含めて、使い続けた描画器の画素が、そのフレームの入力を
 * 新しい GPU の描画器で描いた画素と 1 画素も違わないこと。何も変わらないフレームは描かず、塗りの色の変化・{@code invalidate}
 * （書体の登録が変わったときのホストの知らせ）の後は描くこと。
 */
class GpuRepaintSkipTest {
    private static final int W = 360, H = 260;

    private static final String DOC = "<html><head><style>"
            + "body{margin:6px;font:13px sans-serif;color:#223;background:#f4f4f8}"
            + ".card{padding:6px;margin:4px 0;background:#fff;border:1px solid #bbb;border-radius:6px;box-shadow:0 2px 5px rgba(0,0,0,.25)}"
            + ".card:hover{background:#e8f0ff;color:#036}"
            + ".skew{transform:skewX(18deg);background:#0a8;height:24px;color:#fff}"
            + "#scroller{height:70px;overflow-y:auto;background:#eef}"
            + "</style></head><body>"
            + "<div class='card'>Card one with some text</div><div class='skew'>skewed (drawn on the CPU)</div>"
            + "<div class='card'>Card two</div><input type='text' value='field'><div id='scroller'>"
            + "<p>row 1</p><p>row 2</p><p>row 3</p><p>row 4</p><p>row 5</p><p>row 6</p></div></body></html>";

    private static void requireGl() {
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(8, 8)) {
            // 作れれば良い
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
    }

    @Test
    void skippedGpuFramesMatchAFreshGpuBackend() {
        requireGl();
        try (GpuSkipTwin twin = new GpuSkipTwin();
             StandaloneView v = new StandaloneView(DOC, W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            assertTrue(twin.step(v, t), "初めのフレームは描く");
            assertFalse(twin.step(v, t += 16), "何も変わらないフレームは描かない");
            double[][] moves = {{40, 20}, {40, 21}, {-1, -1}, {60, 200}};
            for (double[] m : moves) {
                if (m[0] < 0) v.mouseMove(-50, -50, twin.sessionId()); else v.mouseMove(m[0], m[1], twin.sessionId());
                for (int i = 0; i < 30; i++) twin.step(v, t += 40); // 遷移・滑らかなスクロールが落ち着くまで
            }
            v.scroll(0, -2, twin.sessionId());
            for (int i = 0; i < 60; i++) twin.step(v, t += 16);
            assertFalse(twin.step(v, t += 16), "落ち着いたら描かない");

            twin.clearColor(0xFF203040);
            assertTrue(twin.step(v, t += 16), "塗りの色が変わったら描く");
            assertFalse(twin.step(v, t += 16));
            twin.g.invalidate();
            assertTrue(twin.step(v, t += 16), "ホストが前の画素を捨てたら描く");
            assertFalse(twin.step(v, t += 16));

            assertEquals("", twin.report.toString());
            assertTrue(twin.skipped >= 10, "描かなかったフレームが少ない: " + twin.skipped + "/" + twin.frames);
        }
    }

    /** ホストを真似た描画器: 描く前に GPU の描画器へ聞き、true のときだけ描く。毎回、持っている画素を新しい GPU の描画器の画素と比べる。 */
    static final class GpuSkipTwin implements RenderBackend {
        final LwjglGlRenderBackend g = new LwjglGlRenderBackend(W, H);
        final StringBuilder report = new StringBuilder();
        private int clearArgb;
        int frames, skipped;
        private boolean lastNeed;

        boolean step(StandaloneView v, long now) {
            v.frame(now, this, true);
            return lastNeed;
        }

        @Override
        public void render(com.nishiyu.enderframelib.core.paint.PreparedFrame frame) {
            com.nishiyu.enderframelib.core.paint.RenderFrame f = frame.frame();
            com.nishiyu.enderframelib.core.paint.PreparedFrame mine = g.prepare(f);
            boolean need = lastNeed = mine.needsRepaint();
            if (need) g.render(mine);
            else skipped++;
            int[] got = g.pixmap().pixels().clone();
            try (LwjglGlRenderBackend fresh = new LwjglGlRenderBackend(W, H)) {
                fresh.clearColor(clearArgb);
                fresh.render(f);
                int[] want = fresh.pixmap().pixels();
                int diff = 0;
                for (int i = 0; i < want.length; i++) if (want[i] != got[i]) diff++;
                if (diff > 0) report.append("\n  frame ").append(frames).append(need ? " (描いた)" : " (描かなかった)").append(": ")
                        .append(diff).append(" 画素が新しい描画器と違う");
            }
            frames++;
        }

        void clearColor(int argb) { clearArgb = argb; g.clearColor(argb); }

        @Override public Pixmap pixmap() { return g.pixmap(); }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return g.sessionId(); }
        @Override public void close() { g.close(); }
    }
}
