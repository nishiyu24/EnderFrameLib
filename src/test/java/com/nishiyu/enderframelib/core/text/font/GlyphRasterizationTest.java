package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字形は描く大きさのデバイスピクセルで直接ラスタライズする（拡大縮小しない）ので、どの倍率でも線が潰れない。
 *
 * 何が壊れていたか: 以前はゲーム内の文字を Minecraft の unifont（16px のビットマップ）で描き、
 * CSS の大きさ × 画面の倍率へ最近傍で拡大縮小していた。13px × 1.25 のような中間の倍率では
 * 行や列が間引かれ・重複し、「m」の縦線がくっつくなど字が潰れていた。
 */
public class GlyphRasterizationTest {

    private static OutlineFontFace segoe;

    @BeforeAll
    static void setUp() {
        SystemFonts system = SystemFonts.system();
        Assumptions.assumeTrue(system.hasFamily("Segoe UI"), "Segoe UI が無い環境");
        segoe = (OutlineFontFace) system.withLocale(Locale.JAPANESE).resolve("'Segoe UI'");
    }

    private static LoadedFace.GlyphBitmap raster(char c, float pixelSize) {
        FontStyle style = new FontStyle(13, false, false, 0);
        return segoe.glyphFace(c, style).face().rasterize(c, pixelSize, false, false);
    }

    /** x ハイトの中ほどの行で、被覆率 50% 以上の区間がいくつあるか（縦線の数）。 */
    private static int stemsAtMidXHeight(LoadedFace.GlyphBitmap g, float pixelSize) {
        float xHeight = segoe.primaryFace(new FontStyle(pixelSize, false, false, 0)).xHeight(pixelSize);
        int row = Math.round(g.top() - xHeight / 2f);
        int stems = 0;
        boolean inside = false;
        for (int x = 0; x < g.width(); x++) {
            boolean on = g.coverageAt(x, row) >= 128;
            if (on && !inside) stems++;
            inside = on;
        }
        return stems;
    }

    @Test
    void stemsStaySeparateAtEveryScale() {
        // 13px の文字を GUI の倍率 1 / 1.25 / 1.5 / 1.75 / 2 / 3 で描く大きさ
        for (float px : new float[]{13f, 16.25f, 19.5f, 22.75f, 26f, 39f}) {
            assertEquals(3, stemsAtMidXHeight(raster('m', px), px), "m の縦線が 3 本に分かれる: " + px + "px");
            assertEquals(2, stemsAtMidXHeight(raster('n', px), px), "n の縦線が 2 本: " + px + "px");
            assertEquals(2, stemsAtMidXHeight(raster('H', px), px), "H の縦線が 2 本: " + px + "px");
        }
    }

    @Test
    void bitmapIsRasterizedAtTheDeviceSize() {
        // 拡大縮小していなければ、H の高さはその大きさでの大文字の高さ（Segoe UI は 0.7em）にほぼ等しい
        for (float px : new float[]{13f, 19.5f, 26f, 52f}) {
            LoadedFace.GlyphBitmap h = raster('H', px);
            assertEquals(0.7f * px, h.height(), 1.5f, "H の高さ @" + px + "px");
            assertTrue(h.top() > 0 && h.top() <= Math.ceil(0.7f * px) + 1, "H はベースラインの上に乗る");
        }
    }

    /** 原点の端数（1/4px）の位置でラスタライズすると、字形の重心がその分だけ右へ動く（字間が不揃いにならない）。 */
    @Test
    void subpixelOffsetsShiftTheGlyph() {
        FontStyle style = new FontStyle(13, false, false, 0);
        LoadedFace face = segoe.glyphFace('l', style).face();
        float base = centroidX(face.rasterize('l', 16f, false, false, 0));
        for (int s = 1; s < LoadedFace.SUBPIXEL_STEPS; s++) {
            float shifted = centroidX(face.rasterize('l', 16f, false, false, s));
            assertEquals(base + s / (float) LoadedFace.SUBPIXEL_STEPS, shifted, 0.15f, "端数 " + s + "/4");
        }
    }

    private static float centroidX(LoadedFace.GlyphBitmap g) {
        double sum = 0, weighted = 0;
        for (int y = 0; y < g.height(); y++) {
            for (int x = 0; x < g.width(); x++) {
                int c = g.coverageAt(x, y);
                sum += c;
                weighted += c * (x + 0.5 + g.left());
            }
        }
        return (float) (weighted / sum);
    }

    /**
     * 被覆率の補正（DirectWrite と同じ考え方）。端（0 と 255）は変えず、明るい字は中間を濃くする。
     * ゲーム内で showcase を撮って比べると、補正前は字のインクが Chrome の 40〜65%、補正後は 97〜113% だった。
     */
    @Test
    void textContrastKeepsEndsAndBoostsLightText() {
        LoadedFace.GlyphBitmap g = new LoadedFace.GlyphBitmap(3, 1, 0, 0, new byte[]{0, (byte) 128, (byte) 255});
        LoadedFace.GlyphBitmap light = g.withTextContrast(true);
        LoadedFace.GlyphBitmap dark = g.withTextContrast(false);
        assertEquals(0, light.coverageAt(0, 0));
        assertEquals(255, light.coverageAt(2, 0));
        assertEquals(0, dark.coverageAt(0, 0));
        assertEquals(255, dark.coverageAt(2, 0));
        assertTrue(light.coverageAt(1, 0) > 200, "明るい字の中間の被覆率は濃くなる: " + light.coverageAt(1, 0));
        assertTrue(dark.coverageAt(1, 0) < light.coverageAt(1, 0), "暗い字は sRGB の合成で太って見えるので補正が弱い");
    }

    @Test
    void japaneseGlyphsComeFromFallbackAndRasterize() {
        FontStyle style = new FontStyle(13, false, false, 0);
        LoadedFace face = segoe.glyphFace('日', style).face();
        assertTrue(face.hasGlyph('日'));
        LoadedFace.GlyphBitmap g = face.rasterize('日', 19.5f, false, false);
        assertTrue(g.width() > 10 && g.height() > 10, "代替書体（Noto Sans JP など）で描ける");
    }
}
