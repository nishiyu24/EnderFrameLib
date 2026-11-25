package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.PaintBox;

/**
 * 描画中の部分矩形（インライン断片の背景・{@code <fieldset>} の {@code <legend>} 補正）だけを一時的に差し替える
 * L5 側の座標上書き（S7 ①）。{@link PaintBox} 本体の欄は一切書き換えない。
 */
final class PaintBounds {
    private PaintBox target;
    private float x, y, w, h;
    private boolean zeroEdges;

    void with(PaintBox box, float tx, float ty, float tw, float th, boolean zero, Runnable action) {
        PaintBox prevTarget = this.target;
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

    float x(PaintBox b) { return b == target ? x : b.x(); }
    float y(PaintBox b) { return b == target ? y : b.y(); }
    float w(PaintBox b) { return b == target ? w : b.w(); }
    float h(PaintBox b) { return b == target ? h : b.h(); }
    float bL(PaintBox b) { return b == target && zeroEdges ? 0f : b.bL(); }
    float bT(PaintBox b) { return b == target && zeroEdges ? 0f : b.bT(); }
    float bR(PaintBox b) { return b == target && zeroEdges ? 0f : b.bR(); }
    float bB(PaintBox b) { return b == target && zeroEdges ? 0f : b.bB(); }
    float pL(PaintBox b) { return b == target && zeroEdges ? 0f : b.pL(); }
    float pT(PaintBox b) { return b == target && zeroEdges ? 0f : b.pT(); }
    float pR(PaintBox b) { return b == target && zeroEdges ? 0f : b.pR(); }
    float pB(PaintBox b) { return b == target && zeroEdges ? 0f : b.pB(); }

    BoxDecorations.Rect area(PaintBox b, String which) {
        return BoxDecorations.boxArea(x(b), y(b), w(b), h(b), bL(b), bT(b), bR(b), bB(b), pL(b), pT(b), pR(b), pB(b), which);
    }
}
