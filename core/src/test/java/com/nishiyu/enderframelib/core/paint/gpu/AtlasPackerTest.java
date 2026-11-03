package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 字形アトラスの棚詰め（描画 API を使わない配置器）。 */
public class AtlasPackerTest {

    private static GlyphBitmap glyph(int w, int h) {
        return new GlyphBitmap(w, h, 0, 0, new byte[w * h]);
    }

    @Test
    public void packsGlyphsOnShelvesAndWrapsToNextRow() {
        AtlasPacker atlas = new AtlasPacker(16);
        GlyphBitmap a = glyph(8, 8), b = glyph(8, 8), c = glyph(8, 8), d = glyph(8, 8);
        assertArrayEquals(new int[]{0, 0}, atlas.slot(a, null));
        assertTrue(atlas.lastPlacementWasNew());
        assertArrayEquals(new int[]{8, 0}, atlas.slot(b, null));
        assertArrayEquals(new int[]{0, 8}, atlas.slot(c, null));
        assertArrayEquals(new int[]{8, 8}, atlas.slot(d, null));
        assertEquals(4, atlas.count());
    }

    @Test
    public void sameGlyphKeepsItsSlotAndIsNotNewTheSecondTime() {
        AtlasPacker atlas = new AtlasPacker(16);
        GlyphBitmap a = glyph(4, 4);
        int[] first = atlas.slot(a, null);
        assertSame(first, atlas.slot(a, null));
        assertFalse(atlas.lastPlacementWasNew());
    }

    @Test
    public void fullAtlasFlushesThenStartsOver() {
        AtlasPacker atlas = new AtlasPacker(16);
        GlyphBitmap[] g = {glyph(8, 8), glyph(8, 8), glyph(8, 8), glyph(8, 8)};
        for (GlyphBitmap x : g) atlas.slot(x, null);
        AtomicInteger resets = new AtomicInteger();
        GlyphBitmap extra = glyph(8, 8);
        int[] slot = atlas.slot(extra, resets::incrementAndGet);
        assertEquals(1, resets.get(), "詰め直す前に、溜めた分を吐かせる");
        assertArrayEquals(new int[]{0, 0}, slot);
        assertTrue(atlas.lastPlacementWasNew());
        assertEquals(1, atlas.count(), "古い字形は忘れる");
        // 忘れた字形はもう一度置き直しになる
        atlas.slot(g[0], null);
        assertTrue(atlas.lastPlacementWasNew());
    }

    @Test
    public void rejectsEmptyAndOversizedGlyphs() {
        AtlasPacker atlas = new AtlasPacker(16);
        assertFalse(atlas.fits(glyph(0, 4)));
        assertFalse(atlas.fits(glyph(17, 4)));
        assertFalse(atlas.fits(glyph(4, 17)));
        assertTrue(atlas.fits(glyph(16, 16)));
    }

    @Test
    public void growsBeforeResettingAndKeepsExistingSlots() {
        AtlasPacker atlas = new AtlasPacker(16, 32);
        GlyphBitmap[] g = {glyph(8, 8), glyph(8, 8), glyph(8, 8), glyph(8, 8)};
        for (GlyphBitmap x : g) atlas.slot(x, null);
        assertEquals(16, atlas.size());
        AtomicInteger flushes = new AtomicInteger();
        GlyphBitmap extra = glyph(8, 8);
        int[] slot = atlas.slot(extra, flushes::incrementAndGet);
        assertEquals(1, flushes.get(), "テクスチャを作り直す前に溜めた分を吐かせる");
        assertTrue(atlas.lastPlacementGrew());
        assertEquals(32, atlas.size());
        assertArrayEquals(new int[]{0, 16}, slot, "段が尽きたので広げて次の段に置く");
        assertEquals(5, atlas.count(), "広げても字形は忘れない");
        assertArrayEquals(new int[]{8, 8}, atlas.slot(g[3], null), "既存の位置は不変");
        assertFalse(atlas.lastPlacementGrew());
        int[] n = {0};
        atlas.forEach((b, s) -> n[0]++);
        assertEquals(5, n[0]);
    }

    @Test
    public void resetsOnlyAfterReachingMaxSize() {
        AtlasPacker atlas = new AtlasPacker(8, 16);
        GlyphBitmap[] g = {glyph(8, 8), glyph(8, 8), glyph(8, 8)};
        for (GlyphBitmap x : g) atlas.slot(x, null); // 2 つ目の段で 8 → 16 に広がる
        assertEquals(16, atlas.size());
        assertEquals(3, atlas.count());
        AtomicInteger flushes = new AtomicInteger();
        atlas.slot(glyph(8, 8), flushes::incrementAndGet);
        assertEquals(1, flushes.get());
        assertEquals(1, atlas.count(), "上限で満ちたら詰め直し");
        assertEquals(16, atlas.size());
    }

    @Test
    public void wideGlyphGrowsAtlasAndFitsUsesMaxSize() {
        AtlasPacker atlas = new AtlasPacker(8, 32);
        assertTrue(atlas.fits(glyph(32, 32)));
        assertFalse(atlas.fits(glyph(33, 4)));
        int[] slot = atlas.slot(glyph(20, 4), null);
        assertTrue(atlas.lastPlacementGrew());
        assertEquals(32, atlas.size());
        assertArrayEquals(new int[]{0, 0}, slot);
    }

    @Test
    public void maxSizeCanBeLoweredToDeviceLimitButNotBelowCurrent() {
        AtlasPacker atlas = new AtlasPacker(16, 64);
        atlas.setMaxSize(8);
        assertEquals(16, atlas.maxSize());
    }
}
