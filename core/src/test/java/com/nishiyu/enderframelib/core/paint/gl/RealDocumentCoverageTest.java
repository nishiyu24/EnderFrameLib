package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.port.GpuCoverage;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** 同梱の実文書は、どの図形も CPU 層へ落ちず GPU が受け取る（被覆率 100%。B4）。 */
class RealDocumentCoverageTest {
    private static final Path DIR = Path.of("mod/src/main/resources/data/enderframelib/enderframe_ui");

    @Test
    void bundledDocumentsAreFullyCoveredByTheGpu() throws Exception {
        try (LwjglGlRenderBackend probe = new LwjglGlRenderBackend(8, 8)) {
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
        // animation_test.html は、縦横の倍率が違う変形（scale(x, y) のアニメーション）の下の角丸の枠線が 1 つ CPU 層へ落ちる（円の角は一様な縮尺が前提）。
        // それ以外の同梱文書は 0。落ちる数が増えたら失敗にする
        java.util.Map<String, Long> allowed = java.util.Map.of("animation_test.html", 3L);
        for (String name : List.of("showcase.html", "surface_test.html", "voidwave.html", "item_block_test.html", "animation_test.html", "hud_test.html", "media_test.html")) {
            Path file = DIR.resolve(name);
            if (!Files.exists(file)) file = Path.of("..").resolve(file); // :core のテストの作業ディレクトリが core/ のとき
            String html = Files.readString(file);
            try (StandaloneView v = new StandaloneView(html, 800, 600, FontResolver.FALLBACK);
                 LwjglGlRenderBackend gl = new LwjglGlRenderBackend(800, 600, 1f)) {
                GpuCoverage.reset();
                GpuCoverage.enable(true);
                try {
                    for (int i = 0; i < 3; i++) v.frame(i * 100L, gl, true);
                } finally {
                    GpuCoverage.enable(false);
                }
                assertTrue(GpuCoverage.totalDeclined() <= allowed.getOrDefault(name, 0L), name + " で CPU 層へ落ちた図形: " + GpuCoverage.declinedHistogram());
            }
        }
    }
}
