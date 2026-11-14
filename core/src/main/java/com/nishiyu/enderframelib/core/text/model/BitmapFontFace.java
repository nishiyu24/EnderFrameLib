package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

import java.util.Arrays;

/**
 * ビットマップフォント用の {@link FontFace} の骨組み。
 */
public abstract class BitmapFontFace implements FontFace {

    /** 1em に相当する素のサイズ（px）。 */
    public static final float MC_UNITS_PER_EM = 9.0f;
    /** 素のサイズでのベースライン上の高さ（px）。 */
    public static final float MC_ASCENT = 7.0f;
    /** 素のサイズでのベースライン下の高さ（px）。 */
    public static final float MC_DESCENT = 2.0f;
    /** 行間（em 比）。 */
    public static final float MC_LINE_GAP_RATIO = 0.15f;

    private static final int ADVANCE_CACHE_BUCKETS = 4096;
    private static final int ADVANCE_CACHE_MASK = ADVANCE_CACHE_BUCKETS - 1;
    private static final int ADVANCE_MAX_CACHE_LEN = 512;

    private static final class AdvanceEntry {
        final String text;
        final boolean bold;
        final float width;

        AdvanceEntry(String text, boolean bold, float width) {
            this.text = text;
            this.bold = bold;
            this.width = width;
        }
    }

    private final float unitsPerEm;
    private final float naturalAscent;
    private final float naturalDescent;
    private final float lineGapRatio;
    private volatile float[] asciiCharCache;
    private volatile AdvanceEntry[] advanceCache;

    protected BitmapFontFace() {
        this(MC_UNITS_PER_EM, MC_ASCENT, MC_DESCENT, MC_LINE_GAP_RATIO);
    }

    protected BitmapFontFace(float unitsPerEm, float naturalAscent, float naturalDescent, float lineGapRatio) {
        this.unitsPerEm = unitsPerEm;
        this.naturalAscent = naturalAscent;
        this.naturalDescent = naturalDescent;
        this.lineGapRatio = lineGapRatio;
    }

    /**
     * 素のサイズでの送り幅。太字の表現方法は実装の内側に閉じる。
     */
    protected abstract float naturalAdvance(String text, boolean bold);

    /** {@code font-size} に対応する素のサイズからの倍率。描画側も同じ値を使う。 */
    public float scaleFor(FontStyle style) {
        return style.fontSizePx() / unitsPerEm;
    }

    /** {@code naturalAdvance(text, bold)} の語・文字列単位キャッシュ。 */
    public final float cachedNaturalAdvance(String text, boolean bold) {
        if (text == null || text.isEmpty()) return 0;
        int len = text.length();
        if (len == 1) {
            char c = text.charAt(0);
            if (c < 128) {
                float[] ascii = asciiCharCache;
                if (ascii == null) {
                    ascii = new float[256];
                    Arrays.fill(ascii, Float.NaN);
                    asciiCharCache = ascii;
                }
                int slot = bold ? (128 + c) : c;
                float cached = ascii[slot];
                if (!Float.isNaN(cached)) return cached;
                float w = naturalAdvance(text, bold);
                ascii[slot] = w;
                return w;
            }
        }
        if (len > ADVANCE_MAX_CACHE_LEN) {
            return naturalAdvance(text, bold);
        }
        AdvanceEntry[] table = advanceCache;
        if (table == null) {
            table = new AdvanceEntry[ADVANCE_CACHE_BUCKETS * 2];
            advanceCache = table;
        }
        int h = text.hashCode();
        int base = ((h ^ (h >>> 16) ^ (bold ? 0x5bd1e995 : 0)) & ADVANCE_CACHE_MASK) << 1;
        AdvanceEntry e0 = table[base];
        if (e0 != null && e0.bold == bold && (e0.text == text || e0.text.equals(text))) {
            return e0.width;
        }
        AdvanceEntry e1 = table[base + 1];
        if (e1 != null && e1.bold == bold && (e1.text == text || e1.text.equals(text))) {
            table[base + 1] = e0;
            table[base] = e1;
            return e1.width;
        }
        float w = naturalAdvance(text, bold);
        if (e0 != null) table[base + 1] = e0;
        table[base] = new AdvanceEntry(text, bold, w);
        return w;
    }

    @Override
    public float advance(String text, FontStyle style) {
        if (text == null || text.isEmpty()) return 0;
        float advance = cachedNaturalAdvance(text, style.bold()) * scaleFor(style);
        if (style.letterSpacingPx() != 0) advance += style.letterSpacingPx() * glyphCount(text);
        return advance;
    }

    @Override
    public float ascent(FontStyle style) {
        return com.nishiyu.enderframelib.core.kernel.LayoutUnit.snap(naturalAscent * scaleFor(style));
    }

    @Override
    public float descent(FontStyle style) {
        return com.nishiyu.enderframelib.core.kernel.LayoutUnit.snap(naturalDescent * scaleFor(style));
    }

    @Override
    public float lineGap(FontStyle style) {
        return lineGapRatio * style.fontSizePx();
    }
}
