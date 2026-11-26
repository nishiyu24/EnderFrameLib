package com.nishiyu.enderframelib.client.renderer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * コアのバックエンドの追い出し。同時に見える面が多いとき、描いている面を閉じると毎フレーム作り直しになる
 * （旧: 9 枚目を作るたびに一番古い面を閉じていた）。
 */
class MinecraftHostSurfaceEvictionTest {

    private static final long NOW = 100_000;

    /** 面 0..n-1 を古い順に並べ、{@code idle} 個目までは使っていない（IDLE_MS より前）、残りは今描いた面にする。 */
    private static List<String> keys(int n) {
        List<String> k = new ArrayList<>();
        for (int i = 0; i < n; i++) k.add("s" + i);
        return k;
    }

    private static Map<String, Long> used(List<String> keys, int idle) {
        Map<String, Long> m = new HashMap<>();
        for (int i = 0; i < keys.size(); i++) m.put(keys.get(i), i < idle ? NOW - MinecraftHostSurface.IDLE_MS - 1 : NOW);
        return m;
    }

    @Test
    void surfacesDrawnTogetherAreNotClosedEvenAboveTheIdleLimit() {
        // 12 枚の面が同時に見えている: どれも描いているので、1 枚も閉じない
        List<String> k = keys(12);
        Map<String, Long> u = used(k, 0);
        assertTrue(MinecraftHostSurface.evictions(k, u::get, NOW, "s11").isEmpty());
    }

    @Test
    void idleSurfacesAreClosedOldestFirstDownToTheIdleLimit() {
        // 12 枚のうち古い 6 枚は使っていない: 8 枚まで減らすので、古い 4 枚を閉じる
        List<String> k = keys(12);
        Map<String, Long> u = used(k, 6);
        assertEquals(List.of("s0", "s1", "s2", "s3"), MinecraftHostSurface.evictions(k, u::get, NOW, "s11"));
    }

    @Test
    void theHardLimitClosesTheOldestEvenWhileDrawn() {
        int n = MinecraftHostSurface.HARD_MAX + 3;
        List<String> k = keys(n);
        Map<String, Long> u = used(k, 0);
        List<String> out = MinecraftHostSurface.evictions(k, u::get, NOW, "s" + (n - 1));
        assertEquals(List.of("s0", "s1", "s2"), out);
        assertFalse(out.contains("s" + (n - 1)), "今作った面は閉じない");
    }

    @Test
    void theNewSurfaceIsNeverClosed() {
        // 今作った面が一番古い位置にあっても（呼び出し側の並びに依らず）閉じない
        List<String> k = keys(10);
        Map<String, Long> u = used(k, 10);
        List<String> out = MinecraftHostSurface.evictions(k, u::get, NOW, "s0");
        assertFalse(out.contains("s0"));
        assertEquals(2, out.size());
    }
}
