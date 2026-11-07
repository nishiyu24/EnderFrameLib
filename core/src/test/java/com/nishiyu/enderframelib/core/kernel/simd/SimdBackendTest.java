package com.nishiyu.enderframelib.core.kernel.simd;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SimdBackendTest {

    private static float[] rnd(Random r, int n, float scale) {
        float[] a = new float[n];
        for (int i = 0; i < n; i++) a[i] = (r.nextFloat() - 0.3f) * scale;
        return a;
    }

    @Test
    void currentBackendWorksWithoutAnyJvmFlag() {
        SimdBackend s = SimdBackends.current();
        assertNotNull(s);
        assertTrue(s.isAvailable());
        assertSame(ScalarSimd.INSTANCE, s, "プラグインを同梱していなければ既定実装");
    }

    @Test
    void kernelsMatchReferenceForAllLengthsIncludingTails() {
        SimdBackend s = SimdBackends.current();
        Random r = new Random(42);
        for (int n : new int[] {0, 1, 7, 8, 9, 15, 16, 17, 31, 1000, 1023}) {
            float[] a = rnd(r, n, 50), b = rnd(r, n, 50), c = rnd(r, n, 50), d = rnd(r, n, 50);
            float[] out = new float[n];
            s.sum4(a, b, c, d, out, n);
            for (int i = 0; i < n; i++) assertEquals((a[i] + b[i]) + (c[i] + d[i]), out[i], 0f);

            float[] lo = rnd(r, n, 10), hi = new float[n], v = rnd(r, n, 100);
            for (int i = 0; i < n; i++) hi[i] = lo[i] + Math.abs(r.nextFloat() * 40);
            float[] expect = new float[n];
            for (int i = 0; i < n; i++) expect[i] = Math.min(Math.max(v[i], lo[i]), hi[i]);
            s.clamp(v, lo, hi, n);
            assertArrayEquals(expect, v, 0f);

            float[] f = new float[n];
            s.fma(a, b, c, f, n);
            for (int i = 0; i < n; i++) assertEquals(Math.fma(a[i], b[i], c[i]), f[i], 0f);
        }
    }

    @Test
    void sum4WritingIntoInputAliasIsSafe() {
        float[] a = {1, 2, 3, 4, 5, 6, 7, 8, 9}, z = new float[9];
        float[] expect = new float[9];
        for (int i = 0; i < 9; i++) expect[i] = a[i] * 2;
        SimdBackends.current().sum4(a, a, z, z, a, 9);
        assertArrayEquals(expect, a, 0f);
    }

    @Test
    void hitTestReturnsTopmostAndHandlesEdges() {
        SimdBackend s = SimdBackends.current();
        int n = 21;
        float[] x = new float[n], y = new float[n], w = new float[n], h = new float[n];
        for (int i = 0; i < n; i++) { x[i] = i * 10f; w[i] = 10f; h[i] = 10f; }
        assertEquals(9, s.hitTestLast(x, y, w, h, n, 95f, 5f));
        assertEquals(1, s.hitTestLast(x, y, w, h, n, 10f, 0f), "左上の辺は含む");
        assertEquals(2, s.hitTestLast(x, y, w, h, n, 20f, 0f), "右端(20)は次の矩形の左端");
        assertEquals(-1, s.hitTestLast(x, y, w, h, n, 5f, 10f), "下端は含まない");
        assertEquals(-1, s.hitTestLast(x, y, w, h, n, -1f, 5f));
        assertEquals(-1, s.hitTestLast(x, y, w, h, 0, 5f, 5f));
        // 重なりは添字の大きい方（後から描いた＝最前面）
        float[] ox = {0, 0, 0}, oy = {0, 0, 0}, ow = {10, 10, 10}, oh = {10, 10, 10};
        assertEquals(2, s.hitTestLast(ox, oy, ow, oh, 3, 5f, 5f));
        // n で打ち切る（範囲外の要素は見ない）
        assertEquals(1, s.hitTestLast(ox, oy, ow, oh, 2, 5f, 5f));
    }

    private record Fake(String name, boolean available, int priority, boolean throwsOnProbe) implements SimdBackend {
        @Override public boolean isAvailable() { if (throwsOnProbe) throw new NoClassDefFoundError("jdk/incubator/vector"); return available; }
        @Override public void sum4(float[] a, float[] b, float[] c, float[] d, float[] out, int n) {}
        @Override public void clamp(float[] v, float[] lo, float[] hi, int n) {}
        @Override public void fma(float[] a, float[] b, float[] c, float[] out, int n) {}
        @Override public int hitTestLast(float[] x, float[] y, float[] w, float[] h, int n, float px, float py) { return -1; }
    }

    @Test
    void chooseSelectsHighestAvailableAndFallsBackSafely() {
        Fake fast = new Fake("fast", true, 10, false);
        Fake faster = new Fake("faster", true, 20, false);
        Fake unavailable = new Fake("n/a", false, 99, false);
        Fake broken = new Fake("broken", true, 99, true);
        assertSame(faster, SimdBackends.choose(List.of(fast, unavailable, faster, broken)));
        assertSame(ScalarSimd.INSTANCE, SimdBackends.choose(List.of(unavailable, broken)));
        assertSame(ScalarSimd.INSTANCE, SimdBackends.choose(List.of()));
    }
}
