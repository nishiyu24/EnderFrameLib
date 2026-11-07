package com.nishiyu.enderframelib.core.kernel.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.kernel.layout.LayoutResultCache.LayoutResult;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Milestone 3: Fenwick 仮想リスト・4 スロットキャッシュ・SIMD レイアウト演算・ヒットテスト。 */
class Milestone3KernelTest {

    // ---- FenwickVirtualList ----

    /** 素朴な配列との照合。ランダムな操作列で、全行の offset と rowAt が一致する。 */
    @Test
    void fenwickMatchesNaivePrefixSums() {
        Random rnd = new Random(42);
        FenwickVirtualList list = new FenwickVirtualList(2);
        java.util.ArrayList<Float> ref = new java.util.ArrayList<>();
        for (int step = 0; step < 4000; step++) {
            int op = rnd.nextInt(10);
            float h = rnd.nextInt(6) * 4f; // 0 を含む（高さ 0 の行）
            if (ref.isEmpty() || op < 4) {
                list.append(h);
                ref.add(h);
            } else if (op < 6) {
                int i = rnd.nextInt(ref.size());
                list.setHeight(i, h);
                ref.set(i, h);
            } else if (op < 8) {
                int i = rnd.nextInt(ref.size() + 1);
                list.insert(i, h);
                ref.add(i, h);
            } else {
                int i = rnd.nextInt(ref.size());
                list.remove(i);
                ref.remove(i);
            }
            if (step % 97 == 0) verify(list, ref, rnd);
        }
        verify(list, ref, rnd);
    }

    private static void verify(FenwickVirtualList list, java.util.List<Float> ref, Random rnd) {
        assertEquals(ref.size(), list.size());
        float acc = 0;
        for (int i = 0; i < ref.size(); i++) {
            assertEquals(acc, list.offsetOf(i), 1e-3f, "offset " + i);
            acc += ref.get(i);
        }
        assertEquals(acc, list.totalHeight(), 1e-3f);
        for (int t = 0; t < 50 && acc > 0; t++) {
            float y = rnd.nextFloat() * acc;
            int expected = 0;
            float top = 0;
            while (expected < ref.size() && top + ref.get(expected) <= y) top += ref.get(expected++);
            assertEquals(expected, list.rowAt(y), "rowAt " + y);
        }
        assertEquals(-1, list.rowAt(-1f));
        assertEquals(ref.size(), list.rowAt(acc));
    }

    @Test
    void fenwickVisibleRange() {
        FenwickVirtualList list = new FenwickVirtualList(8);
        for (int i = 0; i < 100; i++) list.append(10f);
        assertEquals(0, list.firstVisible(0));
        assertEquals(9, list.lastVisible(0, 100f)); // 下端 100 は行境界: 10 行目は見えない
        assertEquals(10, list.firstVisible(100f));
        assertEquals(10, list.lastVisible(95f, 10f));
        assertEquals(99, list.lastVisible(900f, 500f));
    }

    @Test
    void fenwickUpdateDoesNotTouchOtherRows() {
        FenwickVirtualList list = new FenwickVirtualList(4);
        for (int i = 0; i < 1000; i++) list.append(20f);
        list.setHeight(3, 120f);
        assertEquals(60f, list.offsetOf(3), 0f);
        assertEquals(180f, list.offsetOf(4), 0f);
        assertEquals(20f * 999 + 120f, list.totalHeight(), 0f);
        assertEquals(20f, list.height(500), 0f);
    }

    @Test
    void fenwickSetHeightAndOffsetAllocateNothing() {
        FenwickVirtualList list = new FenwickVirtualList(1 << 14);
        for (int i = 0; i < 10_000; i++) list.append(18f);
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().getId();
        float sink = 0;
        for (int i = 0; i < 20_000; i++) { // 暖機
            list.setHeight(i % 10_000, 18f + (i & 3));
            sink += list.offsetOf(i % 10_000);
        }
        long before = mx.getThreadAllocatedBytes(id);
        for (int i = 0; i < 100_000; i++) {
            list.setHeight(i % 10_000, 18f + (i & 3));
            sink += list.offsetOf(i % 10_000) + list.rowAt(i);
        }
        long after = mx.getThreadAllocatedBytes(id);
        assertTrue(sink != 0);
        assertTrue(after - before < 4096, "allocated " + (after - before));
    }

    // ---- LayoutResultCache ----

    @Test
    void cacheSlotsAreIndependent() {
        LayoutResultCache<String> c = new LayoutResultCache<>();
        LayoutResult a = new LayoutResult(1, 2, 3, 4, 5);
        LayoutResult b = new LayoutResult(6, 7, 8, 9, 10);
        c.put(LayoutResultCache.MIN_CONTENT, "min", a);
        c.put(LayoutResultCache.FINAL, "final", b);
        // 別条件で別スロットに書いても、他のスロットは壊れない（単一スロットなら min が消える）
        c.put(LayoutResultCache.TENTATIVE, "probe", a);
        assertSame(a, c.get(LayoutResultCache.MIN_CONTENT, "min"));
        assertSame(b, c.get(LayoutResultCache.FINAL, "final"));
        assertNull(c.get(LayoutResultCache.FINAL, "other"));
        assertNull(c.get(LayoutResultCache.MAX_CONTENT, "min"));
        c.invalidate(LayoutResultCache.FINAL);
        assertNull(c.get(LayoutResultCache.FINAL, "final"));
        assertSame(a, c.get(LayoutResultCache.TENTATIVE, "probe"));
        c.invalidateAll();
        assertNull(c.get(LayoutResultCache.MIN_CONTENT, "min"));
    }

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

    // ---- SimdHitTester ----

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
        SimdHitTester t = new SimdHitTester();
        for (int q = 0; q < 2000; q++) {
            float px = rnd.nextInt(600), py = rnd.nextInt(600);
            int expected = -1;
            for (int i = 0; i < n; i++) {
                if (px >= x[i] && px < x[i] + w[i] && py >= y[i] && py < y[i] + h[i]) expected = i;
            }
            assertEquals(expected, t.hit(x, y, w, h, n, px, py));
        }
        assertEquals(-1, t.hit(x, y, w, h, 0, 1f, 1f));
    }
}
