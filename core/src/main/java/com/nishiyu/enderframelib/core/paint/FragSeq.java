package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import java.util.Arrays;
import java.util.List;

/**
 * 不変の項目の並び（C1・C1p）。設計: {@code core/DESIGN_FRAGMENT_TREE.md} §3.1・§0.5 M7。
 * 葉（項目の配列）・所有者つきの葉・2 つの連結・z 付きの寄与・レイヤーの錨の包み・32 分岐の永続 B 木ベクタ（{@link Vec}）。
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
            if (a.length > 0) System.arraycopy(a, 0, dst, off, a.length);
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
     * 出力の項目数（span）つきの項目へ作り直す（項目が 0 件なら何もしない）。部分木が記憶から再利用されたときは同じ錨項目を使い回す。
     */
    static final class Layered extends FragSeq {
        final LayerSpec spec;
        final FragSeq inner;
        final Anchor anchor;
        private DisplayItem cachedSrc;
        private int cachedSpan;
        private DisplayItem cachedWrapped;

        Layered(LayerSpec spec, FragSeq inner, Anchor anchor) { this.spec = spec; this.inner = inner; this.anchor = anchor; }
        @Override int items() { return inner.items(); }
        @Override boolean empty() { return inner.empty(); }
        @Override int copyTo(DisplayItem[] dst, int off, OwnerSink sink) {
            int end = inner.copyTo(dst, off, sink);
            if (end > off) {
                DisplayItem first = dst[off];
                int span = end - off;
                if (first != cachedSrc || span != cachedSpan || cachedWrapped == null) {
                    cachedSrc = first;
                    cachedSpan = span;
                    cachedWrapped = anchor.wrap(first, spec.withSpan(span));
                }
                dst[off] = cachedWrapped;
            }
            return end;
        }
    }

    /**
     * 要素（{@link FragSeq}）の永続 B 木ベクタ。最大 32 分岐（分割 16・結合 16）。
     * {@link #set}・{@link #insert}・{@link #remove} は根から該当の節までの O(log32 N) 個だけを複製する。
     */
    static final class Vec extends FragSeq {
        static final int BITS = 5, WIDTH = 1 << BITS, HALF = WIDTH >> 1, MERGE_MAX = HALF;
        static final Vec EMPTY_VEC = new Vec(new FragSeq[0], 0, 0, 0, false);

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

        private static Vec leafOf(FragSeq[] slots) {
            int it = 0;
            boolean ne = false;
            for (FragSeq s : slots) { it += s.items(); ne |= !s.empty(); }
            return new Vec(slots, 0, it, slots.length, ne);
        }

        private static Vec nodeOf(FragSeq[] slots, int level) {
            int it = 0, ln = 0;
            boolean ne = false;
            for (FragSeq f : slots) {
                Vec c = (Vec) f;
                it += c.items;
                ln += c.len;
                ne |= c.nonEmpty;
            }
            return new Vec(slots, level, it, ln, ne);
        }

        private static boolean hasNonEmpty(FragSeq[] s) {
            for (FragSeq x : s) if (!x.empty()) return true;
            return false;
        }

        static Vec of(List<? extends FragSeq> elems) {
            int n = elems.size();
            if (n == 0) return EMPTY_VEC;
            if (n <= WIDTH) {
                FragSeq[] s = new FragSeq[n];
                int it = 0;
                boolean ne = false;
                for (int i = 0; i < n; i++) { s[i] = elems.get(i); it += s[i].items(); ne |= !s[i].empty(); }
                return new Vec(s, 0, it, n, ne);
            }
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
            return wrapLevels(cur);
        }

        /** 長さ {@code n} の、全要素が {@link FragSeq#EMPTY} の B 木ベクタを返す（空は同じ葉を共有する）。 */
        static Vec emptyOfLen(int n) {
            if (n <= 0) return EMPTY_VEC;
            int groups = (n + WIDTH - 1) / WIDTH;
            FragSeq[] full = new FragSeq[Math.min(n, WIDTH)];
            Arrays.fill(full, FragSeq.EMPTY);
            Vec fullLeaf = new Vec(full, 0, 0, full.length, false);
            if (groups == 1) return fullLeaf;
            Vec[] cur = new Vec[groups];
            for (int g = 0; g < groups; g++) {
                int len = Math.min(WIDTH, n - g * WIDTH);
                if (len == WIDTH) cur[g] = fullLeaf;
                else {
                    FragSeq[] tail = new FragSeq[len];
                    Arrays.fill(tail, FragSeq.EMPTY);
                    cur[g] = new Vec(tail, 0, 0, len, false);
                }
            }
            return wrapLevels(cur);
        }

        static Vec ofLayer(Fragment[] kids, int n, int layer) {
            if (n == 0) return EMPTY_VEC;
            if (n <= WIDTH) {
                FragSeq[] s = new FragSeq[n];
                int it = 0;
                boolean ne = false;
                for (int i = 0; i < n; i++) { s[i] = kids[i].out[layer]; it += s[i].items(); ne |= !s[i].empty(); }
                return new Vec(s, 0, it, n, ne);
            }
            int groups = (n + WIDTH - 1) / WIDTH;
            Vec[] cur = new Vec[groups];
            for (int g = 0; g < groups; g++) {
                int from = g * WIDTH, to = Math.min(n, from + WIDTH);
                FragSeq[] s = new FragSeq[to - from];
                int it = 0;
                boolean ne = false;
                for (int i = from; i < to; i++) { s[i - from] = kids[i].out[layer]; it += s[i - from].items(); ne |= !s[i - from].empty(); }
                cur[g] = new Vec(s, 0, it, to - from, ne);
            }
            return wrapLevels(cur);
        }

        static Vec ofLayer(Fragment[][] chunks, int n, int layer) {
            if (n == 0) return EMPTY_VEC;
            int groups = chunks.length;
            if (groups == 1) {
                Fragment[] ch = chunks[0];
                FragSeq[] s = new FragSeq[ch.length];
                int it = 0;
                boolean ne = false;
                for (int i = 0; i < ch.length; i++) { s[i] = ch[i].out[layer]; it += s[i].items(); ne |= !s[i].empty(); }
                return new Vec(s, 0, it, ch.length, ne);
            }
            Vec[] cur = new Vec[groups];
            for (int g = 0; g < groups; g++) {
                Fragment[] ch = chunks[g];
                FragSeq[] s = new FragSeq[ch.length];
                int it = 0;
                boolean ne = false;
                for (int i = 0; i < ch.length; i++) { s[i] = ch[i].out[layer]; it += s[i].items(); ne |= !s[i].empty(); }
                cur[g] = new Vec(s, 0, it, ch.length, ne);
            }
            return wrapLevels(cur);
        }

        private static Vec wrapLevels(Vec[] cur) {
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
            if (i < 0 || i >= len) throw new IndexOutOfBoundsException(i);
            Vec v = this;
            while (v.level > 0) {
                for (int k = 0; ; k++) {
                    Vec c = (Vec) v.slots[k];
                    if (i < c.len) { v = c; break; }
                    i -= c.len;
                }
            }
            return v.slots[i];
        }

        /** 位置 {@code i} の要素を {@code e} に替えた新しい並び。 */
        Vec set(int i, FragSeq e) {
            if (i < 0 || i >= len) throw new IndexOutOfBoundsException(i);
            FragSeq[] s = slots.clone();
            int newItems;
            if (level == 0) {
                FragSeq old = s[i];
                s[i] = e;
                newItems = items - old.items() + e.items();
            } else {
                int k = 0, rem = i;
                for (;;) {
                    Vec c = (Vec) s[k];
                    if (rem < c.len) break;
                    rem -= c.len;
                    k++;
                }
                Vec old = (Vec) s[k];
                Vec nw = old.set(rem, e);
                s[k] = nw;
                newItems = items - old.items + nw.items;
            }
            boolean ne = !e.empty() || hasNonEmpty(s);
            return new Vec(s, level, newItems, len, ne);
        }

        /** 位置 {@code i}（{@code 0..len}）へ {@code e} を差し込んだ新しい並び。 */
        Vec insert(int i, FragSeq e) {
            if (i < 0 || i > len) throw new IndexOutOfBoundsException(i);
            if (len == 0) return new Vec(new FragSeq[]{e}, 0, e.items(), 1, !e.empty());
            Vec[] res = insertRec(i, e);
            return res.length == 1 ? res[0] : nodeOf(res, level + 1);
        }

        private Vec[] insertRec(int i, FragSeq e) {
            if (level == 0) {
                FragSeq[] next = new FragSeq[slots.length + 1];
                System.arraycopy(slots, 0, next, 0, i);
                next[i] = e;
                System.arraycopy(slots, i, next, i + 1, slots.length - i);
                if (next.length <= WIDTH) return new Vec[]{leafOf(next)};
                return new Vec[]{leafOf(Arrays.copyOfRange(next, 0, HALF)), leafOf(Arrays.copyOfRange(next, HALF, next.length))};
            }
            int k = 0, rem = i, last = slots.length - 1;
            while (k < last) {
                Vec c = (Vec) slots[k];
                if (rem < c.len) break;
                rem -= c.len;
                k++;
            }
            Vec[] res = ((Vec) slots[k]).insertRec(rem, e);
            if (res.length == 1) {
                FragSeq[] ns = slots.clone();
                ns[k] = res[0];
                return new Vec[]{new Vec(ns, level, items + e.items(), len + 1, nonEmpty || !e.empty())};
            }
            FragSeq[] next = new FragSeq[slots.length + 1];
            System.arraycopy(slots, 0, next, 0, k);
            next[k] = res[0];
            next[k + 1] = res[1];
            System.arraycopy(slots, k + 1, next, k + 2, slots.length - k - 1);
            if (next.length <= WIDTH) return new Vec[]{nodeOf(next, level)};
            return new Vec[]{nodeOf(Arrays.copyOfRange(next, 0, HALF), level), nodeOf(Arrays.copyOfRange(next, HALF, next.length), level)};
        }

        /** 位置 {@code i}（{@code 0..len-1}）の要素を除いた新しい並び。 */
        Vec remove(int i) {
            if (i < 0 || i >= len) throw new IndexOutOfBoundsException(i);
            if (len == 1) return EMPTY_VEC;
            Vec res = removeRec(i);
            if (res == null) return EMPTY_VEC;
            while (res.level > 0 && res.slots.length == 1) res = (Vec) res.slots[0];
            return res;
        }

        private Vec removeRec(int i) {
            if (level == 0) {
                if (slots.length == 1) return null;
                FragSeq[] next = new FragSeq[slots.length - 1];
                System.arraycopy(slots, 0, next, 0, i);
                System.arraycopy(slots, i + 1, next, i, slots.length - i - 1);
                return leafOf(next);
            }
            int k = 0, rem = i;
            for (;;) {
                Vec c = (Vec) slots[k];
                if (rem < c.len) break;
                rem -= c.len;
                k++;
            }
            Vec oldChild = (Vec) slots[k];
            Vec newChild = oldChild.removeRec(rem);
            if (newChild == null) {
                if (slots.length == 1) return null;
                FragSeq[] next = new FragSeq[slots.length - 1];
                System.arraycopy(slots, 0, next, 0, k);
                System.arraycopy(slots, k + 1, next, k, slots.length - k - 1);
                return new Vec(next, level, items - oldChild.items, len - oldChild.len, hasNonEmpty(next));
            }
            int newItems = items - oldChild.items + newChild.items;
            if (newChild.slots.length <= MERGE_MAX) {
                if (k + 1 < slots.length && newChild.slots.length + ((Vec) slots[k + 1]).slots.length <= MERGE_MAX) {
                    Vec right = (Vec) slots[k + 1];
                    Vec merged = merge(newChild, right);
                    FragSeq[] next = new FragSeq[slots.length - 1];
                    System.arraycopy(slots, 0, next, 0, k);
                    next[k] = merged;
                    System.arraycopy(slots, k + 2, next, k + 1, slots.length - k - 2);
                    return new Vec(next, level, newItems, len - 1, merged.nonEmpty || hasNonEmpty(next));
                }
                if (k > 0 && ((Vec) slots[k - 1]).slots.length + newChild.slots.length <= MERGE_MAX) {
                    Vec left = (Vec) slots[k - 1];
                    Vec merged = merge(left, newChild);
                    FragSeq[] next = new FragSeq[slots.length - 1];
                    System.arraycopy(slots, 0, next, 0, k - 1);
                    next[k - 1] = merged;
                    System.arraycopy(slots, k + 1, next, k, slots.length - k - 1);
                    return new Vec(next, level, newItems, len - 1, merged.nonEmpty || hasNonEmpty(next));
                }
            }
            FragSeq[] next = slots.clone();
            next[k] = newChild;
            return new Vec(next, level, newItems, len - 1, newChild.nonEmpty || hasNonEmpty(next));
        }

        private static Vec merge(Vec a, Vec b) {
            FragSeq[] s = new FragSeq[a.slots.length + b.slots.length];
            System.arraycopy(a.slots, 0, s, 0, a.slots.length);
            System.arraycopy(b.slots, 0, s, a.slots.length, b.slots.length);
            return new Vec(s, a.level, a.items + b.items, a.len + b.len, a.nonEmpty || b.nonEmpty);
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
