package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.layout.model.ScrollState;

/**
 * {@link DisplayScene} の外に置く動的な描画状態（スクロール量・ビューポートの大きさ。M3b-2。計画書 §2.1-3 / §3.4）。
 * シーン木自体を書き換えずに、走査時にスクロール・sticky・アンカー追従の平行移動を加える。
 */
public final class DynamicSceneState {
    /** {@code content-visibility: auto} の箱を関連範囲とみなすビューポート外余白（CSS px）。 */
    public static final float CONTENT_VISIBILITY_MARGIN = 640f;

    public final ScrollState scroll;
    public final float viewportW, viewportH;

    private DynamicSceneState(ScrollState scroll, float viewportW, float viewportH) {
        this.scroll = scroll != null ? scroll : new ScrollState();
        this.viewportW = viewportW;
        this.viewportH = viewportH;
    }

    public static DynamicSceneState of(ScrollState scroll, float viewportW, float viewportH) {
        return new DynamicSceneState(scroll, viewportW, viewportH);
    }

    public float scrollX(SceneLayer scroller) {
        return scroller != null && scroller.node != null ? scroll.getScrollX(scroller.node) : 0f;
    }

    public float scrollY(SceneLayer scroller) {
        return scroller != null && scroller.node != null ? scroll.getScrollY(scroller.node) : 0f;
    }

    /** スクロール中のアンカーへの追従（G8）。追従しなければ {@code null}。 */
    public float[] anchorShift(SceneLayer layer) {
        return layer.follow != null ? SceneAnchorFollow.shift(layer.follow, scroll) : null;
    }

    /** {@code position: sticky} の貼り付きのずれ。ずれが無ければ {@code null}。 */
    public float[] stickyOffset(SceneLayer layer, float unstuckX, float unstuckY,
                                float portX, float portY, float portW, float portH,
                                float cbLeft, float cbTop, float cbRight, float cbBottom) {
        if (layer.sticky == null) return null;
        return layer.sticky.offset(unstuckX, unstuckY, layer.w, layer.h,
                portX, portY, portW, portH, cbLeft, cbTop, cbRight, cbBottom, viewportW, viewportH);
    }

    /** {@code content-visibility: auto} の箱がビューポートの関連範囲にかかるか。 */
    public boolean nearViewport(float absX, float absY, float w, float h) {
        float m = CONTENT_VISIBILITY_MARGIN;
        return absX + Math.max(0f, w) >= -m && absX <= viewportW + m
                && absY + Math.max(0f, h) >= -m && absY <= viewportH + m;
    }
}
