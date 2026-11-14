package com.nishiyu.enderframelib.core.layout.model;

import com.nishiyu.enderframelib.core.layout.*;
import com.nishiyu.enderframelib.core.layout.model.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * スクロール位置（px。小数を持つ。丸めは描画の 1 箇所）。節そのものをキーにするので、同じ id を持つ別の節とは混ざらない。
 * 節が文書から外れたら位置も一緒に回収されるよう、キーは弱参照で持つ。
 */
public class ScrollState {
    private final Map<HtmlNode, float[]> offsetsByNode = Collections.synchronizedMap(new WeakHashMap<>());

    /** 一度でも位置が書かれたか。書かれていなければ、レイアウトが箱ごとに引く同期付きの弱参照の表を見ずに 0 を返す。 */
    private volatile boolean anyScroll;

    public float getScrollX(HtmlNode node) {
        if (!anyScroll) return 0;
        float[] offset = node != null ? offsetsByNode.get(node) : null;
        return offset != null ? offset[0] : 0;
    }

    public float getScrollY(HtmlNode node) {
        if (!anyScroll) return 0;
        float[] offset = node != null ? offsetsByNode.get(node) : null;
        return offset != null ? offset[1] : 0;
    }

    public void setScroll(HtmlNode node, float x, float y) {
        if (node == null) return;
        anyScroll = true;
        synchronized (offsetsByNode) {
            float[] cur = offsetsByNode.get(node);
            if (cur != null) {
                cur[0] = x;
                cur[1] = y;
            } else {
                offsetsByNode.put(node, new float[]{x, y});
            }
        }
    }

    /** {@code baseline} から位置が変わった節のうち、{@code handled} に含まれないものがあれば true（スナップショットを作らない）。 */
    public boolean hasUncheckedChange(Map<HtmlNode, float[]> baseline, java.util.Set<HtmlNode> handled) {
        if (!anyScroll && baseline.isEmpty()) return false;
        synchronized (offsetsByNode) {
            for (Map.Entry<HtmlNode, float[]> e : offsetsByNode.entrySet()) {
                float[] v = e.getValue();
                if (v[0] == 0f && v[1] == 0f) continue;
                HtmlNode node = e.getKey();
                if (handled.contains(node)) continue;
                float[] was = baseline.get(node);
                if (was == null || was[0] != v[0] || was[1] != v[1]) return true;
            }
            for (Map.Entry<HtmlNode, float[]> e : baseline.entrySet()) {
                HtmlNode node = e.getKey();
                if (handled.contains(node)) continue;
                float[] cur = offsetsByNode.get(node);
                if (cur == null || (cur[0] == 0f && cur[1] == 0f)) return true;
            }
        }
        return false;
    }

    /** 今のスクロール位置の写し（節 → {x, y}）。スクロールした節の数だけ。 */
    public Map<HtmlNode, float[]> snapshot() {
        if (!anyScroll) return Map.of();
        Map<HtmlNode, float[]> copy = null;
        synchronized (offsetsByNode) {
            for (Map.Entry<HtmlNode, float[]> e : offsetsByNode.entrySet()) {
                float[] v = e.getValue();
                if (v[0] != 0 || v[1] != 0) {
                    if (copy == null) copy = new java.util.IdentityHashMap<>();
                    copy.put(e.getKey(), v.clone());
                }
            }
        }
        return copy != null ? copy : Map.of();
    }

    public void clear() {
        offsetsByNode.clear();
        anyScroll = false;
    }
}
