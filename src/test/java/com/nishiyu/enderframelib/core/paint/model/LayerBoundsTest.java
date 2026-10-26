package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.FilterOp;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** レイヤー FBO の矩形（画面全体ではなく外接矩形 + 滲み）の計算。 */
public class LayerBoundsTest {

    @Test
    void smallIconOn4kGetsASmallRectNotTheWholeScreen() {
        // 32x32 のアイコンに opacity だけ（滲み 0）。4K でも FBO は余白込みの小ささ
        LayerBounds b = LayerBounds.of(100, 200, 164, 264, 4, 0, 3840, 2160);
        assertEquals(new LayerBounds(96, 196, 72, 72), b);
        long full = (long) 3840 * 2160;
        assertTrue((long) b.w() * b.h() * 100 < full, "全画面の 1/100 未満");
    }

    @Test
    void reachExtendsTheRectAndIsClippedToTheFrame() {
        LayerBounds b = LayerBounds.of(-500, 10, -20, 50, 0, 30, 1000, 1000);
        assertEquals(0, b.x(), "枠の外へは出ない");
        assertEquals(10, b.w(), "右端は中身の端 + reach");
        LayerBounds inner = LayerBounds.of(100, 100, 200, 200, 4, 30, 1000, 1000);
        assertEquals(new LayerBounds(66, 66, 168, 168), inner);
    }

    @Test
    void blurSamplesStopAtTheFrameEdgeOnTouchingSides() {
        // 右・上の端に接する辺は矩形の縁で止める（縁の画素を引き伸ばす）。接しない辺は FBO の端まで
        float[] c = new LayerBounds(1850, 100, 70, 65).clampUv(1920, 1080, 256, 256);
        assertArrayEquals(new float[]{0f, 0f, 70f / 256f, 1f}, c);
        assertArrayEquals(new float[]{0f, 0f, 1f, 1f}, new LayerBounds(10, 10, 70, 65).clampUv(1920, 1080, 256, 256));
    }

    @Test
    void contentFartherOffscreenThanReachIsEmpty() {
        assertEquals(LayerBounds.EMPTY, LayerBounds.of(-500, 0, -300, 50, 4, 30, 1000, 1000));
        assertTrue(LayerBounds.of(1100, 0, 1200, 50, 0, 30, 1000, 1000).isEmpty());
        assertTrue(LayerBounds.of(5000, 0, 5100, 50, 4, 0, 1000, 1000).isEmpty());
    }

    @Test
    void nonFiniteFallsBackToNull() {
        assertNull(LayerBounds.of(Float.NaN, 0, 10, 10, 4, 0, 100, 100));
        assertNull(LayerBounds.of(0, 0, Float.POSITIVE_INFINITY, 10, 4, 0, 100, 100));
    }

    @Test
    void visiblePartIsClippedToTheFrame() {
        assertArrayEquals(new int[]{10, 20, 30, 40}, new LayerBounds(10, 20, 90, 90).visible(40, 60));
        assertNull(new LayerBounds(120, 10, 20, 20).visible(100, 100));
        assertNull(LayerBounds.EMPTY.visible(100, 100));
    }

    @Test
    void bucketRoundsUpButNeverBeyondTheFrame() {
        assertEquals(128, LayerBounds.bucket(65, 1920));
        assertEquals(32, LayerBounds.bucket(1, 1920));
        assertEquals(256, LayerBounds.bucket(256, 1920));
        assertEquals(512, LayerBounds.bucket(257, 1920));
        assertEquals(1024, LayerBounds.bucket(600, 1920));
        assertEquals(1000, LayerBounds.bucket(990, 1000), "枠より大きくしない");
        assertEquals(1100, LayerBounds.bucket(1100, 1000), "矩形が枠を超えるならそのまま");
        assertEquals(1, LayerBounds.bucket(0, 1000));
    }

    @Test
    void blurReachIsAboutThreeSigmaScaled() {
        assertEquals(0, LayerBounds.reach(List.of(), 1f));
        assertEquals(0, LayerBounds.reach(List.of(FilterOp.opacity(0.5f)), 2f));
        assertEquals(31, LayerBounds.reach(List.of(FilterOp.blur(10f)), 1f));
        assertEquals(61, LayerBounds.reach(List.of(FilterOp.blur(10f)), 2f), "拡縮した箱では画素が増える");
        // drop-shadow はずれを足す
        assertEquals(30 + 8 + 1, LayerBounds.reach(List.of(FilterOp.dropShadow(8f, 3f, 10f, 0xFF000000)), 1f));
        // 段は合計
        assertEquals(30 + 15 + 1, LayerBounds.reach(List.of(FilterOp.blur(10f), FilterOp.blur(5f)), 1f));
    }

    @Test
    void reachIsCapped() {
        assertEquals(LayerBounds.MAX_REACH, LayerBounds.reach(List.of(FilterOp.blur(1e9f)), 1f));
        assertEquals(LayerBounds.MAX_REACH, LayerBounds.reach(List.of(FilterOp.blur(Float.POSITIVE_INFINITY)), 1f));
    }

    @Test
    void fullFrameKeepsTheLegacySize() {
        assertEquals(new LayerBounds(0, 0, 1920, 1080), LayerBounds.full(1920, 1080));
    }

    private static float[] apply(float[] reuse, float fx, float fy) {
        return new float[]{reuse[0] * fx + reuse[1] * fy + reuse[4], reuse[2] * fx + reuse[3] * fy + reuse[5]};
    }

    @Test
    void translatedBoxReusesThePictureShiftedByTheSameAmount() {
        float[] oldMap = {1, 0, 0, 1, 100, 50}, newMap = {1, 0, 0, 1, 130, 50};
        float[] m = LayerBounds.reuseMap(oldMap, newMap, 90, 40);
        // 新しい画素 (135, 60) は、30 右へ動く前の (105, 60)。矩形の左下 (90, 40) からの (15, 20)
        assertArrayEquals(new float[]{15f, 20f}, apply(m, 135, 60), 1e-4f);
        LayerBounds moved = LayerBounds.movedBounds(oldMap, newMap, new LayerBounds(90, 40, 50, 30));
        assertEquals(new LayerBounds(119, 39, 52, 32), moved, "30 右へ動き、縁の補間のために 1 画素ずつ広がる");
    }

    @Test
    void rotatedAndScaledBoxMapsPixelsBackToTheCachedPicture() {
        float[] oldMap = {1, 0, 0, 1, 0, 0};
        // 90 度回す: 箱の (2, 3) は新しい画素の (-3, 2)、描いたときの画素の (2, 3)
        float[] rot = {0, 1, -1, 0, 0, 0};
        assertArrayEquals(new float[]{2f, 3f}, apply(LayerBounds.reuseMap(oldMap, rot, 0, 0), -3, 2), 1e-4f);
        // 1.5 倍: 箱の (2, 3) は新しい画素の (3, 4.5)
        float[] scale = {1.5f, 0, 0, 1.5f, 0, 0};
        assertArrayEquals(new float[]{2f, 3f}, apply(LayerBounds.reuseMap(oldMap, scale, 0, 0), 3, 4.5f), 1e-4f);
    }

    @Test
    void tooDifferentScaleMirroredOrSingularMapsAreNotReused() {
        float[] oldMap = {1, 0, 0, 1, 0, 0};
        assertNull(LayerBounds.reuseMap(oldMap, new float[]{3, 0, 0, 3, 0, 0}, 0, 0), "3 倍は引き伸ばさず描き直す");
        assertNull(LayerBounds.reuseMap(oldMap, new float[]{0.3f, 0, 0, 0.3f, 0, 0}, 0, 0));
        assertNull(LayerBounds.reuseMap(oldMap, new float[]{-1, 0, 0, 1, 0, 0}, 0, 0), "裏返し");
        assertNull(LayerBounds.reuseMap(oldMap, new float[]{0, 0, 0, 0, 5, 5}, 0, 0), "つぶれた");
        assertNull(LayerBounds.reuseMap(oldMap, new float[]{Float.NaN, 0, 0, 1, 0, 0}, 0, 0));
    }
}
