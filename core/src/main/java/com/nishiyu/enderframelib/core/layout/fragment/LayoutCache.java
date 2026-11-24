package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 単一の 1 層メモ化（Engine v3 M2a。計画書 §2.1-4・§3.3 A-4）。1 ビュー（{@link LayoutSession}）に閉じる。
 *
 * <ul>
 *   <li><b>レイアウト</b>: {@code (BoxNode ==, ConstraintSpace 値等価) → LayoutFragment}。スロットは<b>前回の断片の木そのもの</b>:
 *       親の前回の断片が子の断片（とブロックの流れでは {@link FlowChunk} ごと）を持つので、親が子を測るときはそこから候補を
 *       引いて照合する。節 → 結果の別の表を持たないので、木から外れた箱の結果は木と一緒に GC に回収され、掃除の仕組みが要らない
 *       （断片は自分の箱を参照するので、箱を鍵にした弱参照表では値が鍵を生かして回収されない。それを避けるための形）。</li>
 *   <li><b>2 パス測定の固定少数スロット</b>: flex は 1 つの項目を仮測定と確定測定の異なる制約で測る。その 1 回の整形の間に
 *       使った断片を {@link Slots}（最大 {@link #SLOTS} 個・LRU）に集め、結果の {@link FlowChunk#slots} に残す。次のフレームは
 *       そこから引くので、pass 1 と pass 2 が互いを追い出さない（旧 {@code TentativeSlots} の可変欄の退避・復元は無い）。</li>
 *   <li><b>内在サイズ</b>: {@code (BoxNode ==, MinMaxContext) → MinMaxSizes}。値が鍵を参照しないので、箱を弱参照の鍵にした表で持つ
 *       （{@link BoxNode} は {@code equals} を定義しないので同一性で引ける）。</li>
 * </ul>
 */
public final class LayoutCache {
    /** 1 つの子に覚える断片の最大数（flex の 2 パス × 親の 2 パス）。 */
    public static final int SLOTS = 4;

    private final Map<BoxNode, MinMaxSizes> minMax = new WeakHashMap<>();
    private final Stats stats = new Stats();
    private Map<BoxNode, int[]> perBox;

    /** 1 フレームの観測値（テスト・計測用）。 */
    public static final class Stats {
        int layoutHits, layoutComputes, chunkHits, chunkComputes, minMaxHits, minMaxComputes, slotEvictions;

        public int layoutHits() { return layoutHits; }
        public int layoutComputes() { return layoutComputes; }
        public int chunkHits() { return chunkHits; }
        public int chunkComputes() { return chunkComputes; }
        public int minMaxHits() { return minMaxHits; }
        public int minMaxComputes() { return minMaxComputes; }
        /** 1 回の整形の間に使ったスロットを追い出した回数（pass 1 と pass 2 の相互駆逐）。 */
        public int slotEvictions() { return slotEvictions; }

        void reset() {
            layoutHits = layoutComputes = chunkHits = chunkComputes = minMaxHits = minMaxComputes = slotEvictions = 0;
        }

        @Override
        public String toString() {
            return "layout " + layoutHits + "/" + layoutComputes + " chunk " + chunkHits + "/" + chunkComputes
                    + " minmax " + minMaxHits + "/" + minMaxComputes + " evict " + slotEvictions;
        }
    }

    public Stats stats() { return stats; }

    /** 箱ごとの {整形の回数, 内在サイズの計算の回数} を数える（テスト用。null で数えない）。 */
    public void trackPerBox(boolean on) {
        perBox = on ? new IdentityHashMap<>() : null;
    }

    /** 直前のフレームでの、この箱の {整形の回数, 内在サイズの計算の回数}。 */
    public int[] perBox(BoxNode box) {
        int[] c = perBox != null ? perBox.get(box) : null;
        return c != null ? c : new int[2];
    }

    void beginFrame() {
        stats.reset();
        if (perBox != null) perBox.clear();
    }

    /** 候補 1 つ（ブロックの流れの子）で引く。外れたら整形して返す。 */
    LayoutFragment layout(BoxNode box, ConstraintSpace space, LayoutFragment prior, LayoutSession s) {
        if (prior != null && prior.matches(box, space)) {
            stats.layoutHits++;
            return prior;
        }
        return compute(box, space, prior != null && prior.box != null && prior.box.node == box.node ? prior : null, s);
    }

    /** 候補の集まり（flex の項目）で引く。使った・作った断片は {@code slots} に入る。 */
    LayoutFragment layout(BoxNode box, ConstraintSpace space, Slots slots, LayoutSession s) {
        LayoutFragment hit = slots.find(box, space);
        if (hit != null) {
            stats.layoutHits++;
            return hit;
        }
        LayoutFragment f = compute(box, space, slots.prior(box), s);
        if (slots.add(f)) stats.slotEvictions++;
        return f;
    }

    private LayoutFragment compute(BoxNode box, ConstraintSpace space, LayoutFragment prior, LayoutSession s) {
        stats.layoutComputes++;
        if (perBox != null) perBox.computeIfAbsent(box, k -> new int[2])[0]++;
        return FormattingEngine.of(box).layout(box, space, prior, s);
    }

    /** 内在サイズ（1 層メモ）。 */
    MinMaxSizes minMax(BoxNode box, LayoutSession s) {
        MinMaxSizes m = minMax.get(box);
        if (m != null) {
            stats.minMaxHits++;
            return m;
        }
        stats.minMaxComputes++;
        if (perBox != null) perBox.computeIfAbsent(box, k -> new int[2])[1]++;
        m = FormattingEngine.of(box).computeMinMaxSizes(box, MinMaxContext.NONE, s);
        minMax.put(box, m);
        return m;
    }

    void chunkHit() { stats.chunkHits++; }

    void chunkComputed() { stats.chunkComputes++; }

    /**
     * 1 つの子の候補の集まり（最大 {@link #SLOTS} 個。後ろほど最近使った）。前のフレームの候補で始め、この整形で使った・作った
     * 断片だけを {@link #used()} で残す。
     */
    static final class Slots {
        private final LayoutFragment[] items = new LayoutFragment[SLOTS];
        private final boolean[] used = new boolean[SLOTS];
        private int n;

        /** 前回の最終の断片と、その他のスロット（どちらも null 可）で始める。 */
        Slots(LayoutFragment last, LayoutFragment[] others) {
            if (others != null) {
                for (LayoutFragment f : others) if (f != null && f != last && n < SLOTS - 1) items[n++] = f;
            }
            if (last != null) items[n++] = last;
        }

        LayoutFragment find(BoxNode box, ConstraintSpace space) {
            for (int i = n - 1; i >= 0; i--) {
                if (items[i].matches(box, space)) {
                    touch(i);
                    return items[n - 1];
                }
            }
            return null;
        }

        /** 子の候補を引くための前回の断片（同じ節で一番最近のもの）。 */
        LayoutFragment prior(BoxNode box) {
            for (int i = n - 1; i >= 0; i--) {
                if (items[i].box != null && items[i].box.node == box.node) return items[i];
            }
            return null;
        }

        /** 足す。満杯なら一番古いものを捨てる（捨てたら true）。 */
        boolean add(LayoutFragment f) {
            boolean evicted = false;
            if (n == SLOTS) {
                evicted = used[0];
                System.arraycopy(items, 1, items, 0, SLOTS - 1);
                System.arraycopy(used, 1, used, 0, SLOTS - 1);
                n--;
            }
            items[n] = f;
            used[n++] = true;
            return evicted;
        }

        private void touch(int i) {
            LayoutFragment f = items[i];
            System.arraycopy(items, i + 1, items, i, n - 1 - i);
            System.arraycopy(used, i + 1, used, i, n - 1 - i);
            items[n - 1] = f;
            used[n - 1] = true;
        }

        /** この整形で使った・作った断片（最後に使ったものを除く。1 個だけなら null）。 */
        LayoutFragment[] used(LayoutFragment last) {
            int k = 0;
            for (int i = 0; i < n; i++) if (used[i] && items[i] != last) k++;
            if (k == 0) return null;
            LayoutFragment[] out = new LayoutFragment[k];
            k = 0;
            for (int i = 0; i < n; i++) if (used[i] && items[i] != last) out[k++] = items[i];
            return out;
        }
    }
}
