package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.paint.scene.DrawOp;
import com.nishiyu.enderframelib.core.paint.scene.SceneEmitter;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 層 1 つの描画命令を作る（M3b-4a。{@link SceneEmitter} が層を新しく作ったときだけ呼ぶ）。
 *
 * <p>層を位置 0（原点の節も 0）に置いて項目の生成器を走らせ、祖先（原点の節・親の錨・祖先の文字色のアニメーション・箱のクリップ）は
 * 組み立て（{@link OpComposer#composeLayer}）まで残す。命令の座標は層自身か原点の節からの float で、層の位置に依らない。
 * 読むのは層の値だけ（M3b-4c-1a。一時アダプタの箱を作らない）。</p>
 */
public final class LayerOps implements SceneEmitter.OpsSource {
    private final AtomicInteger fragIds = new AtomicInteger();
    private final BoxItemPainter painter;
    private final OpList ops = new OpList(32);
    private int viewportW, viewportH;

    public LayerOps(int viewportW, int viewportH, Map<String, Keyframes> keyframes) {
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        this.painter = new BoxItemPainter(viewportW, viewportH, keyframes != null ? keyframes : Map.of(), fragIds);
    }

    /** 1 CSS px が何画素か（画像の解像度の選び方に効く）。変えたらシーンを作り直す。 */
    public void setDevicePixelRatio(float dpr) {
        painter.decor.setDevicePixelRatio(dpr);
    }

    @Override
    public DrawOp[] ops(SceneLayer layer, int vw, int vh) {
        if (vw != viewportW || vh != viewportH) {
            viewportW = vw;
            viewportH = vh;
            painter.setViewport(vw, vh);
        }
        ops.beginDeferred();
        fragIds.set(0);
        painter.drawBox(ops, layer, 0f, 0f, 0f, 0f, null, null, null, false, PaintSupport.hasOwnNode(layer), false);
        DrawOp[] out = ops.toArray(DrawOp.EMPTY);
        ops.clear();
        return out;
    }
}
