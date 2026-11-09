package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ShadowShape;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase A-2 第2弾: 外側・内側のぼかし影の GPU 試行を {@link GpuAttempt} へ分けた後の挙動。 */
class ShadowAttemptTest {

    private static final class ShadowSink implements GpuSink {
        final boolean accept;
        int outer, inset;
        float[] hole, hrx, hry;
        float lastSigma;

        ShadowSink(boolean accept) { this.accept = accept; }

        @Override
        public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     int argb, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                         float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                         int[] colors, float[] widths, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean outerShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float sx0, float sy0, float sx1,
                                   float sy1, float[] srx, float[] sry, float sigma, float[] hole, float[] hrx, float[] hry,
                                   int argb, GpuClip[] clips, float opacity) {
            outer++; this.hole = hole; this.hrx = hrx; this.hry = hry; lastSigma = sigma;
            return accept;
        }

        @Override
        public boolean insetShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float[] rx, float[] ry, float sigma,
                                   float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
            inset++; this.hole = hole; this.hrx = hrx; this.hry = hry; lastSigma = sigma;
            return accept;
        }
    }

    private static final float[] Z = new float[4];
    private static final Clip OPEN = new Clip(List.of(), 0, 0, 10, 10, 0, 0, 10, 10);
    private static final ShadowShape SHAPE = new ShadowShape(false, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

    @Test
    void adapterPassesTheShapeAsRectAndPerCornerRadii() {
        ShadowSink s = new ShadowSink(true);
        assertTrue(GpuAttempt.outerShadow(s, Matrix2D.IDENTITY, 0, 0, 9, 9, 1, 1, 8, 8, Z, Z, 2f, SHAPE, 0xff000000, OPEN, 1f));
        assertArrayEquals(new float[]{1, 2, 3, 4}, s.hole);
        assertArrayEquals(new float[]{5, 6, 7, 8}, s.hrx);
        assertArrayEquals(new float[]{9, 10, 11, 12}, s.hry);
        assertTrue(GpuAttempt.insetShadow(s, Matrix2D.IDENTITY, 0, 0, 9, 9, Z, Z, 2f, SHAPE, 0xff000000, OPEN, 1f));
        assertArrayEquals(new float[]{1, 2, 3, 4}, s.hole);
        assertEquals(1, s.inset);
    }

    @Test
    void outerShadowWithoutAHoleSendsNulls() {
        ShadowSink s = new ShadowSink(true);
        assertTrue(GpuAttempt.outerShadow(s, Matrix2D.IDENTITY, 0, 0, 9, 9, 1, 1, 8, 8, Z, Z, 2f, null, 0xff000000, OPEN, 1f));
        assertNull(s.hole); assertNull(s.hrx); assertNull(s.hry);
    }

    @Test
    void noSinkRefusalOrTooManyClipsMeansFalse() {
        assertFalse(GpuAttempt.outerShadow(null, Matrix2D.IDENTITY, 0, 0, 9, 9, 1, 1, 8, 8, Z, Z, 2f, null, 1, OPEN, 1f));
        assertFalse(GpuAttempt.insetShadow(null, Matrix2D.IDENTITY, 0, 0, 9, 9, Z, Z, 2f, SHAPE, 1, OPEN, 1f));
        ShadowSink no = new ShadowSink(false);
        assertFalse(GpuAttempt.outerShadow(no, Matrix2D.IDENTITY, 0, 0, 9, 9, 1, 1, 8, 8, Z, Z, 2f, null, 1, OPEN, 1f));
        assertFalse(GpuAttempt.insetShadow(no, Matrix2D.IDENTITY, 0, 0, 9, 9, Z, Z, 2f, SHAPE, 1, OPEN, 1f));
        assertEquals(1, no.outer);
        assertEquals(1, no.inset);
        ClipPart p = new ClipPart(Matrix2D.IDENTITY, 1f, 0, 0, 10, 10, Z, Z, 0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 10, 10);
        Clip many = new Clip(Collections.nCopies(GpuSink.MAX_CLIPS + 1, p), 0, 0, 10, 10, 0, 0, 10, 10);
        ShadowSink yes = new ShadowSink(true);
        assertFalse(GpuAttempt.outerShadow(yes, Matrix2D.IDENTITY, 0, 0, 9, 9, 1, 1, 8, 8, Z, Z, 2f, null, 1, many, 1f));
        assertFalse(GpuAttempt.insetShadow(yes, Matrix2D.IDENTITY, 0, 0, 9, 9, Z, Z, 2f, SHAPE, 1, many, 1f));
        assertEquals(0, yes.outer + yes.inset);
    }

    private static final String OUTER = "<html><body style='margin:0;padding:20px'><div style='width:60px;height:30px;background:#fff;"
            + "border-radius:8px;box-shadow:4px 6px 10px rgba(0,0,0,0.6)'></div></body></html>";
    private static final String INSET = "<html><body style='margin:0'><div style='width:60px;height:40px;background:#fff;"
            + "border-radius:8px;box-shadow:inset 3px 3px 8px rgba(0,0,0,0.7)'></div></body></html>";

    private static int[] cpu(String html, GpuSink sink) {
        List<DisplayItem> items = SoftwareRendering.displayList(html, 120, 90);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(120, 90, 1f)) {
            if (sink == null) b.render(items, 0); else b.renderOnto(items, 0, sink);
            return b.pixmap().pixels().clone();
        }
    }

    @Test
    void refusedShadowsFallBackToTheSamePixelsAsNoSink() {
        for (String html : new String[]{OUTER, INSET}) {
            ShadowSink s = new ShadowSink(false);
            int[] with = cpu(html, s);
            assertTrue(s.outer + s.inset > 0, "影が GPU に届く");
            assertArrayEquals(cpu(html, null), with);
        }
    }

    @Test
    void acceptedShadowsAreNotPaintedByTheCpuShadowPath() {
        ShadowSink out = new ShadowSink(true);
        cpu(OUTER, out);
        assertEquals(1, out.outer);
        assertEquals(0, out.inset);
        assertEquals(10f / 2f, out.lastSigma, 1e-4f, "標準偏差 = ぼかし半径 / 2");
        ShadowSink in = new ShadowSink(true);
        cpu(INSET, in);
        assertEquals(1, in.inset);
        assertEquals(0, in.outer);
        assertEquals(8f / 2f, in.lastSigma, 1e-4f);
    }

    @Test
    void shadowGpuCallsStayOutOfTheBackend() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!java.nio.file.Files.exists(src)) src = java.nio.file.Path.of("core").resolve(src);
        String t = java.nio.file.Files.readString(src);
        assertFalse(t.contains("sink.outerShadow("), "外側の影の GPU 試行は GpuAttempt 経由");
        assertFalse(t.contains("sink.insetShadow("), "内側の影の GPU 試行は GpuAttempt 経由");
    }
}
