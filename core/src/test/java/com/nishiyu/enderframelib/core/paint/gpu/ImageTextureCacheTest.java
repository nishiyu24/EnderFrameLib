package com.nishiyu.enderframelib.core.paint.gpu;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 画像テクスチャ LRU（描画 API を使わない管理表）。 */
public class ImageTextureCacheTest {

    @Test
    public void evictsOnlyLeastRecentlyUsed() {
        ImageTextureCache<Object> c = new ImageTextureCache<>(3);
        Object a = new Object(), b = new Object(), d = new Object();
        c.put(a, 1, 0);
        c.put(b, 2, 0);
        c.put(d, 3, 0);
        assertTrue(c.isFull());
        c.get(a); // a を使った → 最古は b
        ImageTextureCache.Entry ev = c.evictOldest();
        assertEquals(2, ev.texture);
        assertEquals(2, c.size());
        assertNotNull(c.get(a));
        assertNotNull(c.get(d));
        assertNull(c.get(b));
    }

    @Test
    public void notFullAfterEvictionAndKeepsOthers() {
        ImageTextureCache<Object> c = new ImageTextureCache<>(2);
        Object a = new Object(), b = new Object();
        c.put(a, 10, 0);
        c.put(b, 11, 0);
        assertEquals(10, c.evictOldest().texture);
        assertFalse(c.isFull());
        assertEquals(11, c.get(b).texture);
    }

    @Test
    public void keysCompareByIdentity() {
        ImageTextureCache<String> c = new ImageTextureCache<>(4);
        String k1 = new String("x");
        String k2 = new String("x");
        c.put(k1, 1, 0);
        assertNull(c.get(k2));
        assertSame(c.get(k1), c.get(k1));
    }

    @Test
    public void versionIsMutableAndClearReturnsTextures() {
        ImageTextureCache<Object> c = new ImageTextureCache<>(4);
        Object a = new Object(), b = new Object();
        ImageTextureCache.Entry e = c.put(a, 5, 1);
        c.put(b, 6, 1);
        e.version = 2;
        assertEquals(2, c.get(a).version);
        List<Integer> ids = c.clear();
        assertEquals(2, ids.size());
        assertTrue(ids.contains(5) && ids.contains(6));
        assertEquals(0, c.size());
        assertNull(c.evictOldest());
    }
}
