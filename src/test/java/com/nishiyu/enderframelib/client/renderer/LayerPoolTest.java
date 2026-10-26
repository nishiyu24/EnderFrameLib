package com.nishiyu.enderframelib.client.renderer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** レイヤー FBO のプール（偽の描画先）。大きさはレイヤーごとに違うので、違う大きさの空きを巻き添えにしない。 */
public class LayerPoolTest {

    private final List<String> destroyed = new ArrayList<>();

    private LayerPool<String> pool(int max) {
        return new LayerPool<>(new LayerPool.Factory<>() {
            @Override
            public String create(int w, int h) {
                return w + "x" + h + "#" + System.identityHashCode(new Object());
            }

            @Override
            public void destroy(String t) {
                destroyed.add(t);
            }
        }, () -> max);
    }

    @Test
    void reusesSameSizedFreeTarget() {
        LayerPool<String> p = pool(4);
        String a = p.acquire(64, 64);
        p.release(a, 1);
        assertSame(a, p.acquire(64, 64));
        assertEquals(0, destroyed.size());
    }

    @Test
    void differentSizedFreeTargetsAreKeptWhileUnderTheLimit() {
        LayerPool<String> p = pool(4);
        String small = p.acquire(64, 64);
        p.release(small, 1);
        String wide = p.acquire(256, 64);
        assertNotSame(small, wide);
        assertEquals(0, destroyed.size(), "上限まで余裕があれば、違う大きさの空きは壊さない");
        assertEquals(1, p.freeCount());
        assertSame(small, p.acquire(64, 64));
    }

    @Test
    void atTheLimitOnlyTheOldestFreeTargetIsEvicted() {
        LayerPool<String> p = pool(2);
        String a = p.acquire(64, 64);
        String b = p.acquire(128, 64);
        p.release(a, 10);
        p.release(b, 20);
        String c = p.acquire(256, 256);
        assertEquals(List.of(a), destroyed, "最も古い空きだけ");
        assertEquals(1, p.freeCount());
        assertSame(b, p.acquire(128, 64));
        p.release(c, 30);
    }

    @Test
    void returnsNullWhenEverythingIsInUse() {
        LayerPool<String> p = pool(1);
        p.acquire(64, 64);
        assertNull(p.acquire(128, 128));
    }

    @Test
    void trimDestroysIdleTargetsOnly() {
        LayerPool<String> p = pool(4);
        String a = p.acquire(64, 64);
        String b = p.acquire(64, 128);
        p.release(a, 0);
        p.release(b, 900);
        p.trim(1000, 500);
        assertEquals(List.of(a), destroyed);
    }
}
