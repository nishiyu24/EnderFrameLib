package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.StandaloneView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 止まっている画面の 1 フレームの判定コストが、要素数に比例しないことの物差し
 * （DIRTY_AND_COMPLEXITY_PLAN.md Phase 0 / §1.1）。
 * 絶対値は機械依存なので、要素数を 10 倍にしたときの時間の比（傾き）を出力する。現状は計測の記録が目的で、傾きでは落とさない。
 */
class IdleCostScalingTest {

    private static String listHtml(int rows) {
        StringBuilder sb = new StringBuilder("<html><head><style>li{height:20px}</style></head><body style='margin:0'><ul>");
        for (int i = 0; i < rows; i++) sb.append("<li>row ").append(i).append("</li>");
        return sb.append("</ul></body></html>").toString();
    }

    private static double idleNanos(int rows) {
        StandaloneView view = new StandaloneView(listHtml(rows), 400, 300);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(400, 300, 1f)) {
            view.frame(1000, cpu, true);
            view.frame(1016, cpu, false);
            // 暖機
            for (int i = 0; i < 200; i++) view.needsFrame();
            int iters = 2000;
            long best = Long.MAX_VALUE;
            for (int round = 0; round < 5; round++) {
                long t0 = System.nanoTime();
                boolean any = false;
                for (int i = 0; i < iters; i++) any |= view.needsFrame();
                long dt = System.nanoTime() - t0;
                assertFalse(any, "止まっている画面は needsFrame が false");
                best = Math.min(best, dt);
            }
            double needs = best / (double) iters;
            long t0 = System.nanoTime();
            for (int i = 0; i < iters; i++) view.frame(2000 + i * 16L, cpu, false);
            double frame = (System.nanoTime() - t0) / (double) iters;
            System.out.printf("IDLE rows=%d needsFrame=%.0f ns  frame=%.0f ns%n", rows, needs, frame);
            return needs;
        }
    }

    @Test
    void idleNeedsFrameScaling() {
        double a = idleNanos(500);
        double b = idleNanos(5000);
        double c = idleNanos(20000);
        System.out.printf("IDLE ratio 500->5000: x%.1f, 5000->20000: x%.1f (O(1) なら約 x1)%n", b / a, c / b);
    }
}
