package com.nishiyu.enderframelib.core.scene3d;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** X3-2: Bloom の CPU 参照実装（GPU シェーダーの照合基準）。段数の計画・しきい値・広がり・対称性・合成。 */
class Bloom3DTest {

    private static float[] black(int w, int h) {
        return new float[w * h * 4];
    }

    private static void put(float[] img, int w, int x, int y, float r, float g, float b, float a) {
        int i = (y * w + x) * 4;
        img[i] = r;
        img[i + 1] = g;
        img[i + 2] = b;
        img[i + 3] = a;
    }

    private static double sum(Bloom3D.Level l, int channel) {
        double s = 0;
        for (int i = channel; i < l.rgba().length; i += 4) s += l.rgba()[i];
        return s;
    }

    @Test
    void planFollowsDualKawaseAndIsBoundedByTheImage() {
        assertTrue(Bloom3D.plan(1f, 256, 256).levels() >= 1, "少なくとも 1 段");
        assertTrue(Bloom3D.plan(64f, 256, 256).levels() > Bloom3D.plan(4f, 256, 256).levels(), "σ が大きいほど段が多い");
        assertEquals(1, Bloom3D.plan(500f, 2, 2).levels(), "小さい画像では段数を抑える");
        int levels = Bloom3D.plan(500f, 8, 8).levels();
        assertTrue((8 >> levels) >= 1, "最小の段が 1 画素を下回らない: " + levels);
        assertEquals(1, Bloom3D.half(1));
        assertEquals(4, Bloom3D.half(7));
        assertEquals(4, Bloom3D.half(8));
    }

    @Test
    void nothingBelowTheThresholdGlows() {
        int w = 32, h = 32;
        float[] img = black(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) put(img, w, x, y, 0.5f, 0.4f, 0.3f, 1f);
        Bloom3D.Level b = Bloom3D.bloom(img, w, h, 1f, 8f);
        assertEquals(0.0, sum(b, 0), 1e-6, "しきい値未満（明るさ 0.5 < 1）はブルームに出ない");
    }

    @Test
    void aBrightPointSpreadsSymmetricallyAndKeepsMostOfItsEnergy() {
        int w = 64, h = 64;
        float[] img = black(w, h);
        put(img, w, 31, 31, 20f, 20f, 20f, 1f);
        put(img, w, 32, 31, 20f, 20f, 20f, 1f);
        put(img, w, 31, 32, 20f, 20f, 20f, 1f);
        put(img, w, 32, 32, 20f, 20f, 20f, 1f); // 2×2 の明るい塊（画像の中心に対称）
        Bloom3D.Level b = Bloom3D.bloom(img, w, h, 1f, 8f);
        assertEquals(32, b.w());
        // 広がっている: 元の塊（半分の解像度で 1 画素）より外にも値がある
        int nonZero = 0;
        for (int i = 0; i < b.rgba().length; i += 4) if (b.rgba()[i] > 1e-4f) nonZero++;
        assertTrue(nonZero > 30, "広がっていない: " + nonZero);
        // 左右・上下の対称
        for (int y = 0; y < b.h(); y++) {
            for (int x = 0; x < b.w(); x++) {
                float a = b.rgba()[(y * b.w() + x) * 4], m = b.rgba()[(y * b.w() + (b.w() - 1 - x)) * 4];
                assertEquals(a, m, 1e-3f * Math.max(1f, a), "左右対称 (" + x + "," + y + ")");
                float t = b.rgba()[((b.h() - 1 - y) * b.w() + x) * 4];
                assertEquals(a, t, 1e-3f * Math.max(1f, a), "上下対称 (" + x + "," + y + ")");
            }
        }
        // 取り出したエネルギー（しきい値 1 を引いた分）が、広がっても大きくは失われない（半分の解像度なので面積比 1/4）
        double input = 4 * (20.0 - 1.0) / 4.0; // 4 画素 × 超過分 ÷ 面積比 4
        double out = sum(b, 0);
        assertTrue(out > input * 0.5 && out < input * 6.0, "エネルギー out=" + out + " 目安=" + input);
    }

    @Test
    void biggerSigmaSpreadsFurther() {
        int w = 128, h = 128;
        float[] img = black(w, h);
        put(img, w, 64, 64, 50f, 50f, 50f, 1f);
        double[] far = new double[2];
        float[] sigmas = {4f, 40f};
        for (int k = 0; k < 2; k++) {
            Bloom3D.Level b = Bloom3D.bloom(img, w, h, 1f, sigmas[k]);
            double total = sum(b, 0), farSum = 0;
            for (int y = 0; y < b.h(); y++) {
                for (int x = 0; x < b.w(); x++) {
                    if (Math.hypot(x - 32, y - 32) > 8) farSum += b.rgba()[(y * b.w() + x) * 4];
                }
            }
            far[k] = farSum / Math.max(total, 1e-9);
        }
        assertTrue(far[1] > far[0] + 0.05, "σ が大きいほど遠くへ広がる: " + far[0] + " → " + far[1]);
    }

    @Test
    void compositeWithZeroIntensityIsPlainAcesAndBloomOnlyAddsLight() {
        int w = 8, h = 8;
        float[] img = black(w, h);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) put(img, w, x, y, 0.2f, 0.5f, 3f, 1f);
        Bloom3D.Level bloom = Bloom3D.bloom(img, w, h, 1f, 4f);
        int[] a = new int[w * h], b = new int[w * h];
        Bloom3D.composite(img, w, h, bloom, 0f, a);
        int expect = (255 << 24) | (Math.round(Bloom3D.aces(0.2f) * 255) << 16) | (Math.round(Bloom3D.aces(0.5f) * 255) << 8)
                | Math.round(Bloom3D.aces(3f) * 255);
        assertEquals(expect, a[0]);
        Bloom3D.composite(img, w, h, bloom, 1f, b);
        assertTrue((b[0] & 0xFF) >= (a[0] & 0xFF), "ブルームは足すだけ（青が減らない）");
    }

    @Test
    void acesIsMonotonicBoundedAndMapsHdrIntoRange() {
        float prev = -1f;
        for (float x = 0f; x <= 16f; x += 0.05f) {
            float v = Bloom3D.aces(x);
            assertTrue(v >= prev - 1e-6f, "単調 x=" + x);
            assertTrue(v >= 0f && v <= 1f);
            prev = v;
        }
        assertEquals(0f, Bloom3D.aces(0f), 1e-6);
        assertEquals(0f, Bloom3D.aces(-1f), 0f, "負は 0");
        assertTrue(Bloom3D.aces(1f) > 0.7f && Bloom3D.aces(1f) < 0.9f, "1.0 は約 0.8: " + Bloom3D.aces(1f));
        assertTrue(Bloom3D.aces(16f) > 0.97f, "強い光は 1 へ飽和");
    }

    @Test
    void prefilterKeepsAWholeColorInProportionAndSoftensAtTheKnee() {
        float[] c = {4f, 2f, 1f, 1f};
        Bloom3D.prefilter(c, 1f, 0.5f);
        // 最大成分 4 → 超過 3 の分だけ残す（比率を保つ）: 係数 = (4-1)/4
        assertEquals(4f * 0.75f, c[0], 1e-4);
        assertEquals(2f * 0.75f, c[1], 1e-4);
        float[] dark = {0.2f, 0.2f, 0.2f, 1f};
        Bloom3D.prefilter(dark, 1f, 0.5f);
        assertEquals(0f, dark[0], 1e-6, "ひざの下は 0");
        float[] knee = {1f, 1f, 1f, 1f};
        Bloom3D.prefilter(knee, 1f, 0.5f);
        assertTrue(knee[0] > 0f && knee[0] < 0.5f, "しきい値ちょうどは滑らかに立ち上がる: " + knee[0]);
    }
}
