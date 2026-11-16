package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.gpu.GpuRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * B10: 差分パッチで書き換えた範囲だけをシザー付きでクリアして描き直す GL の部分描画は、毎フレーム全面を描き直した結果と
 * 全画素一致する（ホバー・文字の書き換え・スタイルの変更を混ぜた長い列で、フレームごとに比べる）。
 */
class GlDamageParityTest {
    @AfterEach
    void restore() {
        GpuRenderBackend.setDamageEnabled(true);
    }

    private static String listHtml(int rows) {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:8px;font:14px sans-serif} li{padding:4px;border-bottom:1px solid #ccc;list-style:none}"
                + " li:hover{background:#cdf;box-shadow:0 0 6px #08f} .hot{background:#fc8;border-radius:6px}</style></head><body><ul id='u'>");
        for (int i = 0; i < rows; i++) sb.append("<li id='r").append(i).append("'>Row item #").append(i).append(" with some description text</li>");
        return sb.append("</ul></body></html>").toString();
    }

    /** 1 つの文書で、同じ乱数列の操作を ON / OFF の 2 つのバックエンドへ流して、毎フレーム比べる。 */
    private static void run(String label, String html, int w, int h, int frames, long stepMs, boolean requirePartial, String[] textIds, String[] styleIds, double[][] hoverPoints) {
        run(label, false, html, w, h, frames, stepMs, requirePartial, textIds, styleIds, hoverPoints);
    }

    private static com.nishiyu.enderframelib.core.paint.RenderBackend open(boolean vulkan, int w, int h) {
        return vulkan ? new com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend(w, h, 1f) : new LwjglGlRenderBackend(w, h, 1f);
    }

    private static GpuRenderBackend gpuOf(com.nishiyu.enderframelib.core.paint.RenderBackend b) {
        return b instanceof LwjglGlRenderBackend g ? g.gpuBackend() : ((com.nishiyu.enderframelib.core.paint.vulkan.VkRenderBackend) b).gpuBackend();
    }

    private static void run(String label, boolean vulkan, String html, int w, int h, int frames, long stepMs, boolean requirePartial, String[] textIds, String[] styleIds, double[][] hoverPoints) {
        try (com.nishiyu.enderframelib.core.paint.RenderBackend probe = open(vulkan, 8, 8)) {
        } catch (Throwable t) {
            Assumptions.abort((vulkan ? "Vulkan" : "OpenGL") + " を使えない: " + t);
        }
        Pixmap[] off = new Pixmap[frames];
        long partial = 0;
        for (int mode = 0; mode < 2; mode++) {
            GpuRenderBackend.setDamageEnabled(mode == 1);
            Random rnd = new Random(7);
            try (StandaloneView v = new StandaloneView(html, w, h, FontResolver.FALLBACK);
                 com.nishiyu.enderframelib.core.paint.RenderBackend gl = open(vulkan, w, h)) {
                for (int i = 0; i < frames; i++) {
                    int op = rnd.nextInt(10);
                    if (i > 3) {
                        if (op < 5) {
                            double[] p = hoverPoints[rnd.nextInt(hoverPoints.length)];
                            v.mouseMove(p[0], p[1], gl.sessionId());
                        } else if (op < 7 && textIds.length > 0) {
                            var el = v.document().getElementById(textIds[rnd.nextInt(textIds.length)]);
                            if (el != null) el.setTextContent("t" + rnd.nextInt(100000));
                        } else if (op < 9 && styleIds.length > 0) {
                            var el = v.document().getElementById(styleIds[rnd.nextInt(styleIds.length)]);
                            if (el != null) el.setClassName(rnd.nextBoolean() ? "hot" : "");
                        }
                    }
                    v.frame(1000 + i * stepMs, gl, false);
                    Pixmap pm = gl.pixmap().copy();
                    if (mode == 0) {
                        off[i] = pm;
                    } else {
                        int[] a = off[i].pixels(), b = pm.pixels();
                        int diff = 0;
                        for (int k = 0; k < a.length; k++) if (a[k] != b[k]) diff++;
                        assertEquals(0, diff, label + (vulkan ? "(Vulkan)" : "(GL)") + " フレーム " + i + " で全面描画と違う画素");
                    }
                }
                if (mode == 1) partial = gpuOf(gl).partialFrameCount();
            }
        }
        if (requirePartial) assertTrue(partial > 0, label + ": 部分描画が 1 回も使われていない（検証になっていない）");
    }

    @Test
    void listWithHoverTextAndStyleChanges() {
        String[] ids = new String[40];
        for (int i = 0; i < ids.length; i++) ids[i] = "r" + (i * 3);
        double[][] pts = new double[30][];
        for (int i = 0; i < pts.length; i++) pts[i] = new double[]{20 + (i * 37) % 500, 12 + (i * 29) % 500};
        run("list", listHtml(60), 600, 560, 160, 16, true, ids, ids, pts);
    }

    @Test
    void showcaseWithHoverAndTextChanges() throws Exception {
        Path f = Path.of("core/demo/resources/showcase.html");
        if (!Files.exists(f)) f = Path.of("demo/resources/showcase.html");
        String html = Files.readString(f);
        double[][] pts = {{140, 520}, {700, 540}, {500, 420}, {200, 120}, {640, 330}, {400, 300}};
        // 動き続ける @keyframes のあるページは、時刻を進めると毎フレーム全面が変わるので、時刻を止めて操作（ホバー・文字）だけを流す
        // 常時動くアニメーションと層（backdrop-filter など）があるので、部分描画に入るかは問わず、全画素一致だけを見る
        run("showcase", html, 800, 600, 120, 16, false, new String[]{"clock", "counter", "sizeOut"}, new String[]{}, pts);
    }

    /** 影・グラデーション・角丸・文字の影・クリップなど、範囲の見積もりが難しい図形を含む文書。 */
    @Test
    void richShapesWithHoverTextAndClassChanges() {
        StringBuilder sb = new StringBuilder("<html><head><style>body{margin:8px;font:14px sans-serif;background:#1e222a;color:#eee}"
                + " .c{display:inline-block;margin:6px;padding:8px 12px;border:2px solid #557;border-radius:10px;background:linear-gradient(135deg,#2a3140,#1c222d);"
                + "box-shadow:2px 4px 8px rgba(0,0,0,.45);text-shadow:1px 1px 3px #000;width:120px}"
                + " .c:hover{background:#3b465c;box-shadow:0 0 14px #08f;transform:none} .hot{background:#7a3;border-radius:22px;box-shadow:inset 0 0 8px #000}"
                + " .clip{width:150px;height:44px;overflow:hidden;border:1px solid #88a;margin:6px} .t{font-size:18px;text-shadow:0 0 6px #fff}</style></head><body>");
        for (int i = 0; i < 24; i++) {
            sb.append("<div class='c' id='c").append(i).append("'><span class='t' id='t").append(i).append("'>Card ").append(i).append("</span>")
              .append("<div class='clip'><div id='in").append(i).append("'>clipped content line ").append(i).append(" that overflows</div></div></div>");
        }
        sb.append("</body></html>");
        String[] cards = new String[24], texts = new String[24];
        for (int i = 0; i < 24; i++) { cards[i] = "c" + i; texts[i] = "t" + i; }
        double[][] pts = new double[40][];
        for (int i = 0; i < pts.length; i++) pts[i] = new double[]{25 + (i * 53) % 560, 20 + (i * 41) % 430};
        // 影・グラデーション・文字の影が多く、差分パッチに入らないことが多い。部分描画に入るかは問わず、全画素一致だけを見る
        run("rich", sb.toString(), 620, 480, 200, 16, false, texts, cards, pts);
    }

    @Test
    void listOnVulkan() {
        String[] ids = new String[40];
        for (int i = 0; i < ids.length; i++) ids[i] = "r" + (i * 3);
        double[][] pts = new double[30][];
        for (int i = 0; i < pts.length; i++) pts[i] = new double[]{20 + (i * 37) % 500, 12 + (i * 29) % 500};
        run("list", true, listHtml(60), 600, 560, 120, 16, true, ids, ids, pts);
    }
}
