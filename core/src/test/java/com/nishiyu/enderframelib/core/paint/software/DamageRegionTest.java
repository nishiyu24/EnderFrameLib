package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link DamageRegion} の外接矩形・タイルの印・{@link DamageRegion#seal()} のタイル境界への拡大。 */
class DamageRegionTest {

    @Test
    void addClampsToCanvasAndIgnoresEmptyRanges() {
        DamageRegion d = new DamageRegion();
        d.reset(100, 70);
        assertTrue(d.isEmpty());
        assertNull(d.bounds());
        d.add(10, 10, 10, 20); // 幅 0
        d.add(100, 0, 120, 10);  // 画面の外
        assertTrue(d.isEmpty());
        assertEquals(0, d.dirtyTileCount());

        d.add(-10, -20, 20, 30);
        assertArrayEquals(new int[]{0, 0, 20, 30}, d.bounds());
        assertTrue(d.isTileDirty(0, 0));
        assertFalse(d.isTileDirty(1, 0));
        assertEquals(1, d.dirtyTileCount());
        assertTrue(d.intersects(19, 29, 40, 40));
        assertFalse(d.intersects(20, 0, 40, 10));
        assertTrue(d.covers(0, 0, 20, 30));
        assertFalse(d.covers(0, 0, 21, 30));
    }

    @Test
    void sealExpandsBoundsToWholeTiles() {
        DamageRegion d = new DamageRegion();
        d.reset(130, 130);
        d.add(10, 20, 70, 80);
        assertArrayEquals(new int[]{10, 20, 70, 80}, d.bounds());
        assertEquals(4, d.dirtyTileCount()); // 2×2 タイルに掛かる
        d.seal();
        assertArrayEquals(new int[]{0, 0, 128, 128}, d.bounds());
        assertEquals(4, d.dirtyTileCount());
        for (int ty = 0; ty < 2; ty++) {
            for (int tx = 0; tx < 2; tx++) assertTrue(d.isTileDirty(tx, ty), "tile " + tx + "," + ty);
        }
        assertFalse(d.isTileDirty(2, 0));
        assertFalse(d.isTileDirty(0, 2));
        assertEquals(3, d.tilesX());
        assertEquals(3, d.tilesY());
        // 広げた外接矩形は、掛かっていたタイルをすべて覆う
        assertTrue(d.covers(0, 0, 128, 128));
        assertFalse(d.covers(0, 0, 129, 128));
    }

    @Test
    void sealClampsAtTheCanvasEdge() {
        DamageRegion d = new DamageRegion();
        d.reset(100, 100);
        d.add(63, 63, 65, 65);
        assertEquals(4, d.dirtyTileCount());
        d.seal();
        assertArrayEquals(new int[]{0, 0, 100, 100}, d.bounds()); // 128 ではなく描画先の端
        assertEquals(4, d.dirtyTileCount());
        assertTrue(d.covers(99, 99, 100, 100));
    }

    @Test
    void resetClearsTilesAndBounds() {
        DamageRegion d = new DamageRegion();
        d.reset(64, 64);
        d.add(1, 1, 63, 63);
        assertEquals(1, d.dirtyTileCount());
        d.reset(64, 64);
        assertTrue(d.isEmpty());
        assertEquals(0, d.dirtyTileCount());
        assertFalse(d.isTileDirty(0, 0));
        // 同じタイル数の大きさへ戻したときも、前の印が残らない
        d.add(0, 0, 1, 1);
        d.reset(64, 64);
        assertEquals(0, d.dirtyTileCount());
    }

    @Test
    void multipleAddsUnionIntoTilesAndBounds() {
        DamageRegion d = new DamageRegion();
        d.reset(200, 100);
        d.add(5, 5, 10, 10);
        d.add(120, 70, 180, 90);
        assertArrayEquals(new int[]{5, 5, 180, 90}, d.bounds());
        assertEquals(3, d.dirtyTileCount()); // (0,0)・(1,1)・(2,1)
        assertTrue(d.isTileDirty(0, 0));
        assertTrue(d.isTileDirty(1, 1));
        assertTrue(d.isTileDirty(2, 1));
        assertFalse(d.isTileDirty(1, 0));
        assertFalse(d.isTileDirty(3, 1));
        d.seal();
        assertArrayEquals(new int[]{0, 0, 192, 100}, d.bounds());
        assertEquals(6, d.dirtyTileCount()); // 3×2 タイルの外接矩形
    }
}
