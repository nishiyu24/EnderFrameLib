package com.nishiyu.enderframelib.core.paint;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 世界内の面の描画順（積んだ文字との前後）と、部分的にかかる字の板の切り取り。 */
class WorldSurfaceOrderTest {

    // 板: (0,0)-(10,20)、テクスチャ (0,0)-(1,1)
    private static final float[] X = {0, 0, 10, 10};
    private static final float[] Y = {0, 20, 20, 0};
    private static final float[] U = {0, 0, 1, 1};
    private static final float[] V = {0, 1, 1, 0};

    @Test
    void quadInsideClipIsUnchanged() {
        float[] r = QuadClip.clip(X, Y, U, V, -5, -5, 50, 50);
        assertArrayEquals(X, java.util.Arrays.copyOfRange(r, 0, 4), 1e-6f);
        assertArrayEquals(V, java.util.Arrays.copyOfRange(r, 12, 16), 1e-6f);
    }

    @Test
    void quadOutsideClipIsDropped() {
        assertNull(QuadClip.clip(X, Y, U, V, 11, 0, 30, 30));
        assertNull(QuadClip.clip(X, Y, U, V, 0, 20, 30, 30));
        assertNull(QuadClip.clip(X, Y, U, V, 5, 5, 5, 15));
    }

    @Test
    void bottomHalfClippedKeepsTextureProportional() {
        float[] r = QuadClip.clip(X, Y, U, V, 0, 0, 10, 10);
        for (int i = 0; i < 4; i++) {
            assertTrue(r[4 + i] <= 10 + 1e-6f);
            // y が 20 の頂点は 10 に詰まり、v は 1 から 0.5 へ
            if (Y[i] == 20) {
                assertEquals(10, r[4 + i], 1e-6f);
                assertEquals(0.5f, r[12 + i], 1e-6f);
            } else {
                assertEquals(0, r[12 + i], 1e-6f);
            }
        }
    }

    @Test
    void leftClippedMovesUvRight() {
        float[] r = QuadClip.clip(X, Y, U, V, 4, -10, 20, 40);
        for (int i = 0; i < 4; i++) {
            if (X[i] == 0) {
                assertEquals(4, r[i], 1e-6f);
                assertEquals(0.4f, r[8 + i], 1e-6f);
            } else {
                assertEquals(10, r[i], 1e-6f);
                assertEquals(1f, r[8 + i], 1e-6f);
            }
        }
    }

    @Test
    void vertexOrderDoesNotMatter() {
        float[] x = {10, 0, 0, 10}, y = {0, 0, 20, 20}, u = {1, 0, 0, 1}, v = {0, 0, 1, 1};
        float[] r = QuadClip.clip(x, y, u, v, 0, 0, 10, 10);
        assertEquals(0.5f, r[12 + 2], 1e-6f);
        assertEquals(10, r[4 + 2], 1e-6f);
        assertEquals(0, r[12 + 1], 1e-6f);
    }

    @Test
    void pendingTextOnlyForcesFlushWhenOverlapped() {
        QuadClip.Pending p = new QuadClip.Pending();
        assertFalse(p.overlaps(0, 0, 100, 100, true), "nothing pending: never flush");
        p.add(10, 10, 50, 30);
        assertFalse(p.overlaps(60, 10, 90, 30, false), "disjoint quad keeps the batch");
        assertFalse(p.overlaps(50, 10, 90, 30, false), "touching edge is not overlap");
        assertTrue(p.overlaps(40, 20, 90, 60, false));
        assertTrue(p.overlaps(60, 60, 90, 90, true), "unknown (transformed) bounds are conservative");
        p.clear();
        assertFalse(p.overlaps(40, 20, 90, 60, true));
    }

    @Test
    void transformedPendingContentOverlapsEverything() {
        QuadClip.Pending p = new QuadClip.Pending();
        p.addEverywhere();
        assertTrue(p.overlaps(1000, 1000, 1001, 1001, false));
        for (int i = 0; i < 200; i++) p.add(i, i, i + 1, i + 1); // grows past the initial capacity
        p.clear();
        assertTrue(p.isEmpty());
        for (int i = 0; i < 200; i++) p.add(i * 3, 0, i * 3 + 1, 1);
        assertTrue(p.overlaps(597.5f, 0, 598, 1, false));
        assertFalse(p.overlaps(1.2f, 0, 1.8f, 1, false));
    }
}
