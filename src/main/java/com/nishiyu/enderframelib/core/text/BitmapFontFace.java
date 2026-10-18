package com.nishiyu.enderframelib.core.text;

/**
 * ビットマップフォント用の {@link FontFace} の骨組み。
 * 実装側に残るのは「素のサイズでの送り幅」だけで、
 * px への換算・ベースライン・行送りはここが一手に決める。
 *
 * Minecraft のフォントは素のサイズで ascent 7px / descent 2px（行高 9px）。
 * これを 1em = 9px と見なすと、{@code font-size: 16px} のときの
 * キャップハイト 12.4px・x ハイト 8.9px が、ブラウザで一般的な UI フォント
 * （16px でそれぞれ約 11.5px / 8.3px）とほぼ重なる。
 * 以前は 1em = 12px 相当（測定の {@code 16/12} と描画の {@code /12.0f}）で、
 * 同じ {@code font-size} でもブラウザより 3 割小さく出ていた。
 *
 * LIMITATION(制約): 合わせられるのは文字の見かけの大きさと行送りまで。
 * グリフの形と 1 文字あたりの送り幅はビットマップフォント固有なので、
 * 折り返し位置・省略位置・中央寄せの offset はブラウザと一致しない
 * （README の "Glyph-level fidelity" と FIDELITY.md の「意図的に違うもの」）。
 */
public abstract class BitmapFontFace implements FontFace {

    /** 1em に相当する素のサイズ（px）。 */
    public static final float MC_UNITS_PER_EM = 9.0f;
    /** 素のサイズでのベースライン上の高さ（px）。 */
    public static final float MC_ASCENT = 7.0f;
    /** 素のサイズでのベースライン下の高さ（px）。 */
    public static final float MC_DESCENT = 2.0f;
    /**
     * 行間（em 比）。
     * Minecraft のフォント自体は行間を持たない（行高 = ascent + descent）が、
     * それだと {@code line-height: normal} が 1.0 になりブラウザ（1.15 前後）より詰まる。
     * UA 水準の行間はここで与える。
     */
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
     * 素のサイズでの送り幅。太字の表現方法（Minecraft では {@code §l} の前置）は
     * 実装の内側に閉じる。
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
