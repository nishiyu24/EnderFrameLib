package com.nishiyu.enderframelib.core.layout.fragment;

/**
 * 子の断片の並び（Engine v3 M2a）。{@link FlowChunk} の列と、各チャンクの上端（内容ボックス基準の累積和）。不変。
 * 子の位置を確定する費用は {@code O(チャンク数 + 作り直したチャンクの中身)}（8,000 子でも 250 チャンク）。
 *
 * <p>流れの箱が始まる前の子（高さ 0 の素通しの箱・float。{@link FlowChunk#leadCount}）の位置は、親が先頭の余白を自分と
 * 相殺するかで決まるので、チャンク（親に依らず使い回す）ではなくここで解く（{@link #y}。CSS 2 §8.3.1）。</p>
 */
public final class ChunkedFlow {
    public final FlowChunk[] chunks;
    /** {@code chunks[k]} の中の位置に足す縦の位置（内容ボックス基準）。 */
    public final float[] chunkY;
    /** 最初に流れの箱が始まったチャンク（無ければ {@code chunks.length}）。 */
    private final int firstStarted;
    /** 親が先頭の余白を子と相殺するか。 */
    private final boolean collapseTop;

    ChunkedFlow(FlowChunk[] chunks, float[] chunkY, int firstStarted, boolean collapseTop) {
        this.chunks = chunks;
        this.chunkY = chunkY;
        this.firstStarted = firstStarted;
        this.collapseTop = collapseTop;
    }

    /**
     * {@code chunks[k]} の {@code i} 番目の子の border-box の上端（親の内容ボックス基準）。
     *
     * <p>流れの箱が始まる前の子は、余白の領域の上端（＝親の内容ボックスの上端）を基準に置いてある。親が先頭の余白を相殺しない
     * なら、その位置のまま（最初の流れの箱だけが相殺した余白の分下がる）。相殺するなら、素通しの箱の余白は親の余白と
     * 1 つになるので、素通しの箱の上端は親の上端（0）に来る。float は親の内容ボックスの上端から積んだ位置のまま。</p>
     */
    public float y(int k, int i) {
        FlowChunk c = chunks[k];
        boolean leading = k < firstStarted || (k == firstStarted && i < c.leadCount);
        if (!leading) return chunkY[k] + c.y[i];
        return collapseTop && c.frags[i].collapseThrough ? 0 : c.y[i];
    }

    /** 子の断片を順に訪ねる。{@code x}, {@code y} は親の内容ボックス基準の border-box の左上。 */
    public interface Visitor {
        void visit(LayoutFragment child, float x, float y);
    }

    public void forEach(Visitor v) {
        for (int k = 0; k < chunks.length; k++) {
            FlowChunk c = chunks[k];
            for (int i = 0; i < c.frags.length; i++) v.visit(c.frags[i], c.x[i], y(k, i));
        }
    }

    /** 子の断片の総数。 */
    public int size() {
        int n = 0;
        for (FlowChunk c : chunks) n += c.frags.length;
        return n;
    }
}
