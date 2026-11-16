package com.nishiyu.enderframelib.core.paint.model;

/**
 * 表示リストのスナップショット（{@code ImmutableItemList}）の 32 要素ブロックごとに、最後に項目のセルが書かれたときの通し番号を持つ時計
 * （B9 の M6）。項目のセルは自分の位置を知っていて、書くたびにここへ印を付ける。消費者は「前に見た番号より新しい印のブロック」
 * だけを項目単位で調べればよく、書き込みのたびにブロックを複製して変化を知らせる必要が無い。
 * 同じ長さの差分パッチの間は同じ時計を使い（位置が変わらないため）、項目の増減では新しく作る。
 */
public final class ChunkClock {
    private final int[] chunkEpoch;
    private int maxEpoch;
    private int maxPaintEpoch;

    public ChunkClock(int chunks) {
        this.chunkEpoch = new int[chunks];
    }

    /** 位置 {@code pos} の項目が通し番号 {@code epoch} で書かれた（塗りも含むなら {@code paint}）。 */
    void mark(int pos, int epoch, boolean paint) {
        int c = pos >>> 5;
        if (c < chunkEpoch.length) chunkEpoch[c] = epoch;
        maxEpoch = epoch;
        if (paint) maxPaintEpoch = epoch;
    }

    /** ブロック {@code c} が最後に書かれたときの通し番号（一度も書かれていなければ 0）。 */
    public int chunkEpoch(int c) {
        return chunkEpoch[c];
    }

    /** どれかの項目が最後に書かれたときの通し番号。 */
    public int maxEpoch() {
        return maxEpoch;
    }

    /** 塗り（色・背景色・不透明度）が最後に書かれたときの通し番号。移動量だけの書き込みでは進まない。 */
    public int maxPaintEpoch() {
        return maxPaintEpoch;
    }
}
