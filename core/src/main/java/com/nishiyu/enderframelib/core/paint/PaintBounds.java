package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;

/**
 * 項目の生成器が描いている層 1 つの置き場所（M3b-4c-1a）と、描画中の部分矩形（インライン断片の背景・{@code <fieldset>} の
 * {@code <legend>} 補正）だけを一時的に差し替える上書き（S7 ①）。
 *
 * <p>生成器は {@link SceneLayer} の値だけを読み、層の位置は層に無い（親相対）ので、ここに置いた値から読む。層の記憶（{@code LayerOps}）は
 * 層を位置 0・原点の節 0 に置き、毎フレーム作る箱（キャンバスへ背景を譲った html / body・縦書きの包み箱・マスク・{@code ::backdrop}）は
 * 走査の側が今の位置に置く。{@link SceneLayer} 本体の欄は一切書き換えない。</p>
 */
final class PaintBounds {
    private SceneLayer placed;
    private float px, py, originX, originY;
    private ComputedStyle parentFirstLine;

    private SceneLayer target;
    private float x, y, w, h;
    private boolean zeroEdges;

    /**
     * 描く層を置く。
     *
     * @param layer           描く層
     * @param x               層の左上（項目の座標の空間。記憶する命令なら 0）
     * @param y               同じく上
     * @param originX         層の原点の節（文字・インラインの断片の座標の基準）の左上。原点の節が無ければ 0
     * @param originY         同じく上
     * @param parentFirstLine 親の {@code ::first-line}（縦書きの包み箱が親の 1 行目の背景を描くため。無ければ null）
     */
    void place(SceneLayer layer, float x, float y, float originX, float originY, ComputedStyle parentFirstLine) {
        this.placed = layer;
        this.px = x;
        this.py = y;
        this.originX = originX;
        this.originY = originY;
        this.parentFirstLine = parentFirstLine;
    }

    /** 層の置き場所を外す（描き終えたら呼ぶ。層を持ち続けない）。 */
    void clear() {
        placed = null;
        parentFirstLine = null;
        px = py = originX = originY = 0f;
    }

    /** 置いた層の左上（上書きに依らない）。 */
    float px() { return px; }
    float py() { return py; }
    /** 置いた層の原点の節の左上。 */
    float originX() { return originX; }
    float originY() { return originY; }
    ComputedStyle parentFirstLine() { return parentFirstLine; }
    SceneLayer placed() { return placed; }

    void with(SceneLayer box, float tx, float ty, float tw, float th, boolean zero, Runnable action) {
        SceneLayer prevTarget = this.target;
        float ox = this.x, oy = this.y, ow = this.w, oh = this.h;
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
            this.x = ox;
            this.y = oy;
            this.w = ow;
            this.h = oh;
            this.zeroEdges = pz;
        }
    }

    /** 層 {@code b}（置いた層か上書きの対象）の左上。上書きの対象でなければ置いた位置。 */
    float x(SceneLayer b) { return b == target ? x : px; }
    float y(SceneLayer b) { return b == target ? y : py; }
    float w(SceneLayer b) { return b == target ? w : b.w; }
    float h(SceneLayer b) { return b == target ? h : b.h; }
    float bL(SceneLayer b) { return b == target && zeroEdges ? 0f : b.bL; }
    float bT(SceneLayer b) { return b == target && zeroEdges ? 0f : b.bT; }
    float bR(SceneLayer b) { return b == target && zeroEdges ? 0f : b.bR; }
    float bB(SceneLayer b) { return b == target && zeroEdges ? 0f : b.bB; }
    float pL(SceneLayer b) { return b == target && zeroEdges ? 0f : b.pL; }
    float pT(SceneLayer b) { return b == target && zeroEdges ? 0f : b.pT; }
    float pR(SceneLayer b) { return b == target && zeroEdges ? 0f : b.pR; }
    float pB(SceneLayer b) { return b == target && zeroEdges ? 0f : b.pB; }

    BoxDecorations.Rect area(SceneLayer b, String which) {
        return BoxDecorations.boxArea(x(b), y(b), w(b), h(b), bL(b), bT(b), bR(b), bB(b), pL(b), pT(b), pR(b), pB(b), which);
    }
}
