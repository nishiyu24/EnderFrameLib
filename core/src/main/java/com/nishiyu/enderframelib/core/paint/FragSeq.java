package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import java.util.List;

/**
 * 不変の項目の並び（C1。設計: {@code core/DESIGN_FRAGMENT_TREE.md} §3.1・§0.5 M7）。
 * 葉（項目の配列）・所有者つきの葉・2 つの連結・z 付きの寄与・レイヤーの錨の包み・32 分岐の永続ベクタ（{@link Vec}）。
 * どれも生成後は書き換えない。
 */
abstract class FragSeq {
    static final FragSeq EMPTY = new Leaf(new DisplayItem[0]);

    /** 含む項目の総数。 */
    abstract int items();

    /**
     * 項目も所有者の記録も含まないか。所有者つきの葉は、項目が 0 件でも空でない（描いた箱として範囲を記録するため）。
     */
    abstract boolean empty();

    /** 項目を {@code dst} の {@code off} から順に書き、次の書き位置を返す。{@code sink} があれば、所有者の範囲を順に渡す。 */
    abstract int copyTo(DisplayItem[] dst, int off, OwnerSink sink);

    final int copyTo(DisplayItem[] dst, int off) {
        return copyTo(dst, off, null);
    }

    /** 箱が描いた項目の範囲 {@code [start, end)}（展開した配列の中の位置）を、描いた順に受け取る。 */
    interface OwnerSink {
        void owner(LayoutBox box, int start, int end);
    }

    /** レイヤーの錨の項目を、出力の項目数（span）つきの項目へ作り直す関数（{@code DisplayListBuilder.withLayer}）。 */
    interface Anchor {
        DisplayItem wrap(DisplayItem anchor, LayerSpec spec);
    }

    static class Leaf extends FragSeq {
        final DisplayItem[] a;
        Leaf(DisplayItem[] a) { this.a = a; }
        @Override int items() { return a.length; }
        @Override boolean empty() { return a.length == 0; }
        @Override int copyTo(DisplayItem[] dst, int off, OwnerSink sink) {
            System.arraycopy(a, 0, dst, off, a.length);
            return off + a.length;
        }
    }

    /** 箱 {@code owner} が描いた項目（0 件でも「描いた」の記録になる）。 */
    static final class Owned extends Leaf {
        final LayoutBox owner;
        Owned(LayoutBox owner, DisplayItem[] a) { super(a); this.owner = owner; }
        @Override boolean empty() { return false; }
        @Override int copyTo(DisplayItem[] dst, int off, OwnerSink sink) {
            int end = super.copyTo(dst, off, sink);
            if (sink != null) sink.owner(owner, off, end);
            return end;
        }
    }

    static final class Pair extends FragSeq {
        final FragSeq first, second;
        final int items;
        Pair(FragSeq first, FragSeq second) {
            this.first = first;
            this.second = second;
            this.items = first.items() + second.items();
        }
        @Override int items() { return items; }
        @Override boolean empty() { return first.empty() && second.empty(); }
        @Override int copyTo(DisplayItem[] dst, int off, OwnerSink sink) {
            return second.copyTo(dst, first.copyTo(dst, off, sink), sink);
        }
    }

    /** z-index 付きの寄与（スタッキングコンテキストの整列用）。 */
    static final class ZSeq extends FragSeq {
        final int z;
        final FragSeq inner;
        ZSeq(int z, FragSeq inner) { this.z = z; this.inner = inner; }
        @Override int items() { return inner.items(); }
        @Override boolean empty() { return inner.empty(); }
        @Override int copyTo(DisplayItem[] dst, int off, OwnerSink sink) { return inner.copyTo(dst, off, sink); }
    }

    /**
     * レイヤーに描く箱（フィルタ・ブレンド・クリップを持つスタッキングコンテキスト）の出力。展開したとき、先頭の項目（錨）を
     * 出力の項目数（span）つきの項目へ作り直す（旧経路の {@code executeTasks} の層の終わりと同じ。項目が 0 件なら何もしない）。
     */
    static final class Layered extends FragSeq {
        final LayerSpec spec;
        final FragSeq inner;
        final Anchor anchor;
        Layered(LayerSpec spec, FragSeq inner, Anchor anchor) { this.spec = spec; this.inner = inner; this.anchor = anchor; }
        @Override int items() { return inner.items(); }
        @Override boolean empty() { return inner.empty(); }
        @Override int copyTo(DisplayItem[] dst, int off, OwnerSink sink) {
            int end = inner.copyTo(dst, off, sink);
            if (end > off) dst[off] = anchor.wrap(dst[off], spec.withSpan(end - off));
            return end;
        }
    }

    /**
     * 要素（{@link FragSeq}）の永続ベクタ。32 分岐。{@link #set} は根から該当の要素までの節だけを複製する
     * （1 節 = 参照 32 個 ≒ 140 B）。要素数が変わる操作（挿入・削除）は F3 で足す。
     */
    static final class Vec extends FragSeq {
        static final int BITS = 5, WIDTH = 1 << BITS, MASK = WIDTH - 1;
        final FragSeq[] slots;
        /** 0 = 葉の節（slots が要素）、n = slots が level n-1 の {@link Vec}。 */
        final int level;
        final int items;
        /** この節の下の要素の数。 */
        final int len;
        /** この節の下に、空でない要素があるか。 */
        final boolean nonEmpty;

        private Vec(FragSeq[] slots, int level, int items, int len, boolean nonEmpty) {
            this.slots = slots;
            this.level = level;
            this.items = items;
            this.len = len;
            this.nonEmpty = nonEmpty;
        }

        static Vec of(List<? extends FragSeq> elems) {
            int n = elems.size();
            if (n == 0) return new Vec(new FragSeq[0], 0, 0, 0, false);
            int groups = (n + WIDTH - 1) / WIDTH;
            Vec[] cur = new Vec[groups];
            for (int g = 0; g < groups; g++) {
                int from = g * WIDTH, to = Math.min(n, from + WIDTH);
                FragSeq[] s = new FragSeq[to - from];
                int it = 0;
                boolean ne = false;
                for (int i = from; i < to; i++) { s[i - from] = elems.get(i); it += s[i - from].items(); ne |= !s[i - from].empty(); }
                cur[g] = new Vec(s, 0, it, to - from, ne);
            }
            int level = 0;
            while (cur.length > 1) {
                level++;
                int ng = (cur.length + WIDTH - 1) / WIDTH;
                Vec[] next = new Vec[ng];
                for (int g = 0; g < ng; g++) {
                    int from = g * WIDTH, to = Math.min(cur.length, from + WIDTH);
                    FragSeq[] s = new FragSeq[to - from];
                    int it = 0, ln = 0;
                    boolean ne = false;
                    for (int i = from; i < to; i++) { s[i - from] = cur[i]; it += cur[i].items; ln += cur[i].len; ne |= cur[i].nonEmpty; }
                    next[g] = new Vec(s, level, it, ln, ne);
                }
                cur = next;
            }
            return cur[0];
        }

        int len() { return len; }

        FragSeq get(int i) {
            Vec v = this;
            while (v.level > 0) {
                v = (Vec) v.slots[(i >> (BITS * v.level)) & MASK];
            }
            return v.slots[i & MASK];
        }

        /** 位置 {@code i} の要素を {@code e} に替えた新しい並び。 */
        Vec set(int i, FragSeq e) {
            if (i < 0 || i >= len) throw new IndexOutOfBoundsException(i);
            FragSeq[] s = slots.clone();
            int newItems;
            if (level == 0) {
                FragSeq old = s[i & MASK];
                s[i & MASK] = e;
                newItems = items - old.items() + e.items();
            } else {
                int idx = (i >> (BITS * level)) & MASK;
                Vec old = (Vec) s[idx];
                Vec nw = old.set(i & ((1 << (BITS * level)) - 1), e);
                s[idx] = nw;
                newItems = items - old.items + nw.items;
            }
            boolean ne = false;
            for (FragSeq x : s) if (!x.empty()) { ne = true; break; }
            return new Vec(s, level, newItems, len, ne);
        }

        @Override int items() { return items; }
        @Override boolean empty() { return !nonEmpty; }
        @Override int copyTo(DisplayItem[] dst, int off, OwnerSink sink) {
            if (!nonEmpty) return off;
            for (FragSeq s : slots) {
                if (!s.empty()) off = s.copyTo(dst, off, sink);
            }
            return off;
        }
    }
}
