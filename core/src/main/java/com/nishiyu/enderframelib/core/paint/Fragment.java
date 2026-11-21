package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 断片（C1。設計: {@code core/DESIGN_FRAGMENT_TREE.md} §3。現段階 F1 は影モードで、{@link FragmentVerifier} だけが使う）。{@code PaintOrder.classifyPlain} の分類を、
 * 「箱が親へ渡す層別の列（{@link #out}）」という形で写したもの。
 *
 * <ul>
 *   <li>ブロック・インラインの箱は自分の項目を親の層（blocks / inlines）へ足し、子の寄与をそのまま通す。</li>
 *   <li>float・不可分なインライン・z-index が auto の位置指定の箱は「疑似コンテキスト」: 自分の中身（自分・ブロック・float・
 *       インライン）を 1 つにまとめて親の該当の層へ足す。中の位置指定・スタッキングコンテキスト（sc の層）は通り抜ける。</li>
 *   <li>スタッキングコンテキストの箱は、中身を z で整列して 1 つにまとめ、親の sc の層（負・0・正）へ足す。</li>
 * </ul>
 *
 * 箱の木と同じ形の不変の木で、各層は子の寄与を子の順に並べた永続ベクタ（{@link FragSeq.Vec}）。1 つの子が変わると、道の上の節で
 * 該当の要素だけを差し替える。レイヤー（フィルタ・ブレンド・クリップ）・マスク・Top Layer・スクロールバー・仮想化した並びは対象外。
 */
final class Fragment {
    static final DisplayItem[] NONE = new DisplayItem[0];

    enum Kind { ROOT, BLOCK, INLINE, FLOAT, ATOMIC, POSITIONED, STACKING }

    static final int BLOCKS = 0, FLOATS = 1, INLINES = 2, NEG = 3, POS0 = 4, POSZ = 5, AFTER = 6, HOIST = 7, LAYERS = 8;

    final LayoutBox box;
    final DisplayItem[] own;
    /** 自分のスクロールバーの項目。 */
    final DisplayItem[] ownAfter;
    /** 描いた箱か（項目が 0 件でも、旧経路の {@code executeTasks} が持ち主として範囲を記録する箱）。影モードでは使わない（false）。 */
    final boolean drawn;
    final Kind kind;
    final int z;
    /** レイヤーに描く箱（スタッキングコンテキストのみ）の仕様。null なら錨の書き換えをしない（影モードは出力にすでに入っている）。 */
    final LayerSpec layer;
    final FragSeq.Anchor anchor;
    final Fragment[] kids;
    /** 子の寄与を子の順に並べた層（{@link FragSeq.Vec}、全部空なら {@link FragSeq#EMPTY}）。 */
    final FragSeq[] agg;
    /** 親へ渡す層。 */
    final FragSeq[] out = new FragSeq[LAYERS];
    /** この箱が 1 つにまとめた出力（ROOT・STACKING・疑似コンテキストだけ。それ以外は null）。 */
    final FragSeq emitted;

    Fragment(LayoutBox box, DisplayItem[] own, DisplayItem[] ownAfter, Kind kind, int z, Fragment[] kids, FragSeq[] agg) {
        this(box, own, ownAfter, false, kind, z, null, null, kids, agg);
    }

    Fragment(LayoutBox box, DisplayItem[] own, DisplayItem[] ownAfter, boolean drawn, Kind kind, int z, LayerSpec layer,
             FragSeq.Anchor anchor, Fragment[] kids, FragSeq[] agg) {
        this(box, own, ownAfter, drawn, kind, z, layer, anchor, (Extras) null, kids, agg);
    }

    /**
     * スタッキングコンテキストの付属の出力。{@code layerMask}: マスクのあるレイヤーの、マスク用の項目（箱の持ち物でなく、層の中身のあとに足される）。
     * {@code hoist}: Top Layer の箱（祖先のスタッキングコンテキストを越えて文書の根の最前面へ出る）。{@code backdrop}: その {@code ::backdrop} の項目
     * （箱の直前に、箱の持ち物として出る）。
     */
    record Extras(DisplayItem[] layerMask, boolean hoist, DisplayItem[] backdrop) {}

    Fragment(LayoutBox box, DisplayItem[] own, DisplayItem[] ownAfter, boolean drawn, Kind kind, int z, LayerSpec layer,
             FragSeq.Anchor anchor, Extras extras, Fragment[] kids, FragSeq[] agg) {
        DisplayItem[] layerMask = extras == null ? null : extras.layerMask();
        this.box = box;
        this.own = own;
        this.ownAfter = ownAfter;
        this.drawn = drawn;
        this.kind = kind;
        this.z = z;
        this.layer = layer;
        this.anchor = anchor;
        this.kids = kids;
        this.agg = agg;
        FragSeq ownSeq = drawn ? new FragSeq.Owned(box, own) : own.length == 0 ? FragSeq.EMPTY : new FragSeq.Leaf(own);
        FragSeq afterSeq = cat(agg[AFTER], ownAfter.length == 0 ? FragSeq.EMPTY : new FragSeq.Leaf(ownAfter)); // 子のあとに自分のスクロールバー
        java.util.Arrays.fill(out, FragSeq.EMPTY);
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
                out[POS0] = cat(em, agg[POS0]); // 自分が先、中の位置指定は木の順で後ろ
            }
            case STACKING -> {
                em = stackingEmit(ownSeq, afterSeq);
                if (layer != null) {
                    LayerSpec spec = layer;
                    if (layer.wantsMask() && em.items() > 0) { // マスクは、箱の中身を出し終えたあとに足す（描画側は maskFrom 以降をマスク用の描画先へ描く）
                        boolean has = layerMask != null && layerMask.length > 0;
                        spec = layer.withMaskFrom(has ? em.items() : -1);
                        if (has) em = cat(em, new FragSeq.Leaf(layerMask));
                    }
                    em = new FragSeq.Layered(spec, em, anchor);
                }
                passHoist();
                if (extras != null && extras.hoist()) { // Top Layer: backdrop（箱の直前）→箱の順で、根へ持ち上げる。中の Top Layer の箱はそのあと
                    FragSeq bd = extras.backdrop() == null || extras.backdrop().length == 0 ? FragSeq.EMPTY : new FragSeq.Owned(box, extras.backdrop());
                    out[HOIST] = cat(cat(bd.empty() ? bd : new FragSeq.ZSeq(z, bd), new FragSeq.ZSeq(z, em)), agg[HOIST]);
                } else if (z < 0) out[NEG] = new FragSeq.ZSeq(z, em);
                else if (z > 0) out[POSZ] = new FragSeq.ZSeq(z, em);
                else out[POS0] = em;
            }
        }
        this.emitted = em;
    }

    private void passThrough() {
        out[FLOATS] = agg[FLOATS];
        out[INLINES] = agg[INLINES];
        out[BLOCKS] = agg[BLOCKS];
        passSc();
    }

    /** 子孫の Top Layer の箱は、この箱を越えて根へ通り抜ける。 */
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

    /** {@code PaintOrder.emit} の非スタッキング（自分・ブロック・float・インライン）。 */
    private FragSeq plainEmit(FragSeq ownSeq, FragSeq afterSeq) {
        return cat(ownSeq, agg[BLOCKS], agg[FLOATS], agg[INLINES], afterSeq);
    }

    /** {@code PaintOrder.emit} のスタッキング（自分・負の z・ブロック・float・インライン・位置指定・正の z）。 */
    private FragSeq stackingEmit(FragSeq ownSeq, FragSeq afterSeq) {
        // 文書の根は、Top Layer の箱（持ち上げられてきたもの）を最前面の正の z として並べる（z は最大なので、通常の正の z のあと）
        FragSeq positive = kind == Kind.ROOT ? cat(agg[POSZ], agg[HOIST]) : agg[POSZ];
        return cat(ownSeq, sortedZ(agg[NEG]), agg[BLOCKS], agg[FLOATS], agg[INLINES], agg[POS0], sortedZ(positive), afterSeq);
    }

    /** z の昇順（同じ値は木の順）に並べた 1 本の列。 */
    static FragSeq sortedZ(FragSeq layer) {
        if (layer.empty()) return FragSeq.EMPTY;
        List<FragSeq.ZSeq> es = new ArrayList<>();
        collectZ(layer, es);
        es.sort((x, y) -> Integer.compare(x.z, y.z)); // 安定
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

    // ------------------------------------------------------------ 全面の組み立て（記憶が空の増分）

    /** 子を持たない箱の寄与（全層が空。読むだけで書き換えない）。 */
    private static final FragSeq[] NO_KIDS = emptyLayers();

    private static FragSeq[] emptyLayers() {
        FragSeq[] a = new FragSeq[LAYERS];
        java.util.Arrays.fill(a, FragSeq.EMPTY);
        return a;
    }

    /**
     * 子の断片が前回の {@code prev} と同じ実体の位置が大半なら、層の列は前回のものから、変わった子だけを差し替える（永続ベクタの
     * {@code set}）。変わった子が多い・子が少ないときは全面の {@link #aggregate} と同じ。結果は常に同じ（組み立ての段の最適化）。
     */
    static FragSeq[] aggregateFrom(Fragment prev, Fragment[] kids) {
        if (prev == null || prev.kids.length != kids.length || kids.length < 64) return aggregate(kids);
        int diff = 0;
        for (int i = 0; i < kids.length; i++) if (prev.kids[i] != kids[i] && ++diff > 8) return aggregate(kids);
        if (diff == 0) return prev.agg;
        FragSeq[] ag = prev.agg.clone();
        for (int i = 0; i < kids.length; i++) {
            if (prev.kids[i] == kids[i]) continue;
            for (int l = 0; l < LAYERS; l++) {
                FragSeq now = kids[i].out[l], was = prev.kids[i].out[l];
                if (now == was) continue;
                ag[l] = ag[l] instanceof FragSeq.Vec v ? v.set(i, now) : layerOf(kids, l);
            }
        }
        return ag;
    }

    static FragSeq[] aggregate(Fragment[] kids) {
        if (kids.length == 0) return NO_KIDS;
        FragSeq[] agg = new FragSeq[LAYERS];
        for (int l = 0; l < LAYERS; l++) agg[l] = layerOf(kids, l);
        return agg;
    }

    /** 層 {@code l} の、子の寄与の並び。全部空なら {@link FragSeq#EMPTY}。 */
    private static FragSeq layerOf(Fragment[] kids, int l) {
        boolean any = false;
        for (Fragment k : kids) {
            if (!k.out[l].empty()) { any = true; break; }
        }
        if (!any) return FragSeq.EMPTY; // 寄与する子が無い層は、リストを作らない
        List<FragSeq> xs = new ArrayList<>(kids.length);
        for (Fragment k : kids) xs.add(k.out[l]);
        return FragSeq.Vec.of(xs);
    }

    /** 付録 E の分類（{@code createsStackingContext} → 位置指定 → float → 不可分なインライン → ブロック/インライン の順）。 */
    static Kind kindOf(LayoutBox b, PaintOrder order) {
        if (order.createsStackingContext(b)) return Kind.STACKING;
        if (PaintOrder.isPositioned(b)) return Kind.POSITIONED;
        if (BlockLayoutEngine.isFloating(b)) return Kind.FLOAT;
        if (PaintSupport.isAtomicInline(b)) return Kind.ATOMIC;
        return b.display().isInlineLevel() ? Kind.INLINE : Kind.BLOCK;
    }

    // ------------------------------------------------------------ 読み出し

    /** 根の出力の項目数。 */
    int size() { return emitted.items(); }

    /** 平らな配列へ（消費者へ渡す {@code FragmentList} の中身）。 */
    DisplayItem[] flatten() {
        DisplayItem[] out = new DisplayItem[size()];
        flattenInto(out);
        return out;
    }

    int flattenInto(DisplayItem[] out) {
        return emitted.copyTo(out, 0);
    }
}
