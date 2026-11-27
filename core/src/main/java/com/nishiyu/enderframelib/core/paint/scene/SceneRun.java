package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.paint.tree.PaintRun;

/**
 * {@link SceneLayer} が持つ、行に置かれた断片 1 つ（文字の語、またはインライン要素の行ごとの箱。M3b-2）。
 * 座標は持ち主の箱の原点の箱（{@link PaintBox#originParent}）の左上が原点。不変。
 */
public final class SceneRun implements PaintRun {
    private final float x, y, w, h, baseline;
    private final String text;
    private final boolean startEdge, endEdge;
    private final ComputedStyle firstLine;

    public SceneRun(float x, float y, float w, float h, String text, float baseline, boolean startEdge, boolean endEdge) {
        this(x, y, w, h, text, baseline, startEdge, endEdge, null);
    }

    public SceneRun(float x, float y, float w, float h, String text, float baseline, boolean startEdge, boolean endEdge,
                    ComputedStyle firstLine) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.text = text;
        this.baseline = baseline;
        this.startEdge = startEdge;
        this.endEdge = endEdge;
        this.firstLine = firstLine;
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
