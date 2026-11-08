package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * 基底の表示リストに、少数の項目だけを差し替えて見せる読み取り専用のリスト。
 *
 * <p>スクロールやウィンドウのドラッグは、表示リストの 1〜数個の節の移動量だけを変える。そのたびに全項目のリストを複製すると
 * O(N) の割り当てと、リストの同一性に頼る全ての索引（ヒットテスト・描画の走査）の作り直しが走る。このクラスは
 * 差し替えを疎な表（位置の昇順の配列 2 本）で持つので、作るのは O(k)（k = 差し替えた数）、{@link #get} は O(log k)。
 * 基底は不変のリストで、{@link #base()} の同一性で「同じ基底の上の差し替えだけが変わった」ことを判定できる。</p>
 */
public final class OffsetItemList extends AbstractList<DisplayItem> implements RandomAccess {
    private final List<DisplayItem> base;
    private final int[] positions;
    private final DisplayItem[] replacements;

    /** {@code positions} は昇順・重複なし。{@code replacements[i]} が位置 {@code positions[i]} の項目の代わり。 */
    public OffsetItemList(List<DisplayItem> base, int[] positions, DisplayItem[] replacements) {
        if (positions.length != replacements.length) throw new IllegalArgumentException("length");
        this.base = base;
        this.positions = positions;
        this.replacements = replacements;
    }

    public List<DisplayItem> base() { return base; }

    public int overrideCount() { return positions.length; }

    /** {@code k} 番目（位置の昇順）の差し替えの位置。 */
    public int positionAt(int k) { return positions[k]; }

    /** {@code k} 番目（位置の昇順）の差し替えの項目。 */
    public DisplayItem replacementAt(int k) { return replacements[k]; }

    @Override
    public DisplayItem get(int index) {
        int lo = 0, hi = positions.length - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int p = positions[mid];
            if (p == index) return replacements[mid];
            if (p < index) lo = mid + 1; else hi = mid - 1;
        }
        return base.get(index);
    }

    @Override
    public int size() { return base.size(); }
}
