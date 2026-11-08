package com.nishiyu.enderframelib.core.paint.software;

/**
 * CPU が画素を塗った範囲 {@code [x0, x1) × [y0, y1)} の和（外接矩形）。空のときは {@code x1 <= x0 || y1 <= y0}。
 * 以前は {@code SoftwareRenderBackend} が 4 本の {@code int} を手書きで初期化・保存・復元していた。
 * 描画は 1 スレッドなので同期しない。
 */
final class DirtyRect {
    private int x0, y0, x1, y1;

    DirtyRect() {
        reset();
    }

    void reset() {
        x0 = y0 = Integer.MAX_VALUE;
        x1 = y1 = Integer.MIN_VALUE;
    }

    boolean isEmpty() {
        return x1 <= x0 || y1 <= y0;
    }

    /** 範囲を広げる（外接矩形）。 */
    void add(int ax0, int ay0, int ax1, int ay1) {
        x0 = Math.min(x0, ax0);
        y0 = Math.min(y0, ay0);
        x1 = Math.max(x1, ax1);
        y1 = Math.max(y1, ay1);
    }

    /** {@code {x0, y0, x1, y1}} の写し（空でも生の値。{@link #set} で戻せる）。 */
    int[] toArray() {
        return new int[]{x0, y0, x1, y1};
    }

    /** {@link #toArray} で取った値へ戻す。 */
    void set(int[] r) {
        x0 = r[0];
        y0 = r[1];
        x1 = r[2];
        y1 = r[3];
    }

    /** 空なら null、そうでなければ {@code {x0, y0, x1, y1}}。 */
    int[] bounds() {
        return isEmpty() ? null : toArray();
    }
}
