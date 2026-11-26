package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.dom.node.ChangeKind;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.tree.PaintNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 描画用の木（{@link PaintNode}）から表示リストを作る（M2c ②-3。計画書 §3.4.2-4）。描く順は CSS 2.2 付録 E のスタッキングコンテキスト
 * （層別の並び {@link Fragment} / {@link FragSeq} で合成する）、規則は {@link TreeStacking}、箱 1 つ自身の項目は {@link BoxItemPainter}。
 * 出力（{@link DisplayItem}）は旧経路の {@code DisplayListBuilder} と同じ形。
 *
 * <p><b>記憶は 1 層</b>: 鍵は {@code (描画用の木の節 ==, 描く文脈 {@link Context} の値等価)}。節（{@link PaintNode}。断片の同一性で
 * 使い回される）は不変の構造の入力なので参照で、文脈（クリップ・親の id・絶対位置・項目の原点・キャンバスの背景の役・親の表示の種類・
 * スクロールコンテナなら自分のスクロールの位置）は親から渡る値なので {@code ConstraintSpace} と同じく値で照合する（計画書 §2.1-4。
 * 表示項目が絶対の座標・クリップ・親の id を持つ間〔M3b の {@code DisplayScene} まで〕は文脈も入力）。ビューポートと装置の画素比が
 * 変われば記憶を全て捨てる。記憶は前回の木の形のまま持ち（{@link Memo} が子の記憶を持つ。節 → 結果の大域の表は作らない）、使い回した
 * 部分木の記憶はそのまま次へ渡る。描画は DOM を読まない（M3b-1・決定 F）: 属性・入力の値・textarea の中身・状態の規則が掛かるかは
 * 箱の木が写して最新の箱に持つ（{@code BoxNode.values}）ので、属性だけの変化も節（{@link PaintNode}）の変化として届き、変更台帳に
 * 載った節の祖先を数えなくてよい。台帳を読むのは、スクロールしたコンテナ（節つきの {@code SCROLL}）の祖先を使い回さないためだけ
 * （文脈の絶対位置が祖先で変わらないまま中身の位置が変わる。計画書 §3.2 の凍結した例外で、M3b-2 の親相対の scene で消す）。台帳に
 * 載らない入力（画像・SVG の到着）はホストが全体を組み直す（{@code recomputeLayout}。台帳なしのフレームは記憶を使わない）。記憶して
 * よい箱をタグ名で決める一覧は持たない。</p>
 *
 * <p>項目の座標は常に親の節からの相対（旧経路の既定）。旧経路の切り分け用の切り替え（文脈の {@code parentRelativeDisplayItems}）は読まない。</p>
 */
public final class TreePainter {
    /** 背景の指定が無い文書のキャンバス（旧経路と同じく透明）。 */
    private static final int DEFAULT_CANVAS_COLOR = 0x00000000;
    /** マスクの項目の部品の番号の始まり（旧経路と同じ）。 */
    private static final int MASK_ID_BASE = 1_000_000;
    private static final ItemProps EMPTY_PROPS = new ItemProps();
    private static final Memo[] NO_MEMOS = new Memo[0];

    private final AtomicInteger fragIds = new AtomicInteger();
    private final BoxItemPainter painter;
    private final TreeStacking stacking;
    private final PaintSupport.CaptureList scratch = new PaintSupport.CaptureList(64);
    private PaintView canvasSource;
    private float devicePixelRatio = 1f;

    /** 前回の根の記憶と、その時のビューポート・装置の画素比。 */
    private Memo rootMemo;
    private int memoW, memoH;
    private float memoDpr;
    /** 1 回の描画の間だけ: 台帳に節つきの {@code SCROLL} で載ったスクロールコンテナとその祖先（null なら全て使い回さない）。 */
    private Set<HtmlNode> scrolled;
    private Memo lastMemo;
    private int reused, built;

    public TreePainter(int rootW, int rootH, Map<String, Keyframes> keyframes, ScrollState scrollState) {
        this.painter = new BoxItemPainter(rootW, rootH, keyframes != null ? keyframes : Map.of(), fragIds);
        this.stacking = new TreeStacking(rootW, rootH, scrollState != null ? scrollState : new ScrollState(), painter.anim);
    }

    /** 装置の画素比（画像の候補の選び方。既定は 1）。 */
    public void setDevicePixelRatio(float dpr) {
        this.devicePixelRatio = dpr;
    }

    /** ビューポートの大きさが変わった。 */
    public void setViewport(int rootW, int rootH) {
        painter.setViewport(rootW, rootH);
        stacking.rootW = rootW;
        stacking.rootH = rootH;
    }

    /** 直近の描画で中身を 1 つの節にまとめたスクロールコンテナの記録。 */
    public List<ScrollContentInfo> scrollContents() {
        return stacking.scrollContentInfos();
    }

    /** 直前の描画で前回の結果を使った部分木の数。 */
    public int reused() {
        return reused;
    }

    /** 直前の描画で作り直した箱の数。 */
    public int built() {
        return built;
    }

    /** 表示リストを作る（前回の結果を使わない）。 */
    public List<DisplayItem> paint(PaintNode root) {
        return paint(root, null);
    }

    /**
     * 表示リストを作る。{@code ledger} はこのフレームの凍結済みの変更台帳（前回の描画からの変化）で、null なら前回の結果を使わない。
     */
    public List<DisplayItem> paint(PaintNode root, ChangeLedger ledger) {
        painter.decor.setDevicePixelRatio(devicePixelRatio);
        stacking.beginPass();
        stacking.followedScrollers = com.nishiyu.enderframelib.core.paint.tree.AnchorFollow.followedScrollers(root);
        fragIds.set(0);
        reused = built = 0;
        scrolled = readLedger(ledger);
        Memo prev = scrolled != null && memoW == stacking.rootW && memoH == stacking.rootH
                && Float.compare(memoDpr, devicePixelRatio) == 0 ? rootMemo : null;
        PaintView top = PaintView.root(root, stacking.scrollState, stacking.rootW, stacking.rootH);
        canvasSource = null;
        List<DisplayItem> out = new ArrayList<>();
        if ("root".equals(top.node().tag)) {
            int canvasBg = DEFAULT_CANVAS_COLOR;
            PaintView source = TreeStacking.canvasBackgroundSource(top);
            if (source != null) {
                canvasBg = source.style().typed().visual.backgroundColor;
                canvasSource = source;
            }
            if (canvasBg != 0x00000000) {
                long key = source != null && source.node() != null ? DisplayItem.bgKeyOf(source.node().nodeId()) : 0L;
                PaintSupport.CaptureList bg = new PaintSupport.CaptureList(1);
                PaintSupport.addElement(bg, "bg_root", "bg_root", ElementType.RECT, top.x(), top.y(), Math.max(top.w(), stacking.rootW),
                        Math.max(top.h(), stacking.rootH), "", 0, canvasBg, EMPTY_PROPS, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                        RenderMeta.EMPTY, key);
                out.add(bg.get(0));
            }
        }
        try {
            Fragment f = buildTop(top, prev);
            DisplayItem[] items = new DisplayItem[f.size()];
            f.emitted.copyTo(items, 0);
            out.addAll(Arrays.asList(items));
            memoW = stacking.rootW;
            memoH = stacking.rootH;
            memoDpr = devicePixelRatio;
            return out;
        } finally {
            scrolled = null;
        }
    }

    /**
     * 台帳に節つきの {@code SCROLL} で載ったスクロールコンテナとその祖先（あふれなら null で、何も使い回さない）。他の種別は読まない:
     * 描画の入力は全て節（最新の箱が写した DOM の値を含む）と文脈にあり、節の同一性と文脈の値で照合できる。節の無い {@code SCROLL} は
     * どのコンテナかを節つきの {@code SCROLL} が別に載せる。
     */
    private static Set<HtmlNode> readLedger(ChangeLedger ledger) {
        if (ledger == null || ledger.frozenOverflow()) return null;
        Set<HtmlNode> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0, n = ledger.frozenSize(); i < n; i++) {
            HtmlNode node = ledger.frozenNode(i);
            if (node == null || !ledger.frozenIs(i, ChangeKind.SCROLL)) continue;
            for (HtmlNode cur = node; cur != null && out.add(cur); cur = cur.parent) { }
        }
        return out;
    }

    private Fragment buildTop(PaintView root, Memo prev) {
        if ("root".equals(root.node().tag)) {
            TreeStacking.ChildContext cc = stacking.enterChildren(root, null, null);
            Memo[] kidMemos = new Memo[root.paintChildren().size()];
            Fragment[] kids = kids(root, cc, prev, kidMemos);
            rootMemo = new Memo(root.n, null, null, false, TreeStacking.NO_SCROLLS, kidMemos);
            return new Fragment(root.n, Fragment.NONE, Fragment.NONE, false, Fragment.Kind.ROOT, 0, null, null, null, kids.length,
                    kids.length < 64 ? kids : null, kids.length >= 64 ? Fragment.chunkifyFrom(null, kids, kids.length) : null,
                    Fragment.aggregate(kids));
        }
        Fragment f = build(root, null, null, prev);
        rootMemo = lastMemo;
        if (f == null) return Fragment.EMPTY;
        return new Fragment(root.n, f.own, f.ownAfter, f.drawn, Fragment.Kind.ROOT, 0, null, null, null, f.kidCount, f.smallKids,
                f.bigKids, f.agg);
    }

    /** 子を描き、子の記憶を {@code memos}（子の並びの位置）へ入れる。 */
    private Fragment[] kids(PaintView box, TreeStacking.ChildContext cc, Memo prev, Memo[] memos) {
        List<PaintView> ch = box.paintChildren();
        Map<Object, Memo> prevKids = prev != null ? prev.index() : null;
        Fragment[] tmp = new Fragment[ch.size()];
        int c = 0;
        for (int i = 0; i < tmp.length; i++) {
            PaintView child = ch.get(i);
            Memo pm = prevKids == null ? null : prevKids.get(child.n);
            if (pm == null && prevKids != null && !child.n.node.isPseudoNode) pm = prevKids.get(child.n.node);
            Fragment f = build(child, stacking.clipForChild(child, cc.clip()), cc.parentId(), pm);
            memos[i] = f != null ? lastMemo : null;
            if (f != null) tmp[c++] = f;
        }
        return c == tmp.length ? tmp : Arrays.copyOf(tmp, c);
    }

    /** {@code content-visibility: auto} の箱を「利用者に関連する」とみなす、ビューポートの外の余白（CSS px。旧経路と同じ値）。 */
    static final float CONTENT_VISIBILITY_MARGIN = 640f;

    /**
     * 箱の枠線の箱が、ビューポートを関連の余白（{@link #CONTENT_VISIBILITY_MARGIN}）だけ広げた矩形にかかるか（CSS Contain 2 §4.1 の
     * 「利用者に関連する」。位置はスクロールの位置を足した見え方なので、スクロールで関連が変わる。旧経路と同じ余白）。
     */
    private boolean nearViewport(PaintView box) {
        float m = CONTENT_VISIBILITY_MARGIN;
        return box.x() + Math.max(0f, box.w()) >= -m && box.x() <= stacking.rootW + m
                && box.y() + Math.max(0f, box.h()) >= -m && box.y() <= stacking.rootH + m;
    }

    /** 箱 1 つとその部分木（旧 {@code FragmentPaint.buildFresh} の手順）。描かない箱なら null。{@link #lastMemo} に記憶を残す。 */
    private Fragment build(PaintView box, ClipRect clip, String parentId, Memo prev) {
        lastMemo = null;
        if (box.displayNone() || PaintFacts.of(box.style()).displayNone() || ElementTraits.isOption(box.node().tag)) return null;
        if (box.style().isContentVisibilityAuto() && !nearViewport(box)) return null; // 画面から遠い content-visibility: auto は描かない
        PaintView origin = TreeStacking.effectiveParentNodeBox(box, parentId);
        Context ctx = Context.of(box, clip, parentId, origin, box == canvasSource, stacking);
        if (prev != null && prev.node == box.n && prev.reusable && !scrolledBelow(box) && prev.ctx.equals(ctx)) {
            reused++;
            stacking.replayScrollContents(prev.scrolls);
            lastMemo = prev;
            return prev.frag;
        }
        built++;
        Fragment.Kind kind = stacking.kindOf(box);
        boolean hasSelf = TreeStacking.hasSelf(box);
        LayerSpec layer = kind == Fragment.Kind.STACKING && hasSelf ? stacking.layerSpecFor(box) : null;
        boolean valueControl = ElementTraits.isValueControl(box.node().tag);
        TreeStacking.ChildContext cc = valueControl ? null : stacking.enterChildren(box, clip, parentId);
        boolean ownNode = TreeStacking.boxHasOwnNode(box);
        boolean reusable = true;

        DisplayItem[] own = Fragment.NONE;
        boolean drawn = false;
        if (hasSelf) {
            scratch.resetForBuild();
            fragIds.set(0); // 部品の ID は箱ごとに数える
            painter.drawBox(scratch, box, clip, parentId, null, origin, ownNode, ctx.canvas());            TreeStacking.ScrollContent content = stacking.scrollContentOf(box);
            if (content != null) { // 箱の項目のあと・子孫の項目の前に、中身を動かす節
                int sox = scratch.originX, soy = scratch.originY;
                if (ownNode) scratch.setOrigin(Math.round(box.x()), Math.round(box.y()));
                try {
                    stacking.addMovingNode(scratch, content.contentId, box, null);
                } finally {
                    scratch.setOrigin(sox, soy);
                }
            }
            own = scratch.toArray(new DisplayItem[0]);
            drawn = true;
        }
        Fragment.Extras ex = extras(box, kind, layer, ownNode);
        DisplayItem[] after = Fragment.NONE;
        if (!valueControl && TreeStacking.wantsScrollbars(box)) {
            scratch.resetForBuild();
            stacking.drawScrollbars(scratch, box, clip);
            after = scratch.toArray(new DisplayItem[0]);
        }
        int scrollMark = stacking.scrollInfoCount();
        stacking.finishScrollContent(box); // 文書順（子より前）に記録する
        int z = kind == Fragment.Kind.STACKING ? (TreeStacking.isZIndexAuto(box) ? 0 : TreeStacking.zIndex(box)) : 0;
        Fragment result;
        Memo[] kidMemos = NO_MEMOS;
        if (valueControl) {
            result = !drawn && after.length == 0 && ex == null ? Fragment.EMPTY
                    : new Fragment(box.n, own, after, drawn, kind, z, layer, BoxItemPainter::withLayer, ex, 0, Fragment.NO_KIDS_ARRAY, null,
                    Fragment.NO_KIDS);
        } else {
            kidMemos = new Memo[box.paintChildren().size()];
            Fragment[] kids = kids(box, cc, prev, kidMemos);
            for (int i = 0; i < kidMemos.length && reusable; i++) {
                PaintView k = box.paintChildren().get(i);
                if (kidMemos[i] != null ? !kidMemos[i].reusable : !k.displayNone() && !ElementTraits.isOption(k.node().tag)) reusable = false;
            }
            int c = kids.length;
            Fragment[] small = c < 64 ? kids : null;
            Fragment[][] big = c >= 64 ? Fragment.chunkifyFrom(null, kids, c) : null;
            FragSeq[] agg = Fragment.aggregateDiff(null, small, big, c);
            result = !drawn && after.length == 0 && ex == null && agg == Fragment.NO_KIDS ? Fragment.EMPTY
                    : new Fragment(box.n, own, after, drawn, kind, z, layer, BoxItemPainter::withLayer, ex, c, small, big, agg);
        }
        lastMemo = new Memo(box.n, ctx, result, reusable, stacking.scrollInfosSince(scrollMark), kidMemos);
        return result;
    }

    /** スクロールしたコンテナ（か、その祖先）か。疑似要素などの合成の節は持ち主で見る。 */
    private boolean scrolledBelow(PaintView box) {
        if (scrolled == null) return true;
        HtmlNode h = box.node();
        return scrolled.contains(h) || ((h.isPseudoNode || box.anonymous()) && h.parent != null && scrolled.contains(h.parent));
    }

    /** マスクの項目（レイヤーがマスクを求めるとき）と Top Layer の印・{@code ::backdrop}（旧 {@code FragmentPaint.extras}）。 */
    private Fragment.Extras extras(PaintView box, Fragment.Kind kind, LayerSpec layer, boolean ownNode) {
        DisplayItem[] mask = null, backdrop = null;
        boolean hoist = kind == Fragment.Kind.STACKING && TreeStacking.isTopLayer(box);
        if (layer != null && layer.wantsMask()) {
            scratch.resetForBuild();
            fragIds.set(MASK_ID_BASE);
            painter.drawMask(scratch, box, ownNode);
            mask = scratch.toArray(new DisplayItem[0]);
        }
        if (hoist && box.backdrop() != null) { // ::backdrop はビューポート全面に、Top Layer の箱の直前に描く（G5）
            scratch.resetForBuild();
            painter.drawBackdrop(scratch, box, box.backdrop(), ownNode);
            backdrop = scratch.toArray(new DisplayItem[0]);
        }
        return mask == null && !hoist ? null : new Fragment.Extras(mask, hoist, backdrop);
    }

    /**
     * 箱の項目が節（{@link PaintNode}）の外から受け取る入力。親から渡る値なので値で照合する（record の等価。クリップは値の等価を持つ）。
     * {@code x}・{@code y} は箱の絶対位置（スクロールと sticky の貼り付きを足した見え方の位置）、{@code originX}・{@code originY} は項目の
     * 座標の原点にする節の位置（親相対の原点）、{@code scrollX}・{@code scrollY} は箱がスクロールコンテナなら自分のスクロールの位置
     * （スクロールバーのつまみ・中身の節の基準。それ以外は 0）。
     */
    record Context(ClipRect clip, String parentId, float x, float y, int originX, int originY, boolean canvas,
                   Display parentDisplay, float scrollX, float scrollY) {
        static Context of(PaintView box, ClipRect clip, String parentId, PaintView origin, boolean canvas, TreeStacking stacking) {
            PaintView parent = box.paintParent();
            boolean scrolls = box.isScrollContainer();
            return new Context(clip, parentId, box.x(), box.y(), origin != null ? Math.round(origin.x()) : 0,
                    origin != null ? Math.round(origin.y()) : 0, canvas, parent != null ? parent.display() : null,
                    scrolls ? stacking.scrollState.getScrollX(box.node()) : 0f, scrolls ? stacking.scrollState.getScrollY(box.node()) : 0f);
        }
    }

    /** 箱 1 つの記憶: 描いた文脈・部分木の結果・部分木の中身の節の記録（文書順）・子の記憶（子の並びの位置）。前回の木の形のまま持つ。 */
    private record Memo(PaintNode node, Context ctx, Fragment frag, boolean reusable, ScrollContentInfo[] scrolls, Memo[] kids) {
        /** 子の記憶を、描画用の木の節と DOM の節（合成でない節）で引けるようにする。 */
        Map<Object, Memo> index() {
            if (kids.length == 0) return null;
            Map<Object, Memo> out = new IdentityHashMap<>(kids.length * 2);
            for (Memo k : kids) {
                if (k == null) continue;
                out.put(k.node, k);
                if (!k.node.node.isPseudoNode) out.putIfAbsent(k.node.node, k);
            }
            return out;
        }
    }
}
