package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.FilterOp;

import java.util.List;

/**
 * レイヤー（効果のある箱の描画先）の矩形。画面全体ではなく、箱とその子孫の外接矩形にフィルターが滲む分を足した大きさだけ
 * FBO を確保するための、GL を知らない計算。座標は親の描画先の画素（左下原点）。
 *
 * <ul>
 *   <li>矩形は {@code 中身の外接矩形 + pad + reach} を、親の枠で切ったもの。枠の外は描かれない（どの項目も画面で切る）ので、
 *       枠に接した辺では FBO の縁を枠の縁に揃え、ぼかしが縁の画素を引き伸ばす（枠の外へ中身が続くものとして混ぜる）
 *       従来の見た目を保つ（{@link #textureWidth}）。</li>
 *   <li>FBO の大きさは {@link #bucket} で粗く丸め、プールが使い回せるようにする（枠に接した辺を除く）。</li>
 * </ul>
 *
 * @param x 親の描画先での左端
 * @param y 同じく下端
 * @param w 幅（0 なら空。親の枠に影響しない）
 * @param h 高さ
 */
public record LayerBounds(int x, int y, int w, int h) {

    /** FBO の大きさを丸める単位（画素）。 */
    public static final int BUCKET = 64;
    /** フィルターの滲みの上限（画素）。これ以上は広げない（巨大な標準偏差で FBO が際限なく大きくなるのを防ぐ）。 */
    public static final int MAX_REACH = 2048;

    public static final LayerBounds EMPTY = new LayerBounds(0, 0, 0, 0);

    public boolean isEmpty() {
        return w <= 0 || h <= 0;
    }

    /** 枠いっぱい（効果の範囲が分からないときの従来の大きさ）。 */
    public static LayerBounds full(int frameW, int frameH) {
        return new LayerBounds(0, 0, Math.max(0, frameW), Math.max(0, frameH));
    }

    /**
     * 中身の外接矩形（親の描画先の画素）から、レイヤーの矩形を決める。
     *
     * @param pad   アンチエイリアス・字形のはみ出しの余白
     * @param reach フィルターが滲む分（{@link #reach}）
     * @return 数値が有限でなければ null（呼び出し側は枠いっぱいにする）。枠の外へ出きっていれば {@link #EMPTY}
     */
    public static LayerBounds of(float minX, float minY, float maxX, float maxY, int pad, int reach, int frameW, int frameH) {
        if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(maxX) || !Float.isFinite(maxY)) return null;
        reach = Math.max(0, Math.min(MAX_REACH, reach));
        long margin = (long) Math.max(0, pad) + reach;
        long x0 = Math.max(0L, (long) Math.floor(minX) - margin);
        long y0 = Math.max(0L, (long) Math.floor(minY) - margin);
        long x1 = Math.min(frameW, (long) Math.ceil(maxX) + margin);
        long y1 = Math.min(frameH, (long) Math.ceil(maxY) + margin);
        if (x1 <= x0 || y1 <= y0) return EMPTY;
        return new LayerBounds((int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0));
    }

    /** FBO の幅。枠の右端に接していれば矩形の幅のまま（縁の画素を引き伸ばすため）、そうでなければ {@link #bucket}。 */
    public int textureWidth(int frameW) {
        return x + w >= frameW ? w : bucket(w, frameW);
    }

    /** FBO の高さ。{@link #textureWidth} と同じ。 */
    public int textureHeight(int frameH) {
        return y + h >= frameH ? h : bucket(h, frameH);
    }

    /** 親の枠（{@code [0, frameW] x [0, frameH]}）の中に見える部分 {@code {x, y, w, h}}。見える所が無ければ null。 */
    public int[] visible(int frameW, int frameH) {
        long x0 = Math.max(0, x), y0 = Math.max(0, y);
        long x1 = Math.min(frameW, (long) x + w), y1 = Math.min(frameH, (long) y + h);
        if (x1 <= x0 || y1 <= y0) return null;
        return new int[]{(int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0)};
    }

    /**
     * FBO の辺の長さ。{@link #BUCKET} の倍数に切り上げる（プールが近い大きさを使い回せる）。ただし枠より大きくはしない
     * （矩形が枠を超えているときは矩形そのまま）。
     */
    public static int bucket(int size, int frame) {
        if (size <= 0) return 1;
        long rounded = ((long) size + BUCKET - 1) / BUCKET * BUCKET;
        return (int) Math.min(rounded, Math.max(size, frame));
    }

    /**
     * {@code filter} の操作列が箱の外へ滲ませる幅（画素）。ぼかしは標準偏差の 3 倍、ドロップシャドウはそれにずれを足す。
     * 段は順に掛かるので合計する（多めに見積もる分には、余白が広がるだけで見た目は変わらない）。
     *
     * @param scale 箱の座標 1 が画面の何画素か（拡縮した箱でも足りるように、大きいほうの軸）
     */
    public static int reach(List<FilterOp> ops, float scale) {
        double total = 0;
        for (FilterOp op : ops) {
            switch (op.kind()) {
                case BLUR -> total += 3.0 * op.amount() * scale;
                case DROP_SHADOW -> total += 3.0 * op.amount() * scale + Math.max(Math.abs(op.dx()), Math.abs(op.dy())) * scale;
                default -> {
                }
            }
        }
        if (!Double.isFinite(total)) return MAX_REACH;
        return (int) Math.min(MAX_REACH, Math.ceil(total) + (total > 0 ? 1 : 0));
    }
}
