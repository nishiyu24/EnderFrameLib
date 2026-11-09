package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Phase A-3: 旧 {@code GpuBatchPacker}（第 2 世代の GPU 経路）を撤去した後、新経路が受けない稀な図形（せん断の姿勢・縦横比の違う
 * 拡縮の角丸）は GPU が断り、CPU が描く。その画素が CPU 描画と合い、GPU の図形との描画順も保たれることを確かめる。
 */
class CpuFallbackOfRareShapesTest {
    private static final long T0 = 10_000L;

    private static String page(String body) {
        return "<html><body style='margin:0;background:#f4f4f4'>" + body + "</body></html>";
    }

    private static void requireGl() {
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(8, 8)) {
            // 作れれば良い
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
    }

    private static int maxDiff(Pixmap a, Pixmap b) {
        int max = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                for (int sh = 0; sh < 32; sh += 8) max = Math.max(max, Math.abs(((a.get(x, y) >>> sh) & 0xFF) - ((b.get(x, y) >>> sh) & 0xFF)));
        return max;
    }

    /** GPU 版で描き、CPU 描画との最大差と、新経路が姿勢・角の理由で断った数（{@code [0]=姿勢, [2]=角}）を返す。 */
    private static int[] compare(String html, int w, int h, long[] rejects) {
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(w, h, 1f)) {
            SoftwareRendering.renderHtml(html, w, h, T0, g);
            long[] r = g.rectTreeRejects();
            rejects[0] = r[0];
            rejects[2] = r[2];
            SoftwareRenderBackend c = SoftwareRendering.renderHtml(html, w, h, 1f, T0);
            return new int[]{maxDiff(g.pixmap(), c.pixmap())};
        }
    }

    @Test
    void shearedPoseIsRefusedByTheGpuAndDrawnByTheCpu() {
        requireGl();
        String html = page("<div style='position:absolute;left:30px;top:20px;width:80px;height:40px;background:#c33;"
                + "border:3px solid #600;border-radius:10px;transform:skewX(25deg)'></div>");
        long[] rej = new long[5];
        int[] d = compare(html, 200, 100, rej);
        assertTrue(rej[0] >= 1, "せん断の姿勢は新経路が断る: " + rej[0]);
        assertTrue(d[0] <= 4, "CPU 描画との差 " + d[0]);
    }

    @Test
    void anisotropicScaleWithRoundedCornersIsRefusedByTheGpuAndDrawnByTheCpu() {
        requireGl();
        String html = page("<div style='position:absolute;left:30px;top:20px;width:50px;height:40px;background:#36c;"
                + "border-radius:12px;transform:scale(2,1);transform-origin:0 0'></div>");
        long[] rej = new long[5];
        int[] d = compare(html, 200, 100, rej);
        assertTrue(rej[2] >= 1, "縦横比の違う拡縮の角丸は新経路が断る: " + rej[2]);
        assertTrue(d[0] <= 4, "CPU 描画との差 " + d[0]);
    }

    /** GPU の図形 → CPU に回った図形 → GPU の図形の順に重ねても、描画順が保たれる（途中で CPU の命令を書き切る）。 */
    @Test
    void cpuFallbackShapeKeepsTheDrawingOrderBetweenGpuShapes() {
        requireGl();
        String html = page("<div style='position:absolute;left:20px;top:20px;width:100px;height:60px;background:#2a2'></div>"
                + "<div style='position:absolute;left:40px;top:30px;width:80px;height:40px;background:rgba(200,0,0,.7);"
                + "transform:skewX(20deg)'></div>"
                + "<div style='position:absolute;left:60px;top:40px;width:100px;height:40px;background:rgba(0,0,220,.6);border-radius:8px'></div>");
        long[] rej = new long[5];
        int[] d = compare(html, 200, 100, rej);
        assertTrue(rej[0] >= 1, "せん断の図形は CPU へ回る");
        assertTrue(d[0] <= 4, "描画順を含む CPU 描画との差 " + d[0]);
    }

    @Test
    void legacyBatchPathStaysRemoved() throws Exception {
        for (String name : new String[]{"com.nishiyu.enderframelib.core.paint.gpu.GpuBatchPacker",
                "com.nishiyu.enderframelib.core.paint.gpu.BatchDevice"}) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name + " は撤去した");
        }
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/gl/LwjglGlRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"GpuBatchPacker", "BatchDevice", "SHAPE_FRAG", "SHAPE_VERT", "shapeProgram", "dataTex", "extTex",
                "flushBatch", "packer."}) {
            assertFalse(t.contains(bad), bad + " は旧経路の部品。新経路だけにした");
        }
    }
}
