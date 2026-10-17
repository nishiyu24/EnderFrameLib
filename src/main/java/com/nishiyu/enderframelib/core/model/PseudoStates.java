package com.nishiyu.enderframelib.core.model;

/**
 * 要素が持つ疑似クラス状態の構造化表現。
 */
public record PseudoStates(
        PseudoTarget hover,
        PseudoTarget active,
        PseudoTarget focus
) {
    public static final PseudoStates EMPTY = new PseudoStates(null, null, null);

    public boolean hasAny() {
        return hover != null || active != null || focus != null;
    }

    public boolean hasHover() {
        return hover != null;
    }

    public boolean hasActive() {
        return active != null;
    }

    public boolean hasFocus() {
        return focus != null;
    }
}
