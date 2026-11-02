package com.nishiyu.enderframelib.core.css.model;

import com.nishiyu.enderframelib.core.css.*;
import com.nishiyu.enderframelib.core.css.model.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

/**
 * 疑似クラスの照合に必要な、ビュー単位の要素状態（ビット集合）。
 *
 * 以前は {@code record(boolean hover, boolean active, boolean focus)} の 3 値しか持たず、
 * {@code :focus-visible} / {@code :focus-within} が {@code :focus} に潰され、
 * {@code :checked} / {@code :disabled} も静的属性しか参照できなかった。
 */
public record ElementState(int flags) {

    public static final int HOVER         = 1 << 0;
    public static final int ACTIVE        = 1 << 1;
    public static final int FOCUS         = 1 << 2;
    public static final int FOCUS_VISIBLE = 1 << 3;
    public static final int FOCUS_WITHIN  = 1 << 4;
    public static final int CHECKED       = 1 << 5;
    public static final int DISABLED      = 1 << 6;
    public static final int INDETERMINATE = 1 << 7;
    public static final int OPEN          = 1 << 8;

    /** 対話由来の動的な状態ビット群（:hover / :active / :focus*）。 */
    public static final int INTERACTIVE_MASK = HOVER | ACTIVE | FOCUS | FOCUS_VISIBLE | FOCUS_WITHIN;

    public static final ElementState NONE = new ElementState(0);

    public ElementState(boolean hover, boolean active, boolean focus) {
        this((hover ? HOVER : 0) | (active ? ACTIVE : 0) | (focus ? FOCUS : 0));
    }

    public static ElementState of(int flags) {
        return flags == 0 ? NONE : new ElementState(flags);
    }

    public static ElementState hovered() {
        return new ElementState(HOVER);
    }

    /** DOM ノードの静的属性（checked / disabled / indeterminate / open）から初期状態を導出する。 */
    public static ElementState fromNodeAttributes(HtmlNode node) {
        if (node == null || node.attrs == null || node.attrs.isEmpty()) return NONE;
        int f = 0;
        if (node.attrs.containsKey("checked")) f |= CHECKED;
        if (node.attrs.containsKey("disabled")) f |= DISABLED;
        if (node.attrs.containsKey("indeterminate")) f |= INDETERMINATE;
        if (node.attrs.containsKey("open")) f |= OPEN;
        return of(f);
    }

    public boolean has(int mask) {
        return (flags & mask) == mask;
    }

    public boolean hasAny(int mask) {
        return (flags & mask) != 0;
    }

    public ElementState with(int mask) {
        return of(flags | mask);
    }

    public ElementState without(int mask) {
        return of(flags & ~mask);
    }

    public boolean hover() { return (flags & HOVER) != 0; }
    public boolean active() { return (flags & ACTIVE) != 0; }
    public boolean focus() { return (flags & FOCUS) != 0; }
    public boolean focusVisible() { return (flags & FOCUS_VISIBLE) != 0; }
    public boolean focusWithin() { return (flags & FOCUS_WITHIN) != 0; }
    public boolean checked() { return (flags & CHECKED) != 0; }
    public boolean disabled() { return (flags & DISABLED) != 0; }
    public boolean indeterminate() { return (flags & INDETERMINATE) != 0; }
    public boolean open() { return (flags & OPEN) != 0; }

    public boolean isAny() {
        return flags != 0;
    }
}
