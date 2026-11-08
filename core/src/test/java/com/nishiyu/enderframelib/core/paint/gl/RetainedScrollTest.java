package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * スクロールコンテナの中身を GPU の層に保持し、スクロールでは層を重ねるだけにした描画（{@link LwjglGlRenderBackend#retainedScroll}）が、
 * 毎回描き直した描画と同じ画素になること。整数画素のスクロールでは、層をずらすことと描き直すことは同じ結果になる。
 */
class RetainedScrollTest {
    private static final int W = 420, H = 420;

    private static String html(String containerStyle) {
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            rows.append("<div class=\"row\" id=\"r").append(i).append("\">row ").append(i).append(" some text ").append(i * 37 % 91).append("</div>");
        }
        return "<style>body{margin:0;font-size:13px;background:#123}.row{height:22px;margin:2px;padding:2px 6px;background:#eef;color:#223;border:1px solid #99a;"
                + "border-radius:4px}.row:nth-child(3n){background:#fed}"
                + "#outer{position:absolute;left:60px;top:90px;width:220px;height:200px;overflow-y:auto;background:#ccd;" + containerStyle + "}</style>"
                + "<div id=\"outer\">" + rows + "</div><div style=\"position:absolute;left:300px;top:20px;width:80px;height:60px;background:#c33\">side</div>";
    }

    private record Run(List<int[]> frames, long skipped, long recorded) {}

    private static void requireGl() {
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(8, 8)) {
            // 作れれば良い
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
        }
    }

    private static Run run(String html, boolean retained, long seed, int steps) {
        boolean saved = LwjglGlRenderBackend.retainedScroll, savedDrv = PaintSceneDriver.retainScrollLayers;
        LwjglGlRenderBackend.retainedScroll = true; // 整数画素への丸めは両方で有効。違うのは層を保持するか（ドライバへの問い合わせ）だけ
        PaintSceneDriver.retainScrollLayers = retained;
        StandaloneView view = new StandaloneView(html, W, H, FontResolver.FALLBACK);
        try (LwjglGlRenderBackend g = new LwjglGlRenderBackend(W, H)) {
            Random rnd = new Random(seed);
            List<int[]> out = new ArrayList<>();
            long now = 1000;
            view.frame(now, g, true);
            String sid = g.sessionId();
            view.mouseMove(150, 180, sid);
            for (int step = 0; step < steps; step++) {
                view.scroll(0, rnd.nextInt(5) - 2, sid); // 上下に 0〜2 目盛り
                now += 16;
                view.frame(now, g, true);
                out.add(g.pixmap().pixels().clone());
            }
            return new Run(out, g.scrollLayersSkipped(), g.scrollLayersRecorded());
        } finally {
            view.close();
            LwjglGlRenderBackend.retainedScroll = saved;
            PaintSceneDriver.retainScrollLayers = savedDrv;
        }
    }

    private static String bbox(int[] a, int[] b) {
        int x0 = W, y0 = H, x1 = -1, y1 = -1, n = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) { n++; x0 = Math.min(x0, i % W); x1 = Math.max(x1, i % W); y0 = Math.min(y0, i / W); y1 = Math.max(y1, i / W); }
        }
        int[] hist = new int[8];
        StringBuilder worst = new StringBuilder();
        int wv = 0;
        for (int i = 0; i < a.length; i++) {
            int d = 0;
            for (int sh = 0; sh < 32; sh += 8) d = Math.max(d, Math.abs(((a[i] >>> sh) & 0xFF) - ((b[i] >>> sh) & 0xFF)));
            hist[d == 0 ? 0 : d <= 1 ? 1 : d <= 2 ? 2 : d <= 4 ? 3 : d <= 8 ? 4 : d <= 16 ? 5 : d <= 32 ? 6 : 7]++;
            if (d > wv) { wv = d; worst = new StringBuilder(" worst " + d + " at (" + i % W + "," + i / W + ") " + Integer.toHexString(a[i]) + " vs " + Integer.toHexString(b[i])); }
        }
        return "hist[0,1,2,<=4,<=8,<=16,<=32,>32]=" + java.util.Arrays.toString(hist) + worst + " bbox (" + x0 + "," + y0 + ")-(" + x1 + "," + y1 + ") pixels " + n + " example " + Integer.toHexString(a[y0 * W + x0]) + " vs " + Integer.toHexString(b[y0 * W + x0]);
    }

    private static int maxDiff(int[] a, int[] b) {
        int max = 0;
        for (int i = 0; i < a.length; i++) {
            for (int sh = 0; sh < 32; sh += 8) max = Math.max(max, Math.abs(((a[i] >>> sh) & 0xFF) - ((b[i] >>> sh) & 0xFF)));
        }
        return max;
    }

    @Test
    void retainedScrollMatchesRedrawingForASquareContainer() {
        requireGl();
        String html = html("");
        for (long seed : new long[]{1, 2, 3}) {
            Run on = run(html, true, seed, 60), off = run(html, false, seed, 60);
            assertTrue(on.skipped() > 10, "保持した層が使われている seed " + seed + ": skipped " + on.skipped() + " recorded " + on.recorded());
            assertTrue(on.recorded() >= 1);
            assertEquals(0, off.skipped());
            for (int f = 0; f < on.frames().size(); f++) {
                if (maxDiff(on.frames().get(f), off.frames().get(f)) > 1) System.out.println("RETAIN-DIFF seed " + seed + " frame " + f + " " + bbox(on.frames().get(f), off.frames().get(f)));
                assertTrue(maxDiff(on.frames().get(f), off.frames().get(f)) <= 1, "seed " + seed + " frame " + f + " 差 " + maxDiff(on.frames().get(f), off.frames().get(f)));
            }
        }
    }

    @Test
    void retainedScrollMatchesRedrawingForARoundedContainerWithinSmallTolerance() {
        requireGl();
        String html = html("border-radius:18px;border:3px solid #446;");
        Run on = run(html, true, 4, 60), off = run(html, false, 4, 60);
        assertTrue(on.skipped() > 10, "skipped " + on.skipped());
        int worst = 0, worstCount = 0;
        for (int f = 0; f < on.frames().size(); f++) {
            int[] x = on.frames().get(f), y = off.frames().get(f);
            int d = maxDiff(x, y), over = 0;
            for (int i = 0; i < x.length; i++) {
                int di = 0;
                for (int sh = 0; sh < 32; sh += 8) di = Math.max(di, Math.abs(((x[i] >>> sh) & 0xFF) - ((y[i] >>> sh) & 0xFF)));
                if (di > 4) over++;
            }
            worst = Math.max(worst, d);
            worstCount = Math.max(worstCount, over);
        }
        // 丸い切り取りの角だけ、層に溜めてから切る場合と項目ごとに切る場合で、半透明の重なりの丸めが違う（数十画素まで）
        assertTrue(worst <= 40, "最大差 " + worst);
        assertTrue(worstCount <= 40, "差が 4 を超える画素が多い: " + worstCount);
    }
}
