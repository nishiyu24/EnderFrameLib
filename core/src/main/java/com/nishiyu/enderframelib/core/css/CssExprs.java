package com.nishiyu.enderframelib.core.css;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 1 つの型付きスタイル群が持つ {@code calc()} 等の式の表（不変）。{@link CssLength} の {@code CALC} は、この表の添字を持つ。
 * 式を 1 つも持たない群は共有の {@link #EMPTY} を指す（大半の要素）。プロセス共通の式表は持たない。
 */
public final class CssExprs {
    public static final CssExprs EMPTY = new CssExprs(new LengthResolver.CompiledLength[0]);

    private final LengthResolver.CompiledLength[] exprs;

    private CssExprs(LengthResolver.CompiledLength[] exprs) {
        this.exprs = exprs;
    }

    LengthResolver.CompiledLength get(int index) {
        return exprs[index];
    }

    /** {@code calc} 値（{@link CssLength#isCalc}）の式の原文。 */
    String raw(long calc) {
        return exprs[com.nishiyu.enderframelib.core.kernel.style.PackedCssValue.payload(calc)].raw();
    }

    public int size() {
        return exprs.length;
    }

    /** 値等価: 同じ位置に同じ式の文字列が並ぶ。 */
    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CssExprs o) || o.exprs.length != exprs.length) return false;
        for (int i = 0; i < exprs.length; i++) {
            if (!exprs[i].raw().equals(o.exprs[i].raw())) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = exprs.length;
        for (LengthResolver.CompiledLength c : exprs) h = h * 31 + c.raw().hashCode();
        return h;
    }

    @Override
    public String toString() {
        return Arrays.toString(Arrays.stream(exprs).map(LengthResolver.CompiledLength::raw).toArray());
    }

    /** 群の構築中だけ使う、式を集める入れ物。 */
    public static final class Builder {
        private List<LengthResolver.CompiledLength> list;

        int add(LengthResolver.CompiledLength c) {
            if (list == null) list = new ArrayList<>(2);
            list.add(c);
            return list.size() - 1;
        }

        public CssExprs build() {
            return list == null ? EMPTY : new CssExprs(list.toArray(new LengthResolver.CompiledLength[0]));
        }
    }
}
