package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Phase A-1: 字形キャッシュと被覆率の部品を {@link GlyphCache} へ移した。 */
class GlyphCacheTest {

    @Test
    void quantizeRoundsToSixteenths() {
        assertEquals(12.0625f, GlyphCache.quantize(12.07f));
        assertEquals(0.5f, GlyphCache.quantize(0.5f));
        assertEquals(1f, GlyphCache.quantize(0.99f));
    }

    @Test
    void isLightMatchesTheLumaThreshold() {
        assertTrue(GlyphCache.isLight(0xFFFFFFFF));
        assertFalse(GlyphCache.isLight(0xFF000000));
        Random r = new Random(7);
        for (int i = 0; i < 200; i++) {
            int argb = r.nextInt();
            float luma = (0.2126f * ((argb >>> 16) & 0xFF) + 0.7152f * ((argb >>> 8) & 0xFF) + 0.0722f * (argb & 0xFF)) / 255f;
            assertEquals(luma >= 0.4f, GlyphCache.isLight(argb));
        }
    }

    @Test
    void strokeCoverageIsAClampedRampAroundTheOutline() {
        float half = 2f;
        assertEquals(1f, GlyphCache.strokeCoverage(128f, half), "輪郭の上は完全に塗る");
        assertEquals(0f, GlyphCache.strokeCoverage(0f, half), "遠い外側は塗らない");
        float inside = GlyphCache.strokeCoverage(128f + 128f / LoadedFace.SDF_SPREAD * 2.2f, half);
        float outside = GlyphCache.strokeCoverage(128f - 128f / LoadedFace.SDF_SPREAD * 2.2f, half);
        assertEquals(inside, outside, 1e-5f, "輪郭の内外で対称");
        Random r = new Random(3);
        for (int i = 0; i < 200; i++) {
            float c = GlyphCache.strokeCoverage(r.nextFloat() * 255f, 0.2f + r.nextFloat() * 3f);
            assertTrue(c >= 0f && c <= 1f);
        }
    }

    private static GlyphBitmap bitmap(int w, int h, long seed) {
        Random r = new Random(seed);
        byte[] px = new byte[w * h];
        r.nextBytes(px);
        return new GlyphBitmap(w, h, 0, 0, px);
    }

    @Test
    void samplingOutsideIsZeroAndInsideInterpolates() {
        GlyphBitmap g = bitmap(4, 4, 1);
        assertEquals(0f, GlyphCache.sampleRaw(g, -5f, -5f));
        assertEquals(0f, GlyphCache.sampleRaw(g, 40f, 40f));
        assertEquals(g.coverageAt(2, 1), GlyphCache.sampleRaw(g, 2f, 1f), 1e-4f, "格子点ではその値");
        float mid = GlyphCache.sampleRaw(g, 1.5f, 1f);
        assertEquals((g.coverageAt(1, 1) + g.coverageAt(2, 1)) / 2f, mid, 1e-3f);
        assertEquals(mid / 255f, GlyphCache.sampleCoverage(g, 1.5f, 1f), 1e-6f);
    }

    @Test
    void cacheReturnsTheSameBitmapForTheSameKeyAndSeparatesLightAndSdf() {
        Assumptions.assumeTrue(SystemFonts.system().resolve(null) instanceof OutlineFontFace, "no system fonts");
        OutlineFontFace face = (OutlineFontFace) SystemFonts.system().resolve(null);
        FontStyle style = new FontStyle(16f, false, false, 0f, 400, true);
        OutlineFontFace.GlyphFace gf = face.glyphFace('A', style);
        GlyphCache c = new GlyphCache();
        GlyphBitmap a = c.glyph(gf, 'A', 16f, 1, false);
        assertSame(a, c.glyph(gf, 'A', 16f, 1, false), "同じ鍵は同じ字形");
        assertEquals(1, c.glyphCount());
        GlyphBitmap light = c.glyph(gf, 'A', 16f, 1, true);
        assertEquals(2, c.glyphCount(), "明暗で別の鍵");
        assertNotNull(light);
        GlyphBitmap sdf = c.sdfGlyph(gf, 'A', 16f, 1);
        assertSame(sdf, c.sdfGlyph(gf, 'A', 16f, 1));
        assertEquals(1, c.sdfGlyphCount());
        assertEquals(2, c.glyphCount(), "SDF は通常の表を増やさない");
    }

    @Test
    void cacheIsClearedAtTheLimit() {
        Assumptions.assumeTrue(SystemFonts.system().resolve(null) instanceof OutlineFontFace, "no system fonts");
        OutlineFontFace face = (OutlineFontFace) SystemFonts.system().resolve(null);
        FontStyle style = new FontStyle(10f, false, false, 0f, 400, true);
        GlyphCache c = new GlyphCache();
        for (int i = 0; i < GlyphCache.LIMIT; i++) {
            c.glyph(face.glyphFace('A', style), 'A', 8f + i * 0.0625f, 0, false);
        }
        assertEquals(GlyphCache.LIMIT, c.glyphCount());
        c.glyph(face.glyphFace('B', style), 'B', 10f, 0, false);
        assertEquals(1, c.glyphCount(), "上限に達したら全消去して 1 件から");
    }

    @Test
    void glyphCacheBodiesStayOutOfTheBackend() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"class GlyphKey", "GLYPH_CACHE_LIMIT", "Map<GlyphKey", "static float strokeCoverage(",
                "static float sampleRaw(", "static float sampleCoverage(", "static float quantize(", "static boolean isLight("}) {
            assertFalse(t.contains(bad), bad + " は GlyphCache へ移した");
        }
    }
}
