package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
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
    /** スクロール量を 0 として読むスクロールコンテナ（{@link #unscrolled}。走査器が中身を焼き込まずに組むとき）。 */
    private final java.util.Set<HtmlNode> unscrolled;

    private DynamicSceneState(ScrollState scroll, float viewportW, float viewportH, java.util.Set<HtmlNode> unscrolled) {
        this.scroll = scroll != null ? scroll : new ScrollState();
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        this.unscrolled = unscrolled;
    }

    public static DynamicSceneState of(ScrollState scroll, float viewportW, float viewportH) {
        return new DynamicSceneState(scroll, viewportW, viewportH, java.util.Set.of());
    }

    /**
     * スクロールコンテナ {@code nodes} のスクロール量を 0 として読む同じ状態（M3b-4b-3）。走査器は中身を 1 つの節にまとめるコンテナを
     * スクロール 0 で組み、スクロール量は中身の節の平行移動として自分で足す（スクロール量を項目に焼き込まない）。
     */
    public DynamicSceneState unscrolled(java.util.Set<HtmlNode> nodes) {
        return nodes.isEmpty() ? this : new DynamicSceneState(scroll, viewportW, viewportH, nodes);
    }

    public float scrollX(SceneLayer scroller) {
        return scroller != null && scroller.node != null && !unscrolled.contains(scroller.node) ? scroll.getScrollX(scroller.node) : 0f;
    }

    public float scrollY(SceneLayer scroller) {
        return scroller != null && scroller.node != null && !unscrolled.contains(scroller.node) ? scroll.getScrollY(scroller.node) : 0f;
    }

    /** スクロール量を 0 として読むコンテナか（{@link #unscrolled}。中身は描くときにスクロール量だけ平行移動する）。 */
    public boolean readsUnscrolled(SceneLayer scroller) {
        return scroller != null && scroller.node != null && unscrolled.contains(scroller.node);
    }

    /** 0 として読むコンテナも含めた今のスクロール量（描くフレームのカリングが中身の見える範囲を求める。M3b-4c-2b-2c）。 */
    public float actualScrollX(SceneLayer scroller) {
        return scroller != null && scroller.node != null ? scroll.getScrollX(scroller.node) : 0f;
    }

    public float actualScrollY(SceneLayer scroller) {
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
