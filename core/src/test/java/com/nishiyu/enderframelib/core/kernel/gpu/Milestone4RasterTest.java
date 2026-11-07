package com.nishiyu.enderframelib.core.kernel.gpu;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class Milestone4RasterTest {

    private record Cmd(int x, int y, int w, int h, float r, int argb, float op, int cx0, int cy0, int cx1, int cy1) {}

    /** 素朴な参照: 画素ごとに命令を順に当てる（タイルを知らない）。 */
    private static int[] reference(int W, int H, List<Cmd> cmds) {
        int[] px = new int[W * H];
        for (Cmd c : cmds) {
            float r = Math.max(0f, Math.min(c.r, Math.min(c.w, c.h) * 0.5f));
            float base = ((c.argb >>> 24) / 255f) * Math.min(1f, c.op);
            for (int y = Math.max(0, Math.max(c.y, c.cy0)); y < Math.min(H, Math.min(c.y + c.h, c.cy1)); y++) {
                for (int x = Math.max(0, Math.max(c.x, c.cx0)); x < Math.min(W, Math.min(c.x + c.w, c.cx1)); x++) {
                    // 角の正方形（半径 r）の内側だけ、角の円までの距離から被覆率を出す
                    float fx = x + 0.5f, fy = y + 0.5f, cov = 1f;
                    float qx = fx < c.x + r ? c.x + r : (fx > c.x + c.w - r ? c.x + c.w - r : fx);
                    float qy = fy < c.y + r ? c.y + r : (fy > c.y + c.h - r ? c.y + c.h - r : fy);
                    if (qx != fx && qy != fy) {
                        float d = (float) Math.hypot(fx - qx, fy - qy) - r;
                        cov = Math.max(0f, Math.min(1f, 0.5f - d));
                    }
                    float a = base * cov;
                    if (a <= 0f) continue;
                    int d0 = px[y * W + x];
                    int[] sh = {24, 16, 8, 0};
                    int out = 0;
                    for (int k = 0; k < 4; k++) {
                        float s = k == 0 ? 255f * a : ((c.argb >> sh[k]) & 0xFF) * a;
                        float v = s + ((d0 >>> sh[k]) & 0xFF) * (1f - a);
                        out |= ((int) (v + 0.5f) & 0xFF) << sh[k];
                    }
                    px[y * W + x] = out;
                }
            }
        }
        return px;
    }

    private static void assertClose(int[] expect, int[] actual, int tol, String msg) {
        assertEquals(expect.length, actual.length);
        for (int i = 0; i < expect.length; i++) {
            for (int sh = 0; sh < 32; sh += 8) {
                int a = (expect[i] >>> sh) & 0xFF, b = (actual[i] >>> sh) & 0xFF;
                if (Math.abs(a - b) > tol) fail(msg + ": 画素 " + i + " ch" + sh + " 期待 " + a + " 実際 " + b);
            }
        }
    }

    private static List<Cmd> randomCmds(Random r, int W, int H, int n) {
        List<Cmd> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int w = 1 + r.nextInt(150), h = 1 + r.nextInt(150);
            int x = r.nextInt(W + 40) - 20, y = r.nextInt(H + 40) - 20;
            boolean clip = r.nextInt(3) == 0;
            l.add(new Cmd(x, y, w, h, r.nextInt(4) == 0 ? 0f : r.nextFloat() * 40, r.nextInt() | (r.nextInt(5) == 0 ? 0xFF000000 : 0),
                    r.nextBoolean() ? 1f : r.nextFloat(),
                    clip ? r.nextInt(W / 2) : 0, clip ? r.nextInt(H / 2) : 0, clip ? W / 2 + r.nextInt(W / 2) : W, clip ? H / 2 + r.nextInt(H / 2) : H));
        }
        return l;
    }

    private static void submit(SimdTileSoftwareRenderer rr, List<Cmd> cmds) {
        for (Cmd c : cmds) rr.fillRoundRect(c.x, c.y, c.w, c.h, c.r, c.argb, c.op, c.cx0, c.cy0, c.cx1, c.cy1);
    }

    @Test
    void tileRendererMatchesThePerPixelReferenceOverRandomScenes() {
        for (int seed = 1; seed <= 12; seed++) {
            Random r = new Random(seed);
            int W = 50 + r.nextInt(250), H = 40 + r.nextInt(250); // タイル幅の倍数でない大きさも
            List<Cmd> cmds = randomCmds(r, W, H, 60);
            SimdTileSoftwareRenderer rr = new SimdTileSoftwareRenderer(W, H);
            submit(rr, cmds);
            rr.render();
            assertClose(reference(W, H, cmds), rr.pixels(), 1, "seed " + seed + " " + W + "x" + H);
        }
    }

    @Test
    void commandOrderIsKeptAcrossOverlapsAndTilesAreIndependent() {
        int W = 200, H = 130;
        List<Cmd> cmds = new ArrayList<>(List.of(
                new Cmd(10, 10, 180, 100, 12f, 0xFFFF0000, 1f, 0, 0, W, H),
                new Cmd(60, 40, 100, 60, 0f, 0x8000FF00, 1f, 0, 0, W, H),
                new Cmd(0, 0, 200, 130, 0f, 0x400000FF, 0.5f, 0, 0, W, H)));
        SimdTileSoftwareRenderer a = new SimdTileSoftwareRenderer(W, H);
        submit(a, cmds);
        a.render();
        assertClose(reference(W, H, cmds), a.pixels(), 1, "order");
        // 逆順で個別タイル描画しても同じ（タイル同士は独立）。
        SimdTileSoftwareRenderer b = new SimdTileSoftwareRenderer(W, H);
        submit(b, cmds);
        java.lang.reflect.Method bin;
        try {
            bin = SimdTileSoftwareRenderer.class.getDeclaredMethod("bin");
            bin.setAccessible(true);
            bin.invoke(b);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        for (int t = b.tileCount() - 1; t >= 0; t--) b.renderTile(t);
        assertArrayEquals(a.pixels(), b.pixels());
    }

    @Test
    void roundedCornersAreTransparentAndOpaqueFillIsExact() {
        SimdTileSoftwareRenderer rr = new SimdTileSoftwareRenderer(40, 40);
        rr.fillRoundRect(0, 0, 40, 40, 20f, 0xFF336699, 1f); // 円
        rr.render();
        assertEquals(0, rr.pixels()[0] >>> 24, "角は透明");
        assertEquals(0xFF336699, rr.pixels()[20 * 40 + 20], "中心は不透明な指定色そのまま");
        assertEquals(0xFF336699, rr.pixels()[20 * 40 + 1] | 0, "辺の中央は塗られる");
    }

    @Test
    void clipRestrictsPaintingAndEmptyScenesAreNoOps() {
        SimdTileSoftwareRenderer rr = new SimdTileSoftwareRenderer(100, 100);
        rr.fillRoundRect(0, 0, 100, 100, 0f, 0xFFFFFFFF, 1f, 30, 30, 60, 60);
        rr.render();
        int painted = 0;
        for (int y = 0; y < 100; y++) for (int x = 0; x < 100; x++) {
            boolean in = x >= 30 && x < 60 && y >= 30 && y < 60;
            assertEquals(in, rr.pixels()[y * 100 + x] != 0);
            if (in) painted++;
        }
        assertEquals(900, painted);
        rr.clear();
        rr.render();
        for (int p : rr.pixels()) assertEquals(0, p);
    }

    @Test
    void steadyStateRenderAllocatesNothing() {
        Random r = new Random(5);
        List<Cmd> cmds = randomCmds(r, 512, 512, 200);
        SimdTileSoftwareRenderer rr = new SimdTileSoftwareRenderer(512, 512);
        for (int i = 0; i < 30; i++) { rr.clear(); submit(rr, cmds); rr.render(); }
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(tid);
        for (int i = 0; i < 30; i++) { rr.clear(); submit(rr, cmds); rr.render(); }
        long used = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(used < 8192, "ソフトウェア合成がヒープを確保している: " + used + " B");
    }

    // ---- LayerAtlas ---------------------------------------------------------------------------

    @Test
    void atlasRectsNeverOverlapStayInBoundsAndAreReusedAfterRelease() {
        LayerAtlas atlas = new LayerAtlas(2048);
        Random r = new Random(3);
        List<int[]> live = new ArrayList<>(); // handle, x, y, w, h
        for (int step = 0; step < 3000; step++) {
            if (!live.isEmpty() && r.nextInt(5) < 2) {
                int[] e = live.remove(r.nextInt(live.size()));
                atlas.release(e[0]);
            } else {
                int w = 1 + r.nextInt(300), h = 1 + r.nextInt(200);
                int hd = atlas.acquire(w, h);
                if (hd < 0) continue;
                assertEquals(w, atlas.w(hd));
                assertEquals(h, atlas.h(hd));
                int x = atlas.x(hd), y = atlas.y(hd);
                assertTrue(x >= LayerAtlas.GUTTER && y >= LayerAtlas.GUTTER && x + w + LayerAtlas.GUTTER <= 2048 && y + h + LayerAtlas.GUTTER <= 2048);
                for (int[] o : live) { // 余白込みの矩形同士が重ならない
                    boolean disjoint = x + w + LayerAtlas.GUTTER <= o[1] - LayerAtlas.GUTTER || o[1] + o[3] + LayerAtlas.GUTTER <= x - LayerAtlas.GUTTER
                            || y + h + LayerAtlas.GUTTER <= o[2] - LayerAtlas.GUTTER || o[2] + o[4] + LayerAtlas.GUTTER <= y - LayerAtlas.GUTTER;
                    assertTrue(disjoint, "重なり step " + step);
                }
                live.add(new int[]{hd, x, y, w, h});
            }
        }
        for (int[] e : live) atlas.release(e[0]);
        assertEquals(0L, atlas.usedPixels());
        assertTrue(atlas.acquire(2046, 2046) >= 0, "全部返せば最大の矩形がまた置ける（棚が畳まれ空きが併合される）");
    }

    @Test
    void atlasRejectsOversizeAndReportsFull() {
        LayerAtlas atlas = new LayerAtlas(256);
        assertEquals(-1, atlas.acquire(255, 10), "余白込みで 256 を超える");
        assertEquals(-1, atlas.acquire(0, 5));
        int a = atlas.acquire(254, 254);
        assertTrue(a >= 0);
        assertEquals(-1, atlas.acquire(10, 10), "満杯");
        atlas.release(a);
        assertTrue(atlas.acquire(10, 10) >= 0);
        assertThrows(IllegalStateException.class, () -> atlas.release(4000));
    }

    // ---- DualKawase ---------------------------------------------------------------------------

    private static float measuredSigma(float[] img, int n) {
        double sum = 0, m2 = 0;
        int c = n / 2;
        for (int x = 0; x < n; x++) { double v = img[c * n + x]; sum += v; m2 += v * (x - c) * (x - c); }
        return (float) Math.sqrt(m2 / sum);
    }

    @Test
    void kawasePlanReachesTheRequestedSigma() {
        assertEquals(0, DualKawase.passes(0.3f));
        int prev = 0;
        for (float sigma : new float[]{2f, 4f, 8f, 16f, 32f, 64f}) {
            int p = DualKawase.passes(sigma);
            assertTrue(p >= prev && p <= DualKawase.MAX_PASSES, "段数は σ に単調 σ=" + sigma);
            prev = p;
            float o = DualKawase.offset(sigma, p);
            int n = 1025;
            float[] img = new float[n * n];
            img[(n / 2) * n + n / 2] = 1f;
            float[] out = DualKawase.blur(img, n, n, p, o);
            float measured = measuredSigma(out, n);
            assertEquals(sigma, measured, sigma * 0.35f, "σ=" + sigma + " passes=" + p + " offset=" + o + " 実測 " + measured);
            assertEquals(sigma, DualKawase.effectiveSigma(p, o), sigma * 0.35f);
        }
    }

    @Test
    void kawaseKeepsFlatImagesFlatAndConservesMass() {
        int w = 96, h = 64;
        float[] flat = new float[w * h];
        java.util.Arrays.fill(flat, 0.6f);
        for (float v : DualKawase.blur(flat, w, h, 3, 1.2f)) assertEquals(0.6f, v, 1e-4f);
        float[] img = new float[w * h];
        img[32 * w + 48] = 1000f;
        double sum = 0;
        for (float v : DualKawase.blur(img, w, h, 2, 1f)) sum += v;
        assertEquals(1000.0, sum, 1000 * 0.03, "端に届かない範囲では総量が保たれる");
        assertArrayEquals(img, DualKawase.blur(img, w, h, 0, 0f));
    }
}
