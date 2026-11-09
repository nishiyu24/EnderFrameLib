package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase A-2 第6弾（最終）: シェーダーグラデーションの GPU 試行を {@link GpuAttempt} へ分けた後の挙動。 */
class GradientAttemptTest {

    private static final class GradSink implements GpuSink {
        final boolean accept;
        int gradients;
        ShaderGradient last;
        float x1, y1;

        GradSink(boolean accept) { this.accept = accept; }

        @Override
        public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     int argb, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                         float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                         int[] colors, float[] widths, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean fillGradient(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                    ShaderGradient gradient, GpuClip[] clips, float opacity) {
            gradients++; last = gradient; this.x1 = x1; this.y1 = y1;
            return accept;
        }
    }

    private static final float[] Z = new float[4];
    private static final Clip OPEN = new Clip(List.of(), 0, 0, 10, 10, 0, 0, 10, 10);

    private static Clip manyClips() {
        ClipPart p = new ClipPart(Matrix2D.IDENTITY, 1f, 0, 0, 10, 10, Z, Z, 0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 10, 10);
        return new Clip(Collections.nCopies(GpuSink.MAX_CLIPS + 1, p), 0, 0, 10, 10, 0, 0, 10, 10);
    }

    @Test
    void noSinkRefusalOrTooManyClipsMeansFalse() {
        assertFalse(GpuAttempt.gradient(null, Matrix2D.IDENTITY, 0, 0, 5, 5, Z, Z, null, OPEN, 1f));
        GradSink no = new GradSink(false);
        assertFalse(GpuAttempt.gradient(no, Matrix2D.IDENTITY, 0, 0, 5, 5, Z, Z, null, OPEN, 1f));
        assertEquals(1, no.gradients);
        GradSink yes = new GradSink(true);
        assertFalse(GpuAttempt.gradient(yes, Matrix2D.IDENTITY, 0, 0, 5, 5, Z, Z, null, manyClips(), 1f));
        assertEquals(0, yes.gradients, "クリップが多すぎるときは GPU に聞かない");
        assertTrue(GpuAttempt.gradient(yes, Matrix2D.IDENTITY, 0, 0, 5, 7, Z, Z, null, OPEN, 1f));
        assertEquals(7f, yes.y1);
    }

    private static final String[] HTMLS = {
            "<html><body style=\"margin:0\"><div style=\"width:60px;height:30px;border-radius:8px;"
                    + "background:linear-gradient(90deg,#f00,#00f)\"></div></body></html>",
            "<html><body style=\"margin:0\"><div style=\"width:60px;height:30px;"
                    + "background:linear-gradient(to bottom,#ff0,#0c0 60%,#006)\"></div></body></html>",
            "<html><body style=\"margin:0\"><div style=\"width:50px;height:50px;border-radius:25px;"
                    + "background:radial-gradient(circle,#fff,#000)\"></div></body></html>"};

    private static int[] cpu(String html, GpuSink sink) {
        List<DisplayItem> items = SoftwareRendering.displayList(html, 80, 60);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(80, 60, 1f)) {
            if (sink == null) b.render(items, 0); else b.renderOnto(items, 0, sink);
            return b.pixmap().pixels().clone();
        }
    }

    @Test
    void acceptedGradientIsNotPaintedByTheCpu() {
        for (String html : HTMLS) {
            GradSink s = new GradSink(true);
            List<DisplayItem> items = SoftwareRendering.displayList(html, 80, 60);
            try (SoftwareRenderBackend b = new SoftwareRenderBackend(80, 60, 1f)) {
                b.renderOnto(items, 0, s);
                assertNull(b.dirtyBounds(), html);
                assertFalse(b.isRootAllocated(), html);
            }
            assertTrue(s.gradients >= 1, "区間ごとに GPU へ届く（3 色なら 2 区間）: " + html);
            assertNotNull(s.last, html);
        }
    }

    @Test
    void refusedGradientFallsBackToTheSamePixelsAsNoSink() {
        for (String html : HTMLS) {
            GradSink s = new GradSink(false);
            int[] with = cpu(html, s);
            assertTrue(s.gradients >= 1, html);
            int[] plain = cpu(html, null);
            assertTrue(Arrays.stream(plain).anyMatch(v -> v != 0), "何か塗られている");
            assertArrayEquals(plain, with, html);
        }
    }

    @Test
    void noSinkLeftInTheBackendOutsideLayerControl() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        assertFalse(t.contains("sink.fillGradient("), "グラデーションの GPU 試行は GpuAttempt 経由");
        // 図形ごとの GPU 試行はすべて GpuAttempt へ移した。残る sink 呼び出しは層（グループ・マスク・スクロール層）の制御だけ
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\bsink\\.(\\w+)\\(").matcher(t);
        while (m.find()) {
            String name = m.group(1);
            assertTrue(name.matches("(begin|end)(Group|Mask|ScrollLayer)|drawScrollLayer"), "図形の GPU 呼び出しが戻っている: sink." + name);
        }
    }
}
