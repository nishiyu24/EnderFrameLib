package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;
import java.util.List;

/**
 * 走査器（{@link SceneWalker}）の 1 フレームの、保持層にできるスクロールコンテナの中身の範囲（M3b-4b-3b。保持層を中身の節に結ぶ）。
 *
 * <p>中身を 1 つの節にまとめたコンテナごとに、中身の節の位置・子孫の項目の範囲 {@code [from, to)}・中身の同一性の印・描画側の答えを持つ。
 * 印はコンテナの層の子の並び（{@code SceneLayer#children}。組むときに {@link ScrollContentInfo#contentToken} に持たせる）: 中身の層が使い回される限り同じ参照で、スクロール量に依らない
 * （走査器はコンテナをスクロール 0 で組む）。子孫が描画順で連続しない（中身の外の項目が間に入る。重なりの文脈を作らないコンテナの中身の文字は
 * 祖先のインラインの段で描かれる）コンテナは、重ねると重なりの順が変わるので外す。並びは外側が先（先頭の昇順、同じなら長い方）。</p>
 *
 * <p>フレームをまたいで何も持たない（配列は使い回すだけ）。スレッドセーフではない。</p>
 */
final class ScrollContentRanges {
    int[] node = new int[4], from = new int[4], to = new int[4];
    Object[] token = new Object[4];
    RetainPlan[] plan = new RetainPlan[4];
    int count;
    private int[] descendants = new int[4];
    /** 項目の位置 → 範囲の番号（中身の節でなければ -1）。 */
    private int[] slotOf = new int[64];

    /** 中身の範囲を、走査器の項目 {@code items[0, n)}・親の位置 {@code parentIdx}・鍵の索引 {@code indexOf} から求める。 */
    void compute(DisplayItem[] items, int n, int[] parentIdx, Long2IntOpenHashMap indexOf, List<ScrollContentInfo> contents) {
        clear();
        for (int c = 0, cn = contents.size(); c < cn; c++) {
            ScrollContentInfo info = contents.get(c);
            int idx = indexOf.get(DisplayItem.keyOf(info.contentId()));
            if (idx < 0 || items[idx].type() != ElementType.NODE || !info.contentId().equals(items[idx].id())) continue;
            if (info.contentToken() == null) continue;
            if (count == node.length) grow(count * 2);
            node[count] = idx;
            from[count] = Integer.MAX_VALUE;
            to[count] = -1;
            descendants[count] = 0;
            token[count] = info.contentToken();
            plan[count] = RetainPlan.NONE;
            count++;
        }
        if (count == 0) return;
        if (slotOf.length < n) slotOf = new int[Math.max(n, slotOf.length * 2)];
        Arrays.fill(slotOf, 0, n, -1);
        for (int k = 0; k < count; k++) slotOf[node[k]] = k;
        for (int j = 0; j < n; j++) {
            int p = parentIdx[j];
            for (int guard = 0; p >= 0 && guard < 128; guard++) {
                int k = slotOf[p];
                if (k >= 0) {
                    if (j < from[k]) from[k] = j;
                    if (j > to[k]) to[k] = j;
                    descendants[k]++;
                }
                p = parentIdx[p];
            }
        }
        int kept = 0;
        for (int k = 0; k < count; k++) {
            if (descendants[k] == 0 || to[k] - from[k] + 1 != descendants[k]) continue; // 子孫が連続していない
            node[kept] = node[k]; from[kept] = from[k]; to[kept] = to[k] + 1; token[kept] = token[k]; plan[kept] = RetainPlan.NONE;
            kept++;
        }
        Arrays.fill(token, kept, count, null);
        count = kept;
        for (int k = 1; k < count; k++) { // 数個なので挿入整列（外側が先）
            for (int m = k; m > 0 && (from[m - 1] > from[m] || (from[m - 1] == from[m] && to[m - 1] < to[m])); m--) swap(m - 1, m);
        }
    }

    /** 位置 {@code i} から始まる、描画側が {@code answer} と答えた範囲の番号（無ければ -1）。 */
    int at(int i, RetainPlan answer) {
        for (int k = 0; k < count; k++) if (from[k] == i && plan[k] == answer) return k;
        return -1;
    }

    /** 印の参照を手放す（フレームの終わり）。 */
    void clear() {
        Arrays.fill(token, 0, count, null);
        count = 0;
    }

    private void grow(int cap) {
        node = Arrays.copyOf(node, cap); from = Arrays.copyOf(from, cap); to = Arrays.copyOf(to, cap);
        descendants = Arrays.copyOf(descendants, cap); token = Arrays.copyOf(token, cap); plan = Arrays.copyOf(plan, cap);
    }

    private void swap(int a, int b) {
        int t = node[a]; node[a] = node[b]; node[b] = t;
        t = from[a]; from[a] = from[b]; from[b] = t;
        t = to[a]; to[a] = to[b]; to[b] = t;
        Object o = token[a]; token[a] = token[b]; token[b] = o;
        RetainPlan r = plan[a]; plan[a] = plan[b]; plan[b] = r;
    }
}
