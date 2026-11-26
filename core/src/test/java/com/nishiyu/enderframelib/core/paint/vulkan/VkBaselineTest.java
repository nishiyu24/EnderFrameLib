package com.nishiyu.enderframelib.core.paint.vulkan;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.gl.LwjglGlRenderBackend;
import com.nishiyu.enderframelib.core.paint.port.GpuCoverage;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * G3 受け入れ門番: {@code showcase} / {@code large-list} / {@code varied-list} の 3 シナリオを
 * {@code SOFTWARE} / {@code GL} / {@code Vulkan} で描画し、ピクセル一致（許容差内）・
 * GPU 被覆率・{@code VK_LAYER_KHRONOS_validation} エラー 0 件を検証する。
 */
class VkBaselineTest {
    private static final long T0 = 10_000L;
    private static final int W = 800, H = 600;
    private static final Path FIXTURES = Path.of("mod/src/main/resources/data/enderframelib/enderframe_ui");

    private static final String[] PALETTE = {"#fff", "#eef", "#fee", "#efe", "#ffe", "#eff", "#fef", "#dde", "#edd", "#ded", "#eed"};
    private static final String[] BORDER = {"#ccc", "#99c", "#c99", "#9c9", "#cc9", "#9cc", "#c9c", "#888"};
    private static final String LOREM = "alpha beta gamma delta epsilon zeta eta theta iota kappa ";

    private static String doc(String name) throws IOException {
        return switch (name) {
            case "showcase" -> Files.readString(FIXTURES.resolve("showcase.html"), StandardCharsets.UTF_8);
            case "large-list" -> largeList(2000);
            case "varied-list" -> variedList(2000);
            default -> throw new IllegalArgumentException("unknown scenario: " + name);
        };
    }

    private static String largeList(int count) {
        StringBuilder sb = new StringBuilder(count * 90 + 256);
        sb.append("<html><head><style>body{margin:0;padding:8px;font-family:sans-serif;background:#fff}ul{list-style:none;margin:0;padding:0}li{padding:6px 10px;border-bottom:1px solid #ddd}</style></head><body><ul>");
        for (int i = 0; i < count; i++) sb.append("<li>Row ").append(i).append(" — item alpha beta gamma</li>");
        return sb.append("</ul></body></html>").toString();
    }

    private static String variedList(int count) {
        StringBuilder sb = new StringBuilder(count * 160 + 256);
        sb.append("<html><head><style>body{margin:0;padding:8px;font-family:sans-serif;background:#f7f7fa}ul{list-style:none;margin:0;padding:0}li{box-sizing:border-box}</style></head><body><ul>");
        for (int i = 0; i < count; i++) {
            sb.append("<li style=\"background:").append(PALETTE[i % PALETTE.length])
                    .append(";padding:").append(4 + i % 6).append("px ").append(6 + (i * 3) % 10).append("px")
                    .append(";border-bottom:").append(1 + (i % 5 == 0 ? 1 : 0)).append("px solid ").append(BORDER[i % BORDER.length])
                    .append(i % 4 == 0 ? ";border-radius:" + (2 + i % 7) + "px" : "")
                    .append("\">Row ").append(i).append(' ')
                    .append(LOREM, 0, Math.min(LOREM.length(), 14 + (i * 7) % 44))
                    .append("</li>");
        }
        return sb.append("</ul></body></html>").toString();
    }

    private static int maxDiff(Pixmap a, Pixmap b) {
        assertEquals(a.width(), b.width());
        assertEquals(a.height(), b.height());
        int[] pa = a.pixels(), pb = b.pixels();
        int max = 0;
        for (int i = 0; i < pa.length; i++) {
            for (int sh = 0; sh < 32; sh += 8) {
                int d = Math.abs(((pa[i] >>> sh) & 0xFF) - ((pb[i] >>> sh) & 0xFF));
                if (d > max) max = d;
            }
        }
        return max;
    }

    private static void check(String scenario, double minCoverage) throws IOException {
        String html = doc(scenario);

        SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f);
        SoftwareRendering.renderHtml(html, W, H, T0, cpu);

        VkRenderBackend vkBackend;
        try {
            vkBackend = new VkRenderBackend(W, H, 1f, true);
        } catch (Throwable t) {
            Assumptions.abort("Vulkan 1.1 を使えない: " + t);
            return;
        }

        Pixmap vk;
        double coverage;
        List<String> valMsgs;
        int valErrs;
        boolean prevCov = GpuCoverage.enabled();
        GpuCoverage.reset();
        GpuCoverage.enable(true);
        try (VkRenderBackend v = vkBackend) {
            SoftwareRendering.renderHtml(html, W, H, T0, v);
            vk = v.pixmap().copy();
            coverage = GpuCoverage.coverage(v.rectTreeReplayedShapes());
            valErrs = v.validationErrorCount();
            valMsgs = v.validationMessages();
            System.out.printf("G3 Vulkan %s: GPU coverage=%.4f accepted=%d declined=%d declinedByKind=%s valErrs=%d%n",
                    scenario, coverage, GpuCoverage.totalAccepted(), GpuCoverage.totalDeclined(),
                    GpuCoverage.declinedHistogram(), valErrs);
        } finally {
            GpuCoverage.reset();
            GpuCoverage.enable(prevCov);
        }

        assertEquals(0, valErrs, scenario + ": Vulkan validation errors: " + valMsgs);
        assertTrue(valMsgs.isEmpty(), scenario + ": Vulkan validation messages: " + valMsgs);
        assertTrue(coverage >= minCoverage, scenario + ": GPU coverage " + coverage + " < " + minCoverage);

        int vsCpu = maxDiff(vk, cpu.pixmap());
        System.out.printf("G3 Vulkan %s: maxDiff(vk, cpu)=%d%n", scenario, vsCpu);
        assertTrue(vsCpu <= 8, scenario + ": Vulkan と SOFTWARE の最大差 " + vsCpu);

        LwjglGlRenderBackend glBackend = null;
        try {
            glBackend = new LwjglGlRenderBackend(W, H, 1f);
        } catch (Throwable ignored) {
        }
        if (glBackend != null) {
            try (LwjglGlRenderBackend g = glBackend) {
                SoftwareRendering.renderHtml(html, W, H, T0, g);
                Pixmap gl = g.pixmap().copy();
                int vsGl = maxDiff(vk, gl);
                System.out.printf("G3 Vulkan %s: maxDiff(vk, gl)=%d%n", scenario, vsGl);
                assertTrue(vsGl <= 4, scenario + ": Vulkan と GL の最大差 " + vsGl);
            }
        }
    }

    @Test
    void showcaseMatchesSoftwareAndGlZeroValidationErrors() throws IOException {
        check("showcase", 0.90);
    }

    @Test
    void largeListMatchesSoftwareAndGlZeroValidationErrors() throws IOException {
        check("large-list", 0.90);
    }

    @Test
    void variedListMatchesSoftwareAndGlZeroValidationErrors() throws IOException {
        check("varied-list", 0.90);
    }

    @Test
    void vulkanOutputIsDeterministic() throws IOException {
        String html = doc("large-list");
        VkRenderBackend v1;
        try {
            v1 = new VkRenderBackend(W, H, 1f, true);
        } catch (Throwable t) {
            Assumptions.abort("Vulkan 1.1 を使えない: " + t);
            return;
        }
        Pixmap a;
        try (v1) {
            SoftwareRendering.renderHtml(html, W, H, T0, v1);
            a = v1.pixmap().copy();
            assertEquals(0, v1.validationErrorCount(), "Validation errors: " + v1.validationMessages());
        }
        Pixmap b;
        try (VkRenderBackend v2 = new VkRenderBackend(W, H, 1f, true)) {
            SoftwareRendering.renderHtml(html, W, H, T0, v2);
            b = v2.pixmap().copy();
            assertEquals(0, v2.validationErrorCount(), "Validation errors: " + v2.validationMessages());
        }
        assertEquals(0, maxDiff(a, b));
    }
}
