package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.BlockLayoutEngine;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.ReplacedElements;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.TextShaper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.model.*;
import com.nishiyu.enderframelib.core.css.model.TextAlign;
import java.util.concurrent.atomic.AtomicInteger;
import static com.nishiyu.enderframelib.core.paint.BoxAnimation.applyTransitionEvents;
import static com.nishiyu.enderframelib.core.paint.BoxAnimation.parseOriginProp;
import static com.nishiyu.enderframelib.core.paint.BoxAnimation.parseTransitionSpec;
import static com.nishiyu.enderframelib.core.paint.ControlPainter.isPlainRange;
import static com.nishiyu.enderframelib.core.paint.ControlPainter.isPlainToggle;
import static com.nishiyu.enderframelib.core.paint.ControlPainter.rangeData;
import static com.nishiyu.enderframelib.core.paint.PaintOrder.canvasBackgroundSource;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.alignOffset;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.isInvisible;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.normalizeClipRect;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.parseFloatAttr;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.putFontEvents;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.resolveLiveInputValue;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.roundRadii;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.paint.BoxAnimation.TransitionSpec;
import com.nishiyu.enderframelib.core.paint.BoxDecorationPainter.BorderSpec;

public class DisplayListBuilder {
    // 単位なしの 0（例: translate(-50%, 0)）も受け付けるため単位は省略可能（省略時は px 扱い）

    static volatile java.util.function.BooleanSupplier parentRelativeSource =
            () -> com.nishiyu.enderframelib.core.kernel.EngineFlags.PARENT_RELATIVE;

    public static void bindParentRelativeSource(java.util.function.BooleanSupplier source) {
        parentRelativeSource = source != null ? source : () -> true;
    }

    int rootW, rootH;

    private final Map<String, Keyframes> keyframes;

    final ScrollState scrollState;

    /** 要素の部品の id（{@code _txt_3} など）に振る通し番号。描画のたびに 0 から数える。 */
    final AtomicInteger fragIds = new AtomicInteger();
    private final BoxAnimation anim;
    final BoxDecorationPainter decor;
    private final ControlPainter controls;
    final PaintOrder order;

    /** 背景をキャンバスに譲った要素（html か body）。自分の箱には背景色を描かない。 */
    LayoutBox canvasBackgroundSource;
    /** 1 箱の未キャッシュ構築で使い回す ItemProps（DisplayItem 側には attrs Map / RenderHints だけが渡る）。 */
    private final ItemProps scratchAnchorProps = new ItemProps();
    private final ItemProps scratchPartsProps = new ItemProps();
    private final ItemProps scratchPassiveProps = new ItemProps();
    private final int[] scratchRawRadii = new int[4];

    /**
     * ビューポートの大きさが変わった（ウィンドウのリサイズ）。箱ごとの記憶は残す: ビューポート基準の単位（{@code vw} など）を
     * 使わない箱の項目は、ビューポートの大きさに依らないので、位置・寸法・スタイルが同じなら使い回せる
     * （断片の記憶の枠組みの鍵 {@code memoFrameKey}）。部分木の保持は箱の記憶と別の鍵なので、捨てる。
     */
    public void setViewport(int rootW, int rootH) {
        if (this.rootW == rootW && this.rootH == rootH) return;
        this.rootW = rootW;
        this.rootH = rootH;
        anim.setViewport(rootW, rootH);
        decor.setViewport(rootW, rootH);
        order.setViewport(rootW, rootH);
    }

    public DisplayListBuilder(int rootW, int rootH, Map<String, Keyframes> keyframes, ScrollState scrollState) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.keyframes = keyframes != null ? keyframes : Map.of();
        this.scrollState = scrollState;
        this.anim = new BoxAnimation(rootW, rootH, this.keyframes);
        this.decor = new BoxDecorationPainter(rootW, rootH, fragIds);
        this.controls = new ControlPainter(decor);
        this.order = new PaintOrder(rootW, rootH, scrollState, fragIds, this::drawBoxSelf,
                box -> PaintOrder.factsOf(box.style).hasAnimation()
                        && (anim.parseAnimationDef(box.style, box.w(), box.h(), 0, 0).animatedChannels() & AnimationSpec.CH_OPACITY) != 0);
        this.fragmentPaint = new FragmentPaint(this, order);
    }

    /** 直近に描画またはパッチされた完全な表示リスト（Segmented DisplayList）。 */
    List<DisplayItem> currentDisplayList;
    /** 全面の描画を断片の木で行う経路（Shortcut.FRAGMENT_BUILD）。 */
    private final FragmentPaint fragmentPaint;
    /** 直近の描画のルートボックス。 */
    LayoutBox currentRoot;

    /** 現在保持されている完全な表示リストを取得する（未描画時は null）。 */
    public List<DisplayItem> currentDisplayList() {
        return currentDisplayList;
    }

    /** 箱を描く順（スタッキングコンテキストの順）に並べた箱。 */
    public List<LayoutBox> paintOrder(LayoutBox root) {
        return fragmentPaint.paintOrder(root);
    }

public List<DisplayItem> render(LayoutBox box) {
    return render(box, false);
}

/**
 * {@code cullOffscreen} が true なら、画面（と余白）の外にある箱の項目は作らない。位置だけが変わるスクロールのように、
 * 直前に全件で描いた木と同じ中身のときに使う（部品の ID は箱ごとに数えるので、省いた箱が他の箱の ID に響かない）。
 * 変形・アニメーション・フィルターを持つ祖先の下は、動いて画面に入ってくるので省かない。
 */
private static final ItemProps EMPTY_PROPS = new ItemProps();
private DisplayItem[] flatBuf = Fragment.NONE;
private int lastFlatLen;
private final PaintSupport.CaptureList bgScratch = new PaintSupport.CaptureList(1);

DisplayItem[] ensureFlatBuf(int needed) {
    if (flatBuf.length < needed) flatBuf = Arrays.copyOf(flatBuf, Math.max(needed, flatBuf.length + (flatBuf.length >> 1) + 16));
    return flatBuf;
}

private void clearFlatTail(int total) {
    if (lastFlatLen > total) Arrays.fill(flatBuf, total, Math.min(lastFlatLen, flatBuf.length), null);
    lastFlatLen = total;
}

public ImmutableItemList renderImmutable(LayoutBox box, boolean cullOffscreen, ImmutableItemList prev) {
    long prof = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
    try {
        int total = renderToFlatBuf(box, cullOffscreen);
        ImmutableItemList out = ImmutableItemList.fromArray(prev, flatBuf, total);
        clearFlatTail(total);
        this.currentDisplayList = out;
        return out;
    } finally {
        StageProfiler.end(PipelineStage.DISPLAY_LIST, prof);
    }
}

public List<DisplayItem> render(LayoutBox box, boolean cullOffscreen) {
    long prof = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
    try {
        int total = renderToFlatBuf(box, cullOffscreen);
        List<DisplayItem> list = total == 0 ? new ArrayList<>() : new ArrayList<>(Arrays.asList(Arrays.copyOf(flatBuf, total)));
        clearFlatTail(total);
        this.currentDisplayList = list;
        return list;
    } finally {
        StageProfiler.end(PipelineStage.DISPLAY_LIST, prof);
    }
}

private int renderToFlatBuf(LayoutBox box, boolean cullOffscreen) {
    com.nishiyu.enderframelib.core.layout.PaintStamps.settle(box);
    buildEpoch = LayoutBox.paintEpoch();
    if (lastOwnerCount == 0 && com.nishiyu.enderframelib.core.kernel.Shortcut.WINDOWED.on()) lastOwnerCount = countBoxes(box, WINDOW_MIN_OWNERS);
    boolean windowed = !cullOffscreen && com.nishiyu.enderframelib.core.kernel.Shortcut.WINDOWED.on() && lastOwnerCount >= WINDOW_MIN_OWNERS;
    windowedBuild = windowed;
    if (windowed) STAT_WINDOWED_BUILDS.increment();
    boolean cull = cullOffscreen || windowed;
    currentRoot = box;
    boolean relMode = parentRelativeSource.getAsBoolean();
    this.parentRelative = relMode;
    order.setParentRelative(relMode);
    canvasBackgroundSource = null;
    decor.setDevicePixelRatio(box.devicePixelRatio());
    int base = 0;
    DisplayItem bgItem = null;
    if ("root".equals(box.node.tag)) {
        int canvasBg = DEFAULT_CANVAS_COLOR;
        LayoutBox source = canvasBackgroundSource(box);
        if (source != null) {
            canvasBg = ColorParser.parseColor(source.style.getOrDefault(CssPropertyIndex.BACKGROUND_COLOR, "transparent"));
            canvasBackgroundSource = source;
        }
        if (canvasBg != 0x00000000) {
            long bgRootKey = source != null && source.node != null ? DisplayItem.bgKeyOf(source.node.nodeId()) : 0L;
            bgScratch.resetForBuild();
            addElement(bgScratch, "bg_root", "bg_root", ElementType.RECT, box.x(), box.y(), Math.max(box.w(), rootW), Math.max(box.h(), rootH),
                    "", 0, canvasBg, EMPTY_PROPS, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, RenderMeta.EMPTY, bgRootKey);
            bgItem = bgScratch.get(0);
            base = 1;
        }
    }
    int total = fragmentPaint.paintInto(box, base, cull, windowed);
    if (base == 1) flatBuf[0] = bgItem;
    lastElementCount = total;
    lastOwnerCount = fragmentPaint.lastOwners();
    this.currentRoot = box;
    return total;
}

    private int lastOwnerCount;
    /** 窓付きで作った表示リストの数（検証用）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_WINDOWED_BUILDS = new java.util.concurrent.atomic.LongAdder();
    /** 直近の描画が窓付き（画面外の箱を省いた）か。 */
    boolean windowedBuild;
    /** 窓付きにする、項目を持ちうる箱の数の下限（これより小さい文書は全件を作る）。 */
    public static volatile int WINDOW_MIN_OWNERS = com.nishiyu.enderframelib.core.kernel.EngineFlags.WINDOW_MIN_OWNERS;
    /** 箱の数（{@code cap} に達したらそこで打ち切る）。 */
    private static int countBoxes(LayoutBox b, int cap) {
        int n = 1;
        for (LayoutBox c : b.children) {
            n += countBoxes(c, cap - n);
            if (n >= cap) return n;
        }
        return n;
    }

    /** 直近の表示リストが窓付き（画面外の箱の項目が無い）か。 */
    public boolean isWindowed() { return windowedBuild; }

    /** 画面の外へ表示項目を作っておく幅（px）。スクロールを節の移動で済ませる範囲（UiView）はこれより小さく取る。広いほど、作り直しが起きる間隔が長い。 */
    public static final float CULL_MARGIN = com.nishiyu.enderframelib.core.kernel.EngineFlags.CULL_MARGIN;
    static final int[] MOVING_PROPS = {
            CssPropertyIndex.TRANSFORM, CssPropertyIndex.TRANSLATE, CssPropertyIndex.ROTATE,
            CssPropertyIndex.SCALE, CssPropertyIndex.ANIMATION, CssPropertyIndex.ANIMATION_NAME, CssPropertyIndex.FILTER,
            CssPropertyIndex.PERSPECTIVE};

    /** 経路の観測口（箱ごとの {@code FragmentPaint.PATH_*}）。C7 の予測テスト用。 */
    public void setPathRecorder(java.util.function.ObjIntConsumer<LayoutBox> recorder) { fragmentPaint.setPathRecorder(recorder); }

    public static long fragmentOwnReused() { return FragmentPaint.OWN_REUSED.sum(); }

    public static long fragmentMemoHits() { return FragmentPaint.MEMO_HITS.sum(); } public static long fragmentMemoMisses() { return FragmentPaint.MEMO_MISSES.sum(); }

    /**
     * 断片の記憶の鍵のうち、描画の枠組み（省略・窓・親相対・平行移動の方式・ビューポート）。変わったら記憶は全部外れる。
     * 窓がスクロールの分だけずれている間は記憶を使えないので {@code Integer.MIN_VALUE}（毎回変わる値）を返す。
     */
    int memoFrameKey(boolean cull, boolean windowed) {
        if (!windowShift.isEmpty()) return Integer.MIN_VALUE;
        int k = (cull ? 1 : 0) | (windowed ? 2 : 0) | (parentRelative ? 4 : 0);
        return cull ? (k * 31 + rootW) * 31 + rootH : k;
    }

    /** 絶対位置 {@code (ax, ay)} を呼び出し側が持つ版（窓のずれ・断片・文字・無名の箱は元の判定）。 */
    boolean inWindowGeometry(LayoutBox box, float ax, float ay) {
        if (!windowShift.isEmpty() || box.isAnonymous || "#text".equals(box.node.tag) || !box.inlineFragments.isEmpty()) return inWindowGeometry(box);
        return ax + box.w() >= -CULL_MARGIN && ax <= rootW + CULL_MARGIN && ay + box.h() >= -CULL_MARGIN && ay <= rootH + CULL_MARGIN;
    }
    boolean inWindowGeometry(LayoutBox box) {
        LayoutBox at = box;
        while (at.parent != null && ("#text".equals(at.node.tag) || at.isAnonymous)) at = at.parent;
        float sx = 0, sy = 0;
        if (!windowShift.isEmpty()) {
            for (LayoutBox a = at.parent; a != null; a = a.parent) {
                float[] d = windowShift.get(a.node);
                if (d != null) { sx += d[0]; sy += d[1]; }
            }
        }
        List<LayoutBox.InlineFragment> frags = at.inlineFragments;
        if (frags != null && !frags.isEmpty()) {
            // 行に分かれた箱は、断片の範囲で判定する（A6b ⑤）。インラインの中の箱は箱自身の x / y が (0, 0) のまま（位置は断片にだけある）ことがあり、以前は窓の中と判定された
            LayoutBox op = at.originParent();
            float fxOff = (op != null ? op.x() : 0f) - sx, fyOff = (op != null ? op.y() : 0f) - sy;
            for (int i = 0, n = frags.size(); i < n; i++) {
                LayoutBox.InlineFragment f = frags.get(i);
                if (f.x + fxOff + f.w >= -CULL_MARGIN && f.x + fxOff <= rootW + CULL_MARGIN && f.y + fyOff + f.h >= -CULL_MARGIN && f.y + fyOff <= rootH + CULL_MARGIN) return true;
            }
            return false;
        }
        return at.x() - sx + at.w() >= -CULL_MARGIN && at.x() - sx <= rootW + CULL_MARGIN
                && at.y() - sy + at.h() >= -CULL_MARGIN && at.y() - sy <= rootH + CULL_MARGIN;
    }

    /** 箱の位置を決めたときから、スクロールコンテナが動いた分（節 → {dx, dy}）。窓の判定は、この分だけずらした位置で行う。 */
    private java.util.Map<com.nishiyu.enderframelib.core.dom.node.HtmlNode, float[]> windowShift = java.util.Map.of();

    /** 箱の位置を決め直さずに窓だけを動かして作り直すとき、スクロールの動いた分を渡す（終わったら null）。 */
    public void setWindowShift(java.util.Map<com.nishiyu.enderframelib.core.dom.node.HtmlNode, float[]> shift) {
        windowShift = shift != null ? shift : java.util.Map.of();
    }

    int lastElementCount;

    private static final Object CORNERS_MEMO_KEY = new Object();

    private static final class CornersMemo {
        float[] src;
        Corners corners;
    }

    /** 読み取り専用の丸め済み {@link Corners}。半径の配列（{@code LengthResolver} が有効な間は同じ実体）が変わらない間は作り直さない。 */
    private static Corners cornersShared(ComputedStyle style, float w, float h, float rootW, float rootH) {
        if (!LengthResolver.hasNonZeroCornerRadius(style)) return Corners.ZERO;
        float[] r = LengthResolver.cornerRadiiXYShared(style, w, h, rootW, rootH);
        CornersMemo memo = style.paintMemoSelf(CORNERS_MEMO_KEY, st -> new CornersMemo());
        if (memo.src != r) {
            memo.corners = Corners.ofFloatXY(r);
            memo.src = r;
        }
        return memo.corners;
    }

    /** 箱の {@code mask-image} のレイヤーを項目にして足す（背景画像と同じ手順。クリップは箱の外へは出さない）。 */
    void drawMask(List<DisplayItem> out, LayoutBox box) { fragIds.set(MASK_ID_BASE); drawMaskItems(out, box); } // 断片の描画用（ID の振り方は旧経路と同じ）

    private void drawMaskItems(List<DisplayItem> elements, LayoutBox box) {
        PaintSupport.CaptureList cap = (parentRelative && PaintOrder.boxHasOwnNode(box) && elements instanceof PaintSupport.CaptureList c) ? c : null;
        int prevOx = cap != null ? cap.originX : 0, prevOy = cap != null ? cap.originY : 0;
        if (cap != null) cap.setOrigin(Math.round(box.x()), Math.round(box.y()));
        try {
            Corners radii = cornersShared(box.axisStyle(), box.w(), box.h(), rootW, rootH);
            int[] rawRadii;
            if (radii == Corners.ZERO) {
                rawRadii = Corners.ZERO_RAW;
            } else {
                rawRadii = scratchRawRadii;
                rawRadii[0] = Math.min(radii.topLeft(), radii.topLeftV());
                rawRadii[1] = Math.min(radii.topRight(), radii.topRightV());
                rawRadii[2] = Math.min(radii.bottomRight(), radii.bottomRightV());
                rawRadii[3] = Math.min(radii.bottomLeft(), radii.bottomLeftV());
            }
            int color = box.style.color(CssPropertyIndex.COLOR, 0xFF000000);
            BorderSpec border = decor.parseBorderSpec(box, rawRadii, radii, color);
            float fontHVal = Math.max(1.0f, box.style.fontSizePx());
            decor.drawMaskLayers(elements, box, box.node.key(), null, radii, border, fontHVal, 0.5f, 0.5f);
            if (BoxDecorations.clipsText(box.style)) {
                // background-clip: text: 子孫の文字を白（不透明）で出す。これがマスクになり、箱の背景が文字の形だけに残る
                ItemProps textEvents = ItemProps.parent(box.node.key());
                textEvents.pointerEventsNone();
                suppressTextShadow = true;
                try {
                    maskText(elements, box, box.node.key(), textEvents);
                } finally {
                    suppressTextShadow = false;
                }
            }
        } finally {
            if (cap != null) cap.setOrigin(prevOx, prevOy);
        }
    }

    /** 文字の影を出さない（マスクに影が混ざらないように）。 */
    boolean suppressTextShadow;

    private void maskText(List<DisplayItem> elements, LayoutBox box, String id, ItemProps events) {
        for (LayoutBox child : box.children) {
            if (child.display().isNone()) continue;
            if ("#text".equals(child.node.tag)) {
                float fontH = Math.max(1f, child.style.fontSizePx());
                drawTextNodeElement(elements, child, id, 0xFFFFFFFF, 0, null, fontH, 0.5f, 0.5f, events, 0, AnimationSpec.NONE, null);
            } else {
                maskText(elements, child, id, events);
            }
        }
    }

    /** 錨の項目にレイヤーの指定を載せる。 */
    static DisplayItem withLayer(DisplayItem anchor, LayerSpec spec) {
        RenderMeta meta = anchor.meta() != null ? anchor.meta() : RenderMeta.EMPTY;
        RenderMeta withLayer = meta.withHints(meta.hints().withLayer(spec));
        return new DisplayItem(anchor.id(), anchor.baseNodeId(), anchor.type(), anchor.x(), anchor.y(), anchor.width(), anchor.height(),
                anchor.text(), anchor.color(), anchor.bgColor(), anchor.events(), anchor.radius(), anchor.opacity(),
                anchor.translateX(), anchor.translateY(), anchor.scaleX(), anchor.scaleY(), anchor.rotate(),
                anchor.transitionDuration(), anchor.animDef(), anchor.fitMode(), withLayer, anchor.key());
    }

    /**
     * 背景の指定が無い文書のキャンバス。ゲームの上に重ねて描く文書は iframe と同じ扱いで透明
     * （ブラウザが白で塗るのは最上位の文書だけ）。html / body はパーサが必ず補う（W7）ので、背景を書かない HUD の断片も透明になる。
     * 以前は ua.css が body を白で塗り、body の無い文書は #111111 で塗りつぶしていた。
     */
    private static final int DEFAULT_CANVAS_COLOR = 0x00000000;

    private static final int MASK_ID_BASE = 1_000_000;

    // ==========================================
    // 箱ごとの記憶（React.memo と同じ考え方）
    // ==========================================
    //
    // 箱の描画結果（表示項目）は、その箱の入力だけで決まる: 算出スタイル・位置と大きさ・文字の断片・属性・クリップ・
    // 親の id・祖先のスタイル（文字色のアニメーションが親を読む）。部品の ID は箱ごとに 0 から数えるので、
    // 他の箱の増減に左右されない。入力が前回と全部同じなら、前回の項目（不変）をそのまま出し、作り直さない。
    // 一致の判定は値の比較（署名のハッシュではない）なので、取り違えは起きない。確実に決まらない箱（表・フォーム部品・
    // SVG・srcset 付きの画像・動画など描画側が別の資源を読む箱）は、いつも描き直す。img（点陣画像）・progress・meter・
    // ホスト登録の置換要素は属性とスタイルだけで決まるので記憶する。

    boolean parentRelative = true;

    long buildEpoch;
    /** 診断: 描画順の分類用のスタイル値を新しく算出した回数の累計（キャッシュ退行の検出用）。 */
    public static long styleFactsComputed() {
        return PaintOrder.STAT_FACTS_COMPUTED.sum();
    }

    static boolean memoizable(LayoutBox box) {
        String tag = box.node.tag;
        if (tag == null) return false;
        if (tag.indexOf('-') >= 0) {
            // カスタム要素名（ハイフンを含む）。ホストが置換要素として登録したものは描画が属性とスタイルだけを読むので記憶できる。
            // 未登録のものは従来どおり除外する
            if (!ReplacedElements.isHost(tag)) return false;
        } else {
            switch (tag) {
                case "video", "audio", "svg", "canvas", "textarea", "select", "fieldset",
                     "legend", "table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th", "col", "colgroup" -> {
                    return false;
                }
                case "img" -> {
                    if (!imgMemoizable(box)) return false;
                }
                default -> { } // progress / meter は value・min・max（属性）だけで決まるので解禁
            }
        }
        if (box.collapsedBorders() != null || box.display() == null) return false;
        // 表の内側の種類（TABLE / TABLE_*）は記憶しない。名前の文字列を作らず enum で判定する（箱ごとに呼ばれる）
        return switch (box.display().inner()) {
            case FLOW, FLOW_ROOT, FLEX, GRID, NONE -> true;
            case TABLE, TABLE_ROW_GROUP, TABLE_ROW, TABLE_CELL, TABLE_CAPTION, TABLE_COLUMN -> false;
        };
    }

    /**
     * {@code img} の項目が箱の入力（属性・スタイル）だけで決まるか。{@code srcset} / {@code sizes} は表示領域と
     * 密度で候補を選ぶので記憶の署名に入らない。SVG は読み込みの完了で結果が変わる（届くまでは何も描かない）。
     */
    private static boolean imgMemoizable(LayoutBox box) {
        Map<String, String> attrs = box.node.attrs;
        if (attrs.containsKey("srcset") || attrs.containsKey("sizes")) return false;
        String src = box.style.getOrDefault(CssPropertyIndex.SRC, attrs.getOrDefault("src", ""));
        return !com.nishiyu.enderframelib.core.raster.svg.SvgImage.looksLikeSvg(src);
    }

    /** 直近の描画で、中身を 1 つの節にまとめたスクロールコンテナの記録（スクロールだけの更新で、表示リストを作り直さずに済ませる）。 */
    public List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> scrollContents() {
        return order.scrollContentInfos();
    }

    private void drawBoxSelf(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId, List<DisplayItem> prevItems) {
        drawBoxSelfMemo(elements, box, clipRect, parentId, prevItems);
        PaintOrder.ScrollContent content = order.scrollContentOf(box);
        if (content != null) {
            PaintSupport.CaptureList cap = (parentRelative && PaintOrder.boxHasOwnNode(box) && elements instanceof PaintSupport.CaptureList c) ? c : null;
            int prevOx = cap != null ? cap.originX : 0, prevOy = cap != null ? cap.originY : 0;
            if (cap != null) cap.setOrigin(Math.round(box.x()), Math.round(box.y()));
            try {
                order.addMovingNode(elements, content.contentId, box, null); // 箱の項目のあと・子孫の項目の前
            } finally {
                if (cap != null) cap.setOrigin(prevOx, prevOy);
            }
        }
    }

    private void drawBoxSelfMemo(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId, List<DisplayItem> prevItems) {
        fragIds.set(0); // 部品の ID は箱ごとに数える
        drawBoxSelfUncached(elements, box, clipRect, parentId, prevItems);
    }

    private static RenderMeta reuseMeta(RenderMeta prev, ClipRect clipRect, Corners borderRadii,
                                        float fontHeight, float lineHeight, float originX, float originY,
                                        int itemCount, boolean interactiveState, RenderHints hints) {
        if (prev != null && prev.circleBorder() == null && prev.roundedBorder() == null
                && prev.baseline() == 0f
                && Float.compare(prev.fontHeight(), fontHeight) == 0
                && Float.compare(prev.lineHeight(), lineHeight) == 0
                && Float.compare(prev.originX(), originX) == 0
                && Float.compare(prev.originY(), originY) == 0
                && prev.itemCount() == itemCount
                && prev.interactiveState() == interactiveState
                && java.util.Objects.equals(prev.clipRect(), clipRect)
                && java.util.Objects.equals(prev.borderRadii(), borderRadii)
                && java.util.Objects.equals(prev.hints(), hints)) {
            return prev;
        }
        return new RenderMeta(clipRect, borderRadii, null, fontHeight, 0f, lineHeight, originX, originY,
                itemCount, interactiveState, null, hints);
    }

    /**
     * @param prevItems この箱が前に出した項目（無ければ null）。メタ・ヒントを作り直さず使い回す候補。
     */
    private void drawBoxSelfUncached(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId, List<DisplayItem> prevItems) {
        Corners radii = cornersShared(box.axisStyle(), box.w(), box.h(), rootW, rootH);
        // 円しか持たない所（インラインの断片・円判定）には、横と縦の小さい方の円で近似した半径を渡す
        int[] rawRadii;
        if (radii == Corners.ZERO) {
            rawRadii = Corners.ZERO_RAW;
        } else {
            rawRadii = scratchRawRadii;
            rawRadii[0] = Math.min(radii.topLeft(), radii.topLeftV());
            rawRadii[1] = Math.min(radii.topRight(), radii.topRightV());
            rawRadii[2] = Math.min(radii.bottomRight(), radii.bottomRightV());
            rawRadii[3] = Math.min(radii.bottomLeft(), radii.bottomLeftV());
        }
        int fallbackRadius = radii.topLeft();
        float rOff = Math.min(fallbackRadius, Math.min(box.w(), box.h()) / 2);

        // 何が壊れていたか: 以前は 0x00000000 をオフホワイトに書き換えていたので、color: transparent の文字が
        // 白で描かれていた。color は継承するので、宣言の無い箱にも親か初期値（black）が届いている
        int color = box.style.color(CssPropertyIndex.COLOR, 0xFF000000);

        // 背景色と画像のレイヤーは別物（色はいちばん下）。background ショートハンドは算出の段で
        // ロングハンドに展開済み。キャンバスに背景を譲った要素は色を描かない
        int bgColor = box == canvasBackgroundSource ? 0x00000000
                : ColorParser.parseColor(box.style.getOrDefault(CssPropertyIndex.BACKGROUND_COLOR, "transparent"));

        String id = box.node.key();

        float opacity = box.style.number(CssPropertyIndex.OPACITY, 1.0f);
        float[] tr = anim.transformOf(box);
        float tx = tr[0] + parseFloatAttr(box.node.attrs, "data-pan-x", 0f);
        float ty = tr[1] + parseFloatAttr(box.node.attrs, "data-pan-y", 0f);
        float zoom = parseFloatAttr(box.node.attrs, "data-zoom", 1.0f);
        float sx = tr[2] * zoom;
        float sy = tr[3] * zoom;
        float rot = tr[4];

        TransitionSpec transSpec = parseTransitionSpec(box.style);
        AnimationSpec animDef = anim.parseAnimationDef(box.style, box.w(), box.h(), color, bgColor);
        AnimationSpec colorBgAnim = animDef.isEmpty() ? AnimationSpec.NONE : animDef.withChannels(AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR);
        int transDur = transSpec.durationMs();
        boolean interactive = box.hasInteractiveRules;

        float ox = 0.5f, oy = 0.5f;
        ItemProps anchorEvents = scratchAnchorProps.reset();
        if (parentId != null) anchorEvents.parentId(parentId);
        if (tr.length > 5 && tr[5] != 0f) anchorEvents.skew(tr[5]);
        applyTransitionEvents(anchorEvents, transSpec);
        if (box.style.containsKey(CssPropertyIndex.TRANSFORM_ORIGIN)) {
            List<String> parts = CssSyntax.words(box.style.get(CssPropertyIndex.TRANSFORM_ORIGIN).toLowerCase(Locale.ROOT));
            if (parts.size() > 0) ox = parseOriginProp(parts.get(0), box.w(), box.style.fontSizePx(), box.style.rootFontSizePx());
            if (parts.size() > 1) oy = parseOriginProp(parts.get(1), box.h(), box.style.fontSizePx(), box.style.rootFontSizePx());
        }

        // 算出済みの font-size をそのまま運ぶ。描画側は書体の em からの倍率に直す。
        // 以前は 16 倍した「倍率」を渡し、描画側が 12 で割るという二重の換算だった。
        float fontHVal = Math.max(1.0f, box.style.fontSizePx());
        int itemCount = (int) parseFloatAttr(box.node.attrs, "data-count", 1f);

        boolean pointerEventsNone = box.style.keywordOf(CssPropertyIndex.POINTER_EVENTS, CssKeywords.PointerEvents.class)
                == CssKeywords.PointerEvents.NONE;
        if (pointerEventsNone) anchorEvents.pointerEventsNone();

        boolean useRel = parentRelative && PaintOrder.boxHasOwnNode(box);
        if (useRel) anchorEvents.relativeOrigin();
        RenderMeta prevAnchorMeta = null, prevBaseMeta = null, prevInnerMeta = null;
        RenderHints prevAnchorHints = null, prevPartsHints = null, prevPassiveHints = null;
        Map<String, String> prevPartsAttrs = null;
        if (prevItems != null && !prevItems.isEmpty()) {
            DisplayItem m0 = prevItems.get(0);
            if (m0.type() == ElementType.NODE) {
                prevAnchorMeta = m0.meta();
                prevAnchorHints = m0.hints();
            }
            for (int mi = 1, mn = Math.min(prevItems.size(), 8); mi < mn; mi++) {
                DisplayItem item = prevItems.get(mi);
                RenderHints h = item.hints();
                if (h == null || h.shadowShape() != null || h.shadowBlur() != 0f || h.shaderGradient() != null) continue;
                if (h.pointerEventsNone() && !pointerEventsNone) {
                    if (prevPassiveHints == null) prevPassiveHints = h;
                } else {
                    if (prevBaseMeta == null) prevBaseMeta = item.meta();
                    else if (prevInnerMeta == null && item.meta() != null && item.meta() != prevBaseMeta
                            && item.meta().roundedBorder() == null && item.meta().circleBorder() == null
                            && h == RenderHints.NONE) {
                        prevInnerMeta = item.meta();
                    }
                    if (prevPartsHints == null) {
                        prevPartsHints = h;
                        prevPartsAttrs = item.events();
                    }
                }
            }
        }
        anchorEvents.hintsReuse(prevAnchorHints);
        ClipRect normalizedClip = clipRect;
        RenderMeta anchorMeta = reuseMeta(prevAnchorMeta, normalizedClip, radii, fontHVal, box.lineHeightPx(), ox, oy,
                itemCount, interactive, anchorEvents.hints());
        long nodeKey = box.node != null ? DisplayItem.nodeKeyOf(box.node.paintHandle()) : 0L;
        PaintSupport.CaptureList cap = (parentRelative && elements instanceof PaintSupport.CaptureList c) ? c : null;
        int prevOx = cap != null ? cap.originX : 0, prevOy = cap != null ? cap.originY : 0;
        if (cap != null) {
            LayoutBox pNode = order.effectiveParentNodeBox(box, parentId);
            cap.setOrigin(pNode != null ? Math.round(pNode.x()) : 0, pNode != null ? Math.round(pNode.y()) : 0);
        }
        try {
            addElement(elements, id, id, ElementType.NODE, box.x(), box.y(), box.w(), box.h(), "", color, bgColor, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, transDur, animDef, anchorMeta, nodeKey);

            // visibility: hidden の箱は場所だけを取る。錨（NODE）は子孫の変形と不透明度の親として残す
            // 何が壊れていたか: 以前は visibility を読んでおらず、hidden の箱が描かれクリックも受けていた
            if (isInvisible(box) || PaintSupport.hidesEmptyCell(box)) return;

            if (useRel && cap != null) cap.setOrigin(Math.round(box.x()), Math.round(box.y()));

            ItemProps partsEvents = ItemProps.populateForParts(scratchPartsProps, box, id, transSpec, prevPartsAttrs);
            if (pointerEventsNone) partsEvents.pointerEventsNone();
            partsEvents.reuseCandidate(prevPartsHints);
            if ("#text".equals(box.node.tag)) {
                drawTextNodeElement(elements, box, id, color, bgColor, normalizedClip, fontHVal, ox, oy, partsEvents, transDur, anim.textColorAnimation(box, animDef), prevItems);
                return;
            }
            ItemProps passiveBorderEvents = ItemProps.populateForPassiveBorder(scratchPassiveProps, id, nodeKey, transSpec);
            // 何が壊れていたか: 以前は pointer-events を部品に載せていなかったので、ヒットテストの
            // pointer-events: none の判定は常に偽だった
            if (pointerEventsNone) {
                passiveBorderEvents.pointerEventsNone();
            }
            passiveBorderEvents.reuseCandidate(prevPassiveHints != null ? prevPassiveHints : prevPartsHints);

            if (!box.inlineFragments.isEmpty() && !ElementTraits.isNonFragmented(box.node.tag) && !ReplacedElements.isHost(box.node.tag) && !BlockLayoutEngine.isAtomicInline(box)) {
                decor.drawInlineFragments(elements, box, id, color, bgColor, rawRadii, normalizedClip, fontHVal, ox, oy, itemCount, interactive,
                        partsEvents, passiveBorderEvents, transDur, colorBgAnim);
                return;
            }

            RenderMeta baseMeta = reuseMeta(prevBaseMeta, normalizedClip, radii, fontHVal, box.lineHeightPx(), ox, oy,
                    itemCount, interactive, partsEvents.hints());

            List<BoxDecorations.Shadow> shadows = BoxDecorations.parseBoxShadows(box.style.get(CssPropertyIndex.BOX_SHADOW), color,
                    rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
            decor.drawOuterShadows(elements, box, id, shadows, radii, normalizedClip, fontHVal, ox, oy);

            BorderSpec border = decor.parseBorderSpec(box, rawRadii, radii, color);
            RenderMeta innerMeta = border.innerRadii().equals(radii)
                    ? baseMeta
                    : reuseMeta(prevInnerMeta, normalizedClip, border.innerRadii(), fontHVal, box.lineHeightPx(), ox, oy,
                            itemCount, interactive, RenderHints.NONE);

            switch (box.node.tag) {
                case "img", "video", "audio" -> controls.drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "progress", "meter" -> {
                    String val = box.node.attrs.getOrDefault("value", "0");
                    String min = box.node.attrs.getOrDefault("min", "0");
                    String max = box.node.attrs.getOrDefault("max", "100");
                    // 何が壊れていたか: ここが RANGE を出していたため、progress / meter が
                    // つまみ付きのスライダーとして描かれていた。進捗バーの描画実装
                    // (ControlElementRenderer.renderProgressElement) は呼ばれない死にコードだった。
                    long progressKey = box.node != null ? DisplayItem.keyOf(box.node.paintHandle(), DisplayItem.PART_PROGRESS) : 0L;
                    addElement(elements, id, id + "_progress", ElementType.PROGRESS, box.x(), box.y(), box.w(), box.h(), val + ":" + min + ":" + max, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta, progressKey);
                }
                case "svg" -> controls.drawSvgElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "canvas" -> controls.drawCanvasElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "textarea" -> controls.drawTextareaElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "select" -> controls.drawSelectElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "input" -> {
                    if (isPlainRange(box)) {
                        // appearance: none のレンジは作者の CSS で描いた箱に、つまみ（::-webkit-slider-thumb は部品のまま）だけを重ねる
                        decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows, prevItems);
                        controls.drawNativeRange(elements, box, id, rangeData(box, resolveLiveInputValue(id, box.node.attrs.getOrDefault("value", ""))),
                                color, false, partsEvents, border, baseMeta, transDur, colorBgAnim);
                    } else if (isPlainToggle(box)) {
                        // appearance: none のチェックボックス・ラジオボタンは普通の箱（作者の枠線・背景だけで描き、印は無い）
                        decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows, prevItems);
                    } else {
                        controls.drawInputElement(elements, box, id, color, bgColor, fallbackRadius, fontHVal, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                    }
                }
                case "fieldset" -> controls.drawFieldsetElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                default -> {
                    if (ReplacedElements.isHostScene(box.node.tag)) {
                        controls.drawSceneElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                    } else if (ReplacedElements.isHostImageLike(box.node.tag)) {
                        controls.drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                    } else {
                        decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows, prevItems);
                    }
                }
            }
            decor.drawFirstLineBackground(elements, box, id, color, partsEvents, innerMeta, transDur);
            decor.drawColumnRules(elements, box, id, color, passiveBorderEvents, baseMeta, transDur);
            decor.drawOutline(elements, box, id, color, passiveBorderEvents, baseMeta, transDur);
        } finally {
            if (cap != null) cap.setOrigin(prevOx, prevOy);
        }
    }

    /**
     * 文字 1 行（断片）を足す。{@code text-shadow} があれば、後ろに書いた影から先に（下に）、ずらした影色の文字を出してから本体を出す。
     * ぼかしのある影は、錨（NODE）に {@code blur} のレイヤーを載せ、その中に影の文字を置く（ぼかしの σ は半径の半分）。
     * 影はクリックを受けず、色のアニメーション・トランジションも受けない。
     */
    private void addTextRun(List<DisplayItem> elements, LayoutBox box, com.nishiyu.enderframelib.core.css.ComputedStyle effStyle,
                            String id, float x, float y, float w, float h, String text, int color, int bgColor,
                            ItemProps events, RenderMeta meta, int transDur, AnimationSpec colorAnim, List<BoxDecorations.Shadow> shadows) {
        if (effStyle != null && WritingModes.usesUpright(effStyle) && hasUprightChar(effStyle, text)) {
            addVerticalTextRuns(elements, box, effStyle, id, x, y, w, h, text, color, bgColor, events, meta, transDur, colorAnim, shadows);
            return;
        }
        emitTextRun(elements, box, effStyle, id, x, y, w, h, text, color, bgColor, events, meta, transDur, colorAnim, shadows, 0f);
    }

    private static boolean hasUprightChar(ComputedStyle style, String text) {
        WritingModes.Orientation o = WritingModes.orientationOf(style);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (WritingModes.isUpright(o, cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * 縦書きの文字 1 行（論理の横一列）を、寝かせたままの塊（ラテン文字など）と、正立させる字（かな・漢字・全角など。1 字ずつ）に分けて出す。
     * 縦書きの根は内容ごと 90° 回って描かれるので、正立させる字は自分の中心まわりに -90° 回して回転を打ち消す。
     * 各塊の幅は書体の送り幅で測り、行の幅 {@code w} に合うよう等倍して配る。
     */
    private void addVerticalTextRuns(List<DisplayItem> elements, LayoutBox box, com.nishiyu.enderframelib.core.css.ComputedStyle effStyle,
                                     String id, float x, float y, float w, float h, String text,
                                     int color, int bgColor, ItemProps events, RenderMeta meta, int transDur, AnimationSpec colorAnim,
                                     List<BoxDecorations.Shadow> shadows) {
        WritingModes.Orientation o = WritingModes.orientationOf(effStyle);
        List<String> runs = new ArrayList<>();
        List<Boolean> upright = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean curUpright = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            boolean up = WritingModes.isUpright(o, cp);
            if (cur.length() > 0 && (up != curUpright || up)) {
                runs.add(cur.toString());
                upright.add(curUpright);
                cur.setLength(0);
            }
            curUpright = up;
            cur.appendCodePoint(cp);
        }
        if (cur.length() > 0) {
            runs.add(cur.toString());
            upright.add(curUpright);
        }
        com.nishiyu.enderframelib.core.text.model.FontFace face =
                PaintServices.fontResolver().resolve(effStyle.get(CssPropertyIndex.FONT_FAMILY));
        com.nishiyu.enderframelib.core.text.model.FontStyle font = effStyle.layoutFacts().fontStyle;
        float[] adv = new float[runs.size()];
        float total = 0;
        for (int i = 0; i < adv.length; i++) {
            adv[i] = Math.max(0f, face.advance(runs.get(i), font));
            total += adv[i];
        }
        float scale = total > 0 ? w / total : 1f;
        float cx = x;
        for (int i = 0; i < adv.length; i++) {
            float rw = adv[i] * scale;
            emitTextRun(elements, box, effStyle, id, cx, y, rw, h, runs.get(i), color, bgColor, events, meta, transDur, colorAnim, shadows,
                    upright.get(i) ? (WritingModes.isTransposed(WritingModes.flowOf(effStyle)) ? 90f : -90f) : 0f);
            cx += rw;
        }
    }

    private void emitTextRun(List<DisplayItem> elements, LayoutBox box, com.nishiyu.enderframelib.core.css.ComputedStyle effStyle,
                             String id, float x, float y, float w, float h, String text, int color, int bgColor,
                             ItemProps events, RenderMeta meta, int transDur, AnimationSpec colorAnim, List<BoxDecorations.Shadow> shadows, float rot) {
        // vertical-lr は転置で置くので、文字は上下反転で鏡映を打ち消す
        float flipY = effStyle != null && WritingModes.isTransposed(WritingModes.flowOf(effStyle)) ? -1f : 1f;
        int nodeHandle = box.node != null ? box.node.paintHandle() : 0;
        for (int i = shadows.size() - 1; i >= 0; i--) {
            BoxDecorations.Shadow sh = shadows.get(i);
            if ((sh.color() >>> 24) == 0) continue;
            ItemProps shadowEvents = events.copy();
            shadowEvents.pointerEventsNone();
            boolean blurred = sh.blur() > 0f;
            if (blurred) {
                int anchorPart = fragIds.getAndIncrement();
                String anchorId = id + "_tsh" + anchorPart;
                long anchorKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, anchorPart) : 0L;
                ItemProps anchorEvents = new ItemProps();
                String owner = events.hints().parentId();
                if (owner != null) anchorEvents.parentId(owner);
                anchorEvents.pointerEventsNone();
                addElement(elements, id, anchorId, ElementType.NODE, x, y, w, h, "", 0, 0, anchorEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, meta, anchorKey);
                int at = elements.size() - 1;
                shadowEvents.parentId(anchorId);
                int shadowPart = fragIds.getAndIncrement();
                long shadowKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, shadowPart) : 0L;
                addElement(elements, id, id + "_txt_" + shadowPart, ElementType.TEXT, x + sh.offsetX(), y + sh.offsetY(), w, h, text,
                        sh.color(), 0, shadowEvents, 0, 1f, 0, 0, 1f, flipY, rot, 0, AnimationSpec.NONE, meta, shadowKey);
                LayerSpec spec = new LayerSpec(elements.size() - at, 1f, List.of(FilterOp.blur(sh.blur() / 2f)), BlendMode.NORMAL, false);
                elements.set(at, withLayer(elements.get(at), spec));
            } else {
                int shadowPart = fragIds.getAndIncrement();
                long shadowKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, shadowPart) : 0L;
                addElement(elements, id, id + "_txt_" + shadowPart, ElementType.TEXT, x + sh.offsetX(), y + sh.offsetY(), w, h, text,
                        sh.color(), 0, shadowEvents, 0, 1f, 0, 0, 1f, flipY, rot, 0, AnimationSpec.NONE, meta, shadowKey);
            }
        }
        int mainPart = fragIds.getAndIncrement();
        long mainKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, mainPart) : 0L;
        addElement(elements, id, id + "_txt_" + mainPart, ElementType.TEXT, x, y, w, h, text, color, bgColor, events, 0, 1f, 0, 0, 1f, flipY, rot, transDur, colorAnim, meta, mainKey);
    }

    private ItemProps textPropsForStyle(com.nishiyu.enderframelib.core.css.ComputedStyle style, ItemProps partsEvents, int resolvedColor, DisplayItem prevItem) {
        ItemProps props = partsEvents.copy();
        if (prevItem != null) {
            props.reuseAttrs(prevItem.events());
            props.reuseCandidate(prevItem.hints());
        }
        if (style.containsKey(CssPropertyIndex.FONT_WEIGHT)) props.put("font-weight", style.get(CssPropertyIndex.FONT_WEIGHT));
        if (style.containsKey(CssPropertyIndex.FONT_FAMILY)) props.fontFamily(style.get(CssPropertyIndex.FONT_FAMILY));
        if (style.containsKey(CssPropertyIndex.TEXT_DECORATION)) props.put("text-decoration", style.get(CssPropertyIndex.TEXT_DECORATION));
        putFontEvents(style, props);
        float strokeWidth = com.nishiyu.enderframelib.core.css.TextStroke.widthPx(style);
        if (strokeWidth > 0f) {
            String colorValue = style.get(CssPropertyIndex.WEBKIT_TEXT_STROKE_COLOR);
            int strokeColor = colorValue == null || colorValue.isBlank() || "currentcolor".equalsIgnoreCase(colorValue.trim())
                    ? resolvedColor : ColorParser.parseColor(colorValue);
            props.put("text-stroke", strokeWidth + " " + Integer.toHexString(strokeColor));
        }
        return props;
    }

    private List<BoxDecorations.Shadow> textShadowsForStyle(com.nishiyu.enderframelib.core.css.ComputedStyle style, int resolvedColor, ItemProps props) {
        String shadowValue = style.get(CssPropertyIndex.TEXT_SHADOW);
        if (shadowValue == null || shadowValue.isBlank() || "none".equalsIgnoreCase(shadowValue.trim())) return List.of();
        List<BoxDecorations.Shadow> list = suppressTextShadow ? List.of()
                : BoxDecorations.parseBoxShadows(shadowValue, resolvedColor, rootW, rootH, style.fontSizePx(), style.rootFontSizePx())
                        .stream().filter(sh -> !sh.inset()).toList();
        if (list.isEmpty() && !suppressTextShadow) props.put("text-shadow", "true");
        return list;
    }

    private static DisplayItem prevTextItem(List<DisplayItem> prevItems, int idx) {
        if (prevItems == null || idx >= prevItems.size()) return null;
        DisplayItem cand = prevItems.get(idx);
        return cand.type() == ElementType.TEXT ? cand : null;
    }

    private static RenderMeta reuseTextMeta(DisplayItem prevItem, ClipRect clipRect, float fontHeight, float baseline,
                                            float lineHeight, float originX, float originY, RenderHints hints) {
        RenderMeta prev = prevItem != null ? prevItem.meta() : null;
        if (prev != null && prev.circleBorder() == null && prev.roundedBorder() == null && prev.borderRadii() == null
                && prev.itemCount() == 1 && !prev.interactiveState()
                && Float.compare(prev.fontHeight(), fontHeight) == 0 && Float.compare(prev.baseline(), baseline) == 0
                && Float.compare(prev.lineHeight(), lineHeight) == 0 && Float.compare(prev.originX(), originX) == 0
                && Float.compare(prev.originY(), originY) == 0
                && java.util.Objects.equals(prev.clipRect(), clipRect) && java.util.Objects.equals(prev.hints(), hints)) {
            return prev;
        }
        return new RenderMeta(clipRect, null, null, fontHeight, baseline, lineHeight, originX, originY, 1, false, null, hints);
    }

    private void drawTextNodeElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor,
                                     ClipRect normalizedClip, float fontHVal, float ox, float oy, ItemProps partsEvents,
                                     int transDur, AnimationSpec colorAnim, List<DisplayItem> prevItems) {
        ItemProps baseTextEvents = null;
        List<BoxDecorations.Shadow> baseTextShadows = null;
        int prevIdx = 1;

        if (!box.inlineFragments.isEmpty()) {
            LayoutBox op = box.originParent(); float opX = op != null ? op.x() : 0f, opY = op != null ? op.y() : 0f;
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (frag.text == null || frag.text.isBlank()) continue;
                com.nishiyu.enderframelib.core.css.ComputedStyle fl = frag.effectiveFirstLineStyle(box);
                DisplayItem prevItem = prevTextItem(prevItems, prevIdx);
                int before = elements.size();
                if (fl != null) {
                    int flColor = fl.color(CssPropertyIndex.COLOR, color);
                    float flFontH = Math.max(1.0f, fl.fontSizePx());
                    ItemProps flEvents = textPropsForStyle(fl, partsEvents, flColor, prevItem);
                    List<BoxDecorations.Shadow> flShadows = textShadowsForStyle(fl, flColor, flEvents);
                    RenderMeta fragMeta = reuseTextMeta(prevItem, normalizedClip, flFontH, frag.baseline, frag.h, ox, oy, flEvents.hints());
                    addTextRun(elements, box, fl, id, frag.x + opX, frag.y + opY, frag.w, frag.h, frag.text, flColor, bgColor, flEvents, fragMeta, transDur, AnimationSpec.NONE, flShadows);
                } else {
                    if (baseTextEvents == null) {
                        baseTextEvents = textPropsForStyle(box.style, partsEvents, color, prevItem);
                        baseTextShadows = textShadowsForStyle(box.style, color, baseTextEvents);
                    }
                    RenderMeta fragMeta = reuseTextMeta(prevItem, normalizedClip, fontHVal, frag.baseline, frag.h, ox, oy, baseTextEvents.hints());
                    addTextRun(elements, box, box.style, id, frag.x + opX, frag.y + opY, frag.w, frag.h, frag.text, color, bgColor, baseTextEvents, fragMeta, transDur, colorAnim, baseTextShadows);
                }
                prevIdx += elements.size() - before;
            }
        } else if (box.shapedLines() != null && !box.shapedLines().isEmpty()) {
            baseTextEvents = textPropsForStyle(box.style, partsEvents, color, prevTextItem(prevItems, prevIdx));
            baseTextShadows = textShadowsForStyle(box.style, color, baseTextEvents);
            TextAlign align = box.style.keywordOf(CssPropertyIndex.TEXT_ALIGN, TextAlign.class);
            boolean rtl = "rtl".equalsIgnoreCase(box.style.getOrDefault(CssPropertyIndex.DIRECTION, "ltr").trim());
            float contentX = box.x() + box.pL() + box.bL();
            float availableW = Math.max(0, box.w() - box.pL() - box.pR() - box.bL() - box.bR());
            float lineY = box.y() + box.pT() + box.bT();

            for (ShapedLine line : box.shapedLines()) {
                if (!line.text().isBlank()) {
                    DisplayItem prevItem = prevTextItem(prevItems, prevIdx);
                    int before = elements.size();
                    float lineW = line.width();
                    float lineX = contentX + alignOffset(align, rtl, availableW, lineW);
                    RenderMeta lineMeta = reuseTextMeta(prevItem, normalizedClip, fontHVal, line.baseline(), line.height(), ox, oy, baseTextEvents.hints());
                    addTextRun(elements, box, box.style, id, lineX, lineY, lineW, line.height(), line.text(), color, bgColor, baseTextEvents, lineMeta, transDur, colorAnim, baseTextShadows);
                    prevIdx += elements.size() - before;
                }
                lineY += line.height();
            }
        }
    }

}
