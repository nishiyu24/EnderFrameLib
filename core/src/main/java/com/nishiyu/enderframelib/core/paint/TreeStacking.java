package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.VisualStyle;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.box.Display;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.ClipShape;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isInvisible;

/**
 * 描画用の木の重なりの規則（M2c ②-3。旧 {@code PaintOrder} の規則を {@link PaintView} へ移植したもの）: 子の中身に掛けるクリップ・
 * 子の項目の親・スタッキングコンテキストと層の種類・レイヤーの指定・スクロールバー・スクロールの中身の節。1 回の描画の間だけの表を持つ
 * （{@link #beginPass} で空にする）。旧経路の記憶（クリップの使い回し・節の記憶）・窓・sticky の式は持たない。
 *
 * <p>項目の座標は常に親の節からの相対（旧経路の既定）で、中身を 1 つの節にまとめられるスクロールコンテナは常にまとめる。旧経路の
 * 切り分け用の切り替え（{@code Shortcut}・文脈の {@code parentRelativeDisplayItems}）は読まない（プロセス共有の状態に依らない）。</p>
 */
final class TreeStacking {
    /** 切らない軸のクリップの端（旧経路と同じ値）。 */
    static final float UNCLIPPED = PaintFacts.UNCLIPPED;
    private static final RenderMeta MOVING_NODE_META = new RenderMeta(null, null, null, 12.0f, 0.5f, 0.5f, 1, false);
    private static final Corners SCROLLBAR_CORNERS = new Corners(2, 2, 2, 2);
    static final ScrollContentInfo[] NO_SCROLLS = new ScrollContentInfo[0];

    int rootW, rootH;
    final ScrollState scrollState;
    private final BoxAnimation anim;

    // ---- 1 回の描画の間だけ ----
    private final Map<PaintView, ClipRect> contentClips = new IdentityHashMap<>();
    private final Map<PaintView, Boolean> blendDescendants = new IdentityHashMap<>();
    private final Map<PaintView, ScrollContent> scrollContentOf = new IdentityHashMap<>();
    /** 中身の節の記録（文書順。使い回した部分木の分は記憶から足す）。 */
    private final List<ScrollContentInfo> scrollInfos = new ArrayList<>();

    /** 中身を 1 つの節にまとめたスクロールコンテナ（旧 {@code PaintOrder.ScrollContent}）。 */
    static final class ScrollContent {
        final PaintView box;
        final String contentId;
        final float sx0, sy0;
        String thumbVId, thumbHId;
        float kx, ky, fx0, fy0;
        /** 中身の中の sticky の箱の式（スクロールだけの更新で節に貼り付きのずれの増減を足す）。 */
        List<com.nishiyu.enderframelib.core.paint.model.StickyShift> stickies = List.of();

        ScrollContent(PaintView box, String contentId, float sx0, float sy0) {
            this.box = box;
            this.contentId = contentId;
            this.sx0 = sx0;
            this.sy0 = sy0;
        }
    }

    /** 子へ渡す文脈（中身に掛けるクリップ・子の項目の親の id）。 */
    record ChildContext(ClipRect clip, String parentId) { }

    TreeStacking(int rootW, int rootH, ScrollState scrollState, BoxAnimation anim) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.scrollState = scrollState;
        this.anim = anim;
    }

    /** 外の箱が追従するスクロールコンテナ（G8。{@link #scrollsAsOneNode} で中身を 1 つの節にしない）。描く回ごとに置く。 */
    java.util.Set<com.nishiyu.enderframelib.core.dom.node.HtmlNode> followedScrollers = java.util.Set.of();

    void beginPass() {
        contentClips.clear();
        blendDescendants.clear();
        scrollContentOf.clear();
        scrollInfos.clear();
    }

    ScrollContent scrollContentOf(PaintView box) {
        return scrollContentOf.get(box);
    }

    /** 直近の描画で中身の節を作ったスクロールコンテナの記録。 */
    List<ScrollContentInfo> scrollContentInfos() {
        return new ArrayList<>(scrollInfos);
    }

    /** これまでに記録した中身の節の数（部分木の記録の始まりの印）。 */
    int scrollInfoCount() {
        return scrollInfos.size();
    }

    /** 箱 {@code box} の中身の節の記録を確定する（スクロールバーを描いた後。中身の節が無ければ何もしない）。 */
    void finishScrollContent(PaintView box) {
        ScrollContent c = scrollContentOf.get(box);
        if (c != null) {
            scrollInfos.add(new ScrollContentInfo(c.box.node(), c.contentId, c.thumbVId, c.thumbHId, c.sx0, c.sy0, c.kx, c.ky, c.fx0, c.fy0,
                    c.stickies));
        }
    }

    /** 印 {@code mark} の後に記録した中身の節（部分木の分。記憶に持たせる）。 */
    ScrollContentInfo[] scrollInfosSince(int mark) {
        int n = scrollInfos.size() - mark;
        return n == 0 ? NO_SCROLLS : scrollInfos.subList(mark, scrollInfos.size()).toArray(new ScrollContentInfo[n]);
    }

    /** 使い回した部分木の中身の節の記録を足す（描いた時のスクロールの位置〔{@code sx0}〕のまま。動かすのはホストの節）。 */
    void replayScrollContents(ScrollContentInfo[] recorded) {
        if (recorded.length > 0) scrollInfos.addAll(java.util.Arrays.asList(recorded));
    }

    // ---- 箱の素性 ----

    static boolean boxHasOwnNode(PaintView b) {
        return b != null && !b.anonymous() && b.w() > 0 && b.h() > 0 && b.node() != null
                && !"root".equals(b.node().tag) && !"#text".equals(b.node().tag);
    }

    static boolean hasSelf(PaintView box) {
        return box.w() > 0 && box.h() > 0 && !box.anonymous();
    }

    private static Position position(PaintView box) {
        return box.style().typed().box.position;
    }

    static boolean isFixed(PaintView box) {
        return position(box) == Position.FIXED;
    }

    static boolean isSticky(PaintView box) {
        return position(box) == Position.STICKY;
    }

    static boolean isPositioned(PaintView box) {
        Position p = position(box);
        return p == Position.RELATIVE || p == Position.ABSOLUTE || p == Position.FIXED || p == Position.STICKY;
    }

    static boolean isZIndexAuto(PaintView box) {
        return PaintFacts.of(box.style()).zAuto();
    }

    static int zIndex(PaintView box) {
        return (int) Style.num(box.style().typed().visual.zIndex, 0f);
    }

    /** float（旧 {@code BlockLayoutEngine.isFloating}: 親がブロックの整形をする箱）。 */
    static boolean isFloating(PaintView box) {
        PaintView p = box.paintParent();
        if (box.isAbsolute() || p == null || "#text".equals(box.node().tag)) return false;
        if (!box.style().typed().facts.floating) return false;
        Display d = p.display();
        if (d == null) return true;
        return switch (d.inner()) {
            case FLEX, GRID, TABLE, TABLE_ROW_GROUP, TABLE_ROW -> false;
            default -> true;
        };
    }

    /** 層の種類（旧 {@code Fragment.kindOf}）。 */
    Fragment.Kind kindOf(PaintView b) {
        if (createsStackingContext(b)) return Fragment.Kind.STACKING;
        if (isPositioned(b)) return Fragment.Kind.POSITIONED;
        if (isFloating(b)) return Fragment.Kind.FLOAT;
        if (PaintSupport.isAtomicInline(b)) return Fragment.Kind.ATOMIC;
        return b.display() != null && b.display().isInlineLevel() ? Fragment.Kind.INLINE : Fragment.Kind.BLOCK;
    }

    /** その箱の {@code animation} が {@code opacity} を動かすか。 */
    boolean animatesOpacity(PaintView box) {
        return PaintFacts.of(box.style()).hasAnimation()
                && (anim.parseAnimationDef(box.style(), box.w(), box.h(), 0, 0).animatedChannels() & AnimationSpec.CH_OPACITY) != 0;
    }

    /** スタッキングコンテキストを作るか（旧 {@code PaintOrder.createsStackingContext}）。 */
    boolean createsStackingContext(PaintView box) {
        PaintFacts f = PaintFacts.of(box.style());
        boolean zAuto = f.zAuto();
        if (isPositioned(box) && !zAuto) return true;
        if (isFixed(box) || isSticky(box)) return true;
        if (!zAuto && PaintSupport.isFlexOrGridItem(box)) return true;
        if (f.opacity() < 1f) return true;
        if (animatesOpacity(box)) return true;
        return f.stackingByStyle();
    }

    /** Top Layer の箱（旧 {@code PaintOrder.isTopLayer}）。 */
    static boolean isTopLayer(PaintView box) {
        return isFixed(box) && zIndex(box) == PaintFacts.TOP_LAYER_Z && !isZIndexAuto(box);
    }

    /** 包含ブロック（旧 {@code LayoutReads.findContainingBlockRaw}。null ならビューポート）。 */
    static PaintView containingBlock(PaintView box) {
        boolean fixed = isFixed(box);
        for (PaintView cur = box.paintParent(); cur != null; cur = cur.paintParent()) {
            VisualStyle v = cur.style().typed().visual;
            boolean transformLike = !PaintFacts.isNone(v.transform) || !PaintFacts.isNone(v.filter)
                    || (cur.style().containment() & (ComputedStyle.CONTAIN_LAYOUT | ComputedStyle.CONTAIN_PAINT)) != 0;
            if (transformLike || (!fixed && isPositioned(cur))) {
                return cur;
            }
        }
        return null;
    }

    /** 項目が親として辿る、自分の節を持つ祖先の箱（旧 {@code PaintOrder.effectiveParentNodeBox}）。 */
    static PaintView effectiveParentNodeBox(PaintView box, String parentId) {
        if (parentId == null || box == null) return null;
        for (PaintView a = box.paintParent(); a != null; a = a.paintParent()) {
            if (a.node() != null && "root".equals(a.node().tag)) return null;
            if (a.anonymous()) continue;
            return boxHasOwnNode(a) ? a : null;
        }
        return null;
    }

    // ---- 子の文脈 ----

    /** 箱 {@code box} の中身の文脈を決め、クリップの表とスクロールの節の登録を行う（子を描く前に呼ぶ）。 */
    ChildContext enterChildren(PaintView box, ClipRect clip, String parentId) {
        String tag = box.node().tag;
        ClipRect childClip = computeChildClipRect(box, clip);
        contentClips.put(box, childClip);
        String childParent = "root".equals(tag) ? null : box.anonymous() ? parentId : box.node().key();
        List<com.nishiyu.enderframelib.core.paint.model.StickyShift> stickies;
        if ((stickies = scrollsAsOneNode(box)) != null) {
            ScrollContent content = new ScrollContent(box, box.node().scrollContentKey(),
                    scrollState.getScrollX(box.node()), scrollState.getScrollY(box.node()));
            content.stickies = stickies;
            scrollContentOf.put(box, content);
            childParent = content.contentId;
        }
        return new ChildContext(childClip, childParent);
    }

    /** 子に掛かるクリップ: 絶対配置の箱は包含ブロックが中身に掛けるクリップ、固定配置（ビューポート）は切られない。 */
    ClipRect clipForChild(PaintView child, ClipRect flowClip) {
        if (!child.isAbsolute()) return flowClip;
        PaintView cb = containingBlock(child);
        if (cb == null) return null;
        return contentClips.containsKey(cb) ? contentClips.get(cb) : flowClip;
    }

    /**
     * スクロールするだけで中身が正しく動くコンテナなら中の sticky の箱の式（無ければ空）、そうでなければ null（旧
     * {@code PaintOrder.scrollsAsOneNode}）。中身に固定配置・包含ブロックがコンテナの外の絶対配置が無く、sticky は上の inset だけで
     * いちばん近いスクロールコンテナがこのコンテナで、sticky の中の sticky でないこと。貼り付きのずれは見え方（{@code PaintView}）が
     * 付けるので、式がその今のずれと合う箱だけを許す。
     */
    private List<com.nishiyu.enderframelib.core.paint.model.StickyShift> scrollsAsOneNode(PaintView box) {
        if (box.anonymous() || box.w() <= 0 || box.h() <= 0 || !box.style().facts().scrollContainer) return null;
        String tag = box.node().tag;
        if ("root".equals(tag) || "html".equals(tag) || "body".equals(tag)) return null;
        if (followedScrollers.contains(box.node())) return null; // 外の箱が中のアンカーに追従する（G8。中身だけ動かしても外の箱が動かない）
        List<PaintView> stickies = new ArrayList<>(0);
        if (hasUnscrollableDescendant(box, box, false, stickies)) return null;
        if (stickies.isEmpty()) return List.of();
        List<com.nishiyu.enderframelib.core.paint.model.StickyShift> shifts = new ArrayList<>(stickies.size());
        for (PaintView s : stickies) {
            float[] p = boxHasOwnNode(s) ? stickyScrollParams(s, box) : null;
            if (p == null) return null;
            var shift = new com.nishiyu.enderframelib.core.paint.model.StickyShift(s.node().key(), p[0], p[1], p[2], p[3], p[4], p[5] != 0f, s.stickyDy());
            if (Float.floatToIntBits(shift.dyAt(0f)) != Float.floatToIntBits(s.stickyDy())) return null; // 式が今の貼り付きと合わない
            shifts.add(shift);
        }
        return shifts;
    }

    /** sticky の箱のスクロールの式（旧 {@code ScrollExtentCalc.stickyScrollParams}。使えなければ null）。 */
    private float[] stickyScrollParams(PaintView box, PaintView port) {
        PaintView parent = box.paintParent();
        if (!isSticky(box) || parent == null) return null;
        PaintView near = parent;
        while (near != null && !near.style().facts().scrollContainer) near = near.paintParent();
        if (near != port) return null;
        Style t = box.axisStyle().typed();
        if (com.nishiyu.enderframelib.core.css.CssLength.isAutoOrUndeclared(t.box.top) || !com.nishiyu.enderframelib.core.css.CssLength.isAutoOrUndeclared(t.box.bottom)
                || !com.nishiyu.enderframelib.core.css.CssLength.isAutoOrUndeclared(t.box.left)
                || !com.nishiyu.enderframelib.core.css.CssLength.isAutoOrUndeclared(t.box.right)) return null;
        float portY = port.y() + port.bT();
        float portH = Math.max(0, port.h() - port.bT() - port.bB());
        float limit = portY + t.boxPx(t.box.top, portH, rootW, rootH);
        float cbBottom = parent.y() + parent.h() - parent.bB() - parent.pB();
        return new float[]{limit, cbBottom, box.y() - box.stickyDy(), box.h(), box.mB(), parent != port ? 1f : 0f};
    }

    private boolean hasUnscrollableDescendant(PaintView container, PaintView box, boolean underSticky, List<PaintView> stickies) {
        for (PaintView child : box.paintChildren()) {
            if (child.displayNone()) continue;
            if (isFixed(child) || child.style().isContentVisibilityAuto()) return true; // 描くかどうかがスクロールの位置で変わる
            if (isSticky(child)) {
                if (underSticky || stickyScrollParams(child, container) == null) return true;
                stickies.add(child);
                if (hasUnscrollableDescendant(container, child, true, stickies)) return true;
                continue;
            }
            if (child.isAbsolute()) {
                PaintView cb = containingBlock(child);
                if (cb == null) return true;
                boolean inside = false;
                for (PaintView a = cb; a != null; a = a.paintParent()) {
                    if (a == container) {
                        inside = true;
                        break;
                    }
                }
                if (!inside) return true;
            }
            if (hasUnscrollableDescendant(container, child, underSticky, stickies)) return true;
        }
        return false;
    }

    /** 中身に掛けるクリップ（パディングボックスの角丸矩形と祖先のクリップの交わり。旧 {@code PaintOrder.computeChildClipRect}）。 */
    ClipRect computeChildClipRect(PaintView box, ClipRect parentClip) {
        String tag = box.node().tag;
        if ("root".equals(tag) || "html".equals(tag) || "body".equals(tag) || "#text".equals(tag)) return parentClip;
        boolean panZoom = !box.node().attrs.isEmpty() && "true".equalsIgnoreCase(box.node().attrs.get("data-pan-zoom"));
        PaintFacts facts = PaintFacts.of(box.axisStyle());
        boolean clipX = panZoom || !"visible".equals(facts.overflowX());
        boolean clipY = panZoom || !"visible".equals(facts.overflowY());
        if (!clipX && !clipY) return parentClip;
        float minX = clipX ? box.x() + box.bL() : -UNCLIPPED;
        float minY = clipY ? box.y() + box.bT() : -UNCLIPPED;
        float maxX = clipX ? box.x() + box.w() - box.bR() : UNCLIPPED;
        float maxY = clipY ? box.y() + box.h() - box.bB() : UNCLIPPED;
        float rx0 = 0f, rx1 = 0f, rx2 = 0f, rx3 = 0f, ry0 = 0f, ry1 = 0f, ry2 = 0f, ry3 = 0f;
        boolean elliptical = false;
        if (clipX && clipY) {
            float[] r = LengthResolver.cornerRadiiXYShared(box.axisStyle(), box.w(), box.h(), rootW, rootH);
            for (int i = 0; i < 4; i++) elliptical |= Math.round(r[i]) != Math.round(r[4 + i]);
            if (!elliptical) {
                rx0 = Math.min(r[0], r[4]) - Math.max(box.bT(), box.bL());
                rx1 = Math.min(r[1], r[5]) - Math.max(box.bT(), box.bR());
                rx2 = Math.min(r[2], r[6]) - Math.max(box.bB(), box.bR());
                rx3 = Math.min(r[3], r[7]) - Math.max(box.bB(), box.bL());
            } else {
                rx0 = Math.max(0f, r[0] - box.bL()); ry0 = Math.max(0f, r[4] - box.bT());
                rx1 = Math.max(0f, r[1] - box.bR()); ry1 = Math.max(0f, r[5] - box.bT());
                rx2 = Math.max(0f, r[2] - box.bR()); ry2 = Math.max(0f, r[6] - box.bB());
                rx3 = Math.max(0f, r[3] - box.bL()); ry3 = Math.max(0f, r[7] - box.bB());
            }
        }
        int ox = boxHasOwnNode(box) && clipX ? Math.round(box.x()) : 0;
        int oy = boxHasOwnNode(box) && clipY ? Math.round(box.y()) : 0;
        float[] chainOwn = elliptical
                ? new float[]{minX - ox, minY - oy, maxX - ox, maxY - oy, rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3}
                : new float[]{minX - ox, minY - oy, maxX - ox, maxY - oy, rx0, rx1, rx2, rx3};
        return BoxDecorations.intersectClipRect(parentClip, minX, minY, maxX, maxY, rx0, rx1, rx2, rx3, ry0, ry1, ry2, ry3,
                elliptical, null, ClipRect.appendChain(parentClip, chainOwn, box.node().key(), ox, oy));
    }

    // ---- レイヤー ----

    /** スタッキングコンテキストの箱をレイヤーに描いて合成する指定（旧 {@code PaintOrder.layerSpecFor}。要らなければ null）。 */
    LayerSpec layerSpecFor(PaintView box) {
        Style st = box.style().typed();
        VisualStyle v = st.visual;
        float opacity = Style.num(v.opacity, 1f);
        boolean opacityMoves = animatesOpacity(box);
        float fontPx = st.inherited.fontSize, rootFontPx = st.inherited.rootFontSizePx;
        int fgColor = st.inheritedPaint.color;
        List<FilterOp> filters = FilterOp.parse(v.filter, new FilterOp.Context(rootW, rootH, fontPx, rootFontPx, fgColor));
        BlendMode blend = BlendMode.fromCss(v.mixBlendMode == null ? "normal" : v.mixBlendMode.trim().toLowerCase(java.util.Locale.ROOT));
        boolean isolate = hasBlendDescendant(box);
        boolean rel = boxHasOwnNode(box);
        float lx = rel ? box.x() - Math.round(box.x()) : box.x();
        float ly = rel ? box.y() - Math.round(box.y()) : box.y();
        float[] border = {lx, ly, box.w(), box.h()};
        float[] padding = {lx + box.bL(), ly + box.bT(), Math.max(0, box.w() - box.bL() - box.bR()), Math.max(0, box.h() - box.bT() - box.bB())};
        float[] content = {padding[0] + box.pL(), padding[1] + box.pT(), Math.max(0, padding[2] - box.pL() - box.pR()), Math.max(0, padding[3] - box.pT() - box.pB())};
        ClipShape clip = ClipShape.parse(v.clipPath, ClipShape.Context.of(border, padding, content, rootW, rootH, fontPx, rootFontPx));
        boolean masked = PaintFacts.of(box.style()).hasMask();
        boolean luminance = masked && !BoxDecorations.maskModes(box.style()).isEmpty()
                && "luminance".equals(BoxDecorations.maskModes(box.style()).getFirst());
        List<FilterOp> backdrop = FilterOp.parse(v.backdropFilter, new FilterOp.Context(rootW, rootH, fontPx, rootFontPx, fgColor));
        ClipShape backdropShape = null;
        if (!backdrop.isEmpty()) {
            float[] r = LengthResolver.cornerRadiiXYShared(box.axisStyle(), box.w(), box.h(), rootW, rootH);
            float[] d = new float[]{lx, ly, lx + box.w(), ly + box.h(), 0, 0, 0, 0, 0, 0, 0, 0};
            System.arraycopy(r, 0, d, 4, 8);
            backdropShape = new ClipShape(ClipShape.Kind.INSET, d, false);
        }
        if (opacity >= 1f && !opacityMoves && filters.isEmpty() && blend == BlendMode.NORMAL && !isolate
                && clip == null && !masked && backdrop.isEmpty()) return null;
        return new LayerSpec(0, opacity, filters, blend, isolate, clip, masked ? LayerSpec.WANTS_MASK : -1, luminance, backdrop, backdropShape);
    }

    /** 子孫に {@code mix-blend-mode} を持つ箱があるか（スタッキングコンテキストを作る子孫の中は数えない）。 */
    private boolean hasBlendDescendant(PaintView box) {
        Boolean cached = blendDescendants.get(box);
        if (cached != null) return cached;
        boolean found = false;
        for (PaintView child : box.paintChildren()) {
            if (child.displayNone()) continue;
            String mbm = child.style().typed().visual.mixBlendMode;
            if (!"normal".equals(mbm == null ? "normal" : mbm.trim().toLowerCase(java.util.Locale.ROOT))) {
                found = true;
                break;
            }
            if (!createsStackingContext(child) && hasBlendDescendant(child)) {
                found = true;
                break;
            }
        }
        blendDescendants.put(box, found);
        return found;
    }

    // ---- スクロールバー ----

    static boolean wantsScrollbars(PaintView box) {
        return !"root".equals(box.node().tag) && box.w() > 0 && box.h() > 0 && PaintFacts.of(box.style()).scrollbars() && !isInvisible(box);
    }

    /** 子の項目をまとめて動かすための、見えない節（旧 {@code PaintOrder.addMovingNode}）。 */
    void addMovingNode(List<DisplayItem> elements, String id, PaintView box, RenderMeta meta) {
        ItemProps events = new ItemProps();
        int nodeHandle = box.node() != null ? box.node().paintHandle() : 0;
        long nodeKey = nodeHandle > 0 ? DisplayItem.nodeKeyOf(nodeHandle) : 0L;
        events.parentId(box.node() != null ? box.node().key() : id, nodeKey);
        events.pointerEventsNone();
        boolean isV = id.endsWith("_scrollv");
        boolean isH = !isV && id.endsWith("_scrollh");
        long movingKey = nodeHandle > 0 ? (isV ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_V)
                : isH ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_H)
                : id.endsWith(PaintSceneDriver.SCROLL_CONTENT_SUFFIX) ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLL_CONTENT)
                : DisplayItem.nodeKeyOf(nodeHandle)) : 0L;
        RenderMeta baseMeta = (meta == null ? RenderMeta.EMPTY : MOVING_NODE_META).withHints(events.hints());
        addElement(elements, id, id, ElementType.NODE, box.x(), box.y(), box.w(), box.h(), "", 0, 0, events, 0, 1f, 0, 0, 1f, 1f, 0, 0,
                AnimationSpec.NONE, baseMeta, movingKey);
    }

    /** スクロールバー（旧 {@code PaintOrder.drawScrollbarsIfNeeded}）。 */
    void drawScrollbars(List<DisplayItem> elements, PaintView box, ClipRect clipRect) {
        if (box.style().typed().box.scrollbarWidth == com.nishiyu.enderframelib.core.css.model.CssKeywords.ScrollbarWidth.NONE) return;
        PaintSupport.CaptureList cap = (boxHasOwnNode(box) && elements instanceof PaintSupport.CaptureList c) ? c : null;
        int prevOx = cap != null ? cap.originX : 0, prevOy = cap != null ? cap.originY : 0;
        if (cap != null) cap.setOrigin(Math.round(box.x()), Math.round(box.y()));
        try {
            float scrollW = box.scrollWidth(), scrollH = box.scrollHeight();
            float curX = scrollState.getScrollX(box.node()), curY = scrollState.getScrollY(box.node());
            int nodeHandle = box.node() != null ? box.node().paintHandle() : 0;
            long nodeKey = nodeHandle > 0 ? DisplayItem.nodeKeyOf(nodeHandle) : 0L;
            String ownerId = box.node().key();
            ItemProps sbEvents = new ItemProps();
            sbEvents.parentId(ownerId, nodeKey);
            ScrollContent content = scrollContentOf.get(box);
            RenderMeta sbMeta = new RenderMeta(clipRect, SCROLLBAR_CORNERS, null, 12.0f, 0f, 0f, 0.5f, 0.5f, 1, false, null, sbEvents.hints());
            int bar = 4;
            if (scrollH > box.h() + 2 && box.h() > 12) {
                float trackX = box.x() + box.w() - box.bR() - bar - 2, trackY = box.y() + box.bT() + 2;
                float trackH = box.h() - box.bT() - box.bB() - 4;
                float thumbH = Math.round(Math.max(12, trackH * (box.h() / scrollH)));
                float maxScrollY = Math.max(1f, scrollH - box.h());
                float thumbY = trackY + (trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f);
                long trackKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_TRACK_V) : 0L;
                long thumbKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_V) : 0L;
                long nodeVKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_V) : 0L;
                addElement(elements, ownerId, box.node().partKey(12, "_sb_vt"), ElementType.RECT, trackX, trackY, bar, trackH, "", 0, 0x33000000,
                        sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta, trackKey);
                ItemProps thumbEvents = sbEvents;
                RenderMeta thumbMeta = sbMeta;
                if (content != null) { // つまみはスクロール量に比例して動く節の下に置く
                    content.thumbVId = box.node().partKey(13, "_scrollv");
                    content.ky = (trackH - thumbH) / maxScrollY;
                    content.fy0 = thumbY - Math.round(thumbY);
                    addMovingNode(elements, content.thumbVId, box, sbMeta);
                    thumbEvents = new ItemProps();
                    thumbEvents.parentId(content.thumbVId, nodeVKey);
                    thumbMeta = new RenderMeta(clipRect, SCROLLBAR_CORNERS, null, 12.0f, 0f, 0f, 0.5f, 0.5f, 1, false, null, thumbEvents.hints());
                }
                addElement(elements, ownerId, box.node().partKey(14, "_sb_v"), ElementType.RECT, trackX, thumbY, bar, thumbH, "", 0, 0x88CCCCCC,
                        thumbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, thumbMeta, thumbKey);
            }
            if (scrollW > box.w() + 2 && box.w() > 12) {
                float trackX = box.x() + box.bL() + 2, trackY = box.y() + box.h() - box.bB() - bar - 2;
                float trackW = box.w() - box.bL() - box.bR() - 4;
                float thumbW = Math.round(Math.max(12, trackW * (box.w() / scrollW)));
                float maxScrollX = Math.max(1f, scrollW - box.w());
                float thumbX = trackX + (trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f);
                long trackKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_TRACK_H) : 0L;
                long thumbKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_H) : 0L;
                long nodeHKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, DisplayItem.PART_SCROLLBAR_NODE_H) : 0L;
                addElement(elements, ownerId, box.node().partKey(15, "_sb_ht"), ElementType.RECT, trackX, trackY, trackW, bar, "", 0, 0x33000000,
                        sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta, trackKey);
                ItemProps thumbEvents = sbEvents;
                RenderMeta thumbMeta = sbMeta;
                if (content != null) {
                    content.thumbHId = box.node().partKey(16, "_scrollh");
                    content.kx = (trackW - thumbW) / maxScrollX;
                    content.fx0 = thumbX - Math.round(thumbX);
                    addMovingNode(elements, content.thumbHId, box, sbMeta);
                    thumbEvents = new ItemProps();
                    thumbEvents.parentId(content.thumbHId, nodeHKey);
                    thumbMeta = new RenderMeta(clipRect, SCROLLBAR_CORNERS, null, 12.0f, 0f, 0f, 0.5f, 0.5f, 1, false, null, thumbEvents.hints());
                }
                addElement(elements, ownerId, box.node().partKey(17, "_sb_h"), ElementType.RECT, thumbX, trackY, thumbW, bar, "", 0, 0x88CCCCCC,
                        thumbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, thumbMeta, thumbKey);
            }
        } finally {
            if (cap != null) cap.setOrigin(prevOx, prevOy);
        }
    }

    // ---- キャンバスの背景 ----

    /** 背景をキャンバスに譲る要素（html か body。旧 {@code PaintOrder.canvasBackgroundSource}）。 */
    static PaintView canvasBackgroundSource(PaintView root) {
        PaintView html = childWithTag(root, "html");
        if (html != null && hasBackground(html)) return html;
        PaintView body = childWithTag(html != null ? html : root, "body");
        return body != null && hasBackground(body) ? body : null;
    }

    private static PaintView childWithTag(PaintView parent, String tag) {
        for (PaintView child : parent.paintChildren()) {
            if (tag.equals(child.node().tag)) return child;
        }
        return null;
    }

    private static boolean hasBackground(PaintView box) {
        VisualStyle v = box.style().typed().visual;
        return v.hasBackgroundColor || (v.backgroundImage != null && !"none".equals(v.backgroundImage));
    }
}
