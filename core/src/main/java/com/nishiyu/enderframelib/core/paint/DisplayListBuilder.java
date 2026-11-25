package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.replaced.ReplacedElements;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.nishiyu.enderframelib.core.paint.model.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.nishiyu.enderframelib.core.paint.PaintOrder.canvasBackgroundSource;
import static com.nishiyu.enderframelib.core.paint.PaintSupport.addElement;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;

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
    /** 箱 1 つ自身の項目を作る道具（M2c ②で取り出した）。 */
    final BoxItemPainter painter;
    private final BoxAnimation anim;
    final BoxDecorationPainter decor;
    final PaintOrder order;

    /** 背景をキャンバスに譲った要素（html か body）。自分の箱には背景色を描かない。 */
    LayoutBox canvasBackgroundSource;

    /**
     * ビューポートの大きさが変わった（ウィンドウのリサイズ）。箱ごとの記憶は残す: ビューポート基準の単位（{@code vw} など）を
     * 使わない箱の項目は、ビューポートの大きさに依らないので、位置・寸法・スタイルが同じなら使い回せる
     * （断片の記憶の枠組みの鍵 {@code memoFrameKey}）。部分木の保持は箱の記憶と別の鍵なので、捨てる。
     */
    public void setViewport(int rootW, int rootH) {
        if (this.rootW == rootW && this.rootH == rootH) return;
        this.rootW = rootW;
        this.rootH = rootH;
        painter.setViewport(rootW, rootH);
        order.setViewport(rootW, rootH);
    }

    public DisplayListBuilder(int rootW, int rootH, Map<String, Keyframes> keyframes, ScrollState scrollState) {
        this.rootW = rootW;
        this.rootH = rootH;
        this.keyframes = keyframes != null ? keyframes : Map.of();
        this.scrollState = scrollState;
        this.painter = new BoxItemPainter(rootW, rootH, this.keyframes, fragIds);
        this.anim = painter.anim;
        this.decor = painter.decor;
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
    painter.parentRelative = relMode;
    order.setParentRelative(relMode);
    canvasBackgroundSource = null;
    decor.setDevicePixelRatio(box.devicePixelRatio());
    int base = 0;
    DisplayItem bgItem = null;
    if ("root".equals(box.node.tag)) {
        int canvasBg = DEFAULT_CANVAS_COLOR;
        LayoutBox source = canvasBackgroundSource(box);
        if (source != null) {
            canvasBg = source.style.typed().visual.backgroundColor;
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

    /** 箱の {@code mask-image} のレイヤーを項目にして足す（背景画像と同じ手順。クリップは箱の外へは出さない）。 */
    void drawMask(List<DisplayItem> out, LayoutBox box) { // 断片の描画用（ID の振り方は旧経路と同じ）
        fragIds.set(MASK_ID_BASE);
        painter.drawMask(out, box, parentRelative && PaintOrder.boxHasOwnNode(box));
    }

    /** 文字の影を出さない間か（{@code background-clip: text} のマスクを描く間。断片の記憶の鍵）。 */
    boolean suppressTextShadow() { return painter.suppressTextShadow(); }

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
        String src = Style.or(box.style.typed().rare.src, attrs.getOrDefault("src", ""));
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
        painter.drawBox(elements, box, clipRect, parentId, prevItems, order.effectiveParentNodeBox(box, parentId),
                parentRelative && PaintOrder.boxHasOwnNode(box), box == canvasBackgroundSource);
    }

}
