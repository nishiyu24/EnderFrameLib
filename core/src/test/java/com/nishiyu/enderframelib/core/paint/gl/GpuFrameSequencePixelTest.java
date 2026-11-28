package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * GPU の描画器はフレームをまたいで出力の置き場（{@code RectStream} の slot の上書き・クリップの表・保持したスクロール層・字形の表）を持つ。
 * 使い続けた描画器の毎フレームの画素が、そのフレームの入力（列・平行移動・シーン）を<b>新しい描画器</b>で描いた画素と 1 画素も違わないこと
 * （§6-27 の旧「項目の再生」が補間中の色・上書きされたクリップの slot・CPU 層との順序を見落とした不具合の種類を、再生を消した M3b-4b-4a の後も
 * 見張る）。文書はクリップ（overflow）・影・角丸の枠・グラデーション・背景画像（CPU に回る）・不透明度・せん断（CPU に回る）・入力欄・
 * スクロールを含み、GPU と CPU の描画が混ざる。新しい描画器は補間の状態を引き継がないので、時刻で変わる遷移は文書に置かない。
 */
class GpuFrameSequencePixelTest {
    private static final int W = 420, H = 340;

    private static final String DOC = "<html><head><style>"
            + "body{margin:6px;font:13px sans-serif;color:#223;background:#f4f4f8}"
            + ".card{padding:6px;margin:4px 0;background:#fff;border:1px solid #bbb;border-radius:6px;box-shadow:0 2px 5px rgba(0,0,0,.25)}"
            + ".card:hover{background:#e8f0ff;color:#036}"
            + ".btn{display:inline-block;padding:4px 10px;background:#36c;color:#fff;border-radius:4px}"
            + ".btn:hover{background:#c33}"
            + ".grad{height:20px;background:linear-gradient(90deg,#f90,#09f)}"
            + ".clip{width:160px;height:36px;overflow:hidden;background:#ddd;border-radius:8px}"
            + ".clip div{width:300px;height:20px;background:#8a8;margin-left:-20px}"
            + ".half{opacity:.5;background:#c60;height:18px}"
            + ".img{height:22px;background:url('data:image/svg+xml;utf8,<svg xmlns=%22http://www.w3.org/2000/svg%22 width=%224%22 height=%224%22><rect width=%222%22 height=%222%22 fill=%22%23888%22/></svg>')}"
            + ".over{background:#fc0;height:24px;margin-top:0;position:relative;color:#000}"
            + ".skew{transform:skewX(18deg);background:#0a8;height:30px;margin-bottom:-20px;color:#fff}"
            + "#scroller{height:70px;overflow-y:auto;background:#eef}"
            + "</style></head><body>"
            + "<div class='card'>Card one with some text that is long enough to wrap onto a second line of the card</div>"
            + "<div class='skew'>skewed (drawn on the CPU)</div><div class='over'>plain box over the skewed one</div>"
            + "<div class='card'><span class='btn'>Button A</span> <span class='btn'>Button B</span></div>"
            + "<div class='grad'></div><div class='clip'><div>clipped content inside</div></div>"
            + "<div class='half'>half opacity</div><div class='img'></div>"
            + "<input type='text' value='field'><div id='scroller'>"
            + "<p>row 1</p><p>row 2</p><p>row 3</p><p>row 4</p><p>row 5</p><p>row 6</p><p>row 7</p><p>row 8</p></div>"
            + "<div class='skew' style='margin-top:6px'>skewed again</div><div class='card'>Card two</div></body></html>";

    private static void requireGl() {
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(8, 8)) {
            // 作れれば良い
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
    }

    @Test
    void persistentBackendMatchesAFreshBackendOnEveryFrame() {
        requireGl();
        int frames = 0;
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(W, H);
             StandaloneView v = new StandaloneView(DOC, W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            double[][] moves = {{-1, -1}, {40, 30}, {40, 31}, {200, 80}, {60, 70}, {60, 70}, {300, 200}, {20, 330}, {-1, -1}, {100, 50},
                    {120, 260}, {120, 262}};
            int step = 0;
            for (double[] m : moves) {
                if (m[0] < 0) v.mouseMove(-50, -50, "s"); else v.mouseMove(m[0], m[1], "s");
                if (++step == 11) v.scroll(0, -2, "s"); // 内側のコンテナのスクロール（保持したスクロール層の平行移動）
                for (int i = 0; i < 2; i++) { // 変わったフレームと、何も変わらない続きのフレーム
                    v.frame(t += 40, g, true);
                    int[] got = g.pixmap().pixels().clone();
                    try (LwjglGlRenderBackend fresh = new LwjglGlRenderBackend(W, H)) {
                        fresh.render(new com.nishiyu.enderframelib.core.paint.RenderFrame(v.ui().renderCache(), java.util.List.of(), v.ui().paintOffsets(), v.ui().paintSource(), t));
                        assertArrayEquals(fresh.pixmap().pixels(), got, "フレーム " + frames + " の画素が新しい描画器と違う");
                    }
                    frames++;
                }
            }
        }
        assertTrue(frames >= 20, "比べたフレームが少ない: " + frames);
    }
}
