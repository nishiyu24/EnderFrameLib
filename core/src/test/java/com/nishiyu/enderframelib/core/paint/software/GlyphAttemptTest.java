package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Phase A-2 第4弾: 字形（通常・縁取り・変形）の GPU 試行を {@link GpuAttempt} へ分けた後の挙動。 */
class GlyphAttemptTest {

    private static final class GlyphSink implements GpuSink {
        final boolean accept;
        int plain, stroke, transformed;
        int bx, by;
        float half, bx0, by0, bx1, by1;

        GlyphSink(boolean accept) { this.accept = accept; }

        @Override
        public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     int argb, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                         float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                         int[] colors, float[] widths, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean drawGlyph(GlyphBitmap g, int bx, int by, int argb, GpuClip[] clips, float opacity) {
            plain++; this.bx = bx; this.by = by;
            return accept;
        }

        @Override
        public boolean drawGlyphStroke(GlyphBitmap sdf, int bx, int by, float half, int argb, GpuClip[] clips, float opacity) {
            stroke++; this.bx = bx; this.by = by; this.half = half;
            return accept;
        }

        @Override
        public boolean drawGlyphTransformed(Matrix2D pose, GlyphBitmap g, float ox, float oy, float s,
                                            float bx0, float by0, float bx1, float by1, int argb, GpuClip[] clips, float opacity) {
            transformed++; this.bx0 = bx0; this.by0 = by0; this.bx1 = bx1; this.by1 = by1;
            return accept;
        }
    }

    private static final Clip OPEN = new Clip(List.of(), 0, 0, 10, 10, 0, 0, 10, 10);
    private static final GlyphBitmap G = new GlyphBitmap(6, 8, 2, 7, new byte[48]);

    private static Clip manyClips() {
        float[] z = new float[4];
        ClipPart p = new ClipPart(Matrix2D.IDENTITY, 1f, 0, 0, 10, 10, z, z, 0, 0, 10, 10, 0, 0, 10, 10, 0, 0, 10, 10);
        return new Clip(Collections.nCopies(GpuSink.MAX_CLIPS + 1, p), 0, 0, 10, 10, 0, 0, 10, 10);
    }

    @Test
    void originIsRoundedOntoTheDevicePixelGrid() {
        GlyphSink s = new GlyphSink(true);
        assertTrue(GpuAttempt.glyph(s, G, 10.4f, 20.6f, 0xff000000, OPEN, 1f));
        assertEquals(Math.round(10.4f + 2), s.bx);
        assertEquals(Math.round(20.6f - 7), s.by);
        assertTrue(GpuAttempt.glyphStroke(s, G, 10.4f, 20.6f, 1.5f, 0xff000000, OPEN, 1f));
        assertEquals(Math.round(10.4f + 2), s.bx);
        assertEquals(Math.round(20.6f - 7), s.by);
        assertEquals(1.5f, s.half);
    }

    @Test
    void transformedRangeIsTheLocalBoundsGrownByOne() {
        GlyphSink s = new GlyphSink(true);
        assertTrue(GpuAttempt.glyphTransformed(s, G, 10f, 20f, 2f, Matrix2D.IDENTITY, 0xff000000, OPEN, 1f));
        assertEquals(10f + 2 / 2f - 1, s.bx0);
        assertEquals(20f - 7 / 2f - 1, s.by0);
        assertEquals(10f + (2 + 6) / 2f + 1, s.bx1);
        assertEquals(20f + (8 - 7) / 2f + 1, s.by1);
    }

    @Test
    void zeroOpacityCountsAsHandledWithoutAskingTheGpu() {
        GlyphSink s = new GlyphSink(false);
        assertTrue(GpuAttempt.glyph(s, G, 1, 1, 1, OPEN, 0f));
        assertTrue(GpuAttempt.glyphStroke(s, G, 1, 1, 1, 1, OPEN, 0f));
        assertTrue(GpuAttempt.glyphTransformed(s, G, 1, 1, 1, Matrix2D.IDENTITY, 1, OPEN, 0f));
        assertEquals(0, s.plain + s.stroke + s.transformed);
        assertFalse(GpuAttempt.glyph(null, G, 1, 1, 1, OPEN, 0f), "sink が無ければ不透明度に関わらず CPU");
    }

    @Test
    void noSinkRefusalOrTooManyClipsMeansFalse() {
        assertFalse(GpuAttempt.glyph(null, G, 1, 1, 1, OPEN, 1f));
        assertFalse(GpuAttempt.glyphStroke(null, G, 1, 1, 1, 1, OPEN, 1f));
        assertFalse(GpuAttempt.glyphTransformed(null, G, 1, 1, 1, Matrix2D.IDENTITY, 1, OPEN, 1f));
        GlyphSink no = new GlyphSink(false);
        assertFalse(GpuAttempt.glyph(no, G, 1, 1, 1, OPEN, 1f));
        assertFalse(GpuAttempt.glyphStroke(no, G, 1, 1, 1, 1, OPEN, 1f));
        assertFalse(GpuAttempt.glyphTransformed(no, G, 1, 1, 1, Matrix2D.IDENTITY, 1, OPEN, 1f));
        assertEquals(1, no.plain);
        assertEquals(1, no.stroke);
        assertEquals(1, no.transformed);
        GlyphSink yes = new GlyphSink(true);
        assertFalse(GpuAttempt.glyph(yes, G, 1, 1, 1, manyClips(), 1f));
        assertFalse(GpuAttempt.glyphStroke(yes, G, 1, 1, 1, 1, manyClips(), 1f));
        assertFalse(GpuAttempt.glyphTransformed(yes, G, 1, 1, 1, Matrix2D.IDENTITY, 1, manyClips(), 1f));
        assertEquals(0, yes.plain + yes.stroke + yes.transformed);
    }

    private static String html(String textStyle) {
        return "<html><body style=\"margin:0\"><div style=\"padding:10px;font:bold 36px sans-serif;" + textStyle + "\">HIl</div></body></html>";
    }

    private static int[] cpu(String html, GpuSink sink) {
        List<DisplayItem> items = SoftwareRendering.displayList(html, 140, 90);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(140, 90, 1f)) {
            if (sink == null) b.render(items, 0); else b.renderOnto(items, 0, sink);
            return b.pixmap().pixels().clone();
        }
    }

    private static final String[] STYLES = {"color:#c00", "color:#c00;-webkit-text-stroke:2px #00f", "color:#c00;transform:rotate(12deg)"};

    @Test
    void textReachesTheMatchingGpuPathAndIsNotPaintedByTheCpuWhenAccepted() {
        Assumptions.assumeTrue(SystemFonts.system().resolve(null) instanceof OutlineFontFace, "no system fonts");
        GlyphSink plain = new GlyphSink(true);
        List<DisplayItem> items = SoftwareRendering.displayList(html(STYLES[0]), 140, 90);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(140, 90, 1f)) {
            b.renderOnto(items, 0, plain);
            assertNull(b.dirtyBounds());
        }
        assertTrue(plain.plain >= 3, "HIl の字形が届く");
        GlyphSink st = new GlyphSink(true);
        cpu(html(STYLES[1]), st);
        assertTrue(st.stroke > 0);
        GlyphSink tr = new GlyphSink(true);
        cpu(html(STYLES[2]), tr);
        assertTrue(tr.transformed > 0);
    }

    @Test
    void refusedGlyphsFallBackToTheSamePixelsAsNoSink() {
        Assumptions.assumeTrue(SystemFonts.system().resolve(null) instanceof OutlineFontFace, "no system fonts");
        for (String css : STYLES) {
            GlyphSink s = new GlyphSink(false);
            int[] with = cpu(html(css), s);
            assertTrue(s.plain + s.stroke + s.transformed > 0, css);
            assertArrayEquals(cpu(html(css), null), with, css);
        }
    }

    @Test
    void glyphGpuCallsStayOutOfTheBackend() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"sink.drawGlyph(", "sink.drawGlyphStroke(", "sink.drawGlyphTransformed(",
                "boolean strokeOnGpu(", "boolean glyphOnGpu(", "boolean glyphTransformedOnGpu("}) {
            assertFalse(t.contains(bad), bad + " は GpuAttempt へ移した");
        }
    }
}
