package com.nishiyu.enderframelib.core.kernel.layout;

import java.util.Arrays;

/**
 * 行高の累積和を Fenwick 木（Binary Indexed Tree）で持つ巨大リスト用の配置表。
 * 1 行の高さ変更・末尾追加・「y 座標の行」の逆引きは O(log N)。後続行の座標を書き直さない。
 * 途中への挿入・削除は木を線形時間で組み直す（行ごとのレイアウトはしない。{@code System.arraycopy} と線形構築だけ）。
 * 割り当ては容量が増えるときだけ。
 */
public final class FenwickVirtualList {
    private float[] heights;
    private float[] tree; // 1 起点
    private int size;
    private int highestPow; // size 以下の最大の 2 冪

    public FenwickVirtualList(int initialCapacity) {
        int cap = Math.max(4, initialCapacity);
        heights = new float[cap];
        tree = new float[cap + 1];
    }

    public int size() {
        return size;
    }

    public float height(int row) {
        checkRow(row);
        return heights[row];
    }

    /** 末尾に行を足す。O(log N)。 */
    public void append(float height) {
        if (size == heights.length) grow(size * 2);
        heights[size] = height;
        size++;
        int i = size;
        // 節 i は [i - lowbit(i) + 1, i] の和。下位の節から組み立てる
        float sum = height;
        int lowbit = i & -i;
        for (int j = 1; j < lowbit; j <<= 1) sum += tree[i - j];
        tree[i] = sum;
        highestPow = Integer.highestOneBit(size);
    }

    /** 行の高さを変える。O(log N)。 */
    public void setHeight(int row, float height) {
        checkRow(row);
        float delta = height - heights[row];
        heights[row] = height;
        if (delta == 0f) return;
        for (int i = row + 1; i <= size; i += i & -i) tree[i] += delta;
    }

    /** 行 {@code row} の上端 y（row 行目の直前までの高さの和）。{@code row == size()} なら全体の高さ。O(log N)。 */
    public float offsetOf(int row) {
        if (row < 0 || row > size) throw new IndexOutOfBoundsException("row " + row);
        float sum = 0f;
        for (int i = row; i > 0; i -= i & -i) sum += tree[i];
        return sum;
    }

    public float totalHeight() {
        return offsetOf(size);
    }

    /**
     * y を含む行（上端 ≤ y &lt; 下端）。y &lt; 0 は {@code -1}、y ≥ 全体の高さは {@code size()}。
     * 高さ 0 の行は飛ばす。O(log N)。
     */
    public int rowAt(float y) {
        if (y < 0f) return -1;
        int pos = 0;
        float rest = y;
        for (int step = highestPow; step > 0; step >>= 1) {
            int next = pos + step;
            if (next <= size && tree[next] <= rest) {
                pos = next;
                rest -= tree[next];
            }
        }
        return pos; // 先頭 pos 行は y 以下に収まる = y を含むのは pos 行目
    }

    /** 画面に出る最初の行。 */
    public int firstVisible(float scrollTop) {
        return Math.min(rowAt(Math.max(0f, scrollTop)), size);
    }

    /** 画面に出る最後の行（含む）。下端がちょうど行境界ならその次の行は見えない。 */
    public int lastVisible(float scrollTop, float viewportHeight) {
        float bottom = scrollTop + viewportHeight;
        int r = rowAt(Math.max(0f, bottom));
        if (r < size && offsetOf(r) == bottom) r--;
        return Math.min(r, size - 1);
    }

    /** {@code row} の位置に挿入する。木は線形時間で組み直す。 */
    public void insert(int row, float height) {
        if (row < 0 || row > size) throw new IndexOutOfBoundsException("row " + row);
        if (size == heights.length) grow(size * 2);
        System.arraycopy(heights, row, heights, row + 1, size - row);
        heights[row] = height;
        size++;
        rebuild();
    }

    public void remove(int row) {
        checkRow(row);
        System.arraycopy(heights, row + 1, heights, row, size - row - 1);
        size--;
        rebuild();
    }

    private void rebuild() {
        Arrays.fill(tree, 0, size + 1, 0f);
        for (int i = 1; i <= size; i++) {
            tree[i] += heights[i - 1];
            int parent = i + (i & -i);
            if (parent <= size) tree[parent] += tree[i];
        }
        highestPow = size == 0 ? 0 : Integer.highestOneBit(size);
    }

    private void grow(int cap) {
        heights = Arrays.copyOf(heights, cap);
        tree = Arrays.copyOf(tree, cap + 1);
    }

    private void checkRow(int row) {
        if (row < 0 || row >= size) throw new IndexOutOfBoundsException("row " + row + " size " + size);
    }
}
