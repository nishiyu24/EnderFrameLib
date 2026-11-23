package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import java.util.List;

/** スクロール節（{@code _scrollc}）ごとの中身の範囲の表。{@link PaintSceneDriver} が索引を整えるたびに作り直す（表は使い回す）。 */
final class RetainRanges {
    /** スクロール節の添字 → 中身の範囲 {@code [from, to)}（表示リスト上で連続しているものだけ。無ければ from = -1）。 */
    int[] from = new int[0], to = new int[0];
    /** 範囲の先頭の添字 → その範囲のスクロール節の添字（無ければ -1）。 */
    int[] nodeAt = new int[0];
    /** スクロール節の添字（外側の節が先）。無ければ空。 */
    int[] nodes = new int[0];
    /** {@link #compute} の作業表（使い回す）。 */
    private int[] min0 = new int[0], max0 = new int[0], count0 = new int[0];
    private boolean[] cand0 = new boolean[0];

    /** スクロール節ごとの中身の範囲（子孫の添字の最小〜最大。個数と一致して連続しているものだけ）を求める。 */
    void compute(List<DisplayItem> items, int[] parentIdx) {
        int n = items.size();
        IntArrayListLite found = new IntArrayListLite();
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            if (it.type() == ElementType.NODE && it.id().endsWith(PaintSceneDriver.SCROLL_CONTENT_SUFFIX)) found.add(i);
        }
        // 表は使い回す（索引を整えるたびに項目数ぶんの配列を 6 本作っていた。並びの入れ替えの差分は hover の毎フレーム走る。B12）。
        // 大きさは項目数以上で、[0, n) だけを使う（読む側は添字 i < n だけを引く）
        if (from.length < n) {
            int cap = Math.max(n, from.length * 3 / 2);
            from = new int[cap];
            to = new int[cap];
            nodeAt = new int[cap];
            min0 = new int[cap];
            max0 = new int[cap];
            count0 = new int[cap];
            cand0 = new boolean[cap];
        }
        java.util.Arrays.fill(from, -1);
        java.util.Arrays.fill(to, 0);
        java.util.Arrays.fill(nodeAt, -1);
        if (found.size == 0) {
            if (nodes.length != 0) nodes = new int[0];
            return;
        }
        boolean[] candidate = cand0;
        java.util.Arrays.fill(candidate, 0, n, false);
        for (int k = 0; k < found.size; k++) candidate[found.a[k]] = true;
        int[] min = min0, max = max0, count = count0;
        java.util.Arrays.fill(min, 0, n, Integer.MAX_VALUE);
        java.util.Arrays.fill(max, 0, n, -1);
        java.util.Arrays.fill(count, 0, n, 0);
        for (int j = 0; j < n; j++) {
            int p = parentIdx[j];
            for (int guard = 0; p >= 0 && guard < 128; guard++) {
                if (candidate[p]) {
                    if (j < min[p]) min[p] = j;
                    if (j > max[p]) max[p] = j;
                    count[p]++;
                }
                p = parentIdx[p];
            }
        }
        IntArrayListLite valid = new IntArrayListLite();
        for (int k = 0; k < found.size; k++) {
            int c = found.a[k];
            if (count[c] == 0 || max[c] - min[c] + 1 != count[c]) continue; // 連続していない（範囲を飛ばせない）
            from[c] = min[c];
            to[c] = max[c] + 1;
            valid.add(c);
        }
        // 範囲の先頭 → 節。同じ先頭を持つ入れ子は外側（範囲が大きい方）を採る
        for (int k = 0; k < valid.size; k++) {
            int c = valid.a[k];
            int at = from[c];
            int other = nodeAt[at];
            if (other < 0 || to[c] > to[other]) nodeAt[at] = c;
        }
        // 問い合わせは外側の節が先（範囲の先頭の昇順、同じなら範囲の大きい方）
        // 節は数個なので挿入整列（箱詰めの配列と比較の式を作らない）
        int[] order = nodes.length == valid.size ? nodes : new int[valid.size];
        for (int k = 0; k < valid.size; k++) {
            int c = valid.a[k], j = k - 1;
            while (j >= 0 && (from[order[j]] > from[c]
                    || (from[order[j]] == from[c] && to[order[j]] < to[c]))) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = c;
        }
        nodes = order;
    }
}
