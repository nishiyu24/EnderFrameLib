package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.CssSyntax;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;
import com.nishiyu.enderframelib.core.text.*;
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
import com.nishiyu.enderframelib.core.paint.PaintOrder.RenderTask;

public class DisplayListBuilder {
    // 単位なしの 0（例: translate(-50%, 0)）も受け付けるため単位は省略可能（省略時は px 扱い）

    private static final Set<String> NON_FRAGMENT_TAGS = Set.of(
            "#text", "img",
            "input", "select", "progress", "meter", "textarea", "canvas", "svg"
    );

    private final int rootW;

    private final int rootH;

    private final Map<String, Keyframes> keyframes;

    private final ScrollState scrollState;

    /** 要素の部品の id（{@code _txt_3} など）に振る通し番号。描画のたびに 0 から数える。 */
    private final AtomicInteger fragIds = new AtomicInteger();
    private final BoxAnimation anim;
    private final BoxDecorationPainter decor;
    private final ControlPainter controls;
    private final PaintOrder order;

    /** 背景をキャンバスに譲った要素（html か body）。自分の箱には背景色を描かない。 */
    private LayoutBox canvasBackgroundSource;

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
                        && (anim.parseAnimationDef(box.style, box.w, box.h, 0, 0).animatedChannels() & AnimationSpec.CH_OPACITY) != 0);
        this.order.configureChunks(new PaintOrder.MemoKeeper() {
            @Override
            public void owned(LayoutBox box, PaintOrder.Chunk chunk) {
                BoxMemo m = boxMemo.get(box);
                if (m != null) m.chunk = chunk;
            }
        }, DisplayListBuilder::memoizable);
    }

    /** 切り分け用: false なら部分木の保持（{@link PaintOrder.Chunk}）を使わない。結果は同じでなければならない。 */
    public static void setChunksEnabled(boolean enabled) {
        PaintOrder.chunksEnabled = enabled;
    }

    /** 診断: 部分木の保持を再利用した回数の累計。 */
    public static long chunksReplayed() {
        return PaintOrder.STAT_CHUNK_REPLAYED.sum();
    }

    /** 直近に描画またはパッチされた完全な表示リスト（Segmented DisplayList）。 */
    private List<DisplayItem> currentDisplayList;
    /** 直近に描画されたボックス（描画順 = 表示リスト上の dlStart 昇順）。 */
    private final List<LayoutBox> renderedBoxes = new ArrayList<>();
    /** スクロールバーを持ちうる（overflow: scroll / auto）描画済みの箱。差分パッチで全箱を走査せずに済ませるための集合（描画のたびに作り直し、子の増減で更新する）。 */
    private final Set<LayoutBox> scrollBoxes = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 窓付きの描画で項目を省いた箱（画面外）。差分パッチが、窓の出入りが無いかを調べるのに使う。 */
    private final List<LayoutBox> culledBoxes = new ArrayList<>();
    /** 直近の描画のルートボックス。 */
    private LayoutBox currentRoot;

    /** 診断: 表示リストの差分スライスパッチ成功回数（全ボックス走査スキップ）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_SLICE_PATCH_HITS = new java.util.concurrent.atomic.LongAdder();
    public static final java.util.concurrent.atomic.LongAdder STAT_CHILD_SLICE_PATCH_HITS = new java.util.concurrent.atomic.LongAdder();
    /** パッチ不成立の理由別カウンタ（計測用）。[0]=canPatchBoxes、[1]=canPatchChildSlices。添字は各判定内の if の出現順。 */
    public static final java.util.concurrent.atomic.LongAdder[][] STAT_CANT_PATCH = {
        newAdders(16), newAdders(16)
    };

    private static java.util.concurrent.atomic.LongAdder[] newAdders(int n) {
        java.util.concurrent.atomic.LongAdder[] a = new java.util.concurrent.atomic.LongAdder[n];
        for (int i = 0; i < n; i++) a[i] = new java.util.concurrent.atomic.LongAdder();
        return a;
    }

    private static boolean cant(int which, int reason) {
        STAT_CANT_PATCH[which][reason].increment();
        return false;
    }

    public static final java.util.concurrent.atomic.LongAdder STAT_SLICE_PATCH_FALLBACKS = new java.util.concurrent.atomic.LongAdder();

    /** 現在保持されている完全な表示リストを取得する（未描画時は null）。 */
    public List<DisplayItem> currentDisplayList() {
        return currentDisplayList;
    }

    /** 箱を描く順（スタッキングコンテキストの順）に並べた箱。 */
    public List<LayoutBox> paintOrder(LayoutBox root) {
        return order.paintOrder(root);
    }

public List<DisplayItem> render(LayoutBox box) {
    return render(box, false);
}

/**
 * {@code cullOffscreen} が true なら、画面（と余白）の外にある箱の項目は作らない。位置だけが変わるスクロールのように、
 * 直前に全件で描いた木と同じ中身のときに使う（部品の ID は箱ごとに数えるので、省いた箱が他の箱の ID に響かない）。
 * 変形・アニメーション・フィルターを持つ祖先の下は、動いて画面に入ってくるので省かない。
 */
public List<DisplayItem> render(LayoutBox box, boolean cullOffscreen) {
    long prof = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
    try {
        return renderUnprofiled(box, cullOffscreen);
    } finally {
        StageProfiler.end(PipelineStage.DISPLAY_LIST, prof);
    }
}

private List<DisplayItem> renderUnprofiled(LayoutBox box, boolean cullOffscreen) {
    generation++;
    buildSerial++;
    buildEpoch = LayoutBox.paintEpoch();
    translating = cullOffscreen && TRANSLATE_ENABLED;
    // 大きい文書は、スクロール時でなくても画面（と余白）の外の箱の項目を作らない（窓付きの表示リスト。項目数が可視範囲に比例する）
    if (lastOwnerCount == 0 && WINDOWED_ENABLED) lastOwnerCount = countBoxes(box, WINDOW_MIN_OWNERS); // 最初の描画だけ、箱の数を数えて判定する
    boolean windowed = !cullOffscreen && WINDOWED_ENABLED && lastOwnerCount >= WINDOW_MIN_OWNERS;
    windowedBuild = windowed;
    if (windowed) STAT_WINDOWED_BUILDS.increment();
    boolean cull = cullOffscreen || windowed;
    int ownerCount = 0;
    translatedHits = 0;
    renderedBoxes.clear();
    scrollBoxes.clear();
    culledBoxes.clear();
    currentRoot = box;
    // 前回と同じ件数で足りることが多い。倍々に伸ばしながら全件を写し直すのを避ける
    List<DisplayItem> elements = new ArrayList<>(lastElementCount + (lastElementCount >> 4) + 16);
        canvasBackgroundSource = null;
        decor.setDevicePixelRatio(box.devicePixelRatio);

        if ("root".equals(box.node.tag)) {
            // キャンバスの背景はルート要素の背景、それが透明なら body の背景（CSS Backgrounds 3 §2.11.2）。
            // 背景を譲った要素は自分の箱には背景色を描かない
            int canvasBg = DEFAULT_CANVAS_COLOR;
            LayoutBox source = canvasBackgroundSource(box);
            if (source != null) {
                canvasBg = ColorParser.parseColor(source.style.getOrDefault(CssPropertyIndex.BACKGROUND_COLOR, "transparent"));
                canvasBackgroundSource = source;
            }
            if (canvasBg != 0x00000000) {
                long bgRootKey = source != null && source.node != null ? DisplayItem.bgKeyOf(source.node.nodeId()) : 0L;
                addElement(elements, "bg_root", "bg_root", ElementType.RECT, box.x, box.y, Math.max(box.w, rootW), Math.max(box.h, rootH),
                        "", 0, canvasBg, new ItemProps(), 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, RenderMeta.EMPTY, bgRootKey);
            }
        }

        // レイヤーに描く箱: 開始の印の次に出る錨の位置を覚え、終了の印でそこから出し終えた項目の数（span）を錨に書く
        java.util.ArrayDeque<int[]> openLayers = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<LayerSpec> openSpecs = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<LayoutBox> openBoxes = new java.util.ArrayDeque<>();
        // 部分木の保持は、全ての箱を描く描画でだけ（平行移動・画面外の省略では項目が欠ける／動く）。再利用は変更印が使える描画でだけ
        order.beginRender(buildSerial, buildEpoch, generation, !translating && !windowed, useChangeStamps && !translating);
        for (RenderTask task : order.paintTasks(box)) {
            if (task.layerStart() != null) {
                openLayers.push(new int[]{elements.size()});
                openSpecs.push(task.layerStart());
                openBoxes.push(task.layerBox());
                continue;
            }
            if (task.layerEnd()) {
                int start = openLayers.pop()[0];
                LayerSpec spec = openSpecs.pop();
                LayoutBox layerBox = openBoxes.pop();
                if (elements.size() - start > 0) {
                    // マスクは、箱の中身を出し終えたあとに足す（描画側は maskFrom 以降をマスク用の描画先へ描く）
                    if (spec.wantsMask()) {
                        int maskFrom = elements.size() - start;
                        fragIds.set(MASK_ID_BASE); // 箱の本体の部品（0 から）と ID が重ならないように
                        drawMaskItems(elements, layerBox);
                        spec = spec.withMaskFrom(elements.size() - start > maskFrom ? maskFrom : -1);
                    }
                    DisplayItem anchor = elements.get(start);
                    elements.set(start, withLayer(anchor, spec.withSpan(elements.size() - start)));
                }
                continue;
            }
            LayoutBox owner = task.box();
            if (owner == null) {
                task.drawAction().accept(elements);
                continue;
            }
            ownerCount++;
            if (cull && !openBoxes.contains(owner) && offscreen(owner)) {
                BoxMemo kept = boxMemo.get(owner);
                if (kept != null) kept.generation = generation; // 省いた箱の記憶は、画面に戻ってくるまで残す
                owner.dlStart = -1;
                owner.dlEnd = -1;
                owner.dlOrder = -1;
                if (windowed && isGeometryOwner(owner)) culledBoxes.add(owner);
                continue;
            }
            int start = elements.size();
            task.drawAction().accept(elements);
            int end = elements.size();
            owner.dlStart = start;
            owner.dlEnd = end;
            owner.dlOrder = renderedBoxes.size();
            renderedBoxes.add(owner);
            if (owner.node != null && PaintOrder.factsOf(owner.style).scrollbars()) scrollBoxes.add(owner);
        }
        // 今回使われなかった箱（木から消えた箱）の記憶を捨てる
        // 掃除は数回に 1 度（毎回全件を辿るとリストの規模に比例する）。保持された部分木の中の箱は、部分木が使われていれば残す
        if (generation % PaintOrder.SWEEP_EVERY == 0) {
            boxMemo.values().removeIf(m -> m.generation != generation && (m.chunk == null || m.chunk.generation != generation));
        }
        order.endRender();

        lastElementCount = elements.size();
        lastOwnerCount = ownerCount;
        this.currentDisplayList = elements;
        this.currentRoot = box;
        return elements;
    }

    /** 直近の描画で出した項目の数（次の描画の出力リストの初期容量に使う）。 */
    private int lastElementCount;
    /** 直近の描画の、項目を持ちうる箱の数（省いた箱も含む）。窓付きの表示リストにするかの判定に使う。 */
    private int lastOwnerCount;
    /** 窓付きで作った表示リストの数（検証用）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_WINDOWED_BUILDS = new java.util.concurrent.atomic.LongAdder();
    /** 直近の描画が窓付き（画面外の箱を省いた）か。 */
    private boolean windowedBuild;
    /** 切り分け用: false なら、大きい文書でも窓付きの表示リストにしない（従来の動き）。 */
    public static volatile boolean WINDOWED_ENABLED = !Boolean.getBoolean("enderframe.paint.nowindow");
    /** 窓付きにする、項目を持ちうる箱の数の下限（これより小さい文書は全件を作る）。 */
    public static volatile int WINDOW_MIN_OWNERS = Integer.getInteger("enderframe.paint.windowmin", 800);
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

    /** 記憶の項目を出力へ足す。数個なら直接足す（{@code addAll} は毎回 {@code toArray} で写しを作る）。 */
    static void appendItems(List<DisplayItem> elements, List<DisplayItem> items) {
        int n = items.size();
        if (n > 8) {
            elements.addAll(items);
        } else {
            for (int i = 0; i < n; i++) elements.add(items.get(i));
        }
    }

    private static final float CULL_MARGIN = 256f;
    private static final int[] MOVING_PROPS = {CssPropertyIndex.TRANSFORM, CssPropertyIndex.TRANSLATE, CssPropertyIndex.ROTATE,
            CssPropertyIndex.SCALE, CssPropertyIndex.ANIMATION, CssPropertyIndex.ANIMATION_NAME, CssPropertyIndex.FILTER,
            CssPropertyIndex.PERSPECTIVE};

    /** 箱（文字の塊は持ち主の箱）の位置が、画面と余白の範囲に掛かるか。 */
    private boolean inWindowGeometry(LayoutBox box) {
        LayoutBox at = box;
        while (at.parent != null && ("#text".equals(at.node.tag) || at.isAnonymous)) at = at.parent;
        return at.x + at.w >= -CULL_MARGIN && at.x <= rootW + CULL_MARGIN
                && at.y + at.h >= -CULL_MARGIN && at.y <= rootH + CULL_MARGIN;
    }

    private boolean offscreen(LayoutBox box) {
        if (inWindowGeometry(box)) return false;
        for (LayoutBox b = box; b != null; b = b.parent) {
            if (b.style == null) continue;
            for (int prop : MOVING_PROPS) {
                String v = b.style.get(prop);
                if (v != null && !v.isBlank() && !"none".equalsIgnoreCase(v.trim())) return false;
            }
        }
        return true;
    }

    /** 箱の {@code mask-image} のレイヤーを項目にして足す（背景画像と同じ手順。クリップは箱の外へは出さない）。 */
    private void drawMaskItems(List<DisplayItem> elements, LayoutBox box) {
        int[] xy = roundRadii(LengthResolver.cornerRadiiXY(box.style, box.w, box.h, rootW, rootH));
        Corners radii = Corners.ofEllipses(java.util.Arrays.copyOfRange(xy, 0, 4), java.util.Arrays.copyOfRange(xy, 4, 8));
        int[] rawRadii = {Math.min(radii.topLeft(), radii.topLeftV()), Math.min(radii.topRight(), radii.topRightV()),
                Math.min(radii.bottomRight(), radii.bottomRightV()), Math.min(radii.bottomLeft(), radii.bottomLeftV())};
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
    }

    /** 文字の影を出さない（マスクに影が混ざらないように）。 */
    private boolean suppressTextShadow;

    private void maskText(List<DisplayItem> elements, LayoutBox box, String id, ItemProps events) {
        for (LayoutBox child : box.children) {
            if (child.display.isNone()) continue;
            if ("#text".equals(child.node.tag)) {
                float fontH = Math.max(1f, child.style.fontSizePx());
                drawTextNodeElement(elements, child, id, 0xFFFFFFFF, 0, null, fontH, 0.5f, 0.5f, events, 0, AnimationSpec.NONE);
            } else {
                maskText(elements, child, id, events);
            }
        }
    }

    /** 錨の項目にレイヤーの指定を載せる。 */
    private static DisplayItem withLayer(DisplayItem anchor, LayerSpec spec) {
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

    /**
     * スクロールコンテナの中身を 1 つの節にまとめるか。まとめると、スクロールだけの更新は表示リストを作り直さず、その節の
     * 移動量を更新するだけで済む（{@code UiView} が行う）。false なら従来どおり、中身の項目はコンテナの節の直下に並び、
     * スクロールのたびに作り直す（疑うときの切り分け用。{@code -Denderframe.paint.noscrollnode=true} でも切れる）。結果は同じでなければならない。
     */
    public static volatile boolean scrollContentNodes = !Boolean.getBoolean("enderframe.paint.noscrollnode");

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

    /** 箱の記憶。{@code generation} は最後に使われた描画の世代（古いものは描画の終わりに捨てる）。 */
    private static final class BoxMemo {
        /**
         * 表示リストの項目は、記憶の位置から平行移動した位置にある（{@link #match} が 2 を返した箱。記憶そのものは動かさない）ときの動き。
         * 差分パッチは「箱の今の位置 − 記憶の位置」で動きを見るので、これを足さないと、リストの中で既に動いた分を二重に動かす。
         */
        int appliedDx, appliedDy;
        boolean appliedFragOnly;
        int generation;
        List<DisplayItem> items;
        com.nishiyu.enderframelib.core.css.ComputedStyle style;
        com.nishiyu.enderframelib.core.css.ComputedStyle[] ancestors;
        float[] nums;
        float scrollW, scrollH;
        ClipRect clip;
        String parentId;
        /** 記憶を作ったときの属性の版番号（属性の写しを持たずに、書き換えがあったかを見分ける）。 */
        int attrVersion;
        String displayText;
        Object shaped;
        Object[] fragRefs;
        float[] fragNums;
        String[] fragText;
        boolean canvasSource, interactive, suppressShadow;
        /** 記憶を作った（または変わっていないと確かめた）描画の開始時の通し番号と、そのときの親の箱。 */
        long epoch;
        LayoutBox parentBox;
        /** 位置だけが変わった作り直し（スクロール）で、項目を平行移動して使い回せる箱か。 */
        boolean translatable;
        /** 項目が NODE（箱の位置）と TEXT（断片の位置）だけ。箱が動かず断片だけが動くとき、TEXT だけを動かせば済む形。 */
        boolean nodeAndTextOnly;
        /** 項目が中身の大きさ（スクロール量）を持つ。中身が変わると、箱が動いただけでは使い回せない。 */
        boolean carriesContentExtent;
        /** この箱を含む、保持された部分木。再利用されている間は、箱ごとに世代を書かなくても記憶は捨てられない。 */
        PaintOrder.Chunk chunk;
    }

    private final java.util.IdentityHashMap<LayoutBox, BoxMemo> boxMemo = new java.util.IdentityHashMap<>();
    private int generation;
    /** 変更印で署名の比較を省くか（呼び出し側が、変わった箱が分かっている局所更新のときだけ true にする）。 */
    private boolean useChangeStamps;
    private long buildEpoch;
    private int buildSerial;
    /** 診断: 変更印で比較を省いた箱の累計。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_STAMPED = new java.util.concurrent.atomic.LongAdder();
    /** 検証: 変更印が「変わっていない」と言った箱でも署名を比べ、食い違えば例外にする（{@code -Denderframe.paint.verifystamps=true}）。 */
    public static volatile boolean verifyStamps = Boolean.getBoolean("enderframe.paint.verifystamps");

    /** 診断: 描画順の分類用のスタイル値を新しく算出した回数の累計（キャッシュ退行の検出用）。 */
    public static long styleFactsComputed() {
        return PaintOrder.STAT_FACTS_COMPUTED.sum();
    }

    /** 次の {@link #render} だけ変更印を使う。 */
    /**
     * 次の {@link #render} だけ、整数ぶんだけ動いた箱の項目を厳密に平行移動して使い回す（子の増減で後続の行が下へずれるとき）。
     * スクロール用の平行移動（{@link #translating}）と違って丸めず、動きが整数でない箱は使い回さない（結果は描き直しと完全に同じ）。
     */
    public void setExactTranslation(boolean exact) {
        this.exactTranslation = exact;
    }

    private boolean exactTranslation;

    public void setUseChangeStamps(boolean use) {
        this.useChangeStamps = use;
    }
    /** 診断: 箱ごとの記憶の使われ方の累計（そのまま・平行移動・新規・不一致）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_EXACT = new java.util.concurrent.atomic.LongAdder(), STAT_TRANSLATED = new java.util.concurrent.atomic.LongAdder(),
            STAT_NEW = new java.util.concurrent.atomic.LongAdder(), STAT_MISS = new java.util.concurrent.atomic.LongAdder(), STAT_UNMEMOIZABLE = new java.util.concurrent.atomic.LongAdder();
    /** 位置だけが変わった作り直し（{@link #render(LayoutBox, boolean)} の {@code cullOffscreen}）か。 */
    private boolean translating;
    private int translatedHits;

    /** 直近の描画で、前回の項目を平行移動して使った箱の数（0 でなければ、位置が最大 1 画素ずれうる）。 */
    public int translatedHits() {
        return translatedHits;
    }
    private static final boolean MEMO_ENABLED = !Boolean.getBoolean("enderframe.paint.nomemo");
    /** 切り分け用: {@code -Denderframe.paint.notranslate=true} で、位置が動いた箱の項目を平行移動で使い回さない。 */
    private static final boolean TRANSLATE_ENABLED = !Boolean.getBoolean("enderframe.paint.notranslate");

    private static boolean memoizable(LayoutBox box) {
        String tag = box.node.tag;
        if (tag == null) return false;
        if (tag.indexOf('-') >= 0) {
            // カスタム要素名（ハイフンを含む）。ホストが置換要素として登録したものは描画が属性とスタイルだけを読むので記憶できる。
            // 未登録のものは従来どおり除外する
            if (!ReplacedElements.isHost(tag)) return false;
        } else {
            switch (tag) {
                case "video", "audio", "svg", "canvas", "textarea", "select", "input", "fieldset",
                     "legend", "table", "caption", "thead", "tbody", "tfoot", "tr", "td", "th", "col", "colgroup" -> {
                    return false;
                }
                case "img" -> {
                    if (!imgMemoizable(box)) return false;
                }
                default -> { } // progress / meter は value・min・max（属性）だけで決まるので解禁
            }
        }
        if (box.collapsedBorders != null || box.display == null) return false;
        // 表の内側の種類（TABLE / TABLE_*）は記憶しない。名前の文字列を作らず enum で判定する（箱ごとに呼ばれる）
        return switch (box.display.inner()) {
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

    /**
     * 算出スタイルが値として同じか。再スタイルされた箱は宣言が同じでも新しいインスタンスになるので、同一性だけで比べると
     * 子孫まで巻き込んで外れる。描画が読むのは宣言と、文字の大きさ（自分とルート）だけ。
     */
    private static boolean sameStyle(com.nishiyu.enderframelib.core.css.ComputedStyle a, com.nishiyu.enderframelib.core.css.ComputedStyle b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.fontSizePx() == b.fontSizePx() && a.rootFontSizePx() == b.rootFontSizePx() && com.nishiyu.enderframelib.core.css.ComputedStyle.sameDeclarations(a, b);
    }

    static float[] numsOf(LayoutBox b) {
        return new float[]{b.x, b.y, b.w, b.h, b.pL, b.pT, b.pR, b.pB, b.bL, b.bT, b.bR, b.bB, b.lineHeightPx};
    }

    /** {@link #numsOf} の i 番目を、配列を作らずに返す（{@link #match} が箱ごとに配列を確保しないため）。 */
    static float numAt(LayoutBox b, int i) {
        return switch (i) {
            case 0 -> b.x; case 1 -> b.y; case 2 -> b.w; case 3 -> b.h;
            case 4 -> b.pL; case 5 -> b.pT; case 6 -> b.pR; case 7 -> b.pB;
            case 8 -> b.bL; case 9 -> b.bT; case 10 -> b.bR; case 11 -> b.bB;
            case 12 -> b.lineHeightPx;
            default -> throw new IndexOutOfBoundsException(i);
        };
    }

    static final int NUMS_LENGTH = 13;

    private static boolean sameNums(float[] a, float[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (Float.compare(a[i], b[i]) != 0) return false;
        return true;
    }

    /** クリップの値としての一致（連鎖の矩形が配列なので、{@code ClipChainPart} の equals が中身を見る）。 */
    private static boolean sameClip(ClipRect a, ClipRect b) {
        return a == b || (a != null && a.equals(b));
    }

    private static com.nishiyu.enderframelib.core.css.ComputedStyle[] ancestorStyles(LayoutBox box) {
        int n = 0;
        for (LayoutBox a = box.parent; a != null; a = a.parent) n++;
        com.nishiyu.enderframelib.core.css.ComputedStyle[] out = new com.nishiyu.enderframelib.core.css.ComputedStyle[n];
        int i = 0;
        for (LayoutBox a = box.parent; a != null; a = a.parent) out[i++] = a.style;
        return out;
    }

    /**
     * 変更印から、記憶が今の箱にそのまま使えると言えるか。記憶を作った後に、自分にも祖先にもレイアウトが触れておらず
     * （印が記憶の通し番号以下）、親の箱・クリップ・親の id・描画側の旗が同じ。属性の変更は印に出ないので、
     * 属性が変わらない更新（文字の局所パッチ）でだけ呼び出し側が有効にする。
     */
    private boolean stampClean(BoxMemo m, LayoutBox box, ClipRect clip, String parentId) {
        if (m.nums != null && m.nums.length >= 4) {
            if (Float.compare(box.x, m.nums[0]) != 0 || Float.compare(box.y, m.nums[1]) != 0
                    || Float.compare(box.w, m.nums[2]) != 0 || Float.compare(box.h, m.nums[3]) != 0) {
                return false;
            }
        }
        if (m.carriesContentExtent && (Float.compare(m.scrollW, box.scrollWidth) != 0 || Float.compare(m.scrollH, box.scrollHeight) != 0)) {
            return false;
        }
        if (!box.inlineFragments.isEmpty() && m.fragNums != null && m.fragNums.length >= 2) {
            LayoutBox.InlineFragment f0 = box.inlineFragments.get(0);
            if (Float.compare(f0.x, m.fragNums[0]) != 0 || Float.compare(f0.y, m.fragNums[1]) != 0) {
                return false;
            }
        }
        return m.parentBox == box.parent && box.pathPaintStamp(buildSerial) <= m.epoch
                && m.canvasSource == (box == canvasBackgroundSource) && m.interactive == box.hasInteractiveRules
                && m.suppressShadow == suppressTextShadow && sameClip(m.clip, clip)
                && java.util.Objects.equals(m.parentId, parentId);
    }

    /** 平行移動で使い回すときの、箱の動き（{@link #match} が 2 を返したとき有効）。 */
    private float hitDx, hitDy;
    private boolean hitFragmentsOnly;

    /**
     * 記憶が今の箱に使えるか。0: 使えない / 1: 項目をそのまま使える / 2: 箱がまるごと {@code (hitDx, hitDy)} だけ動いただけ
     * （または、クリップだけが違う）なので、前回の項目を平行移動して使える。
     * 2 を返すのは {@link #translating}（スクロールなど位置だけが変わる作り直し）で、記憶が平行移動に耐える箱のときだけ。
     */
    private int match(BoxMemo m, LayoutBox box, ClipRect clip, String parentId) {
        if (!sameStyle(m.style, box.style) || m.canvasSource != (box == canvasBackgroundSource) || m.interactive != box.hasInteractiveRules
                || m.suppressShadow != suppressTextShadow) return 0;
        if (m.carriesContentExtent && (Float.compare(m.scrollW, box.scrollWidth) != 0 || Float.compare(m.scrollH, box.scrollHeight) != 0)) return 0;
        float dx = box.x - m.nums[0], dy = box.y - m.nums[1];
        // 文字の箱は自分の位置を持たず、断片だけが置かれる。箱が動かないのに断片が動いたときは、断片の動きを箱の動きとして見る
        // （後続の行が下へずれるとき、文字の箱が毎回描き直しになるのを避ける）。断片どうしの動きが揃っているかは下で確かめる
        // 箱の位置で置かれる NODE の項目は動かさず、断片の位置で置かれる TEXT の項目だけを動かす（hitFragmentsOnly）
        boolean fragmentsOnly = false;
        if (dx == 0 && dy == 0 && m.nodeAndTextOnly && m.fragNums.length >= 2 && !box.inlineFragments.isEmpty()) {
            LayoutBox.InlineFragment f0 = box.inlineFragments.get(0);
            dx = f0.x - m.fragNums[0];
            dy = f0.y - m.fragNums[1];
            fragmentsOnly = dx != 0 || dy != 0;
        }
        boolean moved = dx != 0 || dy != 0;
        // スクロールの作り直しは丸めて動かす。子の増減では、整数ぶんだけ動いた箱に限って厳密に動かす（丸めが入らない）
        boolean moveOk = m.translatable && (translating
                || (exactTranslation && TRANSLATE_ENABLED && !m.carriesContentExtent && dx == Math.rint(dx) && dy == Math.rint(dy)));
        if (moved && !moveOk) return 0;
        if (m.nums.length != NUMS_LENGTH) return 0;
        for (int i = 2; i < NUMS_LENGTH; i++) if (Float.compare(m.nums[i], numAt(box, i)) != 0) return 0;
        boolean clipSame = sameClip(m.clip, clip);
        if (!clipSame && !moveOk) return 0;
        if (!java.util.Objects.equals(m.parentId, parentId)) return 0;
        if (!java.util.Objects.equals(m.displayText, box.displayText) || !java.util.Objects.equals(m.shaped, box.shapedLines)) return 0;
        if (m.attrVersion != box.node.attrVersion()) return 0;
        int depth = 0;
        for (LayoutBox a = box.parent; a != null; a = a.parent, depth++) {
            if (depth >= m.ancestors.length || !sameStyle(m.ancestors[depth], a.style)) return 0;
        }
        if (depth != m.ancestors.length) return 0;
        List<LayoutBox.InlineFragment> frags = box.inlineFragments;
        if (m.fragText.length != frags.size()) return 0;
        int r = 0;
        for (int i = 0; i < frags.size(); i++) {
            LayoutBox.InlineFragment f = frags.get(i);
            int n = i * 8;
            if (!java.util.Objects.equals(m.fragText[i], f.text)) return 0;
            // 断片の位置は、箱と同じだけ動いていること（動き方が違う断片があれば、平行移動では済まない）
            if (moved ? (Math.abs(f.x - m.fragNums[n] - dx) > 1e-3f || Math.abs(f.y - m.fragNums[n + 1] - dy) > 1e-3f)
                    : (Float.compare(m.fragNums[n], f.x) != 0 || Float.compare(m.fragNums[n + 1], f.y) != 0)) return 0;
            if (Float.compare(m.fragNums[n + 2], f.w) != 0 || Float.compare(m.fragNums[n + 3], f.h) != 0
                    || Float.compare(m.fragNums[n + 4], f.baseline) != 0 || m.fragNums[n + 5] != (f.startEdge ? 1f : 0f)
                    || m.fragNums[n + 6] != (f.endEdge ? 1f : 0f) || Float.compare(m.fragNums[n + 7], f.descent) != 0) return 0;
            if (m.fragRefs[r++] != f.box || !sameStyle((com.nishiyu.enderframelib.core.css.ComputedStyle) m.fragRefs[r++], f.box != null ? f.box.style : null)) return 0;
            List<LayoutBox> parents = f.parents;
            int pn = parents == null ? 0 : parents.size();
            if (!(m.fragRefs[r++] instanceof Integer count) || count != pn) return 0;
            for (int k = 0; k < pn; k++) {
                if (m.fragRefs[r++] != parents.get(k) || !sameStyle((com.nishiyu.enderframelib.core.css.ComputedStyle) m.fragRefs[r++], parents.get(k).style)) return 0;
            }
        }
        if (!moved && clipSame) return 1;
        hitDx = dx;
        hitDy = dy;
        hitFragmentsOnly = fragmentsOnly;
        return 2;
    }

    /**
     * 前回の項目を {@code (dx, dy)} を四捨五入した分だけ動かし、クリップを今のものに替えた写し。項目の座標は整数なので、
     * 位置を整数に丸めたあとの相対関係（箱の中の項目どうしの並び）は崩れない。
     */
    private static List<DisplayItem> translated(BoxMemo m, ClipRect newClip, float dx, float dy, boolean textOnly) {
        int ix = Math.round(dx), iy = Math.round(dy);
        List<DisplayItem> out = new ArrayList<>(m.items.size());
        for (DisplayItem it : m.items) {
            int ox = textOnly && it.type() != ElementType.TEXT ? 0 : ix, oy = textOnly && it.type() != ElementType.TEXT ? 0 : iy;
            RenderMeta meta = it.meta();
            if (meta != null && meta.clipRect() != null) {
                meta = new RenderMeta(newClip, meta.borderRadii(), meta.circleBorder(), meta.fontHeight(), meta.baseline(),
                        meta.lineHeight(), meta.originX(), meta.originY(), meta.itemCount(), meta.interactiveState(),
                        meta.roundedBorder(), meta.hints());
            }
            out.add(new DisplayItem(it.id(), it.baseNodeId(), it.type(), it.x() + ox, it.y() + oy, it.width(), it.height(),
                    it.text(), it.color(), it.bgColor(), it.events(), it.radius(), it.opacity(), it.translateX(), it.translateY(),
                    it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(), it.animDef(), it.fitMode(), meta, it.key()));
        }
        return out;
    }

    /**
     * 項目が平行移動だけで作り直せるか: 座標を持つのが x・y と（前回のクリップそのものの）クリップだけで、
     * 箱の座標で決まる図形（影・グラデーション・clip-path・backdrop）や、画面に固定の背景を持たない。
     */
    /** 項目のどれかがスクロールの大きさ（中身の幅・高さ）を持つか。 */
    private static boolean carriesContentExtent(List<DisplayItem> items) {
        for (DisplayItem it : items) {
            RenderMeta meta = it.meta();
            if (meta != null && meta.hints() != null && meta.hints().scrollable()) return true;
        }
        return false;
    }

    private static boolean onlyNodeAndText(List<DisplayItem> items) {
        for (DisplayItem it : items) {
            if (it.type() != ElementType.NODE && it.type() != ElementType.TEXT) return false;
        }
        return true;
    }

    private static boolean survivesTranslation(LayoutBox box, ClipRect clip, List<DisplayItem> items) {
        String attachment = box.style.get(CssPropertyIndex.BACKGROUND_ATTACHMENT);
        if (attachment != null && attachment.contains("fixed")) return false;
        for (DisplayItem it : items) {
            ElementType t = it.type();
            if (t != ElementType.NODE && t != ElementType.RECT && t != ElementType.TEXT && t != ElementType.ROUNDED_BORDER) return false;
            RenderMeta meta = it.meta();
            if (meta == null) continue;
            if (meta.clipRect() != null && !sameClip(meta.clipRect(), clip)) return false;
            RenderHints h = meta.hints();
            if (h.shadowShape() != null || h.shaderGradient() != null) return false;
            LayerSpec layer = h.layer();
            if (layer != null && (layer.clip() != null || layer.backdropShape() != null)) return false;
        }
        return true;
    }

    private BoxMemo remember(LayoutBox box, ClipRect clip, String parentId, List<DisplayItem> items) {
        BoxMemo m = new BoxMemo();
        m.epoch = buildEpoch;
        m.parentBox = box.parent;
        m.items = items;
        m.style = box.style;
        m.ancestors = ancestorStyles(box);
        m.nums = numsOf(box);
        m.scrollW = box.scrollWidth;
        m.scrollH = box.scrollHeight;
        m.clip = clip;
        m.parentId = parentId;
        m.attrVersion = box.node.attrVersion();
        m.displayText = box.displayText;
        m.shaped = box.shapedLines == null ? null : List.copyOf(box.shapedLines);
        List<LayoutBox.InlineFragment> frags = box.inlineFragments;
        m.fragText = new String[frags.size()];
        m.fragNums = new float[frags.size() * 8];
        List<Object> refs = new ArrayList<>();
        for (int i = 0; i < frags.size(); i++) {
            LayoutBox.InlineFragment f = frags.get(i);
            m.fragText[i] = f.text;
            int n = i * 8;
            m.fragNums[n] = f.x;
            m.fragNums[n + 1] = f.y;
            m.fragNums[n + 2] = f.w;
            m.fragNums[n + 3] = f.h;
            m.fragNums[n + 4] = f.baseline;
            m.fragNums[n + 5] = f.startEdge ? 1f : 0f;
            m.fragNums[n + 6] = f.endEdge ? 1f : 0f;
            m.fragNums[n + 7] = f.descent;
            refs.add(f.box);
            refs.add(f.box != null ? f.box.style : null);
            int pn = f.parents == null ? 0 : f.parents.size();
            refs.add(pn);
            for (int k = 0; k < pn; k++) {
                refs.add(f.parents.get(k));
                refs.add(f.parents.get(k).style);
            }
        }
        m.fragRefs = refs.toArray();
        m.canvasSource = box == canvasBackgroundSource;
        m.interactive = box.hasInteractiveRules;
        m.suppressShadow = suppressTextShadow;
        m.translatable = survivesTranslation(box, clip, items);
        m.carriesContentExtent = carriesContentExtent(items);
        m.nodeAndTextOnly = onlyNodeAndText(items);
        m.generation = generation;
        return m;
    }

    /** 直近の描画で、中身を 1 つの節にまとめたスクロールコンテナの記録（スクロールだけの更新で、表示リストを作り直さずに済ませる）。 */
    public List<com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo> scrollContents() {
        return order.scrollContentInfos();
    }

    private void drawBoxSelf(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId) {
        drawBoxSelfMemo(elements, box, clipRect, parentId);
        PaintOrder.ScrollContent content = order.scrollContentOf(box);
        if (content != null) order.addMovingNode(elements, content.contentId, box, null); // 箱の項目のあと・子孫の項目の前
    }

    /** 項目が記憶の位置から動いている分（{@link BoxMemo#appliedDx}）を、記憶の基準の位置へ取り込む（差分パッチの前に呼ぶ）。 */
    private static void syncApplied(BoxMemo m) {
        int ax = m.appliedDx, ay = m.appliedDy;
        if (ax == 0 && ay == 0) return;
        if (!m.appliedFragOnly && m.nums != null && m.nums.length >= 2) {
            m.nums[0] += ax;
            m.nums[1] += ay;
        }
        if (m.fragNums != null) {
            for (int i = 0; i + 1 < m.fragNums.length; i += 8) {
                m.fragNums[i] += ax;
                m.fragNums[i + 1] += ay;
            }
        }
        m.appliedDx = m.appliedDy = 0;
        m.appliedFragOnly = false;
    }

    private void drawBoxSelfMemo(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId) {
        if (!MEMO_ENABLED || !memoizable(box)) {
            STAT_UNMEMOIZABLE.increment();
            fragIds.set(0);
            drawBoxSelfUncached(elements, box, clipRect, parentId);
            return;
        }
        BoxMemo m = boxMemo.get(box);
        int kind;
        if (useChangeStamps && m != null && !translating && stampClean(m, box, clipRect, parentId)) {
            kind = 1;
            STAT_STAMPED.increment();
            if (verifyStamps) {
                int check = match(m, box, clipRect, parentId);
                if (check != 1) throw new AssertionError("変更印は変わっていないと言ったが署名が違う: box=" + box.node.tag + "#" + box.node.key()
                        + " match=" + check + " stamp=" + box.pathPaintStamp(buildSerial) + " epoch=" + m.epoch);
            }
        } else {
            kind = m == null ? 0 : match(m, box, clipRect, parentId);
        }
        if (kind == 1) {
            STAT_EXACT.increment();
            m.appliedDx = m.appliedDy = 0;
            m.appliedFragOnly = false;
            m.generation = generation;
            m.epoch = buildEpoch;
            appendItems(elements, m.items);
            return;
        }
        if (kind == 2) { // 箱が動いただけ。記憶は動く前のまま残す（差が積もっても、丸めの誤差は 1 画素以内）
            m.generation = generation;
            m.appliedDx = Math.round(hitDx);
            m.appliedDy = Math.round(hitDy);
            m.appliedFragOnly = hitFragmentsOnly;
            elements.addAll(translated(m, clipRect, hitDx, hitDy, hitFragmentsOnly));
            translatedHits++;
            STAT_TRANSLATED.increment();
            return;
        }
        (m == null ? STAT_NEW : STAT_MISS).increment();
        fragIds.set(0); // 部品の ID は箱ごとに数える
        int start = elements.size();
        drawBoxSelfUncached(elements, box, clipRect, parentId);
        boxMemo.put(box, remember(box, clipRect, parentId, List.copyOf(elements.subList(start, elements.size()))));
    }

    private void drawBoxSelfUncached(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId) {
        int[] xy = roundRadii(LengthResolver.cornerRadiiXY(box.style, box.w, box.h, rootW, rootH));
        Corners radii = Corners.ofEllipses(java.util.Arrays.copyOfRange(xy, 0, 4), java.util.Arrays.copyOfRange(xy, 4, 8));
        // 円しか持たない所（インラインの断片・円判定）には、横と縦の小さい方の円で近似した半径を渡す
        int[] rawRadii = {Math.min(radii.topLeft(), radii.topLeftV()), Math.min(radii.topRight(), radii.topRightV()),
                Math.min(radii.bottomRight(), radii.bottomRightV()), Math.min(radii.bottomLeft(), radii.bottomLeftV())};
        int fallbackRadius = radii.topLeft();
        float rOff = Math.min(fallbackRadius, Math.min(box.w, box.h) / 2);

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

        TransitionSpec transSpec = parseTransitionSpec(box.style.asMap());
        AnimationSpec animDef = anim.parseAnimationDef(box.style, box.w, box.h, color, bgColor);
        AnimationSpec colorBgAnim = animDef.withChannels(AnimationSpec.CH_COLOR | AnimationSpec.CH_BG_COLOR);
        int transDur = transSpec.durationMs();
        boolean interactive = box.hasInteractiveRules;

        float ox = 0.5f, oy = 0.5f;
        ItemProps anchorEvents = new ItemProps();
        if (parentId != null) anchorEvents.parentId(parentId);
        if (tr.length > 5 && tr[5] != 0f) anchorEvents.skew(tr[5]);
        applyTransitionEvents(anchorEvents, transSpec);
        if (box.style.containsKey(CssPropertyIndex.TRANSFORM_ORIGIN)) {
            List<String> parts = CssSyntax.words(box.style.get(CssPropertyIndex.TRANSFORM_ORIGIN).toLowerCase(Locale.ROOT));
            if (parts.size() > 0) ox = parseOriginProp(parts.get(0), box.w, box.style.fontSizePx(), box.style.rootFontSizePx());
            if (parts.size() > 1) oy = parseOriginProp(parts.get(1), box.h, box.style.fontSizePx(), box.style.rootFontSizePx());
        }

        // 算出済みの font-size をそのまま運ぶ。描画側は書体の em からの倍率に直す。
        // 以前は 16 倍した「倍率」を渡し、描画側が 12 で割るという二重の換算だった。
        float fontHVal = Math.max(1.0f, box.style.fontSizePx());
        int itemCount = (int) parseFloatAttr(box.node.attrs, "data-count", 1f);

        boolean pointerEventsNone = box.style.keywordOf(CssPropertyIndex.POINTER_EVENTS, CssKeywords.PointerEvents.class)
                == CssKeywords.PointerEvents.NONE;
        if (pointerEventsNone) anchorEvents.pointerEventsNone();

        ClipRect normalizedClip = clipRect;
        RenderMeta anchorMeta = new RenderMeta(normalizedClip, radii, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);
        long nodeKey = box.node != null ? DisplayItem.nodeKeyOf(box.node.nodeId()) : 0L;
        addElement(elements, id, id, ElementType.NODE, box.x, box.y, box.w, box.h, "", color, bgColor, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, transDur, animDef, anchorMeta, nodeKey);

        // visibility: hidden の箱は場所だけを取る。錨（NODE）は子孫の変形と不透明度の親として残す
        // 何が壊れていたか: 以前は visibility を読んでおらず、hidden の箱が描かれクリックも受けていた
        if (isInvisible(box) || PaintSupport.hidesEmptyCell(box)) return;

        ItemProps partsEvents = ItemProps.forParts(box, id, transSpec);
        ItemProps passiveBorderEvents = ItemProps.forPassiveBorder(id, nodeKey, transSpec);
        // 何が壊れていたか: 以前は pointer-events を部品に載せていなかったので、ヒットテストの
        // pointer-events: none の判定は常に偽だった
        if (pointerEventsNone) {
            partsEvents.pointerEventsNone();
            passiveBorderEvents.pointerEventsNone();
        }

        RenderMeta baseMeta = new RenderMeta(normalizedClip, radii, null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);

        if (!box.inlineFragments.isEmpty() && !NON_FRAGMENT_TAGS.contains(box.node.tag) && !ReplacedElements.isHost(box.node.tag) && !BlockLayoutEngine.isAtomicInline(box)) {
            decor.drawInlineFragments(elements, box, id, color, bgColor, rawRadii, normalizedClip, fontHVal, ox, oy, itemCount, interactive,
                    partsEvents, passiveBorderEvents, transDur, colorBgAnim);
            return;
        }

        List<BoxDecorations.Shadow> shadows = BoxDecorations.parseBoxShadows(box.style.get(CssPropertyIndex.BOX_SHADOW), color,
                rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
        decor.drawOuterShadows(elements, box, id, shadows, radii, normalizedClip, fontHVal, ox, oy);

        BorderSpec border = decor.parseBorderSpec(box, rawRadii, radii, color);
        RenderMeta innerMeta = new RenderMeta(normalizedClip, border.innerRadii(), null, fontHVal, 0f, box.lineHeightPx, ox, oy, itemCount, interactive);

        switch (box.node.tag) {
            case "img", "video", "audio" -> controls.drawMediaElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "#text" -> drawTextNodeElement(elements, box, id, color, bgColor, normalizedClip, fontHVal, ox, oy, partsEvents, transDur, anim.textColorAnimation(box, animDef));
            case "progress", "meter" -> {
                String val = box.node.attrs.getOrDefault("value", "0");
                String min = box.node.attrs.getOrDefault("min", "0");
                String max = box.node.attrs.getOrDefault("max", "100");
                // 何が壊れていたか: ここが RANGE を出していたため、progress / meter が
                // つまみ付きのスライダーとして描かれていた。進捗バーの描画実装
                // (ControlElementRenderer.renderProgressElement) は呼ばれない死にコードだった。
                long progressKey = box.node != null ? DisplayItem.keyOf(box.node.nodeId(), DisplayItem.PART_PROGRESS) : 0L;
                addElement(elements, id, id + "_progress", ElementType.PROGRESS, box.x, box.y, box.w, box.h, val + ":" + min + ":" + max, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta, progressKey);
            }
            case "svg" -> controls.drawSvgElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "canvas" -> controls.drawCanvasElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "textarea" -> controls.drawTextareaElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "select" -> controls.drawSelectElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
            case "input" -> {
                if (isPlainRange(box)) {
                    // appearance: none のレンジは作者の CSS で描いた箱に、つまみ（::-webkit-slider-thumb は部品のまま）だけを重ねる
                    decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                    controls.drawNativeRange(elements, box, id, rangeData(box, resolveLiveInputValue(id, box.node.attrs.getOrDefault("value", ""))),
                            color, false, partsEvents, border, baseMeta, transDur, colorBgAnim);
                } else if (isPlainToggle(box)) {
                    // appearance: none のチェックボックス・ラジオボタンは普通の箱（作者の枠線・背景だけで描き、印は無い）
                    decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
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
                    decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows);
                }
            }
        }
        decor.drawColumnRules(elements, box, id, color, passiveBorderEvents, baseMeta, transDur);
        decor.drawOutline(elements, box, id, color, passiveBorderEvents, baseMeta, transDur);
    }

    /**
     * 文字 1 行（断片）を足す。{@code text-shadow} があれば、後ろに書いた影から先に（下に）、ずらした影色の文字を出してから本体を出す。
     * ぼかしのある影は、錨（NODE）に {@code blur} のレイヤーを載せ、その中に影の文字を置く（ぼかしの σ は半径の半分）。
     * 影はクリックを受けず、色のアニメーション・トランジションも受けない。
     */
    private void addTextRun(List<DisplayItem> elements, LayoutBox box, String id, float x, float y, float w, float h, String text, int color, int bgColor,
                            ItemProps events, RenderMeta meta, int transDur, AnimationSpec colorAnim, List<BoxDecorations.Shadow> shadows) {
        int nodeHandle = box.node != null ? box.node.nodeId() : 0;
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
                        sh.color(), 0, shadowEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, meta, shadowKey);
                LayerSpec spec = new LayerSpec(elements.size() - at, 1f, List.of(FilterOp.blur(sh.blur() / 2f)), BlendMode.NORMAL, false);
                elements.set(at, withLayer(elements.get(at), spec));
            } else {
                int shadowPart = fragIds.getAndIncrement();
                long shadowKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, shadowPart) : 0L;
                addElement(elements, id, id + "_txt_" + shadowPart, ElementType.TEXT, x + sh.offsetX(), y + sh.offsetY(), w, h, text,
                        sh.color(), 0, shadowEvents, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, meta, shadowKey);
            }
        }
        int mainPart = fragIds.getAndIncrement();
        long mainKey = nodeHandle > 0 ? DisplayItem.keyOf(nodeHandle, mainPart) : 0L;
        addElement(elements, id, id + "_txt_" + mainPart, ElementType.TEXT, x, y, w, h, text, color, bgColor, events, 0, 1f, 0, 0, 1f, 1f, 0, transDur, colorAnim, meta, mainKey);
    }

    /** {@code -webkit-text-stroke-width} の CSS px（{@code thin / medium / thick} は 1 / 3 / 5）。無い・0 以下は 0。 */
    static float textStrokeWidth(com.nishiyu.enderframelib.core.css.ComputedStyle style) {
        String v = style.get(CssPropertyIndex.WEBKIT_TEXT_STROKE_WIDTH);
        if (v == null) return 0f;
        v = v.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            float w = switch (v) {
                case "thin" -> 1f;
                case "medium" -> 3f;
                case "thick" -> 5f;
                default -> v.endsWith("rem") ? Float.parseFloat(v.substring(0, v.length() - 3)) * style.rootFontSizePx()
                        : v.endsWith("em") ? Float.parseFloat(v.substring(0, v.length() - 2)) * style.fontSizePx()
                        : v.endsWith("px") ? Float.parseFloat(v.substring(0, v.length() - 2))
                        : Float.parseFloat(v);
            };
            return w > 0f && Float.isFinite(w) ? w : 0f;
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private void drawTextNodeElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor,
                                     ClipRect normalizedClip, float fontHVal, float ox, float oy, ItemProps partsEvents,
                                     int transDur, AnimationSpec colorAnim) {
        ItemProps baseTextEvents = partsEvents.copy();
        if (box.style.containsKey(CssPropertyIndex.FONT_WEIGHT)) baseTextEvents.put("font-weight", box.style.get(CssPropertyIndex.FONT_WEIGHT));
        if (box.style.containsKey(CssPropertyIndex.FONT_FAMILY)) baseTextEvents.put("font-family", box.style.get(CssPropertyIndex.FONT_FAMILY));
        if (box.style.containsKey(CssPropertyIndex.TEXT_DECORATION)) baseTextEvents.put("text-decoration", box.style.get(CssPropertyIndex.TEXT_DECORATION));
        // text-shadow: 「x y [ぼかし] [色]」の並びは本物の影（ずらした色付きの文字。ぼかしがあれば合成レイヤーで）。
        // 解いて読めない値（旧来の text-shadow:true など）は、従来の「右下に暗い文字を重ねる」影のまま
        List<BoxDecorations.Shadow> textShadows = List.of();
        String shadowValue = box.style.get(CssPropertyIndex.TEXT_SHADOW);
        if (shadowValue != null && !shadowValue.isBlank() && !"none".equalsIgnoreCase(shadowValue.trim())) {
            if (!suppressTextShadow) textShadows = BoxDecorations.parseBoxShadows(shadowValue, color, rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx())
                    .stream().filter(sh -> !sh.inset()).toList();
            if (textShadows.isEmpty() && !suppressTextShadow) baseTextEvents.put("text-shadow", "true");
        }
        putFontEvents(box, baseTextEvents);
        float strokeWidth = textStrokeWidth(box.style);
        if (strokeWidth > 0f) {
            String colorValue = box.style.get(CssPropertyIndex.WEBKIT_TEXT_STROKE_COLOR);
            int strokeColor = colorValue == null || colorValue.isBlank() || "currentcolor".equalsIgnoreCase(colorValue.trim())
                    ? color : ColorParser.parseColor(colorValue);
            baseTextEvents.put("text-stroke", strokeWidth + " " + Integer.toHexString(strokeColor));
        }

        if (!box.inlineFragments.isEmpty()) {
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (frag.text == null || frag.text.isBlank()) continue;
                RenderMeta fragMeta = new RenderMeta(
                        normalizedClip, null, null, fontHVal, frag.baseline, frag.h, ox, oy, 1, false);
                addTextRun(elements, box, id, frag.x, frag.y, frag.w, frag.h, frag.text, color, bgColor, baseTextEvents, fragMeta, transDur, colorAnim, textShadows);
            }
        } else if (box.shapedLines != null && !box.shapedLines.isEmpty()) {
            // 行の文字列・幅・ベースラインは整形器（TextShaper）が確定済み。
            // ここがするのは text-align の offset と丸めだけで、描画側は測り直さない。
            TextAlign align = box.style.keywordOf(CssPropertyIndex.TEXT_ALIGN, TextAlign.class);
            boolean rtl = "rtl".equalsIgnoreCase(box.style.getOrDefault(CssPropertyIndex.DIRECTION, "ltr").trim());
            float contentX = box.x + box.pL + box.bL;
            float availableW = Math.max(0, box.w - box.pL - box.pR - box.bL - box.bR);
            float lineY = box.y + box.pT + box.bT;

            for (ShapedLine line : box.shapedLines) {
                if (!line.text().isBlank()) {
                    float lineW = line.width();
                    float lineX = contentX + alignOffset(align, rtl, availableW, lineW);
                    RenderMeta lineMeta = new RenderMeta(
                            normalizedClip, null, null, fontHVal, line.baseline(), line.height(), ox, oy, 1, false);
                    // text-transform は displayText を作る時点で適用済み（測定と描画で同じ文字列）
                    addTextRun(elements, box, id, lineX, lineY, lineW, line.height(), line.text(), color, bgColor, baseTextEvents, lineMeta, transDur, colorAnim, textShadows);
                }
                lineY += line.height();
            }
        }
    }

    // ==========================================
    // Segmented DisplayList: 差分スライスパッチ API (P0-4 / 戦略 T2)
    // ==========================================

    private ClipRect clipForBox(LayoutBox box) {
        BoxMemo m = boxMemo.get(box);
        if (m != null && m.clip != null) return m.clip;
        LayoutBox p = box.parent;
        while (p != null) {
            BoxMemo pm = boxMemo.get(p);
            if (pm != null && pm.clip != null) return pm.clip;
            p = p.parent;
        }
        return null;
    }

    private String contentParentIdOf(LayoutBox box) {
        if (box == null) return null;
        PaintOrder.ScrollContent sc = order.scrollContentOf(box);
        if (sc != null) return sc.contentId;
        return box.node != null ? box.node.key() : null;
    }

    private String parentIdForBox(LayoutBox box) {
        BoxMemo m = boxMemo.get(box);
        if (m != null && m.parentId != null) return m.parentId;
        if (box.parent == null) return null;
        return contentParentIdOf(box.parent);
    }

    private static void collectSubtreeBoxes(LayoutBox b, List<LayoutBox> out) {
        if (b == null) return;
        if (b.dlStart >= 0) out.add(b);
        if (b.children != null) {
            for (LayoutBox c : b.children) collectSubtreeBoxes(c, out);
        }
    }

    static final class SubtreeSlices {
        final List<LayoutBox> blockBoxes = new ArrayList<>();
        final List<LayoutBox> inlineBoxes = new ArrayList<>();
    }

    private void collectSubtreeSlices(LayoutBox box, SubtreeSlices out) {
        if (box == null || box.w <= 0 || box.h <= 0 || (box.display != null && box.display.isNone())) return;
        if (!box.isAnonymous) {
            if (box.display.isInlineLevel() || (box.node != null && box.node.nodeType == NodeType.TEXT)) {
                out.inlineBoxes.add(box);
            } else {
                out.blockBoxes.add(box);
            }
        }
        if (box.children != null) {
            for (LayoutBox c : box.children) {
                collectSubtreeSlices(c, out);
            }
        }
    }

    private void renderSubtreeSlices(LayoutBox box, ClipRect clip, String parentId,
                                    List<DisplayItem> outBlocks, List<LayoutBox> outBlockBoxes,
                                    List<DisplayItem> outInlines, List<LayoutBox> outInlineBoxes) {
        if (box == null || box.w <= 0 || box.h <= 0 || (box.display != null && box.display.isNone())) return;

        if (!box.isAnonymous && windowedBuild && offscreen(box)) {
            // 窓の外（画面外）の箱は、全体を窓付きで組み直したときと同じく、項目を作らない
            box.dlStart = -1;
            box.dlEnd = -1;
            box.dlOrder = -1;
            if (isGeometryOwner(box)) culledBoxes.add(box);
        } else if (!box.isAnonymous) {
            boolean isInline = box.display.isInlineLevel() || (box.node != null && box.node.nodeType == NodeType.TEXT);
            List<DisplayItem> targetItems = isInline ? outInlines : outBlocks;
            List<LayoutBox> targetBoxes = isInline ? outInlineBoxes : outBlockBoxes;

            int start = targetItems.size();
            fragIds.set(0);
            drawBoxSelfUncached(targetItems, box, clip, parentId);
            int end = targetItems.size();
            box.dlStart = start;
            box.dlEnd = end;
            targetBoxes.add(box);
            boxMemo.put(box, remember(box, clip, parentId, List.copyOf(targetItems.subList(start, end))));
        }

        String childParent = (box.node != null && !box.isAnonymous) ? box.node.key() : parentId;
        ClipRect childClip = clip;
        if (box.children != null) {
            for (LayoutBox child : box.children) {
                renderSubtreeSlices(child, childClip, childParent, outBlocks, outBlockBoxes, outInlines, outInlineBoxes);
            }
        }
    }

    /**
     * 窓付きの表示リストで、子の増減のあと（レイアウトは済んでいる）も、窓の出入りが無いか。
     * 項目を作った箱は全て窓の中、省いた箱は全て窓の外のままなら、全体を窓付きで組み直した結果と同じ並びになる。
     * 窓から出た箱・窓に入る箱があれば、組み直す（削除される箱は調べない）。
     */
    private boolean windowIntact(List<LayoutBox> removed) {
        Set<LayoutBox> gone = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LayoutBox r : removed) markSubtree(r, gone);
        // 位置だけで決まる側（窓に掛かる箱は描く・掛からない箱は、変形などの動く祖先が無いかぎり省く）を先に見て、祖先の走査を避ける。
        // 省いた箱の変形などの有無は、省いた時点から変わらない（スタイルの変更は別の経路で、子の増減のパッチには来ない）
        for (LayoutBox b : renderedBoxes) {
            if (!gone.contains(b) && !inWindowGeometry(b) && offscreen(b)) return false;
        }
        for (LayoutBox b : culledBoxes) {
            if (!gone.contains(b) && inWindowGeometry(b)) return false;
        }
        return true;
    }

    /** 文字の塊・匿名の箱は、窓に掛かるかを持ち主の箱の位置で決める（持ち主と同じ答えになる）ので、窓の出入りの確認では持ち主だけを見る。 */
    private static boolean isGeometryOwner(LayoutBox b) {
        return !b.isAnonymous && !(b.node != null && "#text".equals(b.node.tag));
    }

    private static void markSubtree(LayoutBox b, Set<LayoutBox> out) {
        if (b == null) return;
        out.add(b);
        if (b.children != null) for (LayoutBox c : b.children) markSubtree(c, out);
    }

    /** 窓付きの表示リストで、項目を省いた箱が今も画面外にある（＝表示リストに触れずに済む）か。 */
    private boolean isCulledNoop(LayoutBox b) {
        return windowedBuild && b.dlStart < 0 && b.dlEnd < 0 && b.dlOrder < 0 && offscreen(b);
    }

    /**
     * 指定されたボックス群の表示項目スライス [dlStart, dlEnd) をインプレースで差分差し替え可能か判定する。
     */
    public boolean canPatchBoxes(java.util.Collection<LayoutBox> boxes) {
        if (currentDisplayList == null || currentDisplayList.isEmpty() || currentRoot == null) return cant(0, 0);
        if (boxes == null || boxes.isEmpty()) return cant(0, 1);
        int listSize = currentDisplayList.size();
        int boxCount = renderedBoxes.size();
        for (LayoutBox b : boxes) {
            if (b == null || b.w <= 0 || b.h <= 0 || b.isAnonymous) return cant(0, 2);
            if (isCulledNoop(b)) continue; // 窓の外の箱: 表示リストに項目が無く、画面外のままなら何も変えずに済む
            if (b.dlStart < 0 || b.dlEnd < b.dlStart || b.dlEnd > listSize) return cant(0, 3);
            if (b.dlOrder < 0 || b.dlOrder >= boxCount || renderedBoxes.get(b.dlOrder) != b) return cant(0, 4);
            if (b.style != null) {
                var facts = PaintOrder.factsOf(b.style);
                if (facts.scrollbars() || facts.hasAnimation()) return cant(0, 5);
            }
            if (order.createsStackingContext(b, b.style)) return cant(0, 6);
            BoxMemo m = boxMemo.get(b);
            if (m != null) {
                if (m.style != null) {
                    var prevFacts = PaintOrder.factsOf(m.style);
                    if (prevFacts.scrollbars() || prevFacts.hasAnimation()) return cant(0, 5);
                    if (order.createsStackingContext(b, m.style)) return cant(0, 6);
                }
                if (m.nums != null && m.nums.length >= 4) {
                    if (Float.compare(m.nums[0], b.x) != 0 || Float.compare(m.nums[1], b.y) != 0
                            || Float.compare(m.nums[2], b.w) != 0 || Float.compare(m.nums[3], b.h) != 0) {
                        return cant(0, 8);
                    }
                }
            }
        }
        if (boxes.size() > 1) {
            List<LayoutBox> sorted = new ArrayList<>(boxes instanceof java.util.Set ? boxes : new java.util.LinkedHashSet<>(boxes));
            if (windowedBuild) sorted.removeIf(this::isCulledNoop);
            if (sorted.size() > 1) {
                sorted.sort((x, y) -> Integer.compare(x.dlStart, y.dlStart));
                for (int i = 0, n = sorted.size(); i < n - 1; i++) {
                    LayoutBox b1 = sorted.get(i);
                    LayoutBox b2 = sorted.get(i + 1);
                    if (b1.dlEnd > b2.dlStart) return cant(0, 7);
                }
            }
        }
        return true;
    }

    /**
     * 変更されたボックス群のスライスを再描画し、全ボックス走査を回避して表示リストをインプレースで差分更新する。
     * パッチ不可能な場合は null を返す（呼び出し側がフル描画にフォールバック）。
     */
    public List<DisplayItem> patchBoxes(java.util.Collection<LayoutBox> boxes) {
        long prof = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
        try {
            return patchBoxesUnprofiled(boxes);
        } finally {
            StageProfiler.end(PipelineStage.DISPLAY_LIST, prof);
        }
    }

    private List<DisplayItem> patchBoxesUnprofiled(java.util.Collection<LayoutBox> boxes) {
        if (!canPatchBoxes(boxes)) {
            STAT_SLICE_PATCH_FALLBACKS.increment();
            return null;
        }

        // 複数ボックスがある場合、dlStart の降順（末尾から先頭へ）でソート
        List<LayoutBox> sorted = new ArrayList<>(boxes instanceof java.util.Set ? boxes : new java.util.LinkedHashSet<>(boxes));
        if (windowedBuild) sorted.removeIf(DisplayListBuilder.this::isCulledNoop);
        if (sorted.size() > 1) {
            sorted.sort((a, b) -> Integer.compare(b.dlStart, a.dlStart));
        }

        decor.setDevicePixelRatio(currentRoot.devicePixelRatio);

        for (LayoutBox b : sorted) {
            ClipRect clip = clipForBox(b);
            String parentId = parentIdForBox(b);
            fragIds.set(0);
            List<DisplayItem> newItems = new ArrayList<>(b.dlEnd - b.dlStart + 4);
            drawBoxSelfUncached(newItems, b, clip, parentId);
            boxMemo.put(b, remember(b, clip, parentId, newItems));

            int oldLen = b.dlEnd - b.dlStart;
            int newLen = newItems.size();
            int delta = newLen - oldLen;

            if (delta == 0) {
                // スライス長同一: インプレース置換 (O(newLen))
                for (int i = 0; i < newLen; i++) {
                    currentDisplayList.set(b.dlStart + i, newItems.get(i));
                }
            } else {
                // スライス長変化: 局所置換と後続インデックスシフト
                currentDisplayList.subList(b.dlStart, b.dlEnd).clear();
                currentDisplayList.addAll(b.dlStart, newItems);
                b.dlEnd = b.dlStart + newLen;

                int orderIdx = b.dlOrder;
                for (int k = orderIdx + 1, n = renderedBoxes.size(); k < n; k++) {
                    LayoutBox succ = renderedBoxes.get(k);
                    succ.dlStart += delta;
                    succ.dlEnd += delta;
                }
            }
        }

        generation++;
        buildSerial++;
        buildEpoch = LayoutBox.paintEpoch();
        lastElementCount = currentDisplayList.size();
        STAT_SLICE_PATCH_HITS.increment();
        return currentDisplayList;
    }

    /**
     * 子要素の増減（CHILD_PATCH）に対して、削除されたボックスと追加されたボックスのスライスを差分差し替え可能か判定する。
     * （R2 で後続座標シフト・親寸法更新のチャンク化とともに本格成立させるため、現時点では理由 12 でフォールバック）
     */
    public boolean canPatchChildSlices(LayoutBox parentBox, List<LayoutBox> removed, List<LayoutBox> added, LayoutBox afterBox) {
        if (currentDisplayList == null || currentDisplayList.isEmpty() || currentRoot == null) return cant(1, 0);
        if (parentBox == null || (removed.isEmpty() && added.isEmpty())) return cant(1, 1);
        int listSize = currentDisplayList.size();
        int boxCount = renderedBoxes.size();
        if (windowedBuild && !windowIntact(removed)) return cant(1, 13);
        if (!removed.isEmpty()) {
            SubtreeSlices remSlices = new SubtreeSlices();
            for (LayoutBox r : removed) collectSubtreeSlices(r, remSlices);
            for (LayoutBox b : remSlices.blockBoxes) {
                if (b.dlStart >= 0) {
                    if (b.dlEnd < b.dlStart || b.dlEnd > listSize) return cant(1, 2);
                    if (b.dlOrder < 0 || b.dlOrder >= boxCount || renderedBoxes.get(b.dlOrder) != b) return cant(1, 3);
                }
            }
            for (LayoutBox b : remSlices.inlineBoxes) {
                if (b.dlStart >= 0) {
                    if (b.dlEnd < b.dlStart || b.dlEnd > listSize) return cant(1, 4);
                    if (b.dlOrder < 0 || b.dlOrder >= boxCount || renderedBoxes.get(b.dlOrder) != b) return cant(1, 5);
                }
            }
        }
        return true;
    }

    /**
     * 子要素の増減（CHILD_PATCH）に対して、削除されたボックスのスライスを除去し、追加されたボックスのスライスを挿入して差分更新する。
     * パッチ不可能な場合は null を返す（フル描画フォールバック）。
     */
    public List<DisplayItem> patchChildSlices(LayoutBox parentBox, List<LayoutBox> removed, List<LayoutBox> added, LayoutBox afterBox) {
        long prof = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
        try {
            return patchChildSlicesUnprofiled(parentBox, removed, added, afterBox);
        } finally {
            StageProfiler.end(PipelineStage.DISPLAY_LIST, prof);
        }
    }

    private List<DisplayItem> patchChildSlicesUnprofiled(LayoutBox parentBox, List<LayoutBox> removed, List<LayoutBox> added, LayoutBox afterBox) {
        if (!canPatchChildSlices(parentBox, removed, added, afterBox)) {
            STAT_SLICE_PATCH_FALLBACKS.increment();
            return null;
        }
        order.clearClips();

        // 1. 削除スライスの特定と除去（ブロック層・インライン層）
        int remBlockStart = -1, remBlockEnd = -1, remBlockOrderStart = -1, remBlockCount = 0;
        int remInlineStart = -1, remInlineEnd = -1, remInlineOrderStart = -1, remInlineCount = 0;

        if (!removed.isEmpty()) {
            SubtreeSlices remSlices = new SubtreeSlices();
            for (LayoutBox r : removed) collectSubtreeSlices(r, remSlices);

            // インライン層の削除区間
            if (!remSlices.inlineBoxes.isEmpty()) {
                int minS = Integer.MAX_VALUE, maxE = Integer.MIN_VALUE;
                int minO = Integer.MAX_VALUE, maxO = Integer.MIN_VALUE;
                for (LayoutBox b : remSlices.inlineBoxes) {
                    if (b.dlStart >= 0 && b.dlEnd >= b.dlStart) {
                        minS = Math.min(minS, b.dlStart);
                        maxE = Math.max(maxE, b.dlEnd);
                    }
                    if (b.dlOrder >= 0) {
                        minO = Math.min(minO, b.dlOrder);
                        maxO = Math.max(maxO, b.dlOrder);
                    }
                }
                if (minS != Integer.MAX_VALUE) {
                    remInlineStart = minS;
                    remInlineEnd = maxE;
                    remInlineOrderStart = minO;
                    remInlineCount = maxO - minO + 1;
                }
            }

            // ブロック層の削除区間
            if (!remSlices.blockBoxes.isEmpty()) {
                int minS = Integer.MAX_VALUE, maxE = Integer.MIN_VALUE;
                int minO = Integer.MAX_VALUE, maxO = Integer.MIN_VALUE;
                for (LayoutBox b : remSlices.blockBoxes) {
                    if (b.dlStart >= 0 && b.dlEnd >= b.dlStart) {
                        minS = Math.min(minS, b.dlStart);
                        maxE = Math.max(maxE, b.dlEnd);
                    }
                    if (b.dlOrder >= 0) {
                        minO = Math.min(minO, b.dlOrder);
                        maxO = Math.max(maxO, b.dlOrder);
                    }
                }
                if (minS != Integer.MAX_VALUE) {
                    remBlockStart = minS;
                    remBlockEnd = maxE;
                    remBlockOrderStart = minO;
                    remBlockCount = maxO - minO + 1;
                }
            }

            // 境界チェック
            if (remInlineStart >= 0 && (remInlineEnd > currentDisplayList.size() || remInlineOrderStart + remInlineCount > renderedBoxes.size())) {
                return null;
            }
            if (remBlockStart >= 0 && (remBlockEnd > currentDisplayList.size() || remBlockOrderStart + remBlockCount > renderedBoxes.size())) {
                return null;
            }

            // 後ろの層から先に削除（通常はインライン層が後ろ）
            if (remInlineStart >= 0 && remBlockStart >= 0) {
                if (remInlineStart >= remBlockEnd) {
                    currentDisplayList.subList(remInlineStart, remInlineEnd).clear();
                    renderedBoxes.subList(remInlineOrderStart, remInlineOrderStart + remInlineCount).clear();
                    currentDisplayList.subList(remBlockStart, remBlockEnd).clear();
                    renderedBoxes.subList(remBlockOrderStart, remBlockOrderStart + remBlockCount).clear();
                } else if (remBlockStart >= remInlineEnd) {
                    currentDisplayList.subList(remBlockStart, remBlockEnd).clear();
                    renderedBoxes.subList(remBlockOrderStart, remBlockOrderStart + remBlockCount).clear();
                    currentDisplayList.subList(remInlineStart, remInlineEnd).clear();
                    renderedBoxes.subList(remInlineOrderStart, remInlineOrderStart + remInlineCount).clear();
                } else {
                    return null; // 重複
                }
            } else if (remInlineStart >= 0) {
                currentDisplayList.subList(remInlineStart, remInlineEnd).clear();
                renderedBoxes.subList(remInlineOrderStart, remInlineOrderStart + remInlineCount).clear();
            } else if (remBlockStart >= 0) {
                currentDisplayList.subList(remBlockStart, remBlockEnd).clear();
                renderedBoxes.subList(remBlockOrderStart, remBlockOrderStart + remBlockCount).clear();
            }

            for (LayoutBox b : remSlices.inlineBoxes) { b.clearDlSlice(); scrollBoxes.remove(b); }
            for (LayoutBox b : remSlices.blockBoxes) { b.clearDlSlice(); scrollBoxes.remove(b); }
            if (windowedBuild) {
                Set<LayoutBox> goneBoxes = Collections.newSetFromMap(new IdentityHashMap<>());
                for (LayoutBox r : removed) markSubtree(r, goneBoxes);
                culledBoxes.removeIf(goneBoxes::contains);
            }
        }

        // 2. 追加ボックスの描画スライス生成（ブロック層・インライン層）
        List<DisplayItem> addedBlockItems = new ArrayList<>();
        List<LayoutBox> addedBlockBoxes = new ArrayList<>();
        List<DisplayItem> addedInlineItems = new ArrayList<>();
        List<LayoutBox> addedInlineBoxes = new ArrayList<>();
        decor.setDevicePixelRatio(currentRoot.devicePixelRatio);
        ClipRect clip = order.contentClipOf(parentBox);
        String parentId = contentParentIdOf(parentBox);

        for (LayoutBox a : added) {
            renderSubtreeSlices(a, clip, parentId, addedBlockItems, addedBlockBoxes, addedInlineItems, addedInlineBoxes);
        }

        // 3. 挿入位置の決定（ブロック層・インライン層）
        int insertBlockPos = -1, insertBlockOrder = -1;
        int insertInlinePos = -1, insertInlineOrder = -1;

        int removedBlockItemsLen = remBlockStart >= 0 ? (remBlockEnd - remBlockStart) : 0;

        if (remBlockStart >= 0) {
            insertBlockPos = remBlockStart;
            insertBlockOrder = remBlockOrderStart;
        }

        if (remInlineStart >= 0) {
            insertInlinePos = remInlineStart - removedBlockItemsLen;
            insertInlineOrder = remInlineOrderStart - remBlockCount;
        }

        if (insertBlockPos < 0 || insertInlinePos < 0) {
            int afterIdx = (parentBox.children != null && afterBox != null) ? parentBox.children.indexOf(afterBox) : -1;

            if (afterBox != null && afterIdx >= 0) {
                // afterBox 以前の描画済みボックスを逆順探索
                if (insertBlockPos < 0) {
                    LayoutBox lastBlock = null;
                    for (int i = afterIdx; i >= 0; i--) {
                        LayoutBox c = parentBox.children.get(i);
                        SubtreeSlices s = new SubtreeSlices();
                        collectSubtreeSlices(c, s);
                        for (int k = s.blockBoxes.size() - 1; k >= 0; k--) {
                            LayoutBox b = s.blockBoxes.get(k);
                            if (b.dlEnd >= 0 && b.dlOrder >= 0) {
                                lastBlock = b;
                                break;
                            }
                        }
                        if (lastBlock != null) break;
                        if (c.dlEnd >= 0 && c.dlOrder >= 0 && !c.display.isInlineLevel()) {
                            lastBlock = c;
                            break;
                        }
                    }
                    if (lastBlock != null) {
                        insertBlockPos = lastBlock.dlEnd;
                        insertBlockOrder = lastBlock.dlOrder + 1;
                    } else if (parentBox.dlEnd >= 0 && parentBox.dlOrder >= 0) {
                        insertBlockPos = parentBox.dlEnd;
                        insertBlockOrder = parentBox.dlOrder + 1;
                    } else {
                        // afterBox 以降の最初のブロックボックスを前方探索
                        LayoutBox firstBlock = null;
                        for (int i = afterIdx + 1; i < parentBox.children.size(); i++) {
                            LayoutBox c = parentBox.children.get(i);
                            if (added.contains(c)) continue;
                            SubtreeSlices s = new SubtreeSlices();
                            collectSubtreeSlices(c, s);
                            for (LayoutBox b : s.blockBoxes) {
                                if (b.dlStart >= 0 && b.dlOrder >= 0) {
                                    firstBlock = b;
                                    break;
                                }
                            }
                            if (firstBlock != null) break;
                            if (c.dlStart >= 0 && c.dlOrder >= 0 && !c.display.isInlineLevel()) {
                                firstBlock = c;
                                break;
                            }
                        }
                        if (firstBlock != null) {
                            insertBlockPos = firstBlock.dlStart;
                            insertBlockOrder = firstBlock.dlOrder;
                        } else {
                            insertBlockPos = parentBox.dlEnd >= 0 ? parentBox.dlEnd : 0;
                            insertBlockOrder = parentBox.dlOrder >= 0 ? parentBox.dlOrder + 1 : 0;
                        }
                    }
                }

                if (insertInlinePos < 0) {
                    LayoutBox lastInline = null;
                    for (int i = afterIdx; i >= 0; i--) {
                        LayoutBox c = parentBox.children.get(i);
                        SubtreeSlices s = new SubtreeSlices();
                        collectSubtreeSlices(c, s);
                        for (int k = s.inlineBoxes.size() - 1; k >= 0; k--) {
                            LayoutBox b = s.inlineBoxes.get(k);
                            if (b.dlEnd >= 0 && b.dlOrder >= 0) {
                                lastInline = b;
                                break;
                            }
                        }
                        if (lastInline != null) break;
                    }
                    if (lastInline != null) {
                        insertInlinePos = lastInline.dlEnd;
                        insertInlineOrder = lastInline.dlOrder + 1;
                    } else {
                        // afterBox 以降の最初のインラインボックスを前方探索
                        LayoutBox firstInline = null;
                        for (int i = afterIdx + 1; i < parentBox.children.size(); i++) {
                            LayoutBox c = parentBox.children.get(i);
                            if (added.contains(c)) continue;
                            SubtreeSlices s = new SubtreeSlices();
                            collectSubtreeSlices(c, s);
                            for (LayoutBox b : s.inlineBoxes) {
                                if (b.dlStart >= 0 && b.dlOrder >= 0) {
                                    firstInline = b;
                                    break;
                                }
                            }
                            if (firstInline != null) break;
                        }
                        if (firstInline != null) {
                            insertInlinePos = firstInline.dlStart;
                            insertInlineOrder = firstInline.dlOrder;
                        } else {
                            insertInlinePos = insertBlockPos;
                            insertInlineOrder = insertBlockOrder;
                        }
                    }
                }
            } else {
                // 先頭挿入または afterBox 不明
                if (insertBlockPos < 0) {
                    LayoutBox firstBlock = null;
                    if (parentBox.children != null) {
                        for (LayoutBox c : parentBox.children) {
                            if (added.contains(c)) continue;
                            SubtreeSlices s = new SubtreeSlices();
                            collectSubtreeSlices(c, s);
                            for (LayoutBox b : s.blockBoxes) {
                                if (b.dlStart >= 0 && b.dlOrder >= 0) {
                                    firstBlock = b;
                                    break;
                                }
                            }
                            if (firstBlock != null) break;
                            if (c.dlStart >= 0 && c.dlOrder >= 0 && !c.display.isInlineLevel()) {
                                firstBlock = c;
                                break;
                            }
                        }
                    }
                    if (firstBlock != null) {
                        insertBlockPos = firstBlock.dlStart;
                        insertBlockOrder = firstBlock.dlOrder;
                    } else {
                        insertBlockPos = parentBox.dlEnd >= 0 ? parentBox.dlEnd : 0;
                        insertBlockOrder = parentBox.dlOrder >= 0 ? parentBox.dlOrder + 1 : 0;
                    }
                }

                if (insertInlinePos < 0) {
                    LayoutBox firstInline = null;
                    if (parentBox.children != null) {
                        for (LayoutBox c : parentBox.children) {
                            if (added.contains(c)) continue;
                            SubtreeSlices s = new SubtreeSlices();
                            collectSubtreeSlices(c, s);
                            for (LayoutBox b : s.inlineBoxes) {
                                if (b.dlStart >= 0 && b.dlOrder >= 0) {
                                    firstInline = b;
                                    break;
                                }
                            }
                            if (firstInline != null) break;
                        }
                    }
                    if (firstInline != null) {
                        insertInlinePos = firstInline.dlStart;
                        insertInlineOrder = firstInline.dlOrder;
                    } else {
                        insertInlinePos = insertBlockPos;
                        insertInlineOrder = insertBlockOrder;
                    }
                }
            }
        }

        // 境界値チェックと安全なフォールバック
        if (insertBlockPos < 0 || insertBlockPos > currentDisplayList.size()
                || insertBlockOrder < 0 || insertBlockOrder > renderedBoxes.size()
                || insertInlinePos < 0 || insertInlinePos > currentDisplayList.size()
                || insertInlineOrder < 0 || insertInlineOrder > renderedBoxes.size()) {
            return null;
        }

        // 4. ブロック層の挿入と後続シフト
        int remBlockLen = remBlockStart >= 0 ? (remBlockEnd - remBlockStart) : 0;
        int deltaBlock = addedBlockItems.size() - remBlockLen;
        if (!addedBlockItems.isEmpty()) {
            for (LayoutBox b : addedBlockBoxes) {
                b.dlStart += insertBlockPos;
                b.dlEnd += insertBlockPos;
            }
            currentDisplayList.addAll(insertBlockPos, addedBlockItems);
            renderedBoxes.addAll(insertBlockOrder, addedBlockBoxes);
            for (LayoutBox ab : addedBlockBoxes) if (ab.node != null && PaintOrder.factsOf(ab.style).scrollbars()) scrollBoxes.add(ab);
            if (deltaBlock != 0) {
                for (int k = insertBlockOrder + addedBlockBoxes.size(), n = renderedBoxes.size(); k < n; k++) {
                    LayoutBox succ = renderedBoxes.get(k);
                    succ.dlStart += deltaBlock;
                    succ.dlEnd += deltaBlock;
                }
            }
        } else if (remBlockLen > 0) {
            for (int k = insertBlockOrder, n = renderedBoxes.size(); k < n; k++) {
                LayoutBox succ = renderedBoxes.get(k);
                succ.dlStart -= remBlockLen;
                succ.dlEnd -= remBlockLen;
            }
        }

        // ブロック層の変更がインライン層の挿入位置に及ぼす影響を反映
        int actualInlinePos = insertInlinePos + addedBlockItems.size();
        int actualInlineOrder = insertInlineOrder + addedBlockBoxes.size();

        // 5. インライン層の挿入と後続シフト
        int remInlineLen = remInlineStart >= 0 ? (remInlineEnd - remInlineStart) : 0;
        int deltaInline = addedInlineItems.size() - remInlineLen;
        if (!addedInlineItems.isEmpty()) {
            for (LayoutBox b : addedInlineBoxes) {
                b.dlStart += actualInlinePos;
                b.dlEnd += actualInlinePos;
            }
            currentDisplayList.addAll(actualInlinePos, addedInlineItems);
            renderedBoxes.addAll(actualInlineOrder, addedInlineBoxes);
            for (LayoutBox ab : addedInlineBoxes) if (ab.node != null && PaintOrder.factsOf(ab.style).scrollbars()) scrollBoxes.add(ab);
            if (deltaInline != 0) {
                for (int k = actualInlineOrder + addedInlineBoxes.size(), n = renderedBoxes.size(); k < n; k++) {
                    LayoutBox succ = renderedBoxes.get(k);
                    succ.dlStart += deltaInline;
                    succ.dlEnd += deltaInline;
                }
            }
        } else if (remInlineLen > 0) {
            for (int k = actualInlineOrder, n = renderedBoxes.size(); k < n; k++) {
                LayoutBox succ = renderedBoxes.get(k);
                succ.dlStart -= remInlineLen;
                succ.dlEnd -= remInlineLen;
            }
        }

        // 6. dlOrder の正規化（挿入位置以降のみ）
        int startShiftOrder = Math.max(0, Math.min(insertBlockOrder, actualInlineOrder) - 1);
        for (int k = startShiftOrder, n = renderedBoxes.size(); k < n; k++) {
            renderedBoxes.get(k).dlOrder = k;
        }

        // 7. ボックスの座標変化（dx, dy）のアイテムへの反映（挿入位置以降のみ）
        int translatedHits = 0;
        LayoutBox lastParentForClip = null;
        ClipRect cachedParentClip = null;

        for (int k = startShiftOrder, n = renderedBoxes.size(); k < n; k++) {
            LayoutBox b = renderedBoxes.get(k);
            BoxMemo m = boxMemo.get(b);
            if (m != null) {
                // 表示リストの中の項目は、記憶の位置から appliedDx/appliedDy だけ動いている（箱の位置は記憶の基準を足して見る）
                final int ax = m.appliedFragOnly ? 0 : m.appliedDx, ay = m.appliedFragOnly ? 0 : m.appliedDy;
                final int fx = m.appliedDx, fy = m.appliedDy;
                float dx = (m.nums != null && m.nums.length >= 2) ? (b.x - (m.nums[0] + ax)) : 0;
                float dy = (m.nums != null && m.nums.length >= 2) ? (b.y - (m.nums[1] + ay)) : 0;
                boolean isTextNode = (b.node != null && b.node.nodeType == NodeType.TEXT);
                if (dx == 0 && dy == 0 && !b.inlineFragments.isEmpty() && m.fragNums != null && m.fragNums.length >= 2) {
                    LayoutBox.InlineFragment f0 = b.inlineFragments.get(0);
                    dx = f0.x - (m.fragNums[0] + fx);
                    dy = f0.y - (m.fragNums[1] + fy);
                }
                if (dx != 0 || dy != 0) {
                    if (b.dlStart < 0 || b.dlEnd > currentDisplayList.size() || b.dlStart > b.dlEnd) continue;
                    STAT_TRANSLATED.increment();
                    translatedHits++;

                    // 親クリップのキャッシュ（親が同一なら再計算スキップ）
                    ClipRect expectedClip;
                    if (b.parent == lastParentForClip) {
                        expectedClip = cachedParentClip;
                    } else {
                        expectedClip = order.contentClipOf(b.parent);
                        lastParentForClip = b.parent;
                        cachedParentClip = expectedClip;
                    }

                    int itemCount = b.dlEnd - b.dlStart;
                    if (isTextNode && !b.inlineFragments.isEmpty()) {
                        int numFrags = b.inlineFragments.size();
                        if (numFrags == 1 && itemCount == 1 && currentDisplayList.get(b.dlStart).type() == ElementType.TEXT) {
                            // 最頻ケース: 単一フラグメント・単一TEXTアイテム（リスト行のテキストなど）
                            LayoutBox.InlineFragment f0 = b.inlineFragments.get(0);
                            int newTop = Math.round(f0.y);
                            int newLeft = Math.round(f0.x);
                            int newH = Math.round(f0.y + f0.h) - newTop;
                            int newW = Math.round(f0.x + f0.w) - newLeft;
                            DisplayItem it = currentDisplayList.get(b.dlStart);
                            RenderMeta meta = it.meta();
                            if (meta != null && meta.clipRect() != null && expectedClip != null && !expectedClip.equals(meta.clipRect())) {
                                meta = meta.withClip(expectedClip);
                            }
                            DisplayItem shifted = new DisplayItem(
                                    it.id(), it.baseNodeId(), it.type(),
                                    newLeft, newTop, newW, newH,
                                    it.text(), it.color(), it.bgColor(), it.events(),
                                    it.radius(), it.opacity(), it.translateX(), it.translateY(),
                                    it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(),
                                    it.animDef(), it.fitMode(), meta, it.key()
                            );
                            currentDisplayList.set(b.dlStart, shifted);
                        } else {
                            // 複数フラグメントまたは装飾アンカー・シャドウを含むケース
                            List<LayoutBox.InlineFragment> validFrags = new ArrayList<>(numFrags);
                            for (int fi = 0; fi < numFrags; fi++) {
                                LayoutBox.InlineFragment f = b.inlineFragments.get(fi);
                                if (f.text != null && !f.text.isBlank()) validFrags.add(f);
                            }
                            int textItemCount = 0;
                            for (int idx = b.dlStart; idx < b.dlEnd; idx++) {
                                if (currentDisplayList.get(idx).type() == ElementType.TEXT) textItemCount++;
                            }
                            if (textItemCount == validFrags.size()) {
                                int fIdx = 0;
                                LayoutBox.InlineFragment f0 = validFrags.get(0);
                                int baseTop = Math.round(f0.y);
                                int baseLeft = Math.round(f0.x);
                                float oldFragY = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[1] + fy : f0.y;
                                float oldFragX = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[0] + fx : f0.x;
                                int oldBaseTop = Math.round(oldFragY);
                                int oldBaseLeft = Math.round(oldFragX);
                                for (int idx = b.dlStart; idx < b.dlEnd; idx++) {
                                    DisplayItem it = currentDisplayList.get(idx);
                                    RenderMeta meta = it.meta();
                                    if (meta != null && meta.clipRect() != null && expectedClip != null && !expectedClip.equals(meta.clipRect())) {
                                        meta = meta.withClip(expectedClip);
                                    }
                                    if (it.type() == ElementType.TEXT) {
                                        LayoutBox.InlineFragment f = validFrags.get(fIdx++);
                                        int newTop = Math.round(f.y);
                                        int newLeft = Math.round(f.x);
                                        int newH = Math.round(f.y + f.h) - newTop;
                                        int newW = Math.round(f.x + f.w) - newLeft;
                                        DisplayItem shifted = new DisplayItem(
                                                it.id(), it.baseNodeId(), it.type(),
                                                newLeft, newTop, newW, newH,
                                                it.text(), it.color(), it.bgColor(), it.events(),
                                                it.radius(), it.opacity(), it.translateX(), it.translateY(),
                                                it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(),
                                                it.animDef(), it.fitMode(), meta, it.key()
                                        );
                                        currentDisplayList.set(idx, shifted);
                                    } else if (it.id() != null && b.node != null && it.id().equals(b.node.key())) {
                                        int newX = Math.round(b.x);
                                        int newY = Math.round(b.y);
                                        DisplayItem shifted = new DisplayItem(
                                                it.id(), it.baseNodeId(), it.type(),
                                                newX, newY, it.width(), it.height(),
                                                it.text(), it.color(), it.bgColor(), it.events(),
                                                it.radius(), it.opacity(), it.translateX(), it.translateY(),
                                                it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(),
                                                it.animDef(), it.fitMode(), meta, it.key()
                                        );
                                        currentDisplayList.set(idx, shifted);
                                    } else {
                                        int newY = baseTop + (it.y() - oldBaseTop);
                                        int newX = baseLeft + (it.x() - oldBaseLeft);
                                        DisplayItem shifted = new DisplayItem(
                                                it.id(), it.baseNodeId(), it.type(),
                                                newX, newY, it.width(), it.height(),
                                                it.text(), it.color(), it.bgColor(), it.events(),
                                                it.radius(), it.opacity(), it.translateX(), it.translateY(),
                                                it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(),
                                                it.animDef(), it.fitMode(), meta, it.key()
                                        );
                                        currentDisplayList.set(idx, shifted);
                                    }
                                }
                            } else {
                                LayoutBox.InlineFragment f0 = b.inlineFragments.get(0);
                                int targetTop = Math.round(f0.y);
                                int targetLeft = Math.round(f0.x);
                                float oldFragY = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[1] + fy : f0.y;
                                float oldFragX = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[0] + fx : f0.x;
                                int oldTop = Math.round(oldFragY);
                                int oldLeft = Math.round(oldFragX);
                                int targetH = Math.round(f0.y + f0.h) - targetTop;
                                int targetW = Math.round(f0.x + f0.w) - targetLeft;
                                int oldHInt = Math.round(oldFragY + f0.h) - oldTop;
                                int oldWInt = Math.round(oldFragX + f0.w) - oldLeft;
                                for (int idx = b.dlStart; idx < b.dlEnd; idx++) {
                                    DisplayItem it = currentDisplayList.get(idx);
                                    RenderMeta meta = it.meta();
                                    if (meta != null && meta.clipRect() != null && expectedClip != null && !expectedClip.equals(meta.clipRect())) {
                                        meta = meta.withClip(expectedClip);
                                    }
                                    if (it.id() != null && b.node != null && it.id().equals(b.node.key())) {
                                        int newX = Math.round(b.x);
                                        int newY = Math.round(b.y);
                                        DisplayItem shifted = new DisplayItem(
                                                it.id(), it.baseNodeId(), it.type(),
                                                newX, newY, it.width(), it.height(),
                                                it.text(), it.color(), it.bgColor(), it.events(),
                                                it.radius(), it.opacity(), it.translateX(), it.translateY(),
                                                it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(),
                                                it.animDef(), it.fitMode(), meta, it.key()
                                        );
                                        currentDisplayList.set(idx, shifted);
                                    } else {
                                        int newY = targetTop + (it.y() - oldTop);
                                        int newX = targetLeft + (it.x() - oldLeft);
                                        int newH = (it.height() == oldHInt) ? targetH : it.height();
                                        int newW = (it.width() == oldWInt) ? targetW : it.width();
                                        DisplayItem shifted = new DisplayItem(
                                                it.id(), it.baseNodeId(), it.type(),
                                                newX, newY, newW, newH,
                                                it.text(), it.color(), it.bgColor(), it.events(),
                                                it.radius(), it.opacity(), it.translateX(), it.translateY(),
                                                it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(),
                                                it.animDef(), it.fitMode(), meta, it.key()
                                        );
                                        currentDisplayList.set(idx, shifted);
                                    }
                                }
                            }
                        }
                    } else {
                        // ブロック要素
                        int targetTop = Math.round(b.y);
                        int targetLeft = Math.round(b.x);
                        float oldBoxY = m.nums != null && m.nums.length >= 2 ? m.nums[1] + ay : b.y;
                        float oldBoxX = m.nums != null && m.nums.length >= 2 ? m.nums[0] + ax : b.x;
                        float oldBoxH = m.nums != null && m.nums.length >= 4 ? m.nums[3] : b.h;
                        float oldBoxW = m.nums != null && m.nums.length >= 4 ? m.nums[2] : b.w;
                        int oldTop = Math.round(oldBoxY);
                        int oldLeft = Math.round(oldBoxX);
                        int targetH = Math.round(b.y + b.h) - targetTop;
                        int targetW = Math.round(b.x + b.w) - targetLeft;
                        int oldHInt = Math.round(oldBoxY + oldBoxH) - oldTop;
                        int oldWInt = Math.round(oldBoxX + oldBoxW) - oldLeft;
                        int shiftX = targetLeft - oldLeft;
                        int shiftY = targetTop - oldTop;
                        boolean sameDim = (targetH == oldHInt && targetW == oldWInt);

                        for (int idx = b.dlStart; idx < b.dlEnd; idx++) {
                            DisplayItem it = currentDisplayList.get(idx);
                            int newX, newY, newW, newH;
                            if (sameDim) {
                                newX = it.x() + shiftX;
                                newY = it.y() + shiftY;
                                newW = it.width();
                                newH = it.height();
                            } else {
                                newY = targetTop + (it.y() - oldTop);
                                newX = targetLeft + (it.x() - oldLeft);
                                newH = (it.height() == oldHInt) ? targetH : it.height();
                                newW = (it.width() == oldWInt) ? targetW : it.width();
                                if (it.id() != null && it.id().endsWith("_b_bottom")) {
                                    newY = (targetTop + targetH) - it.height();
                                } else if (it.id() != null && it.id().endsWith("_b_right")) {
                                    newX = (targetLeft + targetW) - it.width();
                                }
                            }
                            RenderMeta meta = it.meta();
                            if (meta != null && meta.clipRect() != null && expectedClip != null && !expectedClip.equals(meta.clipRect())) {
                                meta = meta.withClip(expectedClip);
                            }
                            if (meta != null && meta.hints() != null && meta.hints().scrollable()) {
                                meta = meta.withHints(meta.hints().withScroll(b.scrollWidth, b.scrollHeight));
                            }
                            DisplayItem shifted = new DisplayItem(
                                    it.id(), it.baseNodeId(), it.type(),
                                    newX, newY, newW, newH,
                                    it.text(), it.color(), it.bgColor(), it.events(),
                                    it.radius(), it.opacity(), it.translateX(), it.translateY(),
                                    it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(),
                                    it.animDef(), it.fitMode(), meta, it.key()
                            );
                            currentDisplayList.set(idx, shifted);
                        }
                    }

                    m.appliedDx = m.appliedDy = 0; // 記憶を今のリストの状態に揃える（下で項目も取り直す）
                    m.appliedFragOnly = false;
                    if (m.nums != null && m.nums.length >= 2) {
                        m.nums[0] = b.x;
                        m.nums[1] = b.y;
                    }
                    m.scrollW = b.scrollWidth;
                    m.scrollH = b.scrollHeight;
                    if (expectedClip != null) {
                        m.clip = expectedClip;
                    }
                    if (!b.inlineFragments.isEmpty() && m.fragNums != null && m.fragNums.length >= 2) {
                        for (int i = 0; i < b.inlineFragments.size() && (i * 8 + 1) < m.fragNums.length; i++) {
                            m.fragNums[i * 8] = b.inlineFragments.get(i).x;
                            m.fragNums[i * 8 + 1] = b.inlineFragments.get(i).y;
                        }
                    }
                    if (itemCount > 0) {
                        int memoEnd = b.dlEnd;
                        PaintOrder.ScrollContent content = order.scrollContentOf(b);
                        if (content != null && memoEnd > b.dlStart && content.contentId.equals(currentDisplayList.get(memoEnd - 1).id())) {
                            memoEnd--;
                        }
                        int mLen = memoEnd - b.dlStart;
                        DisplayItem[] arr = new DisplayItem[mLen];
                        for (int ai = 0; ai < mLen; ai++) {
                            arr[ai] = currentDisplayList.get(b.dlStart + ai);
                        }
                        m.items = java.util.Arrays.asList(arr);
                    }
                    b.touchPaint();
                }
            }
        }

        // 8. 祖先ボックスの寸法変化（親要素・body・html の NODE や背景）および scrollWidth/scrollHeight 変化を反映
        for (LayoutBox anc = parentBox; anc != null; anc = anc.parent) {
            BoxMemo am = boxMemo.get(anc);
            if (am != null && am.nums != null && am.nums.length >= 4) {
                float oldH = am.nums[3];
                float newH = anc.h;
                boolean hChanged = Float.compare(oldH, newH) != 0;
                boolean scrollChanged = Float.compare(am.scrollH, anc.scrollHeight) != 0 || Float.compare(am.scrollW, anc.scrollWidth) != 0;
                if ((hChanged || scrollChanged) && anc.dlStart >= 0 && anc.dlEnd > anc.dlStart) {
                    int targetTop = Math.round(anc.y);
                    int targetH = Math.round(anc.y + newH) - targetTop;
                    int oldTop = Math.round(am.nums[1]);
                    int oldHInt = Math.round(am.nums[1] + oldH) - oldTop;
                    int dh = targetH - oldHInt;
                    for (int idx = anc.dlStart; idx < anc.dlEnd; idx++) {
                        DisplayItem it = currentDisplayList.get(idx);
                        RenderMeta meta = it.meta();
                        if (meta != null && meta.hints() != null && meta.hints().scrollable()) {
                            meta = meta.withHints(meta.hints().withScroll(anc.scrollWidth, anc.scrollHeight));
                        }
                        boolean itemChanged = false;
                        int nextH = it.height();
                        if (hChanged) {
                            if (it.type() == ElementType.NODE) {
                                nextH = targetH;
                                itemChanged = true;
                            } else if (it.type() == ElementType.RECT || it.type() == ElementType.ROUNDED_BORDER) {
                                nextH = (it.height() == oldHInt) ? targetH : it.height() + dh;
                                itemChanged = true;
                            }
                        }
                        if (meta != it.meta()) {
                            itemChanged = true;
                        }
                        if (itemChanged) {
                            DisplayItem updated = new DisplayItem(
                                    it.id(), it.baseNodeId(), it.type(),
                                    it.x(), it.y(), it.width(), nextH,
                                    it.text(), it.color(), it.bgColor(), it.events(),
                                    it.radius(), it.opacity(), it.translateX(), it.translateY(),
                                    it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(),
                                    it.animDef(), it.fitMode(), meta, it.key()
                            );
                            currentDisplayList.set(idx, updated);
                        }
                    }
                    am.nums[3] = newH;
                    am.scrollH = anc.scrollHeight;
                    am.scrollW = anc.scrollWidth;
                    if (anc.dlStart >= 0 && anc.dlEnd >= anc.dlStart) {
                        int memoEnd = anc.dlEnd;
                        PaintOrder.ScrollContent content = order.scrollContentOf(anc);
                        if (content != null && memoEnd > anc.dlStart && content.contentId.equals(currentDisplayList.get(memoEnd - 1).id())) {
                            memoEnd--;
                        }
                        syncApplied(am); // 項目を今のリストから取り直すので、基準の位置も動いた分だけ進める
                        am.items = List.copyOf(currentDisplayList.subList(anc.dlStart, memoEnd));
                    }
                    anc.touchPaint();
                }
            }
        }

        // 8b. スクロールバーのトラック・つまみ寸法の同期（祖先コンテナおよび動いたスクロールコンテナ）
        Set<LayoutBox> scrollbarBoxes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (LayoutBox anc = parentBox; anc != null; anc = anc.parent) {
            if (anc.node != null && PaintOrder.factsOf(anc.style).scrollbars()) scrollbarBoxes.add(anc);
        }
        // 全箱ではなく、描画時に集めたスクロール可能な箱だけを見る（取り残しは dlOrder が今の並びと合うかで除く）
        for (LayoutBox b : scrollBoxes) {
            if (b.dlOrder >= 0 && b.dlOrder < renderedBoxes.size() && renderedBoxes.get(b.dlOrder) == b) scrollbarBoxes.add(b);
        }
        for (LayoutBox anc : scrollbarBoxes) {
            float scrollH = anc.scrollHeight;
            float scrollW = anc.scrollWidth;
            boolean wantsV = scrollH > anc.h + 2 && anc.h > 12;
            boolean wantsH = scrollW > anc.w + 2 && anc.w > 12;
            // 6 部品は 64-bit 整数鍵（nodeHandle<<16 | 部品番号）で引く。文字列 equals を全項目 × 6 回しない
            int sbHandle = anc.node.nodeId();
            boolean byKey = sbHandle > 0;
            long vtKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_TRACK_V) : 0L;
            long vKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_V) : 0L;
            long svKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_NODE_V) : 0L;
            long htKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_TRACK_H) : 0L;
            long hKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_H) : 0L;
            long shKey = byKey ? DisplayItem.keyOf(sbHandle, DisplayItem.PART_SCROLLBAR_NODE_H) : 0L;
            String vtId = byKey ? null : anc.node.key() + "_sb_vt";
            String vId = byKey ? null : anc.node.key() + "_sb_v";
            String svId = byKey ? null : anc.node.key() + "_scrollv";
            String htId = byKey ? null : anc.node.key() + "_sb_ht";
            String hId = byKey ? null : anc.node.key() + "_sb_h";
            String shId = byKey ? null : anc.node.key() + "_scrollh";
            int vtIdx = -1, vIdx = -1, svIdx = -1;
            int htIdx = -1, hIdx = -1, shIdx = -1;
            int sbGrace = 3;
            PaintOrder.ScrollContent content = order.scrollContentOf(anc);
            for (int i = currentDisplayList.size() - 1; i >= 0; i--) {
                DisplayItem item = currentDisplayList.get(i);
                if (byKey) {
                    long k = item.key();
                    if (vtIdx < 0 && k == vtKey) vtIdx = i;
                    else if (vIdx < 0 && k == vKey) vIdx = i;
                    else if (svIdx < 0 && k == svKey) svIdx = i;
                    else if (htIdx < 0 && k == htKey) htIdx = i;
                    else if (hIdx < 0 && k == hKey) hIdx = i;
                    else if (shIdx < 0 && k == shKey) shIdx = i;
                } else {
                    if (vtIdx < 0 && vtId.equals(item.id())) vtIdx = i;
                    if (vIdx < 0 && vId.equals(item.id())) vIdx = i;
                    if (svIdx < 0 && svId.equals(item.id())) svIdx = i;
                    if (htIdx < 0 && htId.equals(item.id())) htIdx = i;
                    if (hIdx < 0 && hId.equals(item.id())) hIdx = i;
                    if (shIdx < 0 && shId.equals(item.id())) shIdx = i;
                }
                // 6 部品は「縦（vt, sv, v）→ 横（ht, sh, h）」の順に連続して置かれ、持ち主の内容の後ろ（多くは表示リストの末尾付近）にある。
                // 欲しい軸の部品が全部見つかったら、番号の小さい側に隣り合う高々 3 件（反対軸の取り残し）だけ確かめて打ち切る。
                // 欲しい軸が無いときは、無いことを示すため従来どおり全走査する。
                boolean haveV = !wantsV || (vtIdx >= 0 && vIdx >= 0 && (content == null || svIdx >= 0));
                boolean haveH = !wantsH || (htIdx >= 0 && hIdx >= 0 && (content == null || shIdx >= 0));
                if (haveV && haveH && (wantsV || wantsH)) {
                    if (--sbGrace < 0) break;
                } else if (haveV && haveH && (content == null || (svIdx >= 0 && shIdx >= 0))) {
                    break;
                }
            }
            if (wantsV != (vIdx >= 0) || wantsH != (hIdx >= 0)) {
                // スクロールバーの出現・消滅はアイテム数増減を伴うため安全にフル描画へフォールバック
                return null;
            }
            ClipRect expectedClip = order.contentClipOf(anc.parent);
            int barThickness = 4;
            if (wantsV && vtIdx >= 0 && vIdx >= 0) {
                float curY = scrollState != null ? scrollState.getScrollY(anc.node) : 0f;
                float trackX = anc.x + anc.w - anc.bR - barThickness - 2;
                float trackY = anc.y + anc.bT + 2;
                float trackH = anc.h - anc.bT - anc.bB - 4;
                float thumbH = Math.max(12, trackH * (anc.h / scrollH));
                float maxScrollY = Math.max(1f, scrollH - anc.h);
                float thumbY = trackY + (trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f);

                int vtLeft = Math.round(trackX);
                int vtTop = Math.round(trackY);
                int vtW = Math.round(trackX + barThickness) - vtLeft;
                int vtH = Math.round(trackY + trackH) - vtTop;

                int vLeft = Math.round(trackX);
                int vTop = Math.round(thumbY);
                int vW = Math.round(trackX + barThickness) - vLeft;
                int vH = Math.round(thumbY + thumbH) - vTop;

                DisplayItem itVt = currentDisplayList.get(vtIdx);
                RenderMeta metaVt = itVt.meta();
                if (metaVt != null && metaVt.clipRect() != null && expectedClip != null) {
                    metaVt = metaVt.withClip(expectedClip);
                }
                currentDisplayList.set(vtIdx, new DisplayItem(
                        itVt.id(), itVt.baseNodeId(), itVt.type(),
                        vtLeft, vtTop, vtW, vtH,
                        itVt.text(), itVt.color(), itVt.bgColor(), itVt.events(),
                        itVt.radius(), itVt.opacity(), itVt.translateX(), itVt.translateY(),
                        itVt.scaleX(), itVt.scaleY(), itVt.rotate(), itVt.transitionDuration(),
                        itVt.animDef(), itVt.fitMode(), metaVt, itVt.key()
                ));

                if (content != null && svIdx >= 0) {
                    content.thumbVId = svId;
                    content.ky = (trackH - thumbH) / maxScrollY;
                    DisplayItem itSv = currentDisplayList.get(svIdx);
                    int svLeft = Math.round(anc.x);
                    int svTop = Math.round(anc.y);
                    int svW = Math.round(anc.x + anc.w) - svLeft;
                    int svH = Math.round(anc.y + anc.h) - svTop;
                    RenderMeta metaSv = itSv.meta();
                    if (metaSv != null && metaSv.clipRect() != null && expectedClip != null) {
                        metaSv = metaSv.withClip(expectedClip);
                    }
                    currentDisplayList.set(svIdx, new DisplayItem(
                            itSv.id(), itSv.baseNodeId(), itSv.type(),
                            svLeft, svTop, svW, svH,
                            itSv.text(), itSv.color(), itSv.bgColor(), itSv.events(),
                            itSv.radius(), itSv.opacity(), itSv.translateX(), itSv.translateY(),
                            itSv.scaleX(), itSv.scaleY(), itSv.rotate(), itSv.transitionDuration(),
                            itSv.animDef(), itSv.fitMode(), metaSv, itSv.key()
                    ));
                }

                DisplayItem itV = currentDisplayList.get(vIdx);
                RenderMeta metaV = itV.meta();
                if (metaV != null && metaV.clipRect() != null && expectedClip != null) {
                    metaV = metaV.withClip(expectedClip);
                }
                currentDisplayList.set(vIdx, new DisplayItem(
                        itV.id(), itV.baseNodeId(), itV.type(),
                        vLeft, vTop, vW, vH,
                        itV.text(), itV.color(), itV.bgColor(), itV.events(),
                        itV.radius(), itV.opacity(), itV.translateX(), itV.translateY(),
                        itV.scaleX(), itV.scaleY(), itV.rotate(), itV.transitionDuration(),
                        itV.animDef(), itV.fitMode(), metaV, itV.key()
                ));
            }
            if (wantsH && htIdx >= 0 && hIdx >= 0) {
                float curX = scrollState != null ? scrollState.getScrollX(anc.node) : 0f;
                float trackX = anc.x + anc.bL + 2;
                float trackY = anc.y + anc.h - anc.bB - barThickness - 2;
                float trackW = anc.w - anc.bL - anc.bR - 4;
                float thumbW = Math.max(12, trackW * (anc.w / scrollW));
                float maxScrollX = Math.max(1f, scrollW - anc.w);
                float thumbX = trackX + (trackW - thumbW) * Math.clamp(curX / maxScrollX, 0f, 1f);

                int htLeft = Math.round(trackX);
                int htTop = Math.round(trackY);
                int htW = Math.round(trackX + trackW) - htLeft;
                int htH = Math.round(trackY + barThickness) - htTop;

                int hLeft = Math.round(thumbX);
                int hTop = Math.round(trackY);
                int hW = Math.round(thumbX + thumbW) - hLeft;
                int hH = Math.round(trackY + barThickness) - hTop;

                DisplayItem itHt = currentDisplayList.get(htIdx);
                RenderMeta metaHt = itHt.meta();
                if (metaHt != null && metaHt.clipRect() != null && expectedClip != null) {
                    metaHt = metaHt.withClip(expectedClip);
                }
                currentDisplayList.set(htIdx, new DisplayItem(
                        itHt.id(), itHt.baseNodeId(), itHt.type(),
                        htLeft, htTop, htW, htH,
                        itHt.text(), itHt.color(), itHt.bgColor(), itHt.events(),
                        itHt.radius(), itHt.opacity(), itHt.translateX(), itHt.translateY(),
                        itHt.scaleX(), itHt.scaleY(), itHt.rotate(), itHt.transitionDuration(),
                        itHt.animDef(), itHt.fitMode(), metaHt, itHt.key()
                ));

                if (content != null && shIdx >= 0) {
                    content.thumbHId = shId;
                    content.kx = (trackW - thumbW) / maxScrollX;
                    DisplayItem itSh = currentDisplayList.get(shIdx);
                    int shLeft = Math.round(anc.x);
                    int shTop = Math.round(anc.y);
                    int shW = Math.round(anc.x + anc.w) - shLeft;
                    int shH = Math.round(anc.y + anc.h) - shTop;
                    RenderMeta metaSh = itSh.meta();
                    if (metaSh != null && metaSh.clipRect() != null && expectedClip != null) {
                        metaSh = metaSh.withClip(expectedClip);
                    }
                    currentDisplayList.set(shIdx, new DisplayItem(
                            itSh.id(), itSh.baseNodeId(), itSh.type(),
                            shLeft, shTop, shW, shH,
                            itSh.text(), itSh.color(), itSh.bgColor(), itSh.events(),
                            itSh.radius(), itSh.opacity(), itSh.translateX(), itSh.translateY(),
                            itSh.scaleX(), itSh.scaleY(), itSh.rotate(), itSh.transitionDuration(),
                            itSh.animDef(), itSh.fitMode(), metaSh, itSh.key()
                    ));
                }

                DisplayItem itH = currentDisplayList.get(hIdx);
                RenderMeta metaH = itH.meta();
                if (metaH != null && metaH.clipRect() != null && expectedClip != null) {
                    metaH = metaH.withClip(expectedClip);
                }
                currentDisplayList.set(hIdx, new DisplayItem(
                        itH.id(), itH.baseNodeId(), itH.type(),
                        hLeft, hTop, hW, hH,
                        itH.text(), itH.color(), itH.bgColor(), itH.events(),
                        itH.radius(), itH.opacity(), itH.translateX(), itH.translateY(),
                        itH.scaleX(), itH.scaleY(), itH.rotate(), itH.transitionDuration(),
                        itH.animDef(), itH.fitMode(), metaH, itH.key()
                ));
            }
        }

        // 9. bg_root の寸法同期
        if (!currentDisplayList.isEmpty()) {
            DisplayItem first = currentDisplayList.get(0);
            if ("bg_root".equals(first.id()) && currentRoot != null) {
                int expectedH = Math.round(Math.max(currentRoot.h, (float) rootH));
                if (first.height() != expectedH) {
                    DisplayItem updatedRoot = new DisplayItem(
                            first.id(), first.baseNodeId(), first.type(),
                            first.x(), first.y(), first.width(), expectedH,
                            first.text(), first.color(), first.bgColor(), first.events(),
                            first.radius(), first.opacity(), first.translateX(), first.translateY(),
                            first.scaleX(), first.scaleY(), first.rotate(), first.transitionDuration(),
                            first.animDef(), first.fitMode(), first.meta(), first.key()
                    );
                    currentDisplayList.set(0, updatedRoot);
                }
            }
        }

        generation++;
        buildSerial++;
        buildEpoch = LayoutBox.paintEpoch();
        lastElementCount = currentDisplayList.size();
        STAT_CHILD_SLICE_PATCH_HITS.increment();
        return currentDisplayList;
    }
}
