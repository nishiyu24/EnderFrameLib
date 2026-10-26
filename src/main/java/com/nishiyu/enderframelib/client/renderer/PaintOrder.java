package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import java.util.function.Predicate;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.addElement;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.isAtomicInline;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.isFlexOrGridItem;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.isInvisible;
import static com.nishiyu.enderframelib.client.renderer.PaintSupport.normalizeClipRect;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 描画の順序（CSS 2.1 付録 E のスタッキングコンテキスト）を決め、箱ごとの描画を呼ぶ。スクロールバーと、
 * 箱が中身に掛けるクリップもここで持つ。
 */
final class PaintOrder {
    /** 箱 1 つ自身（背景・枠・部品）を描画項目にする。 */
    @FunctionalInterface
    interface BoxPainter {
        void draw(List<DisplayItem> elements, LayoutBox box, float[] clipRect, String parentId);
    }

    private final int rootW;
    private final int rootH;
    private final ScrollState scrollState;
    private final AtomicInteger fragIds;
    private final BoxPainter painter;
    /**
     * 箱が中身に掛けるクリップ（祖先のクリップと自分の overflow の交わり。null なら切らない）。
     * 絶対配置の箱は木の親ではなく包含ブロックの値を引く。
     */
    private final Map<LayoutBox, float[]> contentClips = new IdentityHashMap<>();
    /** その箱の {@code animation} が {@code opacity} を動かすか（静的な値は 1 でも、動く間はグループとして合成する）。 */
    private final Predicate<LayoutBox> animatesOpacity;
    /** 子孫に {@code mix-blend-mode} を持つ箱がある（スタッキングコンテキストを越えない範囲で）か。 */
    private final Map<LayoutBox, Boolean> blendDescendants = new IdentityHashMap<>();

    PaintOrder(int rootW, int rootH, ScrollState scrollState, AtomicInteger fragIds, BoxPainter painter,
               Predicate<LayoutBox> animatesOpacity) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.scrollState = scrollState;
        this.fragIds = fragIds;
        this.painter = painter;
        this.animatesOpacity = animatesOpacity;
    }

    /**
     * 箱の描画順（先に描くものが先頭）。{@link #render} と同じ並べ替えを通す。
     *
     * 忠実度コーパスが z-order をブラウザと突き合わせるための読み口
     * （{@code FidelityDumper} の paint 列）。描画順の決め方を変えるときは
     * ここも同じ経路を通るので、テストが描画と食い違うことはない。
     */
    public List<LayoutBox> paintOrder(LayoutBox root) {
        List<LayoutBox> order = new ArrayList<>();
        for (RenderTask task : paintTasks(root)) {
            if (task.box() != null) order.add(task.box());
        }
        return order;
    }

    static LayoutBox canvasBackgroundSource(LayoutBox root) {
        LayoutBox html = childWithTag(root, "html");
        if (html != null && hasBackground(html)) return html;
        LayoutBox body = childWithTag(html != null ? html : root, "body");
        return body != null && hasBackground(body) ? body : null;
    }

    static LayoutBox childWithTag(LayoutBox parent, String tag) {
        for (LayoutBox child : parent.children) {
            if (tag.equals(child.node.tag)) return child;
        }
        return null;
    }

    static boolean hasBackground(LayoutBox box) {
        return box.style.containsKey(CssProperty.BACKGROUND_COLOR) || box.style.containsKey(CssProperty.BACKGROUND);
    }

    // ==========================================
    // 描画順（CSS 2.2 付録 E）
    // ==========================================

    /**
     * 描画順に並べたタスク。ボックスツリーからスタッキングコンテキストの木を組み、付録 E の層の順に平らにする。
     *
     * 何が壊れていたか: 以前は z-index を親に足し込んだ値と「通常フロー / float / 位置指定」の 3 層で
     * 全体を 1 回並べ替えていた。スタッキングコンテキストが無いので中の z-index が外へ漏れ
     * （z-index: 1 の箱の中の z-index: 100 が外の z-index: 2 を越えた）、opacity と transform は
     * コンテキストを作らず、位置指定されていないブロックの z-index も効き、
     * コンテキストの中の負の z-index は親の背景より下に潜っていた。
     */
    List<RenderTask> paintTasks(LayoutBox root) {
        fragIds.set(0);
        contentClips.clear();
        blendDescendants.clear();
        PaintNode top;
        if ("root".equals(root.node.tag)) {
            top = new PaintNode(null, true, 0);
            collectChildren(root, null, null, top.layers, top.layers);
        } else {
            top = new PaintNode(selfTask(root, null, null), true, 0);
            collectChildren(root, null, null, top.layers, top.layers);
        }
        List<RenderTask> out = new ArrayList<>();
        emit(top, out);
        return out;
    }

    /**
     * 子を描画の層に振り分ける。
     *
     * @param sc    いちばん近い本物のスタッキングコンテキストの層（負・0・正の z-index と位置指定要素が入る）
     * @param local 今いる層（float・位置指定要素・不可分なインラインは自分の中身だけを持つ疑似コンテキストを作る）
     */
    void collectChildren(LayoutBox box, float[] clip, String parentId, PaintLayers sc, PaintLayers local) {
        String tag = box.node.tag;
        if ("select".equalsIgnoreCase(tag) || "textarea".equalsIgnoreCase(tag) || "input".equalsIgnoreCase(tag)) return;

        float[] childClip = computeChildClipRect(box, clip);
        contentClips.put(box, childClip);
        // 匿名ボックスは描くものを持たない。子の持ち主は匿名ボックスの外の要素のまま
        String childParent = "root".equals(tag) ? null : box.isAnonymous ? parentId : box.node.getId();
        for (LayoutBox child : box.children) classify(child, clipForChild(child, childClip), childParent, sc, local);

        String[] overflow = overflowPair(box);
        boolean scrollbars = isScrollbarOverflow(overflow[0]) || isScrollbarOverflow(overflow[1]);
        if (!"root".equals(tag) && box.w > 0 && box.h > 0 && scrollbars && !isInvisible(box)) {
            local.after.add(new RenderTask(null, elements -> drawScrollbarsIfNeeded(elements, box, clip)));
        }
    }

    /**
     * 子に掛かるクリップ。overflow が切るのは自分が包含ブロックの祖先になっている子孫だけ（CSS 2.2 §11.1.1）なので、
     * 絶対配置の箱は木の親ではなく包含ブロックが中身に掛けるクリップを使う。固定配置（包含ブロックがビューポート）は切られない。
     *
     * 何が壊れていたか: 以前は親のクリップを木のすべての子へそのまま渡していたので、位置指定の無い
     * {@code overflow: hidden} の箱の中のドロップダウンやモーダル（absolute / fixed）が箱の枠で切り落とされていた。
     */
    float[] clipForChild(LayoutBox child, float[] flowClip) {
        if (!child.isAbsolute) return flowClip;
        LayoutBox cb = child.findContainingBlock();
        if (cb == null) return null;
        return contentClips.containsKey(cb) ? contentClips.get(cb) : flowClip;
    }

    /**
     * 付録 E の分類。スタッキングコンテキストは z-index の符号で親のコンテキストの層 2 / 8 / 9 に入り、
     * 中身を自分の層に閉じ込める。z-index: auto の位置指定要素（層 8）・float（層 5）・
     * 不可分なインラインと flex / grid アイテム（層 7）は「コンテキストを作ったかのように」中身ごと描くが、
     * 中の位置指定要素と本物のコンテキストは親のコンテキストに属する。
     */
    void classify(LayoutBox box, float[] clip, String parentId, PaintLayers sc, PaintLayers local) {
        if (box.display.isNone() || "none".equals(box.style.getOrDefault(CssProperty.DISPLAY, "block"))) return;
        if ("option".equalsIgnoreCase(box.node.tag)) return;

        RenderTask self = selfTask(box, clip, parentId);
        if (createsStackingContext(box)) {
            int z = isZIndexAuto(box) ? 0 : box.zIndex;
            PaintNode node = PaintNode.context(self, z, self != null ? layerSpecFor(box) : null, box);
            (z < 0 ? sc.negative : z > 0 ? sc.positive : sc.positioned).add(node);
            collectChildren(box, clip, parentId, node.layers, node.layers);
        } else if (isPositioned(box)) {
            // 先に入れてから中身を集める（中の位置指定要素は木の順で後ろに並ぶ）
            PaintNode node = new PaintNode(self, false, 0);
            sc.positioned.add(node);
            collectChildren(box, clip, parentId, sc, node.layers);
        } else if (BlockLayoutEngine.isFloating(box)) {
            PaintNode node = new PaintNode(self, false, 0);
            local.floats.add(node);
            collectChildren(box, clip, parentId, sc, node.layers);
        } else if (isAtomicInline(box)) {
            PaintNode node = new PaintNode(self, false, 0);
            local.inlines.add(node);
            collectChildren(box, clip, parentId, sc, node.layers);
        } else {
            PaintNode leaf = new PaintNode(self, false, 0, null);
            (box.display.isInlineLevel() ? local.inlines : local.blocks).add(leaf);
            collectChildren(box, clip, parentId, sc, local);
        }
    }

    /** 付録 E の層の順に平らにする。z-index の並べ替えは安定（同じ値なら木の順）。 */
    static void emit(PaintNode node, List<RenderTask> out) {
        // レイヤーに描く箱は、錨から子孫の最後までを開始と終了の印で挟む（スタッキングコンテキストの木は連続している）
        if (node.layerSpec != null) out.add(RenderTask.layerStart(node.layerSpec, node.layerBox));
        if (node.self != null) out.add(node.self);
        PaintLayers l = node.layers;
        if (l == null) {
            if (node.layerSpec != null) out.add(RenderTask.LAYER_END);
            return;
        }
        if (node.stackingContext) {
            l.negative.sort(Comparator.comparingInt(PaintNode::z));
            for (PaintNode n : l.negative) emit(n, out);
        }
        for (PaintNode n : l.blocks) emit(n, out);
        for (PaintNode n : l.floats) emit(n, out);
        for (PaintNode n : l.inlines) emit(n, out);
        if (node.stackingContext) {
            for (PaintNode n : l.positioned) emit(n, out);
            l.positive.sort(Comparator.comparingInt(PaintNode::z));
            for (PaintNode n : l.positive) emit(n, out);
        }
        out.addAll(l.after);
        if (node.layerSpec != null) out.add(RenderTask.LAYER_END);
    }

    RenderTask selfTask(LayoutBox box, float[] clip, String parentId) {
        if (box.w <= 0 || box.h <= 0 || box.isAnonymous) return null;
        return new RenderTask(box, elements -> painter.draw(elements, box, clip, parentId));
    }

    static boolean isPositioned(LayoutBox box) {
        return box.isRelative || box.isAbsolute || box.isSticky;
    }

    static boolean isZIndexAuto(LayoutBox box) {
        String z = box.style.get(CssProperty.Z_INDEX);
        return z == null || z.isBlank() || "auto".equalsIgnoreCase(z.trim());
    }

    /**
     * スタッキングコンテキストを作るか（CSS 2.2 §9.9.1 と CSS Positioned Layout 3 / Color 4 /
     * Compositing 1 / Transforms 1 の追加分）。
     */
    boolean createsStackingContext(LayoutBox box) {
        boolean zAuto = isZIndexAuto(box);
        if (isPositioned(box) && !zAuto) return true;
        if (box.isFixed || box.isSticky) return true;
        if (!zAuto && isFlexOrGridItem(box)) return true;
        if (box.style.number(CssProperty.OPACITY, 1f) < 1f) return true;
        // opacity を動かすアニメーションは、静的な値が 1 でも動いている間スタッキングコンテキストを作る（CSS Positioned Layout 3 §2）
        if (animatesOpacity.test(box)) return true;
        if (!isNone(box.style.get(CssProperty.TRANSFORM))) return true;
        if (!isNone(box.style.get("filter"))) return true;
        // clip-path・mask・backdrop-filter もスタッキングコンテキストを作る（CSS Masking 1 §3 / §6、Filter Effects 2 §2）
        if (!isNone(box.style.get("clip-path")) || !isNone(box.style.get("backdrop-filter")) || hasMask(box)) return true;
        if (box.style.keywordOf("isolation", CssKeywords.Isolation.class) == CssKeywords.Isolation.ISOLATE) return true;
        return !"normal".equals(box.style.keyword("mix-blend-mode", "normal"));
    }

    /**
     * スタッキングコンテキストの箱をレイヤーに描いて合成する指定。グループの不透明度・{@code filter}・{@code mix-blend-mode}、
     * 子孫の混合を閉じ込める必要があるとき（{@code isolation: isolate} か、{@code mix-blend-mode} の子孫を含む）に作る。
     * どれも無ければ null（直に描く）。{@code span} は {@link DisplayListBuilder} が項目を出し終えてから埋める。
     */
    LayerSpec layerSpecFor(LayoutBox box) {
        float opacity = box.style.number(CssProperty.OPACITY, 1f);
        boolean opacityMoves = animatesOpacity.test(box);
        List<FilterOp> filters = FilterOp.parse(box.style.get("filter"), new FilterOp.Context(rootW, rootH,
                box.style.fontSizePx(), box.style.rootFontSizePx(), box.style.color(CssProperty.COLOR, 0xFF000000)));
        BlendMode blend = BlendMode.fromCss(box.style.keyword("mix-blend-mode", "normal"));
        boolean isolate = hasBlendDescendant(box);

        float fontPx = box.style.fontSizePx(), rootFontPx = box.style.rootFontSizePx();
        float[] border = {box.x, box.y, box.w, box.h};
        float[] padding = {box.x + box.bL, box.y + box.bT, Math.max(0, box.w - box.bL - box.bR), Math.max(0, box.h - box.bT - box.bB)};
        float[] content = {padding[0] + box.pL, padding[1] + box.pT, Math.max(0, padding[2] - box.pL - box.pR), Math.max(0, padding[3] - box.pT - box.pB)};
        ClipShape clip = ClipShape.parse(box.style.get("clip-path"),
                ClipShape.Context.of(border, padding, content, rootW, rootH, fontPx, rootFontPx));

        boolean masked = hasMask(box);
        boolean luminance = masked && !BoxDecorations.maskModes(box.style).isEmpty()
                && "luminance".equals(BoxDecorations.maskModes(box.style).getFirst());

        List<FilterOp> backdrop = FilterOp.parse(box.style.get("backdrop-filter"), new FilterOp.Context(rootW, rootH, fontPx, rootFontPx,
                box.style.color(CssProperty.COLOR, 0xFF000000)));
        ClipShape backdropShape = null;
        if (!backdrop.isEmpty()) {
            float[] r = LengthResolver.cornerRadiiXY(box.style, box.w, box.h, rootW, rootH);
            float[] d = new float[12];
            d[0] = box.x;
            d[1] = box.y;
            d[2] = box.x + box.w;
            d[3] = box.y + box.h;
            System.arraycopy(r, 0, d, 4, 8);
            backdropShape = new ClipShape(ClipShape.Kind.INSET, d, false);
        }

        if (opacity >= 1f && !opacityMoves && filters.isEmpty() && blend == BlendMode.NORMAL && !isolate
                && clip == null && !masked && backdrop.isEmpty()) {
            return null;
        }
        return new LayerSpec(0, opacity, filters, blend, isolate, clip, masked ? LayerSpec.WANTS_MASK : -1, luminance, backdrop, backdropShape);
    }

    /** {@code mask-image} に画像かグラデーションのレイヤーがあるか。 */
    static boolean hasMask(LayoutBox box) {
        for (BoxDecorations.BackgroundLayer layer : BoxDecorations.maskLayers(box.style)) {
            if (layer.url() != null || layer.isGradient()) return true;
        }
        return false;
    }

    /**
     * 子孫に {@code mix-blend-mode} を持つ箱があるか。混合は自分を含む最も近いスタッキングコンテキストの中で起こるので、
     * 途中にスタッキングコンテキストを作る子孫があれば、その箱自身の混合までを数え、中は数えない（その箱が閉じ込める）。
     */
    private boolean hasBlendDescendant(LayoutBox box) {
        Boolean cached = blendDescendants.get(box);
        if (cached != null) return cached;
        boolean found = false;
        for (LayoutBox child : box.children) {
            if (child.display.isNone()) continue;
            if (!"normal".equals(child.style.keyword("mix-blend-mode", "normal"))) {
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

    static boolean isNone(String value) {
        return value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim());
    }

    /** スタッキングコンテキスト（または疑似コンテキスト）の中の層。番号は付録 E の手順。 */
    static final class PaintLayers {
        final List<PaintNode> negative = new ArrayList<>();   // 2: 負の z-index のコンテキスト
        final List<PaintNode> blocks = new ArrayList<>();     // 4: 通常フローのブロックレベル
        final List<PaintNode> floats = new ArrayList<>();     // 5: float
        final List<PaintNode> inlines = new ArrayList<>();    // 7: インラインレベル
        final List<PaintNode> positioned = new ArrayList<>(); // 8: z-index: auto / 0 の位置指定要素とコンテキスト
        final List<PaintNode> positive = new ArrayList<>();   // 9: 正の z-index のコンテキスト
        final List<RenderTask> after = new ArrayList<>();     // スクロールバー（中身より上）
    }

    /**
     * {@code layers} が null なら箱自身だけ（中身は今の層に流れている）。{@code layerSpec} があれば、この箱は
     * レイヤーに描いて合成する（スタッキングコンテキストだけが持つ）。
     */
    record PaintNode(RenderTask self, boolean stackingContext, int z, PaintLayers layers, LayerSpec layerSpec, LayoutBox layerBox) {
        PaintNode(RenderTask self, boolean stackingContext, int z, PaintLayers layers) {
            this(self, stackingContext, z, layers, null, null);
        }

        PaintNode(RenderTask self, boolean stackingContext, int z) {
            this(self, stackingContext, z, new PaintLayers(), null, null);
        }

        static PaintNode context(RenderTask self, int z, LayerSpec layerSpec, LayoutBox layerBox) {
            return new PaintNode(self, true, z, new PaintLayers(), layerSpec, layerBox);
        }
    }

    /** 切らない軸のクリップの端（描画とヒットテストの座標に十分遠い値）。 */
    static final float UNCLIPPED = 1.0e6f;

    /**
     * 中身に掛けるクリップ（パディングボックスの角丸矩形と祖先のクリップの交わり）。
     *
     * {@code overflow} はロングハンドの {@code overflow-x} / {@code overflow-y} から読む（ショートハンドは
     * 算出の段で展開済み）。片方の軸だけが切るときはもう片方は祖先のクリップのまま。
     *
     * 何が壊れていたか: 以前はショートハンドの {@code overflow} だけを読んでいたので、展開後は常に
     * visible 扱いになり、{@code overflow: hidden} も {@code overflow-y: auto} も子を切らなかった。
     */
    float[] computeChildClipRect(LayoutBox box, float[] parentClip) {
        String tag = box.node.tag;
        boolean isRootElement = "root".equals(tag) || "html".equals(tag) || "body".equals(tag);
        if (isRootElement) return parentClip;

        boolean panZoom = "true".equalsIgnoreCase(box.node.attrs.get("data-pan-zoom"));
        String[] overflow = overflowPair(box);
        boolean clipX = panZoom || !"visible".equals(overflow[0]);
        boolean clipY = panZoom || !"visible".equals(overflow[1]);
        if (!clipX && !clipY) return parentClip;

        float minX = clipX ? box.x + box.bL : -UNCLIPPED;
        float minY = clipY ? box.y + box.bT : -UNCLIPPED;
        float maxX = clipX ? box.x + box.w - box.bR : UNCLIPPED;
        float maxY = clipY ? box.y + box.h - box.bB : UNCLIPPED;

        // 内側の角は外側の半径から隣り合う辺の太さを引いたもの（角丸は両軸を切るときだけ）
        float[] own = {minX, minY, maxX, maxY, 0, 0, 0, 0};
        if (clipX && clipY) {
            float[] r = LengthResolver.cornerRadiiXY(box.style, box.w, box.h, rootW, rootH);
            boolean elliptical = false;
            for (int i = 0; i < 4; i++) elliptical |= Math.round(r[i]) != Math.round(r[4 + i]);
            if (!elliptical) {
                own[4] = Math.min(r[0], r[4]) - Math.max(box.bT, box.bL);
                own[5] = Math.min(r[1], r[5]) - Math.max(box.bT, box.bR);
                own[6] = Math.min(r[2], r[6]) - Math.max(box.bB, box.bR);
                own[7] = Math.min(r[3], r[7]) - Math.max(box.bB, box.bL);
            } else {
                // 楕円の角は、内側の角を軸ごとに縮める（横は左右の枠線、縦は上下の枠線の太さ）
                own = java.util.Arrays.copyOf(own, 12);
                float[] bx = {box.bL, box.bR, box.bR, box.bL}, by = {box.bT, box.bT, box.bB, box.bB};
                for (int i = 0; i < 4; i++) {
                    own[4 + i] = Math.max(0, r[i] - bx[i]);
                    own[8 + i] = Math.max(0, r[4 + i] - by[i]);
                }
            }
        }
        return BoxDecorations.intersectClip(parentClip, own);
    }

    /**
     * {@code overflow-x} / {@code overflow-y} の使用値。片方が visible / clip でもう片方がそうでなければ、
     * visible は auto、clip は hidden として扱う（CSS Overflow 3 §3.1）。
     */
    static String[] overflowPair(LayoutBox box) {
        String x = box.style.overflowX().cssName();
        String y = box.style.overflowY().cssName();
        boolean xPlain = "visible".equals(x) || "clip".equals(x);
        boolean yPlain = "visible".equals(y) || "clip".equals(y);
        if (xPlain != yPlain) {
            if (xPlain) x = "visible".equals(x) ? "auto" : "hidden";
            else y = "visible".equals(y) ? "auto" : "hidden";
        }
        return new String[]{x, y};
    }

    static boolean isScrollbarOverflow(String overflow) {
        return "scroll".equals(overflow) || "auto".equals(overflow);
    }

    void drawScrollbarsIfNeeded(List<DisplayItem> elements, LayoutBox box, float[] clipRect) {
        if (box.style.keywordOf(CssProperty.SCROLLBAR_WIDTH, CssKeywords.ScrollbarWidth.class) == CssKeywords.ScrollbarWidth.NONE) return;

        float scrollW = box.scrollWidth;
        float scrollH = box.scrollHeight;
        float curX = scrollState.getScrollX(box.node);
        float curY = scrollState.getScrollY(box.node);

        ItemProps sbEvents = new ItemProps();
        sbEvents.parentId(box.node.getId());
        ClipRect sbClip = normalizeClipRect(clipRect);
        RenderMeta sbMeta = new RenderMeta(sbClip, new Corners(2, 2, 2, 2), null, 12.0f, 0.5f, 0.5f, 1, false);

        int barThickness = 4;
        if (scrollH > box.h + 2 && box.h > 12) {
            float trackX = box.x + box.w - box.bR - barThickness - 2;
            float trackY = box.y + box.bT + 2;
            float trackH = box.h - box.bT - box.bB - 4;
            float thumbH = Math.max(12, trackH * (box.h / scrollH));
            float maxScrollY = Math.max(1f, scrollH - box.h);
            float thumbY = trackY + (trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f);

            addElement(elements, box.node.getId(), box.node.getId() + "_sb_vt", ElementType.RECT, trackX, trackY, barThickness, trackH, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
            addElement(elements, box.node.getId(), box.node.getId() + "_sb_v", ElementType.RECT, trackX, thumbY, barThickness, thumbH, "", 0, 0x88CCCCCC, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
        }
        if (scrollW > box.w + 2 && box.w > 12) {
            float trackX = box.x + box.bL + 2;
            float trackY = box.y + box.h - box.bB - barThickness - 2;
            float trackW = box.w - box.bL - box.bR - 4;
            float thumbW = Math.max(12, trackW * (box.w / scrollW));
            float maxScrollX = Math.max(1f, scrollW - box.w);
            float thumbX = trackX + (trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f);

            addElement(elements, box.node.getId(), box.node.getId() + "_sb_ht", ElementType.RECT, trackX, trackY, trackW, barThickness, "", 0, 0x33000000, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
            addElement(elements, box.node.getId(), box.node.getId() + "_sb_h", ElementType.RECT, thumbX, trackY, thumbW, barThickness, "", 0, 0x88CCCCCC, sbEvents, 2, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, sbMeta);
        }
    }

    /**
     * {@code box} は箱自身を描くタスクだけが持つ（スクロールバーは null）。{@code layerStart} はレイヤーの開始の印
     * （次に出る錨から {@link #LAYER_END} までがレイヤーの中身）、{@code layerEnd} は終了の印。印は項目を出さない。
     */
    record RenderTask(LayoutBox box, Consumer<List<DisplayItem>> drawAction, LayerSpec layerStart, boolean layerEnd, LayoutBox layerBox) {
        static final RenderTask LAYER_END = new RenderTask(null, elements -> {}, null, true, null);

        RenderTask(LayoutBox box, Consumer<List<DisplayItem>> drawAction) {
            this(box, drawAction, null, false, null);
        }

        /** {@code layerBox} は、レイヤーの終わりにマスクの項目を足すときの持ち主の箱。 */
        static RenderTask layerStart(LayerSpec spec, LayoutBox layerBox) {
            return new RenderTask(null, elements -> {}, spec, false, layerBox);
        }
    }
}
