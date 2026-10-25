package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.client.ClientWebManager;
import com.nishiyu.enderframelib.core.dom.parser.DomUtils;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.model.KeyframeBlock;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.paint.model.EasingFunction;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.paint.model.RenderKeys;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import com.nishiyu.enderframelib.core.css.model.BorderStyle;

public class DisplayListBuilder {

    // 単位なしの 0（例: translate(-50%, 0)）も受け付けるため単位は省略可能（省略時は px 扱い）
    private static final Pattern TRANSLATE_X_PATTERN = Pattern.compile("translateX\\(\\s*(-?[0-9.]+)(px|%)?\\s*\\)");
    private static final Pattern TRANSLATE_Y_PATTERN = Pattern.compile("translateY\\(\\s*(-?[0-9.]+)(px|%)?\\s*\\)");
    private static final Pattern TRANSLATE_PATTERN = Pattern.compile("translate\\(\\s*(-?[0-9.]+)(px|%)?(?:\\s*,\\s*(-?[0-9.]+)(px|%)?)?\\s*\\)");
    private static final Pattern SCALE_PATTERN = Pattern.compile("scale\\(\\s*(-?[0-9.]+)(?:\\s*,\\s*(-?[0-9.]+))?\\s*\\)");
    private static final Pattern ROTATE_PATTERN = Pattern.compile("rotate\\(\\s*(-?[0-9.]+)(deg|rad)?\\s*\\)");
    private static final Pattern TIME_UNIT_PATTERN = Pattern.compile("([0-9]*\\.?[0-9]+)(ms|s)");
    private static final Pattern CUBIC_BEZIER_SPAN_PATTERN = Pattern.compile("cubic-bezier\\([^)]*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    private static final Set<String> NON_FRAGMENT_TAGS = Set.of(
            "#text", "img", "mc-fluid", "mc-entity", "mc-scene",
            "input", "select", "progress", "meter", "textarea", "canvas"
    );

    private static final Set<String> EASING_KEYWORDS = Set.of(
            "ease", "linear", "ease-in", "ease-out", "ease-in-out"
    );

    private static final String[] PASSTHROUGH_DATA_ATTRS = {
            "data-sound", "data-sound-pitch", "draggable", "data-count",
            "data-ghost-slot", "data-pan-zoom", "for", "tabindex"
    };

    private static final String[] BORDER_DIRECTIONS = {"top", "right", "bottom", "left"};

    private final int rootW;
    private final int rootH;
    private final Map<String, Keyframes> keyframes;
    private final ScrollState scrollState;
    private int fragIdCounter = 0;
    /** 背景をキャンバスに譲った要素（html か body）。自分の箱には背景色を描かない。 */
    private LayoutBox canvasBackgroundSource;
    /**
     * 箱が中身に掛けるクリップ（祖先のクリップと自分の overflow の交わり。null なら切らない）。
     * 絶対配置の箱は木の親ではなく包含ブロックの値を引く。
     */
    private final Map<LayoutBox, float[]> contentClips = new IdentityHashMap<>();

    public DisplayListBuilder(int rootW, int rootH, Map<String, Keyframes> keyframes, ScrollState scrollState) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.keyframes = keyframes != null ? keyframes : Map.of();
        this.scrollState = scrollState;
    }

    public List<DisplayItem> render(LayoutBox box) {
        List<DisplayItem> elements = new ArrayList<>();
        canvasBackgroundSource = null;

        if ("root".equals(box.node.tag)) {
            // キャンバスの背景はルート要素の背景、それが透明なら body の背景（CSS Backgrounds 3 §2.11.2）。
            // 背景を譲った要素は自分の箱には背景色を描かない
            int canvasBg = DEFAULT_CANVAS_COLOR;
            LayoutBox source = canvasBackgroundSource(box);
            if (source != null) {
                canvasBg = ColorParser.parseColor(source.style.getOrDefault(CssProperty.BACKGROUND_COLOR, "transparent"));
                canvasBackgroundSource = source;
            }
            if (canvasBg != 0x00000000) {
                addElement(elements, "bg_root", "bg_root", ElementType.RECT, box.x, box.y, Math.max(box.w, rootW), Math.max(box.h, rootH),
                        "", 0, canvasBg, Map.of(), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, RenderMeta.EMPTY);
            }
        }

        for (RenderTask task : paintTasks(box)) {
            task.drawAction().accept(elements);
        }
        return elements;
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

    /**
     * 背景の指定が無い文書のキャンバス。ゲームの上に重ねて描く文書は iframe と同じ扱いで透明
     * （ブラウザが白で塗るのは最上位の文書だけ）。html / body はパーサが必ず補う（W7）ので、背景を書かない HUD の断片も透明になる。
     * 以前は ua.css が body を白で塗り、body の無い文書は #111111 で塗りつぶしていた。
     */
    private static final int DEFAULT_CANVAS_COLOR = 0x00000000;

    private static LayoutBox canvasBackgroundSource(LayoutBox root) {
        LayoutBox html = childWithTag(root, "html");
        if (html != null && hasBackground(html)) return html;
        LayoutBox body = childWithTag(html != null ? html : root, "body");
        return body != null && hasBackground(body) ? body : null;
    }

    private static LayoutBox childWithTag(LayoutBox parent, String tag) {
        for (LayoutBox child : parent.children) {
            if (tag.equals(child.node.tag)) return child;
        }
        return null;
    }

    private static boolean hasBackground(LayoutBox box) {
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
    private List<RenderTask> paintTasks(LayoutBox root) {
        fragIdCounter = 0;
        contentClips.clear();
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
    private void collectChildren(LayoutBox box, float[] clip, String parentId, PaintLayers sc, PaintLayers local) {
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
    private float[] clipForChild(LayoutBox child, float[] flowClip) {
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
    private void classify(LayoutBox box, float[] clip, String parentId, PaintLayers sc, PaintLayers local) {
        if (box.display.isNone() || "none".equals(box.style.getOrDefault(CssProperty.DISPLAY, "block"))) return;
        if ("option".equalsIgnoreCase(box.node.tag)) return;

        RenderTask self = selfTask(box, clip, parentId);
        if (createsStackingContext(box)) {
            int z = isZIndexAuto(box) ? 0 : box.zIndex;
            PaintNode node = new PaintNode(self, true, z);
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
    private static void emit(PaintNode node, List<RenderTask> out) {
        if (node.self != null) out.add(node.self);
        PaintLayers l = node.layers;
        if (l == null) return;
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
    }

    private RenderTask selfTask(LayoutBox box, float[] clip, String parentId) {
        if (box.w <= 0 || box.h <= 0 || box.isAnonymous) return null;
        return new RenderTask(box, elements -> drawBoxSelf(elements, box, clip, parentId));
    }

    private static boolean isPositioned(LayoutBox box) {
        return box.isRelative || box.isAbsolute || box.isSticky;
    }

    private static boolean isZIndexAuto(LayoutBox box) {
        String z = box.style.get(CssProperty.Z_INDEX);
        return z == null || z.isBlank() || "auto".equalsIgnoreCase(z.trim());
    }

    /** flex / grid アイテムは位置指定が無くても z-index でコンテキストを作る（CSS Flexbox §5.4 / Grid §9.5）。 */
    private static boolean isFlexOrGridItem(LayoutBox box) {
        if (box.parent == null || box.isAbsolute) return false;
        LayoutBox.Display.Inner inner = box.parent.display.inner();
        return inner == LayoutBox.Display.Inner.FLEX || inner == LayoutBox.Display.Inner.GRID;
    }

    /**
     * スタッキングコンテキストを作るか（CSS 2.2 §9.9.1 と CSS Positioned Layout 3 / Color 4 /
     * Compositing 1 / Transforms 1 の追加分）。
     */
    static boolean createsStackingContext(LayoutBox box) {
        boolean zAuto = isZIndexAuto(box);
        if (isPositioned(box) && !zAuto) return true;
        if (box.isFixed || box.isSticky) return true;
        if (!zAuto && isFlexOrGridItem(box)) return true;
        if (box.style.number(CssProperty.OPACITY, 1f) < 1f) return true;
        if (!isNone(box.style.get(CssProperty.TRANSFORM))) return true;
        if (!isNone(box.style.get("filter"))) return true;
        if (box.style.keywordOf("isolation", CssKeywords.Isolation.class) == CssKeywords.Isolation.ISOLATE) return true;
        return !"normal".equals(box.style.keyword("mix-blend-mode", "normal"));
    }

    private static boolean isNone(String value) {
        return value == null || value.isBlank() || "none".equalsIgnoreCase(value.trim());
    }

    /** 中身ごと 1 つの単位として描くインラインレベルの箱（inline-block / inline-flex など）と flex / grid アイテム。 */
    private static boolean isAtomicInline(LayoutBox box) {
        if (isFlexOrGridItem(box)) return true;
        return box.display.isInlineLevel() && box.display.inner() != LayoutBox.Display.Inner.FLOW;
    }

    /** スタッキングコンテキスト（または疑似コンテキスト）の中の層。番号は付録 E の手順。 */
    private static final class PaintLayers {
        final List<PaintNode> negative = new ArrayList<>();   // 2: 負の z-index のコンテキスト
        final List<PaintNode> blocks = new ArrayList<>();     // 4: 通常フローのブロックレベル
        final List<PaintNode> floats = new ArrayList<>();     // 5: float
        final List<PaintNode> inlines = new ArrayList<>();    // 7: インラインレベル
        final List<PaintNode> positioned = new ArrayList<>(); // 8: z-index: auto / 0 の位置指定要素とコンテキスト
        final List<PaintNode> positive = new ArrayList<>();   // 9: 正の z-index のコンテキスト
        final List<RenderTask> after = new ArrayList<>();     // スクロールバー（中身より上）
    }

    /** {@code layers} が null なら箱自身だけ（中身は今の層に流れている）。 */
    private record PaintNode(RenderTask self, boolean stackingContext, int z, PaintLayers layers) {
        PaintNode(RenderTask self, boolean stackingContext, int z) {
            this(self, stackingContext, z, new PaintLayers());
        }
    }

    private static float parseOriginProp(String p) {
        return switch (p) {
            case "left", "top" -> 0.0f;
            case "right", "bottom" -> 1.0f;
            case "center" -> 0.5f;
            default -> {
                if (p.endsWith("%")) {
                    try {
                        yield Float.parseFloat(p.substring(0, p.length() - 1)) / 100f;
                    } catch (NumberFormatException ignored) {}
                }
                yield 0.5f;
            }
        };
    }

    /** 切らない軸のクリップの端（描画とヒットテストの座標に十分遠い値）。 */
    private static final float UNCLIPPED = 1.0e6f;

    /**
     * 中身に掛けるクリップ（パディングボックスの角丸矩形と祖先のクリップの交わり）。
     *
     * {@code overflow} はロングハンドの {@code overflow-x} / {@code overflow-y} から読む（ショートハンドは
     * 算出の段で展開済み）。片方の軸だけが切るときはもう片方は祖先のクリップのまま。
     *
     * 何が壊れていたか: 以前はショートハンドの {@code overflow} だけを読んでいたので、展開後は常に
     * visible 扱いになり、{@code overflow: hidden} も {@code overflow-y: auto} も子を切らなかった。
     */
    private float[] computeChildClipRect(LayoutBox box, float[] parentClip) {
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
            float[] r = LengthResolver.cornerRadii(box.style, box.w, box.h, rootW, rootH);
            own[4] = r[0] - Math.max(box.bT, box.bL);
            own[5] = r[1] - Math.max(box.bT, box.bR);
            own[6] = r[2] - Math.max(box.bB, box.bR);
            own[7] = r[3] - Math.max(box.bB, box.bL);
        }
        return BoxDecorations.intersectClip(parentClip, own);
    }

    /**
     * {@code overflow-x} / {@code overflow-y} の使用値。片方が visible / clip でもう片方がそうでなければ、
     * visible は auto、clip は hidden として扱う（CSS Overflow 3 §3.1）。
     */
    private static String[] overflowPair(LayoutBox box) {
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

    private static boolean isScrollbarOverflow(String overflow) {
        return "scroll".equals(overflow) || "auto".equals(overflow);
    }

    /**
     * {@code visibility: hidden / collapse}。箱自身は描かずヒットテストにも出さないが、場所は取り、子孫は自分の値で描く。
     *
     * LIMITATION(制約): 表の行・列の {@code collapse} も hidden と同じで、場所を詰めない。README の "Not supported" に対応。
     */
    private static boolean isInvisible(LayoutBox box) {
        return box.style.keywordOf(CssProperty.VISIBILITY, CssKeywords.Visibility.class) != CssKeywords.Visibility.VISIBLE;
    }

    private void drawScrollbarsIfNeeded(List<DisplayItem> elements, LayoutBox box, float[] clipRect) {
        if (box.style.keywordOf(CssProperty.SCROLLBAR_WIDTH, CssKeywords.ScrollbarWidth.class) == CssKeywords.ScrollbarWidth.NONE) return;

        float scrollW = box.scrollWidth;
        float scrollH = box.scrollHeight;
        float curX = scrollState.getScrollX(box.node);
        float curY = scrollState.getScrollY(box.node);

        Map<String, String> sbEvents = new HashMap<>(4);
        sbEvents.put(RenderKeys.MC_PARENT, box.node.getId());
        ClipRect sbClip = normalizeClipRect(clipRect);
        RenderMeta sbMeta = new RenderMeta(sbClip, new Corners(2, 2, 2, 2), null, null, 12.0f, 0.5f, 0.5f, 1, false);

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
     * 描画要素を 1 つ足す。レイアウトの float 座標を整数に丸めるのはここ 1 箇所だけ（軸 3）。
     *
     * 幅と高さを個別に丸めず、左右（上下）の辺をそれぞれ丸めて差を取る。こうすると
     * 隣り合う箱の辺が同じ整数に落ち、端数の幅が並んでも隙間や重なりが出ない。
     * 以前はレイアウトの各段が int に切り捨てていたため、丸め誤差が入れ子の深さだけ溜まっていた。
     */
    private void addElement(List<DisplayItem> elements, String baseNodeId, String id, ElementType type, ImageFitMode fitMode,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, Map<String, String> events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            RenderMeta meta) {
        int left = Math.round(x);
        int top = Math.round(y);
        int width = Math.round(x + w) - left;
        int height = Math.round(y + h) - top;
        elements.add(new DisplayItem(id, baseNodeId, type, left, top, width, height, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, fitMode, meta));
    }

    private void addElement(List<DisplayItem> elements, String baseNodeId, String id, ElementType type,
                            float x, float y, float w, float h, String text,
                            int color, int bgColor, Map<String, String> events, int radius, float opacity,
                            float tx, float ty, float sx, float sy, float rot, int trans, AnimationSpec animDef,
                            RenderMeta meta) {
        addElement(elements, baseNodeId, id, type, ImageFitMode.FILL, x, y, w, h, text, color, bgColor, events, radius, opacity, tx, ty, sx, sy, rot, trans, animDef, meta);
    }

    private static String resolveLiveInputValue(String nodeId, String attrValue) {
        return attrValue != null ? attrValue : "";
    }

    static float[] parseTransformProps(String t, float boxW, float boxH, float defTx, float defTy, float defSx, float defSy, float defRot) {
        float tx = defTx, ty = defTy, sx = defSx, sy = defSy, rot = defRot;
        if (t == null || t.isEmpty()) return new float[]{tx, ty, sx, sy, rot};

        Matcher txM = TRANSLATE_X_PATTERN.matcher(t);
        if (txM.find()) {
            tx = "%".equals(txM.group(2)) ? (Float.parseFloat(txM.group(1)) / 100.0f) * boxW : Float.parseFloat(txM.group(1));
        }
        Matcher tyM = TRANSLATE_Y_PATTERN.matcher(t);
        if (tyM.find()) {
            ty = "%".equals(tyM.group(2)) ? (Float.parseFloat(tyM.group(1)) / 100.0f) * boxH : Float.parseFloat(tyM.group(1));
        }
        Matcher tm = TRANSLATE_PATTERN.matcher(t);
        if (tm.find()) {
            tx = "%".equals(tm.group(2)) ? (Float.parseFloat(tm.group(1)) / 100.0f) * boxW : Float.parseFloat(tm.group(1));
            if (tm.group(3) != null) {
                ty = "%".equals(tm.group(4)) ? (Float.parseFloat(tm.group(3)) / 100.0f) * boxH : Float.parseFloat(tm.group(3));
            }
        }
        Matcher sm = SCALE_PATTERN.matcher(t);
        if (sm.find()) {
            sx = Float.parseFloat(sm.group(1));
            sy = sm.group(2) != null ? Float.parseFloat(sm.group(2)) : sx;
        }
        Matcher rm = ROTATE_PATTERN.matcher(t);
        if (rm.find()) {
            float val = Float.parseFloat(rm.group(1));
            if ("rad".equals(rm.group(2))) val = (float) Math.toDegrees(val);
            rot = val;
        }
        return new float[]{tx, ty, sx, sy, rot};
    }

    /**
     * CSS の {@code animation} ショートハンド・各ロングハンドと {@code @keyframes} を
     * {@link AnimationSpec} に解決する。
     */
    private AnimationSpec parseAnimationDef(ComputedStyle style, float baseW, float baseH, int baseColor, int baseBgColor) {
        String animProp = style.get(CssProperty.ANIMATION);
        String name = "";
        int dur = 1000;
        int delay = 0;
        float iterationCount = 1.0f;
        AnimationSpec.Direction direction = AnimationSpec.Direction.NORMAL;
        AnimationSpec.FillMode fillMode = AnimationSpec.FillMode.NONE;
        String easing = EasingFunction.DEFAULT_EASING;

        if (animProp != null && !"none".equalsIgnoreCase(animProp.trim())) {
            easing = EasingFunction.extractFromShorthand(animProp);
            String cleaned = CUBIC_BEZIER_SPAN_PATTERN.matcher(animProp).replaceAll(" ").trim();
            String[] parts = WHITESPACE_SPLIT.split(cleaned);
            int timeCount = 0;
            for (String p : parts) {
                if (p.isEmpty()) continue;
                String lower = p.toLowerCase(Locale.ROOT);
                Matcher tm = TIME_UNIT_PATTERN.matcher(lower);
                if (tm.matches()) {
                    int ms = parseTimeTokenMs(tm);
                    if (timeCount == 0) dur = ms;
                    else if (timeCount == 1) delay = ms;
                    timeCount++;
                } else if ("infinite".equals(lower)) {
                    iterationCount = Float.POSITIVE_INFINITY;
                } else if ("normal".equals(lower) || "reverse".equals(lower)
                        || "alternate".equals(lower) || "alternate-reverse".equals(lower)) {
                    direction = AnimationSpec.Direction.fromCss(lower);
                } else if ("forwards".equals(lower) || "backwards".equals(lower) || "both".equals(lower)) {
                    fillMode = AnimationSpec.FillMode.fromCss(lower);
                } else if (EASING_KEYWORDS.contains(lower)) {
                    easing = lower;
                } else if (keyframes.containsKey(p)) {
                    name = p;
                } else {
                    try {
                        iterationCount = Float.parseFloat(lower);
                    } catch (NumberFormatException ignored) {
                        if (name.isEmpty()) name = p;
                    }
                }
            }
        }

        // 個別ロングハンドの上書き
        String nameProp = style.get(CssProperty.ANIMATION_NAME);
        if (nameProp != null && !nameProp.isBlank()) {
            name = nameProp.trim();
        }
        if (name.isEmpty() || "none".equalsIgnoreCase(name) || !keyframes.containsKey(name)) {
            return AnimationSpec.NONE;
        }

        String durProp = style.get(CssProperty.ANIMATION_DURATION);
        if (durProp != null) {
            Matcher tm = TIME_UNIT_PATTERN.matcher(durProp.trim().toLowerCase(Locale.ROOT));
            if (tm.find()) dur = parseTimeTokenMs(tm);
        }

        String delayProp = style.get(CssProperty.ANIMATION_DELAY);
        if (delayProp != null) {
            Matcher tm = TIME_UNIT_PATTERN.matcher(delayProp.trim().toLowerCase(Locale.ROOT));
            if (tm.find()) delay = parseTimeTokenMs(tm);
        }

        String iterProp = style.get(CssProperty.ANIMATION_ITERATION_COUNT);
        if (iterProp != null && !iterProp.isBlank()) {
            String trimmed = iterProp.trim().toLowerCase(Locale.ROOT);
            if ("infinite".equals(trimmed)) {
                iterationCount = Float.POSITIVE_INFINITY;
            } else {
                try {
                    iterationCount = Float.parseFloat(trimmed);
                } catch (NumberFormatException ignored) {}
            }
        }

        String dirProp = style.get(CssProperty.ANIMATION_DIRECTION);
        if (dirProp != null && !dirProp.isBlank()) {
            direction = AnimationSpec.Direction.fromCss(dirProp);
        }

        String fillProp = style.get(CssProperty.ANIMATION_FILL_MODE);
        if (fillProp != null && !fillProp.isBlank()) {
            fillMode = AnimationSpec.FillMode.fromCss(fillProp);
        }

        String tfProp = style.get(CssProperty.ANIMATION_TIMING_FUNCTION);
        if (tfProp != null && !tfProp.isBlank()) {
            easing = tfProp.trim();
        }

        List<AnimationSpec.Keyframe> frames = new ArrayList<>();
        int animatedChannels = 0;

        for (KeyframeBlock block : keyframes.get(name).blocks()) {
            float pct = block.percent();
            Map<String, String> decls = block.declarations();
            int definedChannels = 0;

            float[] tr = new float[]{0f, 0f, 1f, 1f, 0f};
            if (decls.containsKey(CssProperty.TRANSFORM)) {
                tr = parseTransformProps(decls.get(CssProperty.TRANSFORM), baseW, baseH, 0f, 0f, 1f, 1f, 0f);
                definedChannels |= AnimationSpec.CH_TRANSFORM;
            }

            float op = 1f;
            if (decls.containsKey(CssProperty.OPACITY)) {
                try {
                    op = Float.parseFloat(decls.get(CssProperty.OPACITY).trim());
                    definedChannels |= AnimationSpec.CH_OPACITY;
                } catch (NumberFormatException ignored) {}
            }

            int col = baseColor;
            if (decls.containsKey(CssProperty.COLOR)) {
                // transparent（0x00000000）もキーフレームの色として有効
                if (ColorParser.isColor(decls.get(CssProperty.COLOR))) {
                    col = ColorParser.parseColor(decls.get(CssProperty.COLOR));
                    definedChannels |= AnimationSpec.CH_COLOR;
                }
            }

            int bgCol = baseBgColor;
            String bgDecl = decls.getOrDefault(CssProperty.BACKGROUND_COLOR, decls.get(CssProperty.BACKGROUND));
            if (bgDecl != null) {
                bgCol = ColorParser.parseColor(bgDecl);
                definedChannels |= AnimationSpec.CH_BG_COLOR;
            }

            animatedChannels |= definedChannels;
            frames.add(new AnimationSpec.Keyframe(pct, tr[0], tr[1], tr[2], tr[3], tr[4], op, col, bgCol, definedChannels));
        }

        if (frames.isEmpty() || animatedChannels == 0) return AnimationSpec.NONE;
        return new AnimationSpec(dur, delay, iterationCount, direction, fillMode, easing, animatedChannels, frames);
    }

    private static int parseTimeTokenMs(Matcher tm) {
        try {
            float val = Float.parseFloat(tm.group(1));
            if ("s".equals(tm.group(2))) val *= 1000f;
            return Math.round(val);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * CSS の {@code transition} ショートハンドとロングハンド（{@code transition-property},
     * {@code transition-duration}, {@code transition-delay}, {@code transition-timing-function}）を解決する。
     * CSS 既定のイージングは {@code ease}（{@code cubic-bezier(0.25, 0.1, 0.25, 1.0)}）。
     */
    private static TransitionSpec parseTransitionSpec(Map<String, String> style) {
        int durationMs = 0;
        int delayMs = 0;
        String easing = EasingFunction.DEFAULT_EASING;
        String property = "all";

        String tr = style.get(CssProperty.TRANSITION);
        if (tr != null && !tr.isBlank()) {
            if ("none".equalsIgnoreCase(tr.trim())) {
                property = "none";
            } else {
                easing = EasingFunction.extractFromShorthand(tr);
                List<String> entries = CssSyntax.parseCommaList(tr);
                List<String> propNames = new ArrayList<>(entries.size());

                for (int idx = 0; idx < entries.size(); idx++) {
                    String entry = entries.get(idx).trim();
                    if (entry.isEmpty()) continue;
                    String entryWithoutBezier = CUBIC_BEZIER_SPAN_PATTERN.matcher(entry).replaceAll(" ").trim();
                    String[] tokens = WHITESPACE_SPLIT.split(entryWithoutBezier);
                    int entryTimeCount = 0;
                    for (String tok : tokens) {
                        if (tok.isEmpty()) continue;
                        String lower = tok.toLowerCase(Locale.ROOT);
                        Matcher tm = TIME_UNIT_PATTERN.matcher(lower);
                        if (tm.matches()) {
                            int ms = parseTimeTokenMs(tm);
                            if (idx == 0) {
                                if (entryTimeCount == 0) durationMs = ms;
                                else if (entryTimeCount == 1) delayMs = ms;
                            } else if (entryTimeCount == 0 && durationMs == 0) {
                                durationMs = ms;
                            }
                            entryTimeCount++;
                        } else if (!EASING_KEYWORDS.contains(lower)) {
                            propNames.add(lower);
                        }
                    }
                }
                if (!propNames.isEmpty()) {
                    property = String.join(",", propNames);
                }
            }
        }

        String tp = style.get(CssProperty.TRANSITION_PROPERTY);
        if (tp != null && !tp.isBlank()) {
            property = tp.trim().toLowerCase(Locale.ROOT);
        }

        String tdur = style.get(CssProperty.TRANSITION_DURATION);
        if (tdur != null) {
            Matcher m = TIME_UNIT_PATTERN.matcher(tdur.trim().toLowerCase(Locale.ROOT));
            if (m.find()) {
                durationMs = parseTimeTokenMs(m);
            }
        }

        String td = style.get(CssProperty.TRANSITION_DELAY);
        if (td != null) {
            Matcher m = TIME_UNIT_PATTERN.matcher(td.trim().toLowerCase(Locale.ROOT));
            if (m.find()) {
                delayMs = parseTimeTokenMs(m);
            }
        }

        String ttf = style.get(CssProperty.TRANSITION_TIMING_FUNCTION);
        if (ttf != null && !ttf.isBlank()) {
            easing = ttf.trim();
        }
        return new TransitionSpec(durationMs, delayMs, easing, property);
    }

    /**
     * 枠線の幅・色・線種。幅はレイアウトが決めた値（{@code box.bT} など）をそのまま使う（軸 1・3）。
     * 線種が none / hidden の辺は算出値の段で幅 0 になっている。色が無ければ {@code currentcolor}。
     *
     * 何が壊れていたか: 以前は描画が {@code border-<辺>} の文字列から幅と色を読み直しており、
     * 色を書かない {@code border: 1px solid} の枠が透明になり、線種は読まずに常に実線だった。
     */
    private BorderSpec parseBorderSpec(LayoutBox box, int[] radii, int textColor) {
        float[] widths = {box.bT, box.bR, box.bB, box.bL};
        int[] colors = new int[4];
        String[] styles = new String[4];
        boolean hasBorder = false;
        for (int i = 0; i < 4; i++) {
            String dir = BORDER_DIRECTIONS[i];
            String colorProp = box.style.get("border-" + dir + "-color");
            colors[i] = colorProp == null ? textColor : ColorParser.parseColor(colorProp);
            styles[i] = box.style.keyword("border-" + dir + "-style", "solid");
            if (widths[i] > 0) hasBorder = true;
        }
        float bwTop = widths[0], bwRight = widths[1], bwBottom = widths[2], bwLeft = widths[3];
        int bcTop = colors[0], bcRight = colors[1], bcBottom = colors[2], bcLeft = colors[3];

        boolean isCircle = radii[0] >= (box.w / 2) - 1 && radii[1] >= (box.w / 2) - 1
                && radii[2] >= (box.w / 2) - 1 && radii[3] >= (box.w / 2) - 1
                && box.w > 0 && box.h > 0 && Math.abs(box.w - box.h) <= 2;
        // LIMITATION(制約): 角丸の枠線は線種を読まず実線で描き、角の中で隣り合う辺の色が切り替わる境目は
        // 斜めの線ではなく辺の太さの内側か外側かで決める。README の "Not supported" に対応。
        boolean useRoundedBorder = (radii[0] > 0 || radii[1] > 0 || radii[2] > 0 || radii[3] > 0) && hasBorder && !isCircle;

        Corners innerRadii = new Corners(
                Math.max(0, Math.round(radii[0] - Math.max(bwTop, bwLeft))),
                Math.max(0, Math.round(radii[1] - Math.max(bwTop, bwRight))),
                Math.max(0, Math.round(radii[2] - Math.max(bwBottom, bwRight))),
                Math.max(0, Math.round(radii[3] - Math.max(bwBottom, bwLeft)))
        );

        return new BorderSpec(hasBorder, isCircle, useRoundedBorder,
                bwTop, bwRight, bwBottom, bwLeft, bcTop, bcRight, bcBottom, bcLeft, styles, innerRadii);
    }

    private void drawBoxSelf(List<DisplayItem> elements, LayoutBox box, float[] clipRect, String parentId) {
        int[] rawRadii = roundRadii(LengthResolver.cornerRadii(box.style, box.w, box.h, rootW, rootH));
        Corners radii = Corners.fromArray(rawRadii);
        int fallbackRadius = radii.topLeft();
        float rOff = Math.min(fallbackRadius, Math.min(box.w, box.h) / 2);

        // 何が壊れていたか: 以前は 0x00000000 をオフホワイトに書き換えていたので、color: transparent の文字が
        // 白で描かれていた。color は継承するので、宣言の無い箱にも親か初期値（black）が届いている
        int color = box.style.color(CssProperty.COLOR, 0xFF000000);

        // 背景色と画像のレイヤーは別物（色はいちばん下）。background ショートハンドは算出の段で
        // ロングハンドに展開済み。キャンバスに背景を譲った要素は色を描かない
        int bgColor = box == canvasBackgroundSource ? 0x00000000
                : ColorParser.parseColor(box.style.getOrDefault(CssProperty.BACKGROUND_COLOR, "transparent"));

        String id = box.node.getId();

        float opacity = box.style.number(CssProperty.OPACITY, 1.0f);
        float[] tr = parseTransformProps(box.style.get(CssProperty.TRANSFORM), box.w, box.h, 0f, 0f, 1f, 1f, 0f);
        float tx = tr[0] + parseFloatAttr(box.node.attrs, "data-pan-x", 0f);
        float ty = tr[1] + parseFloatAttr(box.node.attrs, "data-pan-y", 0f);
        float zoom = parseFloatAttr(box.node.attrs, "data-zoom", 1.0f);
        float sx = tr[2] * zoom;
        float sy = tr[3] * zoom;
        float rot = tr[4];

        TransitionSpec transSpec = parseTransitionSpec(box.style.asMap());
        AnimationSpec animDef = parseAnimationDef(box.style, box.w, box.h, color, bgColor);
        AnimationSpec colorBgAnim = animDef.withChannels(AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR);
        int transDur = transSpec.durationMs();
        boolean interactive = box.hasInteractiveRules;

        float ox = 0.5f, oy = 0.5f;
        Map<String, String> anchorEvents = new HashMap<>(6);
        if (parentId != null) anchorEvents.put(RenderKeys.MC_PARENT, parentId);
        applyTransitionEvents(anchorEvents, transSpec);
        if (box.style.containsKey(CssProperty.TRANSFORM_ORIGIN)) {
            String[] parts = WHITESPACE_SPLIT.split(box.style.get(CssProperty.TRANSFORM_ORIGIN).toLowerCase(Locale.ROOT).trim());
            if (parts.length > 0) ox = parseOriginProp(parts[0]);
            if (parts.length > 1) oy = parseOriginProp(parts[1]);
            anchorEvents.put(RenderKeys.MC_ORIGIN, ox + "," + oy);
        }

        // 算出済みの font-size をそのまま運ぶ。描画側は書体の em からの倍率に直す。
        // 以前は 16 倍した「倍率」を渡し、描画側が 12 で割るという二重の換算だった。
        float fontHVal = Math.max(1.0f, box.style.fontSizePx());
        int itemCount = (int) parseFloatAttr(box.node.attrs, "data-count", 1f);

        boolean pointerEventsNone = box.style.keywordOf(CssProperty.POINTER_EVENTS, CssKeywords.PointerEvents.class)
                == CssKeywords.PointerEvents.NONE;
        if (pointerEventsNone) anchorEvents.put(RenderKeys.POINTER_EVENTS, "none");

        ClipRect normalizedClip = normalizeClipRect(clipRect);
        RenderMeta anchorMeta = new RenderMeta(normalizedClip, radii, null, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);
        addElement(elements, id, id, ElementType.NODE, box.x, box.y, box.w, box.h, "", color, bgColor, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, transDur, animDef, anchorMeta);

        // visibility: hidden の箱は場所だけを取る。錨（NODE）は子孫の変形と不透明度の親として残す
        // 何が壊れていたか: 以前は visibility を読んでおらず、hidden の箱が描かれクリックも受けていた
        if (isInvisible(box)) return;

        Map<String, String> partsEvents = buildPartsEvents(box, id, fontHVal, transSpec);
        Map<String, String> passiveBorderEvents = buildPassiveBorderEvents(id, transSpec);
        // 何が壊れていたか: 以前は pointer-events を部品に載せていなかったので、ヒットテストの
        // pointer-events: none の判定は常に偽だった
        if (pointerEventsNone) {
            partsEvents.put(RenderKeys.POINTER_EVENTS, "none");
            passiveBorderEvents.put(RenderKeys.POINTER_EVENTS, "none");
        }

        RenderMeta baseMeta = new RenderMeta(normalizedClip, radii, null, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);

        if (!box.inlineFragments.isEmpty() && !NON_FRAGMENT_TAGS.contains(box.node.tag) && !BlockLayoutEngine.isAtomicInline(box)) {
            drawInlineFragments(elements, box, id, color, bgColor, rawRadii, normalizedClip, fontHVal, ox, oy, itemCount, interactive,
                    partsEvents, passiveBorderEvents, transDur, colorBgAnim);
            return;
        }

        List<BoxDecorations.Shadow> shadows = BoxDecorations.parseBoxShadows(box.style.get(CssProperty.BOX_SHADOW), color,
                rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
        drawOuterShadows(elements, box, id, shadows, rawRadii, normalizedClip, fontHVal, ox, oy);

        BorderSpec border = parseBorderSpec(box, rawRadii, color);
        RenderMeta innerMeta = new RenderMeta(normalizedClip, border.innerRadii(), null, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);

        switch (box.node.tag) {
            case "mc-scene" -> drawSceneElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "img", "video", "mc-fluid", "mc-entity" -> drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "#text" -> drawTextNodeElement(elements, box, id, color, bgColor, normalizedClip, fontHVal, ox, oy, partsEvents, transDur, textColorAnimation(box, animDef));
            case "progress", "meter" -> {
                String val = box.node.attrs.getOrDefault("value", "0");
                String min = box.node.attrs.getOrDefault("min", "0");
                String max = box.node.attrs.getOrDefault("max", "100");
                // 何が壊れていたか: ここが RANGE を出していたため、progress / meter が
                // つまみ付きのスライダーとして描かれていた。進捗バーの描画実装
                // (DisplayItemDrawer.renderProgressElement) は呼ばれない死にコードだった。
                addElement(elements, id, id + "_progress", ElementType.PROGRESS, box.x, box.y, box.w, box.h, val + ":" + min + ":" + max, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            }
            case "canvas" -> drawCanvasElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "textarea" -> drawTextareaElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "select" -> drawSelectElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "input" -> {
                if (isPlainRange(box)) {
                    // appearance: none のレンジは作者の CSS で描いた箱に、つまみ（::-webkit-slider-thumb は部品のまま）だけを重ねる
                    drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                    drawNativeRange(elements, box, id, rangeData(box, resolveLiveInputValue(id, box.node.attrs.getOrDefault("value", ""))),
                            color, false, partsEvents, border, baseMeta, transDur, colorBgAnim);
                } else if (isPlainToggle(box)) {
                    // appearance: none のチェックボックス・ラジオボタンは普通の箱（作者の枠線・背景だけで描き、印は無い）
                    drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                } else {
                    drawInputElement(elements, box, id, color, bgColor, fallbackRadius, fontHVal, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                }
            }
            case "fieldset" -> drawFieldsetElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
            default -> drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
        }
        drawOutline(elements, box, id, color, passiveBorderEvents, baseMeta, transDur);
    }

    /**
     * インラインボックスを行ごとの断片で描く。各断片は背景と上下の枠線を持ち、左の枠線と角丸は箱の最初の断片
     * （{@link LayoutBox.InlineFragment#startEdge}）、右は最後の断片（{@code endEdge}）だけが持つ
     * （{@code box-decoration-break: slice}）。背景の部品 {@code <id>_frag_<n>} がヒットテストを受ける。
     *
     * 何が壊れていたか: 以前は断片に背景色だけを塗って返していたので、{@code <code>} や {@code <kbd>} の枠線と
     * 角丸が描かれなかった。断片も語ごとにあり、背景が語の数だけ重なっていた。
     */
    private void drawInlineFragments(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int[] rawRadii,
                                     ClipRect clip, float fontHVal, float ox, float oy, int itemCount, boolean interactive,
                                     Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                     int transDur, AnimationSpec colorBgAnim) {
        BorderSpec border = parseBorderSpec(box, rawRadii, color);
        String[] styles = border.styles();
        int[] colors = {border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft()};
        for (LayoutBox.InlineFragment frag : box.inlineFragments) {
            if (frag.isText()) continue;
            int[] r = {
                    frag.startEdge ? rawRadii[0] : 0, frag.endEdge ? rawRadii[1] : 0,
                    frag.endEdge ? rawRadii[2] : 0, frag.startEdge ? rawRadii[3] : 0
            };
            Corners radii = Corners.fromArray(r);
            RenderMeta meta = new RenderMeta(clip, radii, null, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);
            addElement(elements, id, id + "_frag_" + (fragIdCounter++), ElementType.RECT, frag.x, frag.y, frag.w, frag.h,
                    "", color, bgColor, partsEvents, r[0], 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, meta);

            float[] widths = {box.bT, frag.endEdge ? box.bR : 0, box.bB, frag.startEdge ? box.bL : 0};
            if (widths[0] <= 0 && widths[1] <= 0 && widths[2] <= 0 && widths[3] <= 0) continue;
            if (r[0] > 0 || r[1] > 0 || r[2] > 0 || r[3] > 0) {
                RoundedBorderSpec spec = new RoundedBorderSpec(widths[0], widths[1], widths[2], widths[3],
                        colors[0], colors[1], colors[2], colors[3]);
                RenderMeta ringMeta = new RenderMeta(clip, radii, null, null,
                        fontHVal, 0f, 0f, ox, oy, 1, false, spec);
                addElement(elements, id, id + "_b_round_" + (fragIdCounter++), ElementType.ROUNDED_BORDER, frag.x, frag.y, frag.w, frag.h,
                        "", 0, 0x00000000, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta);
            } else {
                drawLines(elements, id, "_b_", frag.x, frag.y, frag.w, frag.h, widths, styles, colors, 0,
                        passiveBorderEvents, meta, transDur, true);
            }
        }
    }

    /**
     * 輪郭線。枠線の外側に {@code outline-offset} だけ離して描き、レイアウトには影響しない。
     * 付録 E は輪郭線を最後の層に描いてもよいとしているが、箱自身と一緒に描くのも認めている（こちらを取る）。
     *
     * LIMITATION(制約): 輪郭線は角丸に沿わず、インラインボックスでは行ごとの断片に描かない。
     * README の "Not supported" に対応。
     */
    private void drawOutline(List<DisplayItem> elements, LayoutBox box, String id, int color,
                             Map<String, String> events, RenderMeta meta, int transDur) {
        String lineStyle = box.style.keywordOf("outline-style", BorderStyle.class).cssName();
        if ("none".equals(lineStyle) || "hidden".equals(lineStyle)) return;
        float w = box.style.length("outline-width", box.w, rootW, rootH);
        if (w <= 0) return;
        float offset = box.style.length("outline-offset", box.w, rootW, rootH);
        String colorProp = box.style.get("outline-color");
        int c = colorProp == null || "invert".equalsIgnoreCase(colorProp.trim()) ? color : ColorParser.parseColor(colorProp);
        if ("auto".equals(lineStyle)) lineStyle = "solid";

        float x = box.x - offset - w, y = box.y - offset - w;
        float ow = box.w + 2 * (offset + w), oh = box.h + 2 * (offset + w);
        if (ow <= 0 || oh <= 0) return;
        String[] styles = {lineStyle, lineStyle, lineStyle, lineStyle};
        int[] colors = {c, c, c, c};
        float[] widths = {w, w, w, w};
        drawLines(elements, id, "_o_", x, y, ow, oh, widths, styles, colors, 0, events, meta, transDur);
    }

    private static void applyTransitionEvents(Map<String, String> events, TransitionSpec transSpec) {
        if (transSpec.delayMs() > 0) {
            events.put(RenderKeys.MC_DELAY, String.valueOf(transSpec.delayMs()));
        }
        if (!EasingFunction.DEFAULT_EASING.equals(transSpec.easing())) {
            events.put(RenderKeys.MC_EASING, transSpec.easing());
        }
        if (!"all".equals(transSpec.property())) {
            events.put(RenderKeys.MC_TRANSITION_PROP, transSpec.property());
        }
    }

    private Map<String, String> buildPartsEvents(LayoutBox box, String id, float fontHVal, TransitionSpec transSpec) {
        Map<String, String> partsEvents = new HashMap<>();
        partsEvents.put(RenderKeys.MC_PARENT, id);
        partsEvents.put(RenderKeys.MC_FONT_H, String.valueOf(fontHVal));
        applyTransitionEvents(partsEvents, transSpec);
        if (box.style.containsKey(CssProperty.FONT_FAMILY)) {
            partsEvents.put("font-family", box.style.get(CssProperty.FONT_FAMILY));
        }
        putFontEvents(box, partsEvents);
        if (box.node.attrs.containsKey("title")) partsEvents.put(RenderKeys.MC_TITLE, box.node.attrs.get("title"));
        else if (box.node.attrs.containsKey("data-tooltip")) partsEvents.put(RenderKeys.MC_TITLE, box.node.attrs.get("data-tooltip"));

        for (String attr : PASSTHROUGH_DATA_ATTRS) {
            if (box.node.attrs.containsKey(attr)) {
                partsEvents.put(attr, box.node.attrs.get(attr));
            }
        }

        Overflow overflowX = box.style.overflowX();
        Overflow overflowY = box.style.overflowY();
        boolean isScrollable = overflowX == Overflow.SCROLL || overflowX == Overflow.AUTO
                || overflowY == Overflow.SCROLL || overflowY == Overflow.AUTO
                || "root".equals(box.node.tag) || "html".equals(box.node.tag) || "body".equals(box.node.tag);
        if (isScrollable) {
            partsEvents.put(RenderKeys.MC_SCROLLABLE, box.scrollWidth + "," + box.scrollHeight);
            partsEvents.put(RenderKeys.MC_SCROLL_CURRENT, scrollState.getScrollX(box.node) + "," + scrollState.getScrollY(box.node));
        }

        for (Map.Entry<String, String> entry : box.node.attrs.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).startsWith("on")) {
                partsEvents.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }
        for (Map.Entry<String, String> entry : box.style.asMap().entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).startsWith("on")) {
                partsEvents.put(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue());
            }
        }

        String tag = box.node.tag;
        if (("button".equals(tag) || "select".equals(tag) || "summary".equals(tag)) && !partsEvents.containsKey("onclick")) {
            partsEvents.put("onclick", id);
        }
        if ("label".equals(tag) && box.node.attrs.containsKey("for") && !partsEvents.containsKey("onclick")) {
            partsEvents.put("onclick", "label_for:" + box.node.attrs.get("for"));
        }
        return partsEvents;
    }

    private static Map<String, String> buildPassiveBorderEvents(String id, TransitionSpec transSpec) {
        Map<String, String> passive = new HashMap<>(4);
        passive.put(RenderKeys.MC_PARENT, id);
        applyTransitionEvents(passive, transSpec);
        return passive;
    }

    /**
     * 外側の影（{@code inset} でないもの）。リストの先頭が最前面なので後ろから描き、箱の背景より下に置く。
     * 影の形は箱をオフセットだけずらして広がりだけ膨らませた角丸矩形で、ぼかしは層の重ね塗りで近似する
     * （{@link BoxDecorations#shadowLayers}）。部品の id は {@code <id>_shadow_<番号>}。
     *
     * LIMITATION(制約): 外側の影は箱の下全体に塗るので、背景が透明・半透明の箱では箱の内側にも影が見える
     * （ブラウザは影を枠線ボックスの外だけに描く）。README の "Not supported" に対応。
     */
    private void drawOuterShadows(List<DisplayItem> elements, LayoutBox box, String id, List<BoxDecorations.Shadow> shadows,
                                  int[] radii, ClipRect clip, float fontHVal, float ox, float oy) {
        Map<String, String> events = Map.of(RenderKeys.MC_PARENT, id);
        int n = 0;
        for (int i = shadows.size() - 1; i >= 0; i--) {
            BoxDecorations.Shadow s = shadows.get(i);
            if (s.inset()) continue;
            if (s.blur() > 0) {
                // ぼかしは 1 枚の板に任せ、シェーダーが辺からの距離で滑らかに減衰させる（層の重ね塗りは段が見えた）。
                // 板は影の形から「ぼかしが消える所まで」広げた矩形で、形の角丸は広がりの分だけ変える
                float reach = DisplayItemDrawer.SHADOW_REACH * s.blur();
                float grow = s.spread() + reach;
                float w = box.w + 2 * grow, h = box.h + 2 * grow;
                if (w <= 0 || h <= 0) continue;
                Corners r = new Corners(growRadius(radii[0], s.spread()), growRadius(radii[1], s.spread()),
                        growRadius(radii[2], s.spread()), growRadius(radii[3], s.spread()));
                Map<String, String> blurEvents = new HashMap<>(events);
                blurEvents.put(RenderKeys.SHADOW_BLUR, Float.toString(s.blur()));
                addElement(elements, id, id + "_shadow_" + (n++), ElementType.RECT,
                        box.x + s.offsetX() - grow, box.y + s.offsetY() - grow, w, h, "", 0, s.color(), blurEvents,
                        r.topLeft(), 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                        new RenderMeta(clip, r, null, null, fontHVal, ox, oy, 1, false));
                continue;
            }
            for (BoxDecorations.ShadowLayer layer : BoxDecorations.shadowLayers(s)) {
                float grow = s.spread() + layer.grow();
                float w = box.w + 2 * grow, h = box.h + 2 * grow;
                if (w <= 0 || h <= 0) continue;
                // 広がりは角の丸みも同じだけ変える（丸みの無い角は角のまま。§7.1.1）
                Corners r = new Corners(growRadius(radii[0], grow), growRadius(radii[1], grow),
                        growRadius(radii[2], grow), growRadius(radii[3], grow));
                addElement(elements, id, id + "_shadow_" + (n++), ElementType.RECT,
                        box.x + s.offsetX() - grow, box.y + s.offsetY() - grow, w, h, "", 0, layer.color(), events,
                        r.topLeft(), 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                        new RenderMeta(clip, r, null, null, fontHVal, ox, oy, 1, false));
            }
        }
    }

    /**
     * 内側の影。背景（色と画像）の上、枠線の下にパディングボックスの内側へ描く。影の無い「穴」は
     * パディングボックスをオフセットだけずらして広がりだけ縮めた矩形で、パディングボックスから穴を除いた
     * 上下左右の帯を塗る。帯はパディングボックスの角丸で切る。
     *
     * LIMITATION(制約): 穴の角は丸めない。README の "Not supported" に対応。
     */
    private void drawInsetShadows(List<DisplayItem> elements, LayoutBox box, String id, List<BoxDecorations.Shadow> shadows,
                                  BorderSpec border, ClipRect clip, float fontHVal, float ox, float oy) {
        BoxDecorations.Rect pad = BoxDecorations.boxArea(box, "padding-box");
        if (pad.w() <= 0 || pad.h() <= 0) return;
        ClipRect padClip = intersectClip(clip, pad, border.innerRadii(), id);
        Map<String, String> events = Map.of(RenderKeys.MC_PARENT, id);
        int n = 0;
        for (int i = shadows.size() - 1; i >= 0; i--) {
            BoxDecorations.Shadow s = shadows.get(i);
            if (!s.inset()) continue;
            for (BoxDecorations.ShadowLayer layer : BoxDecorations.shadowLayers(s)) {
                float shrink = s.spread() + layer.grow();
                float px0 = pad.x(), py0 = pad.y(), px1 = pad.x() + pad.w(), py1 = pad.y() + pad.h();
                float hx0 = Math.clamp(px0 + s.offsetX() + shrink, px0, px1), hx1 = Math.clamp(px1 + s.offsetX() - shrink, px0, px1);
                float hy0 = Math.clamp(py0 + s.offsetY() + shrink, py0, py1), hy1 = Math.clamp(py1 + s.offsetY() - shrink, py0, py1);
                if (hx1 <= hx0 || hy1 <= hy0) {
                    hx0 = hx1 = px0;
                    hy0 = hy1 = py0;
                }
                float[][] bands = {
                        {px0, py0, px1 - px0, hy0 - py0},
                        {px0, hy1, px1 - px0, py1 - hy1},
                        {px0, hy0, hx0 - px0, hy1 - hy0},
                        {hx1, hy0, px1 - hx1, hy1 - hy0},
                };
                for (float[] b : bands) {
                    if (b[2] <= 0 || b[3] <= 0) continue;
                    addElement(elements, id, id + "_shadow_in" + (n++), ElementType.RECT, b[0], b[1], b[2], b[3], "", 0,
                            layer.color(), events, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE,
                            new RenderMeta(padClip, null, null, null, fontHVal, ox, oy, 1, false));
                }
            }
        }
    }

    private static int growRadius(int radius, float grow) {
        return radius <= 0 ? 0 : Math.max(0, Math.round(radius + grow));
    }

    private void drawSceneElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                  int transDur, AnimationSpec colorBgAnim) {
        drawBorder(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        Map<String, String> sceneEvents = new HashMap<>(partsEvents);
        if (box.node.attrs.containsKey("data-blocks")) sceneEvents.put("data-blocks", box.node.attrs.get("data-blocks"));
        if (box.node.attrs.containsKey("slice-y")) sceneEvents.put("data-slice-y", box.node.attrs.get("slice-y"));
        else if (box.node.attrs.containsKey("data-slice-y")) sceneEvents.put("data-slice-y", box.node.attrs.get("data-slice-y"));
        if (box.node.attrs.containsKey("auto-rotate")) sceneEvents.put("data-auto-rotate", box.node.attrs.get("auto-rotate"));

        addElement(elements, id, id + "_scene", ElementType.SCENE, innerX, innerY, innerW, innerH, id, color, bgColor, sceneEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
    }

    private void drawMediaElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                  Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                  int transDur, AnimationSpec colorBgAnim) {
        String src = box.style.getOrDefault(CssProperty.SRC, box.node.attrs.getOrDefault("src", ""));
        if ("mc-fluid".equals(box.node.tag) && !src.startsWith("fluid:")) {
            src = "fluid:" + box.node.attrs.getOrDefault("fluid", src);
        } else if ("mc-entity".equals(box.node.tag) && !src.startsWith("entity:")) {
            src = "entity:" + box.node.attrs.getOrDefault("entity", src.isEmpty() ? "@self" : src);
        }

        ElementType type = "video".equals(box.node.tag) ? ElementType.VIDEO : ElementType.IMAGE;
        ImageFitMode fitMode = ImageFitMode.fromString(box.style.getOrDefault(CssProperty.OBJECT_FIT, "fill"));

        if (border.isCircle() && border.hasBorder()) {
            int thickness = border.maxThickness();
            String bData = thickness + ":" + border.bcTop() + "," + border.bcRight() + "," + border.bcBottom() + "," + border.bcLeft();
            CircleBorderSpec cbSpec = new CircleBorderSpec(thickness, border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft());
            addElement(elements, id, id + "_cb", ElementType.CIRCLE_BORDER, box.x, box.y, box.w, box.h, bData, 0, 0, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE,
                    new RenderMeta(normalizedClip, radii, null, cbSpec, fontHVal, ox, oy, 1, false));
        } else {
            drawBorder(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }

        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        addElement(elements, id, id + "_img", type, fitMode, innerX, innerY, innerW, innerH, src, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
    }

    /**
     * 文字の色のアニメーション。{@code animation} は継承されないが、祖先の {@code color} を補間すると
     * 継承した文字の色も一緒に変わる（CSS Animations 1 §3: アニメーションした値も算出値として継承される）。
     * 途中の要素が自分の色を持っていれば、そこから先には届かない。
     *
     * 何が壊れていたか: 文字の DisplayItem には文字ノード自身のアニメーション（常に none）しか載せていなかったので、
     * {@code <span style="animation: kfTextFlash ...">} の文字色が変わらなかった。
     */
    private AnimationSpec textColorAnimation(LayoutBox text, AnimationSpec own) {
        AnimationSpec colorAnim = own.withChannels(AnimationSpec.CH_COLOR);
        if (!colorAnim.isEmpty()) return colorAnim;
        String inherited = text.style.get(CssProperty.COLOR);
        for (LayoutBox a = text.parent; a != null && a.style != null; a = a.parent) {
            if (!Objects.equals(inherited, a.style.get(CssProperty.COLOR))) break;
            if (a.style.get(CssProperty.ANIMATION) != null || a.style.get(CssProperty.ANIMATION_NAME) != null) {
                int color = a.style.color(CssProperty.COLOR, 0xFF000000);
                AnimationSpec anim = parseAnimationDef(a.style, a.w, a.h, color, 0).withChannels(AnimationSpec.CH_COLOR);
                if (!anim.isEmpty()) return anim;
            }
            // 祖先の要素が自分の色を宣言していれば、その上の祖先の色は継承されない
            if (declaresOwnColor(a)) break;
        }
        return AnimationSpec.NONE;
    }

    /** 親と違う色を持っている（= 自分で color を宣言した）か。 */
    private static boolean declaresOwnColor(LayoutBox box) {
        return box.parent != null && box.parent.style != null
                && !Objects.equals(box.style.get(CssProperty.COLOR), box.parent.style.get(CssProperty.COLOR));
    }

    private void drawTextNodeElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor,
                                     ClipRect normalizedClip, float fontHVal, float ox, float oy, Map<String, String> partsEvents,
                                     int transDur, AnimationSpec colorAnim) {
        Map<String, String> baseTextEvents = new HashMap<>(partsEvents);
        if (box.style.containsKey(CssProperty.FONT_WEIGHT)) baseTextEvents.put("font-weight", box.style.get(CssProperty.FONT_WEIGHT));
        if (box.style.containsKey(CssProperty.FONT_FAMILY)) baseTextEvents.put("font-family", box.style.get(CssProperty.FONT_FAMILY));
        if (box.style.containsKey(CssProperty.TEXT_DECORATION)) baseTextEvents.put("text-decoration", box.style.get(CssProperty.TEXT_DECORATION));
        if (box.style.containsKey(CssProperty.TEXT_SHADOW) && !"none".equals(box.style.get(CssProperty.TEXT_SHADOW))) baseTextEvents.put("text-shadow", "true");
        putFontEvents(box, baseTextEvents);

        if (!box.inlineFragments.isEmpty()) {
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (frag.text == null || frag.text.isBlank()) continue;
                RenderMeta fragMeta = new RenderMeta(
                        normalizedClip, null, null, null, fontHVal, frag.baseline, frag.h, ox, oy, 1, false);
                addElement(elements, id, id + "_txt_" + (fragIdCounter++), ElementType.TEXT, frag.x, frag.y, frag.w, frag.h, frag.text, color, bgColor, baseTextEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, colorAnim, fragMeta);
            }
        } else if (box.shapedLines != null && !box.shapedLines.isEmpty()) {
            // 行の文字列・幅・ベースラインは整形器（TextShaper）が確定済み。
            // ここがするのは text-align の offset と丸めだけで、描画側は測り直さない。
            TextAlign align = box.style.keywordOf(CssProperty.TEXT_ALIGN, TextAlign.class);
            float contentX = box.x + box.pL + box.bL;
            float availableW = Math.max(0, box.w - box.pL - box.pR - box.bL - box.bR);
            float lineY = box.y + box.pT + box.bT;

            for (ShapedLine line : box.shapedLines) {
                if (!line.text().isBlank()) {
                    float lineW = line.width();
                    float lineX = contentX + alignOffset(align, availableW, lineW);
                    RenderMeta lineMeta = new RenderMeta(
                            normalizedClip, null, null, null, fontHVal, line.baseline(), line.height(), ox, oy, 1, false);
                    // text-transform は displayText を作る時点で適用済み（測定と描画で同じ文字列）
                    addElement(elements, id, id + "_txt_" + (fragIdCounter++), ElementType.TEXT, lineX, lineY, lineW, line.height(), line.text(), color, bgColor, baseTextEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, colorAnim, lineMeta);
                }
                lineY += line.height();
            }
        }
    }

    /**
     * 描画側が測定と同じ書体・字の位置を引けるように、書体の照合と字の送りに効く指定を載せる
     * （太さ・斜体・カーニング・字間）。
     */
    private static void putFontEvents(LayoutBox box, Map<String, String> events) {
        // 既定値（400 / normal / auto）は載せない。描画側は無ければ既定値として読む
        String weight = box.style.get(CssProperty.FONT_WEIGHT);
        if (weight != null && !weight.equals("normal") && !weight.equals("400")) events.put("font-weight", weight);
        String fontStyle = box.style.get(CssProperty.FONT_STYLE);
        if (fontStyle != null && !fontStyle.equals("normal")) events.put("font-style", fontStyle);
        String kerning = box.style.get(CssProperty.FONT_KERNING);
        if (kerning != null && !kerning.equals("auto")) events.put("font-kerning", kerning);
        // 長さの解決は要素ごとには重いので、指定があるときだけ（初期値は normal = 0）
        String spacing = box.style.get(CssProperty.LETTER_SPACING);
        if (spacing != null && !spacing.equals("normal") && !spacing.equals("0")) {
            float letterSpacing = box.style.length(CssProperty.LETTER_SPACING, 0, 0, 0);
            if (letterSpacing != 0) events.put("letter-spacing", String.valueOf(letterSpacing));
        }
    }

    /** text-align が行に与える水平方向のずれ。 */
    private static float alignOffset(TextAlign align, float availableW, float lineW) {
        return switch (align) {
            case CENTER -> Math.max(0, (availableW - lineW) / 2);
            case RIGHT, END -> Math.max(0, availableW - lineW);
            default -> 0;
        };
    }

    private void drawCanvasElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                   Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                   BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                   int transDur, AnimationSpec colorBgAnim) {
        drawBorder(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());
        addElement(elements, id, id + "_canvas", ElementType.CANVAS, innerX, innerY, innerW, innerH, id, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
    }

    private void drawTextareaElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                     Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                     BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                     int transDur, AnimationSpec colorBgAnim) {
        String val = box.node.attrs.getOrDefault("value", box.node.getTextContent());
        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        Map<String, String> taEvents = new HashMap<>(partsEvents);
        if (box.node.attrs.containsKey("data-syntax")) taEvents.put("data-syntax", box.node.attrs.get("data-syntax"));
        if (box.node.attrs.containsKey("data-line-numbers")) taEvents.put("data-line-numbers", box.node.attrs.get("data-line-numbers"));

        if (border.useRoundedBorder()) {
            drawRoundedBorder(elements, id, box, passiveBorderEvents, border, baseMeta, transDur);
            addElement(elements, id, id + "_textarea", ElementType.TEXTAREA, innerX, innerY, innerW, innerH, val, color, bgColor, taEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
        } else {
            addElement(elements, id, id + "_textarea", ElementType.TEXTAREA, box.x, box.y, box.w, box.h, val, color, bgColor, taEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
    }

    private void drawSelectElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                   Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                   BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                   int transDur, AnimationSpec colorBgAnim) {
        String currentVal = box.node.attrs.getOrDefault("value", "");
        String displayLabel = DomUtils.resolveSelectDisplayLabel(box.node, currentVal) + " \u25be";
        float innerX = box.x + border.bwLeft(), innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight()), innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        if (border.useRoundedBorder()) {
            drawRoundedBorder(elements, id, box, passiveBorderEvents, border, baseMeta, transDur);
            addElement(elements, id, id + "_select", ElementType.SELECT, innerX, innerY, innerW, innerH, displayLabel, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
        } else {
            addElement(elements, id, id + "_select", ElementType.SELECT, box.x, box.y, box.w, box.h, displayLabel, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
    }

    /** {@code ::placeholder} に色の規則が無いときの文字色。 */
    private static final int DEFAULT_PLACEHOLDER_COLOR = 0xFFAAAAAA;

    private void drawInputElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius, float fontHVal,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                  int transDur, AnimationSpec colorBgAnim) {
        String inputType = box.node.attrs.getOrDefault("type", "text");
        String val = resolveLiveInputValue(id, box.node.attrs.getOrDefault("value", ""));

        float innerX = box.x + border.bwLeft();
        float innerY = box.y + border.bwTop();
        float innerW = Math.max(0, box.w - border.bwLeft() - border.bwRight());
        float innerH = Math.max(0, box.h - border.bwTop() - border.bwBottom());

        Map<String, String> innerEvents = new HashMap<>(partsEvents);

        switch (inputType) {
            case "radio", "checkbox" -> drawNativeToggle(elements, box, id, "radio".equals(inputType), color,
                    partsEvents, passiveBorderEvents, border, baseMeta, transDur, colorBgAnim);
            case "range" -> drawNativeRange(elements, box, id, rangeData(box, val), color, true, partsEvents, border, baseMeta, transDur, colorBgAnim);
            case "color" -> {
                int swatchColor = ColorParser.parseColor(val.isEmpty() ? "#89b4fa" : val);
                drawBorderedControl(elements, id, "_input", ElementType.INPUT, box, innerX, innerY, innerW, innerH, val, color, swatchColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            }
            default -> {
                drawBorderedControl(elements, id, "_input", ElementType.INPUT, box, innerX, innerY, innerW, innerH, val, color, bgColor, fallbackRadius, partsEvents, innerEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                if (box.node.attrs.containsKey("placeholder")) {
                    String placeholderText = val.isEmpty() ? box.node.attrs.get("placeholder") : "";
                    // 入力欄の内容領域をそのまま渡す。縦方向の中央寄せは書体の
                    // ascent / descent を知っている描画側が 1 箇所で行う
                    float phX = box.x + border.bwLeft() + 4;
                    Map<String, String> phEvents = new HashMap<>(passiveBorderEvents);
                    phEvents.put(RenderKeys.POINTER_EVENTS, "none");
                    // ::placeholder の color（UA の既定は #aaa）。規則が掛からなければ既定の色
                    int placeholderColor = box.placeholderStyle != null
                            ? box.placeholderStyle.color(CssProperty.COLOR, DEFAULT_PLACEHOLDER_COLOR)
                            : DEFAULT_PLACEHOLDER_COLOR;
                    addElement(elements, id, id + "_ph", ElementType.TEXT, phX, innerY, innerW, innerH, placeholderText, placeholderColor, 0x00000000, phEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
                }
            }
        }
    }

    /**
     * UA の部品で描くラジオボタン・チェックボックス（appearance: auto）。Chromium の描き方
     * （NativeThemeBase::PaintRadio / PaintCheckbox）に合わせ、箱に収まる最大の正方形を中央に置いて描く:
     * <ul>
     *   <li>ラジオボタン: 白い円と 1px の輪。選択時は輪と点（直径の 60%）を強調色で</li>
     *   <li>チェックボックス: 未選択は白い角丸（2px）の四角と 1px の枠。選択・未確定（indeterminate 属性）は
     *       四角全体を強調色で塗って枠を描かず、白い印（チェックの折れ線・横棒）を重ねる</li>
     * </ul>
     * 作者の枠線・背景・padding は使わない（{@code StyleResolver#adjustForAppearance} が枠線と padding を 0 にし、
     * 枠の色を UA の状態ごとの色（ua.css の :hover / :active / :checked）に置き換えてある）。
     * {@code accent-color} があれば選択時の強調色はその色。その色が白と見分けにくい（コントラスト比 3 未満）なら、
     * Chrome と同じく選択時だけ暗い配色にする（チェックの印・ラジオの輪と点の間が #3b3b3b）。
     *
     * 何が壊れていたか: 以前は選択の印を文字色（黒）の四角で描き、作者の枠線・背景がそのまま部品に効いていた。
     */
    private void drawNativeToggle(List<DisplayItem> elements, LayoutBox box, String id, boolean radio, int color,
                                  Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                  BorderSpec border, RenderMeta baseMeta, int transDur, AnimationSpec colorBgAnim) {
        boolean mixed = !radio && box.node.attrs.containsKey("indeterminate");
        boolean checked = box.node.attrs.containsKey("checked");
        int stateColor = border.bcTop();
        Integer accent = accentColor(box, color);
        // accent-color が白と見分けにくければ暗い配色（Chrome の実測。濃淡の向きもこれで決まる）
        boolean darkAccent = accent != null && contrastRatio(accent, NATIVE_WHITE) < 3f;
        int strong = (checked || mixed) && accent != null ? shadeAccent(accent, stateColor, darkAccent) : stateColor;

        float size = Math.min(box.w, box.h);
        float x = box.x + (box.w - size) / 2, y = box.y + (box.h - size) / 2;
        int radius = radio ? Math.round(size / 2) : Math.min(NATIVE_CHECKBOX_RADIUS, Math.round(size / 2));
        Corners corners = Corners.all(radius);
        RenderMeta meta = new RenderMeta(baseMeta.clipRect(), corners, null, null,
                baseMeta.fontHeight(), baseMeta.originX(), baseMeta.originY(), 1, false);

        String state = mixed ? "mixed" : Boolean.toString(checked);
        boolean filled = !radio && (checked || mixed);
        // 暗い配色ではチェックの印と、ラジオの輪と点の間が #3b3b3b になる（未確定の横棒は白のまま、未選択の部品は変わらない）
        boolean dark = checked && darkAccent;
        int mark = radio ? strong : dark && !mixed ? NATIVE_DARK : NATIVE_WHITE;
        int fill = filled ? strong : radio && dark ? NATIVE_DARK : NATIVE_WHITE;
        // 印の部品が背景を塗り、印（点・チェック）を中身として描く。輪はその上に重ねる
        addElement(elements, id, id + "_input", radio ? ElementType.RADIO : ElementType.CHECKBOX, x, y, size, size, state,
                mark, fill, partsEvents, radius,
                1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, meta);
        if (filled) return;
        int ring = radio ? strong : stateColor;
        RoundedBorderSpec spec = new RoundedBorderSpec(1f, 1f, 1f, 1f, ring, ring, ring, ring);
        RenderMeta ringMeta = new RenderMeta(baseMeta.clipRect(), corners, null, null,
                baseMeta.fontHeight(), 0f, 0f, baseMeta.originX(), baseMeta.originY(), 1, false, spec);
        addElement(elements, id, id + "_b_round", ElementType.ROUNDED_BORDER, x, y, size, size, "", 0, 0x00000000,
                passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta);
    }

    /**
     * {@code accent-color} の状態ごとの濃淡（Chrome の実測。12 色・24 個の値がすべて一致）。HSL の明度を 0.11 ずらし、
     * 明るい配色ではマウスを乗せると暗く・押すと明るく、暗い配色では逆にする。配色は部品の背景との
     * コントラストで決まるので、同じ色でもチェックボックスとレンジで向きが違うことがある（マゼンタなど）。
     * 状態は ua.css が状態ごとに置いた部品の色（StyleResolver が枠の色に置いたもの。作者の CSS では変わらない）から読む。
     *
     * 何が壊れていたか: 以前は accent-color を指定するとマウスを乗せても押しても色が変わらなかった。
     */
    static int shadeAccent(int accent, int stateColor, boolean dark) {
        int direction = stateColor == NATIVE_ACCENT_HOVER ? -1 : stateColor == NATIVE_ACCENT_ACTIVE ? 1 : 0;
        if (direction == 0) return accent;
        return shiftLightness(accent, (dark ? -direction : direction) * ACCENT_LIGHTNESS_STEP);
    }

    /** ua.css の状態ごとの部品の色（マウスを乗せたとき・押したとき）。 */
    private static final int NATIVE_ACCENT_HOVER = 0xFF005CC8, NATIVE_ACCENT_ACTIVE = 0xFF3793FF;
    private static final float ACCENT_LIGHTNESS_STEP = 0.11f;

    /** HSL の明度を {@code delta} だけずらす（0〜1 に収める）。色相・彩度と不透明度はそのまま。 */
    private static int shiftLightness(int argb, float delta) {
        float r = ((argb >>> 16) & 0xFF) / 255f, g = ((argb >>> 8) & 0xFF) / 255f, b = (argb & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f, chroma = max - min;
        float s = chroma == 0f ? 0f : chroma / (1f - Math.abs(2f * l - 1f));
        float h;
        if (chroma == 0f) h = 0f;
        else if (max == r) h = ((g - b) / chroma + 6f) % 6f;
        else if (max == g) h = (b - r) / chroma + 2f;
        else h = (r - g) / chroma + 4f;

        l = Math.clamp(l + delta, 0f, 1f);
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float x = c * (1f - Math.abs(h % 2f - 1f));
        float m = l - c / 2f;
        float[] rgb = switch ((int) h) {
            case 0 -> new float[]{c, x, 0};
            case 1 -> new float[]{x, c, 0};
            case 2 -> new float[]{0, c, x};
            case 3 -> new float[]{0, x, c};
            case 4 -> new float[]{x, 0, c};
            default -> new float[]{c, 0, x};
        };
        return (argb & 0xFF000000) | Math.round((rgb[0] + m) * 255f) << 16 | Math.round((rgb[1] + m) * 255f) << 8 | Math.round((rgb[2] + m) * 255f);
    }

    /** 部品の背景と印の色（Chromium のライトテーマ）。 */
    private static final int NATIVE_WHITE = 0xFFFFFFFF;
    /** 暗い配色の部品の色（Chromium のダークテーマ）。 */
    private static final int NATIVE_DARK = 0xFF3B3B3B;
    /** チェックボックスの角の半径（Chromium の既定、CSS px）。 */
    private static final int NATIVE_CHECKBOX_RADIUS = 2;

    /**
     * レンジ（Chromium の NativeThemeBase::PaintSliderTrack / PaintSliderThumb と同じ形）。border box いっぱいを使い、
     * 縦の中央に高さ 8px・角丸の溝（#efefef）を置き、値までを強調色で塗り、溝全体に 1px の半透明の枠（#767676 の 50%）を重ね、
     * 直径 16px の強調色の円をつまみにする。つまみの中心は両端から 8px の間を動く。
     * 強調色は ua.css の状態ごとの色（StyleResolver が枠の色に置いたもの）か {@code accent-color}。
     * {@code accent-color} が溝の色と見分けにくい（コントラスト比 3 未満）なら、Chrome と同じく溝を暗い配色（#3b3b3b、枠 #cfcfcf の 50%）にする。
     * {@code track} が偽ならつまみだけ（appearance: none）。
     *
     * 何が壊れていたか: 以前は溝の太さを箱の高さの 30%、つまみの直径を 80% にしていて、溝が細く、つまみの位置も
     * 端から半径の分だけ内側を動く Chrome とずれていた。作者の背景と枠線もそのまま描いていた。
     */
    private void drawNativeRange(List<DisplayItem> elements, LayoutBox box, String id, String rangeData, int color, boolean track,
                                 Map<String, String> partsEvents, BorderSpec border, RenderMeta baseMeta,
                                 int transDur, AnimationSpec colorBgAnim) {
        Integer accent = accentColor(box, color);
        boolean dark = accent != null && contrastRatio(accent, RANGE_TRACK_LIGHT) < 3f;
        int strong = accent != null ? shadeAccent(accent, border.bcTop(), dark) : border.bcTop();
        int fill = !track ? 0x00000000 : dark ? RANGE_TRACK_DARK : RANGE_TRACK_LIGHT;
        int edge = dark ? RANGE_TRACK_BORDER_DARK : RANGE_TRACK_BORDER_LIGHT;
        RoundedBorderSpec trackBorder = track ? new RoundedBorderSpec(1f, 1f, 1f, 1f, edge, edge, edge, edge) : null;
        RenderMeta meta = new RenderMeta(baseMeta.clipRect(), null, null, null, baseMeta.fontHeight(), 0f, 0f,
                baseMeta.originX(), baseMeta.originY(), 1, false, trackBorder);
        addElement(elements, id, id + "_input", ElementType.RANGE, box.x, box.y, box.w, box.h, rangeData, strong, fill,
                partsEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, meta);
    }

    /** 溝の色と枠の色（Chrome の実測: 枠は溝の上で #b2b2b2・値の上で #3b75ba になる 50% の #767676。暗い配色は溝の上で #858585）。 */
    private static final int RANGE_TRACK_LIGHT = 0xFFEFEFEF, RANGE_TRACK_BORDER_LIGHT = 0x80767676;
    private static final int RANGE_TRACK_DARK = 0xFF3B3B3B, RANGE_TRACK_BORDER_DARK = 0x80CFCFCF;

    /** レンジの値の文字列（{@code 値:最小:最大}）。 */
    private static String rangeData(LayoutBox box, String val) {
        String min = box.node.attrs.getOrDefault("min", "0");
        String max = box.node.attrs.getOrDefault("max", "100");
        if (val.isEmpty()) val = "50";
        int colonIdx = val.indexOf(':');
        if (colonIdx > 0) val = val.substring(0, colonIdx);
        return val + ":" + min + ":" + max;
    }

    /** WCAG 2 のコントラスト比（1〜21）。 */
    private static float contrastRatio(int a, int b) {
        double la = relativeLuminance(a), lb = relativeLuminance(b);
        return (float) ((Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05));
    }

    private static double relativeLuminance(int argb) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = ((argb >>> (16 - 8 * i)) & 0xFF) / 255.0;
            c[i] = v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    /** {@code appearance: none} のレンジ。 */
    private static boolean isPlainRange(LayoutBox box) {
        return "range".equalsIgnoreCase(box.node.attrs.getOrDefault("type", "").trim())
                && !StyleResolver.isNativeRange(box.node, box.style.asMap());
    }

    /** {@code appearance: none} のチェックボックス・ラジオボタン。 */
    private static boolean isPlainToggle(LayoutBox box) {
        String type = box.node.attrs.getOrDefault("type", "").trim().toLowerCase(Locale.ROOT);
        return (type.equals("checkbox") || type.equals("radio")) && !StyleResolver.isNativeToggle(box.node, box.style.asMap());
    }

    /** {@code accent-color}。auto（UA の既定）なら null。 */
    private static Integer accentColor(LayoutBox box, int textColor) {
        String v = box.style.get(CssProperty.ACCENT_COLOR);
        if (v == null) return null;
        v = v.trim();
        if (v.isEmpty() || "auto".equalsIgnoreCase(v)) return null;
        if ("currentcolor".equalsIgnoreCase(v)) return textColor;
        return ColorParser.isColor(v) ? ColorParser.parseColor(v) : null;
    }

    private void drawBorderedControl(List<DisplayItem> elements, String id, String suffix, ElementType type, LayoutBox box,
                                     float innerX, float innerY, float innerW, float innerH, String text, int color, int bgColor, int fallbackRadius,
                                     Map<String, String> partsEvents, Map<String, String> innerEvents, Map<String, String> passiveBorderEvents,
                                     BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                     int transDur, AnimationSpec colorBgAnim) {
        if (border.useRoundedBorder()) {
            drawRoundedBorder(elements, id, box, passiveBorderEvents, border, baseMeta, transDur);
            addElement(elements, id, id + suffix, type, innerX, innerY, innerW, innerH, text, color, bgColor, innerEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, innerMeta);
        } else {
            addElement(elements, id, id + suffix, type, box.x, box.y, box.w, box.h, text, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }
    }

    /**
     * fieldset の背景と枠線（HTML §15.3.13）。legend が上の枠線より高ければ、枠線を legend の中央の高さへ下げ、
     * 背景もそこから塗る。枠線は legend の後ろ（legend の border box の横幅）を描かない。
     *
     * 下げた矩形で描くために、描く間だけ箱の y と高さを差し替える（背景・画像・内側の影・枠線の描き方は
     * どれも箱の矩形を読むので、普通の箱と同じ道を通す）。
     */
    private void drawFieldsetElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                     Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                     Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                     BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                     int transDur, AnimationSpec colorBgAnim, List<BoxDecorations.Shadow> shadows) {
        LayoutBox legend = box.renderedLegend();
        float shift = legend == null ? 0 : Math.max(0, (legend.h - box.bT) / 2);
        float y = box.y, h = box.h;
        box.y += shift;
        box.h = Math.max(0, h - shift);
        try {
            drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy,
                    partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
        } finally {
            box.y = y;
            box.h = h;
        }
    }

    private void drawDefaultBlockElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor, int fallbackRadius,
                                         Corners radii, ClipRect normalizedClip, float fontHVal, float ox, float oy,
                                         Map<String, String> partsEvents, Map<String, String> passiveBorderEvents,
                                         BorderSpec border, RenderMeta baseMeta, RenderMeta innerMeta, float rOff,
                                         int transDur, AnimationSpec colorBgAnim, List<BoxDecorations.Shadow> shadows) {
        ElementType bgType = ElementType.RECT;
        if ("button".equalsIgnoreCase(box.node.tag)) {
            bgType = ElementType.BUTTON;
        } else if ("a".equalsIgnoreCase(box.node.tag)) {
            bgType = ElementType.A;
        }
        boolean isInteractiveBox = bgType != ElementType.RECT;

        if (border.isCircle() && border.hasBorder()) {
            int thickness = border.maxThickness();
            String bData = thickness + ":" + border.bcTop() + "," + border.bcRight() + "," + border.bcBottom() + "," + border.bcLeft();
            CircleBorderSpec cbSpec = new CircleBorderSpec(thickness, border.bcTop(), border.bcRight(), border.bcBottom(), border.bcLeft());
            if (bgColor != 0x00000000 || !partsEvents.isEmpty() || isInteractiveBox || colorBgAnim.animates(AnimationSpec.CH_BG_COLOR)) {
                addElement(elements, id, id + "_bg", bgType, box.x, box.y, box.w, box.h, "", color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            }
            drawBackgroundImages(elements, box, id, normalizedClip, radii, border, passiveBorderEvents, fontHVal, ox, oy);
            drawInsetShadows(elements, box, id, shadows, border, normalizedClip, fontHVal, ox, oy);
            addElement(elements, id, id + "_cb", ElementType.CIRCLE_BORDER, box.x, box.y, box.w, box.h, bData, 0, 0, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE,
                    new RenderMeta(normalizedClip, radii, null, cbSpec, fontHVal, ox, oy, 1, false));
        } else {
            // background-clip が border-box でなければ、色は箱の内側だけに塗る。_bg はヒットテストと
            // 状態の受け口なので箱いっぱいのまま残し、色だけを切った矩形に移す
            String colorClip = BoxDecorations.colorClip(box.style);
            int bgFill = bgColor;
            if (bgColor != 0x00000000 && !"border-box".equals(colorClip)) {
                bgFill = 0x00000000;
                BoxDecorations.Rect area = BoxDecorations.boxArea(box, colorClip);
                Corners areaRadii = "padding-box".equals(colorClip) ? border.innerRadii() : null;
                addElement(elements, id, id + "_bgl" + BoxDecorations.layers(box.style).size() + "_0", ElementType.RECT,
                        area.x(), area.y(), area.w(), area.h(), "", 0, bgColor, passiveBorderEvents, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE,
                        new RenderMeta(normalizedClip, areaRadii, null, null, fontHVal, ox, oy, 1, false));
            }
            // 角丸の箱でも背景は枠線ボックスいっぱい（枠線の下まで）に塗り、枠線をその上に重ねる（付録 E）
            if (bgFill != 0x00000000 || !partsEvents.isEmpty() || isInteractiveBox || colorBgAnim.animates(AnimationSpec.CH_BG_COLOR)) {
                addElement(elements, id, id + "_bg", bgType, box.x, box.y, box.w, box.h, "", color, bgFill, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta);
            }
            drawBackgroundImages(elements, box, id, normalizedClip, radii, border, passiveBorderEvents, fontHVal, ox, oy);
            drawInsetShadows(elements, box, id, shadows, border, normalizedClip, fontHVal, ox, oy);
            drawBorder(elements, id, box, rOff, passiveBorderEvents, border, baseMeta, transDur);
        }

        if (box.node.attrs.containsKey("data-ghost-item")) {
            String ghostItem = box.node.attrs.get("data-ghost-item").trim();
            if (!ghostItem.isEmpty() && !"minecraft:air".equals(ghostItem)) {
                float iconX = box.x + (box.w - 16) / 2;
                float iconY = box.y + (box.h - 16) / 2;
                addElement(elements, id, id + "_ghost_img", ElementType.IMAGE, ImageFitMode.FILL, iconX, iconY, 16, 16, "item:" + ghostItem, color, 0, partsEvents, 0, 0.85f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, innerMeta);
            }
        }
    }

    /**
     * 背景の画像レイヤー（{@code url()} とグラデーション）。リストの末尾がいちばん下なので後ろから描く。
     * 各レイヤーは {@code background-origin} の箱を基準にタイルを並べ、{@code background-clip} の箱で切る。
     *
     * 何が壊れていたか: 以前は背景を色 1 つと 2 色のグラデーション 1 つしか持てず、
     * {@code background-image: url()} は描かれず、{@code background-size / position / repeat / clip / origin} も
     * 読んでいなかった。
     *
     * グラデーションは {@link Gradients#strips} が矩形と 4 隅の色に分け、矩形ごとに 1 部品にする。
     * タイルと帯は描画領域の角丸のクリップで切るので、小さいタイルも角の丸みに沿う。
     *
     * LIMITATION(制約): {@code background-attachment} は常に scroll、インラインボックスの断片には画像を描かない。
     * README の "Not supported" に対応。
     */
    private void drawBackgroundImages(List<DisplayItem> elements, LayoutBox box, String id, ClipRect clip, Corners radii,
                                      BorderSpec border, Map<String, String> events, float fontHVal, float ox, float oy) {
        List<BoxDecorations.BackgroundLayer> layers = BoxDecorations.layers(box.style);
        for (int i = layers.size() - 1; i >= 0; i--) {
            BoxDecorations.BackgroundLayer layer = layers.get(i);
            String url = layer.url();
            Gradients.Gradient gradient = layer.isGradient() ? Gradients.parse(layer.image()) : null;
            if (url == null && gradient == null) continue;

            int[] intrinsic = url != null && UiDocumentCompiler.intrinsicSizeResolver != null ? UiDocumentCompiler.intrinsicSizeResolver.getSize(url) : null;
            BoxDecorations.Rect paint = BoxDecorations.boxArea(box, layer.clip());
            Corners paintRadii = "border-box".equals(layer.clip()) ? radii : border.innerRadii();
            ClipRect layerClip = intersectClip(clip, paint, paintRadii, id);
            RenderMeta imageMeta = new RenderMeta(layerClip, null, null, null, fontHVal, ox, oy, 1, false);

            List<BoxDecorations.Rect> tiles = BoxDecorations.tiles(layer, box, intrinsic, rootW, rootH,
                    box.style.fontSizePx(), box.style.rootFontSizePx());
            int part = 0;
            for (BoxDecorations.Rect t : tiles) {
                if (url == null) {
                    // 2 色だけの線形・放射は、シェーダーが画素ごとに計算して 1 枚で描く（帯・格子の継ぎ目や段が出ない）
                    String shaderGradient = Gradients.shaderGradient(gradient, t, rootW, rootH,
                            box.style.fontSizePx(), box.style.rootFontSizePx());
                    if (shaderGradient != null) {
                        float gx0 = Math.max(t.x(), paint.x()), gy0 = Math.max(t.y(), paint.y());
                        float gx1 = Math.min(t.x() + t.w(), paint.x() + paint.w()), gy1 = Math.min(t.y() + t.h(), paint.y() + paint.h());
                        if (gx1 <= gx0 || gy1 <= gy0) continue;
                        Map<String, String> gradientEvents = new HashMap<>(events);
                        gradientEvents.put(RenderKeys.SHADER_GRADIENT, shaderGradient);
                        addElement(elements, id, id + "_bgl" + i + "_" + (part++), ElementType.RECT, gx0, gy0, gx1 - gx0, gy1 - gy0, "",
                                0, 0, gradientEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, imageMeta);
                        continue;
                    }
                }
                if (url != null) {
                    addElement(elements, id, id + "_bgl" + i + "_" + (part++), ElementType.IMAGE, ImageFitMode.FILL, t.x(), t.y(), t.w(), t.h(), url,
                            0, 0, events, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, imageMeta);
                    continue;
                }
                for (Gradients.GradientStrip strip : Gradients.strips(gradient, t, paint, rootW, rootH,
                        box.style.fontSizePx(), box.style.rootFontSizePx())) {
                    GradientSpec spec = new GradientSpec(strip.topLeft(), strip.topRight(), strip.bottomRight(), strip.bottomLeft());
                    RenderMeta meta = new RenderMeta(layerClip, null, spec, null, fontHVal, ox, oy, 1, false);
                    // タイルの外縁にあたる辺だけアンチエイリアスをかける（隣の帯と接する辺にかけると継ぎ目に線が出る）
                    int edges = (strip.x() <= t.x() + 0.01f ? 1 : 0) | (strip.y() <= t.y() + 0.01f ? 2 : 0)
                            | (strip.x() + strip.w() >= t.x() + t.w() - 0.01f ? 4 : 0)
                            | (strip.y() + strip.h() >= t.y() + t.h() - 0.01f ? 8 : 0);
                    Map<String, String> stripEvents = events;
                    if (edges != 15) {
                        stripEvents = new HashMap<>(events);
                        stripEvents.put(RenderKeys.EDGE_MASK, Integer.toString(edges));
                    }
                    addElement(elements, id, id + "_bgl" + i + "_" + (part++), ElementType.RECT, strip.x(), strip.y(), strip.w(), strip.h(), "",
                            0, strip.topLeft(), stripEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, meta);
                }
            }
        }
    }

    /**
     * 描画領域（角丸 {@code r}）で親のクリップを狭める（{@link BoxDecorations#intersectClip}）。
     * 領域は要素 {@code ownerId} 自身の箱なので、その要素の変形に矩形も付いていく（{@link ClipRect#ownerId()}）。
     */
    private static ClipRect intersectClip(ClipRect parent, BoxDecorations.Rect area, Corners r, String ownerId) {
        float[] own = {area.x(), area.y(), area.x() + area.w(), area.y() + area.h(), 0, 0, 0, 0};
        if (r != null) {
            own[4] = r.topLeft();
            own[5] = r.topRight();
            own[6] = r.bottomRight();
            own[7] = r.bottomLeft();
        }
        return ClipRect.fromArray(BoxDecorations.intersectClip(parent == null ? null : parent.toArray(), own)).withOwner(ownerId);
    }

    /** 角丸なら枠の領域だけを塗る 1 部品、そうでなければ辺ごとの帯。 */
    private void drawBorder(List<DisplayItem> elements, String id, LayoutBox box, float rOff, Map<String, String> events,
                            BorderSpec border, RenderMeta meta, int transDur) {
        LayoutBox legend = "fieldset".equals(box.node.tag) ? box.renderedLegend() : null;
        if (legend != null && border.hasBorder()) {
            drawBorderAroundLegend(elements, id, box, legend, rOff, events, border, meta, transDur);
            return;
        }
        if (border.useRoundedBorder()) drawRoundedBorder(elements, id, box, events, border, meta, transDur);
        else if (border.hasBorder()) drawStraightBorders(elements, id, box, rOff, events, border, meta, transDur);
    }

    /**
     * legend の後ろを空けた fieldset の枠線。空ける矩形は、横が legend の border box、縦が枠線の上端から
     * legend の下マージンの端と枠線の下端の低い方まで（HTML §15.3.13）。描画は矩形の切り抜きを持たないので、
     * 空ける矩形の左・右・下の 3 つのクリップで同じ枠線を描く（クリップは重ならないので半透明の色も二重にならない）。
     */
    private void drawBorderAroundLegend(List<DisplayItem> elements, String id, LayoutBox box, LayoutBox legend, float rOff,
                                        Map<String, String> events, BorderSpec b, RenderMeta meta, int transDur) {
        float left = legend.x, right = legend.x + legend.w;
        float bottom = Math.max(legend.y + legend.h + legend.mB, box.y + b.bwTop());
        float x0 = box.x - 1, y0 = box.y - 1, x1 = box.x + box.w + 1, y1 = box.y + box.h + 1;
        float[][] regions = {{x0, y0, left, y1}, {right, y0, x1, y1}, {left, bottom, right, y1}};
        float[] widths = {b.bwTop(), b.bwRight(), b.bwBottom(), b.bwLeft()};
        int[] colors = {b.bcTop(), b.bcRight(), b.bcBottom(), b.bcLeft()};
        for (int i = 0; i < regions.length; i++) {
            if (regions[i][2] <= regions[i][0] || regions[i][3] <= regions[i][1]) continue;
            ClipRect clip = ClipRect.fromArray(BoxDecorations.intersectClip(
                    meta.clipRect() == null ? null : meta.clipRect().toArray(), regions[i]));
            if (b.useRoundedBorder()) {
                RoundedBorderSpec spec = new RoundedBorderSpec(widths[0], widths[1], widths[2], widths[3],
                        colors[0], colors[1], colors[2], colors[3]);
                RenderMeta ringMeta = new RenderMeta(clip, meta.borderRadii(), null, null,
                        meta.fontHeight(), 0f, 0f, meta.originX(), meta.originY(), 1, false, spec);
                addElement(elements, id, id + "_b_round_" + i, ElementType.ROUNDED_BORDER, box.x, box.y, box.w, box.h, "", 0, 0x00000000,
                        events, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta);
            } else {
                RenderMeta clipped = new RenderMeta(clip, meta.borderRadii(), null, null,
                        meta.fontHeight(), meta.originX(), meta.originY(), 1, false);
                drawLines(elements, id, "_b_", box.x, box.y, box.w, box.h, widths, b.styles(), colors, rOff,
                        events, clipped, transDur, true);
            }
        }
    }

    /**
     * 角丸の枠線。枠の領域（外側の角丸矩形 − パディングボックス）だけを塗る部品 {@code <id>_b_round} を 1 つ出す。
     * 片への分割は描画側が {@link BoxDecorations#roundedBorderSpans} で行う。
     *
     * 何が壊れていたか: 以前は箱全体を枠の色で塗った下地（{@code _bbg}）に内側の背景を重ねていたので、
     * 背景が透明な角丸の箱（{@code background: transparent; border: 1px solid; border-radius: 4px}）は
     * 中身ごと枠の色で塗りつぶされ、半透明の背景は枠の色と混ざっていた。
     */
    private void drawRoundedBorder(List<DisplayItem> elements, String id, LayoutBox box, Map<String, String> events,
                                   BorderSpec b, RenderMeta meta, int transDur) {
        RoundedBorderSpec spec = new RoundedBorderSpec(b.bwTop(), b.bwRight(), b.bwBottom(), b.bwLeft(),
                b.bcTop(), b.bcRight(), b.bcBottom(), b.bcLeft());
        RenderMeta ringMeta = new RenderMeta(meta.clipRect(), meta.borderRadii(), null, null,
                meta.fontHeight(), 0f, 0f, meta.originX(), meta.originY(), 1, false, spec);
        addElement(elements, id, id + "_b_round", ElementType.ROUNDED_BORDER, box.x, box.y, box.w, box.h, "", 0, 0x00000000,
                events, 0, 1f, 0, 0, 1f, 1f, 0, transDur, AnimationSpec.NONE, ringMeta);
    }

    private void drawStraightBorders(List<DisplayItem> elements, String id, LayoutBox box, float rOff, Map<String, String> partsEvents, BorderSpec b, RenderMeta meta, int transDur) {
        float[] widths = {b.bwTop(), b.bwRight(), b.bwBottom(), b.bwLeft()};
        int[] colors = {b.bcTop(), b.bcRight(), b.bcBottom(), b.bcLeft()};
        drawLines(elements, id, "_b_", box.x, box.y, box.w, box.h, widths, b.styles(), colors, rOff, partsEvents, meta, transDur);
    }

    /**
     * 矩形の内側に沿った 4 辺の線（枠線と輪郭線で共有）。辺ごとに線種で片に分ける（{@link BoxDecorations}）。
     * 上下の辺は幅いっぱい、左右の辺は高さいっぱいの帯で、角は重ねて塗る（従来どおり）。
     *
     * 片の id は {@code <id><prefix><辺>} と、2 つ目からは {@code _<番号>} を足したもの
     * （{@code getBaseNodeId} がどちらも持ち主の id に戻す）。
     */
    private void drawLines(List<DisplayItem> elements, String id, String prefix, float x, float y, float w, float h,
                           float[] widths, String[] styles, int[] colors, float rOff,
                           Map<String, String> events, RenderMeta meta, int transDur) {
        drawLines(elements, id, prefix, x, y, w, h, widths, styles, colors, rOff, events, meta, transDur, false);
    }

    /** {@code numbered} なら片の id をすべて番号付きにする（1 つの箱が複数の矩形に線を描くインラインの断片）。 */
    private void drawLines(List<DisplayItem> elements, String id, String prefix, float x, float y, float w, float h,
                           float[] widths, String[] styles, int[] colors, float rOff,
                           Map<String, String> events, RenderMeta meta, int transDur, boolean numbered) {
        float[][] bands = {
                {x + rOff, y, w - rOff * 2, widths[0]},
                {x + w - widths[1], y + rOff, widths[1], h - rOff * 2},
                {x + rOff, y + h - widths[2], w - rOff * 2, widths[2]},
                {x, y + rOff, widths[3], h - rOff * 2},
        };
        BoxDecorations.Side[] sides = BoxDecorations.Side.values();
        // 帯の両端に接する辺（上下は左右、左右は上下）
        float[][] insets = {{widths[3], widths[1]}, {widths[0], widths[2]}, {widths[3], widths[1]}, {widths[0], widths[2]}};
        for (int i = 0; i < 4; i++) {
            if (widths[i] <= 0) continue;
            float[] band = bands[i];
            List<BoxDecorations.Segment> segments = BoxDecorations.sideSegments(styles[i], sides[i],
                    band[0], band[1], band[2], band[3], insets[i][0], insets[i][1]);
            String base = id + prefix + BORDER_DIRECTIONS[i];
            for (int k = 0; k < segments.size(); k++) {
                BoxDecorations.Segment seg = segments.get(k);
                int radius = seg.round() ? Math.round(Math.min(seg.w(), seg.h()) / 2f) : 0;
                Corners corners = radius > 0 ? new Corners(radius, radius, radius, radius) : null;
                RenderMeta segMeta = new RenderMeta(meta.clipRect(), corners, null, null,
                        meta.fontHeight(), meta.originX(), meta.originY(), 1, false);
                String partId = numbered ? base + "_" + (fragIdCounter++) : k == 0 ? base : base + "_" + k;
                addElement(elements, id, partId, ElementType.RECT, seg.x(), seg.y(), seg.w(), seg.h(),
                        "", 0, BoxDecorations.shade(colors[i], seg.shade()), events, radius, 1f, 0, 0, 1f, 1f, 0, transDur,
                        AnimationSpec.NONE, segMeta);
            }
        }
    }

    /** 角丸の半径は描画側の整数値（{@link Corners}）なので、解いた長さをここで丸める。 */
    private static int[] roundRadii(float[] radii) {
        int[] rounded = new int[radii.length];
        for (int i = 0; i < radii.length; i++) rounded[i] = Math.round(radii[i]);
        return rounded;
    }

    private static ClipRect normalizeClipRect(float[] clipRect) {
        if (clipRect == null) return null;
        return ClipRect.fromArray(clipRect);
    }

    private static float parseFloatAttr(Map<String, String> map, String key, float fallback) {
        String val = map.get(key);
        if (val == null || val.isEmpty()) return fallback;
        try {
            return Float.parseFloat(val.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record TransitionSpec(int durationMs, int delayMs, String easing, String property) {}

    private record BorderSpec(
            boolean hasBorder, boolean isCircle, boolean useRoundedBorder,
            float bwTop, float bwRight, float bwBottom, float bwLeft,
            int bcTop, int bcRight, int bcBottom, int bcLeft,
            String[] styles, Corners innerRadii
    ) {
        int maxThickness() {
            return Math.round(Math.max(Math.max(bwTop, bwRight), Math.max(bwBottom, bwLeft)));
        }
    }

    /** {@code box} は箱自身を描くタスクだけが持つ（スクロールバーは null）。 */
    private record RenderTask(LayoutBox box, Consumer<List<DisplayItem>> drawAction) {}
}