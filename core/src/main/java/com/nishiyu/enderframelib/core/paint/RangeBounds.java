package com.nishiyu.enderframelib.core.paint;

import static com.nishiyu.enderframelib.core.paint.PaintSceneDriver.BLOCK;

/**
 * スクロール範囲の項目の縦の範囲の索引。範囲の全項目が「変形なし・不透明度 1・グループなし」（姿勢がスクロール節と同じ）のときだけ作る。
 * {@code prefBottom[j]} は先頭から j 番目までの下端（CSS px）の最大、{@code sufTop[j]} は j 番目から末尾までの上端の最小。どちらも単調なので、
 * 画面外の先頭・末尾の区間は二分探索で正確に求まる（項目が縦に整列している仮定は要らない）。
 */
final class RangeBounds {
    boolean valid;
    boolean eligible;
    /** 項目ごとの下端・上端・高さ（CSS px）。節（NODE）は下端 −∞・上端 +∞（何も描かないので判定に影響しない）。 */
    double[] bottom = new double[0], top = new double[0], height = new double[0];
    /**
     * 倍率 {@code keyScale} でのブロック（{@link #BLOCK} 項目ずつ）の鍵: ブロック内の {@code scale*下端 + 高さ} の最大（全項目が上へ外れているかの判定）、
     * {@code scale*上端 − 高さ} の最小（全項目が下へ外れているかの判定）。表示リストは描画順（背景 → 文字）で並び、縦位置は整列していないので、
     * 範囲全体の二分探索ではなくブロック単位で判定する。
     */
    double[] blockMax = new double[0], blockMin = new double[0];
    double keyScale = Double.NaN;
    /** このフレームで項目を走査してよいブロック（{@link #planWindow} が決める）。 */
    boolean[] keep = new boolean[0];

    void ensureKeys(double scale) {
        if (keyScale == scale) return;
        int n = bottom.length, nb = (n + BLOCK - 1) / BLOCK;
        if (blockMax.length != nb) { blockMax = new double[nb]; blockMin = new double[nb]; keep = new boolean[nb]; }
        for (int b = 0; b < nb; b++) {
            double mx = Double.NEGATIVE_INFINITY, mn = Double.POSITIVE_INFINITY;
            for (int j = b * BLOCK, e = Math.min(n, j + BLOCK); j < e; j++) {
                if (bottom[j] != Double.NEGATIVE_INFINITY) mx = Math.max(mx, scale * bottom[j] + height[j]);
                if (top[j] != Double.POSITIVE_INFINITY) mn = Math.min(mn, scale * top[j] - height[j]);
            }
            blockMax[b] = mx;
            blockMin[b] = mn;
        }
        keyScale = scale;
    }
}
