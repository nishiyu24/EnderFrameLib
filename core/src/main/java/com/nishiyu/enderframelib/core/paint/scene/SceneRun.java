package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.css.ComputedStyle;

/**
 * {@link SceneLayer} が持つ、行に置かれた断片 1 つ（文字の語、またはインライン要素の行ごとの箱。M3b-2）。
 * 座標は持ち主の原点の箱（{@code isOrigin} な祖先）の左上が原点。不変。
 */
public final class SceneRun {
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

    public float x() { return x; }
    public float y() { return y; }
    public float w() { return w; }
    public float h() { return h; }
    public float baseline() { return baseline; }
    public String text() { return text; }
    public boolean isText() { return text != null; }
    public boolean startEdge() { return startEdge; }
    public boolean endEdge() { return endEdge; }

    public ComputedStyle firstLineStyle() {
        return firstLine;
    }
}
