package com.nishiyu.enderframelib.core.paint.tree;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.layout.PaintBox;
import com.nishiyu.enderframelib.core.layout.PaintRun;

/**
 * 描画用の木の、行に置かれた箱の断片 1 つ（M2c ②-2。文字の語、またはインライン要素の行ごとの箱）。不変。座標は持ち主の箱の
 * 原点の箱（{@link PaintBox#originParent}）の左上が原点。
 */
final class TreeRun implements PaintRun {
    private final float x, y, w, h, baseline;
    private final String text;
    private final boolean startEdge, endEdge;
    /** 1 行目の語に効く {@code ::first-line} を掛けたスタイル（1 行目でない・規則が無ければ null）。 */
    private final ComputedStyle firstLine;

    TreeRun(float x, float y, float w, float h, String text, float baseline, boolean startEdge, boolean endEdge) {
        this(x, y, w, h, text, baseline, startEdge, endEdge, null);
    }

    TreeRun(float x, float y, float w, float h, String text, float baseline, boolean startEdge, boolean endEdge, ComputedStyle firstLine) {
        this.firstLine = firstLine;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.text = text;
        this.baseline = baseline;
        this.startEdge = startEdge;
        this.endEdge = endEdge;
    }

    @Override public float x() { return x; }
    @Override public float y() { return y; }
    @Override public float w() { return w; }
    @Override public float h() { return h; }
    @Override public float baseline() { return baseline; }
    @Override public String text() { return text; }
    @Override public boolean startEdge() { return startEdge; }
    @Override public boolean endEdge() { return endEdge; }

    @Override
    public ComputedStyle firstLineStyleFor(PaintBox target) {
        return firstLine;
    }
}
