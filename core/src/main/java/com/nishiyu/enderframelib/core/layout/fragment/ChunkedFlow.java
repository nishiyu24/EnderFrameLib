package com.nishiyu.enderframelib.core.layout.fragment;

/**
 * 子の断片の並び（Engine v3 M2a）。{@link FlowChunk} の列と、各チャンクの上端（内容ボックス基準の累積和）。不変。
 * 子の位置を確定する費用は {@code O(チャンク数 + 作り直したチャンクの中身)}（8,000 子でも 250 チャンク）。
 */
public final class ChunkedFlow {
    public final FlowChunk[] chunks;
    /** {@code chunks[k]} の中の位置に足す縦の位置（内容ボックス基準）。 */
    public final float[] chunkY;

    ChunkedFlow(FlowChunk[] chunks, float[] chunkY) {
        this.chunks = chunks;
        this.chunkY = chunkY;
    }

    /** 子の断片を順に訪ねる。{@code x}, {@code y} は親の内容ボックス基準の border-box の左上。 */
    public interface Visitor {
        void visit(LayoutFragment child, float x, float y);
    }

    public void forEach(Visitor v) {
        for (int k = 0; k < chunks.length; k++) {
            FlowChunk c = chunks[k];
            for (int i = 0; i < c.frags.length; i++) v.visit(c.frags[i], c.x[i], chunkY[k] + c.y[i]);
        }
    }

    /** 子の断片の総数。 */
    public int size() {
        int n = 0;
        for (FlowChunk c : chunks) n += c.frags.length;
        return n;
    }
}
