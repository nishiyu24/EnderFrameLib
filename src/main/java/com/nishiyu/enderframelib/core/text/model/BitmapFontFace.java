package com.nishiyu.enderframelib.core.text.model;

import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;

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

    private final float unitsPerEm;
    private final float naturalAscent;
    private final float naturalDescent;
    private final float lineGapRatio;

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

    @Override
    public float advance(String text, FontStyle style) {
        if (text == null || text.isEmpty()) return 0;
        float advance = naturalAdvance(text, style.bold()) * scaleFor(style);
        if (style.letterSpacingPx() != 0) advance += style.letterSpacingPx() * glyphCount(text);
        return advance;
    }

    @Override
    public float ascent(FontStyle style) {
        return naturalAscent * scaleFor(style);
    }

    @Override
    public float descent(FontStyle style) {
        return naturalDescent * scaleFor(style);
    }

    @Override
    public float lineGap(FontStyle style) {
        return lineGapRatio * style.fontSizePx();
    }
}
