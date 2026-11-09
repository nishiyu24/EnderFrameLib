package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.TextureRef;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** Phase A-2 第5弾: 画像・外部テクスチャの GPU 試行を {@link GpuAttempt} へ分けた後の挙動。 */
class ImageAttemptTest {

    private static final class ImageSink implements GpuSink {
        final boolean accept;
        int images, textures;
        Pixmap image;
        float bx0, by0, bx1, by1, px0, py0, sx, sy;
        int taps;
        long handle;
        int texW, texH;
        float tPx0, tPy0, tSx, tSy;

        ImageSink(boolean accept) { this.accept = accept; }

        @Override
        public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     int argb, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                         float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                         int[] colors, float[] widths, GpuClip[] clips, float opacity) { return false; }

        @Override
        public boolean drawImage(Matrix2D pose, Pixmap img, float bx0, float by0, float bx1, float by1, float px0, float py0,
                                 float sx, float sy, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                 int taps, float stepX, float stepY, GpuClip[] clips, float opacity) {
            images++; image = img; this.bx0 = bx0; this.by0 = by0; this.bx1 = bx1; this.by1 = by1;
            this.px0 = px0; this.py0 = py0; this.sx = sx; this.sy = sy; this.taps = taps;
            return accept;
        }

        @Override
        public boolean drawTexture(Matrix2D pose, long handle, int imgW, int imgH, float bx0, float by0, float bx1, float by1,
                                   float px0, float py0, float sx, float sy, float x0, float y0, float x1, float y1,
                                   float[] rx, float[] ry, GpuClip[] clips, float opacity) {
            textures++; this.handle = handle; texW = imgW; texH = imgH; tPx0 = px0; tPy0 = py0; tSx = sx; tSy = sy;
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
    void imageRangeIsTheBoxGrownByOnePixelClippedToThePlacedImage() {
        ImageSink s = new ImageSink(true);
        Pixmap pm = new Pixmap(4, 4);
        // 箱 (10,10) 40x30、画像は箱の中央 20x20（px0=20, py0=15）。拡大率 1 → 余白 1
        assertTrue(GpuAttempt.image(s, Matrix2D.IDENTITY, pm, 10, 10, 40, 30, 20, 15, 20, 20, 0.2f, 0.2f, Z, Z, 1, 1f, OPEN, 1f));
        assertSame(pm, s.image);
        assertEquals(20f, s.bx0);
        assertEquals(15f, s.by0);
        assertEquals(40f, s.bx1);
        assertEquals(35f, s.by1);
        assertEquals(1, s.taps);
        // 画像が箱より大きいときは箱 + 余白で切れる
        assertTrue(GpuAttempt.image(s, Matrix2D.IDENTITY, pm, 10, 10, 40, 30, 0, 0, 100, 100, 0.2f, 0.2f, Z, Z, 1, 2f, OPEN, 1f));
        assertEquals(10f - 0.5f, s.bx0);
        assertEquals(10f + 40 + 0.5f, s.bx1);
    }

    @Test
    void noSinkRefusalOrTooManyClipsMeansFalse() {
        Pixmap pm = new Pixmap(2, 2);
        assertFalse(GpuAttempt.image(null, Matrix2D.IDENTITY, pm, 0, 0, 5, 5, 0, 0, 5, 5, 1, 1, Z, Z, 1, 1f, OPEN, 1f));
        ImageSink no = new ImageSink(false);
        assertFalse(GpuAttempt.image(no, Matrix2D.IDENTITY, pm, 0, 0, 5, 5, 0, 0, 5, 5, 1, 1, Z, Z, 1, 1f, OPEN, 1f));
        assertEquals(1, no.images);
        ImageSink yes = new ImageSink(true);
        assertFalse(GpuAttempt.image(yes, Matrix2D.IDENTITY, pm, 0, 0, 5, 5, 0, 0, 5, 5, 1, 1, Z, Z, 1, 1f, manyClips(), 1f));
        assertEquals(0, yes.images);
    }

    private static String pngDataUri(int w, int h, int argb) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, argb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static String html(String src) {
        return "<html><body style=\"margin:0\"><img style=\"width:40px;height:30px\" src=\"" + src + "\"></body></html>";
    }

    private static int[] cpu(String html, GpuSink sink) {
        List<DisplayItem> items = SoftwareRendering.displayList(html, 60, 40);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(60, 40, 1f)) {
            if (sink == null) b.render(items, 0); else b.renderOnto(items, 0, sink);
            return b.pixmap().pixels().clone();
        }
    }

    @Test
    void decodedImageReachesTheGpuAndRefusalFallsBackToIdenticalPixels() throws Exception {
        String uri = pngDataUri(4, 4, 0xFF336699);
        ImageSink acc = new ImageSink(true);
        List<DisplayItem> items = SoftwareRendering.displayList(html(uri), 60, 40);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(60, 40, 1f)) {
            b.renderOnto(items, 0, acc);
            assertNull(b.dirtyBounds(), "GPU が受け取った画像は CPU が塗らない");
        }
        assertEquals(1, acc.images);
        assertEquals(4, acc.image.width());
        assertEquals(4f / 40f, acc.sx, 1e-5f, "局所 1 単位あたりの画像の画素数");
        ImageSink no = new ImageSink(false);
        int[] with = cpu(html(uri), no);
        assertEquals(1, no.images);
        assertArrayEquals(cpu(html(uri), null), with);
    }

    @Test
    void externalTextureMapsRegionAndFlipIntoTheDrawTextureCall() {
        ImageSink s = new ImageSink(true);
        TextureRef plain = new TextureRef(7, 16, 16, 0.5f, 0f, 1f, 1f, false);
        List<DisplayItem> items = SoftwareRendering.displayList(html(plain.src()), 60, 40);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(60, 40, 1f)) {
            b.renderOnto(items, 0, s);
            assertNull(b.dirtyBounds());
        }
        assertEquals(1, s.textures);
        assertEquals(0, s.images, "受け取られた外部テクスチャは画像として読み込まない");
        assertEquals(7L, s.handle);
        assertEquals(16, s.texW);
        assertEquals(16, s.texH);
        assertTrue(s.tSy > 0f);
        assertEquals(-8f / s.tSx, s.tPx0, 1e-4f, "範囲の左上（u0 × 幅 = 8 画素）を貼る位置の左上に合わせる");
        ImageSink f = new ImageSink(true);
        TextureRef flipped = new TextureRef(7, 16, 16, 0f, 0f, 1f, 1f, true);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(60, 40, 1f)) {
            b.renderOnto(SoftwareRendering.displayList(html(flipped.src()), 60, 40), 0, f);
        }
        assertTrue(f.tSy < 0f, "上下反転は sy が負");
        assertEquals(16f / f.tSy * -1f, f.tPy0 - 0f, 1e-3f, "反転では上端を行 height から数える");
    }

    @Test
    void refusedTextureFallsBackToTheImageResolution() {
        ImageSink s = new ImageSink(false);
        List<DisplayItem> items = SoftwareRendering.displayList(html(new TextureRef(9, 8, 8).src()), 60, 40);
        try (SoftwareRenderBackend b = new SoftwareRenderBackend(60, 40, 1f)) {
            b.renderOnto(items, 0, s);
        }
        assertEquals(1, s.textures, "まずテクスチャとして聞く");
    }

    @Test
    void imageGpuCallsStayOutOfTheBackend() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"sink.drawImage(", "sink.drawTexture(", "boolean drawExternalTexture("}) {
            assertFalse(t.contains(bad), bad + " は GpuAttempt へ移した");
        }
    }
}
