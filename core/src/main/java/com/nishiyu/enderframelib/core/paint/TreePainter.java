package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.dom.node.ChangeKind;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.tree.PaintNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import com.nishiyu.enderframelib.core.replaced.ReplacedElements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 描画用の木（{@link PaintNode}）から表示リストを作る（M2c ②-3。計画書 §3.4.2-4）。描く順は CSS 2.2 付録 E のスタッキングコンテキスト
 * （層別の並び {@link Fragment} / {@link FragSeq} で合成する）、規則は {@link TreeStacking}、箱 1 つ自身の項目は {@link BoxItemPainter}。
 * 出力（{@link DisplayItem}）は旧経路の {@code DisplayListBuilder} と同じ形。
 *
 * <p><b>記憶は 1 層</b>: 描画用の木の節（{@link PaintNode}。断片の同一性で使い回される）が前回と同じ参照で、描く文脈（クリップ・親の id・
 * 絶対位置・項目の原点・キャンバスの背景の役・親の表示の種類・ビューポート）も同じなら、前回の部分木の結果（{@link Fragment}）を
 * そのまま使う。記憶は前回の木の形のまま持ち（{@link Memo} が子の記憶を持つ。節 → 結果の大域の表は作らない）、使い回した部分木の
 * 記憶はそのまま次へ渡る。描画の項目が DOM を直に読む所（属性・入力の値）があるので、変更台帳に載った節とその祖先は使い回さない
 * （入口は台帳 1 本）。スクロールに依る部分木（スクロールバー・スクロールの中身の節）と、旧経路でも記憶しない部品も使い回さない。</p>
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

    /** 前回の根の記憶と、その時のビューポート・座標の方式。 */
    private Memo rootMemo;
    private int memoW, memoH;
    private boolean memoRel;
    /** 1 回の描画の間だけ: 台帳に載った節とその祖先（null なら全て使い回さない）。 */
    private Set<HtmlNode> touched;
    private Memo lastMemo;
    private int reused, built;

    public TreePainter(int rootW, int rootH, Map<String, Keyframes> keyframes, ScrollState scrollState) {
        this.painter = new BoxItemPainter(rootW, rootH, keyframes != null ? keyframes : Map.of(), fragIds);
        this.stacking = new TreeStacking(rootW, rootH, scrollState != null ? scrollState : new ScrollState(), painter.anim);
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
        boolean rel = DisplayListBuilder.parentRelativeSource.getAsBoolean();
        painter.parentRelative = rel;
        stacking.parentRelative = rel;
        painter.decor.setDevicePixelRatio(1f);
        stacking.beginPass();
        fragIds.set(0);
        reused = built = 0;
        touched = readLedger(ledger);
        Memo prev = touched != null && rel == memoRel && memoW == stacking.rootW && memoH == stacking.rootH ? rootMemo : null;
        PaintView top = PaintView.root(root);
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
            memoRel = rel;
            return out;
        } finally {
            touched = null;
        }
    }

    /** 台帳に載った節とその祖先（節を絞れない変更・あふれなら null で、何も使い回さない）。 */
    private static Set<HtmlNode> readLedger(ChangeLedger ledger) {
        if (ledger == null || ledger.frozenOverflow()) return null;
        Set<HtmlNode> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0, n = ledger.frozenSize(); i < n; i++) {
            HtmlNode node = ledger.frozenNode(i);
            if (node == null) {
                if (ledger.frozenIs(i, ChangeKind.SCROLL)) continue;
                return null;
            }
            for (HtmlNode cur = node; cur != null && out.add(cur); cur = cur.parent) { }
        }
        return out;
    }

    private Fragment buildTop(PaintView root, Memo prev) {
        if ("root".equals(root.node().tag)) {
            TreeStacking.ChildContext cc = stacking.enterChildren(root, null, null);
            Memo[] kidMemos = new Memo[root.paintChildren().size()];
            Fragment[] kids = kids(root, cc, prev, kidMemos);
            rootMemo = new Memo(root.n, null, null, null, 0, 0, 0, 0, false, null, false, kidMemos);
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

    /** 箱 1 つとその部分木（旧 {@code FragmentPaint.buildFresh} の手順）。描かない箱なら null。{@link #lastMemo} に記憶を残す。 */
    private Fragment build(PaintView box, ClipRect clip, String parentId, Memo prev) {
        lastMemo = null;
        if (box.displayNone() || PaintOrder.factsOf(box.style()).displayNone() || ElementTraits.isOption(box.node().tag)) return null;
        PaintView origin = TreeStacking.effectiveParentNodeBox(box, parentId);
        int pox = origin != null ? Math.round(origin.x()) : 0, poy = origin != null ? Math.round(origin.y()) : 0;
        boolean canvas = box == canvasSource;
        PaintView parent = box.paintParent();
        LayoutBox.Display parentDisplay = parent != null ? parent.display() : null;
        if (prev != null && prev.node == box.n && prev.reusable && !touched(box) && Objects.equals(prev.clip, clip)
                && Objects.equals(prev.parentId, parentId) && Float.compare(prev.ax, box.x()) == 0 && Float.compare(prev.ay, box.y()) == 0
                && prev.pox == pox && prev.poy == poy && prev.canvas == canvas && Objects.equals(prev.parentDisplay, parentDisplay)) {
            reused++;
            lastMemo = prev;
            return prev.frag;
        }
        built++;
        Fragment.Kind kind = stacking.kindOf(box);
        boolean hasSelf = TreeStacking.hasSelf(box);
        LayerSpec layer = kind == Fragment.Kind.STACKING && hasSelf ? stacking.layerSpecFor(box) : null;
        boolean valueControl = ElementTraits.isValueControl(box.node().tag);
        TreeStacking.ChildContext cc = valueControl ? null : stacking.enterChildren(box, clip, parentId);
        boolean ownNode = stacking.parentRelative && TreeStacking.boxHasOwnNode(box);
        boolean reusable = memoizable(box);

        DisplayItem[] own = Fragment.NONE;
        boolean drawn = false;
        if (hasSelf) {
            scratch.resetForBuild();
            fragIds.set(0); // 部品の ID は箱ごとに数える
            painter.drawBox(scratch, box, clip, parentId, null, origin, ownNode, canvas);
            TreeStacking.ScrollContent content = stacking.scrollContentOf(box);
            if (content != null) { // 箱の項目のあと・子孫の項目の前に、中身を動かす節
                reusable = false;
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
            reusable = false;
            scratch.resetForBuild();
            stacking.drawScrollbars(scratch, box, clip);
            after = scratch.toArray(new DisplayItem[0]);
        }
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
        lastMemo = new Memo(box.n, result, clip, parentId, box.x(), box.y(), pox, poy, canvas, parentDisplay, reusable, kidMemos);
        return result;
    }

    /** 台帳に載った節（か、その祖先）か。疑似要素などの合成の節は持ち主で見る。 */
    private boolean touched(PaintView box) {
        if (touched == null) return true;
        HtmlNode h = box.node();
        return touched.contains(h) || ((h.isPseudoNode || box.anonymous()) && h.parent != null && touched.contains(h.parent));
    }

    /**
     * 箱の項目が箱の入力（描画用の木の節）だけで決まるか（旧 {@code DisplayListBuilder.memoizable} と同じ除外）。表・フォームの部品・
     * SVG・動画・{@code srcset} 付きの画像など、描画が別の資源や状態を読む箱は毎回描く。
     */
    private static boolean memoizable(PaintView box) {
        String tag = box.node().tag;
        if (tag == null) return false;
        if (tag.indexOf('-') >= 0) return ReplacedElements.isHost(tag);
        switch (tag) {
            case "video", "audio", "svg", "canvas", "textarea", "select", "fieldset", "legend", "input", "button",
                 "table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th", "col", "colgroup" -> {
                return false;
            }
            case "img" -> {
                Map<String, String> attrs = box.node().attrs;
                if (attrs.containsKey("srcset") || attrs.containsKey("sizes")) return false;
                String src = Style.or(box.style().typed().rare.src, attrs.getOrDefault("src", ""));
                if (com.nishiyu.enderframelib.core.raster.svg.SvgImage.looksLikeSvg(src)) return false;
            }
            default -> { }
        }
        if (box.collapsedBorders() != null || box.display() == null) return false;
        return switch (box.display().inner()) {
            case FLOW, FLOW_ROOT, FLEX, GRID, NONE -> true;
            default -> false;
        };
    }

    /** マスクの項目（レイヤーがマスクを求めるとき）と Top Layer の印（旧 {@code FragmentPaint.extras}。{@code ::backdrop} は新経路にまだ無い）。 */
    private Fragment.Extras extras(PaintView box, Fragment.Kind kind, LayerSpec layer, boolean ownNode) {
        DisplayItem[] mask = null;
        boolean hoist = kind == Fragment.Kind.STACKING && TreeStacking.isTopLayer(box);
        if (layer != null && layer.wantsMask()) {
            scratch.resetForBuild();
            fragIds.set(MASK_ID_BASE);
            painter.drawMask(scratch, box, ownNode);
            mask = scratch.toArray(new DisplayItem[0]);
        }
        return mask == null && !hoist ? null : new Fragment.Extras(mask, hoist, null);
    }

    /** 箱 1 つの記憶: 描いた文脈・部分木の結果・子の記憶（子の並びの位置）。前回の木の形のまま持つ。 */
    private record Memo(PaintNode node, Fragment frag, ClipRect clip, String parentId, float ax, float ay, int pox, int poy, boolean canvas,
                        LayoutBox.Display parentDisplay, boolean reusable, Memo[] kids) {
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
