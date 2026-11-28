package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 描画の経路の部分の描き直し（M3b-4b-4a。走査器 {@link SceneWalker} と {@link SceneDamage}）の外から見た正しさ: 実文書 9 本の全フレームで、
 * 同じ描画器を使い続けて前のフレームとのシーンの違いだけを塗り直した画素が、そのフレームを<b>新しい描画器で全面に描いた画素</b>と
 * 1 画素も違わないこと。範囲の求め方（{@code SceneDamageTest}）だけでなく、範囲をタイルに広げて塗り直す描画器の側
 * （タイルの消去・塗りの切り詰め・重ね描き・平行移動）までを含めて確かめる。
 */
class PartialRepaintRealDocumentsTest {

    @Test
    void partialRepaintMatchesAFreshFullRepaintOnEveryFrame() throws Exception {
        for (String doc : RealDocumentFramesTest.DOCS) check(doc);
    }

    private static void check(String doc) throws Exception {
        try (Twin twin = new Twin(RealDocumentFramesTest.W, RealDocumentFramesTest.H)) {
            RealDocumentFramesTest.scriptWithView(doc, twin, false, false, (v, f) -> { });
            long partial = twin.partialBackend.partialFrames();
            System.out.printf("[partial-repaint] %s frames=%d partial=%d%n", doc, twin.frames, partial);
            if (!twin.report.isEmpty()) fail(doc + ":" + twin.report);
            assertTrue(twin.frames >= 8, doc + ": 比べたフレームが少ない（" + twin.frames + "）");
            // 前提: 差分で描いたフレームがある（全面だけなら比べる意味が無い）。backdrop-filter・姿勢の補間を持つ文書は全面のまま
            if (List.of("showcase.html", "hud_test.html", "machine_ui.html").contains(doc)) {
                assertTrue(partial > 0, doc + ": 差分で描いたフレームが無い");
            }
        }
    }

    /**
     * 描くたびに、差分で描く描画器（使い続ける）と、全面で描く新しい描画器（同じ補間の状態の持ち主）の両方で描き、画素を比べる。
     * 全面の側はシーンを渡さない（シーンが無ければ全面）。
     */
    static final class Twin implements RenderBackend {
        final SoftwareRenderBackend partialBackend;
        final int w, h;
        final StringBuilder report = new StringBuilder();
        int frames;

        Twin(int w, int h) {
            this.w = w;
            this.h = h;
            this.partialBackend = new SoftwareRenderBackend(w, h);
        }

        @Override
        public void render(com.nishiyu.enderframelib.core.paint.PreparedFrame frame) {
            com.nishiyu.enderframelib.core.paint.RenderFrame f = frame.frame();
            long nowMs = f.nowMs();
            partialBackend.render(f);
            int[] got = partialBackend.pixmap().pixels();
            // 同じセッション（補間の状態）で全面に描く。同じ時刻・同じ列なので補間の状態は進まない
            try (SoftwareRenderBackend fresh = new SoftwareRenderBackend(w, h, 1f, partialBackend.sessionId())) {
                fresh.render(f); // 新しい描画器の最初のフレームは全面（列は描画器がシーンから組む）
                int[] want = fresh.pixmap().pixels();
                int diff = 0, first = -1;
                for (int i = 0; i < want.length; i++) {
                    if (want[i] != got[i]) {
                        if (diff++ == 0) first = i;
                    }
                }
                if (diff > 0) {
                    report.append("\n  frame ").append(frames).append(" t=").append(nowMs).append(": ").append(diff)
                            .append(" 画素が全面描画と違う（最初は ").append(first % w).append(',').append(first / w).append('）');
                }
            }
            frames++;
        }

        @Override public Pixmap pixmap() { return partialBackend.pixmap(); }
        @Override public float devicePixelRatio() { return 1f; }
        @Override public String sessionId() { return partialBackend.sessionId(); }
        @Override public void close() { partialBackend.close(); }
    }
}
