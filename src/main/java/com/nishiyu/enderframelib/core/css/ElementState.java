package com.nishiyu.enderframelib.core.css;

/**
 * 疑似クラスの照合に必要な、ビュー単位の対話状態。
 * 以前は {@code "hover-color"} のようなキープレフィックスと boolean 引数の列で表現していたものを型にしたもの。
 */
public record ElementState(boolean hover, boolean active, boolean focus) {

    public static final ElementState NONE = new ElementState(false, false, false);

    public static ElementState hovered() {
        return new ElementState(true, false, false);
    }

    public boolean isAny() {
        return hover || active || focus;
    }
}
