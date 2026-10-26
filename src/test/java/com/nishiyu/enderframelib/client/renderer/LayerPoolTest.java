package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.paint.model.VramBudget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** レイヤー FBO のプール（偽の描画先）。大きさはレイヤーごとに違うので、違う大きさの空きを巻き添えにしない。上限はバイト数。 */
public class LayerPoolTest {

    private final List<String> destroyed = new ArrayList<>();

    private LayerPool<String> pool(long maxBytes) {
        return new LayerPool<>(new LayerPool.Factory<>() {
            @Override
            public String create(int w, int h) {
                return w + "x" + h + "#" + System.identityHashCode(new Object());
            }

            @Override
            public void destroy(String t) {
                destroyed.add(t);
            }
        }, () -> maxBytes);
    }

    private static long bytes(int w, int h) {
        return VramBudget.layerBytes(w, h);
    }

    @Test
    void reusesSameSizedFreeTarget() {
        LayerPool<String> p = pool(1 << 20);
        String a = p.acquire(64, 64);
        p.release(a, 1);
        assertSame(a, p.acquire(64, 64));
        assertEquals(0, destroyed.size());
    }

    @Test
    void differentSizedFreeTargetsAreKeptWhileUnderTheLimit() {
        LayerPool<String> p = pool(1 << 20);
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
        LayerPool<String> p = pool(131072);
        String a = p.acquire(64, 64);
        String b = p.acquire(64, 128);
        p.release(a, 10);
        p.release(b, 20);
        String c = p.acquire(64, 96);
        assertEquals(List.of(a), destroyed, "最も古い空きだけ");
        assertEquals(1, p.freeCount());
        assertSame(b, p.acquire(64, 128));
        p.release(c, 30);
    }

    @Test
    void manySmallTargetsFitWhereOneFullScreenWouldNot() {
        // 枚数ではなくバイト数で数える: 小さい FBO は数多く持てる
        LayerPool<String> p = pool(bytes(1920, 1080));
        for (int i = 0; i < 100; i++) assertNotNull(p.acquire(64, 64));
        assertEquals(100, p.inUseCount());
        assertNull(p.acquire(1920, 1080), "全画面は入らない");
    }

    @Test
    void returnsNullWhenEverythingIsInUse() {
        LayerPool<String> p = pool(bytes(64, 64));
        p.acquire(64, 64);
        assertNull(p.acquire(128, 128));
    }

    @Test
    void trimDestroysIdleTargetsOnly() {
        LayerPool<String> p = pool(1 << 20);
        String a = p.acquire(64, 64);
        String b = p.acquire(64, 128);
        p.release(a, 0);
        p.release(b, 900);
        p.trim(1000, 500);
        assertEquals(List.of(a), destroyed);
    }
}
