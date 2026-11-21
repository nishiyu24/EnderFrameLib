package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 表示リストの組み立て（C1。設計 {@code core/DESIGN_FRAGMENT_TREE.md}）。箱を再帰してその場で箱の項目を描き、種類（{@link Fragment.Kind}）ごとに
 * 層（付録 E）へ組み立てて 1 本の列に展開する。経路はこの 1 本で、増分の更新（パッチ）は持たない。
 *
 * <ul>
 *   <li>箱の項目は {@code painter.draw} で作る（文脈が前回と同じなら、断片の記憶の項目をそのまま使う）。中身の文脈（クリップ・親 id・スクロールの節の登録）は
 *       {@link PaintOrder#enterChildren} が決める。</li>
 *   <li>窓の外・画面外の省略、箱ごとの範囲（{@code dl*}）・描画順の一覧・スクロールバーの箱の記録は、組み立てたあとの展開で書く。</li>
 *   <li>レイヤーの錨（{@link FragSeq.Layered}）・マスクの項目・Top Layer の持ち上げ（{@link Fragment.Extras}）も、断片の組み立ての中で表す。</li>
 * </ul>
 */
final class FragmentPaint {
    /** 箱 1 つの断片の記憶（鍵 = 親・位置・大きさ・クリップ・親 id・動く祖先・親相対の原点・子の並び・スクロール量・部分木の印）。 */
    private static final class Memo {
        Fragment frag;
        LayoutBox box, parent;
        float x, y, w, h;
        ClipRect clip;
        String parentId;
        boolean moving;
        int pox, poy, pass, owners;
        long epoch;
        LayoutBox[] kids;
        /** {@link #kids} と同じ並びの、子の記憶（まだ無い子は null）。親の文脈が同じとき、子の確認がハッシュ引きなしで済む。 */
        Memo[] kidMemos;
        PaintOrder.ScrollContent[] scrolls;
        /** 箱自身の項目（{@link Fragment#own}）を作ったときの、箱の外の入力（属性・中身の大きさ・先頭の断片・描画側の旗）。 */
        int attrVersion;
        float scrollW, scrollH, f0x, f0y;
        boolean canvasSource, interactive, suppressShadow;
        /** 中身を 1 つの節にまとめるスクロール箱だったか。子孫の状態〔固定・粘着・絶対配置〕で決まるので、箱自身の項目の鍵に入れる。 */
        boolean oneNode;
        /** 先頭の断片が ::first-line を受けるときの、持ち主〔祖先〕の算出スタイルと ::first-line のスタイル。子が祖先から読む唯一のスタイル。 */
        Object flStyle, flPseudo;
        Object placeholder;
        /** 箱自身の項目を作るときに登録されたスクロールの節（項目を使い回すとき再登録する）。 */
        PaintOrder.ScrollContent[] ownScrolls;
    }

    private static final LayoutBox[] NO_BOXES = new LayoutBox[0];
    private static final PaintOrder.ScrollContent[] NO_SCROLLS = new PaintOrder.ScrollContent[0];

    /** 診断: 記憶が当たった数・作り直した数（テストと計測用）。 */
    static final java.util.concurrent.atomic.LongAdder MEMO_HITS = new java.util.concurrent.atomic.LongAdder(), OWN_REUSED = new java.util.concurrent.atomic.LongAdder(),
            MEMO_MISSES = new java.util.concurrent.atomic.LongAdder();

    private final java.util.IdentityHashMap<LayoutBox, Memo> memos = new java.util.IdentityHashMap<>();
    private int frameKey, passNo;
    /** 組み立て中の箱の道。絶対配置の箱が、自分より外の包含ブロックのクリップに依る箱を印付ける。 */
    private final ArrayList<LayoutBox> stack = new ArrayList<>();
    private final java.util.Set<LayoutBox> tainted = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    /** 子の断片を組む作業域（再帰の深さごと。前回と同じなら前回の配列を共有し、確保しない）。 */
    private Fragment[][] kidScratch = new Fragment[16][];

    private final DisplayListBuilder b;
    private final PaintOrder order;
    /** 箱 1 つぶんの出力の作業域（箱ごとに空にして使い回す）。 */
    private final PaintSupport.CaptureList scratch = new PaintSupport.CaptureList(64);
    private boolean cull, windowed;
    private int owners;

    FragmentPaint(DisplayListBuilder b, PaintOrder order) {
        this.b = b;
        this.order = order;
    }

    /** {@code root} を断片の木で描き、{@code elements} へ項目を足して箱の記録を書く。返り値は持ち主（描く箱）の数。 */
    /**
     * 経路の観測口（C7 の予測テスト用）。箱ごとに、選ばれた経路を {@link #PATH_HIT}・{@link #PATH_OWN}・{@link #PATH_REBUILD} で受け取る。
     * 描画の開始で {@code (null, -1)}。{@code null}（既定）なら何もしない。
     */
    private java.util.function.ObjIntConsumer<LayoutBox> pathRecorder;
    static final int PATH_HIT = 0, PATH_OWN = 1, PATH_REBUILD = 2;
    /** 直近の {@link #buildFresh} が、箱自身の項目を前回のまま使った（または持たない）か。 */
    private boolean lastOwnKept;

    void setPathRecorder(java.util.function.ObjIntConsumer<LayoutBox> recorder) {
        this.pathRecorder = recorder;
    }

    int paint(LayoutBox root, List<DisplayItem> elements, boolean cull, boolean windowed) {
        if (pathRecorder != null) pathRecorder.accept(null, -1);
        Fragment top = buildTop(root, cull, windowed);
        int base = elements.size();
        DisplayItem[] flat = new DisplayItem[top.size()];
        top.emitted.copyTo(flat, 0, (box, s, e) -> recordOwner(box, base + s, base + e));
        elements.addAll(Arrays.asList(flat));
        return owners;
    }

    /** 箱を描く順（付録 E）に並べた持ち主。表示リストは作らない（箱の項目は描く）。 */
    List<LayoutBox> paintOrder(LayoutBox root) {
        List<LayoutBox> out = new ArrayList<>();
        Fragment top = buildTop(root, false, false);
        top.emitted.copyTo(new DisplayItem[top.size()], 0, (box, s, e) -> out.add(box));
        return out;
    }

    private Fragment buildTop(LayoutBox root, boolean cull, boolean windowed) {
        this.cull = cull;
        this.windowed = windowed;
        this.owners = 0;
        passNo++;
        LayoutBox.noteRendered(b.buildEpoch);
        if (passNo % 64 == 0) memos.values().removeIf(x -> x.pass < passNo - 64); // 長く使われない記憶（消えた箱を含む）を、64 回に 1 度（フレームの頭で 1 回だけ）捨てる
        int key = b.memoFrameKey(cull, windowed);
        if (key != frameKey || key == Integer.MIN_VALUE) memos.clear(); // 枠組みが変わった・窓がずれている描画では記憶を使わない
        frameKey = key;
        stack.clear();
        tainted.clear();
        order.beginPass();
        boolean rootMoving = root.style != null && PaintOrder.factsOf(root.style).hasMovingProps();
        if ("root".equals(root.node.tag)) {
            PaintOrder.ChildContext cc = order.enterChildren(root, null, null, ctxAt(0));
            Fragment[] kids = buildKids(root, cc, rootMoving, null);
            return new Fragment(root, Fragment.NONE, Fragment.NONE, false, Fragment.Kind.ROOT, 0, null, null, kids, Fragment.aggregate(kids));
        }
        // 文書の根でない箱から描く: その箱が最上位（自分の項目 → 層の順）
        Fragment f = buildFresh(root, null, null, rootMoving, null);
        return new Fragment(root, f.own, f.ownAfter, f.drawn, Fragment.Kind.ROOT, 0, null, null, f.kids, f.agg);
    }

    /** マスクの項目（箱の中身のあとに足す）と、Top Layer の持ち上げ・::backdrop。 */
    private Fragment.Extras extras(LayoutBox box, Fragment.Kind kind, LayerSpec layer) {
        DisplayItem[] mask = null, backdrop = null;
        boolean hoist = kind == Fragment.Kind.STACKING && order.isTopLayerBox(box);
        if (layer != null && layer.wantsMask()) {
            scratch.resetForBuild();
            b.drawMask(scratch, box);
            mask = scratch.toArray(new DisplayItem[0]);
        }
        if (hoist && box.backdropStyle != null) {
            scratch.resetForBuild();
            order.drawBackdrop(scratch, box);
            backdrop = scratch.toArray(new DisplayItem[0]);
        }
        return mask == null && !hoist ? null : new Fragment.Extras(mask, hoist, backdrop);
    }

    /** 箱の中身の文脈の作業域（再帰の深さごと。箱の子を組み終えるまでしか使わないので、使い回せる）。 */
    private PaintOrder.ChildContext[] ctxScratch = new PaintOrder.ChildContext[16];

    private PaintOrder.ChildContext ctxAt(int depth) {
        if (depth >= ctxScratch.length) ctxScratch = Arrays.copyOf(ctxScratch, depth * 2);
        PaintOrder.ChildContext c = ctxScratch[depth];
        return c != null ? c : (ctxScratch[depth] = new PaintOrder.ChildContext());
    }

    private Fragment kid(LayoutBox child, PaintOrder.ChildContext cc, boolean moving, Memo hint, boolean parentCtxOk) {
        if (child.isAbsolute()) taintAboveContainingBlock(child);
        return build(child, order.clipForChild(child, cc.clip), cc.parentId, moving, hint, parentCtxOk);
    }

    /**
     * 箱 {@code box} の描画の断片。表示しない箱は null。記憶の鍵が全部同じなら、子へ降りずにそれを返す。文脈（自分の印・位置・クリップ・親 id・
     * 子の並び）は同じで部分木の中だけが変わっていれば、子は前回の記憶から O(1) で確かめ、変わった子だけを組み直す。
     *
     * @param hint        親の記憶が持つ、この箱の記憶（無ければ null）
     * @param parentCtxOk 親の文脈が前回と同じ（このとき子の文脈も同じなので、子はクリップ・位置・部分木の印だけで確かめる）
     */
    private Fragment build(LayoutBox box, ClipRect clip, String parentId, boolean ancestorMoving, Memo hint, boolean parentCtxOk) {
        if (box.display().isNone() || PaintOrder.factsOf(box.style).displayNone()) return null;
        if (ElementTraits.isOption(box.node.tag)) return null;
        boolean moving = ancestorMoving || (box.style != null && PaintOrder.factsOf(box.style).hasMovingProps());
        Memo m = hint != null && hint.box == box ? hint : memos.get(box); // 配列の位置の記憶が、この箱のものでなければ引き直す
        boolean ctxOk = false;
        if (m != null && (parentCtxOk ? sameContextCheap(m, box, clip, parentId, moving) : sameContext(m, box, clip, parentId, moving))) {
            if (box.subtreePaintStamp() <= m.epoch) {
                if (pathRecorder != null) pathRecorder.accept(box, PATH_HIT);
                return reuse(m);
            }
            // 簡易確認は親の文脈が同じことを前提に印を見ないので、子へ「文脈が同じ」と伝える前に、自分の印を確かめる
            ctxOk = sameKids(m.kids, box.children) && box.ownPaintStamp() <= m.epoch; // 並びが同じなら、子の記憶の位置対応と前回の層の列が使える
        }
        MEMO_MISSES.increment();
        int owners0 = owners, scroll0 = order.scrollContentCount();
        stack.add(box);
        try {
            Fragment f = buildFresh(box, clip, parentId, moving, ctxOk ? m : null);
            if (pathRecorder != null) pathRecorder.accept(box, lastOwnKept ? PATH_OWN : PATH_REBUILD);
            if (!tainted.contains(box)) store(box, f, clip, parentId, moving, owners - owners0, scroll0);
            return f;
        } finally {
            stack.remove(stack.size() - 1);
        }
    }

    /** 親の文脈が同じとき、子の文脈の確認（位置・大きさ・クリップ・動く祖先・親 id・スクロール量）。子の並びは親が見ている。 */
    private boolean sameContextCheap(Memo m, LayoutBox box, ClipRect clip, String parentId, boolean moving) {
        if (m.parent != box.parent || m.clip != clip || m.moving != moving || !java.util.Objects.equals(m.parentId, parentId)) return false;
        if (m.attrVersion != box.node.attrVersion() || !sameFirstLine(m, box)) return false;
        // 無名の箱は自分の項目を持たないので、位置・大きさは見ない（子が自分の位置を見る）
        if (!box.isAnonymous && (Float.compare(m.x, box.x()) != 0 || Float.compare(m.y, box.y()) != 0 || Float.compare(m.w, box.w()) != 0 || Float.compare(m.h, box.h()) != 0)) return false;
        return scrollSame(m);
    }

    /** 先頭の断片が ::first-line を受けるなら、持ち主〔祖先〕のスタイルと ::first-line のスタイルが前回と同じか（子が祖先から読む唯一のスタイル）。 */
    private static boolean sameFirstLine(Memo m, LayoutBox box) {
        LayoutBox owner = box.inlineFragments.isEmpty() ? null : box.inlineFragments.get(0).firstLineOwner;
        return owner == null ? m.flStyle == null : m.flStyle == owner.style && m.flPseudo == owner.firstLineStyle;
    }

    /** 箱自身の文脈（部分木の印を除く全部）が前回と同じか: 位置・クリップ・親 id・動く祖先・自分と祖先のスタイルの印・親相対の原点・子の並び・スクロール量。 */
    private boolean sameContext(Memo m, LayoutBox box, ClipRect clip, String parentId, boolean moving) {
        if (!sameContextCheap(m, box, clip, parentId, moving)) return false;
        if (box.ownPaintStamp() > m.epoch) return false;
        if (b.parentRelative) {
            LayoutBox p = PaintOrder.effectiveParentNodeBox(box, parentId);
            if ((p != null ? Math.round(p.x()) : 0) != m.pox || (p != null ? Math.round(p.y()) : 0) != m.poy) return false;
        }
        List<LayoutBox> ch = box.children;
        if (ch.size() != m.kids.length) return false;
        for (int i = 0; i < m.kids.length; i++) if (ch.get(i) != m.kids[i]) return false;
        return true;
    }

    private boolean scrollSame(Memo m) {
        for (PaintOrder.ScrollContent c : m.scrolls) { // 節は作ったときのスクロール量を持つ。変わっていたら作り直す
            if (Float.compare(c.sx0, b.scrollState.getScrollX(c.box.node)) != 0 || Float.compare(c.sy0, b.scrollState.getScrollY(c.box.node)) != 0) return false;
        }
        return true;
    }

    /** 記憶の断片を返し、組み立てで起きるはずだった副作用（持ち主の数・スクロールの節・窓の外の記録・箱の記憶の世代）を再現する。 */
    private Fragment reuse(Memo m) {
        MEMO_HITS.increment();
        m.pass = passNo;
        owners += m.owners;
        for (PaintOrder.ScrollContent c : m.scrolls) order.reregister(c);
        return m.frag;
    }

    private void store(LayoutBox box, Fragment f, ClipRect clip, String parentId, boolean moving, int ownersHere, int scroll0) {
        Memo m = memos.get(box);
        if (m == null) memos.put(box, m = new Memo());
        m.frag = f;
        m.box = box;
        m.parent = box.parent;
        m.x = box.x(); m.y = box.y(); m.w = box.w(); m.h = box.h();
        m.clip = clip;
        m.parentId = parentId;
        m.moving = moving;
        LayoutBox p = b.parentRelative ? PaintOrder.effectiveParentNodeBox(box, parentId) : null;
        m.pox = p != null ? Math.round(p.x()) : 0;
        m.poy = p != null ? Math.round(p.y()) : 0;
        m.pass = passNo;
        m.epoch = b.buildEpoch;
        if (m.kids == null || !sameKids(m.kids, box.children)) {
            m.kids = box.children.toArray(NO_BOXES);
            m.kidMemos = new Memo[m.kids.length];
        }
        m.owners = ownersHere;
        m.ownScrolls = lastOwnScrolls;
        m.attrVersion = box.node.attrVersion();
        m.scrollW = box.scrollWidth();
        m.scrollH = box.scrollHeight();
        float fx = 0f, fy = 0f;
        if (!box.inlineFragments.isEmpty()) {
            LayoutBox.InlineFragment f0 = box.inlineFragments.get(0);
            fx = f0.x;
            fy = f0.y;
        }
        m.f0x = fx;
        m.f0y = fy;
        m.canvasSource = box == b.canvasBackgroundSource;
        m.interactive = box.hasInteractiveRules;
        m.suppressShadow = b.suppressTextShadow;
        m.oneNode = order.scrollContentOf(box) != null;
        LayoutBox flOwner = box.inlineFragments.isEmpty() ? null : box.inlineFragments.get(0).firstLineOwner;
        m.flStyle = flOwner == null ? null : flOwner.style;
        m.flPseudo = flOwner == null ? null : flOwner.firstLineStyle;
        m.placeholder = box.placeholderStyle;
        m.scrolls = order.scrollContentCount() == scroll0 ? NO_SCROLLS : order.scrollContentsSince(scroll0).toArray(NO_SCROLLS);
    }

    private static boolean sameKids(LayoutBox[] a, List<LayoutBox> b) {
        if (a.length != b.size()) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b.get(i)) return false;
        return true;
    }

    /**
     * 絶対配置の箱のクリップは、包含ブロックの中身のクリップ（祖先の状態）に依る。包含ブロックより内側の道の箱（その箱を含む断片）は、
     * 自分の外の状態に依るので記憶しない。
     */
    private void taintAboveContainingBlock(LayoutBox abs) {
        LayoutBox cb = abs.findContainingBlock();
        if (cb == null) return;
        for (int i = stack.size() - 1; i >= 0; i--) {
            LayoutBox s = stack.get(i);
            if (s == cb) break;
            tainted.add(s);
        }
    }

    private PaintOrder.ScrollContent[] lastOwnScrolls = NO_SCROLLS;

    /** 箱自身の項目が前回のまま使えるか: 箱の外の入力（属性・中身の大きさ・先頭の断片・描画側の旗）が前回と同じで、記憶できる種類の箱。 */
    private boolean ownReusable(Memo m, LayoutBox box) {
        // 子の確認は親の文脈が同じなら位置・クリップ等だけで済ませるので、箱自身（と祖先のスタイル）の印はここで見る
        if (box.ownPaintStamp() > m.epoch) return false;
        if (!DisplayListBuilder.memoizable(box) || m.attrVersion != box.node.attrVersion()) return false;
        if (Float.compare(m.scrollW, box.scrollWidth()) != 0 || Float.compare(m.scrollH, box.scrollHeight()) != 0) return false;
        float fx = 0f, fy = 0f;
        if (!box.inlineFragments.isEmpty()) {
            LayoutBox.InlineFragment f0 = box.inlineFragments.get(0);
            fx = f0.x;
            fy = f0.y;
        }
        return Float.compare(m.f0x, fx) == 0 && Float.compare(m.f0y, fy) == 0
                && m.canvasSource == (box == b.canvasBackgroundSource) && m.interactive == box.hasInteractiveRules
                && m.suppressShadow == b.suppressTextShadow && m.placeholder == box.placeholderStyle
                && m.oneNode == (order.scrollContentOf(box) != null);
    }

    /** 検証モード（{@code VERIFY_BOX_MEMO}）: 使い回した項目を、記憶を使わず作り直して比べる。 */
    private void verifyOwn(LayoutBox box, ClipRect clip, String parentId, DisplayItem[] reused) {
        if (order.scrollContentOf(box) != null) return; // 作り直すとスクロールの節を二重に登録するので、検証しない
        int saved = b.fragIds.get();
        scratch.resetForBuild();
        order.drawSelf(scratch, box, clip, parentId);
        DisplayItem[] fresh = scratch.toArray(new DisplayItem[0]);
        b.fragIds.set(saved);
        String diff = OwnItemsVerifier.firstDifference(List.of(reused), List.of(fresh));
        if (diff != null) throw new AssertionError("箱の項目の記憶が古い: box=" + box.node.tag + "#" + box.node.key() + " " + diff + (reused.length != fresh.length ? " 記憶=" + java.util.Arrays.stream(reused).map(DisplayItem::id).toList() + " 作り直し=" + java.util.Arrays.stream(fresh).map(DisplayItem::id).toList() : ""));
    }

    /** {@code stale}: 文脈は同じで部分木だけが変わった前回の記憶（無ければ null）。 */
    private Fragment buildFresh(LayoutBox box, ClipRect clip, String parentId, boolean moving, Memo stale) {
        Fragment.Kind kind = Fragment.kindOf(box, order);
        boolean hasSelf = PaintOrder.hasSelf(box);
        LayerSpec layer = kind == Fragment.Kind.STACKING && hasSelf ? order.layerSpecFor(box) : null;
        boolean valueControl = ElementTraits.isValueControl(box.node.tag);
        // 中身の文脈は、箱自身を描く前に決める（箱の描画がスクロールの節を読む）。値の入力部品は中身を持たない
        PaintOrder.ChildContext cc = valueControl ? null : order.enterChildren(box, clip, parentId, ctxAt(stack.size()));

        DisplayItem[] own = Fragment.NONE;
        boolean drawn = false;
        PaintOrder.ScrollContent[] ownScrolls = NO_SCROLLS;
        boolean ownKept = stale != null && !hasSelf;
        if (hasSelf) {
            owners++;
            if (cull && layer == null && !moving && !b.inWindowGeometry(box)) {
                markCulled(box);
                ownKept = stale != null;
            } else {
                // 子の親相対の原点は、親の「描いた」印（paintCulled）を読んで決まる（PaintOrder.effectiveParentNodeBox）。旧経路は親を
                // 先に実行して印を書いてから子を実行するので、ここでも子を描く前に書く（範囲 dl* は展開のとき）
                box.paintCulled = false;
                if (stale != null && stale.frag.drawn && ownReusable(stale, box)) {
                    // 文脈も箱の外の入力も前回と同じ（部分木の中だけが変わった）: 箱自身の項目は前回のものをそのまま使う
                    OWN_REUSED.increment();
                    ownKept = true;
                    own = stale.frag.own;
                    ownScrolls = stale.ownScrolls;
                    for (PaintOrder.ScrollContent c : ownScrolls) order.reregister(c);
                    if (Shortcut.VERIFY_BOX_MEMO.on()) verifyOwn(box, clip, parentId, own);
                } else {
                    int scroll0 = order.scrollContentCount();
                    scratch.resetForBuild();
                    order.drawSelf(scratch, box, clip, parentId);
                    own = scratch.toArray(new DisplayItem[0]);
                    ownScrolls = order.scrollContentCount() == scroll0 ? NO_SCROLLS : order.scrollContentsSince(scroll0).toArray(NO_SCROLLS);
                }
                drawn = true;
            }
        }
        Fragment[] kids = valueControl ? new Fragment[0] : buildKids(box, cc, moving, stale);
        DisplayItem[] after = Fragment.NONE;
        if (!valueControl && PaintOrder.wantsScrollbars(box)) {
            scratch.resetForBuild();
            order.drawScrollbarsIfNeeded(scratch, box, clip);
            after = scratch.toArray(new DisplayItem[0]);
        }
        int z = kind == Fragment.Kind.STACKING ? (PaintOrder.isZIndexAuto(box) ? 0 : box.zIndex()) : 0;
        lastOwnScrolls = ownScrolls; // 直後に build が記憶へ移す（再帰の後に書くので上書きされない）
        lastOwnKept = ownKept;
        return new Fragment(box, own, after, drawn, kind, z, layer, DisplayListBuilder::withLayer, extras(box, kind, layer), kids, Fragment.aggregateFrom(stale == null ? null : stale.frag, kids));
    }

    private Fragment[] buildKids(LayoutBox box, PaintOrder.ChildContext cc, boolean moving, Memo stale) {
        if (box.contentAutoSkipped) return NO_FRAGMENTS;
        List<LayoutBox> ch = box.children;
        int n = ch.size(), depth = stack.size(), c = 0;
        if (depth >= kidScratch.length) kidScratch = Arrays.copyOf(kidScratch, depth * 2);
        Fragment[] tmp = kidScratch[depth];
        if (tmp == null || tmp.length < n) kidScratch[depth] = tmp = new Fragment[Math.max(n, 16)];
        var vf = box.virtualFlowState();
        if (vf == null) {
            Memo[] km = stale != null ? stale.kidMemos : null; // 子の並びは前回と同じ（文脈が同じなので）。子の記憶を配列から引く
            for (int i = 0; i < n; i++) {
                LayoutBox child = ch.get(i);
                Fragment f = kid(child, cc, moving, km != null ? km[i] : null, km != null);
                if (km != null && km[i] == null) km[i] = memos.get(child);
                if (f != null) tmp[c++] = f;
            }
        } else { // 仮想化した並び: 置いた範囲の子と、範囲の外に置いた子だけ（PaintOrder.collectChildren と同じ順）
            int start = vf.placedRangeStart(), end = vf.placedRangeEnd();
            if (start >= 0 && end >= start) {
                for (int i = start, limit = Math.min(end, n - 1); i <= limit; i++) {
                    Fragment f = kid(ch.get(i), cc, moving, null, false);
                    if (f != null) tmp[c++] = f;
                }
            }
            for (int k = 0, m = vf.extraPlacedCount(); k < m; k++) {
                int idx = vf.extraPlacedAt(k);
                if (idx < 0 || idx >= n || (idx >= start && idx <= end)) continue;
                Fragment f = kid(ch.get(idx), cc, moving, null, false);
                if (f != null) tmp[c++] = f;
            }
        }
        Fragment[] prev = stale != null && stale.frag != null ? stale.frag.kids : null;
        Fragment[] res = prev != null && prev.length == c && sameFragments(prev, tmp, c) ? prev : c == 0 ? NO_FRAGMENTS : Arrays.copyOf(tmp, c);
        Arrays.fill(tmp, 0, c, null);
        return res;
    }

    private static boolean sameFragments(Fragment[] a, Fragment[] b, int n) {
        for (int i = 0; i < n; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static final Fragment[] NO_FRAGMENTS = new Fragment[0];

    /** 画面外・窓の外で項目を省いた箱の記録（旧経路の {@code executeTasks} と同じ）。 */
    private void markCulled(LayoutBox owner) {
        owner.dlStart = -1;
        owner.paintCulled = true;
        owner.dlEnd = -1;
        owner.dlOrder = -1;
    }

    /** 描いた箱の範囲・描画順の記録（旧経路の {@code executeTasks} と同じ。展開の順 = 描いた順）。 */
    private void recordOwner(LayoutBox owner, int start, int end) {
        owner.dlStart = start;
        owner.dlEnd = end;
        owner.dlOrder = b.renderedBoxes.size();
        b.renderedBoxes.add(owner);
        if (owner.node != null && PaintOrder.factsOf(owner.style).scrollbars()) b.scrollBoxes.add(owner);
    }
}
