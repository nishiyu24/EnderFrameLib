package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.SceneWalker.Resolved;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;

/**
 * 描画側（{@link Painter}）が描く項目・保持層の中身について走査器（{@link SceneWalker}）に問う、層の文脈（M3b-4c-2b-3b-2b-4a。計画書 §3.4）。
 * 描画側は項目の列の位置で走査器に姿勢・祖先・範囲を問わず、走査器が渡すこの文脈だけを読む（走査器がシーンを直接たどる 2b-4b で、
 * 文脈の出どころを列から層の階層に替えても描画側は変わらない）。
 *
 * <p>走査器は文脈の実体を使い回す: 渡された呼び出しの間だけ有効で、描画側は持ち続けない（同一性で覚えない。覚えるなら ID で）。</p>
 */
public interface LayerContext {
    /**
     * 文脈の祖先の箱（自分を含む）のうち、ID が {@code id} の箱の姿勢（無ければ null）。クリップの持ち主は項目の祖先の箱なので、
     * 持ち主の ID から姿勢を引く。同じフレームの中では、同じ箱は同じ実体を返す（{@link Resolved} の同一性で、作ったクリップの前提を確かめられる）。
     */
    Resolved owner(String id);

    /** 描く項目の文脈（{@link Painter#drawItem}）。 */
    interface Item extends LayerContext {
        /** 項目の姿勢（親から引き継ぐ変形）と祖先の不透明度。 */
        Resolved resolved();

        /** 項目の補間値（色など。姿勢を親と共有する項目でも、その項目自身のもの）。 */
        AnimatedValues anim();
    }

    /**
     * 中身を 1 つの節にまとめたスクロールコンテナの中身（保持層にできる範囲。{@link Painter#planRetained}）。範囲の項目は描画順で連続し、
     * 走査器は範囲を重ねる・記録する・ふつうに描くのどれかで扱う。
     */
    interface Content extends LayerContext {
        /** 中身の節の ID（保持した層の名前）。 */
        String id();

        /** コンテナ（中身の節の持ち主）の ID。中身の項目のクリップの鎖のうち、これが持ち主の部品がコンテナの切り取り。 */
        String ownerId();

        /** 中身の同一性の印（同じ印なら、節の移動量以外は前に描いたときと同じ中身。コンテナの層の子の並び）。 */
        Object token();

        /** 中身の節の姿勢。 */
        Resolved resolved();

        /** コンテナの切り取りを持つ中身のクリップ（{@link #ownerId} が持ち主の部品を含む最初のもの。無ければ null）。 */
        ClipRect clip();

        /**
         * 中身が保持した層に向くか: 時間や外の事情で見た目が変わるもの（画像の読み込み・動画・キャンバス・入力欄のカーソル・動くアニメーション）を
         * 含まない。どれも中身の印が変わらないまま見た目だけが変わりうるので、層が古くなる。
         */
        boolean retainable();
    }
}
