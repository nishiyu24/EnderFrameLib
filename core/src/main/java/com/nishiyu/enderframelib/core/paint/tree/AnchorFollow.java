package com.nishiyu.enderframelib.core.paint.tree;

import com.nishiyu.enderframelib.core.box.RememberedScroll;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.fragment.AnchorScroll;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * スクロールコンテナの中のアンカーへの追従（CSS Anchor Positioning 1 §3.3・§6.5。計画書 §3.2 の G8）。スクロール量はレイアウトの入力に
 * しないので、レイアウトが出した {@link AnchorScroll} と今のスクロール量から、描画の側で次を決める。
 *
 * <ul>
 *   <li>{@link #shift}: 箱をずらす量（default scroll shift。{@link PaintView#child} が足す。sticky と同じく見え方の位置だけを変える）。</li>
 *   <li>{@link #needsRecalculation}: アンカーの再計算点か（ずらした箱が包含ブロックからはみ出し、候補が他にある）。ビューは覚えたスクロール量を
 *       今の量（{@link #snapshot}）に置き、台帳の {@code ANCHOR} で箱を組み直す。</li>
 *   <li>{@link #followedScrollers}: 外の箱が追従するスクロールコンテナ（中身を 1 つの節として動かすだけでは外の箱が動かないので、
 *       スクロールだけの更新でも描き直す）。</li>
 * </ul>
 */
public final class AnchorFollow {
    private AnchorFollow() { }

    /** 今のスクロール量 {@code scroll} で、箱をずらす量 {dx, dy}。 */
    public static float[] shift(AnchorScroll a, ScrollState scroll) {
        float sx = 0, sy = 0;
        for (HtmlNode n : a.followed()) {
            sx += scroll.getScrollX(n);
            sy += scroll.getScrollY(n);
        }
        return new float[]{a.x() ? -(sx - a.usedX()) : 0f, a.y() ? -(sy - a.usedY()) : 0f};
    }

    /** ずらした箱が包含ブロックからはみ出し、選び直せる候補があるか（ずらす量が 0 なら選んだときのままなので false）。 */
    public static boolean needsRecalculation(AnchorScroll a, ScrollState scroll) {
        if (!a.retries()) return false;
        float[] d = shift(a, scroll);
        return (d[0] != 0f || d[1] != 0f) && a.overflowsAfter(d[0], d[1]);
    }

    /** 箱が参照するアンカーの上のスクロールコンテナの今の量を写した、覚えたスクロール量。 */
    public static RememberedScroll snapshot(AnchorScroll a, ScrollState scroll) {
        HtmlNode[] ns = a.scrollers();
        float[] off = new float[ns.length * 2];
        for (int i = 0; i < ns.length; i++) {
            off[2 * i] = scroll.getScrollX(ns[i]);
            off[2 * i + 1] = scroll.getScrollY(ns[i]);
        }
        return new RememberedScroll(ns.clone(), off);
    }

    /** 節 {@code n} の追従の入力（無ければ null）。 */
    public static AnchorScroll of(PaintNode n) {
        return n.follow;
    }

    /** 木 {@code root} に追従の入力を持つ節があるか。 */
    public static boolean any(PaintNode root) {
        return root != null && root.anchoredBelow;
    }

    /** 木 {@code root} の、追従の入力を持つ節（{@link PaintNode#anchoredBelow} の部分木だけを辿る）。 */
    public static void collect(PaintNode root, List<PaintNode> out) {
        if (root == null || !root.anchoredBelow) return;
        if (root.follow != null) out.add(root);
        for (PaintNode c : root.children) collect(c, out);
    }

    /** 木 {@code root} の中の箱が追従するスクロールコンテナ（無ければ空）。 */
    public static Set<HtmlNode> followedScrollers(PaintNode root) {
        if (root == null || !root.anchoredBelow) return Set.of();
        List<PaintNode> nodes = new java.util.ArrayList<>();
        collect(root, nodes);
        Set<HtmlNode> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (PaintNode n : nodes) {
            if (n.follow.x() || n.follow.y()) Collections.addAll(out, n.follow.followed());
        }
        return out;
    }
}
