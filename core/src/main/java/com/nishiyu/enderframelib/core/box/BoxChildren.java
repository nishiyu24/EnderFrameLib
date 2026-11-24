package com.nishiyu.enderframelib.core.box;

import java.util.List;

/**
 * 子の不変な並び（Engine v3 M2a）。分岐 32 の浅いチャンク列で、子が {@link #CHUNK} 個以下なら単一の {@link BoxChunk}
 * そのもの（追加の間接参照もメモリも無い）、それより多ければ {@link BoxChunk} の配列を持つ。
 *
 * <p>8,000 子の親へ 1 件足しても、作り直すのは変わった位置を含むチャンク（1〜2 個）と上位の配列（250 要素）だけで、
 * 残りのチャンクは同じインスタンス（{@code ==}）のまま共有される。チャンクの長さは可変（1〜{@link #MAX_CHUNK}）で、
 * 挿入で伸び、上限を超えたら半分に割る。境界を固定しない（固定すると挿入位置より後ろの全チャンクがずれて作り直しになる）。</p>
 */
public abstract sealed class BoxChildren permits BoxChunk, BoxChildren.Chunked {
    /** 新しく並べるときのチャンクの長さ。 */
    public static final int CHUNK = 32;
    /** 挿入で伸びたチャンクがこれを超えたら割る。 */
    public static final int MAX_CHUNK = 64;

    BoxChildren() { }

    /** 子の総数。 */
    public abstract int size();

    /** チャンクの数（{@link BoxChunk} 自身は 1）。 */
    public abstract int chunkCount();

    /** {@code i} 番目のチャンク。 */
    public abstract BoxChunk chunk(int i);

    /** どれかのチャンクがインライン水準の子を含むか（O(チャンク数)）。 */
    public boolean hasInline() {
        for (int c = 0, n = chunkCount(); c < n; c++) if (chunk(c).hasInline()) return true;
        return false;
    }

    /** どれかのチャンクがブロック水準の子を含むか（O(チャンク数)）。 */
    public boolean hasBlock() {
        for (int c = 0, n = chunkCount(); c < n; c++) if (chunk(c).hasBlock()) return true;
        return false;
    }

    /** どれかのチャンクが float の子を含むか（O(チャンク数)）。 */
    public boolean hasFloat() {
        for (int c = 0, n = chunkCount(); c < n; c++) if (chunk(c).hasFloat()) return true;
        return false;
    }

    /** 子を全部、列にする（O(子の数)。インラインの整形・内在サイズ用）。 */
    public java.util.List<BoxNode> toList() {
        java.util.List<BoxNode> out = new java.util.ArrayList<>(size());
        for (int c = 0, n = chunkCount(); c < n; c++) {
            BoxChunk ch = chunk(c);
            for (int i = 0; i < ch.size(); i++) out.add(ch.get(i));
        }
        return out;
    }

    /** {@code index} 番目の子（チャンクを順に数える。O(チャンク数)）。 */
    public BoxNode get(int index) {
        for (int c = 0, n = chunkCount(); c < n; c++) {
            BoxChunk ch = chunk(c);
            if (index < ch.size()) return ch.get(index);
            index -= ch.size();
        }
        throw new IndexOutOfBoundsException(index);
    }

    /** 子の列から新しく並べる（{@link #CHUNK} ごとに切る）。 */
    public static BoxChildren of(List<BoxNode> kids) {
        int n = kids.size();
        if (n == 0) return BoxChunk.EMPTY;
        if (n <= CHUNK) return new BoxChunk(kids.toArray(new BoxNode[0]));
        BoxChunk[] chunks = new BoxChunk[(n + CHUNK - 1) / CHUNK];
        for (int c = 0; c < chunks.length; c++) {
            int from = c * CHUNK, to = Math.min(n, from + CHUNK);
            chunks[c] = new BoxChunk(kids.subList(from, to).toArray(new BoxNode[0]));
        }
        return new Chunked(chunks, n);
    }

    /** チャンクの列から並びを作る（空のチャンクは捨てる。1 個なら、そのチャンクそのもの）。 */
    public static BoxChildren ofChunks(List<BoxChunk> chunks) {
        int n = 0, live = 0;
        for (BoxChunk c : chunks) {
            if (c.size() > 0) { n += c.size(); live++; }
        }
        if (live == 0) return BoxChunk.EMPTY;
        if (live == 1) {
            for (BoxChunk c : chunks) if (c.size() > 0) return c;
        }
        BoxChunk[] out = new BoxChunk[live];
        int k = 0;
        for (BoxChunk c : chunks) if (c.size() > 0) out[k++] = c;
        return new Chunked(out, n);
    }

    /**
     * {@code kids} の前後に疑似要素の箱を 1 個ずつのチャンクで足した並び（{@link BoxNode#flow}）。{@code kids} のチャンクは同じ参照のまま
     * 並ぶので、レイアウトのチャンク単位の照合はそのまま当たる。
     */
    static BoxChildren withPseudos(BoxNode before, BoxChildren kids, BoxNode after) {
        List<BoxChunk> chunks = new java.util.ArrayList<>(kids.chunkCount() + 2);
        if (before != null) chunks.add(new BoxChunk(new BoxNode[]{before}));
        for (int c = 0, n = kids.chunkCount(); c < n; c++) chunks.add(kids.chunk(c));
        if (after != null) chunks.add(new BoxChunk(new BoxNode[]{after}));
        return ofChunks(chunks);
    }

    /** 2 個以上のチャンクを持つ並び。 */
    static final class Chunked extends BoxChildren {
        private final BoxChunk[] chunks;
        private final int size;

        Chunked(BoxChunk[] chunks, int size) {
            this.chunks = chunks;
            this.size = size;
        }

        @Override public int size() { return size; }
        @Override public int chunkCount() { return chunks.length; }
        @Override public BoxChunk chunk(int i) { return chunks[i]; }
    }
}
