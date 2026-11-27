package com.nishiyu.enderframelib.core.paint;

/**
 * 描画中の部分矩形（インライン断片の背景・{@code <fieldset>} の {@code <legend>} 補正）だけを一時的に差し替える
 * L5 側の座標上書き（S7 ①）。{@link SceneAdapterBox} 本体の欄は一切書き換えない。
 */
final class PaintBounds {
    private SceneAdapterBox target;
    private float x, y, w, h;
    private boolean zeroEdges;

    void with(SceneAdapterBox box, float tx, float ty, float tw, float th, boolean zero, Runnable action) {
        SceneAdapterBox prevTarget = this.target;
        float px = this.x, py = this.y, pw = this.w, ph = this.h;
        boolean pz = this.zeroEdges;
        this.target = box;
        this.x = tx;
        this.y = ty;
        this.w = tw;
        this.h = th;
        this.zeroEdges = zero;
        try {
            action.run();
        } finally {
            this.target = prevTarget;
            this.x = px;
            this.y = py;
            this.w = pw;
            this.h = ph;
            this.zeroEdges = pz;
        }
    }

    float x(SceneAdapterBox b) { return b == target ? x : b.x(); }
    float y(SceneAdapterBox b) { return b == target ? y : b.y(); }
    float w(SceneAdapterBox b) { return b == target ? w : b.w(); }
    float h(SceneAdapterBox b) { return b == target ? h : b.h(); }
    float bL(SceneAdapterBox b) { return b == target && zeroEdges ? 0f : b.bL(); }
    float bT(SceneAdapterBox b) { return b == target && zeroEdges ? 0f : b.bT(); }
    float bR(SceneAdapterBox b) { return b == target && zeroEdges ? 0f : b.bR(); }
    float bB(SceneAdapterBox b) { return b == target && zeroEdges ? 0f : b.bB(); }
    float pL(SceneAdapterBox b) { return b == target && zeroEdges ? 0f : b.pL(); }
    float pT(SceneAdapterBox b) { return b == target && zeroEdges ? 0f : b.pT(); }
    float pR(SceneAdapterBox b) { return b == target && zeroEdges ? 0f : b.pR(); }
    float pB(SceneAdapterBox b) { return b == target && zeroEdges ? 0f : b.pB(); }

    BoxDecorations.Rect area(SceneAdapterBox b, String which) {
        return BoxDecorations.boxArea(x(b), y(b), w(b), h(b), bL(b), bT(b), bR(b), bB(b), pL(b), pT(b), pR(b), pB(b), which);
    }
}
