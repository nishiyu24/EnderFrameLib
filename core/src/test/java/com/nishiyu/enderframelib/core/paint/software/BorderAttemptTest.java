package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.BorderDashPattern;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Phase A-2 第3弾: 実線・破線・円の枠線の GPU 試行を {@link GpuAttempt} へ分けた後の挙動。 */
class BorderAttemptTest {

    private static final class BorderSink implements GpuSink {
        final boolean accept;
        int solid, dashed, circle;
        int[] solidColors, circleColors;
        float dashPeriod, dashThickness;
        float circleOuter, circleInner;

        BorderSink(boolean accept) { this.accept = accept; }

        @Override
        public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     int argb, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                         float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                         int[] colors, float[] widths, GpuClip[] clips, float opacity) {
            solid++; solidColors = colors;
            return accept;
        }

        @Override
        public boolean strokeDashedBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                          float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                          int[] colors, float[] widths, float bx, float by, float cxm, float cym, float[] pcx,
                                          float[] pcy, float period, float dash, boolean round, float thickness,
                                          GpuClip[] clips, float opacity) {
            dashed++; dashPeriod = period; dashThickness = thickness;
            return accept;
        }

        @Override
        public boolean strokeCircleBorder(Matrix2D pose, float cx, float cy, float outerR, float innerR, float bx0, float by0,
                                          float bx1, float by1, int[] colors, GpuClip[] clips, float opacity) {
            circle++; circleColors = colors; circleOuter = outerR; circleInner = innerR;
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
    void circleAdapterPassesColorsAndRadii() {
        BorderSink s = new BorderSink(true);
        int[] c = {1, 2, 3, 4};
        assertTrue(GpuAttempt.circleBorder(s, Matrix2D.IDENTITY, 5, 5, 4, 2, 0, 0, 10, 10, c, OPEN, 1f));
        assertArrayEquals(c, s.circleColors);
        assertEquals(4f, s.circleOuter);
        assertEquals(2f, s.circleInner);
    }

    @Test
    void noSinkRefusalOrTooManyClipsMeansFalse() {
        int[] c = {1, 2, 3, 4};
        assertFalse(GpuAttempt.circleBorder(null, Matrix2D.IDENTITY, 5, 5, 4, 2, 0, 0, 10, 10, c, OPEN, 1f));
        BorderSink no = new BorderSink(false);
        assertFalse(GpuAttempt.circleBorder(no, Matrix2D.IDENTITY, 5, 5, 4, 2, 0, 0, 10, 10, c, OPEN, 1f));
        assertEquals(1, no.circle);
        BorderSink yes = new BorderSink(true);
        assertFalse(GpuAttempt.circleBorder(yes, Matrix2D.IDENTITY, 5, 5, 4, 2, 0, 0, 10, 10, c, manyClips(), 1f));
        assertEquals(0, yes.circle, "クリップが多すぎるときは GPU に聞かない");
    }

    private static final String SOLID = "<html><body style='margin:0;padding:10px'><div style='width:60px;height:30px;background:#fff;"
            + "border:4px solid #c33;border-radius:8px'></div></body></html>";
    private static final String DASHED = "<html><body style='margin:0;padding:10px'><div style='width:60px;height:30px;"
            + "border:3px dashed #36c;border-radius:8px'></div></body></html>";
    private static final String CIRCLE = "<html><body style='margin:0;padding:10px'><div style='width:40px;height:40px;"
            + "border:5px solid #393;border-radius:50%'></div></body></html>";

    private static int[] cpu(String html, GpuSink sink) {
        List<DisplayItem> items = SoftwareRendering.displayList(html, 100, 80);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(100, 80, 1f)) {
            if (sink == null) b.render(items, 0); else b.renderOnto(items, 0, sink);
            return b.pixmap().pixels().clone();
        }
    }

    @Test
    void solidBorderGoesToTheSolidPathAndDashedToTheDashedPath() {
        BorderSink s = new BorderSink(true);
        cpu(SOLID, s);
        assertEquals(1, s.solid);
        assertEquals(0, s.dashed);
        assertEquals(0xffcc3333, s.solidColors[0], "非乗算の色が渡る");
        BorderSink d = new BorderSink(true);
        cpu(DASHED, d);
        assertEquals(1, d.dashed);
        assertEquals(0, d.solid);
        assertEquals(3f, d.dashThickness);
        assertTrue(d.dashPeriod > 0f);
    }

    @Test
    void refusedBordersFallBackToTheSamePixelsAsNoSink() {
        for (String html : new String[]{SOLID, DASHED, CIRCLE}) {
            BorderSink s = new BorderSink(false);
            int[] with = cpu(html, s);
            assertTrue(s.solid + s.dashed + s.circle > 0, "枠が GPU に届く: " + html);
            assertArrayEquals(cpu(html, null), with);
        }
    }

    @Test
    void acceptedBordersAreNotPaintedByTheCpu() {
        // 背景なしの枠だけの箱は、GPU が枠を受け取れば CPU は何も塗らない
        for (String html : new String[]{DASHED, CIRCLE}) {
            List<DisplayItem> items = SoftwareRendering.displayList(html, 100, 80);
            BorderSink s = new BorderSink(true);
            try (SoftwareRenderBackend b = new SoftwareRenderBackend(100, 80, 1f)) {
                b.renderOnto(items, 0, s);
                assertNull(b.dirtyBounds(), html);
            }
        }
    }

    @Test
    void borderGpuCallsStayOutOfTheBackend() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"sink.strokeRoundBorder(", "sink.strokeDashedBorder(", "sink.strokeCircleBorder("}) {
            assertFalse(t.contains(bad), bad + " は GpuAttempt 経由");
        }
    }
}
