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
    public boolean hasOrdinalMarkers() {
        for (int c = 0, n = chunkCount(); c < n; c++) if (chunk(c).hasOrdinalMarkers()) return true;
        return false;
    }

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

    /** どれかのチャンクが表の内部の箱を含むか（O(チャンク数)。{@link TableBoxes}）。 */
    public boolean hasTableInternal() {
        for (int c = 0, n = chunkCount(); c < n; c++) if (chunk(c).hasTableInternal()) return true;
        return false;
    }

    /** どれかのチャンクが {@code display: contents} の子を含むか（O(チャンク数)）。 */
    public boolean hasContents() {
        for (int c = 0, n = chunkCount(); c < n; c++) if (chunk(c).hasContents()) return true;
        return false;
    }

    /**
     * レイアウトが並べる子を全部、列にする（O(子の数)。インラインの整形・内在サイズ・flex の項目用）。{@code display: contents} の子は
     * その中身（{@link BoxNode#flow}）へ展開する。
     */
    public java.util.List<BoxNode> toList() {
        java.util.List<BoxNode> out = new java.util.ArrayList<>(size());
        addTo(this, out);
        return out;
    }

    private static void addTo(BoxChildren kids, java.util.List<BoxNode> out) {
        for (int c = 0, n = kids.chunkCount(); c < n; c++) {
            BoxChunk ch = kids.chunk(c);
            for (int i = 0; i < ch.size(); i++) {
                BoxNode b = ch.get(i);
                if (b.kind == BoxKind.CONTENTS) addTo(b.flow, out);
                else out.add(b);
            }
        }
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

    /**
     * レイアウトの入力の木の側の並び（M2c。{@link BoxNode#layout}）。全ての子が自分自身をレイアウトの入力とするなら同じ参照。そうで
     * なければ子をレイアウトの入力の箱に替えた並びで、子のレイアウトの入力の箱が {@code prev}（前回のレイアウトの入力の箱の並び）と
     * 1 つずつ同じなら {@code prev} そのもの（最新の木の側でチャンクの切れ目が変わっても〔隣り合う 2 つを作り直すと 1 つにまとまる〕
     * レイアウトの入力は前回の参照のまま）。違えば同じ形の位置のチャンクごとに前回の参照を使う。
     */
    BoxChildren layoutView(BoxChildren prev) {
        int n = chunkCount();
        int first = 0;
        while (first < n && chunk(first).self()) first++;
        if (first == n) return this;
        if (prev != null && prev.size() == size() && sameLayoutItems(prev)) return prev;
        BoxChunk[] out = new BoxChunk[n];
        boolean aligned = prev != null && prev.chunkCount() == n;
        for (int c = 0; c < n; c++) out[c] = chunk(c).layoutChunk(aligned ? prev.chunk(c) : null);
        return n == 1 ? out[0] : new Chunked(out, size());
    }

    /** 子のレイアウトの入力の箱が {@code prev} の子と順に同じ参照か（チャンクの切れ目は問わない）。 */
    private boolean sameLayoutItems(BoxChildren prev) {
        int pc = 0, pi = 0;
        BoxChunk p = prev.chunk(0);
        for (int c = 0, n = chunkCount(); c < n; c++) {
            BoxChunk ch = chunk(c);
            for (int i = 0, m = ch.size(); i < m; i++) {
                while (pi == p.size()) {
                    p = prev.chunk(++pc);
                    pi = 0;
                }
                if (ch.get(i).layout != p.get(pi++)) return false;
            }
        }
        return true;
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
    static BoxChildren withPseudos(BoxNode marker, BoxNode before, BoxChildren kids, BoxNode after) {
        List<BoxChunk> chunks = new java.util.ArrayList<>(kids.chunkCount() + 3);
        if (marker != null) chunks.add(new BoxChunk(new BoxNode[]{marker}));
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
