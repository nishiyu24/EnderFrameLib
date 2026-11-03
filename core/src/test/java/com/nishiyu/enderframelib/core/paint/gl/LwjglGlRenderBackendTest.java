package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.RenderBackend;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 純 LWJGL の GPU バックエンドを、同じ HTML を描いた CPU 版（{@link SoftwareRenderBackend}）と画素で突き合わせる。
 * GL 3.2 のコンテキストを作れない環境（ヘッドレスの CI など）では全部スキップする。
 */
class LwjglGlRenderBackendTest {

    private static final String BODY0 = "<html><body style='margin:0'>";
    private static final long T0 = 10_000L;

    private static LwjglGlRenderBackend gl;
    private static String unavailable;

    @BeforeAll
    static void probe() {
        try {
            gl = new LwjglGlRenderBackend(16, 16);
        } catch (Throwable t) {
            unavailable = t.toString();
        }
    }

    @AfterAll
    static void cleanup() {
        if (gl != null) gl.close();
    }

    private static void requireGl() {
        Assumptions.assumeTrue(gl != null, "OpenGL を使えない: " + unavailable);
    }

    private static String page(String body) {
        return BODY0 + body + "</body></html>";
    }

    private static LwjglGlRenderBackend gpu(String body, int w, int h, float dpr) {
        LwjglGlRenderBackend b = new LwjglGlRenderBackend(w, h, dpr);
        SoftwareRendering.renderHtml(page(body), w, h, T0, b);
        return b;
    }

    private static SoftwareRenderBackend cpu(String body, int w, int h, float dpr) {
        return SoftwareRendering.renderHtml(page(body), w, h, dpr, T0);
    }

    private static int ch(int argb, int shift) {
        return (argb >>> shift) & 0xFF;
    }

    /** 最大の差（事前乗算の各チャンネル）と、2 以上ずれた画素の数。 */
    private record Diff(int max, int over) {}

    private static Diff diff(Pixmap a, Pixmap b) {
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        int max = 0, over = 0;
        for (int y = 0; y < a.height(); y++) {
            for (int x = 0; x < a.width(); x++) {
                int p = a.get(x, y), q = b.get(x, y);
                int m = 0;
                for (int s = 0; s <= 24; s += 8) m = Math.max(m, Math.abs(ch(p, s) - ch(q, s)));
                max = Math.max(max, m);
                if (m > 2) over++;
            }
        }
        return new Diff(max, over);
    }

    /** CPU 版との差が小さい（誤差は丸めだけ）ことを確かめる。 */
    private static void assertMatchesCpu(String body, int w, int h, float dpr, int tolerance) {
        assertMatchesCpu(body, w, h, dpr, tolerance, 0);
    }

    /**
     * {@code ties} 画素までは、色の境目（円の対角線は上下と左右の辺の境で、中心が丁度のってしまう画素は
     * 浮動小数点の誤差でどちらの辺にも転ぶ）が違ってもよい。それ以外は {@code tolerance} 以内。
     */
    private static void assertMatchesCpu(String body, int w, int h, float dpr, int tolerance, int ties) {
        requireGl();
        try (LwjglGlRenderBackend g = gpu(body, w, h, dpr)) {
            SoftwareRenderBackend c = cpu(body, w, h, dpr);
            Diff d = diff(g.pixmap(), c.pixmap());
            assertTrue(d.max() <= tolerance || d.over() <= ties, "max channel diff " + d.max() + " (" + d.over() + " pixels > 2) for: " + body);
            assertTrue(g.gpuShapeCount() > 0, "GPU が図形を 1 つも描いていない: " + body);
        }
    }

    // ------------------------------------------------------------------ 基本

    @Test
    void implementsTheSharedBackendInterface() {
        requireGl();
        RenderBackend b = gl;
        assertEquals(1f, b.devicePixelRatio());
        assertTrue(new SoftwareRenderBackend(4, 4) instanceof RenderBackend);
    }

    @Test
    void solidBackgroundFillsExactlyTheBox() {
        requireGl();
        try (LwjglGlRenderBackend g = gpu("<div style='margin-left:10px;margin-top:10px;width:40px;height:30px;background:#ff0000'></div>", 80, 60, 1f)) {
            Pixmap p = g.pixmap();
            assertEquals(0xFFFF0000, p.getStraight(20, 20));
            assertEquals(0xFFFF0000, p.getStraight(10, 10), "top-left pixel of the box");
            assertEquals(0xFFFF0000, p.getStraight(49, 39), "bottom-right pixel of the box");
            assertEquals(0, p.getStraight(9, 20));
            assertEquals(0, p.getStraight(50, 20));
            assertEquals(0, p.getStraight(20, 9));
            assertEquals(0, p.getStraight(20, 40));
            assertEquals(1, g.gpuShapeCount());
        }
    }

    @Test
    void imageIsNotUpsideDown() {
        requireGl();
        try (LwjglGlRenderBackend g = gpu("<div style='width:30px;height:10px;background:#00ff00'></div>", 40, 40, 1f)) {
            assertEquals(0xFF00FF00, g.pixmap().getStraight(5, 5), "box is at the top");
            assertEquals(0, g.pixmap().getStraight(5, 35), "bottom is empty");
        }
    }

    @Test
    void clearColorFillsTheBackground() {
        requireGl();
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(20, 20).clearColor(0xFF102030)) {
            SoftwareRendering.renderHtml(page("<div style='width:10px;height:10px;background:#ffffff'></div>"), 20, 20, T0, g);
            assertEquals(0xFFFFFFFF, g.pixmap().getStraight(2, 2));
            assertEquals(0xFF102030, g.pixmap().getStraight(15, 15));
        }
    }

    @Test
    void groupOpacityScalesParts() {
        requireGl();
        try (LwjglGlRenderBackend g = gpu("<div style='width:40px;height:40px;background:#ff0000;opacity:0.5'></div>", 60, 60, 1f)) {
            int c = g.pixmap().getStraight(20, 20);
            assertEquals(128, ch(c, 24), 2);
            assertEquals(255, ch(c, 16), 1);
        }
    }

    @Test
    void overlappingGroupOpacityMatchesCpu() {
        requireGl();
        String body = "<div style='width:40px;height:40px;padding:10px;background:#ff0000;opacity:0.5'>"
                + "<div style='width:30px;height:30px;background:#0000ff'></div></div>"
                + "<div style='width:30px;height:20px;background:#00aa00'></div>";
        try (LwjglGlRenderBackend g = gpu(body, 80, 120, 1f)) {
            Diff d = diff(g.pixmap(), cpu(body, 80, 120, 1f).pixmap());
            assertTrue(d.max() <= 2, "diff " + d);
            assertEquals(128, ch(g.pixmap().getStraight(30, 30), 24), 2);
        }
    }

    @Test
    void nestedGroupOpacityMatchesCpuAndStaysOnGpu() {
        requireGl();
        String body = "<div style='width:60px;height:60px;padding:10px;background:#ff0000;opacity:0.6'>"
                + "<div style='width:40px;height:40px;padding:10px;background:#0000ff;opacity:0.5;border-radius:10px'>"
                + "<div style='width:20px;height:20px;background:#00ff00'></div></div></div>"
                + "<div style='width:30px;height:20px;background:#aaaa00'></div>";
        try (LwjglGlRenderBackend g = gpu(body, 100, 140, 1f)) {
            assertTrue(g.gpuShapeCount() >= 4, "shapes inside layers are drawn by the GPU: " + g.gpuShapeCount());
            Diff d = diff(g.pixmap(), cpu(body, 100, 140, 1f).pixmap());
            assertTrue(d.max() <= 3, "diff " + d);
        }
    }

    @Test
    void groupOpacityWithRotationClipAndTextMatchesCpu() {
        assertMatchesCpu("<div style='margin:30px;width:90px;height:40px;overflow:hidden;border-radius:12px;background:#ffeecc;opacity:0.7;"
                + "font:18px sans-serif;color:#224466;border:3px solid #884400;transform:rotate(-20deg) scale(1.3)'>Rotated clipped text</div>", 160, 160, 1f, 6);
    }

    @Test
    void layersAreReusedAcrossFrames() {
        requireGl();
        String body = "<div style='width:40px;height:40px;padding:10px;background:#ff0000;opacity:0.5'>"
                + "<div style='width:30px;height:30px;background:#0000ff'></div></div>";
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(80, 80)) {
            var items = SoftwareRendering.displayList(page(body), 80, 80);
            SoftwareRenderBackend c = new SoftwareRenderBackend(80, 80);
            for (int i = 0; i < 3; i++) {
                g.render(items, T0);
                c.render(items, T0);
                Diff d = diff(g.pixmap(), c.pixmap());
                assertTrue(d.max() <= 2, "frame " + i + " diff " + d);
            }
        }
    }

    // ------------------------------------------------------------------ filter / mask（GPU の層）

    @Test
    void colorFilterMatchesCpu() {
        assertMatchesCpu("<div style='width:40px;height:40px;background:#ff8800;filter:grayscale(0.7) brightness(1.2)'></div>", 60, 60, 1f, 3);
    }

    @Test
    void invertFilterAppliesToWholeGroupOnGpu() {
        assertMatchesCpu("<div style='width:40px;height:40px;padding:10px;background:#ff0000;filter:invert(1)'>"
                + "<div style='width:30px;height:30px;background:#0000ff'></div></div>", 80, 80, 1f, 3);
    }

    @Test
    void opacityFilterMatchesCpu() {
        assertMatchesCpu("<div style='width:40px;height:40px;background:#00ff00;filter:opacity(0.4)'></div>", 60, 60, 1f, 3);
    }

    @Test
    void blurFilterMatchesCpu() {
        assertMatchesCpu("<div style='margin:20px;width:30px;height:30px;background:#2244cc;border-radius:8px;filter:blur(3px)'></div>", 80, 80, 1f, 5);
    }

    @Test
    void blurWithTextMatchesCpu() {
        assertMatchesCpu("<div style='margin:6px;width:80px;height:24px;background:#ffffff;color:#112233;font:16px sans-serif;filter:blur(1.5px)'>Blur me</div>", 110, 40, 1f, 6);
    }

    @Test
    void dropShadowFilterMatchesCpu() {
        assertMatchesCpu("<div style='margin:10px;width:24px;height:24px;background:#ff0000;border-radius:6px;filter:drop-shadow(8px 6px 3px #0000ff)'></div>", 70, 70, 1f, 5);
    }

    @Test
    void filtersUnderRotationMatchCpu() {
        assertMatchesCpu("<div style='margin:25px;width:30px;height:30px;background:#cc3300;filter:blur(2px) drop-shadow(6px 4px 2px #000000);transform:rotate(25deg)'></div>", 100, 100, 1f, 6);
    }

    @Test
    void alphaMaskMatchesCpu() {
        assertMatchesCpu("<div style='width:60px;height:40px;background:#ff0000;"
                + "mask-image:linear-gradient(to right, #000000 0%, #000000 50%, transparent 50%, transparent 100%)'></div>", 80, 60, 1f, 3);
    }

    @Test
    void gradientMaskMatchesCpu() {
        assertMatchesCpu("<div style='width:60px;height:40px;background:#3355ff;"
                + "mask-image:linear-gradient(to bottom, #000000, transparent)'></div>", 80, 60, 1f, 4);
    }

    @Test
    void luminanceMaskMatchesCpu() {
        assertMatchesCpu("<div style='width:60px;height:40px;background:#ff0000;mask-mode:luminance;"
                + "mask-image:linear-gradient(to right, #ffffff, #000000)'></div>", 80, 60, 1f, 4);
    }

    @Test
    void filterMaskAndOpacityTogetherMatchCpu() {
        assertMatchesCpu("<div style='width:60px;height:40px;background:#ffaa00;opacity:0.8;filter:saturate(2);"
                + "mask-image:linear-gradient(to right, #000000, transparent)'></div>"
                + "<div style='width:20px;height:10px;background:#00aa00'></div>", 80, 80, 1f, 4);
    }

    @Test
    void filterLayerRunsOnGpuAndLayersAreReused() {
        requireGl();
        String body = "<div style='width:40px;height:40px;padding:10px;background:#ff0000;filter:blur(2px);"
                + "mask-image:linear-gradient(#000000,#000000)'><div style='width:30px;height:30px;background:#0000ff'></div></div>";
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(90, 90)) {
            var items = SoftwareRendering.displayList(page(body), 90, 90);
            SoftwareRenderBackend c = new SoftwareRenderBackend(90, 90);
            for (int i = 0; i < 3; i++) {
                g.render(items, T0);
                c.render(items, T0);
                Diff d = diff(g.pixmap(), c.pixmap());
                assertTrue(d.max() <= 5, "frame " + i + " diff " + d);
                assertTrue(g.gpuShapeCount() > 0);
            }
        }
    }

    @Test
    void clipPathCircleMatchesCpu() {
        assertMatchesCpu("<div style='margin:10px;width:60px;height:60px;background:#ff0000;clip-path:circle(28px at 30px 30px)'></div>", 80, 80, 1f, 4);
    }

    @Test
    void clipPathInsetRoundMatchesCpu() {
        assertMatchesCpu("<div style='margin:10px;width:60px;height:50px;background:#3355ff;clip-path:inset(5px 8px round 12px)'></div>", 80, 80, 1f, 4);
    }

    @Test
    void clipPathEllipseMatchesCpu() {
        assertMatchesCpu("<div style='margin:10px;width:60px;height:40px;background:#00aa55;clip-path:ellipse(25px 15px at 50% 50%)'></div>", 80, 60, 1f, 4);
    }

    @Test
    void clipPathPolygonMatchesCpu() {
        assertMatchesCpu("<div style='margin:5px;width:60px;height:60px;background:#aa22cc;clip-path:polygon(50% 0, 100% 38%, 82% 100%, 18% 100%, 0 38%)'></div>", 80, 80, 1f, 4);
    }

    @Test
    void clipPathPolygonEvenOddMatchesCpu() {
        assertMatchesCpu("<div style='margin:5px;width:60px;height:60px;background:#cc8800;clip-path:polygon(evenodd, 50% 0, 80% 100%, 0 38%, 100% 38%, 20% 100%)'></div>", 80, 80, 1f, 4);
    }

    @Test
    void clipPathWithRotationFilterAndChildrenMatchesCpu() {
        assertMatchesCpu("<div style='margin:15px;width:50px;height:50px;transform:rotate(20deg);filter:saturate(1.5);clip-path:circle(22px)'>"
                + "<div style='height:25px;background:#ff0000'></div><div style='height:25px;background:#0000ff'></div></div>", 80, 80, 1f, 5);
    }

    @Test
    void clipPathWithMaskAndOpacityMatchesCpu() {
        assertMatchesCpu("<div style='width:60px;height:40px;background:#ffaa00;opacity:0.7;clip-path:inset(0 round 15px);"
                + "mask-image:linear-gradient(to right, #000000, transparent)'></div>", 80, 60, 1f, 5);
    }

    @Test
    void clipPathStaysOnGpu() {
        requireGl();
        try (LwjglGlRenderBackend g = gpu("<div style='width:60px;height:60px;background:#ff0000;clip-path:circle(25px)'></div>", 80, 80, 1f)) {
            assertTrue(g.gpuShapeCount() > 0);
            assertEquals(0, ch(g.pixmap().get(1, 1), 24));
            assertEquals(0xFFFF0000, g.pixmap().get(30, 30));
        }
    }

    @Test
    void backdropInvertMatchesCpu() {
        assertMatchesCpu("<div style='width:80px;height:40px;background:#ff8800'></div>"
                + "<div style='margin-top:-40px;width:40px;height:40px;backdrop-filter:invert(1)'></div>", 80, 40, 1f, 3);
    }

    @Test
    void backdropBlurMatchesCpu() {
        assertMatchesCpu("<div style='width:40px;height:60px;background:#ffffff;float:left'></div>"
                + "<div style='width:40px;height:60px;background:#000000;float:left'></div>"
                + "<div style='position:absolute;left:10px;top:5px;width:60px;height:50px;border-radius:12px;backdrop-filter:blur(5px)'></div>", 80, 60, 1f, 6);
    }

    @Test
    void backdropWithOpacityAndClipMatchesCpu() {
        assertMatchesCpu("<div style='width:80px;height:60px;background:#2266ff'></div>"
                + "<div style='margin-top:-60px;width:60px;height:60px;opacity:0.5;backdrop-filter:grayscale(1) brightness(1.2);clip-path:circle(28px)'></div>", 80, 60, 1f, 5);
    }

    @Test
    void canWritePng() throws Exception {
        requireGl();
        java.nio.file.Path f = java.nio.file.Files.createTempFile("ef-gl", ".png");
        try (LwjglGlRenderBackend g = gpu("<div style='width:10px;height:10px;background:#123456'></div>", 16, 16, 1f)) {
            g.writePng(f);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(f.toFile());
            assertEquals(0xFF123456, img.getRGB(3, 3));
        } finally {
            java.nio.file.Files.deleteIfExists(f);
        }
    }

    // ------------------------------------------------------------------ CPU 版との一致

    @Test
    void roundedCornersMatchCpu() {
        assertMatchesCpu("<div style='width:100px;height:100px;background:#0000ff;border-radius:30px'></div>", 120, 120, 1f, 4);
    }

    @Test
    void ellipticalCornersMatchCpu() {
        assertMatchesCpu("<div style='width:100px;height:60px;background:#00ff00;border-radius:50px / 10px'></div>", 120, 80, 1f, 4);
    }

    @Test
    void bordersWithPerSideColorsMatchCpu() {
        assertMatchesCpu("<div style='width:40px;height:40px;border:10px solid;border-color:#ff0000 #00ff00 #0000ff #ffff00'></div>", 80, 80, 1f, 4);
    }

    @Test
    void roundedBorderMatchesCpu() {
        assertMatchesCpu("<div style='width:60px;height:40px;border:4px solid #336699;border-radius:14px;background:#ffeecc'></div>", 80, 60, 1f, 4);
    }

    @Test
    void rotationMatchesCpu() {
        assertMatchesCpu("<div style='margin:30px;width:40px;height:10px;background:#0000ff;transform:rotate(30deg)'></div>", 100, 100, 1f, 6);
    }

    @Test
    void highDpiMatchesCpu() {
        assertMatchesCpu("<div style='width:30px;height:20px;background:#ff8800;border-radius:6px;border:2px solid #003366'></div>", 50, 40, 2f, 4);
    }

    @Test
    void overflowClipWithRoundedAncestorMatchesCpu() {
        assertMatchesCpu("<div style='width:60px;height:60px;border-radius:30px;overflow:hidden;background:#222222'>"
                + "<div style='width:80px;height:20px;background:#ff0000'></div></div>", 80, 80, 1f, 6);
    }

    // ------------------------------------------------------------------ グラデーション・影（第 2 段）

    @Test
    void linearGradientMatchesCpu() {
        assertMatchesCpu("<div style='width:100px;height:40px;background:linear-gradient(90deg,#ff0000,#0000ff)'></div>", 120, 60, 1f, 4);
    }

    @Test
    void multiStopRoundedAngledGradientMatchesCpu() {
        assertMatchesCpu("<div style='width:100px;height:60px;border-radius:16px;background:linear-gradient(30deg,#ff0000 10%,#00ff00 50%,rgba(0,0,255,0.5) 90%)'></div>", 120, 80, 1f, 5);
    }

    @Test
    void radialGradientMatchesCpu() {
        assertMatchesCpu("<div style='width:80px;height:80px;background:radial-gradient(circle at 30% 40%,#ffffff,#000000)'></div>", 100, 100, 1f, 5);
    }

    @Test
    void conicGradientMatchesCpu() {
        assertMatchesCpu("<div style='width:80px;height:80px;background:conic-gradient(#ff0000,#00ff00,#0000ff,#ff0000)'></div>", 100, 100, 1f, 6);
    }

    @Test
    void repeatingGradientMatchesCpu() {
        assertMatchesCpu("<div style='width:80px;height:40px;background:repeating-linear-gradient(45deg,#ff0000 0,#ff0000 10px,#ffffff 10px,#ffffff 20px)'></div>", 100, 60, 1f, 8);
    }

    @Test
    void gradientUnderRotationMatchesCpu() {
        assertMatchesCpu("<div style='margin:20px;width:60px;height:30px;background:linear-gradient(#ff0000,#0000ff);transform:rotate(20deg)'></div>", 100, 100, 1f, 8);
    }

    @Test
    void circleBorderMatchesCpuAndIsOnGpu() {
        assertMatchesCpu("<div style='width:60px;height:60px;border:6px solid;border-color:#ff0000 #00ff00 #0000ff #ffff00;border-radius:50%'></div>", 80, 80, 1f, 6, 8);
    }

    @Test
    void circleBorderUnderRotationMatchesCpu() {
        assertMatchesCpu("<div style='width:60px;height:60px;border:4px solid #3366cc;border-radius:50%;transform:rotate(20deg)'></div>", 90, 90, 1f, 6);
    }

    @Test
    void dashedBorderMatchesCpuAndIsOnGpu() {
        assertMatchesCpu("<div style='width:194px;height:50px;border:3px dashed #000000'></div>", 210, 70, 1f, 8);
    }

    @Test
    void roundedDashedBorderMatchesCpu() {
        assertMatchesCpu("<div style='width:120px;height:70px;border:4px dashed #cc3300;border-radius:20px'></div>", 140, 90, 1f, 8);
    }

    @Test
    void dottedBorderMatchesCpu() {
        assertMatchesCpu("<div style='width:120px;height:70px;border:4px dotted #0033cc;border-radius:16px'></div>", 140, 90, 1f, 8);
    }

    @Test
    void dashedAndCircleBordersNoLongerUseCpuLayer() {
        requireGl();
        try (LwjglGlRenderBackend g = gpu("<div style='width:100px;height:50px;border:3px dashed #000'></div>"
                + "<div style='width:40px;height:40px;border:4px solid #f00;border-radius:50%'></div>", 140, 120, 1f)) {
            assertTrue(g.gpuShapeCount() >= 2, "gpu shapes: " + g.gpuShapeCount());
            assertTrue(g.pixmap().getStraight(1, 25) != 0 || g.pixmap().getStraight(2, 25) != 0);
        }
    }

    @Test
    void outerBoxShadowMatchesCpu() {
        assertMatchesCpu("<div style='margin:30px;width:60px;height:40px;background:#ffffff;border-radius:10px;box-shadow:0 0 20px #000000'></div>", 130, 110, 1f, 6);
    }

    @Test
    void insetBoxShadowMatchesCpu() {
        assertMatchesCpu("<div style='width:80px;height:50px;background:#eeeeee;border-radius:12px;box-shadow:inset 0 0 16px #336699'></div>", 100, 70, 1f, 6);
    }

    @Test
    void gradientAndShadowAreDrawnOnGpu() {
        requireGl();
        try (LwjglGlRenderBackend g = gpu("<div style='margin:20px;width:40px;height:30px;background:linear-gradient(#ff0000,#0000ff);box-shadow:0 0 10px #000000'></div>", 90, 80, 1f)) {
            assertTrue(g.gpuShapeCount() >= 2, "gradient and shadow both on GPU, got " + g.gpuShapeCount());
        }
    }

    @Test
    void textAndShapesKeepPaintOrder() {
        // 文字（CPU の層）の上に図形（GPU）が乗る。順序が崩れると文字が隠れない／隠れすぎる
        requireGl();
        String body = "<div style='position:relative;width:200px;height:40px;background:#ffffff'>"
                + "<span style='font-size:24px;color:#000000'>HHHH</span>"
                + "<div style='position:absolute;left:0;top:0;width:200px;height:40px;background:#ff0000'></div></div>";
        try (LwjglGlRenderBackend g = gpu(body, 220, 60, 1f)) {
            SoftwareRenderBackend c = cpu(body, 220, 60, 1f);
            Diff d = diff(g.pixmap(), c.pixmap());
            assertTrue(d.max() <= 4, "diff " + d);
            assertEquals(0xFFFF0000, g.pixmap().getStraight(10, 10), "red cover hides the text under it");
        }
    }

    @Test
    void textOverBackgroundMatchesCpu() {
        requireGl();
        String body = "<div style='width:200px;height:40px;background:#223344'><span style='font-size:24px;color:#ffffff'>Hello</span></div>";
        try (LwjglGlRenderBackend g = gpu(body, 220, 60, 1f)) {
            Pixmap p = g.pixmap();
            long ink = 0;
            for (int y = 0; y < 40; y++) for (int x = 0; x < 100; x++) if (ch(p.getStraight(x, y), 16) > 200) ink++;
            assertTrue(ink > 50, "text pixels drawn over the GPU background");
            Diff d = diff(p, cpu(body, 220, 60, 1f).pixmap());
            assertTrue(d.max() <= 4, "diff " + d);
        }
    }

    @Test
    void repeatedFramesAdvanceTransitionsLikeCpu() {
        requireGl();
        String body = "<div style='width:40px;height:40px;background:#ff0000;animation:fade 1000ms linear infinite'></div>"
                + "<style>@keyframes fade{from{opacity:1}to{opacity:0}}</style>";
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(60, 60)) {
            SoftwareRenderBackend c = new SoftwareRenderBackend(60, 60);
            var items = SoftwareRendering.displayList(page(body), 60, 60);
            int prev = -1;
            for (long t : new long[]{T0, T0 + 500, T0 + 900}) {
                g.render(items, t);
                c.render(items, t);
                Diff d = diff(g.pixmap(), c.pixmap());
                assertTrue(d.max() <= 4, "t=" + t + " diff " + d);
                int a = ch(g.pixmap().getStraight(20, 20), 24);
                if (prev >= 0) assertTrue(a < prev, "opacity falls over time: " + prev + " -> " + a);
                prev = a;
            }
        }
    }

    // ------------------------------------------------------------------ 文字・画像の GPU 化

    private static String gradientPngUri(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, 0xFF000000 | ((x * 255 / w) << 16) | ((y * 255 / h) << 8) | 0x40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    void textIsDrawnOnGpuAndMatchesCpu() {
        requireGl();
        String body = "<div style='width:220px;height:40px;background:#223344'><span style='font-size:24px;color:#ffffff'>Hello GPU</span></div>";
        try (LwjglGlRenderBackend g = gpu(body, 240, 60, 1f)) {
            assertTrue(g.gpuGlyphCount() >= 7, "glyphs on GPU: " + g.gpuGlyphCount());
            Diff d = diff(g.pixmap(), cpu(body, 240, 60, 1f).pixmap());
            assertTrue(d.max() <= 2, "diff " + d);
        }
    }

    @Test
    void textUnderClipAndOpacityMatchesCpu() {
        requireGl();
        String body = "<div style='width:80px;height:30px;overflow:hidden;border-radius:12px;opacity:0.6;background:#ffffff'>"
                + "<span style='font-size:22px;color:#cc0000;white-space:nowrap'>Clipped text overflow</span></div>";
        try (LwjglGlRenderBackend g = gpu(body, 120, 50, 1f)) {
            assertTrue(g.gpuGlyphCount() > 0, "glyphs inside an opacity group still go to the GPU");
            Diff d = diff(g.pixmap(), cpu(body, 120, 50, 1f).pixmap());
            assertTrue(d.max() <= 4, "diff " + d);
        }
    }

    @Test
    void rotatedTextIsDrawnOnGpuAndMatchesCpu() {
        requireGl();
        String body = "<div style='margin:30px;width:80px;font:20px sans-serif;color:#000;transform:rotate(30deg)'>Rotate</div>";
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(140, 140)) {
            SoftwareRendering.renderHtml(page(body), 140, 140, T0, g);
            assertTrue(g.gpuGlyphCount() > 0, "rotated glyphs go to the GPU");
            Diff d = diff(g.pixmap(), cpu(body, 140, 140, 1f).pixmap());
            assertTrue(d.max() <= 4, "diff " + d);
        }
    }

    @Test
    void rotatedTextUnderClipMatchesCpu() {
        assertMatchesCpu("<div style='margin:30px;width:90px;height:40px;overflow:hidden;border-radius:12px;background:#ffeecc;font:18px sans-serif;color:#224466;transform:rotate(-20deg) scale(1.3)'>Rotated clipped text</div>", 160, 160, 1f, 6, 0);
    }

    @Test
    void rotatedTextOnlyLeavesNoCpuLayer() {
        requireGl();
        String body = "<div style='margin:30px;font:20px sans-serif;color:#000;transform:rotate(30deg)'>AB</div>";
        try (LwjglGlRenderBackend g = gpu(body, 100, 100, 1f)) {
            assertTrue(g.gpuGlyphCount() >= 2);
            assertTrue(g.drawCallCount() >= 1);
        }
    }

    @Test
    void highDpiTextMatchesCpu() {
        requireGl();
        String body = "<div style='font-size:14px;color:#112233'>Retina text 123</div>";
        try (LwjglGlRenderBackend g = gpu(body, 120, 30, 2f)) {
            assertTrue(g.gpuGlyphCount() > 0);
            Diff d = diff(g.pixmap(), cpu(body, 120, 30, 2f).pixmap());
            assertTrue(d.max() <= 2, "diff " + d);
        }
    }

    @Test
    void manyDistinctGlyphsOverflowTheAtlasSafely() {
        requireGl();
        StringBuilder sb = new StringBuilder("<div style='width:900px;font-size:11px;color:#000'>");
        for (int cp = 0x4E00; cp < 0x4E00 + 400; cp++) sb.append((char) cp);
        sb.append("</div>");
        String body = sb.toString();
        try (LwjglGlRenderBackend g = gpu(body, 900, 200, 1f)) {
            Diff d = diff(g.pixmap(), cpu(body, 900, 200, 1f).pixmap());
            assertTrue(d.max() <= 2, "diff " + d);
        }
    }

    @Test
    void imageIsDrawnOnGpuAndMatchesCpu() throws Exception {
        requireGl();
        String body = "<img style='width:64px;height:48px' src='" + gradientPngUri(16, 12) + "'>";
        try (LwjglGlRenderBackend g = gpu(body, 80, 60, 1f)) {
            assertEquals(1, g.gpuImageCount());
            Diff d = diff(g.pixmap(), cpu(body, 80, 60, 1f).pixmap());
            assertTrue(d.max() <= 3, "diff " + d);
        }
    }

    @Test
    void roundedRotatedImageMatchesCpu() throws Exception {
        requireGl();
        String body = "<img style='margin:20px;width:60px;height:40px;border-radius:14px;transform:rotate(15deg);opacity:0.8' src='" + gradientPngUri(12, 8) + "'>";
        try (LwjglGlRenderBackend g = gpu(body, 100, 90, 1f)) {
            assertEquals(1, g.gpuImageCount());
            Diff d = diff(g.pixmap(), cpu(body, 100, 90, 1f).pixmap());
            assertTrue(d.max() <= 6, "diff " + d);
        }
    }

    @Test
    void downscaledImageIsDrawnOnGpuAndMatchesCpu() throws Exception {
        requireGl();
        String body = "<img style='width:16px;height:16px' src='" + gradientPngUri(40, 40) + "'>";
        try (LwjglGlRenderBackend g = gpu(body, 40, 40, 1f)) {
            SoftwareRenderBackend c = cpu(body, 40, 40, 1f);
            assertTrue(c.pixmap().getStraight(8, 8) != 0, "CPU draws the image");
            assertTrue(g.gpuImageCount() > 0, "downscaled image goes to the GPU");
            Diff d = diff(g.pixmap(), c.pixmap());
            assertTrue(d.max() <= 4, "diff " + d);
        }
    }

    @Test
    void imageAndTextKeepPaintOrder() throws Exception {
        requireGl();
        String body = "<div style='position:relative;width:100px;height:40px'><span style='font-size:30px;color:#000'>HHH</span>"
                + "<img style='position:absolute;left:0;top:0;width:100px;height:40px' src='" + pngDataUriOpaque() + "'></div>";
        try (LwjglGlRenderBackend g = gpu(body, 120, 60, 1f)) {
            assertEquals(0xFF00FF00, g.pixmap().getStraight(10, 10), "opaque image hides the text under it");
        }
    }

    @Test
    void manyShapesShareFewDrawCalls() {
        requireGl();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("<div style='width:20px;height:6px;margin:1px;border-radius:3px;background:rgb(")
                    .append(i * 6).append(",80,200);box-shadow:0 1px 2px rgba(0,0,0,.4)'></div>");
        }
        String body = sb.toString();
        try (LwjglGlRenderBackend g = gpu(body, 40, 320, 1f)) {
            assertTrue(g.gpuShapeCount() >= 40, "shapes " + g.gpuShapeCount());
            assertTrue(g.drawCallCount() * 10 <= g.gpuShapeCount(), "draws " + g.drawCallCount() + " shapes " + g.gpuShapeCount());
            Diff d = diff(g.pixmap(), cpu(body, 40, 320, 1f).pixmap());
            assertTrue(d.max() <= 3, "diff " + d);
        }
    }

    @Test
    void batchOverflowKeepsOrderAndPixels() {
        requireGl();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 700; i++) {
            sb.append("<div style='position:absolute;left:").append(i % 50).append("px;top:").append(i % 30)
                    .append("px;width:30px;height:20px;border-radius:4px;background:rgba(")
                    .append((i * 37) & 255).append(',').append((i * 91) & 255).append(',').append((i * 13) & 255).append(",.5)'></div>");
        }
        String body = sb.toString();
        try (LwjglGlRenderBackend g = gpu(body, 90, 60, 1f)) {
            assertTrue(g.drawCallCount() >= 2, "overflow must split: " + g.drawCallCount());
            Diff d = diff(g.pixmap(), cpu(body, 90, 60, 1f).pixmap());
            assertTrue(d.max() <= 4, "diff " + d);
        }
    }

    private static String pngDataUriOpaque() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) img.setRGB(x, y, 0xFF00FF00);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    // ------------------------------------------------------------------ mix-blend-mode（GPU の層）

    @Test
    void blendModesMatchCpu() {
        for (String mode : new String[]{"multiply", "screen", "overlay", "darken", "lighten", "color-dodge", "color-burn",
                "hard-light", "soft-light", "difference", "exclusion", "hue", "saturation", "color", "luminosity", "plus-lighter"}) {
            assertMatchesCpu("<div style='width:40px;height:40px;background:#ff8800'></div>"
                    + "<div style='margin-top:-20px;width:40px;height:40px;background:#4090e0;mix-blend-mode:" + mode + "'></div>", 60, 70, 1f, 3);
        }
    }

    @Test
    void blendWithOpacityAndFilterMatchesCpu() {
        assertMatchesCpu("<div style='width:40px;height:40px;background:#33aa55'></div>"
                + "<div style='margin-top:-20px;width:40px;height:40px;background:#ff2200;opacity:0.6;filter:saturate(1.5);mix-blend-mode:hard-light'></div>", 60, 70, 1f, 4);
    }

    /** 層を {@code depth} 段入れ子にし、最も内側に {@code inner} を置く（各段は filter で層になる）。 */
    private static String deepLayers(int depth, String inner) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("<div style='width:70px;height:70px;filter:brightness(1.01)'>");
        sb.append(inner);
        sb.append("</div>".repeat(depth));
        return sb.toString();
    }

    @Test
    void blendBeyondGpuLayerDepthStillMixesWithGpuBackdrop() {
        requireGl();
        String body = deepLayers(7, "<div style='width:40px;height:40px;background:#ff8800'></div>"
                + "<div style='margin-top:-20px;width:40px;height:40px;background:#4090e0;mix-blend-mode:multiply'></div>");
        try (LwjglGlRenderBackend g = gpu(body, 80, 80, 1f)) {
            Pixmap p = g.pixmap();
            int overlap = p.getStraight(20, 30); // 橙と青の重なり: 乗算なら暗い色（青の素の色ではない）
            assertNotEquals(0xFF4090E0, overlap, "blend must read the GPU backdrop, not paint normal");
            Diff d = diff(p, cpu(body, 80, 80, 1f).pixmap());
            assertTrue(d.max() <= 4, "diff " + d);
        }
    }

    @Test
    void backdropFilterBeyondGpuLayerDepthMatchesCpu() {
        requireGl();
        String body = deepLayers(7, "<div style='width:60px;height:20px;background:#ff0000'></div>"
                + "<div style='margin-top:-10px;width:50px;height:40px;background:rgba(255,255,255,0.1);backdrop-filter:invert(1)'></div>");
        try (LwjglGlRenderBackend g = gpu(body, 80, 80, 1f)) {
            Diff d = diff(g.pixmap(), cpu(body, 80, 80, 1f).pixmap());
            assertTrue(d.max() <= 4, "diff " + d);
        }
    }

    @Test
    void isolatedBlendMatchesCpu() {
        assertMatchesCpu("<div style='width:40px;height:40px;background:#ff8800'></div>"
                + "<div style='margin-top:-30px;width:30px;height:30px;isolation:isolate'>"
                + "<div style='width:30px;height:30px;background:#80c0ff;mix-blend-mode:multiply'></div></div>", 60, 70, 1f, 3);
    }

    @Test
    void glyphAtlasGrowsPastInitialSizeAndStillMatchesCpu() {
        requireGl();
        // 大きさの違う同じ文字は別の字形。1024² を超えるまで積んで、アトラスの拡張（作り直し＋載せ直し）を通す
        StringBuilder sb = new StringBuilder();
        for (int px = 40; px <= 100; px += 2) {
            sb.append("<div style='font:").append(px).append("px sans-serif;color:#102030;height:").append(px + 4)
                    .append("px;white-space:nowrap'>ABCDEFGHIJKLMNOPQRSTUVWX</div>");
        }
        int w = 1800, h = 2400;
        try (LwjglGlRenderBackend g = gpu(sb.toString(), w, h, 1f)) {
            assertTrue(g.atlasSize() > 1024, "アトラスが広がっていない: " + g.atlasSize());
            SoftwareRenderBackend c = cpu(sb.toString(), w, h, 1f);
            Diff d = diff(g.pixmap(), c.pixmap());
            assertTrue(d.max() <= 6 || d.over() <= 50, "max channel diff " + d.max() + " (" + d.over() + " pixels > 2)");
        }
    }

    @Test
    void persistentBatchBufferSurvivesManyFlushes() {
        // 512 を超える図形で複数回バッチを吐く。転送バッファを使い回しても内容が壊れない
        requireGl();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 1500; i++) {
            sb.append("<div style='position:absolute;left:").append((i % 50) * 4).append("px;top:").append((i / 50) * 4)
                    .append("px;width:3px;height:3px;background:#").append(String.format("%06x", (i * 2654435) & 0xFFFFFF)).append("'></div>");
        }
        assertMatchesCpu(sb.toString(), 200, 120, 1f, 2);
    }
}
