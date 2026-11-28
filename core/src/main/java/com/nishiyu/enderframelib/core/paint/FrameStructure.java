package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import java.util.Arrays;
import java.util.List;

/**
 * 組んだ表示項目の列の階層（M3b-4c-2b-3b-1。計画書 §3.4）: 項目ごとの親（変形・不透明度・グループを引き継ぐ節の錨）の位置と、保持層に
 * できるスクロールコンテナの中身の範囲。列を組む側（{@link SceneDisplayListAdapter}）がシーンの層をたどりながら書くので、走査器は
 * 項目の鍵・ID から親を引き直さない（以前の走査器の鍵の索引・{@code ScrollContentRanges} を置き換えた）。
 *
 * <p>保持層の範囲は中身の節の子孫の項目 {@code [from, to)}（描画順で連続するものだけ。中身の外の項目が間に入るコンテナは、重ねると重なりの
 * 順が変わるので外す）と、中身の同一性の印（コンテナの層の子の並び {@code SceneLayer#children}。中身の層が使い回される限り同じ参照で、
 * スクロール量に依らない）。並びは外側が先（先頭の昇順、同じなら長い方）。列を組み直したときだけ作る（列を使い回すフレームは作らない）。</p>
 *
 * <p>マスク画像を描く項目（{@code mask-image} の箱が中身の後ろに出す項目）には印を持つ（{@link #isMask}。M3b-4c-2b-3b-2a）。走査器は印の項目を、
 * 親の錨の層（マスクを持つ箱）の中身ではなくマスク用の描画先に描く。層の中身は錨の子孫で、列の範囲は持たない。</p>
 *
 * <p>作り終えたら不変。シーンの無いフレーム（列だけのフレーム。テストと 1 回だけの描画の道具）は項目の鍵から作る（{@link #fromKeys}。
 * 列だけのフレームとともに M3b-4c-3 で消す）。</p>
 */
public final class FrameStructure {
    /** 項目が無い・親を持たない列の階層。 */
    public static final FrameStructure EMPTY = new FrameStructure(new int[0], 0, new int[0], new int[0], new int[0], new Object[0], 0, null);

    private final int[] parent;
    private final int size;
    private final int[] retainNode, retainFrom, retainTo;
    private final Object[] retainToken;
    private final int retainCount;
    /** マスク画像を描く項目の印（ビットの並び。無ければ null）。 */
    private final long[] mask;

    private FrameStructure(int[] parent, int size, int[] node, int[] from, int[] to, Object[] token, int count, long[] mask) {
        this.parent = parent;
        this.size = size;
        this.retainNode = node;
        this.retainFrom = from;
        this.retainTo = to;
        this.retainToken = token;
        this.retainCount = count;
        this.mask = mask;
    }

    /** 階層を持つ項目の数（列の先頭から。後ろの重ね描きは親を持たない）。 */
    public int size() { return size; }

    /** 位置 {@code i} の項目の親の位置（無ければ -1。{@link #size} の外も -1）。 */
    public int parent(int i) { return i >= 0 && i < size ? parent[i] : -1; }

    /** 位置 {@code i} の項目がマスク画像を描く項目か（親の錨の箱のマスク用の描画先に描く）。 */
    public boolean isMask(int i) {
        return mask != null && i >= 0 && i < size && (mask[i >>> 6] & (1L << i)) != 0L;
    }

    /** 保持層にできる範囲の数。 */
    public int retainCount() { return retainCount; }

    /** 範囲 {@code k} の中身の節の位置。 */
    public int retainNode(int k) { return retainNode[k]; }

    /** 範囲 {@code k} の先頭（中身の節の最初の子孫の位置）。 */
    public int retainFrom(int k) { return retainFrom[k]; }

    /** 範囲 {@code k} の終わり（最後の子孫の次の位置）。 */
    public int retainTo(int k) { return retainTo[k]; }

    /** 範囲 {@code k} の中身の同一性の印。 */
    public Object retainToken(int k) { return retainToken[k]; }

    /**
     * 項目の鍵から作る階層（シーンの無いフレーム。テストと 1 回だけの描画の道具で、列だけのフレームとともに M3b-4c-3 で消す）: 親は項目の
     * 親の鍵（無ければ親の ID の鍵）の項目。保持層の範囲は持たない。
     */
    public static FrameStructure fromKeys(List<DisplayItem> items) {
        int n = items.size();
        if (n == 0) return EMPTY;
        Long2IntOpenHashMap at = new Long2IntOpenHashMap(n);
        at.defaultReturnValue(-1);
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            at.put(it.key(), i);
            if (it.id() != null && !it.id().isEmpty()) {
                long strKey = DisplayItem.keyOf(it.id());
                if (strKey != it.key()) at.putIfAbsent(strKey, i);
            }
        }
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            long pKey = it.hints().parentKey();
            int p = pKey != 0L ? at.get(pKey) : -1;
            if (p < 0) {
                String pid = it.hints().parentId();
                if (pid != null) p = at.get(DisplayItem.keyOf(pid));
            }
            parent[i] = p;
        }
        return new FrameStructure(parent, n, new int[0], new int[0], new int[0], new Object[0], 0, null);
    }

    /**
     * 列を組む側が項目を足す順に書く。親が後から決まる項目（祖先の錨より先に描く {@code ::backdrop}）は {@link #setParent} で直す。
     * 組むたびに {@link #reset} して使い回す（作業用の配列だけを持ち、作った階層とは配列を共有しない）。
     */
    static final class Builder {
        private int[] parent = new int[64];
        private int size;
        private int[] contentNode = new int[4];
        private Object[] contentToken = new Object[4];
        private int contentCount;
        private int[] slotOf = new int[64];
        private long[] mask = new long[1];
        private boolean anyMask;

        /** 空に戻す（印の参照を手放す）。 */
        void reset() {
            if (anyMask) Arrays.fill(mask, 0L);
            anyMask = false;
            size = 0;
            java.util.Arrays.fill(contentToken, 0, contentCount, null);
            contentCount = 0;
        }

        /** 次の項目（位置 {@link #size}）の親を {@code parentPos} にして足す。 */
        void add(int parentPos) {
            if (size == parent.length) parent = Arrays.copyOf(parent, size * 2);
            parent[size++] = parentPos;
        }

        int size() { return size; }

        /** 位置 {@code [from, to)} の項目を、マスク画像を描く項目にする。 */
        void markMask(int from, int to) {
            if (to <= from) return;
            int words = ((to - 1) >>> 6) + 1;
            if (mask.length < words) mask = Arrays.copyOf(mask, Math.max(words, mask.length * 2));
            for (int i = from; i < to; i++) mask[i >>> 6] |= 1L << i;
            anyMask = true;
        }

        /** 位置 {@code pos} の項目の親を {@code parentPos} にする。 */
        void setParent(int pos, int parentPos) { parent[pos] = parentPos; }

        /** 位置 {@code pos} の項目を、中身を 1 つの節にまとめたスクロールコンテナの中身の節にする（{@code token} は中身の同一性の印）。 */
        void markContent(int pos, Object token) {
            if (token == null) return;
            if (contentCount == contentNode.length) {
                contentNode = Arrays.copyOf(contentNode, contentCount * 2);
                contentToken = Arrays.copyOf(contentToken, contentCount * 2);
            }
            contentNode[contentCount] = pos;
            contentToken[contentCount++] = token;
        }

        /** 作り終える（保持層の範囲を求める）。 */
        FrameStructure build() {
            if (size == 0) return EMPTY;
            int[] p = Arrays.copyOf(parent, size);
            long[] maskBits = anyMask ? Arrays.copyOf(mask, ((size - 1) >>> 6) + 1) : null;
            int n = contentCount;
            if (n == 0) return new FrameStructure(p, size, new int[0], new int[0], new int[0], new Object[0], 0, maskBits);
            int[] node = Arrays.copyOf(contentNode, n), from = new int[n], to = new int[n], descendants = new int[n];
            Object[] token = Arrays.copyOf(contentToken, n);
            Arrays.fill(from, Integer.MAX_VALUE);
            Arrays.fill(to, -1);
            if (slotOf.length < size) slotOf = new int[Math.max(size, slotOf.length * 2)];
            Arrays.fill(slotOf, 0, size, -1);
            for (int k = 0; k < n; k++) slotOf[node[k]] = k;
            for (int j = 0; j < size; j++) {
                int a = p[j];
                for (int guard = 0; a >= 0 && guard < 128; guard++) {
                    int k = slotOf[a];
                    if (k >= 0) {
                        if (j < from[k]) from[k] = j;
                        if (j > to[k]) to[k] = j;
                        descendants[k]++;
                    }
                    a = p[a];
                }
            }
            int kept = 0;
            for (int k = 0; k < n; k++) {
                if (descendants[k] == 0 || to[k] - from[k] + 1 != descendants[k]) continue; // 子孫が描画順で連続していない
                node[kept] = node[k];
                from[kept] = from[k];
                to[kept] = to[k] + 1;
                token[kept] = token[k];
                kept++;
            }
            for (int k = 1; k < kept; k++) { // 数個なので挿入整列（外側が先）
                for (int m = k; m > 0 && (from[m - 1] > from[m] || (from[m - 1] == from[m] && to[m - 1] < to[m])); m--) {
                    int t = node[m - 1]; node[m - 1] = node[m]; node[m] = t;
                    t = from[m - 1]; from[m - 1] = from[m]; from[m] = t;
                    t = to[m - 1]; to[m - 1] = to[m]; to[m] = t;
                    Object o = token[m - 1]; token[m - 1] = token[m]; token[m] = o;
                }
            }
            return new FrameStructure(p, size, Arrays.copyOf(node, kept), Arrays.copyOf(from, kept), Arrays.copyOf(to, kept),
                    Arrays.copyOf(token, kept), kept, maskBits);
        }
    }
}
