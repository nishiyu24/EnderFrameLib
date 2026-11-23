package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 断片（C1・C1p。設計: {@code core/DESIGN_INCREMENTAL_KERNEL.md} 付録 A.2）。{@code PaintOrder.classifyPlain} の分類を、
 * 「箱が親へ渡す層別の列（{@link #out}）」という形で写したもの。
 *
 * <ul>
 *   <li>ブロック・インラインの箱は自分の項目を親の層（blocks / inlines）へ足し、子の寄与をそのまま通す。</li>
 *   <li>float・不可分なインライン・z-index が auto の位置指定の箱は「疑似コンテキスト」: 自分の中身（自分・ブロック・float・
 *       インライン）を 1 つにまとめて親の該当の層へ足す。中の位置指定・スタッキングコンテキスト（sc の層）は通り抜ける。</li>
 *   <li>スタッキングコンテキストの箱は、中身を z で整列して 1 つにまとめ、親の sc の層（負・0・正）へ足す。</li>
 * </ul>
 *
 * <p>C1p: 子が 64 個以上の断片は 32 個単位のチャンク配列 {@link #bigKids} と永続 B 木ベクタ {@link FragSeq.Vec} で保持し、
 * 少数の子・挿入・削除で配列全体と全層を作り直さない。</p>
 */
final class Fragment {
    static final DisplayItem[] NONE = new DisplayItem[0];
    static final int CHUNK_SHIFT = 5, CHUNK_SIZE = 1 << CHUNK_SHIFT, CHUNK_MASK = CHUNK_SIZE - 1;
    static final Fragment[] NO_KIDS_ARRAY = new Fragment[0];

    enum Kind { ROOT, BLOCK, INLINE, FLOAT, ATOMIC, POSITIONED, STACKING }

    static final int BLOCKS = 0, FLOATS = 1, INLINES = 2, NEG = 3, POS0 = 4, POSZ = 5, AFTER = 6, HOIST = 7, LAYERS = 8;

    /** 子を持たない箱の寄与（全層が空。読むだけで書き換えない）。 */
    static final FragSeq[] NO_KIDS = emptyLayers();
    /** 項目も子も持たない空の断片（画面外の省略などで共有する）。 */
    static final Fragment EMPTY = new Fragment();

    final DisplayItem[] own;
    /** 自分のスクロールバーの項目。 */
    final DisplayItem[] ownAfter;
    /** 描いた箱か（項目が 0 件でも、持ち主として範囲を記録する箱）。 */
    final boolean drawn;
    final Kind kind;
    final int z;
    /** レイヤーに描く箱（スタッキングコンテキストのみ）の仕様。null なら錨の書き換えをしない。 */
    final LayerSpec layer;
    final FragSeq.Anchor anchor;
    final int kidCount;
    /** 子が 64 未満のときの子の断片（64 以上のときは null）。 */
    final Fragment[] smallKids;
    /** 子が 64 以上のときの 32 個単位チャンク（64 未満のときは null）。 */
    final Fragment[][] bigKids;
    /** 子の寄与を子の順に並べた層（{@link FragSeq.Vec} 等、全部空なら {@link FragSeq#EMPTY}）。 */
    final FragSeq[] agg;
    /** 親へ渡す層。 */
    final FragSeq[] out;
    /** この箱が 1 つにまとめた出力（ROOT・STACKING・疑似コンテキストだけ。EMPTY 以外は BLOCK/INLINE で null）。 */
    final FragSeq emitted;

    private Fragment() {
        this.own = NONE;
        this.ownAfter = NONE;
        this.drawn = false;
        this.kind = Kind.BLOCK;
        this.z = 0;
        this.layer = null;
        this.anchor = null;
        this.kidCount = 0;
        this.smallKids = NO_KIDS_ARRAY;
        this.bigKids = null;
        this.agg = NO_KIDS;
        this.out = NO_KIDS;
        this.emitted = FragSeq.EMPTY;
    }

    Fragment(LayoutBox box, DisplayItem[] own, DisplayItem[] ownAfter, Kind kind, int z, Fragment[] kids, FragSeq[] agg) {
        this(box, own, ownAfter, false, kind, z, null, null, null, kids.length, kids, null, agg);
    }

    Fragment(LayoutBox box, DisplayItem[] own, DisplayItem[] ownAfter, boolean drawn, Kind kind, int z, LayerSpec layer,
             FragSeq.Anchor anchor, Fragment[] kids, FragSeq[] agg) {
        this(box, own, ownAfter, drawn, kind, z, layer, anchor, null, kids.length, kids, null, agg);
    }

    /**
     * スタッキングコンテキストの付属の出力。{@code layerMask}: マスクのあるレイヤーの、マスク用の項目（箱の持ち物でなく、層の中身のあとに足される）。
     * {@code hoist}: Top Layer の箱（祖先のスタッキングコンテキストを越えて文書の根の最前面へ出る）。{@code backdrop}: その {@code ::backdrop} の項目
     * （箱の直前に、箱の持ち物として出る）。
     */
    record Extras(DisplayItem[] layerMask, boolean hoist, DisplayItem[] backdrop) {}

    Fragment(LayoutBox box, DisplayItem[] own, DisplayItem[] ownAfter, boolean drawn, Kind kind, int z, LayerSpec layer,
             FragSeq.Anchor anchor, Extras extras, Fragment[] kids, FragSeq[] agg) {
        this(box, own, ownAfter, drawn, kind, z, layer, anchor, extras, kids.length, kids, null, agg);
    }

    Fragment(LayoutBox box, DisplayItem[] own, DisplayItem[] ownAfter, boolean drawn, Kind kind, int z, LayerSpec layer,
             FragSeq.Anchor anchor, Extras extras, int kidCount, Fragment[] smallKids, Fragment[][] bigKids, FragSeq[] agg) {
        DisplayItem[] layerMask = extras == null ? null : extras.layerMask();
        this.own = own;
        this.ownAfter = ownAfter;
        this.drawn = drawn;
        this.kind = kind;
        this.z = z;
        this.layer = layer;
        this.anchor = anchor;
        this.kidCount = kidCount;
        this.smallKids = smallKids;
        this.bigKids = bigKids;
        this.agg = agg;
        this.out = new FragSeq[LAYERS];
        FragSeq ownSeq = drawn ? new FragSeq.Owned(box, own) : own.length == 0 ? FragSeq.EMPTY : new FragSeq.Leaf(own);
        FragSeq afterSeq = cat(agg[AFTER], ownAfter.length == 0 ? FragSeq.EMPTY : new FragSeq.Leaf(ownAfter));
        Arrays.fill(out, FragSeq.EMPTY);
        FragSeq em = null;
        switch (kind) {
            case ROOT -> em = stackingEmit(ownSeq, afterSeq);
            case BLOCK -> {
                passThrough();
                out[BLOCKS] = cat(ownSeq, agg[BLOCKS]);
                out[AFTER] = afterSeq;
            }
            case INLINE -> {
                passThrough();
                out[INLINES] = cat(ownSeq, agg[INLINES]);
                out[AFTER] = afterSeq;
            }
            case FLOAT -> {
                em = plainEmit(ownSeq, afterSeq);
                passSc();
                out[FLOATS] = em;
            }
            case ATOMIC -> {
                em = plainEmit(ownSeq, afterSeq);
                passSc();
                out[INLINES] = em;
            }
            case POSITIONED -> {
                em = plainEmit(ownSeq, afterSeq);
                passSc();
                out[POS0] = cat(em, agg[POS0]);
            }
            case STACKING -> {
                em = stackingEmit(ownSeq, afterSeq);
                if (layer != null) {
                    LayerSpec spec = layer;
                    if (layer.wantsMask() && em.items() > 0) {
                        boolean has = layerMask != null && layerMask.length > 0;
                        spec = layer.withMaskFrom(has ? em.items() : -1);
                        if (has) em = cat(em, new FragSeq.Leaf(layerMask));
                    }
                    em = new FragSeq.Layered(spec, em, anchor);
                }
                passHoist();
                if (extras != null && extras.hoist()) {
                    FragSeq bd = extras.backdrop() == null || extras.backdrop().length == 0 ? FragSeq.EMPTY : new FragSeq.Owned(box, extras.backdrop());
                    out[HOIST] = cat(cat(bd.empty() ? bd : new FragSeq.ZSeq(z, bd), new FragSeq.ZSeq(z, em)), agg[HOIST]);
                } else if (z < 0) out[NEG] = new FragSeq.ZSeq(z, em);
                else if (z > 0) out[POSZ] = new FragSeq.ZSeq(z, em);
                else out[POS0] = em;
            }
        }
        this.emitted = em;
    }

    Fragment kidAt(int i) {
        return smallKids != null ? smallKids[i] : bigKids[i >>> CHUNK_SHIFT][i & CHUNK_MASK];
    }

    private void passThrough() {
        out[FLOATS] = agg[FLOATS];
        out[INLINES] = agg[INLINES];
        out[BLOCKS] = agg[BLOCKS];
        passSc();
    }

    private void passHoist() {
        out[HOIST] = agg[HOIST];
    }

    private void passSc() {
        out[NEG] = agg[NEG];
        out[POS0] = agg[POS0];
        out[POSZ] = agg[POSZ];
        out[HOIST] = agg[HOIST];
    }

    private static FragSeq cat(FragSeq a, FragSeq b) {
        if (a.empty()) return b;
        if (b.empty()) return a;
        return new FragSeq.Pair(a, b);
    }

    private static FragSeq cat(FragSeq... parts) {
        FragSeq r = FragSeq.EMPTY;
        for (FragSeq p : parts) r = cat(r, p);
        return r;
    }

    private FragSeq plainEmit(FragSeq ownSeq, FragSeq afterSeq) {
        return cat(ownSeq, agg[BLOCKS], agg[FLOATS], agg[INLINES], afterSeq);
    }

    private FragSeq stackingEmit(FragSeq ownSeq, FragSeq afterSeq) {
        FragSeq positive = kind == Kind.ROOT ? cat(agg[POSZ], agg[HOIST]) : agg[POSZ];
        return cat(ownSeq, sortedZ(agg[NEG]), agg[BLOCKS], agg[FLOATS], agg[INLINES], agg[POS0], sortedZ(positive), afterSeq);
    }

    static FragSeq sortedZ(FragSeq layer) {
        if (layer.empty()) return FragSeq.EMPTY;
        List<FragSeq.ZSeq> es = new ArrayList<>();
        collectZ(layer, es);
        es.sort((x, y) -> Integer.compare(x.z, y.z));
        FragSeq r = FragSeq.EMPTY;
        for (FragSeq.ZSeq e : es) r = cat(r, e.inner);
        return r;
    }

    private static void collectZ(FragSeq s, List<FragSeq.ZSeq> out) {
        if (s.empty()) return;
        if (s instanceof FragSeq.ZSeq z) out.add(z);
        else if (s instanceof FragSeq.Pair p) { collectZ(p.first, out); collectZ(p.second, out); }
        else if (s instanceof FragSeq.Vec v) for (FragSeq c : v.slots) collectZ(c, out);
    }

    private static FragSeq[] emptyLayers() {
        FragSeq[] a = new FragSeq[LAYERS];
        Arrays.fill(a, FragSeq.EMPTY);
        return a;
    }

    static boolean sameArray(Fragment[] a, Fragment[] b, int len) {
        for (int i = 0; i < len; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static boolean sameRange(Fragment[] chunk, Fragment[] src, int from, int len) {
        for (int i = 0; i < len; i++) if (chunk[i] != src[from + i]) return false;
        return true;
    }

    static Fragment[][] chunkifyFrom(Fragment prev, Fragment[] src, int n) {
        int groups = (n + CHUNK_MASK) >>> CHUNK_SHIFT;
        Fragment[][] prevChunks = prev != null ? prev.bigKids : null;
        Fragment[][] chunks = new Fragment[groups][];
        boolean allReused = prevChunks != null && prevChunks.length == groups;
        for (int g = 0; g < groups; g++) {
            int from = g << CHUNK_SHIFT;
            int len = Math.min(CHUNK_SIZE, n - from);
            Fragment[] pc = prevChunks != null && g < prevChunks.length ? prevChunks[g] : null;
            if (pc != null && pc.length == len && sameRange(pc, src, from, len)) {
                chunks[g] = pc;
            } else {
                chunks[g] = Arrays.copyOfRange(src, from, from + len);
                allReused = false;
            }
        }
        return allReused ? prevChunks : chunks;
    }

    /** 64 以上で少数のインデックスだけ入れ替わったとき、該当チャンクだけ複製する。 */
    static Fragment[][] replaceChunks(Fragment[][] base, int[] indices, Fragment[] newFrags, int count) {
        if (count == 0) return base;
        Fragment[][] next = null;
        for (int k = 0; k < count; k++) {
            int idx = indices[k];
            int g = idx >>> CHUNK_SHIFT;
            int slot = idx & CHUNK_MASK;
            Fragment[] cur = (next != null ? next : base)[g];
            if (cur[slot] == newFrags[k]) continue;
            if (next == null) next = base.clone();
            if (next[g] == base[g]) next[g] = base[g].clone();
            next[g][slot] = newFrags[k];
        }
        return next != null ? next : base;
    }

    /** 64 以上で指定インデックスの断片だけ入れ替わったときの層更新（{@link FragSeq.Vec#set}）。 */
    static FragSeq[] aggregateReplaced(FragSeq[] prevAgg, int kidCount, int[] indices,
                                       Fragment[] oldFrags, Fragment[] newFrags, int count) {
        if (count == 0) return prevAgg;
        FragSeq[] ag = null;
        for (int k = 0; k < count; k++) {
            int idx = indices[k];
            Fragment of = oldFrags[k], nf = newFrags[k];
            if (of == nf) continue;
            for (int l = 0; l < LAYERS; l++) {
                FragSeq now = nf.out[l], was = of.out[l];
                if (now == was || (now.empty() && was.empty())) continue;
                if (ag == null) ag = prevAgg.clone();
                if (ag[l] instanceof FragSeq.Vec v) {
                    FragSeq.Vec updated = v.set(idx, now);
                    ag[l] = updated.empty() ? FragSeq.EMPTY : updated;
                } else {
                    ag[l] = FragSeq.Vec.emptyOfLen(kidCount).set(idx, now);
                }
            }
        }
        return ag != null ? ag : prevAgg;
    }

    /** 子の前後一致・少数差分・挿入・削除を考慮して層を組み立てる（全件作り直しを極力避ける）。 */
    static FragSeq[] aggregateDiff(Fragment prev, Fragment[] smallKids, Fragment[][] bigKids, int n) {
        if (n == 0) return NO_KIDS;
        if (prev == null || n < 64 || prev.kidCount < 64) {
            return smallKids != null ? aggregateSmall(smallKids, n) : aggregateBig(bigKids, n);
        }
        int pn = prev.kidCount;
        int minN = Math.min(pn, n);
        int pre = 0;
        while (pre < minN && prev.kidAt(pre) == (smallKids != null ? smallKids[pre] : bigKids[pre >>> CHUNK_SHIFT][pre & CHUNK_MASK])) {
            pre++;
        }
        if (pn == n && pre == n) return prev.agg;
        int suf = 0;
        while (suf < minN - pre) {
            Fragment pf = prev.kidAt(pn - 1 - suf);
            Fragment nf = bigKids[(n - 1 - suf) >>> CHUNK_SHIFT][(n - 1 - suf) & CHUNK_MASK];
            if (pf != nf) break;
            suf++;
        }
        if (pn == n) {
            int diffCount = 0;
            int[] diffIdx = new int[16];
            int end = n - suf;
            for (int i = pre; i < end; i++) {
                if (prev.kidAt(i) != bigKids[i >>> CHUNK_SHIFT][i & CHUNK_MASK]) {
                    if (diffCount >= 16) return aggregateBig(bigKids, n);
                    diffIdx[diffCount++] = i;
                }
            }
            Fragment[] olds = new Fragment[diffCount];
            Fragment[] news = new Fragment[diffCount];
            for (int k = 0; k < diffCount; k++) {
                int idx = diffIdx[k];
                olds[k] = prev.kidAt(idx);
                news[k] = bigKids[idx >>> CHUNK_SHIFT][idx & CHUNK_MASK];
            }
            return aggregateReplaced(prev.agg, n, diffIdx, olds, news, diffCount);
        }
        int oldMid = pn - pre - suf, newMid = n - pre - suf;
        if (oldMid <= 16 && newMid <= 16) {
            return patchSplice(prev, prev.agg, bigKids, n, pre, oldMid, newMid);
        }
        return aggregateBig(bigKids, n);
    }

    private static FragSeq[] patchSplice(Fragment prev, FragSeq[] prevAgg, Fragment[][] bigKids, int n, int pre, int oldMid, int newMid) {
        int common = Math.min(oldMid, newMid);
        FragSeq[] ag = null;
        for (int l = 0; l < LAYERS; l++) {
            boolean layerDiff = false;
            for (int k = 0; k < common; k++) {
                FragSeq now = bigKids[(pre + k) >>> CHUNK_SHIFT][(pre + k) & CHUNK_MASK].out[l];
                FragSeq was = prev.kidAt(pre + k).out[l];
                if (now != was && !(now.empty() && was.empty())) { layerDiff = true; break; }
            }
            if (!layerDiff) {
                if (newMid > oldMid) {
                    for (int k = oldMid; k < newMid; k++) {
                        if (!bigKids[(pre + k) >>> CHUNK_SHIFT][(pre + k) & CHUNK_MASK].out[l].empty()) { layerDiff = true; break; }
                    }
                } else {
                    for (int k = newMid; k < oldMid; k++) {
                        if (!prev.kidAt(pre + k).out[l].empty()) { layerDiff = true; break; }
                    }
                }
            }
            if (!layerDiff && !(prevAgg[l] instanceof FragSeq.Vec)) continue;
            if (!layerDiff && oldMid == newMid) continue;
            if (ag == null) ag = prevAgg.clone();
            FragSeq.Vec v = ag[l] instanceof FragSeq.Vec vv ? vv : FragSeq.Vec.emptyOfLen(prev.kidCount);
            for (int k = 0; k < common; k++) {
                FragSeq now = bigKids[(pre + k) >>> CHUNK_SHIFT][(pre + k) & CHUNK_MASK].out[l];
                FragSeq was = prev.kidAt(pre + k).out[l];
                if (now != was && !(now.empty() && was.empty())) v = v.set(pre + k, now);
            }
            if (newMid > oldMid) {
                for (int k = oldMid; k < newMid; k++) {
                    v = v.insert(pre + k, bigKids[(pre + k) >>> CHUNK_SHIFT][(pre + k) & CHUNK_MASK].out[l]);
                }
            } else {
                for (int k = oldMid - 1; k >= newMid; k--) v = v.remove(pre + k);
            }
            ag[l] = v.empty() ? FragSeq.EMPTY : v;
        }
        return ag != null ? ag : prevAgg;
    }

    static FragSeq[] aggregate(Fragment[] kids) {
        return aggregate(kids, kids.length);
    }

    static FragSeq[] aggregate(Fragment[] kids, int n) {
        if (n == 0) return NO_KIDS;
        if (n < 64) return aggregateSmall(kids, n);
        FragSeq[] agg = null;
        for (int l = 0; l < LAYERS; l++) {
            boolean any = false;
            for (int i = 0; i < n; i++) if (!kids[i].out[l].empty()) { any = true; break; }
            if (any) {
                if (agg == null) agg = emptyLayers();
                agg[l] = FragSeq.Vec.ofLayer(kids, n, l);
            }
        }
        return agg != null ? agg : NO_KIDS;
    }

    private static FragSeq[] aggregateSmall(Fragment[] kids, int n) {
        if (n == 0) return NO_KIDS;
        FragSeq[] agg = null;
        for (int l = 0; l < LAYERS; l++) {
            FragSeq seq = layerOf(kids, n, l);
            if (seq != FragSeq.EMPTY) {
                if (agg == null) agg = emptyLayers();
                agg[l] = seq;
            }
        }
        return agg != null ? agg : NO_KIDS;
    }

    private static FragSeq[] aggregateBig(Fragment[][] chunks, int n) {
        if (n == 0) return NO_KIDS;
        FragSeq[] agg = null;
        for (int l = 0; l < LAYERS; l++) {
            boolean any = false;
            for (Fragment[] ch : chunks) {
                for (Fragment f : ch) if (!f.out[l].empty()) { any = true; break; }
                if (any) break;
            }
            if (any) {
                if (agg == null) agg = emptyLayers();
                agg[l] = FragSeq.Vec.ofLayer(chunks, n, l);
            }
        }
        return agg != null ? agg : NO_KIDS;
    }

    private static FragSeq layerOf(Fragment[] kids, int n, int l) {
        FragSeq single = null;
        int count = 0;
        for (int i = 0; i < n; i++) {
            FragSeq s = kids[i].out[l];
            if (!s.empty()) { single = s; count++; }
        }
        if (count == 0) return FragSeq.EMPTY;
        if (n < 64 && count == 1) return single;
        return FragSeq.Vec.ofLayer(kids, n, l);
    }

    static Kind kindOf(LayoutBox b, PaintOrder order) {
        if (order.createsStackingContext(b)) return Kind.STACKING;
        if (PaintOrder.isPositioned(b)) return Kind.POSITIONED;
        if (BlockLayoutEngine.isFloating(b)) return Kind.FLOAT;
        if (PaintSupport.isAtomicInline(b)) return Kind.ATOMIC;
        return b.display().isInlineLevel() ? Kind.INLINE : Kind.BLOCK;
    }

    int size() { return emitted.items(); }

    DisplayItem[] flatten() {
        DisplayItem[] out = new DisplayItem[size()];
        flattenInto(out, 0);
        return out;
    }

    int flattenInto(DisplayItem[] out, int off) {
        return emitted.copyTo(out, off);
    }
}
