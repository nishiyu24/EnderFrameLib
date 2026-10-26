package com.nishiyu.enderframelib.core.text.font;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字形の置き場の VRAM の見積もりと、実際の書体で測った使用量（レイヤー合成の FBO の予算を決める根拠）。
 */
public class AtlasFootprintTest {

    @Test
    void emptyAtlasHoldsNoTexture() {
        AtlasFootprint f = new AtlasFootprint();
        assertEquals(0, f.estimatedSheets());
        assertEquals(0, f.estimatedBytes());
    }

    @Test
    void oneGlyphTakesOneSheet() {
        AtlasFootprint f = new AtlasFootprint();
        f.recordGlyph(10, 12);
        assertEquals(1, f.estimatedSheets());
        assertEquals(256 * 256, f.estimatedBytes());
        assertEquals(120, f.glyphPixels());
    }

    @Test
    void sheetsGrowWithGlyphArea() {
        AtlasFootprint f = new AtlasFootprint();
        for (int i = 0; i < 1000; i++) f.recordGlyph(16, 16);
        // 256000px × 1.3 / 65536 → 6 枚
        assertEquals(6, f.estimatedSheets());
    }

    @Test
    void atlasCacheSumsAndEvictsOnlyUnprotected() {
        AtomicLong now = new AtomicLong();
        List<String> released = new ArrayList<>();
        AtlasCache<String, String> c = new AtlasCache<>(64, 2_000_000_000L, 30_000_000_000L, now::get, released::add);
        c.get("aa", k -> k);
        now.addAndGet(3_000_000_000L);
        c.get("bbbb", k -> k);
        assertEquals(6, c.sum(String::length));
        assertEquals(1, c.evictUnprotected(), "3 秒前に使った置き場だけが保護の外");
        assertEquals(List.of("aa"), released);
        assertEquals(4, c.sum(String::length));
    }

    /**
     * 実際の書体で、典型的な文書（ASCII + 日本語 2000 字 × 3 つの大きさ × 明暗 2 × 端数 4）を焼いたときの使用量。
     * 簡単な棚詰め（高さ順）で必要なテクスチャの枚数を求め、見積もりが実際を下回らないことを確かめる。
     */
    @Test
    void estimateCoversShelfPackedGlyphsOfARealFont() {
        SystemFonts system = SystemFonts.system().withLocale(Locale.ENGLISH);
        String name = system.hasFamily("Arial") ? "Arial" : system.hasFamily("DejaVu Sans") ? "DejaVu Sans" : null;
        Assumptions.assumeTrue(name != null, "Arial / DejaVu Sans が無い環境");
        SystemFonts.Face record = system.family(name).stream()
                .filter(f -> f.weight() == 400 && !f.italic()).findFirst().orElseThrow();
        LoadedFace face = LoadedFace.open(record);

        AtlasFootprint footprint = new AtlasFootprint();
        List<int[]> sizes = new ArrayList<>();
        int[] cps = new int[95 + 400];
        int n = 0;
        for (int cp = 0x21; cp < 0x7F; cp++) cps[n++] = cp;
        for (int cp = 0x4E00; n < cps.length; cp++) if (face.hasGlyph(cp)) cps[n++] = cp; else if (cp > 0x9FFF) break;
        for (float px : new float[]{13f, 16f, 26f}) {
            for (int i = 0; i < n; i++) {
                for (int sub = 0; sub < LoadedFace.SUBPIXEL_STEPS; sub++) {
                    LoadedFace.GlyphBitmap b = face.rasterize(cps[i], px, false, false, sub);
                    if (b.isEmpty()) continue;
                    footprint.recordGlyph(b.width(), b.height());
                    sizes.add(new int[]{b.width() + 1, b.height() + 1});
                }
            }
        }
        assertTrue(footprint.glyphCount() > 0);
        int packed = shelfSheets(sizes);
        System.out.printf("[atlas] glyphs=%d pixels=%d estimatedSheets=%d (%d KiB) shelfPackedSheets=%d%n",
                footprint.glyphCount(), footprint.glyphPixels(), footprint.estimatedSheets(),
                footprint.estimatedBytes() / 1024, packed);
        assertTrue(footprint.estimatedSheets() >= packed,
                "見積もり " + footprint.estimatedSheets() + " 枚が棚詰めの " + packed + " 枚を下回る");
    }

    private static int shelfSheets(List<int[]> sizes) {
        sizes.sort(Comparator.comparingInt((int[] s) -> s[1]).reversed());
        int sheets = 1, x = 0, y = 0, shelfH = 0;
        int size = AtlasFootprint.SHEET_SIZE;
        for (int[] s : sizes) {
            if (x + s[0] > size) { x = 0; y += shelfH; shelfH = 0; }
            if (y + s[1] > size) { sheets++; x = 0; y = 0; shelfH = 0; }
            x += s[0];
            shelfH = Math.max(shelfH, s[1]);
        }
        return sheets;
    }
}
