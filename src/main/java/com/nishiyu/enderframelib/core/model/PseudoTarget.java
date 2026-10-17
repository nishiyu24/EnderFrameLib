package com.nishiyu.enderframelib.core.model;

/**
 * 疑似クラス（:hover, :active, :focus）適用時のアニメーション/スタイル目標値。
 */
public record PseudoTarget(
        float opacity,
        float translateX,
        float translateY,
        float scaleX,
        float scaleY,
        float rotate,
        int color,
        int bgColor
) {
    public float op() { return opacity; }
    public float tx() { return translateX; }
    public float ty() { return translateY; }
    public float sx() { return scaleX; }
    public float sy() { return scaleY; }
    public float rot() { return rotate; }
    public int col() { return color; }
    public int bgCol() { return bgColor; }
}
