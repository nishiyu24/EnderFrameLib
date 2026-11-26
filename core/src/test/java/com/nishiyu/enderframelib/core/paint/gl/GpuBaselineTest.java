package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.port.GpuCoverage;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * G0（ENGINE_REARCHITECTURE_PLAN）: GL の出力画素を showcase・large・varied の全シナリオで保存し、G1〜G3 の比較基準にする。
 *
 * <ul>
 *   <li>毎回: GL と {@code SOFTWARE} が許容差内で一致すること（ハードウェア非依存の合否線）。</li>
 *   <li>基準画像 {@code core/baseline/gpu/<scenario>.png}（git 管理外。フォントがマシン依存なのでマシンごとに作る）が無ければ作り、
 *       あれば GL の出力と比べる。既定は小さな差を許す（GPU の違い）。G1 以降のリファクタリングでは
 *       {@code -Dgpu.baseline.exact=true} を付けて全画素一致を要求する。作り直しは {@code -Dgpu.baseline.update=true}。</li>
 *   <li>被覆率: GPU が受理した図形数 ÷ 全図形数が、下限を割らないこと（B4 の着手判断の基準）。</li>
 * </ul>
 */
class GpuBaselineTest {
    private static final long T0 = 10_000L;
    private static final int W = 800, H = 600;
    private static final Path BASELINE_DIR = Path.of("core/baseline/gpu");
    private static final Path FIXTURES = Path.of("mod/src/main/resources/data/enderframelib/enderframe_ui");

    private static final String[] PALETTE = {"#fff", "#eef", "#fee", "#efe", "#ffe", "#eff", "#fef", "#dde", "#edd", "#ded", "#eed"};
    private static final String[] BORDER = {"#ccc", "#99c", "#c99", "#9c9", "#cc9", "#9cc", "#c9c", "#888"};
    private static final String LOREM = "alpha beta gamma delta epsilon zeta eta theta iota kappa ";

    /** 計測の代表文書（bench の {@code Workloads} と同じ形。行数だけ小さくして実行を短くする）。 */
    static String doc(String name) throws IOException {
        return switch (name) {
            case "showcase" -> Files.readString(FIXTURES.resolve("showcase.html"), StandardCharsets.UTF_8).replace("\r\n", "\n");
            case "large-list" -> largeList(120);
            case "varied-list" -> variedList(120);
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static String largeList(int count) {
        StringBuilder sb = new StringBuilder("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { padding: 4px; border-bottom: 1px solid #ccc; }</style></head><body>");
        sb.append("<div id=\"scroller\" style=\"height: 400px; overflow-y: auto;\"><ul>");
        for (int i = 1; i <= count; i++) sb.append("<li id=\"item-").append(i).append("\">Row item #").append(i).append(" with description text</li>");
        return sb.append("</ul></div></body></html>").toString();
    }

    private static String variedList(int count) {
        StringBuilder sb = new StringBuilder("<html><head><style>body { margin: 8px; } ul { list-style: none; padding: 0; } li { border-bottom: 1px solid #ccc; }</style></head><body>");
        sb.append("<div id=\"scroller\" style=\"height: 400px; overflow-y: auto;\"><ul>");
        for (int i = 1; i <= count; i++) {
            int pad = 2 + (i * 7) % 9, mb = i % 5, bl = (i * 3) % 5, fs = 80 + (i * 5) % 6 * 10;
            sb.append("<li id=\"item-").append(i).append("\" style=\"padding:").append(pad).append("px ").append(pad + (i % 4)).append("px;margin-bottom:")
              .append(mb).append("px;background:").append(PALETTE[(i * 3) % PALETTE.length]).append(";border-left:").append(bl).append("px solid ")
              .append(BORDER[i % BORDER.length]).append(";border-bottom:").append(1 + i % 3).append("px solid ").append(BORDER[(i * 5) % BORDER.length])
              .append(";font-size:").append(fs).append("%").append(i % 6 == 0 ? ";color:#224" : "").append("\">");
            sb.append("Row item #").append(i).append(" ").append(LOREM.repeat(1 + (i * 13) % 6));
            if (i % 3 == 0) sb.append("<span style=\"background:").append(PALETTE[i % PALETTE.length]).append(";padding:1px ").append(2 + i % 5).append("px;border-radius:").append(i % 4).append("px\">tag").append(i % 7).append("</span>");
            if (i % 4 == 0) sb.append(" <b>bold ").append(i % 10).append("</b>");
            sb.append("</li>");
        }
        return sb.append("</ul></div></body></html>").toString();
    }

    private static void requireGl() {
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(8, 8)) {
            // 作れれば良い
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
    }

    /** 2 枚の（事前乗算 ARGB の）画素の、チャンネルごとの最大差。 */
    static int maxDiff(Pixmap a, Pixmap b) {
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        int max = 0;
        for (int y = 0; y < a.height(); y++)
            for (int x = 0; x < a.width(); x++)
                for (int sh = 0; sh < 32; sh += 8) max = Math.max(max, Math.abs(((a.get(x, y) >>> sh) & 0xFF) - ((b.get(x, y) >>> sh) & 0xFF)));
        return max;
    }

    static void writePng(Pixmap pm, Path file) throws IOException {
        BufferedImage img = new BufferedImage(pm.width(), pm.height(), BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, pm.width(), pm.height(), pm.toStraightArgb(), 0, pm.width());
        Files.createDirectories(file.getParent());
        ImageIO.write(img, "png", file.toFile());
    }

    static Pixmap readPng(Path file) throws IOException {
        BufferedImage img = ImageIO.read(file.toFile());
        int[] argb = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        return Pixmap.fromStraightArgb(img.getWidth(), img.getHeight(), argb);
    }

    private static void check(String scenario, double minCoverage) throws IOException {
        requireGl();
        String html = doc(scenario);
        SoftwareRenderBackend cpu = SoftwareRendering.renderHtml(html, W, H, 1f, T0);
        Pixmap gl;
        boolean prev = GpuCoverage.enabled();
        GpuCoverage.reset();
        GpuCoverage.enable(true);
        double coverage;
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(W, H, 1f)) {
            SoftwareRendering.renderHtml(html, W, H, T0, g);
            gl = g.pixmap().copy();
            coverage = GpuCoverage.coverage(g.rectTreeReplayedShapes());
        } finally {
            GpuCoverage.enable(prev);
        }
        System.out.printf("G0 %s: GPU coverage=%.4f accepted=%d declined=%d declinedByKind: %s%n", scenario, coverage,
                GpuCoverage.totalAccepted(), GpuCoverage.totalDeclined(), GpuCoverage.declinedHistogram());
        assertTrue(coverage >= minCoverage, scenario + " の GPU 被覆率 " + coverage + " < " + minCoverage + "（" + GpuCoverage.declinedHistogram() + "）");

        // ① SOFTWARE との一致（ハードウェア非依存）
        int vsCpu = maxDiff(gl, cpu.pixmap());
        assertTrue(vsCpu <= 8, scenario + ": GL と SOFTWARE の最大差 " + vsCpu);

        // ② 基準画像（マシンごと）。無ければ作る
        Path file = BASELINE_DIR.resolve(scenario + ".png");
        if (Boolean.getBoolean("gpu.baseline.update") || !Files.exists(file)) {
            writePng(gl, file);
            System.out.println("G0 基準画像を書いた: " + file.toAbsolutePath());
            return;
        }
        Pixmap base = readPng(file);
        int vsBase = maxDiff(gl, base);
        int allowed = Boolean.getBoolean("gpu.baseline.exact") ? 0 : 2;
        assertTrue(vsBase <= allowed, scenario + ": 基準画像との最大差 " + vsBase + "（許容 " + allowed + "）");
    }

    @Test
    void showcaseMatchesSoftwareAndBaseline() throws IOException {
        check("showcase", 0.90);
    }

    @Test
    void largeListMatchesSoftwareAndBaseline() throws IOException {
        check("large-list", 0.90);
    }

    @Test
    void variedListMatchesSoftwareAndBaseline() throws IOException {
        check("varied-list", 0.90);
    }

    /** 同じ文書を 2 回描いても GL の出力は同じ（基準画像が不安定でないことの確認）。 */
    @Test
    void glOutputIsDeterministic() throws IOException {
        requireGl();
        String html = doc("large-list");
        Pixmap a, b;
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(W, H, 1f)) {
            SoftwareRendering.renderHtml(html, W, H, T0, g);
            a = g.pixmap().copy();
        }
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(W, H, 1f)) {
            SoftwareRendering.renderHtml(html, W, H, T0, g);
            b = g.pixmap().copy();
        }
        assertEquals(0, maxDiff(a, b));
    }

    /** 新経路が断る図形（せん断の姿勢）が、実際の GL 描画で「断り」として数えられ、被覆率が 1 を割る。 */
    @Test
    void coverageSeesShapesTheGpuRefuses() {
        requireGl();
        String html = "<html><body style='margin:0'><div style='position:absolute;left:30px;top:20px;width:80px;height:40px;background:#c33;"
                + "border:3px solid #600;border-radius:10px;transform:skewX(25deg)'></div>"
                + "<div style='position:absolute;left:150px;top:20px;width:80px;height:40px;background:#36c'></div></body></html>";
        boolean prev = GpuCoverage.enabled();
        GpuCoverage.reset();
        GpuCoverage.enable(true);
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(300, 100, 1f)) {
            SoftwareRendering.renderHtml(html, 300, 100, T0, g);
            assertTrue(GpuCoverage.totalDeclined() >= 1, "せん断の図形は断られる: " + GpuCoverage.declinedHistogram());
            assertTrue(GpuCoverage.totalAccepted() >= 1, "平行な図形は受理される");
            double c = GpuCoverage.coverage(g.rectTreeReplayedShapes());
            assertTrue(c > 0.0 && c < 1.0, "被覆率 " + c);
            assertNotEquals("(none)", GpuCoverage.declinedHistogram());
            assertTrue(g.cpuOverlayFlushCount() >= 1, "CPU 層へ落ちた図形があれば重ね転送が起きる");
        } finally {
            GpuCoverage.reset();
            GpuCoverage.enable(prev);
        }
    }

    /** 被覆率の計数そのもの: 受理・断りが種別に分かれて数えられ、無効なら数えない。 */
    @Test
    void coverageCounterSeparatesAcceptedAndDeclined() {
        boolean prev = GpuCoverage.enabled();
        try {
            GpuCoverage.reset();
            GpuCoverage.enable(false);
            GpuCoverage.note(GpuCoverage.Kind.FILL, true);
            assertEquals(0, GpuCoverage.totalAccepted(), "無効の間は数えない");
            GpuCoverage.enable(true);
            assertTrue(GpuCoverage.note(GpuCoverage.Kind.FILL, true));
            assertFalse(GpuCoverage.note(GpuCoverage.Kind.IMAGE, false));
            assertFalse(GpuCoverage.note(GpuCoverage.Kind.IMAGE, false));
            assertFalse(GpuCoverage.note(GpuCoverage.Kind.GLYPH, false));
            assertEquals(1, GpuCoverage.accepted(GpuCoverage.Kind.FILL));
            assertEquals(2, GpuCoverage.declined(GpuCoverage.Kind.IMAGE));
            assertEquals(0.25, GpuCoverage.coverage(0), 1e-9);
            assertEquals(0.4, GpuCoverage.coverage(1), 1e-9); // 受理 1 + 再生 1 = 2、断り 3
            assertEquals("IMAGE=2 GLYPH=1", GpuCoverage.declinedHistogram());
        } finally {
            GpuCoverage.reset();
            GpuCoverage.enable(prev);
        }
    }
}
