package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.FilterOp;

import java.util.List;

/**
 * レイヤー（効果のある箱の描画先）の矩形。画面全体ではなく、箱とその子孫の外接矩形にフィルターが滲む分を足した大きさだけ
 * FBO を確保するための、GL を知らない計算。座標は親の描画先の画素（左下原点）。
 *
 * <ul>
 *   <li>矩形は {@code 中身の外接矩形 + pad + reach} を、親の枠で切ったもの。枠の外は描かれない（どの項目も画面で切る）ので、
 *       枠に接した辺では、ぼかしが縁の画素を引き伸ばす（枠の外へ中身が続くものとして混ぜる）従来の見た目を保つため、
 *       サンプルの範囲をその辺で止める（{@link #clampUv}）。</li>
 *   <li>FBO の大きさは {@link #bucket} で粗く丸め、プールが使い回せるようにする。</li>
 * </ul>
 *
 * @param x 親の描画先での左端
 * @param y 同じく下端
 * @param w 幅（0 なら空。親の枠に影響しない）
 * @param h 高さ
 */
public record LayerBounds(int x, int y, int w, int h) {

    /** FBO の辺の最小の長さ（画素）。これより小さく丸めない。 */
    public static final int BUCKET = 32;
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

    /**
     * ぼかしの標本の uv の範囲 {@code {下限x, 下限y, 上限x, 上限y}}（大きさ {@code texW x texH} の FBO の左下に矩形を置いたとき）。
     * 枠の右・上に接した辺は矩形の縁で止める（縁の画素を引き伸ばす）。それ以外の辺は FBO の端まで（余白の透明を読む）。
     * 左・下の辺は矩形の原点が枠の原点のとき FBO の端と一致する。
     */
    public float[] clampUv(int frameW, int frameH, int texW, int texH) {
        return new float[]{0f, 0f, x + w >= frameW ? (float) w / texW : 1f, y + h >= frameH ? (float) h / texH : 1f};
    }

    /** 親の枠（{@code [0, frameW] x [0, frameH]}）の中に見える部分 {@code {x, y, w, h}}。見える所が無ければ null。 */
    public int[] visible(int frameW, int frameH) {
        long x0 = Math.max(0, x), y0 = Math.max(0, y);
        long x1 = Math.min(frameW, (long) x + w), y1 = Math.min(frameH, (long) y + h);
        if (x1 <= x0 || y1 <= y0) return null;
        return new int[]{(int) x0, (int) y0, (int) (x1 - x0), (int) (y1 - y0)};
    }

    /**
     * FBO の辺の長さ。{@link #BUCKET} 以上の 2 の累乗に切り上げる。ただし枠より大きくはしない（矩形が枠を超えているときは
     * 矩形そのまま）。
     *
     * <p>2 の累乗に丸めるのは、箱ごとに大きさが違っても、プールが同じ大きさの FBO を使い回せるようにするため
     * （大きさの種類が多いと、使い回せず作り直しが増える）。
     */
    public static int bucket(int size, int frame) {
        if (size <= 0) return 1;
        long rounded = BUCKET;
        while (rounded < size) rounded <<= 1;
        return (int) Math.min(rounded, Math.max(size, frame));
    }

    /** 保持した絵を使い回すとき、箱の拡縮が元の何倍までなら引き伸ばして使うか（これを超えたら描き直す）。 */
    public static final float MIN_REUSE_SCALE = 0.5f;
    public static final float MAX_REUSE_SCALE = 2f;

    /**
     * 箱の座標が画素になる変換 {@code {ex.x, ex.y, ey.x, ey.y, tx, ty}}（{@code frag = (ex.x*x + ey.x*y + tx, ex.y*x + ey.y*y + ty)}）が
     * 変わった（移動・回転・拡縮した）とき、新しい画素から、保持した絵（{@code oldMap} で描き、その矩形の左下が
     * {@code (rx, ry)}）のテクスチャの画素への変換 {@code {l00, l01, l10, l11, tx, ty}}。
     * {@code tex = (l00*fx + l01*fy + tx, l10*fx + l11*fy + ty)}。
     * 逆変換できない、または拡縮が {@link #MIN_REUSE_SCALE}〜{@link #MAX_REUSE_SCALE} を外れるなら null（描き直す）。
     */
    public static float[] reuseMap(float[] oldMap, float[] newMap, int rx, int ry) {
        float a0 = oldMap[0], b0 = oldMap[1], c0 = oldMap[2], d0 = oldMap[3];
        float a1 = newMap[0], b1 = newMap[1], c1 = newMap[2], d1 = newMap[3];
        float det0 = a0 * d0 - c0 * b0, det1 = a1 * d1 - c1 * b1;
        if (!Float.isFinite(det0) || !Float.isFinite(det1) || Math.abs(det0) < 1e-9f || Math.abs(det1) < 1e-9f) return null;
        float ratio = (float) Math.sqrt(Math.abs(det1 / det0));
        if (!(ratio >= MIN_REUSE_SCALE && ratio <= MAX_REUSE_SCALE) || (det0 > 0) != (det1 > 0)) return null;
        // L = A0 * A1^-1
        float l00 = (a0 * d1 - c0 * b1) / det1, l01 = (c0 * a1 - a0 * c1) / det1;
        float l10 = (b0 * d1 - d0 * b1) / det1, l11 = (d0 * a1 - b0 * c1) / det1;
        float tx = oldMap[4] - (l00 * newMap[4] + l01 * newMap[5]) - rx;
        float ty = oldMap[5] - (l10 * newMap[4] + l11 * newMap[5]) - ry;
        return new float[]{l00, l01, l10, l11, tx, ty};
    }

    /**
     * 保持した絵の矩形 {@code r}（{@code oldMap} で描いたときの画素）が、新しい変換 {@code newMap} で画素のどこに写るか（囲む矩形。
     * 縁の補間のために 1 画素広げる）。逆変換できなければ null。
     */
    public static LayerBounds movedBounds(float[] oldMap, float[] newMap, LayerBounds r) {
        float det0 = oldMap[0] * oldMap[3] - oldMap[2] * oldMap[1];
        if (!Float.isFinite(det0) || Math.abs(det0) < 1e-9f) return null;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float[][] corners = {{r.x, r.y}, {r.x + r.w, r.y}, {r.x + r.w, r.y + r.h}, {r.x, r.y + r.h}};
        for (float[] c : corners) {
            float fx = c[0] - oldMap[4], fy = c[1] - oldMap[5];
            // 箱の座標 = A0^-1 * (cf - t0)
            float bx = (oldMap[3] * fx - oldMap[2] * fy) / det0, by = (-oldMap[1] * fx + oldMap[0] * fy) / det0;
            float nx = newMap[0] * bx + newMap[2] * by + newMap[4], ny = newMap[1] * bx + newMap[3] * by + newMap[5];
            minX = Math.min(minX, nx);
            maxX = Math.max(maxX, nx);
            minY = Math.min(minY, ny);
            maxY = Math.max(maxY, ny);
        }
        if (!Float.isFinite(minX) || !Float.isFinite(maxX) || !Float.isFinite(minY) || !Float.isFinite(maxY)) return null;
        int x0 = (int) Math.floor(minX) - 1, y0 = (int) Math.floor(minY) - 1;
        return new LayerBounds(x0, y0, (int) Math.ceil(maxX) + 1 - x0, (int) Math.ceil(maxY) + 1 - y0);
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
