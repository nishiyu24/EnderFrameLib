package com.nishiyu.enderframelib.core.text.model;


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

    private static final int ADVANCE_MAX_CACHE_LEN = 512;

    private final float unitsPerEm;
    private final float naturalAscent;
    private final float naturalDescent;
    private final float lineGapRatio;
    private final float[] asciiCharCache;
    /** 語・文字列の素の送り幅の記憶（計画書 §2.3 の L1。4,096 組 × 2 = 8,192 欄。書体の実例が 1 つ持ち、スレッドで共有する）。 */
    private final TextMemo<Boolean, Float> advanceMemo = new TextMemo<>(4096);

    protected BitmapFontFace() {
        this(MC_UNITS_PER_EM, MC_ASCENT, MC_DESCENT, MC_LINE_GAP_RATIO);
    }

    protected BitmapFontFace(float unitsPerEm, float naturalAscent, float naturalDescent, float lineGapRatio) {
        this.unitsPerEm = unitsPerEm;
        this.naturalAscent = naturalAscent;
        this.naturalDescent = naturalDescent;
        this.lineGapRatio = lineGapRatio;
        float[] ascii = new float[256];
        Arrays.fill(ascii, Float.NaN);
        this.asciiCharCache = ascii;
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
        Boolean b = bold; // Boolean.valueOf の共有の実例（確保しない）
        Float hit = advanceMemo.get(text, b);
        if (hit != null) return hit;
        float w = naturalAdvance(text, bold);
        advanceMemo.put(text, b, w);
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
