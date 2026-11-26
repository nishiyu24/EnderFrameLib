package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;

/**
 * 1 ビューのレイアウトの入口（Engine v3 M2a）。{@code BoxNode 木 → 不変の LayoutFragment 木}。前回の根の断片を
 * 1 つ覚えておくだけで、それが次のフレームのキャッシュ（子の候補の木）になる。
 */
public final class FragmentLayout {
    private final LayoutSession session;
    private LayoutFragment root;

    public FragmentLayout(LayoutSession session) {
        this.session = session;
    }

    public LayoutSession session() { return session; }

    /** 前回の結果（まだ無ければ null）。 */
    public LayoutFragment root() { return root; }

    /**
     * 初期包含ブロック（ビューポートの幅・高さ）で根から整形する。変わっていない部分木は前回の断片をそのまま使う。読むのは
     * レイアウトの入力の木（{@link BoxNode#layout}。M2c）なので、描画だけの変化では根の断片がそのまま返る。
     */
    public LayoutFragment layout(BoxNode boxRoot, int viewportW, int viewportH) {
        return layout(boxRoot, viewportW, viewportH, true);
    }

    /**
     * {@code fixedRootWidth} が false なら、根の幅を使える幅に固定せず根自身の指定に従わせる（文書の一部の要素を根として組むとき。
     * {@code width} が auto なら使える幅いっぱい）。文書の根はビューポートの幅に固定する。
     */
    LayoutFragment layout(BoxNode boxRoot, int viewportW, int viewportH, boolean fixedRootWidth) {
        session.viewport(viewportW, viewportH);
        session.cache().beginFrame();
        ConstraintSpace space = session.space(viewportW, fixedRootWidth ? viewportW : Float.NaN, Float.NaN, viewportH, ConstraintSpace.FORMATTING_ROOT);
        root = session.cache().layout(boxRoot.layout, space, root, session);
        return root;
    }
}
