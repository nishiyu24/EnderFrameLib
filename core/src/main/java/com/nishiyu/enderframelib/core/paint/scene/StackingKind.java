package com.nishiyu.enderframelib.core.paint.scene;

/**
 * CSS 2.2 付録 E における箱の重なりの分類（旧 {@code Fragment.Kind} に相当）。
 */
public enum StackingKind {
    ROOT,
    BLOCK,
    INLINE,
    FLOAT,
    ATOMIC,
    POSITIONED,
    STACKING
}
