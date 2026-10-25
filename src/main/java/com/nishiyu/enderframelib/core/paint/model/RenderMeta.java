package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.text.model.BitmapFontFace;

public record RenderMeta(
        ClipRect clipRect,
        Corners borderRadii,
        CircleBorderSpec circleBorder,
        float fontHeight,
        float baseline,
        float lineHeight,
        float originX,
        float originY,
        int itemCount,
        boolean interactiveState,
        RoundedBorderSpec roundedBorder,
        RenderHints hints
) {
    public RenderMeta {
        if (hints == null) hints = RenderHints.NONE;
    }

    public RenderMeta(ClipRect clipRect, Corners borderRadii, CircleBorderSpec circleBorder,
                      float fontHeight, float baseline, float lineHeight,
                      float originX, float originY, int itemCount, boolean interactiveState,
                      RoundedBorderSpec roundedBorder) {
        this(clipRect, borderRadii, circleBorder, fontHeight, baseline, lineHeight,
                originX, originY, itemCount, interactiveState, roundedBorder, RenderHints.NONE);
    }

    public RenderMeta(ClipRect clipRect, Corners borderRadii, CircleBorderSpec circleBorder,
                      float fontHeight, float baseline, float lineHeight,
                      float originX, float originY, int itemCount, boolean interactiveState) {
        this(clipRect, borderRadii, circleBorder, fontHeight, baseline, lineHeight,
                originX, originY, itemCount, interactiveState, null);
    }

    /**
     * 行の情報を持たない要素（テキスト以外）のための短縮形。
     */
    public RenderMeta(ClipRect clipRect, Corners borderRadii, CircleBorderSpec circleBorder,
                      float fontHeight, float originX, float originY, int itemCount, boolean interactiveState) {
        this(clipRect, borderRadii, circleBorder, fontHeight, 0.0f, 0.0f,
                originX, originY, itemCount, interactiveState);
    }

    public static final RenderMeta EMPTY = new RenderMeta(null, null, null, 16.0f, 0.5f, 0.5f, 1, false);

    public RenderMeta withHints(RenderHints newHints) {
        if (newHints == null) newHints = RenderHints.NONE;
        if (newHints.equals(hints)) return this;
        return new RenderMeta(clipRect, borderRadii, circleBorder, fontHeight, baseline, lineHeight,
                originX, originY, itemCount, interactiveState, roundedBorder, newHints);
    }

    /**
     * 素のサイズからのフォントの倍率。
     */
    public float textScale() {
        return fontHeight / BitmapFontFace.MC_UNITS_PER_EM;
    }

    /** 行送り（px）。レイアウトが解決していなければ {@code line-height: normal} 相当。 */
    public float resolvedLineHeight() {
        if (lineHeight > 0) return lineHeight;
        return fontHeight * (1.0f + BitmapFontFace.MC_LINE_GAP_RATIO);
    }

    public boolean hasClip() {
        return clipRect != null;
    }

    public boolean hasBorderRadii() {
        return borderRadii != null && borderRadii.hasRadius();
    }
}
