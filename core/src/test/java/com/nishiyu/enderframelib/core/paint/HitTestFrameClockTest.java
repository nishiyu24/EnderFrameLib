package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

/**
 * T2 で見つけた不具合の回帰テスト: ヒットテストは、動いている項目の姿勢を描いたフレームと同じ時刻で評価する。
 * 以前は描画がフレームの時刻（{@code frame(now)}）で、ヒットテストが壁時計（{@code System.currentTimeMillis()}）で
 * アニメーションを評価していたので、ホストのフレームの時刻が壁時計と違うと、見えている位置とクリックが当たる位置がずれ、
 * 同じ入力でも実行ごとに結果が変わった（{@code RealDocumentFramesTest} の voidwave で、表示リストと画素は同じなのに
 * ヒットテストだけが実行ごとに違った）。
 */
class HitTestFrameClockTest {

    private static final String DOC = "<html><head><style>"
            + "body{margin:0}"
            + "@keyframes slide{from{transform:translateX(0)}to{transform:translateX(200px)}}"
            + "#m{position:absolute;left:0;top:0;width:50px;height:50px;border:0;padding:0;background:#c33;"
            + "animation:slide 1000ms linear infinite}"
            + "</style></head><body><button id='m'></button></body></html>";

    private static String hitId(StandaloneView v, double x, double y) {
        var hit = v.ui().hitTest(x, y);
        return hit == null ? null : hit.element().baseId();
    }

    @Test
    void animatedElementIsHitWhereItWasDrawnAtTheFrameTime() {
        // フレームの時刻は壁時計から遠い値（ゲームの tick・テストの固定の時刻など）
        try (StandaloneView v = new StandaloneView(DOC, 400, 100, FontResolver.FALLBACK);
             var be = new com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend(400, 100, 1f)) {
            long t = 10_000;
            v.frame(t, be, true);
            v.frame(t += 250, be, true); // 1/4 周: translateX(50px) → 箱は x = 50..100 に描かれている
            String moved = hitId(v, 80, 25), origin = hitId(v, 20, 25);
            assertEquals("m", moved, "描いた位置（x = 50..100）に当たる");
            assertNotEquals("m", origin, "動く前の位置（x = 0..50）には当たらない");
            // 壁時計が進んでも、次のフレームを描くまでは同じ結果（見えている絵は変わっていない）
            try { Thread.sleep(120); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            assertEquals(moved, hitId(v, 80, 25));
            assertEquals(origin, hitId(v, 20, 25));
            v.frame(t += 250, be, true); // 1/2 周: x = 100..150
            assertEquals("m", hitId(v, 120, 25));
            assertNotEquals("m", hitId(v, 80, 25));
        }
    }
}
