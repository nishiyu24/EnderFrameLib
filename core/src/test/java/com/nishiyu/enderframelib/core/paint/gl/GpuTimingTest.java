package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** GPU のタイマークエリ。GL を作れない環境・タイマークエリの無い GL ではスキップする。 */
class GpuTimingTest {

    private static final String HTML = "<html><body style='margin:0'>"
            + "<div style='width:200px;height:100px;background:#36c;border-radius:12px;box-shadow:0 4px 12px #000'></div>"
            + "</body></html>";

    private static LwjglGlRenderBackend open() {
        try {
            return new LwjglGlRenderBackend(256, 128, 1f);
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
            return null;
        }
    }

    @Test
    void timingIsOffByDefaultAndTouchesNoQueries() {
        try (LwjglGlRenderBackend gl = open()) {
            assertEquals(-1.0, gl.lastGpuMillis());
            StandaloneView view = new StandaloneView(HTML, 256, 128);
            for (int i = 0; i < 5; i++) view.frame(1000 + i * 16L, gl, true);
            assertEquals(-1.0, gl.lastGpuMillis());
        }
    }

    @Test
    void enabledTimingDeliversResultsWithoutChangingPixels() {
        try (LwjglGlRenderBackend plain = open(); LwjglGlRenderBackend timed = open()) {
            Assumptions.assumeTrue(timed.enableGpuTiming(), "タイマークエリ非対応");
            assertTrue(timed.enableGpuTiming(), "2 回呼んでもよい");
            StageProfiler.enable(true);
            StageProfiler.reset();
            try {
                StandaloneView a = new StandaloneView(HTML, 256, 128);
                StandaloneView b = new StandaloneView(HTML, 256, 128);
                for (int i = 0; i < 40; i++) {
                    a.frame(1000 + i * 16L, plain, true);
                    b.frame(1000 + i * 16L, timed, true);
                }
                assertArrayEquals(plain.pixmap().pixels(), timed.pixmap().pixels(), "計測は絵を変えない");
                assertTrue(timed.lastGpuMillis() >= 0, "40 フレーム描けば結果が届く");
                assertTrue(StageProfiler.snapshot().stream().anyMatch(s -> s.stage() == PipelineStage.GPU));
            } finally {
                StageProfiler.enable(false);
                StageProfiler.reset();
            }
        }
    }
}
