package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import java.util.HashMap;
import java.util.Map;

/**
 * 字形（通常・縁取り用 SDF）のキャッシュと、字形の被覆率を引く純粋な部品。
 * 旧 {@code SoftwareRenderBackend} 内にあったものを、コードを変えずに移した（Phase A-1）。
 * 引くときは作業用の鍵 1 個を書き換えて使い回し（字ごとの割り当てを避ける）、登録するときだけ複製する。
 * 描画は 1 スレッドなので同期しない。
 */
final class GlyphCache {
    /** 文字の字形キャッシュの上限。 */
    static final int LIMIT = 4096;

    private final Map<Key, GlyphBitmap> glyphs = new HashMap<>();
    /** 縁取り用の SDF の字形（{@link #glyphs} と同じ鍵）。 */
    private final Map<Key, GlyphBitmap> sdfGlyphs = new HashMap<>();
    private final Key probe = new Key();

    /** 通常の字形。 */
    GlyphBitmap glyph(OutlineFontFace.GlyphFace gf, int cp, float pixelSize, int sub, boolean light) {
        Key key = probe.set(gf.face(), cp, pixelSize, gf.syntheticBold(), gf.syntheticItalic(), sub, light);
        GlyphBitmap g = glyphs.get(key);
        if (g == null) {
            g = gf.face().rasterize(cp, pixelSize, gf.syntheticBold(), gf.syntheticItalic(), sub);
            if (!g.isEmpty()) g = g.withTextContrast(light);
            if (glyphs.size() >= LIMIT) glyphs.clear();
            glyphs.put(key.copy(), g);
        }
        return g;
    }

    /** 縁取り用の SDF の字形。 */
    GlyphBitmap sdfGlyph(OutlineFontFace.GlyphFace gf, int cp, float pixelSize, int sub) {
        Key key = probe.set(gf.face(), cp, pixelSize, gf.syntheticBold(), gf.syntheticItalic(), sub, false);
        GlyphBitmap g = sdfGlyphs.get(key);
        if (g == null) {
            g = gf.face().rasterizeSdf(cp, pixelSize, gf.syntheticBold(), gf.syntheticItalic(), sub);
            if (sdfGlyphs.size() >= LIMIT) sdfGlyphs.clear();
            sdfGlyphs.put(key.copy(), g);
        }
        return g;
    }

    int glyphCount() {
        return glyphs.size();
    }

    int sdfGlyphCount() {
        return sdfGlyphs.size();
    }

    static float quantize(float px) {
        return Math.round(px * 16f) / 16f;
    }

    static boolean isLight(int argb) {
        float luma = (0.2126f * ((argb >>> 16) & 0xFF) + 0.7152f * ((argb >>> 8) & 0xFF) + 0.0722f * (argb & 0xFF)) / 255f;
        return luma >= 0.4f;
    }

    /** SDF の値（128 = 輪郭、内側が大きい、{@code SDF_SPREAD} 画素で 128 段階）→ 縁取りの被覆率。 */
    static float strokeCoverage(float sdfValue, float half) {
        float d = (sdfValue - 128f) * (LoadedFace.SDF_SPREAD / 128f); // 画素（内側が正）
        float c = half - Math.abs(d) + 0.5f;
        return c <= 0f ? 0f : Math.min(1f, c);
    }

    /** 範囲の外は SDF の「遠い外側」（0）として、双一次補間した生の値（0〜255）。 */
    static float sampleRaw(GlyphBitmap g, float col, float row) {
        int c0 = (int) Math.floor(col), r0 = (int) Math.floor(row);
        float fx = col - c0, fy = row - r0;
        float v00 = cov(g, c0, r0), v10 = cov(g, c0 + 1, r0), v01 = cov(g, c0, r0 + 1), v11 = cov(g, c0 + 1, r0 + 1);
        return (v00 * (1 - fx) + v10 * fx) * (1 - fy) + (v01 * (1 - fx) + v11 * fx) * fy;
    }

    /** 双一次補間した被覆率（0〜1）。 */
    static float sampleCoverage(GlyphBitmap g, float col, float row) {
        return sampleRaw(g, col, row) / 255f;
    }

    private static float cov(GlyphBitmap g, int c, int r) {
        return c < 0 || r < 0 || c >= g.width() || r >= g.height() ? 0f : g.coverageAt(c, r);
    }

    /** 字形キャッシュの鍵。登録済みの鍵は書き換えない。 */
    private static final class Key {
        LoadedFace face;
        int codePoint;
        float pixelSize;
        boolean bold, italic, light;
        int subpixel;

        Key set(LoadedFace face, int codePoint, float pixelSize, boolean bold, boolean italic, int subpixel, boolean light) {
            this.face = face;
            this.codePoint = codePoint;
            this.pixelSize = pixelSize;
            this.bold = bold;
            this.italic = italic;
            this.subpixel = subpixel;
            this.light = light;
            return this;
        }

        Key copy() {
            return new Key().set(face, codePoint, pixelSize, bold, italic, subpixel, light);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key k)) return false;
            return face.equals(k.face) && codePoint == k.codePoint && Float.compare(pixelSize, k.pixelSize) == 0
                    && bold == k.bold && italic == k.italic && subpixel == k.subpixel && light == k.light;
        }

        @Override
        public int hashCode() {
            int h = face.hashCode();
            h = 31 * h + codePoint;
            h = 31 * h + Float.floatToIntBits(pixelSize);
            h = 31 * h + (bold ? 1 : 0) + (italic ? 2 : 0) + (light ? 4 : 0);
            return 31 * h + subpixel;
        }
    }
}
