package com.nishiyu.enderframelib.core.box;

/**
 * 子の不変チャンク（Engine v3 M2a）。最大 {@link BoxChildren#MAX_CHUNK} 個の {@link BoxNode} を持つ。
 * 子が {@link BoxChildren#CHUNK} 個以下の親では、これがそのまま子の並び（{@link BoxChildren}）になる。
 *
 * <p>レイアウトはチャンク単位で {@code (BoxChunk ==, ConstraintSpace)} を鍵に {@code FlowChunk} を使い回す。
 * {@code equals} は定義しない（同一性）。</p>
 */
public final class BoxChunk extends BoxChildren {
    /** 子の無い並び（全ての葉が共有する）。 */
    public static final BoxChunk EMPTY = new BoxChunk(new BoxNode[0]);

    private final BoxNode[] items;
    /** 中身の事実（構築時に 1 回だけ求める不変の導出値）: インライン水準の子・ブロック水準の子があるか。 */
    private final boolean inline, block, floats, contents, ordinals, table;
    /** 全ての子が自分自身をレイアウトの入力とするか（{@link BoxNode#layout}。M2c）。 */
    private final boolean self;

    /** {@code items} の所有権を受け取る（呼び出し側は以後書き換えない）。 */
    BoxChunk(BoxNode[] items) {
        this.items = items;
        boolean in = false, bl = false, fl = false, co = false, od = false, tb = false, sf = true;
        for (BoxNode b : items) {
            if (b.layout != b) sf = false;
            if (b.kind.tableInternal()) tb = true;
            if (b.marker != null && b.marker.ordinal != MarkerBoxes.NO_ORDINAL) od = true;
            if (b.kind == BoxKind.CONTENTS) { // 中身の事実を引き継ぐ（中身のチャンクは構築済み）
                co = true;
                in |= b.flow.hasInline();
                bl |= b.flow.hasBlock();
                fl |= b.flow.hasFloat();
                tb |= b.flow.hasTableInternal();
                continue;
            }
            if (b.kind.blockLevel() && b.style.facts.floating) fl = true;
            if (b.kind.blockLevel()) bl = true;
            else if (b.kind == BoxKind.INLINE || b.kind.atomicInline() || (b.kind == BoxKind.TEXT && !b.collapsibleWhitespace())) in = true;
        }
        this.inline = in;
        this.block = bl;
        this.floats = fl;
        this.contents = co;
        this.ordinals = od;
        this.table = tb;
        this.self = sf;
    }

    /** 全ての子が自分自身をレイアウトの入力とするか（M2c）。 */
    boolean self() { return self; }

    /**
     * 子を {@link BoxNode#layout} に替えたチャンク（M2c）。{@code prev}（前回のレイアウトの入力の側の同じ位置のチャンク）と中身が同じなら
     * その参照（描画だけの変化でレイアウトのチャンク単位の照合を外さない）。
     */
    BoxChunk layoutChunk(BoxChunk prev) {
        if (self) return this;
        BoxNode[] out = new BoxNode[items.length];
        for (int i = 0; i < out.length; i++) out[i] = items[i].layout;
        return prev != null && prev.sameItems(out, 0, out.length) ? prev : new BoxChunk(out);
    }

    /** 表の内部の箱（{@link BoxKind#tableInternal()}）を含むか（{@code display: contents} の中身を含む。匿名の表の箱を作るかの判定）。 */
    public boolean hasTableInternal() { return table; }

    /** 番号に依るマーカー（{@link MarkerBoxes}）を持つ子を含むか（親が子の並びを組み直したら数え直す）。 */
    public boolean hasOrdinalMarkers() { return ordinals; }

    /** {@code display: contents} の子を含むか（レイアウトはチャンク単位で使い回さず、展開した列で並べる）。 */
    public boolean hasContents() { return contents; }

    /** float の子（ブロック水準）を含むか。 */
    public boolean hasFloat() { return floats; }

    /** 畳まれない文字の節かインライン要素を含むか。 */
    public boolean hasInline() { return inline; }

    /** ブロック水準の子（{@link BoxKind#blockLevel()}）を含むか。 */
    public boolean hasBlock() { return block; }

    @Override public int size() { return items.length; }
    @Override public int chunkCount() { return 1; }
    @Override public BoxChunk chunk(int i) {
        if (i != 0) throw new IndexOutOfBoundsException(i);
        return this;
    }
    @Override public BoxNode get(int index) { return items[index]; }

    /** 中身が {@code other} の {@code from} から同じ参照で並んでいるか。 */
    boolean sameItems(BoxNode[] other, int from, int to) {
        if (to - from != items.length) return false;
        for (int i = 0; i < items.length; i++) if (items[i] != other[from + i]) return false;
        return true;
    }
}
