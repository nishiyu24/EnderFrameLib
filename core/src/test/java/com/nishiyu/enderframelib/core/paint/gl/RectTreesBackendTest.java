package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * バックエンドの GPU 経路（図形を Property Tree で描く）と、図形を全て CPU が描く経路（{@code rectTrees = false}）を、同じ文書で比べる。
 * 画素が（誤差内で）一致すること、新経路が実際に使われること、描画回数の収支を確かめる。
 */
class RectTreesBackendTest {
    private static final long T0 = 10_000L;
    private static final long[] REJ = new long[5];

    private static final String MIXED = "<html><body style='margin:0;font:12px sans-serif'>"
            + "<div style='position:absolute;left:10px;top:10px;width:120px;height:60px;background:#3366cc;border-radius:8px'></div>"
            + "<div style='position:absolute;left:40px;top:30px;width:120px;height:60px;background:rgba(255,128,0,.6)'></div>"
            + "<div style='position:absolute;left:20px;top:80px;width:100px;height:40px;background:#228822;opacity:.5;border-radius:20px'>text</div>"
            + "<div style='position:absolute;left:150px;top:10px;width:60px;height:60px;background:#aa2222;border:3px solid #000;border-radius:10px'></div>"
            + "<div style='position:absolute;left:30px;top:130px;width:90px;height:30px;overflow:hidden;background:#ddd;border-radius:6px'>"
            + "<div style='width:200px;height:20px;background:#888'></div></div>"
            + "</body></html>";

    private static String showcase() throws Exception {
        return Files.readString(Path.of("core/demo/resources/showcase.html"));
    }

    private static int[] render(String html, int w, int h, float dpr, boolean trees, long[] stats) {
        boolean saved = LwjglGlRenderBackend.rectTrees;
        LwjglGlRenderBackend.rectTrees = trees;
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(w, h, dpr)) {
            SoftwareRendering.renderHtml(html, w, h, T0, g);
            if (stats != null) {
                stats[0] = 0; // 旧経路は撤去済み。CPU 経路との比較なので GPU の旧描画は常に 0
                stats[1] = g.rectTreeDrawCalls();
                stats[2] = g.rectTreeShapes();
                stats[3] = g.gpuShapeCount();
                if (trees) System.arraycopy(g.rectTreeRejects(), 0, REJ, 0, 5);
            }
            return g.pixmap().pixels().clone();
        } finally {
            LwjglGlRenderBackend.rectTrees = saved;
        }
    }

    private static void requireGl() {
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(8, 8)) {
            // 作れれば良い
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
    }

    private static int maxDiff(int[] a, int[] b) {
        assertEquals(a.length, b.length);
        int max = 0;
        for (int i = 0; i < a.length; i++)
            for (int sh = 0; sh < 32; sh += 8) max = Math.max(max, Math.abs(((a[i] >>> sh) & 0xFF) - ((b[i] >>> sh) & 0xFF)));
        return max;
    }

    @Test
    void mixedDocumentMatchesTheCpuPath() {
        requireGl();
        for (float dpr : new float[]{1f, 2f}) {
            long[] st = new long[4];
            int[] on = render(MIXED, 240, 180, dpr, true, st);
            int[] off = render(MIXED, 240, 180, dpr, false, null);
            assertTrue(st[2] > 0, "新経路が使われる dpr=" + dpr);
            assertTrue(maxDiff(on, off) <= 2, "新経路と従来経路の差 dpr=" + dpr + ": " + maxDiff(on, off));
        }
    }

    private static final String BORDERS = "<html><body style='margin:0;background:#eee'>"
            + "<div style='position:absolute;left:10px;top:10px;width:80px;height:50px;border:4px solid #c00'></div>"
            + "<div style='position:absolute;left:110px;top:10px;width:80px;height:50px;border:6px solid #06c;border-radius:14px'></div>"
            + "<div style='position:absolute;left:10px;top:80px;width:80px;height:50px;border-style:solid;border-width:2px 8px 5px 12px;"
            + "border-color:#c00 #0a0 #00c #cc0;background:#fff'></div>"
            + "<div style='position:absolute;left:110px;top:80px;width:80px;height:50px;border:5px solid rgba(0,0,0,.5);"
            + "border-radius:30px/12px;background:#9cf'></div>"
            + "<div style='position:absolute;left:10px;top:150px;width:6px;height:6px;border:5px solid #333;border-radius:50%'></div>"
            + "<div style='position:absolute;left:60px;top:150px;width:80px;height:20px;border:3px solid #333;opacity:.5;"
            + "border-radius:8px;background:#fc0'></div>"
            + "<div style='position:absolute;left:150px;top:150px;width:60px;height:20px;border-top:4px solid #f00;"
            + "border-bottom:4px solid #00f;border-radius:10px 0 10px 0'></div>"
            + "</body></html>";

    @Test
    void bordersMatchTheCpuPath() {
        requireGl();
        for (float dpr : new float[]{1f, 2f}) {
            long[] st = new long[4];
            int[] on = render(BORDERS, 240, 190, dpr, true, st);
            int[] off = render(BORDERS, 240, 190, dpr, false, null);
            assertTrue(st[2] >= 7, "枠線が新経路に載る dpr=" + dpr + " (" + st[2] + ")");
            assertTrue(maxDiff(on, off) <= 2, "枠線の差 dpr=" + dpr + ": " + maxDiff(on, off));
        }
    }

    private static final String CLIPPED = "<html><body style='margin:0;background:#fafafa'>"
            + "<div style='position:absolute;left:10px;top:10px;width:90px;height:60px;overflow:hidden;border-radius:16px;background:#ddd'>"
            + "<div style='position:absolute;left:-20px;top:-10px;width:80px;height:50px;background:#c33;border:3px solid #600'></div>"
            + "<div style='position:absolute;left:50px;top:30px;width:80px;height:50px;background:rgba(0,0,200,.6);border-radius:12px'></div>"
            + "</div>"
            + "<div style='position:absolute;left:120px;top:10px;width:90px;height:60px;overflow:hidden;border-radius:30px 5px'>"
            + "<div style='width:60px;height:60px;overflow:hidden;border-radius:20px'>"
            + "<div style='width:200px;height:200px;background:#393;border:4px solid #060'></div></div></div>"
            + "<div style='position:absolute;left:10px;top:90px;width:100px;height:40px;overflow:hidden;opacity:.7'>"
            + "<div style='width:150px;height:25px;margin-left:-30px;background:#a0a;border-radius:10px'></div></div>"
            + "</body></html>";

    @Test
    void clippedShapesMatchTheCpuPath() {
        requireGl();
        for (float dpr : new float[]{1f, 2f}) {
            long[] st = new long[4];
            int[] on = render(CLIPPED, 240, 150, dpr, true, st);
            int[] off = render(CLIPPED, 240, 150, dpr, false, null);
            assertTrue(st[2] >= 5, "クリップつきも新経路に載る dpr=" + dpr + " (" + st[2] + ")");
            assertTrue(maxDiff(on, off) <= 2, "クリップつきの差 dpr=" + dpr + ": " + maxDiff(on, off));
        }
    }

    private static final String GRADIENTS = "<html><body style='margin:0;background:#fff'>"
            + "<div style='position:absolute;left:10px;top:10px;width:100px;height:50px;background:linear-gradient(90deg,#f00,#00f)'></div>"
            + "<div style='position:absolute;left:120px;top:10px;width:100px;height:50px;border-radius:14px;"
            + "background:linear-gradient(135deg,#ff0 0%,#0c0 40%,#06c 100%)'></div>"
            + "<div style='position:absolute;left:10px;top:70px;width:100px;height:50px;border-radius:25px;"
            + "background:radial-gradient(circle at 30% 30%,#fff,#369)'></div>"
            + "<div style='position:absolute;left:120px;top:70px;width:100px;height:50px;"
            + "background:conic-gradient(from 20deg,#f00,#ff0,#0f0,#0ff,#00f,#f0f,#f00)'></div>"
            + "<div style='position:absolute;left:10px;top:130px;width:100px;height:20px;opacity:.6;"
            + "background:repeating-linear-gradient(45deg,#000 0 4px,#fff 4px 8px)'></div>"
            + "<div style='position:absolute;left:120px;top:130px;width:100px;height:20px;overflow:hidden;border-radius:8px;"
            + "background:linear-gradient(#fff,#aaa)'></div>"
            + "</body></html>";

    private static final String SHADOWS = "<html><body style='margin:0;background:#f4f4f4'>"
            + "<div style='position:absolute;left:20px;top:20px;width:80px;height:50px;background:#fff;box-shadow:0 4px 10px rgba(0,0,0,.4)'></div>"
            + "<div style='position:absolute;left:130px;top:20px;width:80px;height:50px;background:#fff;border-radius:14px;"
            + "box-shadow:3px 5px 8px 2px rgba(200,0,0,.5)'></div>"
            + "<div style='position:absolute;left:20px;top:100px;width:80px;height:50px;background:#fff;border-radius:10px;"
            + "box-shadow:inset 0 3px 8px rgba(0,0,0,.5)'></div>"
            + "<div style='position:absolute;left:130px;top:100px;width:80px;height:50px;background:#9cf;"
            + "box-shadow:0 0 12px #06c,inset 0 0 6px #fff'></div>"
            + "<div style='position:absolute;left:20px;top:170px;width:80px;height:20px;background:#fff;opacity:.7;"
            + "box-shadow:0 2px 6px rgba(0,0,0,.5)'></div>"
            + "</body></html>";

    @Test
    void gradientsMatchTheCpuPath() {
        requireGl();
        for (float dpr : new float[]{1f, 2f}) {
            long[] st = new long[4];
            int[] on = render(GRADIENTS, 240, 160, dpr, true, st);
            int[] off = render(GRADIENTS, 240, 160, dpr, false, null);
            assertTrue(st[2] >= 5, "グラデーションが新経路に載る dpr=" + dpr + " (" + st[2] + ")");
            assertTrue(maxDiff(on, off) <= 2, "グラデーションの差 dpr=" + dpr + ": " + maxDiff(on, off));
        }
    }

    @Test
    void shadowsMatchTheCpuPath() {
        requireGl();
        for (float dpr : new float[]{1f, 2f}) {
            long[] st = new long[4];
            int[] on = render(SHADOWS, 240, 200, dpr, true, st);
            int[] off = render(SHADOWS, 240, 200, dpr, false, null);
            assertTrue(st[2] >= 6, "影が新経路に載る dpr=" + dpr + " (" + st[2] + ")");
            assertTrue(maxDiff(on, off) <= 2, "影の差 dpr=" + dpr + ": " + maxDiff(on, off));
        }
    }

    private static final String DASHED = "<html><body style='margin:0;background:#fff'>"
            + "<div style='position:absolute;left:10px;top:10px;width:90px;height:50px;border:3px dashed #c00'></div>"
            + "<div style='position:absolute;left:120px;top:10px;width:90px;height:50px;border:3px dotted #06c;border-radius:16px'></div>"
            + "<div style='position:absolute;left:10px;top:80px;width:90px;height:50px;border:5px dashed #090;border-radius:25px/12px'></div>"
            + "<div style='position:absolute;left:120px;top:80px;width:90px;height:50px;border:2px dashed #333;opacity:.6;background:#fe8'></div>"
            + "<div style='position:absolute;left:10px;top:150px;width:90px;height:30px;border-style:dashed;border-width:2px 6px 4px 8px;"
            + "border-color:#c00 #0a0 #00c #cc0;overflow:hidden;border-radius:6px'></div>"
            + "</body></html>";

    @Test
    void dashedBordersMatchTheCpuPath() {
        requireGl();
        for (float dpr : new float[]{1f, 2f}) {
            long[] st = new long[4];
            int[] on = render(DASHED, 240, 200, dpr, true, st);
            int[] off = render(DASHED, 240, 200, dpr, false, null);
            assertTrue(st[2] >= 5, "破線枠が新経路に載る dpr=" + dpr + " (" + st[2] + ")");
            assertTrue(maxDiff(on, off) <= 2, "破線枠の差 dpr=" + dpr + ": " + maxDiff(on, off));
        }
    }

    @Test
    void showcaseMatchesTheCpuPath() throws Exception {
        requireGl();
        String html = showcase();
        long[] on = new long[4], off = new long[4];
        int[] a = render(html, 900, 700, 1f, true, on);
        int[] b = render(html, 900, 700, 1f, false, off);
        System.out.println("RECT-TREES showcase: rects on trees=" + on[2] + ", draws legacy-path " + on[0] + " + tree " + on[1]
                + " (all-legacy draws " + off[0] + ", shapes " + off[3] + ", glyphs/images n/a), rejects[pose,grid,corner,cap]=" + java.util.Arrays.toString(REJ) + " clip=" + REJ[4] + ", maxDiff=" + maxDiff(a, b));
        try {
        for (int bit = 0; bit < 12; bit++) {
            GlRectStream.opMask = 1 << bit;
            int[] only = render(html, 900, 700, 1f, true, null);
            int w = 0, n = 0;
            for (int i = 0; i < only.length; i++) {
                int dm = 0;
                for (int sh = 0; sh < 32; sh += 8) dm = Math.max(dm, Math.abs(((only[i] >>> sh) & 0xFF) - ((b[i] >>> sh) & 0xFF)));
                if (dm > 2) n++;
                w = Math.max(w, dm);
            }
            System.out.println("RECT-TREES op" + bit + " only: worst " + w + ", pixels>2 " + n);
            // 残りは CPU が描く。GPU と CPU の層を重ねるたびの 8 bit の丸めで、影のぼかしなどで少数の画素が 3〜4 になりうる（全種類のときの上限と同じ）
            assertTrue(w <= 4 && n <= 200, "図形の種類 " + bit + " だけを GPU にした差: worst " + w + ", pixels>2 " + n);
        }
        } finally {
            GlRectStream.opMask = 0xFFF; // 途中で失敗しても、他のテストへ残さない
        }
        int worst = 0, wi = -1, bad = 0;
        for (int i = 0; i < a.length; i++) {
            int dmax = 0;
            for (int sh = 0; sh < 32; sh += 8) dmax = Math.max(dmax, Math.abs(((a[i] >>> sh) & 0xFF) - ((b[i] >>> sh) & 0xFF)));
            if (dmax > 2) bad++;
            if (dmax > worst) { worst = dmax; wi = i; }
        }
        System.out.println("RECT-TREES worst diff " + worst + " at (" + wi % 900 + "," + wi / 900 + ") trees=" + Integer.toHexString(a[wi])
                + " legacy=" + Integer.toHexString(b[wi]) + " pixels>2: " + bad);
        // 全種類を GPU にすると、CPU 描画との間で影のぼかし・層ごとの 8 bit の丸めが違い、約 0.1%（630,000 画素中 500 前後）の画素が 3〜4 になる
        assertTrue(worst <= 4 && bad <= 800, "全種類の差: worst " + worst + ", 2 超の画素 " + bad);
    }

    /** 定常状態のフレーム（同じ文書を描き直す）の CPU 時間と GPU への転送量を、CPU 経路と GPU 経路で比べて出す（診断。数値は環境による）。 */
    @Test
    void steadyFrameCostComparedWithTheCpuPath() throws Exception {
        requireGl();
        String html = showcase();
        double[] ms = new double[2];
        for (int mode = 0; mode < 2; mode++) {
            boolean saved = LwjglGlRenderBackend.rectTrees;
            LwjglGlRenderBackend.rectTrees = mode == 1;
            try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(900, 700)) {
                for (int i = 0; i < 30; i++) SoftwareRendering.renderHtml(html, 900, 700, T0, g);
                long t0 = System.nanoTime();
                int n = 100;
                for (int i = 0; i < n; i++) SoftwareRendering.renderHtml(html, 900, 700, T0, g);
                g.pixmap(); // GPU の仕事の完了まで含める
                ms[mode] = (System.nanoTime() - t0) / 1e6 / n;
            } finally {
                LwjglGlRenderBackend.rectTrees = saved;
            }
        }
        System.out.println("RECT-TREES frame: cpu " + String.format("%.2f", ms[0]) + " ms; gpu trees "
                + String.format("%.2f", ms[1]) + " ms (instance data is written in place by the persistent mapping)");
    }

    @Test
    void steadyFramesUploadNothingToTheTrees() {
        requireGl();
        boolean saved = LwjglGlRenderBackend.rectTrees;
        LwjglGlRenderBackend.rectTrees = true;
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(240, 180)) {
            SoftwareRendering.renderHtml(MIXED, 240, 180, T0, g);
            int[] first = g.pixmap().pixels().clone();
            long shapes = g.rectTreeShapes();
            SoftwareRendering.renderHtml(MIXED, 240, 180, T0, g);
            assertArrayEquals(first, g.pixmap().pixels());
            assertEquals(shapes * 2, g.rectTreeShapes(), "2 回目も同じ数を受け付ける");
        } finally {
            LwjglGlRenderBackend.rectTrees = saved;
        }
    }
}
