package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * R5: 項目単位の再生（直前のフレームと同じ入力・同じ slot 位置の項目は GPU 出力を計算せず前進させる）は、画素を 1 つも変えない。
 * 再生なしと同じ操作列（ホバーの移動・遷移の途中と確定・文字の変更・スクロール）で、毎フレームの画素が完全に一致することを確かめる。
 * 文書はクリップ（overflow）・影・角丸の枠・グラデーション・背景画像（CPU に回る）・不透明度・入力欄を含み、再生と CPU 描画が混ざる。
 */
class ItemReplayPixelTest {
    private static final int W = 420, H = 340;

    private static final String DOC = "<html><head><style>"
            + "body{margin:6px;font:13px sans-serif;color:#223;background:#f4f4f8}"
            + ".card{padding:6px;margin:4px 0;background:#fff;border:1px solid #bbb;border-radius:6px;box-shadow:0 2px 5px rgba(0,0,0,.25)}"
            + ".card:hover{background:#e8f0ff;color:#036}"
            + ".btn{display:inline-block;padding:4px 10px;background:#36c;color:#fff;border-radius:4px;transition:background-color 100ms}"
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

    @AfterEach
    void restore() {
        GlRectStream.replayEnabled = true;
    }

    private static void requireGl() {
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(8, 8)) {
            // 作れれば良い
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
    }

    /** 操作列を流し、毎フレームの画素を集める。{@code replay} が false なら再生を止める。 */
    private static List<int[]> run(boolean replay, long[] stats) {
        GlRectStream.replayEnabled = replay;
        List<int[]> out = new ArrayList<>();
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(W, H);
             StandaloneView v = new StandaloneView(DOC, W, H, FontResolver.FALLBACK)) {
            long t = 1000;
            double[][] moves = {{-1, -1}, {40, 30}, {40, 31}, {200, 80}, {60, 70}, {60, 70}, {300, 200}, {20, 330}, {-1, -1}, {100, 50}};
            for (double[] m : moves) {
                if (m[0] < 0) v.mouseMove(-50, -50, "s"); else v.mouseMove(m[0], m[1], "s");
                for (int i = 0; i < 4; i++) { // 遷移の途中・確定・何も変わらない続きのフレーム
                    v.frame(t += 40, g, true);
                    out.add(g.pixmap().pixels().clone());
                }
            }
            v.scroll(0, -2, "s");
            for (int i = 0; i < 3; i++) { v.frame(t += 40, g, true); out.add(g.pixmap().pixels().clone()); }
            if (stats != null) { stats[0] = g.rectTreeReplayedItems(); stats[1] = g.rectTreeReplayedShapes(); long[] rj = g.rectTreeRejects(); stats[2] = rj[0] + rj[1] + rj[2] + rj[3]; }
        }
        return out;
    }

    @Test
    void replayDoesNotChangeAnyPixelAcrossTheSequence() {
        requireGl();
        long[] st = new long[3];
        List<int[]> off = run(false, null);
        List<int[]> on = run(true, st);
        assertEquals(off.size(), on.size());
        for (int i = 0; i < off.size(); i++) {
            assertArrayEquals(off.get(i), on.get(i), "フレーム " + i + " の画素が再生なしと違う");
        }
        assertTrue(st[0] > 0 && st[1] > 0, "再生が実際に使われている（空の検証ではない）: items=" + st[0]);
        assertTrue(st[2] > 0, "CPU に回る図形（せん断）が混ざっている（再生と CPU 描画の順序づけを検証する）");
    }
}
