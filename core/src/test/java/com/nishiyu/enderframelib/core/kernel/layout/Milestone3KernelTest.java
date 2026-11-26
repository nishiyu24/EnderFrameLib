package com.nishiyu.enderframelib.core.kernel.layout;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

/** Milestone 3: SIMD レイアウト演算・ヒットテスト（Fenwick 仮想リストは旧経路の仮想化と一緒に M2c-3 で消した）。 */
class Milestone3KernelTest {

    // ---- SimdLayoutKernel ----

    @Test
    void edgeSumsAndClampMatchScalarReference() {
        Random rnd = new Random(7);
        int n = 1003; // 8 の倍数でない端数を含む
        float[][] f = new float[6][n];
        for (float[] col : f) for (int i = 0; i < n; i++) col[i] = rnd.nextInt(40);
        float[] scratch = new float[n], out = new float[n];
        SimdLayoutKernel k = new SimdLayoutKernel();
        k.edgeSums(f[0], f[1], f[2], f[3], f[4], f[5], scratch, out, n);
        for (int i = 0; i < n; i++) {
            assertEquals(f[0][i] + f[1][i] + f[2][i] + f[3][i] + f[4][i] + f[5][i], out[i], 0f);
        }
        float[] v = new float[n], lo = new float[n], hi = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = rnd.nextInt(300) - 50;
            lo[i] = rnd.nextInt(20);
            hi[i] = i % 5 == 0 ? Float.POSITIVE_INFINITY : lo[i] + rnd.nextInt(100);
        }
        float[] expected = new float[n];
        for (int i = 0; i < n; i++) expected[i] = Math.min(Math.max(v[i], lo[i]), hi[i]);
        k.clampContent(v, lo, hi, n);
        assertArrayEquals(expected, v, 0f);
    }

    /** 乱数の木で、行きがけ順 1 本・深さ順のスイープ・再帰の 3 通りの世界座標が一致する。 */
    @Test
    void worldCoordinateSweepsAgree() {
        Random rnd = new Random(3);
        int n = 5000;
        int[] parent = new int[n];
        short[] depth = new short[n];
        float[] rx = new float[n], ry = new float[n];
        parent[0] = -1;
        // 行きがけ順を保つ木: 直前のノードの祖先のどれかを親にする
        int[] stack = new int[n];
        int sp = 0;
        stack[sp++] = 0;
        for (int i = 1; i < n; i++) {
            sp = Math.max(1, sp - rnd.nextInt(4));
            parent[i] = stack[sp - 1];
            depth[i] = (short) (depth[parent[i]] + 1);
            stack[sp++] = i;
            rx[i] = rnd.nextInt(50);
            ry[i] = rnd.nextInt(50);
        }
        float[] wx = new float[n], wy = new float[n];
        SimdLayoutKernel.accumulateWorld(parent, rx, ry, wx, wy, n);

        int[] order = new int[n];
        int[] starts = new int[n + 2];
        int maxDepth = SimdLayoutKernel.levelOrder(depth, n, order, starts);
        float[] lx = new float[n], ly = new float[n];
        SimdLayoutKernel.accumulateWorldByLevel(parent, order, starts, maxDepth, rx, ry, lx, ly);
        assertArrayEquals(wx, lx, 0f);
        assertArrayEquals(wy, ly, 0f);

        for (int i = 0; i < n; i++) { // 親をたどる素朴な定義
            float sx = 0, sy = 0;
            for (int p = i; p >= 0; p = parent[p]) {
                sx += rx[p];
                sy += ry[p];
            }
            assertEquals(sx, wx[i], 1e-2f);
            assertEquals(sy, wy[i], 1e-2f);
        }
        // 深さごとの束: 全節が一度ずつ、深さが合っている
        boolean[] seen = new boolean[n];
        for (int d = 0; d <= maxDepth; d++) {
            for (int k = starts[d]; k < starts[d + 1]; k++) {
                assertEquals(d, depth[order[k]]);
                assertFalse(seen[order[k]]);
                seen[order[k]] = true;
            }
        }
        assertEquals(n, starts[maxDepth + 1]);
    }

    // ---- SimdBackend.hitTestLast（旧 SimdHitTester ラッパーは撤去済み） ----

    @Test
    void hitTesterReturnsTopmostAndMatchesNaive() {
        Random rnd = new Random(11);
        int n = 1000;
        float[] x = new float[n], y = new float[n], w = new float[n], h = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextInt(500);
            y[i] = rnd.nextInt(500);
            w[i] = 1 + rnd.nextInt(80);
            h[i] = 1 + rnd.nextInt(80);
        }
        var t = com.nishiyu.enderframelib.core.kernel.simd.SimdBackends.current();
        for (int q = 0; q < 2000; q++) {
            float px = rnd.nextInt(600), py = rnd.nextInt(600);
            int expected = -1;
            for (int i = 0; i < n; i++) {
                if (px >= x[i] && px < x[i] + w[i] && py >= y[i] && py < y[i] + h[i]) expected = i;
            }
            assertEquals(expected, t.hitTestLast(x, y, w, h, n, px, py));
        }
        assertEquals(-1, t.hitTestLast(x, y, w, h, 0, 1f, 1f));
    }
}
