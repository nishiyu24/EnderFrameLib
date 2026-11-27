package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.box.RememberedScroll;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.fragment.AnchorScroll;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * スクロールコンテナの中のアンカーへの追従（CSS Anchor Positioning 1 §3.3・§6.5。計画書 §3.2 の G8。M3b-2 で {@link DisplayScene} へ移行）。
 */
public final class SceneAnchorFollow {
    private SceneAnchorFollow() { }

    /** 今のスクロール量 {@code scroll} で、箱をずらす量 {dx, dy}。 */
    public static float[] shift(AnchorScroll a, ScrollState scroll) {
        float sx = 0, sy = 0;
        for (HtmlNode n : a.followed()) {
            sx += scroll.getScrollX(n);
            sy += scroll.getScrollY(n);
        }
        return new float[]{a.x() ? -(sx - a.usedX()) : 0f, a.y() ? -(sy - a.usedY()) : 0f};
    }

    /** ずらした箱が包含ブロックからはみ出し、選び直せる候補があるか。 */
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

    public static boolean any(DisplayScene scene) {
        return scene != null && scene.root.anchoredBelow();
    }

    public static void collect(DisplayScene scene, List<SceneLayer> out) {
        if (scene == null || !scene.root.anchoredBelow()) return;
        collectLayer(scene.root, out);
    }

    private static void collectLayer(SceneLayer layer, List<SceneLayer> out) {
        if (!layer.anchoredBelow()) return;
        if (layer.follow != null) out.add(layer);
        for (SceneChunk c : layer.children.chunks) {
            if (!c.anchoredBelow) continue;
            for (SceneLayer child : c.layers) collectLayer(child, out);
        }
    }

    /** シーン内の箱が追従するスクロールコンテナ（無ければ空）。 */
    public static Set<HtmlNode> followedScrollers(DisplayScene scene) {
        if (scene == null || !scene.root.anchoredBelow()) return Set.of();
        List<SceneLayer> nodes = new ArrayList<>();
        collectLayer(scene.root, nodes);
        Set<HtmlNode> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (SceneLayer n : nodes) {
            if (n.follow.x() || n.follow.y()) Collections.addAll(out, n.follow.followed());
        }
        return out;
    }
}
