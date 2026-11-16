package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.RenderHints;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 親相対の表示リスト（{@code relativeOrigin} を持つ NODE の下の項目は、その NODE の位置からの相対座標）を、
 * 従来の絶対座標の表示リストへ直す。絶対座標を前提にした消費者（MOD 側のレンダラー・ヒットテスト）との境界で使う。
 *
 * <p>直す項目は、位置 {@code (x, y)}・影の形・シェーダーグラデーション（作るときに原点を引いたものを足し戻す）と、
 * NODE の {@code relativeOrigin} の印。相対の NODE が 1 つも無いリストは、そのまま返す。
 */
public final class AbsoluteItems {
    /** 診断: 絶対座標へ直した回数（相対の NODE があって実際に新しいリストを作った回数）。通常の描画経路では増えない。 */
    public static final java.util.concurrent.atomic.LongAdder CONVERSIONS = new java.util.concurrent.atomic.LongAdder();

    private AbsoluteItems() {
    }

    /** {@code items} が絶対座標なら同じリスト、親相対なら絶対座標へ直した新しいリスト（元のリストは変えない）。 */
    public static List<DisplayItem> of(List<DisplayItem> items) {
        int n = items.size();
        boolean any = false;
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            if (it.type() == ElementType.NODE && it.hints().relativeOrigin()) {
                any = true;
                break;
            }
        }
        if (!any) return items;
        CONVERSIONS.increment();

        Map<Long, Integer> indexOf = new HashMap<>(n * 2);
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            indexOf.put(it.key(), i);
            if (it.id() != null && !it.id().isEmpty()) indexOf.putIfAbsent(DisplayItem.keyOf(it.id()), i);
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            RenderHints h = items.get(i).hints();
            Integer p = null;
            if (h.parentKey() != 0L) p = indexOf.get(h.parentKey());
            if (p == null && h.parentId() != null) p = indexOf.get(DisplayItem.keyOf(h.parentId()));
            parent[i] = p == null || p == i ? -1 : p;
        }
        int[] ox = new int[n], oy = new int[n];
        boolean[] done = new boolean[n];
        List<DisplayItem> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            resolve(items, parent, ox, oy, done, i);
            DisplayItem it = items.get(i);
            int dx = ox[i], dy = oy[i];
            RenderMeta meta = it.meta();
            boolean rel = it.type() == ElementType.NODE && it.hints().relativeOrigin();
            if (dx != 0 || dy != 0 || rel) {
                RenderHints h = it.hints();
                if (rel) h = h.withRelativeOrigin(false);
                if (h.shadowShape() != null && (dx != 0 || dy != 0)) h = h.withShadowShape(h.shadowShape().offset(dx, dy));
                if (h.shaderGradient() != null && (dx != 0 || dy != 0)) h = h.withShaderGradient(h.shaderGradient().offset(dx, dy));
                if (h != it.hints()) meta = (meta != null ? meta : RenderMeta.EMPTY).withHints(h);
                it = it.withPositionAndMeta(it.x() + dx, it.y() + dy, meta);
            }
            out.add(it);
        }
        return out;
    }

    /** 項目 {@code i} の原点（その項目が相対で測る基準の絶対位置）を、親をたどって求める。 */
    private static void resolve(List<DisplayItem> items, int[] parent, int[] ox, int[] oy, boolean[] done, int i) {
        if (done[i]) return;
        // 親は木の上なので、深さの分だけ辿る（循環は親の連鎖の長さで打ち切る）
        int p = parent[i];
        int x = 0, y = 0;
        if (p >= 0 && p != i) {
            if (!done[p]) {
                int guard = 0;
                int cur = p;
                java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
                while (cur >= 0 && !done[cur] && guard++ < items.size()) {
                    stack.push(cur);
                    cur = parent[cur];
                }
                while (!stack.isEmpty()) {
                    int c = stack.pop();
                    int pc = parent[c];
                    int cx = 0, cy = 0;
                    if (pc >= 0 && done[pc]) {
                        DisplayItem pi = items.get(pc);
                        if (pi.type() == ElementType.NODE && pi.hints().relativeOrigin()) {
                            cx = ox[pc] + pi.x();
                            cy = oy[pc] + pi.y();
                        } else {
                            cx = ox[pc];
                            cy = oy[pc];
                        }
                    }
                    ox[c] = cx;
                    oy[c] = cy;
                    done[c] = true;
                }
            }
            DisplayItem pi = items.get(p);
            if (pi.type() == ElementType.NODE && pi.hints().relativeOrigin()) {
                x = ox[p] + pi.x();
                y = oy[p] + pi.y();
            } else {
                x = ox[p];
                y = oy[p];
            }
        }
        ox[i] = x;
        oy[i] = y;
        done[i] = true;
    }
}
