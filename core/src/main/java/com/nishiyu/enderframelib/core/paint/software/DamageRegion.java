package com.nishiyu.enderframelib.core.paint.software;

import java.util.Arrays;

/**
 * 1 フレームで塗り直す範囲（デバイス画素）と、その範囲が掛かる 64×64 タイルのビット列。
 *
 * <p>差分描画（{@link SoftwareRenderBackend}）は、変更のあった項目の旧範囲と新範囲をここへ足し、
 * {@link #seal()} の後に {@link #isTileDirty(int, int)} が真のタイルだけを {@code clearColor} で塗り直す。
 * 描画側のクリップは {@link #x0()}〜{@link #y1()} の外接矩形で、タイル単位の塗り直し範囲はその上位集合になる
 * （外接矩形の中の全タイルを {@link #seal()} が印付けるので、矩形内を描いても二重塗りにはならない）。</p>
 *
 * <p>スレッドセーフではない（描画は 1 スレッド）。</p>
 */
final class DamageRegion {
    static final int TILE = 64;

    private int width, height, tilesX, tilesY;
    /** タイルごとの汚れ（bit = ty * tilesX + tx）。 */
    private long[] tiles = new long[0];
    private int x0, y0, x1, y1;

    DamageRegion() {
        reset(0, 0);
    }

    /** 描画先の大きさを設定して空にする。 */
    void reset(int width, int height) {
        this.width = Math.max(0, width);
        this.height = Math.max(0, height);
        this.tilesX = (this.width + TILE - 1) / TILE;
        this.tilesY = (this.height + TILE - 1) / TILE;
        int words = (tilesX * tilesY + 63) >>> 6;
        if (tiles.length != words) tiles = new long[words];
        else Arrays.fill(tiles, 0L);
        x0 = y0 = Integer.MAX_VALUE;
        x1 = y1 = Integer.MIN_VALUE;
    }

    /** 描画先の外へは広げずに範囲を足す（空の範囲は無視する）。 */
    void add(int ax0, int ay0, int ax1, int ay1) {
        ax0 = Math.max(0, ax0);
        ay0 = Math.max(0, ay0);
        ax1 = Math.min(width, ax1);
        ay1 = Math.min(height, ay1);
        if (ax1 <= ax0 || ay1 <= ay0) return;
        if (ax0 < x0) x0 = ax0;
        if (ay0 < y0) y0 = ay0;
        if (ax1 > x1) x1 = ax1;
        if (ay1 > y1) y1 = ay1;
        int tx0 = ax0 / TILE, tx1 = (ax1 - 1) / TILE;
        int ty0 = ay0 / TILE, ty1 = (ay1 - 1) / TILE;
        for (int ty = ty0; ty <= ty1; ty++) {
            int row = ty * tilesX;
            for (int tx = tx0; tx <= tx1; tx++) {
                int bit = row + tx;
                tiles[bit >>> 6] |= 1L << (bit & 63);
            }
        }
    }

    /**
     * 外接矩形をタイルの境界まで広げ、その中のタイルをすべて汚れとして印付ける。描画クリップもこの広げた矩形にするので、
     * 矩形の中に「汚れていないタイル」が残らない（残すと、そのタイルの前フレームの絵の上へ二重に描いてしまう）。
     * タイルの端は矩形の外へはみ出すため、広げた矩形の外の画素も消える。描画側は必ずこの矩形を塗り直すこと。
     */
    void seal() {
        if (isEmpty()) return;
        int tx0 = x0 / TILE, tx1 = (x1 - 1) / TILE;
        int ty0 = y0 / TILE, ty1 = (y1 - 1) / TILE;
        x0 = tx0 * TILE;
        y0 = ty0 * TILE;
        x1 = Math.min(width, (tx1 + 1) * TILE);
        y1 = Math.min(height, (ty1 + 1) * TILE);
        for (int ty = ty0; ty <= ty1; ty++) {
            int row = ty * tilesX;
            for (int tx = tx0; tx <= tx1; tx++) {
                int bit = row + tx;
                tiles[bit >>> 6] |= 1L << (bit & 63);
            }
        }
    }

    boolean isEmpty() {
        return x1 <= x0 || y1 <= y0;
    }

    int x0() { return x0; }
    int y0() { return y0; }
    int x1() { return x1; }
    int y1() { return y1; }

    /** 外接矩形（空なら null）。 */
    int[] bounds() {
        return isEmpty() ? null : new int[]{x0, y0, x1, y1};
    }

    boolean intersects(int ax0, int ay0, int ax1, int ay1) {
        return !isEmpty() && ax1 > x0 && ax0 < x1 && ay1 > y0 && ay0 < y1;
    }

    /** {@code [ax0,ax1) × [ay0,ay1)} が外接矩形に完全に入るか（差分描画を続けてよいかの判定）。 */
    boolean covers(int ax0, int ay0, int ax1, int ay1) {
        return !isEmpty() && ax0 >= x0 && ay0 >= y0 && ax1 <= x1 && ay1 <= y1;
    }

    boolean isTileDirty(int tx, int ty) {
        if (tx < 0 || ty < 0 || tx >= tilesX || ty >= tilesY) return false;
        int bit = ty * tilesX + tx;
        return (tiles[bit >>> 6] & (1L << (bit & 63))) != 0L;
    }

    /** 汚れているタイルの数（診断・テスト用）。 */
    int dirtyTileCount() {
        int n = 0;
        for (long word : tiles) n += Long.bitCount(word);
        return n;
    }

    int tilesX() { return tilesX; }

    int tilesY() { return tilesY; }
}
