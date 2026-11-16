package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.CssKeywords;
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

    private static volatile java.util.function.BooleanSupplier parentRelativeSource =
            () -> !Boolean.getBoolean("enderframe.paint.noparentrelative");

    public static void setParentRelativeSource(java.util.function.BooleanSupplier source) {
        parentRelativeSource = source != null ? source : () -> true;
    }

    private static final Set<String> NON_FRAGMENT_TAGS = Set.of(
            "#text", "img",
            "input", "select", "progress", "meter", "textarea", "canvas", "svg"
    );

    private int rootW;

    private int rootH;

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
    /** 1 箱の未キャッシュ構築で使い回す ItemProps（DisplayItem 側には attrs Map / RenderHints だけが渡る）。 */
    private final ItemProps scratchAnchorProps = new ItemProps();
    private final ItemProps scratchPartsProps = new ItemProps();
    private final ItemProps scratchPassiveProps = new ItemProps();
    private final int[] scratchRawRadii = new int[4];

    /**
     * ビューポートの大きさが変わった（ウィンドウのリサイズ）。箱ごとの記憶は残す: ビューポート基準の単位（{@code vw} など）を
     * 使わない箱の項目は、ビューポートの大きさに依らないので、位置・寸法・スタイルが同じなら使い回せる
     * （{@link BoxMemo#viewportSensitive}）。部分木の保持は箱の記憶と別の鍵なので、捨てる。
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
private static final ItemProps EMPTY_PROPS = new ItemProps();
private PaintSupport.CaptureList reusableCaptureList;

public ImmutableItemList renderImmutable(LayoutBox box, boolean cullOffscreen, ImmutableItemList prev) {
    long prof = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
    try {
        int cap = lastElementCount + (lastElementCount >> 4) + 16;
        PaintSupport.CaptureList list = reusableCaptureList;
        if (list == null) {
            reusableCaptureList = list = new PaintSupport.CaptureList(cap);
        } else {
            list.resetForBuild();
            list.ensureCapacity(cap);
        }
        renderUnprofiled(box, cullOffscreen, list);
        return ImmutableItemList.from(prev, list);
    } finally {
        StageProfiler.end(PipelineStage.DISPLAY_LIST, prof);
    }
}

public List<DisplayItem> render(LayoutBox box, boolean cullOffscreen) {
    long prof = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
    try {
        return renderUnprofiled(box, cullOffscreen, null);
    } finally {
        StageProfiler.end(PipelineStage.DISPLAY_LIST, prof);
    }
}

private List<DisplayItem> renderUnprofiled(LayoutBox box, boolean cullOffscreen, List<DisplayItem> sink) {
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
    boolean relMode = parentRelativeSource.getAsBoolean();
    this.parentRelative = relMode;
    order.setParentRelative(relMode);
    // 前回と同じ件数で足りることが多い。倍々に伸ばしながら全件を写し直すのを避ける
    List<DisplayItem> elements = sink != null ? sink
            : ((MEMO_ENABLED || relMode)
            ? new PaintSupport.CaptureList(lastElementCount + (lastElementCount >> 4) + 16)
            : new ArrayList<>(lastElementCount + (lastElementCount >> 4) + 16));
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
                        "", 0, canvasBg, EMPTY_PROPS, 0, 1f, 0, 0, 1f, 1f, 0, 0, AnimationSpec.NONE, RenderMeta.EMPTY, bgRootKey);
            }
        }

        // レイヤーに描く箱: 開始の印の次に出る錨の位置を覚え、終了の印でそこから出し終えた項目の数（span）を錨に書く
        int[] openLayerStarts = null;
        LayerSpec[] openSpecs = null;
        LayoutBox[] openBoxes = null;
        int layerDepth = 0;
        // 部分木の保持は、全ての箱を描く描画でだけ（平行移動・画面外の省略では項目が欠ける／動く）。再利用は変更印が使える描画でだけ
        order.beginRender(buildSerial, buildEpoch, generation, !translating && !windowed, useChangeStamps && !translating);
        prunedOwners = 0;
        pruneWindowed = windowed;
        pruneSweeping = generation % PaintOrder.SWEEP_EVERY == 0;
        order.subtreePruner = cull && PRUNE_ENABLED ? this::pruneOffscreenSubtree : null;
        List<RenderTask> paintTaskList;
        try {
            paintTaskList = order.paintTasks(box);
        } finally {
            order.subtreePruner = null;
        }
        ownerCount += prunedOwners;
        for (int ti = 0, tn = paintTaskList.size(); ti < tn; ti++) {
            RenderTask task = paintTaskList.get(ti);
            if (task.layerStart() != null) {
                if (openLayerStarts == null) {
                    openLayerStarts = new int[8];
                    openSpecs = new LayerSpec[8];
                    openBoxes = new LayoutBox[8];
                } else if (layerDepth == openLayerStarts.length) {
                    int next = layerDepth << 1;
                    openLayerStarts = java.util.Arrays.copyOf(openLayerStarts, next);
                    openSpecs = java.util.Arrays.copyOf(openSpecs, next);
                    openBoxes = java.util.Arrays.copyOf(openBoxes, next);
                }
                openLayerStarts[layerDepth] = elements.size();
                openSpecs[layerDepth] = task.layerStart();
                openBoxes[layerDepth] = task.layerBox();
                layerDepth++;
                continue;
            }
            if (task.layerEnd()) {
                layerDepth--;
                int start = openLayerStarts[layerDepth];
                LayerSpec spec = openSpecs[layerDepth];
                LayoutBox layerBox = openBoxes[layerDepth];
                openSpecs[layerDepth] = null;
                openBoxes[layerDepth] = null;
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
                order.executeTask(task, elements);
                continue;
            }
            ownerCount++;
            if (cull && !containsBox(openBoxes, layerDepth, owner) && !task.hasMovingAncestor() && !inWindowGeometry(owner)) {
                BoxMemo kept = boxMemo.get(owner);
                if (kept != null) kept.generation = generation; // 省いた箱の記憶は、画面に戻ってくるまで残す
                owner.dlStart = -1;
                owner.paintCulled = true;
                owner.dlEnd = -1;
                owner.dlOrder = -1;
                if (windowed && isGeometryOwner(owner)) culledBoxes.add(owner);
                continue;
            }
            int start = elements.size();
            order.executeTask(task, elements);
            int end = elements.size();
            owner.dlStart = start;
            owner.paintCulled = false;
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

    private static boolean containsBox(LayoutBox[] boxes, int len, LayoutBox target) {
        if (boxes == null) return false;
        for (int i = 0; i < len; i++) if (boxes[i] == target) return true;
        return false;
    }

    /** 切り分け用: false なら、画面外の部分木も箱ごとに分類してから省く（従来の動き）。 */
    public static volatile boolean PRUNE_ENABLED = !Boolean.getBoolean("enderframe.paint.noprune");
    private int prunedOwners;
    /** 診断: 部分木ごと省いた回数。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_PRUNED_SUBTREES = new java.util.concurrent.atomic.LongAdder();
    private boolean pruneWindowed;
    private boolean pruneSweeping;

    /**
     * 画面外の箱を、部分木ごと分類せずに省く。省けるのは、箱とその子孫の全てが、変形・アニメーション・フィルター・スタッキングコンテキスト・
     * 固定/粘着配置を持たず、窓の判定を動かすスクロールの対象でもなく、位置が箱の枠の内に収まるとき（このとき子孫も全て、
     * 箱ごとの判定で省かれる）。省いた箱は、箱ごとに省いたときと同じ記録（項目の位置の取り消し・記憶の保持・窓の外の記録）を残す。
     */
    private boolean pruneOffscreenSubtree(LayoutBox c, boolean ancestorHasMoving) {
        if (c.isAnonymous || c.node == null || c.display == null || c.style == null || c.display.isNone()
                || "#text".equals(c.node.tag)) {
            return false;
        }
        boolean moving = ancestorHasMoving || PaintOrder.hasMovingProps(c.style);
        if (moving || inWindowGeometry(c)) return false;
        PrunedSubtree known = pruneCache.get(c);
        if (known != null && known.stillValid() && !known.anyShifted()) { // 部分木のどの箱にも描画の印が付いていない（中身も位置も同じ）ので、前の判定がそのまま使える
            if (!pruneSweeping && known.recordedSerial == buildSerial - 1 && known.recordedWindowed == pruneWindowed) {
                // 直前の作り直しでも省いていて、その記録（項目の位置の取り消し）がそのまま残っている。箱を 1 つずつ触らずに、数と窓の外の記録だけを足す
                prunedOwners += known.ownerCount;
                if (pruneWindowed) culledBoxes.addAll(java.util.Arrays.asList(known.geometryOwners));
            } else {
                known.record();
            }
            known.recordedSerial = buildSerial;
            known.recordedWindowed = pruneWindowed;
            STAT_PRUNED_SUBTREES.increment();
            return true;
        }
        pruneOwners.clear();
        pruneScrolls.clear();
        pruneVisited.clear();
        if (!cullableSubtree(c, c)) {
            pruneOwners.clear();
            return false;
        }
        PrunedSubtree fresh = new PrunedSubtree(
                pruneVisited.isEmpty() ? EMPTY_BOX_ARRAY : pruneVisited.toArray(EMPTY_BOX_ARRAY),
                pruneScrolls.isEmpty() ? EMPTY_NODE_ARRAY : pruneScrolls.toArray(EMPTY_NODE_ARRAY),
                LayoutBox.paintEpoch());
        if (pruneScrolls.size() <= 8) {
            if (pruneCache.size() > Math.max(8192, lastOwnerCount)) pruneCache.clear(); // 消えた箱の分が溜まり続けないように
            pruneCache.put(c, fresh);
        }
        fresh.record();
        fresh.recordedSerial = buildSerial;
        fresh.recordedWindowed = pruneWindowed;
        pruneOwners.clear();
        STAT_PRUNED_SUBTREES.increment();
        return true;
    }

    private static final LayoutBox[] EMPTY_BOX_ARRAY = new LayoutBox[0];
    private static final com.nishiyu.enderframelib.core.dom.node.HtmlNode[] EMPTY_NODE_ARRAY =
            new com.nishiyu.enderframelib.core.dom.node.HtmlNode[0];
    private final List<LayoutBox> pruneGeoScratch = new ArrayList<>();

    /** 窓の判定のために、このスクロールコンテナの節の位置がずれているか（ずれが 0 なら窓の判定には響かない）。 */
    private boolean shifted(com.nishiyu.enderframelib.core.dom.node.HtmlNode node) {
        if (windowShift.isEmpty()) return false;
        float[] d = windowShift.get(node);
        return d != null && (d[0] != 0f || d[1] != 0f);
    }

    /** 省けた部分木の判定: 子孫の持ち主の箱と、中のスクロールコンテナの節（窓の動きに依る分）。 */
    private final class PrunedSubtree {
        final LayoutBox[] boxes;
        final com.nishiyu.enderframelib.core.dom.node.HtmlNode[] scrolls;
        long epoch;
        /** 最後に記録を残した作り直し（{@code buildSerial}）と、そのとき窓付きだったか。 */
        long recordedSerial = -1;
        boolean recordedWindowed;
        int ownerCount;
        LayoutBox[] geometryOwners = EMPTY_BOX_ARRAY;

        /** 部分木の箱ごとの記録を残す（箱ごとに省いたときと同じ）。 */
        void record() {
            pruneGeoScratch.clear();
            int n = 0;
            for (LayoutBox b : boxes) {
                if (!isPruneOwner(b)) continue;
                n++;
                recordPruned(b);
                if (isGeometryOwner(b)) pruneGeoScratch.add(b);
            }
            ownerCount = n;
            geometryOwners = pruneGeoScratch.isEmpty() ? EMPTY_BOX_ARRAY : pruneGeoScratch.toArray(EMPTY_BOX_ARRAY);
            pruneGeoScratch.clear();
        }

        PrunedSubtree(LayoutBox[] boxes, com.nishiyu.enderframelib.core.dom.node.HtmlNode[] scrolls, long epoch) {
            this.boxes = boxes;
            this.scrolls = scrolls;
            this.epoch = epoch;
        }

        /** 判定したあとに、部分木のどの箱にも描画の印（位置・スタイルの変更）が付いていないか。 */
        boolean stillValid() {
            long now = LayoutBox.paintEpoch();
            if (now == epoch) return true;
            for (LayoutBox b : boxes) if (b.paintStamp > epoch) return false;
            epoch = now;
            return true;
        }

        boolean anyShifted() {
            for (com.nishiyu.enderframelib.core.dom.node.HtmlNode n : scrolls) if (shifted(n)) return true;
            return false;
        }
    }

    private final ArrayList<com.nishiyu.enderframelib.core.dom.node.HtmlNode> pruneScrolls = new ArrayList<>();

    /**
     * 省けた部分木の判定の保持。判定は箱の木と位置・スタイルだけで決まり、それらが変わると描画の印が付く
     * （{@link LayoutBox#paintEpoch}）ので、印が進んだら捨てる。窓の位置には依らないが、中のスクロールコンテナの節がずれていると
     * 窓の判定が変わるので、使うときに確かめる。
     */
    private final java.util.IdentityHashMap<LayoutBox, PrunedSubtree> pruneCache = new java.util.IdentityHashMap<>();
    private final ArrayList<LayoutBox> pruneVisited = new ArrayList<>();

    private static boolean isPruneOwner(LayoutBox b) {
        return b.w > 0 && b.h > 0 && !b.isAnonymous && !(b.display != null && b.display.isNone());
    }

    /** 部分木を調べる間に集める、省く箱（持ち主になる箱）。 */
    private final ArrayList<LayoutBox> pruneOwners = new ArrayList<>();

    private boolean cullableSubtree(LayoutBox b, LayoutBox top) {
        pruneVisited.add(b);
        if (b.display != null && b.display.isNone()) return true;
        if (b.w > 0 && b.h > 0 && !b.isAnonymous) pruneOwners.add(b);
        if (b.style != null && b.isScrollContainer()) pruneScrolls.add(b.node);
        if (b.style == null || b.node == null) return false;
        String tag = b.node.tag;
        boolean text = "#text".equals(tag);
        if (!text && !b.isAnonymous) {
            if (PaintOrder.factsOf(b.style).hasMovingProps() || b.isFixed || b.isSticky || order.createsStackingContext(b)) return false;
            if (shifted(b.node)) return false;
            if (b != top && (b.x < top.x || b.y < top.y || b.x + b.w > top.x + top.w || b.y + b.h > top.y + top.h)) return false;
        } else if (b.style != null && !text && PaintOrder.factsOf(b.style).hasMovingProps()) {
            return false;
        }
        if (b.contentAutoSkipped) return true;
        if (b.isVirtualizedFlow()) return false;
        if (b.children != null) {
            List<LayoutBox> kids = b.children;
            for (int i = 0, cn = kids.size(); i < cn; i++) {
                if (!cullableSubtree(kids.get(i), top)) return false;
            }
        }
        return true;
    }

    /** 部分木ごと省いた箱の、箱ごとに省いたときと同じ記録。 */
    private void recordPruned(LayoutBox b) {
        prunedOwners++;
        if (pruneSweeping) { // 記憶の掃除はこの世代でだけ使われを見る。それ以外の世代では、記憶を引かずに済ませる
            BoxMemo kept = boxMemo.get(b);
            if (kept != null) kept.generation = generation;
        }
        b.dlStart = -1;
        b.paintCulled = true;
        b.dlEnd = -1;
        b.dlOrder = -1;
        if (pruneWindowed && isGeometryOwner(b)) culledBoxes.add(b);
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

    /** 画面の外へ表示項目を作っておく幅（px）。スクロールを節の移動で済ませる範囲（UiView）はこれより小さく取る。広いほど、作り直しが起きる間隔が長い。 */
    public static final float CULL_MARGIN = Float.parseFloat(System.getProperty("enderframe.paint.cullmargin", "640"));
    static final int[] MOVING_PROPS = {
            CssPropertyIndex.TRANSFORM, CssPropertyIndex.TRANSLATE, CssPropertyIndex.ROTATE,
            CssPropertyIndex.SCALE, CssPropertyIndex.ANIMATION, CssPropertyIndex.ANIMATION_NAME, CssPropertyIndex.FILTER,
            CssPropertyIndex.PERSPECTIVE};

    /** 箱（文字の塊は持ち主の箱）の位置が、画面と余白の範囲に掛かるか。 */
    private boolean inWindowGeometry(LayoutBox box) {
        LayoutBox at = box;
        while (at.parent != null && ("#text".equals(at.node.tag) || at.isAnonymous)) at = at.parent;
        float sx = 0, sy = 0;
        if (!windowShift.isEmpty()) {
            for (LayoutBox a = at.parent; a != null; a = a.parent) {
                float[] d = windowShift.get(a.node);
                if (d != null) { sx += d[0]; sy += d[1]; }
            }
        }
        return at.x - sx + at.w >= -CULL_MARGIN && at.x - sx <= rootW + CULL_MARGIN
                && at.y - sy + at.h >= -CULL_MARGIN && at.y - sy <= rootH + CULL_MARGIN;
    }

    /** 箱の位置を決めたときから、スクロールコンテナが動いた分（節 → {dx, dy}）。窓の判定は、この分だけずらした位置で行う。 */
    private java.util.Map<com.nishiyu.enderframelib.core.dom.node.HtmlNode, float[]> windowShift = java.util.Map.of();

    /** 箱の位置を決め直さずに窓だけを動かして作り直すとき、スクロールの動いた分を渡す（終わったら null）。 */
    public void setWindowShift(java.util.Map<com.nishiyu.enderframelib.core.dom.node.HtmlNode, float[]> shift) {
        windowShift = shift != null ? shift : java.util.Map.of();
    }

    private int movingEpochSeq;
    private int activeMovingEpoch;
    private final ArrayList<LayoutBox> movingPathScratch = new ArrayList<>();

    private boolean hasMovingAncestor(LayoutBox box) {
        if (box == null) return false;
        if (box.movingStamped) return box.selfOrAncestorMoving;
        int epoch = activeMovingEpoch;
        if (epoch == 0) {
            for (LayoutBox b = box; b != null; b = b.parent) {
                if (b.style != null && PaintOrder.hasMovingProps(b.style)) return true;
            }
            return false;
        }
        movingPathScratch.clear();
        boolean moving = false;
        for (LayoutBox b = box; b != null; b = b.parent) {
            if (b.movingMemoEpoch == epoch) {
                moving = b.movingMemoResult;
                break;
            }
            movingPathScratch.add(b);
            if (b.style != null && PaintOrder.hasMovingProps(b.style)) {
                moving = true;
                break;
            }
        }
        for (int i = movingPathScratch.size() - 1; i >= 0; i--) {
            LayoutBox b = movingPathScratch.get(i);
            b.movingMemoEpoch = epoch;
            b.movingMemoResult = moving;
        }
        movingPathScratch.clear();
        return moving;
    }

    private boolean offscreen(LayoutBox box) {
        if (inWindowGeometry(box)) return false;
        return !hasMovingAncestor(box);
    }

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
    private void drawMaskItems(List<DisplayItem> elements, LayoutBox box) {
        PaintSupport.CaptureList cap = (parentRelative && PaintOrder.boxHasOwnNode(box) && elements instanceof PaintSupport.CaptureList c) ? c : null;
        int prevOx = cap != null ? cap.originX : 0, prevOy = cap != null ? cap.originY : 0;
        if (cap != null) cap.setOrigin(Math.round(box.x), Math.round(box.y));
        try {
            Corners radii = cornersShared(box.axisStyle(), box.w, box.h, rootW, rootH);
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
        /** {@link #match} が 2 を返したときの浮動小数の移動量（差分パッチで記憶の基準を進めるのに使う）。 */
        float appliedExactDx, appliedExactDy;
        /** {@link #match} が 2 を返したときの親相対原点の差分。 */
        int appliedOriginDx, appliedOriginDy;
        boolean appliedFragOnly;
        boolean appliedNonUniform;
        /** 各項目に対応する丸める前の {@code (x, y, w, h)}（4 要素ごと。小数移動の平行移動判定用）。 */
        float[] itemCoords;
        int generation;
        /** 記憶を作ったときのビューポートの大きさと、項目がそれに依る箱か（ビューポート基準の単位・ルートの背景）。 */
        int rootW, rootH;
        boolean viewportSensitive;
        List<DisplayItem> items;
        com.nishiyu.enderframelib.core.css.ComputedStyle style;
        com.nishiyu.enderframelib.core.css.ComputedStyle firstLineStyle;
        com.nishiyu.enderframelib.core.css.ComputedStyle placeholderStyle;
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
        /** 記憶を作ったときの親相対モードと、自身および親の NODE の丸め原点。 */
        boolean parentRelative;
        int originX, originY;
        int parentOriginX, parentOriginY;
        /** この箱を含む、保持された部分木。再利用されている間は、箱ごとに世代を書かなくても記憶は捨てられない。 */
        PaintOrder.Chunk chunk;
        boolean itemsMutable;
    }

    private boolean parentRelative = true;

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
        return fillNums(b, null);
    }

    static float[] fillNums(LayoutBox b, float[] reuse) {
        float[] a = reuse != null && reuse.length == NUMS_LENGTH ? reuse : new float[NUMS_LENGTH];
        a[0] = b.x; a[1] = b.y; a[2] = b.w; a[3] = b.h;
        a[4] = b.pL; a[5] = b.pT; a[6] = b.pR; a[7] = b.pB;
        a[8] = b.bL; a[9] = b.bT; a[10] = b.bR; a[11] = b.bB;
        a[12] = b.lineHeightPx;
        return a;
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

    /** クリップの連鎖（各持ち主のローカル空間の矩形と所有者）が同じか。ページ AABB だけ動いたクリップを再利用できるか見る。 */
    private static boolean sameClipChain(ClipRect a, ClipRect b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        List<ClipChainPart> ca = a.chain(), cb = b.chain();
        int n = ca.size();
        if (n == 0 || n != cb.size()) return false;
        for (int i = 0; i < n; i++) {
            if (!ca.get(i).equals(cb.get(i))) return false;
        }
        return true;
    }

    /**
     * 祖先クリップの境界変更が、この箱の全描画項目の範囲の外側だけで起きているか。
     * どの項目の描かれる矩形にも触れない辺の変化なら、記憶している {@code clipRect} のまま画素一致する。
     */
    private static boolean clipDifferenceOutsideItems(BoxMemo m, ClipRect newClip, LayoutBox box,
                                                      ScrollState scrollState,
                                                      int originX, int originY, int shiftX, int shiftY,
                                                      int[] itemRects) {
        ClipRect oldClip = m.clip;
        if (oldClip == null || newClip == null) return false;
        if (!java.util.Objects.equals(oldClip.ownerId(), newClip.ownerId())) return false;
        List<ClipChainPart> oldChain = oldClip.chain(), newChain = newClip.chain();
        if (oldChain.size() != newChain.size()) return false;
        boolean hasOwn = m.parentRelative && PaintOrder.boxHasOwnNode(box);
        int oldOriginDeltaX = hasOwn ? m.originX - originX : 0;
        int oldOriginDeltaY = hasOwn ? m.originY - originY : 0;
        if (!rectEdgesSafe(m.items, oldClip.minX(), oldClip.minY(), oldClip.maxX(), oldClip.maxY(),
                newClip.minX(), newClip.minY(), newClip.maxX(), newClip.maxY(),
                oldClip.radiusTopLeft(), oldClip.radiusTopRight(), oldClip.radiusBottomRight(), oldClip.radiusBottomLeft(),
                oldClip.radiusTopLeftV(), oldClip.radiusTopRightV(), oldClip.radiusBottomRightV(), oldClip.radiusBottomLeftV(),
                newClip.radiusTopLeft(), newClip.radiusTopRight(), newClip.radiusBottomRight(), newClip.radiusBottomLeft(),
                newClip.radiusTopLeftV(), newClip.radiusTopRightV(), newClip.radiusBottomRightV(), newClip.radiusBottomLeftV(),
                oldClip, originX, originY, oldOriginDeltaX, oldOriginDeltaY, shiftX, shiftY, itemRects)) {
            return false;
        }
        for (int ci = 0, cn = oldChain.size(); ci < cn; ci++) {
            ClipChainPart pa = oldChain.get(ci), pb = newChain.get(ci);
            if (pa.equals(pb)) continue;
            String oid = pa.ownerId();
            if (!java.util.Objects.equals(oid, pb.ownerId())) return false;
            float oAx = 0f, oAy = 0f, nAx = 0f, nAy = 0f;
            if (oid != null) {
                LayoutBox owner = null;
                boolean axisAlignedNoScroll = true;
                for (LayoutBox a = box.parent; a != null; a = a.parent) {
                    if (PaintOrder.hasMovingProps(a.style)
                            || (scrollState != null && a.node != null
                            && (scrollState.getScrollX(a.node) != 0f || scrollState.getScrollY(a.node) != 0f))) {
                        axisAlignedNoScroll = false;
                        break;
                    }
                    if (a.node != null && oid.equals(a.node.key())) {
                        owner = a;
                        break;
                    }
                }
                if (!axisAlignedNoScroll || owner == null) return false;
                oAx = pa.ownerOriginX();
                oAy = pa.ownerOriginY();
                nAx = pb.ownerOriginX();
                nAy = pb.ownerOriginY();
            }
            int len = pa.rectLength();
            if (len != pb.rectLength() || len < 8) return false;
            float aTLv = len >= 12 ? pa.rectAt(8) : 0f, aTRv = len >= 12 ? pa.rectAt(9) : 0f;
            float aBRv = len >= 12 ? pa.rectAt(10) : 0f, aBLv = len >= 12 ? pa.rectAt(11) : 0f;
            float bTLv = len >= 12 ? pb.rectAt(8) : 0f, bTRv = len >= 12 ? pb.rectAt(9) : 0f;
            float bBRv = len >= 12 ? pb.rectAt(10) : 0f, bBLv = len >= 12 ? pb.rectAt(11) : 0f;
            if (!rectEdgesSafe(m.items,
                    pa.rectAt(0) + oAx, pa.rectAt(1) + oAy, pa.rectAt(2) + oAx, pa.rectAt(3) + oAy,
                    pb.rectAt(0) + nAx, pb.rectAt(1) + nAy, pb.rectAt(2) + nAx, pb.rectAt(3) + nAy,
                    pa.rectAt(4), pa.rectAt(5), pa.rectAt(6), pa.rectAt(7), aTLv, aTRv, aBRv, aBLv,
                    pb.rectAt(4), pb.rectAt(5), pb.rectAt(6), pb.rectAt(7), bTLv, bTRv, bBRv, bBLv,
                    oldClip, originX, originY, oldOriginDeltaX, oldOriginDeltaY, shiftX, shiftY, itemRects)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSubClipOf(ClipRect sub, ClipRect parent) {
        List<ClipChainPart> sc = sub.chain(), pc = parent.chain();
        int pn = pc.size();
        if (sc.size() <= pn) return false;
        for (int i = 0; i < pn; i++) {
            if (!sc.get(i).equals(pc.get(i))) return false;
        }
        return true;
    }

    private static boolean rectEdgesSafe(List<DisplayItem> items,
                                         float oMinX, float oMinY, float oMaxX, float oMaxY,
                                         float nMinX, float nMinY, float nMaxX, float nMaxY,
                                         float oTL, float oTR, float oBR, float oBL,
                                         float oTLv, float oTRv, float oBRv, float oBLv,
                                         float nTL, float nTR, float nBR, float nBL,
                                         float nTLv, float nTRv, float nBRv, float nBLv,
                                         ClipRect oldClip, int originX, int originY,
                                         int oldOriginDeltaX, int oldOriginDeltaY, int shiftX, int shiftY,
                                         int[] itemRects) {
        if ((oTL != 0f || oTR != 0f || oBR != 0f || oBL != 0f || oTLv != 0f || oTRv != 0f || oBRv != 0f || oBLv != 0f
                || nTL != 0f || nTR != 0f || nBR != 0f || nBL != 0f || nTLv != 0f || nTRv != 0f || nBRv != 0f || nBLv != 0f)
                && (Float.compare(oTL, nTL) != 0 || Float.compare(oTR, nTR) != 0
                || Float.compare(oBR, nBR) != 0 || Float.compare(oBL, nBL) != 0
                || Float.compare(oTLv, nTLv) != 0 || Float.compare(oTRv, nTRv) != 0
                || Float.compare(oBRv, nBRv) != 0 || Float.compare(oBLv, nBLv) != 0)) {
            return false;
        }
        boolean diffMinX = Float.compare(oMinX, nMinX) != 0;
        boolean diffMinY = Float.compare(oMinY, nMinY) != 0;
        boolean diffMaxX = Float.compare(oMaxX, nMaxX) != 0;
        boolean diffMaxY = Float.compare(oMaxY, nMaxY) != 0;
        if (!diffMinX && !diffMinY && !diffMaxX && !diffMaxY) return true;
        float rL = Math.max(Math.max(oTL, oBL), Math.max(nTL, nBL));
        float rR = Math.max(Math.max(oTR, oBR), Math.max(nTR, nBR));
        float rT = Math.max(Math.max(oTLv > 0f ? oTLv : oTL, oTRv > 0f ? oTRv : oTR),
                Math.max(nTLv > 0f ? nTLv : nTL, nTRv > 0f ? nTRv : nTR));
        float rB = Math.max(Math.max(oBLv > 0f ? oBLv : oBL, oBRv > 0f ? oBRv : oBR),
                Math.max(nBLv > 0f ? nBLv : nBL, nBRv > 0f ? nBRv : nBR));
        float safeMinX = Math.max(oMinX, nMinX) + rL;
        float safeMinY = Math.max(oMinY, nMinY) + rT;
        float safeMaxX = Math.min(oMaxX, nMaxX) - rR;
        float safeMaxY = Math.min(oMaxY, nMaxY) - rB;
        int minShiftX = Math.min(oldOriginDeltaX, shiftX), maxShiftX = Math.max(oldOriginDeltaX, shiftX);
        int minShiftY = Math.min(oldOriginDeltaY, shiftY), maxShiftY = Math.max(oldOriginDeltaY, shiftY);
        for (int i = 0, n = items.size(); i < n; i++) {
            DisplayItem it = items.get(i);
            if (it.type() == ElementType.NODE) {
                // 箱自身のノードの範囲は親相対で、ここの境界検査に載らない。クリップが違うまま古い印を残すと
                // 子へ伝わるので、ノードがクリップを持つなら（ここへ来たのは箱のクリップが前と違うとき）「外側だけの差」とは言えない
                RenderMeta nodeMeta = it.meta();
                ClipRect nodeClip = nodeMeta != null ? nodeMeta.clipRect() : null;
                if (nodeClip != null) return false;
                continue;
            }
            if (it.translateX() != 0f || it.translateY() != 0f
                    || it.scaleX() != 1f || it.scaleY() != 1f || it.rotate() != 0f) {
                return false;
            }
            RenderMeta meta = it.meta();
            RenderHints h = meta != null ? meta.hints() : null;
            if (meta != null) {
                ClipRect mc = meta.clipRect();
                if (mc != null && !sameClip(mc, oldClip) && !isSubClipOf(mc, oldClip)) return false;
                if (h != null && h.skew() != 0f) return false;
            }
            float pad = (it.type() == ElementType.TEXT ? 4f : 1f) + (h != null && h.shadowBlur() > 0f ? 2f : 0f);
            float x0, y0, x1, y1;
            if (itemRects != null) {
                int base = i * 4;
                float ox0 = it.x() + oldOriginDeltaX, oy0 = it.y() + oldOriginDeltaY;
                float nx0 = itemRects[base], ny0 = itemRects[base + 1];
                float minXLocal = Math.min(ox0, nx0), minYLocal = Math.min(oy0, ny0);
                float maxXLocal = Math.max(ox0 + it.width(), nx0 + itemRects[base + 2]);
                float maxYLocal = Math.max(oy0 + it.height(), ny0 + itemRects[base + 3]);
                if (h != null && h.shadowShape() != null) {
                    ShadowShape ss = h.shadowShape();
                    minXLocal = Math.min(minXLocal, ss.x0() + oldOriginDeltaX);
                    minYLocal = Math.min(minYLocal, ss.y0() + oldOriginDeltaY);
                    maxXLocal = Math.max(maxXLocal, ss.x1() + oldOriginDeltaX);
                    maxYLocal = Math.max(maxYLocal, ss.y1() + oldOriginDeltaY);
                }
                x0 = minXLocal + originX - pad;
                y0 = minYLocal + originY - pad;
                x1 = maxXLocal + originX + pad;
                y1 = maxYLocal + originY + pad;
            } else {
                float ix0 = it.x(), iy0 = it.y(), ix1 = ix0 + it.width(), iy1 = iy0 + it.height();
                if (h != null && h.shadowShape() != null) {
                    ShadowShape ss = h.shadowShape();
                    ix0 = Math.min(ix0, ss.x0());
                    iy0 = Math.min(iy0, ss.y0());
                    ix1 = Math.max(ix1, ss.x1());
                    iy1 = Math.max(iy1, ss.y1());
                }
                x0 = ix0 + originX + minShiftX - pad;
                y0 = iy0 + originY + minShiftY - pad;
                x1 = ix1 + originX + maxShiftX + pad;
                y1 = iy1 + originY + maxShiftY + pad;
            }
            if (diffMinX && x0 < safeMinX) return false;
            if (diffMinY && y0 < safeMinY) return false;
            if (diffMaxX && x1 > safeMaxX) return false;
            if (diffMaxY && y1 > safeMaxY) return false;
        }
        return true;
    }

    private static com.nishiyu.enderframelib.core.css.ComputedStyle[] ancestorStyles(LayoutBox box,
            com.nishiyu.enderframelib.core.css.ComputedStyle[] reuse) {
        int n = 0;
        for (LayoutBox a = box.parent; a != null; a = a.parent) n++;
        com.nishiyu.enderframelib.core.css.ComputedStyle[] out = reuse != null && reuse.length == n
                ? reuse : new com.nishiyu.enderframelib.core.css.ComputedStyle[n];
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
        if (m.viewportSensitive && (m.rootW != rootW || m.rootH != rootH)) return false;
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
        if (m.parentRelative != parentRelative || m.parentBox != box.parent || box.pathPaintStamp(buildSerial) > m.epoch
                || m.attrVersion != box.node.attrVersion()
                || m.canvasSource != (box == canvasBackgroundSource) || m.interactive != box.hasInteractiveRules
                || m.suppressShadow != suppressTextShadow || !sameClip(m.clip, clip)
                || !sameStyle(m.placeholderStyle, box.placeholderStyle)
                || !java.util.Objects.equals(m.parentId, parentId)) {
            return false;
        }
        if (parentRelative) {
            LayoutBox pNode = PaintOrder.effectiveParentNodeBox(box, parentId);
            int curPox = pNode != null ? Math.round(pNode.x) : 0;
            int curPoy = pNode != null ? Math.round(pNode.y) : 0;
            if (curPox != m.parentOriginX || curPoy != m.parentOriginY) return false;
        }
        return true;
    }

    /** 平行移動で使い回すときの、箱の動き（{@link #match} が 2 を返したとき有効）。 */
    private float hitDx, hitDy;
    private int hitIntDx, hitIntDy;
    private boolean hitFragmentsOnly;
    private boolean hitPerItem;
    private int[] hitRects = new int[32];
    private boolean hitParentRelReuse;
    private boolean hitRelPosChanged, hitScrollChanged, hitClipChanged, hitReuseFragOnly;
    private int hitNewRelX, hitNewRelY, hitCurOx, hitCurOy, hitCurPox, hitCurPoy;
    private int hitNewRelW, hitNewRelH, hitPartDx, hitPartDy;
    private float hitReuseFracDx, hitReuseFracDy;

    /**
     * 記憶が今の箱に使えるか。0: 使えない / 1: 項目をそのまま使える / 2: 箱がまるごと {@code (hitDx, hitDy)} だけ動いただけ
     * （または、クリップだけが違う）なので、前回の項目を平行移動して使える。
     * 2 を返すのは {@link #translating}（スクロールなど位置だけが変わる作り直し）か、{@link #exactTranslation} で
     * 整数移動、または小数移動でも各項目の丸め後の差が均一かつ寸法が変わらない箱のとき。
     */
    /** 診断: {@link #match} が 0（使えない）を返した理由別の回数（添字は match 内の return 0 の出現順。1 始まり、[0] は未使用）。 */
    public static final java.util.concurrent.atomic.LongAdder[] STAT_MATCH_FAIL = newAdders(24);
    /** 診断: {@link #appendTranslated}（全項目の複製を伴う平行移動）を実行した回数。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_APPEND_TRANSLATED = new java.util.concurrent.atomic.LongAdder();

    private static int matchFail(int reason) {
        STAT_MATCH_FAIL[reason].increment();
        return 0;
    }

    private int match(BoxMemo m, LayoutBox box, ClipRect clip, String parentId) {
        hitParentRelReuse = false;
        hitPerItem = false;
        if (m.parentRelative != parentRelative) return matchFail(5);
        if (m.viewportSensitive && (m.rootW != rootW || m.rootH != rootH)) return matchFail(1);
        if (!sameStyle(m.style, box.style) || !sameStyle(m.firstLineStyle, box.firstLineStyle)
                || !sameStyle(m.placeholderStyle, box.placeholderStyle)
                || m.canvasSource != (box == canvasBackgroundSource) || m.interactive != box.hasInteractiveRules
                || m.suppressShadow != suppressTextShadow) return matchFail(2);
        boolean scrollChanged = m.carriesContentExtent
                && (Float.compare(m.scrollW, box.scrollWidth) != 0 || Float.compare(m.scrollH, box.scrollHeight) != 0);
        if (!parentRelative && scrollChanged) return matchFail(3);
        float dx = box.x - m.nums[0], dy = box.y - m.nums[1];
        float effDx = dx, effDy = dy;
        int curOx = Math.round(box.x), curOy = Math.round(box.y);
        int curPox = 0, curPoy = 0;
        boolean relPosChanged = false;
        float localDx = dx, localDy = dy;
        boolean hasOwnNode = parentRelative && PaintOrder.boxHasOwnNode(box);
        if (parentRelative) {
            LayoutBox pNode = PaintOrder.effectiveParentNodeBox(box, parentId);
            curPox = pNode != null ? Math.round(pNode.x) : 0;
            curPoy = pNode != null ? Math.round(pNode.y) : 0;
            relPosChanged = (curOx - curPox) != (m.originX - m.parentOriginX)
                    || (curOy - curPoy) != (m.originY - m.parentOriginY);
            localDx = dx - (hasOwnNode ? (curOx - m.originX) : (curPox - m.parentOriginX));
            localDy = dy - (hasOwnNode ? (curOy - m.originY) : (curPoy - m.parentOriginY));
        }
        // 文字の箱は自分の位置を持たず、断片だけが置かれる。箱が動かないのに断片が動いたときは、断片の動きを箱の動きとして見る
        // （後続の行が下へずれるとき、文字の箱が毎回描き直しになるのを避ける）。断片どうしの動きが揃っているかは下で確かめる
        // 箱の位置で置かれる NODE の項目は動かさず、断片の位置で置かれる TEXT の項目だけを動かす（hitFragmentsOnly）
        boolean fragmentsOnly = false;
        if (dx == 0 && dy == 0 && m.nodeAndTextOnly && m.fragNums.length >= 2 && !box.inlineFragments.isEmpty()) {
            LayoutBox.InlineFragment f0 = box.inlineFragments.get(0);
            effDx = f0.x - m.fragNums[0];
            effDy = f0.y - m.fragNums[1];
            localDx = parentRelative && !hasOwnNode ? effDx - (curPox - m.parentOriginX) : effDx;
            localDy = parentRelative && !hasOwnNode ? effDy - (curPoy - m.parentOriginY) : effDy;
            fragmentsOnly = effDx != 0 || effDy != 0;
        }
        boolean moved = parentRelative ? (localDx != 0 || localDy != 0) : (effDx != 0 || effDy != 0);
        boolean intMove = isIntegerTranslation(localDx) && isIntegerTranslation(localDy);
        boolean exactFracCheck = false;
        // スクロールの作り直しは丸めて動かす。リサイズ・子の増減では、整数移動に加えて小数移動も丸め後の差が一致する箱なら使い回す
        boolean moveOk = m.translatable && (translating
                || (exactTranslation && TRANSLATE_ENABLED && (!m.carriesContentExtent || parentRelative) && intMove));
        if (moved && !moveOk && exactTranslation && TRANSLATE_ENABLED && (!m.carriesContentExtent || parentRelative)
                && m.translatable && m.itemCoords != null && m.itemCoords.length == m.items.size() * 4) {
            exactFracCheck = true;
            moveOk = true;
        }
        if (moved && !moveOk) return matchFail(4);
        if (m.nums.length != NUMS_LENGTH) return matchFail(5);
        for (int i = 2; i < NUMS_LENGTH; i++) if (Float.compare(m.nums[i], numAt(box, i)) != 0) return matchFail(6);
        boolean clipSame = sameClip(m.clip, clip);
        boolean clipChainSame = clipSame || (parentRelative && sameClipChain(m.clip, clip));
        if (parentRelative) {
            if (!clipChainSame && !moveOk) return matchFail(7);
        } else {
            if (!clipSame && !moveOk) return matchFail(7);
        }
        if (!java.util.Objects.equals(m.parentId, parentId)) return matchFail(8);
        if (!java.util.Objects.equals(m.displayText, box.displayText) || !java.util.Objects.equals(m.shaped, box.shapedLines)) return matchFail(9);
        if (m.attrVersion != box.node.attrVersion()) return matchFail(10);
        int depth = 0;
        for (LayoutBox a = box.parent; a != null; a = a.parent, depth++) {
            if (depth >= m.ancestors.length || !sameStyle(m.ancestors[depth], a.style)) return matchFail(11);
        }
        if (depth != m.ancestors.length) return matchFail(12);
        List<LayoutBox.InlineFragment> frags = box.inlineFragments;
        if (m.fragText.length != frags.size()) return matchFail(13);
        int r = 0;
        for (int i = 0; i < frags.size(); i++) {
            LayoutBox.InlineFragment f = frags.get(i);
            int n = i * 8;
            if (!java.util.Objects.equals(m.fragText[i], f.text)) return matchFail(14);
            // 断片の位置は、箱と同じだけ動いていること（動き方が違う断片があれば、平行移動では済まない）
            if ((effDx != 0 || effDy != 0) ? (Math.abs(f.x - m.fragNums[n] - effDx) > 1e-3f || Math.abs(f.y - m.fragNums[n + 1] - effDy) > 1e-3f)
                    : (Float.compare(m.fragNums[n], f.x) != 0 || Float.compare(m.fragNums[n + 1], f.y) != 0)) return matchFail(15);
            if (Float.compare(m.fragNums[n + 2], f.w) != 0 || Float.compare(m.fragNums[n + 3], f.h) != 0
                    || Float.compare(m.fragNums[n + 4], f.baseline) != 0 || m.fragNums[n + 5] != (f.startEdge ? 1f : 0f)
                    || m.fragNums[n + 6] != (f.endEdge ? 1f : 0f) || Float.compare(m.fragNums[n + 7], f.descent) != 0) return matchFail(16);
            if (m.fragRefs[r++] != f.box || !sameStyle((com.nishiyu.enderframelib.core.css.ComputedStyle) m.fragRefs[r++], f.box != null ? f.box.style : null)) return matchFail(17);
            List<LayoutBox> parents = f.parents;
            int pn = parents == null ? 0 : parents.size();
            if (!(m.fragRefs[r++] instanceof Integer count) || count != pn) return matchFail(18);
            for (int k = 0; k < pn; k++) {
                if (m.fragRefs[r++] != parents.get(k) || !sameStyle((com.nishiyu.enderframelib.core.css.ComputedStyle) m.fragRefs[r++], parents.get(k).style)) return matchFail(19);
            }
            com.nishiyu.enderframelib.core.css.ComputedStyle ownerFl = f.firstLineOwner != null ? f.firstLineOwner.firstLineStyle : null;
            if (m.fragRefs[r++] != f.firstLineOwner || !sameStyle((com.nishiyu.enderframelib.core.css.ComputedStyle) m.fragRefs[r++], ownerFl)) return matchFail(17);
        }
        if (parentRelative && !moved) {
            if (!clipSame && clipDifferenceOutsideItems(m, clip, box, scrollState, hasOwnNode ? curOx : curPox, hasOwnNode ? curOy : curPoy, 0, 0, null)) {
                clipSame = true;
            }
            if (!relPosChanged && !scrollChanged && clipSame) {
                syncParentRelPositionOnly(m, box, curOx, curOy, curPox, curPoy, dx, dy);
                return 1;
            }
            if (clipChainSame || moveOk) {
                hitParentRelReuse = true;
                hitRelPosChanged = relPosChanged;
                hitScrollChanged = scrollChanged;
                hitClipChanged = !clipSame;
                hitReuseFragOnly = false;
                hitReuseFracDx = 0f;
                hitReuseFracDy = 0f;
                hitPartDx = 0;
                hitPartDy = 0;
                hitNewRelX = curOx - curPox;
                hitNewRelY = curOy - curPoy;
                hitCurOx = curOx;
                hitCurOy = curOy;
                hitCurPox = curPox;
                hitCurPoy = curPoy;
                hitDx = dx;
                hitDy = dy;
                return 2;
            }
        }
        if (!moved && clipSame) return 1;
        hitPerItem = false;
        if (exactFracCheck) {
            if (!computeItemShifts(m, localDx, localDy, fragmentsOnly, parentRelative, curOx - curPox, curOy - curPoy, box)) return matchFail(4);
            if (parentRelative
                    && (!translating || (!hitPerItem && hitIntDx == 0 && hitIntDy == 0))
                    && !hasMovingShadowShape(m.items, true, fragmentsOnly)) {
                boolean effClipSame = clipSame || clipDifferenceOutsideItems(
                        m, clip, box, scrollState, hasOwnNode ? curOx : curPox, hasOwnNode ? curOy : curPoy,
                        hitIntDx, hitIntDy, hitPerItem ? hitRects : null);
                hitParentRelReuse = true;
                hitRelPosChanged = relPosChanged;
                hitScrollChanged = scrollChanged;
                hitClipChanged = !effClipSame;
                hitReuseFragOnly = fragmentsOnly;
                hitReuseFracDx = localDx;
                hitReuseFracDy = localDy;
                hitPartDx = hitIntDx;
                hitPartDy = hitIntDy;
                hitNewRelX = curOx - curPox;
                hitNewRelY = curOy - curPoy;
                hitCurOx = curOx;
                hitCurOy = curOy;
                hitCurPox = curPox;
                hitCurPoy = curPoy;
                hitDx = dx;
                hitDy = dy;
                return 2;
            }
        } else {
            hitIntDx = Math.round(localDx);
            hitIntDy = Math.round(localDy);
            if (parentRelative && !translating && localDx == hitIntDx && localDy == hitIntDy
                    && !hasMovingShadowShape(m.items, true, fragmentsOnly)) {
                boolean effClipSame = clipSame || clipDifferenceOutsideItems(
                        m, clip, box, scrollState, hasOwnNode ? curOx : curPox, hasOwnNode ? curOy : curPoy,
                        hitIntDx, hitIntDy, null);
                hitParentRelReuse = true;
                hitRelPosChanged = relPosChanged;
                hitScrollChanged = scrollChanged;
                hitClipChanged = !effClipSame;
                hitReuseFragOnly = fragmentsOnly;
                hitReuseFracDx = localDx;
                hitReuseFracDy = localDy;
                hitPartDx = hitIntDx;
                hitPartDy = hitIntDy;
                hitNewRelX = curOx - curPox;
                hitNewRelY = curOy - curPoy;
                hitCurOx = curOx;
                hitCurOy = curOy;
                hitCurPox = curPox;
                hitCurPoy = curPoy;
                hitDx = dx;
                hitDy = dy;
                return 2;
            }
        }
        hitDx = localDx;
        hitDy = localDy;
        hitFragmentsOnly = fragmentsOnly;
        hitRelPosChanged = relPosChanged;
        hitScrollChanged = scrollChanged;
        hitClipChanged = !clipSame;
        hitNewRelX = curOx - curPox;
        hitNewRelY = curOy - curPoy;
        hitCurOx = curOx;
        hitCurOy = curOy;
        hitCurPox = curPox;
        hitCurPoy = curPoy;
        hitNewRelW = Math.round(box.x + box.w) - curOx;
        hitNewRelH = Math.round(box.y + box.h) - curOy;
        return 2;
    }

    private static void syncParentRelPositionOnly(BoxMemo m, LayoutBox box, int curOx, int curOy, int curPox, int curPoy, float dx, float dy) {
        if (dx == 0f && dy == 0f && curOx == m.originX && curOy == m.originY && curPox == m.parentOriginX && curPoy == m.parentOriginY
                && (m.fragNums == null || box.inlineFragments.isEmpty() || (box.inlineFragments.get(0).x == m.fragNums[0] && box.inlineFragments.get(0).y == m.fragNums[1]))) {
            return;
        }
        m.nums[0] = box.x;
        m.nums[1] = box.y;
        m.originX = curOx;
        m.originY = curOy;
        m.parentOriginX = curPox;
        m.parentOriginY = curPoy;
        if (m.fragNums != null && !box.inlineFragments.isEmpty()) {
            for (int j = 0, fn = box.inlineFragments.size(); j < fn && j * 8 + 1 < m.fragNums.length; j++) {
                LayoutBox.InlineFragment f = box.inlineFragments.get(j);
                m.fragNums[j * 8] = f.x;
                m.fragNums[j * 8 + 1] = f.y;
            }
        }
    }

    private static boolean hasMovingShadowShape(List<DisplayItem> items, boolean parentRel, boolean fragOnly) {
        for (int i = 0, n = items.size(); i < n; i++) {
            DisplayItem it = items.get(i);
            if (parentRel && i == 0 && it.type() == ElementType.NODE && it.hints().relativeOrigin()) continue;
            if (fragOnly && !movesWithFragment(it)) continue;
            RenderMeta meta = it.meta();
            if (meta != null && meta.hints() != null && meta.hints().shadowShape() != null) return true;
        }
        return false;
    }

    private static boolean movesWithFragment(DisplayItem it) {
        return it.type() == ElementType.TEXT || it.id().contains("_tsh");
    }

    private static boolean nearHalfBoundary(float v, float base, float delta) {
        float diffFromHalf = Math.abs(Math.abs(v - (float) Math.rint(v)) - 0.5f);
        boolean exactGrid = com.nishiyu.enderframelib.core.kernel.LayoutUnit.isOnGrid(base)
                && com.nishiyu.enderframelib.core.kernel.LayoutUnit.isOnGrid(delta);
        if (diffFromHalf >= (exactGrid ? 1e-4f : 2e-3f)) return false;
        return diffFromHalf > 0f || !exactGrid;
    }

    /** {@link #computeItemShifts(BoxMemo, float, float, boolean, boolean, int, int, LayoutBox)} の互換版。 */
    private boolean computeItemShifts(BoxMemo m, float dx, float dy, boolean fragmentsOnly) {
        return computeItemShifts(m, dx, dy, fragmentsOnly, false, 0, 0, null);
    }

    /**
     * 小数移動 {@code (dx, dy)} で、全移動対象項目について {@link PaintSupport#addElement} と同じ両端丸めを適用し、
     * 整数移動量と整数寸法を求める。全項目の移動量と寸法が一致すれば一括平行移動、項目ごとにずれる場合も
     * .5 境界を跨ぐ恐れがなければ項目ごとの丸め結果として平行移動を許す。
     */
    private boolean computeItemShifts(BoxMemo m, float dx, float dy, boolean fragmentsOnly,
                                      boolean parentRel, int newRelX, int newRelY, LayoutBox box) {
        List<DisplayItem> items = m.items;
        float[] coords = m.itemCoords;
        if (coords == null || coords.length != items.size() * 4) return false;
        int n = items.size();
        if (hitRects.length < n * 4) hitRects = new int[n * 4];
        boolean hasShift = false;
        int uniformX = 0, uniformY = 0;
        boolean uniform = true;
        for (int i = 0; i < n; i++) {
            DisplayItem it = items.get(i);
            int base = i * 4;
            boolean isBoxNode = parentRel && i == 0 && it.type() == ElementType.NODE;
            if (!isBoxNode && fragmentsOnly && !movesWithFragment(it)) {
                hitRects[base] = it.x();
                hitRects[base + 1] = it.y();
                hitRects[base + 2] = it.width();
                hitRects[base + 3] = it.height();
                continue;
            }
            int left, top, nw, nh;
            if (isBoxNode) {
                if (box != null) {
                    float bdx = box.x - m.nums[0], bdy = box.y - m.nums[1];
                    if ((bdx != 0f || bdy != 0f)
                            && (nearHalfBoundary(box.x, m.nums[0], bdx) || nearHalfBoundary(box.y, m.nums[1], bdy)
                            || nearHalfBoundary(box.x + box.w, m.nums[0] + m.nums[2], bdx)
                            || nearHalfBoundary(box.y + box.h, m.nums[1] + m.nums[3], bdy))) {
                        return false;
                    }
                    int boxOx = Math.round(box.x), boxOy = Math.round(box.y);
                    nw = Math.round(box.x + box.w) - boxOx;
                    nh = Math.round(box.y + box.h) - boxOy;
                } else {
                    nw = it.width();
                    nh = it.height();
                }
                left = newRelX;
                top = newRelY;
                hitRects[base] = left;
                hitRects[base + 1] = top;
                hitRects[base + 2] = nw;
                hitRects[base + 3] = nh;
                if (nw != it.width() || nh != it.height()) uniform = false;
                continue;
            } else {
                float fx = coords[base], fy = coords[base + 1], fw = coords[base + 2], fh = coords[base + 3];
                float nx = fx + dx, ny = fy + dy;
                if (nearHalfBoundary(nx, fx, dx) || nearHalfBoundary(ny, fy, dy)
                        || nearHalfBoundary(nx + fw, fx + fw, dx) || nearHalfBoundary(ny + fh, fy + fh, dy)) {
                    return false;
                }
                left = Math.round(nx);
                top = Math.round(ny);
                nw = Math.round(nx + fw) - left;
                nh = Math.round(ny + fh) - top;
            }
            hitRects[base] = left;
            hitRects[base + 1] = top;
            hitRects[base + 2] = nw;
            hitRects[base + 3] = nh;
            if (nw != it.width() || nh != it.height()) {
                uniform = false;
            } else {
                int sx = left - it.x();
                int sy = top - it.y();
                if (!hasShift) {
                    uniformX = sx;
                    uniformY = sy;
                    hasShift = true;
                } else if (sx != uniformX || sy != uniformY) {
                    uniform = false;
                }
            }
        }
        hitPerItem = !uniform;
        hitIntDx = uniform && hasShift ? uniformX : 0;
        hitIntDy = uniform && hasShift ? uniformY : 0;
        return true;
    }

    private static boolean isIntegerTranslation(float d) {
        return Math.abs(d - Math.rint(d)) <= 1e-3f;
    }

    /**
     * 親相対モードで、局所座標（箱の原点からの相対）は変わらず、親に対する {@code NODE} 位置・スクロール寸法・
     * クリップ AABB のみが変わった箱の記憶を再利用する。部品 {@code DisplayItem} の作り直しを避け、必要な項目だけを差し替える。
     */
    private static void appendNodePatched(List<DisplayItem> out, BoxMemo m, LayoutBox box, ClipRect newClip,
                                          int newRelX, int newRelY, int curOx, int curOy, int curPox, int curPoy,
                                          boolean relPosChanged, boolean scrollChanged, boolean clipChanged,
                                          int partDx, int partDy, int[] itemRects,
                                          float fracDx, float fracDy, boolean fracFragOnly) {
        List<DisplayItem> items = m.items;
        int n = items.size();
        boolean nodePosChanged = n > 0 && items.get(0).type() == ElementType.NODE
                && (relPosChanged || items.get(0).x() != newRelX || items.get(0).y() != newRelY);
        boolean hasGradMove = !fracFragOnly && (fracDx != 0f || fracDy != 0f);
        boolean patchItems = nodePosChanged || scrollChanged || clipChanged || partDx != 0 || partDy != 0
                || itemRects != null || (hasGradMove && carriesShaderGradient(items));
        boolean hasOwnNode = PaintOrder.boxHasOwnNode(box);
        int subOx = hasOwnNode ? curOx : curPox;
        int subOy = hasOwnNode ? curOy : curPoy;
        float subShiftX = (hasOwnNode || fracFragOnly) ? 0f : fracDx;
        float subShiftY = (hasOwnNode || fracFragOnly) ? 0f : fracDy;
        boolean ownSubClipMoved = hasOwnNode ? (curOx != m.originX || curOy != m.originY) : (curPox != m.parentOriginX || curPoy != m.parentOriginY || subShiftX != 0f || subShiftY != 0f);
        if (patchItems) {
            DisplayItem[] updatedArr = null;
            ClipRect lastOldClip = null;
            ClipRect lastNextClip = null;
            boolean haveLastClip = false;
            RenderMeta lastOldMeta = null;
            RenderMeta lastNextMeta = null;
            for (int i = 0; i < n; i++) {
                DisplayItem it = items.get(i);
                int nextX = it.x();
                int nextY = it.y();
                int nextW = it.width();
                int nextH = it.height();
                if (itemRects != null) {
                    int base = i * 4;
                    if (i == 0 && it.type() == ElementType.NODE) {
                        if (nodePosChanged) {
                            nextX = newRelX;
                            nextY = newRelY;
                        }
                        nextW = itemRects[base + 2];
                        nextH = itemRects[base + 3];
                    } else if (!fracFragOnly || movesWithFragment(it)) {
                        nextX = itemRects[base];
                        nextY = itemRects[base + 1];
                        nextW = itemRects[base + 2];
                        nextH = itemRects[base + 3];
                    }
                } else if (i == 0 && it.type() == ElementType.NODE) {
                    if (nodePosChanged) {
                        nextX = newRelX;
                        nextY = newRelY;
                    }
                } else if ((partDx != 0 || partDy != 0) && (!fracFragOnly || movesWithFragment(it))) {
                    nextX = it.x() + partDx;
                    nextY = it.y() + partDy;
                }
                RenderMeta meta = it.meta();
                RenderMeta nextMeta = meta;
                if (meta != null) {
                    if (meta == lastOldMeta) {
                        nextMeta = lastNextMeta;
                    } else {
                        ClipRect curMetaClip = meta.clipRect();
                        boolean updScroll = scrollChanged && meta.hints() != null && meta.hints().scrollable();
                        boolean isDirectSubClip = curMetaClip != null && !sameClip(curMetaClip, m.clip);
                        boolean updClip = curMetaClip != null && (clipChanged || (isDirectSubClip && ownSubClipMoved));
                        boolean updGrad = hasGradMove && it.type() != ElementType.NODE && meta.hints() != null && meta.hints().shaderGradient() != null;
                        if (updScroll || updClip || updGrad) {
                            ClipRect nextClip;
                            if (!updClip) {
                                nextClip = curMetaClip;
                            } else if (haveLastClip && curMetaClip == lastOldClip) {
                                nextClip = lastNextClip;
                            } else {
                                ClipRect targetBaseClip = clipChanged ? newClip : m.clip;
                                nextClip = isDirectSubClip
                                        ? PaintSupport.rebaseSubClip(curMetaClip, m.clip, targetBaseClip, subOx, subOy, subShiftX, subShiftY)
                                        : targetBaseClip;
                                lastOldClip = curMetaClip;
                                lastNextClip = nextClip;
                                haveLastClip = true;
                            }
                            RenderHints nextHints = meta.hints();
                            if (updScroll) nextHints = nextHints.withScroll(box.scrollWidth, box.scrollHeight);
                            if (updGrad) nextHints = nextHints.withShaderGradient(nextHints.shaderGradient().offset(fracDx, fracDy));
                            if (!sameClip(nextClip, curMetaClip) || nextHints != meta.hints()) {
                                nextMeta = new RenderMeta(nextClip, meta.borderRadii(), meta.circleBorder(), meta.fontHeight(),
                                        meta.baseline(), meta.lineHeight(), meta.originX(), meta.originY(), meta.itemCount(),
                                        meta.interactiveState(), meta.roundedBorder(), nextHints);
                            }
                            lastOldMeta = meta;
                            lastNextMeta = nextMeta;
                        }
                    }
                }
                DisplayItem res = it.withBoundsAndMeta(nextX, nextY, nextW, nextH, nextMeta);
                if (res != it) {
                    if (m.itemsMutable) {
                        items.set(i, res);
                    } else {
                        if (updatedArr == null) {
                            updatedArr = new DisplayItem[n];
                            for (int j = 0; j < i; j++) updatedArr[j] = items.get(j);
                        }
                        updatedArr[i] = res;
                    }
                } else if (updatedArr != null) {
                    updatedArr[i] = it;
                }
                out.add(res);
            }
            if (updatedArr != null) {
                m.items = java.util.Arrays.asList(updatedArr);
                m.itemsMutable = true;
            }
        } else {
            appendItems(out, items);
        }
        if ((fracDx != 0f || fracDy != 0f) && m.itemCoords != null && m.itemCoords.length == n * 4) {
            for (int i = 0; i < n; i++) {
                DisplayItem it = items.get(i);
                if (i == 0 && it.type() == ElementType.NODE) {
                    if (!it.hints().relativeOrigin()) {
                        m.itemCoords[0] = box.x - curPox;
                        m.itemCoords[1] = box.y - curPoy;
                    }
                    continue;
                }
                if (fracFragOnly && !movesWithFragment(it)) continue;
                m.itemCoords[i * 4] += fracDx;
                m.itemCoords[i * 4 + 1] += fracDy;
            }
        }
        if (m.fragNums != null && !box.inlineFragments.isEmpty()) {
            for (int j = 0, fn = box.inlineFragments.size(); j < fn && j * 8 + 1 < m.fragNums.length; j++) {
                LayoutBox.InlineFragment f = box.inlineFragments.get(j);
                m.fragNums[j * 8] = f.x;
                m.fragNums[j * 8 + 1] = f.y;
            }
        }
        m.nums[0] = box.x;
        m.nums[1] = box.y;
        m.originX = curOx;
        m.originY = curOy;
        m.parentOriginX = curPox;
        m.parentOriginY = curPoy;
        m.scrollW = box.scrollWidth;
        m.scrollH = box.scrollHeight;
        if (clipChanged) m.clip = newClip;
        m.appliedDx = m.appliedDy = 0;
        m.appliedExactDx = m.appliedExactDy = 0f;
        m.appliedOriginDx = m.appliedOriginDy = 0;
        m.appliedFragOnly = false;
        m.appliedNonUniform = false;
    }

    /**
     * 前回の項目を平行移動（一括 {@code (ix, iy)}、または {@code itemRects} に基づく項目ごとの丸め）し、
     * クリップを今のものに替えた写しを {@code out} へ追加する。
     * {@link ShadowShape} は小数座標を保持しているため {@code (dx, dy)} で進める。
     */
    private static void appendTranslated(List<DisplayItem> out, BoxMemo m, ClipRect newClip,
                                         float dx, float dy, int ix, int iy, boolean textOnly, int[] itemRects,
                                         boolean parentRel, int newRelX, int newRelY, int newRelW, int newRelH,
                                         int curOx, int curOy, LayoutBox box, boolean scrollChanged, boolean clipChanged) {
        STAT_APPEND_TRANSLATED.increment();
        List<DisplayItem> items = m.items;
        boolean hasOwnNode = parentRel && PaintOrder.boxHasOwnNode(box);
        for (int i = 0, n = items.size(); i < n; i++) {
            DisplayItem it = items.get(i);
            boolean isRelNode = parentRel && i == 0 && it.type() == ElementType.NODE;
            boolean moves = !textOnly || movesWithFragment(it);
            int nx, ny, nw, nh;
            if (itemRects != null) {
                int base = i * 4;
                nx = itemRects[base];
                ny = itemRects[base + 1];
                nw = itemRects[base + 2];
                nh = itemRects[base + 3];
            } else if (isRelNode) {
                nx = newRelX;
                ny = newRelY;
                nw = newRelW;
                nh = newRelH;
            } else {
                int ox = moves ? ix : 0, oy = moves ? iy : 0;
                nx = it.x() + ox;
                ny = it.y() + oy;
                nw = it.width();
                nh = it.height();
            }
            RenderMeta meta = it.meta();
            if (meta != null) {
                RenderHints h = meta.hints();
                if (scrollChanged && h != null && h.scrollable()) {
                    h = h.withScroll(box.scrollWidth, box.scrollHeight);
                }
                if (!isRelNode && h != null && h.shadowShape() != null && (moves && (dx != 0 || dy != 0))) {
                    h = h.withShadowShape(h.shadowShape().offset(dx, dy));
                }
                if (!isRelNode && parentRel && h != null && h.shaderGradient() != null && (moves && (dx != 0 || dy != 0))) {
                    h = h.withShaderGradient(h.shaderGradient().offset(dx, dy));
                }
                ClipRect c = meta.clipRect();
                if (c != null) {
                    if (parentRel) {
                        LayoutBox pNode = PaintOrder.effectiveParentNodeBox(box, m.parentId);
                        int curPox = pNode != null ? Math.round(pNode.x) : 0;
                        int curPoy = pNode != null ? Math.round(pNode.y) : 0;
                        c = PaintSupport.rebaseSubClip(c, m.clip, newClip,
                                hasOwnNode ? curOx : curPox, hasOwnNode ? curOy : curPoy,
                                (hasOwnNode || !moves) ? 0f : dx, (hasOwnNode || !moves) ? 0f : dy);
                    } else {
                        c = newClip;
                    }
                }
                if (c != meta.clipRect() || (h != null && h != meta.hints())) {
                    meta = new RenderMeta(c, meta.borderRadii(), meta.circleBorder(), meta.fontHeight(), meta.baseline(),
                            meta.lineHeight(), meta.originX(), meta.originY(), meta.itemCount(), meta.interactiveState(),
                            meta.roundedBorder(), h != null ? h : meta.hints());
                }
            }
            out.add(new DisplayItem(it.id(), it.baseNodeId(), it.type(), nx, ny, nw, nh,
                    it.text(), it.color(), it.bgColor(), it.events(), it.radius(), it.opacity(), it.translateX(), it.translateY(),
                    it.scaleX(), it.scaleY(), it.rotate(), it.transitionDuration(), it.animDef(), it.fitMode(), meta, it.key()));
        }
    }

    /**
     * 項目が平行移動だけで作り直せるか: 座標を持つのが x・y と（前回のクリップそのものの）クリップだけで、
     * 箱の座標で決まる図形（影・グラデーション・clip-path・backdrop）や、画面に固定の背景を持たない。
     */
    private static final String[] EMPTY_STRINGS = new String[0];
    private static final float[] EMPTY_FLOATS = new float[0];
    private static final Object[] EMPTY_OBJECTS = new Object[0];

    /** 項目のどれかがスクロールの大きさ（中身の幅・高さ）を持つか。 */
    private static boolean carriesContentExtent(List<DisplayItem> items) {
        for (int i = 0, n = items.size(); i < n; i++) {
            RenderMeta meta = items.get(i).meta();
            if (meta != null && meta.hints() != null && meta.hints().scrollable()) return true;
        }
        return false;
    }

    private static boolean onlyNodeAndText(List<DisplayItem> items) {
        for (int i = 0, n = items.size(); i < n; i++) {
            ElementType t = items.get(i).type();
            if (t != ElementType.NODE && t != ElementType.TEXT) return false;
        }
        return true;
    }

    private static boolean carriesShaderGradient(List<DisplayItem> items) {
        for (int i = 0, n = items.size(); i < n; i++) {
            RenderMeta meta = items.get(i).meta();
            if (meta != null && meta.hints() != null && meta.hints().shaderGradient() != null) return true;
        }
        return false;
    }

    private static boolean isBoxOwnedDirectSubClip(ClipRect mc, ClipRect clip, String ownerKey) {
        if (ownerKey == null) return false;
        List<ClipChainPart> sc = mc.chain();
        List<ClipChainPart> pc = clip == null ? List.of() : clip.chain();
        int pn = pc.size();
        if (sc.size() != pn + 1) return false;
        for (int i = 0; i < pn; i++) {
            if (!sc.get(i).equals(pc.get(i))) return false;
        }
        return ownerKey.equals(sc.get(pn).ownerId());
    }

    private static boolean survivesTranslation(LayoutBox box, ClipRect clip, List<DisplayItem> items, boolean parentRel) {
        String attachment = box.style.get(CssPropertyIndex.BACKGROUND_ATTACHMENT);
        if (attachment != null && attachment.contains("fixed")) return false;
        String ownerKey = box.node != null ? box.node.key() : null;
        for (int i = 0, n = items.size(); i < n; i++) {
            DisplayItem it = items.get(i);
            ElementType t = it.type();
            if (t != ElementType.NODE && t != ElementType.RECT && t != ElementType.TEXT && t != ElementType.ROUNDED_BORDER) return false;
            RenderMeta meta = it.meta();
            if (meta == null) continue;
            ClipRect mc = meta.clipRect();
            if (mc != null && !sameClip(mc, clip) && !(parentRel && isBoxOwnedDirectSubClip(mc, clip, ownerKey))) return false;
            RenderHints h = meta.hints();
            if (h.shaderGradient() != null && !parentRel) return false;
            LayerSpec layer = h.layer();
            if (layer != null && (layer.clip() != null || layer.backdropShape() != null)) return false;
        }
        return true;
    }

    private BoxMemo remember(LayoutBox box, ClipRect clip, String parentId, List<DisplayItem> items) {
        return remember(null, box, clip, parentId, items, false);
    }

    private BoxMemo remember(BoxMemo reuse, LayoutBox box, ClipRect clip, String parentId, List<DisplayItem> items, boolean itemsMutable) {
        BoxMemo m = reuse != null ? reuse : new BoxMemo();
        m.appliedDx = m.appliedDy = 0;
        m.appliedExactDx = m.appliedExactDy = 0f;
        m.appliedOriginDx = m.appliedOriginDy = 0;
        m.appliedFragOnly = false;
        m.appliedNonUniform = false;
        m.itemCoords = null;
        m.epoch = buildEpoch;
        m.rootW = rootW;
        m.rootH = rootH;
        m.viewportSensitive = box.parent == null || box.style.usesViewportUnits();
        m.parentBox = box.parent;
        m.items = items;
        m.itemsMutable = itemsMutable;
        m.style = box.style;
        m.firstLineStyle = box.firstLineStyle;
        m.placeholderStyle = box.placeholderStyle;
        m.ancestors = ancestorStyles(box, m.ancestors);
        m.nums = fillNums(box, m.nums);
        m.scrollW = box.scrollWidth;
        m.scrollH = box.scrollHeight;
        m.clip = clip;
        m.parentId = parentId;
        m.attrVersion = box.node.attrVersion();
        m.displayText = box.displayText;
        if (box.shapedLines == null) {
            m.shaped = null;
        } else if (box.shapedLines.isEmpty()) {
            m.shaped = List.of();
        } else if (!(m.shaped instanceof List<?> prevShaped && prevShaped.equals(box.shapedLines))) {
            m.shaped = List.copyOf(box.shapedLines);
        }
        List<LayoutBox.InlineFragment> frags = box.inlineFragments;
        int fn = frags.size();
        if (fn == 0) {
            m.fragText = EMPTY_STRINGS;
            m.fragNums = EMPTY_FLOATS;
            m.fragRefs = EMPTY_OBJECTS;
        } else {
            m.fragText = (m.fragText != null && m.fragText.length == fn) ? m.fragText : new String[fn];
            m.fragNums = (m.fragNums != null && m.fragNums.length == fn * 8) ? m.fragNums : new float[fn * 8];
            int refLen = 0;
            for (int i = 0; i < fn; i++) {
                List<LayoutBox> ps = frags.get(i).parents;
                refLen += 5 + (ps == null ? 0 : ps.size() * 2);
            }
            Object[] refs = (m.fragRefs != null && m.fragRefs.length == refLen) ? m.fragRefs : new Object[refLen];
            int r = 0;
            for (int i = 0; i < fn; i++) {
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
                refs[r++] = f.box;
                refs[r++] = f.box != null ? f.box.style : null;
                List<LayoutBox> ps = f.parents;
                int pn = ps == null ? 0 : ps.size();
                refs[r++] = pn;
                for (int k = 0; k < pn; k++) {
                    LayoutBox p = ps.get(k);
                    refs[r++] = p;
                    refs[r++] = p.style;
                }
                refs[r++] = f.firstLineOwner;
                refs[r++] = f.firstLineOwner != null ? f.firstLineOwner.firstLineStyle : null;
            }
            m.fragRefs = refs;
        }
        m.canvasSource = box == canvasBackgroundSource;
        m.interactive = box.hasInteractiveRules;
        m.suppressShadow = suppressTextShadow;
        m.translatable = survivesTranslation(box, clip, items, parentRelative);
        m.carriesContentExtent = carriesContentExtent(items);
        m.nodeAndTextOnly = onlyNodeAndText(items);
        m.parentRelative = parentRelative;
        if (parentRelative) {
            m.originX = Math.round(box.x);
            m.originY = Math.round(box.y);
            LayoutBox pNode = PaintOrder.effectiveParentNodeBox(box, parentId);
            m.parentOriginX = pNode != null ? Math.round(pNode.x) : 0;
            m.parentOriginY = pNode != null ? Math.round(pNode.y) : 0;
        }
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
        if (content != null) {
            PaintSupport.CaptureList cap = (parentRelative && PaintOrder.boxHasOwnNode(box) && elements instanceof PaintSupport.CaptureList c) ? c : null;
            int prevOx = cap != null ? cap.originX : 0, prevOy = cap != null ? cap.originY : 0;
            if (cap != null) cap.setOrigin(Math.round(box.x), Math.round(box.y));
            try {
                order.addMovingNode(elements, content.contentId, box, null); // 箱の項目のあと・子孫の項目の前
            } finally {
                if (cap != null) cap.setOrigin(prevOx, prevOy);
            }
        }
    }

    /** 項目が記憶の位置から動いている分（{@link BoxMemo#appliedExactDx}）を、記憶の基準の位置へ取り込む（差分パッチの前に呼ぶ）。 */
    private static void syncApplied(BoxMemo m) {
        float ax = m.appliedExactDx, ay = m.appliedExactDy;
        int odx = m.appliedOriginDx, ody = m.appliedOriginDy;
        if (ax == 0f && ay == 0f && odx == 0 && ody == 0 && m.appliedDx == 0 && m.appliedDy == 0) return;
        float pageDx = odx + ax, pageDy = ody + ay;
        if (!m.appliedFragOnly && m.nums != null && m.nums.length >= 2) {
            m.nums[0] += pageDx;
            m.nums[1] += pageDy;
        }
        m.originX += odx;
        m.originY += ody;
        if (m.fragNums != null) {
            for (int i = 0; i + 1 < m.fragNums.length; i += 8) {
                m.fragNums[i] += pageDx;
                m.fragNums[i + 1] += pageDy;
            }
        }
        if (m.itemCoords != null && m.items != null && m.itemCoords.length == m.items.size() * 4) {
            for (int i = 0, n = m.items.size(); i < n; i++) {
                if (m.appliedFragOnly && !movesWithFragment(m.items.get(i))) continue;
                m.itemCoords[i * 4] += ax;
                m.itemCoords[i * 4 + 1] += ay;
            }
        }
        m.appliedDx = m.appliedDy = 0;
        m.appliedExactDx = m.appliedExactDy = 0f;
        m.appliedOriginDx = m.appliedOriginDy = 0;
        m.appliedFragOnly = false;
        m.appliedNonUniform = false;
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
            m.appliedExactDx = m.appliedExactDy = 0f;
            m.appliedOriginDx = m.appliedOriginDy = 0;
            m.appliedFragOnly = false;
            m.appliedNonUniform = false;
            m.generation = generation;
            m.epoch = buildEpoch;
            appendItems(elements, m.items);
            return;
        }
        if (kind == 2) { // 箱が動いただけ。記憶は動く前のまま残す（差が積もっても、丸めの誤差は 1 画素以内）
            m.generation = generation;
            if (hitParentRelReuse) {
                m.epoch = buildEpoch;
                appendNodePatched(elements, m, box, clipRect, hitNewRelX, hitNewRelY,
                        hitCurOx, hitCurOy, hitCurPox, hitCurPoy,
                        hitRelPosChanged, hitScrollChanged, hitClipChanged,
                        hitPartDx, hitPartDy, hitPerItem ? hitRects : null,
                        hitReuseFracDx, hitReuseFracDy, hitReuseFragOnly);
            } else {
                m.appliedDx = hitIntDx;
                m.appliedDy = hitIntDy;
                m.appliedExactDx = hitDx;
                m.appliedExactDy = hitDy;
                m.appliedOriginDx = parentRelative ? (hitCurOx - m.originX) : 0;
                m.appliedOriginDy = parentRelative ? (hitCurOy - m.originY) : 0;
                m.appliedFragOnly = hitFragmentsOnly;
                m.appliedNonUniform = hitPerItem;
                appendTranslated(elements, m, clipRect, hitDx, hitDy, hitIntDx, hitIntDy, hitFragmentsOnly,
                        hitPerItem ? hitRects : null, parentRelative, hitNewRelX, hitNewRelY, hitNewRelW, hitNewRelH,
                        hitCurOx, hitCurOy, box, hitScrollChanged, hitClipChanged);
            }
            translatedHits++;
            STAT_TRANSLATED.increment();
            return;
        }
        (m == null ? STAT_NEW : STAT_MISS).increment();
        fragIds.set(0); // 部品の ID は箱ごとに数える
        int start = elements.size();
        PaintSupport.CaptureList cap = elements instanceof PaintSupport.CaptureList c ? c : null;
        if (cap != null) cap.beginCapture();
        boolean committed = false;
        float[] prevCoords = m != null ? m.itemCoords : null;
        try {
            drawBoxSelfUncached(elements, box, clipRect, parentId, m);
            int count = elements.size() - start;
            List<DisplayItem> built = sliceCopy(m, elements, start);
            BoxMemo stored = remember(m, box, clipRect, parentId, built, count >= 3);
            if (cap != null && cap.capturing) {
                stored.itemCoords = (stored.translatable && !stored.carriesContentExtent)
                        ? cap.finishCaptureReuse(prevCoords)
                        : null;
                if (cap.capturing) cap.discardCapture();
            }
            committed = true;
            if (stored != m) boxMemo.put(box, stored);
        } finally {
            if (!committed && cap != null && cap.capturing) cap.discardCapture();
        }
    }

    private static List<DisplayItem> sliceCopy(BoxMemo reuse, List<DisplayItem> elements, int start) {
        int count = elements.size() - start;
        if (count <= 0) return List.of();
        if (count == 1) return List.of(elements.get(start));
        if (count == 2) return List.of(elements.get(start), elements.get(start + 1));
        if (reuse != null && reuse.itemsMutable && reuse.items != null && reuse.items.size() == count) {
            List<DisplayItem> dst = reuse.items;
            for (int i = 0; i < count; i++) dst.set(i, elements.get(start + i));
            return dst;
        }
        DisplayItem[] arr = new DisplayItem[count];
        for (int i = 0; i < count; i++) arr[i] = elements.get(start + i);
        return java.util.Arrays.asList(arr);
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

    private void drawBoxSelfUncached(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId) {
        drawBoxSelfUncached(elements, box, clipRect, parentId, null);
    }

    private void drawBoxSelfUncached(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId, BoxMemo prevMemo) {
        Corners radii = cornersShared(box.axisStyle(), box.w, box.h, rootW, rootH);
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

        TransitionSpec transSpec = parseTransitionSpec(box.style);
        AnimationSpec animDef = anim.parseAnimationDef(box.style, box.w, box.h, color, bgColor);
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

        boolean useRel = parentRelative && PaintOrder.boxHasOwnNode(box);
        if (useRel) anchorEvents.relativeOrigin();
        RenderMeta prevAnchorMeta = null, prevBaseMeta = null, prevInnerMeta = null;
        RenderHints prevAnchorHints = null, prevPartsHints = null, prevPassiveHints = null;
        if (prevMemo != null && prevMemo.items != null && !prevMemo.items.isEmpty()) {
            DisplayItem m0 = prevMemo.items.get(0);
            if (m0.type() == ElementType.NODE) {
                prevAnchorMeta = m0.meta();
                prevAnchorHints = m0.hints();
            }
            for (int mi = 1, mn = Math.min(prevMemo.items.size(), 8); mi < mn; mi++) {
                DisplayItem item = prevMemo.items.get(mi);
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
                    if (prevPartsHints == null) prevPartsHints = h;
                }
            }
        }
        anchorEvents.hintsReuse(prevAnchorHints);
        ClipRect normalizedClip = clipRect;
        RenderMeta anchorMeta = reuseMeta(prevAnchorMeta, normalizedClip, radii, fontHVal, box.lineHeightPx, ox, oy,
                itemCount, interactive, anchorEvents.hints());
        long nodeKey = box.node != null ? DisplayItem.nodeKeyOf(box.node.paintHandle()) : 0L;
        PaintSupport.CaptureList cap = (parentRelative && elements instanceof PaintSupport.CaptureList c) ? c : null;
        int prevOx = cap != null ? cap.originX : 0, prevOy = cap != null ? cap.originY : 0;
        if (cap != null) {
            LayoutBox pNode = PaintOrder.effectiveParentNodeBox(box, parentId);
            cap.setOrigin(pNode != null ? Math.round(pNode.x) : 0, pNode != null ? Math.round(pNode.y) : 0);
        }
        try {
            addElement(elements, id, id, ElementType.NODE, box.x, box.y, box.w, box.h, "", color, bgColor, anchorEvents, 0, opacity, tx, ty, sx, sy, rot, transDur, animDef, anchorMeta, nodeKey);

            // visibility: hidden の箱は場所だけを取る。錨（NODE）は子孫の変形と不透明度の親として残す
            // 何が壊れていたか: 以前は visibility を読んでおらず、hidden の箱が描かれクリックも受けていた
            if (isInvisible(box) || PaintSupport.hidesEmptyCell(box)) return;

            if (useRel && cap != null) cap.setOrigin(Math.round(box.x), Math.round(box.y));

            ItemProps partsEvents = ItemProps.populateForParts(scratchPartsProps, box, id, transSpec);
            if (pointerEventsNone) partsEvents.pointerEventsNone();
            partsEvents.reuseCandidate(prevPartsHints);
            if ("#text".equals(box.node.tag)) {
                drawTextNodeElement(elements, box, id, color, bgColor, normalizedClip, fontHVal, ox, oy, partsEvents, transDur, anim.textColorAnimation(box, animDef));
                return;
            }
            ItemProps passiveBorderEvents = ItemProps.populateForPassiveBorder(scratchPassiveProps, id, nodeKey, transSpec);
            // 何が壊れていたか: 以前は pointer-events を部品に載せていなかったので、ヒットテストの
            // pointer-events: none の判定は常に偽だった
            if (pointerEventsNone) {
                passiveBorderEvents.pointerEventsNone();
            }
            passiveBorderEvents.reuseCandidate(prevPassiveHints != null ? prevPassiveHints : prevPartsHints);

            if (!box.inlineFragments.isEmpty() && !NON_FRAGMENT_TAGS.contains(box.node.tag) && !ReplacedElements.isHost(box.node.tag) && !BlockLayoutEngine.isAtomicInline(box)) {
                decor.drawInlineFragments(elements, box, id, color, bgColor, rawRadii, normalizedClip, fontHVal, ox, oy, itemCount, interactive,
                        partsEvents, passiveBorderEvents, transDur, colorBgAnim);
                return;
            }

            RenderMeta baseMeta = reuseMeta(prevBaseMeta, normalizedClip, radii, fontHVal, box.lineHeightPx, ox, oy,
                    itemCount, interactive, partsEvents.hints());

            List<BoxDecorations.Shadow> shadows = BoxDecorations.parseBoxShadows(box.style.get(CssPropertyIndex.BOX_SHADOW), color,
                    rootW, rootH, box.style.fontSizePx(), box.style.rootFontSizePx());
            decor.drawOuterShadows(elements, box, id, shadows, radii, normalizedClip, fontHVal, ox, oy);

            BorderSpec border = decor.parseBorderSpec(box, rawRadii, radii, color);
            RenderMeta innerMeta = border.innerRadii().equals(radii)
                    ? baseMeta
                    : reuseMeta(prevInnerMeta, normalizedClip, border.innerRadii(), fontHVal, box.lineHeightPx, ox, oy,
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
                    addElement(elements, id, id + "_progress", ElementType.PROGRESS, box.x, box.y, box.w, box.h, val + ":" + min + ":" + max, color, bgColor, partsEvents, fallbackRadius, 1f, 0, 0, 1f, 1f, 0, transDur, colorBgAnim, baseMeta, progressKey);
                }
                case "svg" -> controls.drawSvgElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "canvas" -> controls.drawCanvasElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "textarea" -> controls.drawTextareaElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "select" -> controls.drawSelectElement(elements, box, id, color, bgColor, fallbackRadius, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim);
                case "input" -> {
                    if (isPlainRange(box)) {
                        // appearance: none のレンジは作者の CSS で描いた箱に、つまみ（::-webkit-slider-thumb は部品のまま）だけを重ねる
                        decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows, prevMemo != null ? prevMemo.items : null);
                        controls.drawNativeRange(elements, box, id, rangeData(box, resolveLiveInputValue(id, box.node.attrs.getOrDefault("value", ""))),
                                color, false, partsEvents, border, baseMeta, transDur, colorBgAnim);
                    } else if (isPlainToggle(box)) {
                        // appearance: none のチェックボックス・ラジオボタンは普通の箱（作者の枠線・背景だけで描き、印は無い）
                        decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows, prevMemo != null ? prevMemo.items : null);
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
                        decor.drawDefaultBlockElement(elements, box, id, color, bgColor, fallbackRadius, radii, normalizedClip, fontHVal, ox, oy, partsEvents, passiveBorderEvents, border, baseMeta, innerMeta, rOff, transDur, colorBgAnim, shadows, prevMemo != null ? prevMemo.items : null);
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

    private ItemProps textPropsForStyle(com.nishiyu.enderframelib.core.css.ComputedStyle style, ItemProps partsEvents, int resolvedColor) {
        ItemProps props = partsEvents.copy();
        if (style.containsKey(CssPropertyIndex.FONT_WEIGHT)) props.put("font-weight", style.get(CssPropertyIndex.FONT_WEIGHT));
        if (style.containsKey(CssPropertyIndex.FONT_FAMILY)) props.fontFamily(style.get(CssPropertyIndex.FONT_FAMILY));
        if (style.containsKey(CssPropertyIndex.TEXT_DECORATION)) props.put("text-decoration", style.get(CssPropertyIndex.TEXT_DECORATION));
        putFontEvents(style, props);
        float strokeWidth = textStrokeWidth(style);
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

    private void drawTextNodeElement(List<DisplayItem> elements, LayoutBox box, String id, int color, int bgColor,
                                     ClipRect normalizedClip, float fontHVal, float ox, float oy, ItemProps partsEvents,
                                     int transDur, AnimationSpec colorAnim) {
        ItemProps baseTextEvents = null;
        List<BoxDecorations.Shadow> baseTextShadows = null;

        if (!box.inlineFragments.isEmpty()) {
            for (LayoutBox.InlineFragment frag : box.inlineFragments) {
                if (frag.text == null || frag.text.isBlank()) continue;
                com.nishiyu.enderframelib.core.css.ComputedStyle fl = frag.effectiveFirstLineStyle(box);
                if (fl != null) {
                    int flColor = fl.color(CssPropertyIndex.COLOR, color);
                    float flFontH = Math.max(1.0f, fl.fontSizePx());
                    ItemProps flEvents = textPropsForStyle(fl, partsEvents, flColor);
                    List<BoxDecorations.Shadow> flShadows = textShadowsForStyle(fl, flColor, flEvents);
                    RenderMeta fragMeta = new RenderMeta(
                            normalizedClip, null, null, flFontH, frag.baseline, frag.h, ox, oy, 1, false);
                    addTextRun(elements, box, fl, id, frag.x, frag.y, frag.w, frag.h, frag.text, flColor, bgColor, flEvents, fragMeta, transDur, AnimationSpec.NONE, flShadows);
                } else {
                    if (baseTextEvents == null) {
                        baseTextEvents = textPropsForStyle(box.style, partsEvents, color);
                        baseTextShadows = textShadowsForStyle(box.style, color, baseTextEvents);
                    }
                    RenderMeta fragMeta = new RenderMeta(
                            normalizedClip, null, null, fontHVal, frag.baseline, frag.h, ox, oy, 1, false);
                    addTextRun(elements, box, box.style, id, frag.x, frag.y, frag.w, frag.h, frag.text, color, bgColor, baseTextEvents, fragMeta, transDur, colorAnim, baseTextShadows);
                }
            }
        } else if (box.shapedLines != null && !box.shapedLines.isEmpty()) {
            baseTextEvents = textPropsForStyle(box.style, partsEvents, color);
            baseTextShadows = textShadowsForStyle(box.style, color, baseTextEvents);
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
                    addTextRun(elements, box, box.style, id, lineX, lineY, lineW, line.height(), line.text(), color, bgColor, baseTextEvents, lineMeta, transDur, colorAnim, baseTextShadows);
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
        if (b.contentAutoSkipped) return;
        if (b.isVirtualizedFlow()) return;
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

    private void renderSubtreeSlices(LayoutBox box, ClipRect clip, String parentId, boolean ancestorHasMoving,
                                    List<DisplayItem> outBlocks, List<LayoutBox> outBlockBoxes,
                                    List<DisplayItem> outInlines, List<LayoutBox> outInlineBoxes) {
        if (box == null || box.w <= 0 || box.h <= 0 || (box.display != null && box.display.isNone())) return;

        boolean selfOrAncestorMoving = ancestorHasMoving
                || (box.style != null && PaintOrder.hasMovingProps(box.style));
        if (!box.isAnonymous && windowedBuild && !selfOrAncestorMoving && !inWindowGeometry(box)) {
            // 窓の外（画面外）の箱は、全体を窓付きで組み直したときと同じく、項目を作らない
            box.dlStart = -1;
            box.paintCulled = true;
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
            box.paintCulled = false;
            box.dlEnd = end;
            targetBoxes.add(box);
            boxMemo.put(box, remember(box, clip, parentId, List.copyOf(targetItems.subList(start, end))));
        }

        String childParent = (box.node != null && !box.isAnonymous) ? box.node.key() : parentId;
        ClipRect childClip = clip;
        if (box.children != null) {
            for (LayoutBox child : box.children) {
                renderSubtreeSlices(child, childClip, childParent, selfOrAncestorMoving,
                        outBlocks, outBlockBoxes, outInlines, outInlineBoxes);
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
        int prevEpoch = activeMovingEpoch;
        activeMovingEpoch = ++movingEpochSeq;
        try {
            // 位置だけで決まる側（窓に掛かる箱は描く・掛からない箱は、変形などの動く祖先が無いかぎり省く）を先に見て、祖先の走査を避ける。
            // 省いた箱の変形などの有無は、省いた時点から変わらない（スタイルの変更は別の経路で、子の増減のパッチには来ない）
            for (LayoutBox b : renderedBoxes) {
                if (!gone.contains(b) && !inWindowGeometry(b) && offscreen(b)) return false;
            }
            for (LayoutBox b : culledBoxes) {
                if (!gone.contains(b) && inWindowGeometry(b)) return false;
            }
            return true;
        } finally {
            activeMovingEpoch = prevEpoch;
        }
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

    /** スクロール付きの箱の、スクロール節・つまみの出方を決める性質（overflow・scrollbar-width）が前回の記憶と同じか。記憶が無ければ false。 */
    private boolean scrollTraitsUnchanged(LayoutBox b) {
        BoxMemo m = boxMemo.get(b);
        if (m == null || m.style == null || b.style == null) return false;
        var now = PaintOrder.factsOf(b.style);
        var prev = PaintOrder.factsOf(m.style);
        if (!prev.scrollbars() || !java.util.Objects.equals(now.overflowX(), prev.overflowX())
                || !java.util.Objects.equals(now.overflowY(), prev.overflowY())) return false;
        return b.style.keywordOf(CssPropertyIndex.SCROLLBAR_WIDTH, CssKeywords.ScrollbarWidth.class)
                == m.style.keywordOf(CssPropertyIndex.SCROLLBAR_WIDTH, CssKeywords.ScrollbarWidth.class);
    }

    /**
     * 指定されたボックス群のスライスパッチ妥当性を検証し、降順（dlStart の大きい順）にソートされた
     * 有効パッチ対象のリストを返す。不適格な場合は cant カウンタを記録して null を返す。
     * 画面外で項目を持たないボックス（isCulledNoop）は除外され、重複ボックスは 1 つにまとめられる。
     */
    List<LayoutBox> preparePatchBoxes(java.util.Collection<LayoutBox> boxes) {
        if (currentDisplayList == null || currentDisplayList.isEmpty() || currentRoot == null) {
            cant(0, 0);
            return null;
        }
        if (boxes == null || boxes.isEmpty()) {
            cant(0, 1);
            return null;
        }
        int listSize = currentDisplayList.size();
        int boxCount = renderedBoxes.size();
        int prevEpoch = activeMovingEpoch;
        activeMovingEpoch = ++movingEpochSeq;
        List<LayoutBox> targets = new ArrayList<>(boxes.size());
        try {
            for (LayoutBox b : boxes) {
                if (b == null || b.w <= 0 || b.h <= 0 || b.isAnonymous) {
                    cant(0, 2);
                    return null;
                }
                if (isCulledNoop(b)) continue; // 窓の外の箱: 表示リストに項目が無く、画面外のままなら何も変えずに済む
                if (b.dlStart < 0 || b.dlEnd < b.dlStart || b.dlEnd > listSize) {
                    cant(0, 3);
                    return null;
                }
                if (b.dlOrder < 0 || b.dlOrder >= boxCount || renderedBoxes.get(b.dlOrder) != b) {
                    cant(0, 4);
                    return null;
                }
                if (b.style != null) {
                    var facts = PaintOrder.factsOf(b.style);
                    if (facts.hasAnimation()) {
                        cant(0, 5);
                        return null;
                    }
                    // スクロール付きの箱でも、つまみ・溝は箱自身のスライスの外（after 層）に出るので、
                    // スクロールの有無・overflow・scrollbar-width が前回と同じなら自身のスライスだけ差し替えればよい
                    if (facts.scrollbars() && !scrollTraitsUnchanged(b)) {
                        cant(0, 5);
                        return null;
                    }
                }
                if (order.createsStackingContext(b, b.style)) {
                    cant(0, 6);
                    return null;
                }
                BoxMemo m = boxMemo.get(b);
                if (m != null) {
                    if (m.style != null) {
                        var prevFacts = PaintOrder.factsOf(m.style);
                        if (prevFacts.hasAnimation()) {
                            cant(0, 5);
                            return null;
                        }
                        if (prevFacts.scrollbars() != PaintOrder.factsOf(b.style).scrollbars()) {
                            cant(0, 5);
                            return null;
                        }
                        if (order.createsStackingContext(b, m.style)) {
                            cant(0, 6);
                            return null;
                        }
                    }
                    if (m.nums != null && m.nums.length >= 4) {
                        if (Float.compare(m.nums[0], b.x) != 0 || Float.compare(m.nums[1], b.y) != 0
                                || Float.compare(m.nums[2], b.w) != 0 || Float.compare(m.nums[3], b.h) != 0) {
                            cant(0, 8);
                            return null;
                        }
                    }
                }
                targets.add(b);
            }
        } finally {
            activeMovingEpoch = prevEpoch;
        }
        if (targets.size() > 1) {
            targets.sort((a, b) -> {
                int cmp = Integer.compare(b.dlStart, a.dlStart);
                if (cmp != 0) return cmp;
                return Integer.compare(System.identityHashCode(b), System.identityHashCode(a));
            });
            int write = 0;
            for (int i = 0; i < targets.size(); i++) {
                LayoutBox b = targets.get(i);
                if (write > 0) {
                    LayoutBox prev = targets.get(write - 1);
                    if (b == prev) continue;
                    if (b.dlEnd > prev.dlStart) {
                        cant(0, 7);
                        return null;
                    }
                }
                targets.set(write++, b);
            }
            if (write < targets.size()) {
                targets.subList(write, targets.size()).clear();
            }
        }
        return targets;
    }

    /**
     * 指定されたボックス群の表示項目スライス [dlStart, dlEnd) をインプレースで差分差し替え可能か判定する。
     */
    public boolean canPatchBoxes(java.util.Collection<LayoutBox> boxes) {
        return preparePatchBoxes(boxes) != null;
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
        List<LayoutBox> sorted = preparePatchBoxes(boxes);
        if (sorted == null) {
            STAT_SLICE_PATCH_FALLBACKS.increment();
            return null;
        }
        if (sorted.isEmpty()) {
            return currentDisplayList;
        }

        decor.setDevicePixelRatio(currentRoot.devicePixelRatio);
        boolean relMode = parentRelativeSource.getAsBoolean();
        this.parentRelative = relMode;
        order.setParentRelative(relMode);

        for (LayoutBox b : sorted) {
            ClipRect clip = clipForBox(b);
            String parentId = parentIdForBox(b);
            fragIds.set(0);
            BoxMemo prev = boxMemo.get(b);
            PaintSupport.CaptureList newItems;
            if (prev != null && prev.itemsMutable && prev.items instanceof PaintSupport.CaptureList prevCap) {
                prevCap.resetForBuild();
                newItems = prevCap;
            } else {
                newItems = new PaintSupport.CaptureList(b.dlEnd - b.dlStart + 4);
            }
            newItems.beginCapture();
            float[] capturedCoords = null;
            try {
                drawBoxSelfUncached(newItems, b, clip, parentId);
            } finally {
                if (newItems.capturing) capturedCoords = newItems.finishCapture();
            }
            BoxMemo stored = remember(prev, b, clip, parentId, newItems, true);
            if (capturedCoords != null && stored.translatable && !stored.carriesContentExtent
                    && capturedCoords.length == newItems.size() * 4) {
                stored.itemCoords = capturedCoords;
            }
            boxMemo.put(b, stored);
            // スクロールの中身をまとめた節は、箱の項目の直後（drawBoxSelf と同じ位置）にある。差し替えで落とすと、
            // 子の項目が親を失って、スクロール量が掛からなくなる（ホバーのあとにスクロールすると表示がずれる）
            PaintOrder.ScrollContent sc = order.scrollContentOf(b);
            if (sc != null) {
                for (int i = b.dlStart; i < b.dlEnd; i++) {
                    if (sc.contentId.equals(currentDisplayList.get(i).id())) {
                        int prevOx = newItems.originX, prevOy = newItems.originY;
                        if (relMode && PaintOrder.boxHasOwnNode(b)) {
                            newItems.setOrigin(Math.round(b.x), Math.round(b.y));
                        }
                        try {
                            order.addMovingNode(newItems, sc.contentId, b, null);
                        } finally {
                            newItems.setOrigin(prevOx, prevOy);
                        }
                        break;
                    }
                }
            }

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
        if (parentBox.contentAutoSkipped || parentBox.childrenDeferred || parentBox.isVirtualizedFlow()) return cant(1, 12);
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
        boolean relMode = parentRelativeSource.getAsBoolean();
        this.parentRelative = relMode;
        order.setParentRelative(relMode);
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
        List<DisplayItem> addedBlockItems = new PaintSupport.CaptureList(16);
        List<LayoutBox> addedBlockBoxes = new ArrayList<>();
        List<DisplayItem> addedInlineItems = new PaintSupport.CaptureList(16);
        List<LayoutBox> addedInlineBoxes = new ArrayList<>();
        decor.setDevicePixelRatio(currentRoot.devicePixelRatio);
        ClipRect clip = order.contentClipOf(parentBox);
        String parentId = contentParentIdOf(parentBox);

        boolean parentMoving = hasMovingAncestor(parentBox);
        for (LayoutBox a : added) {
            renderSubtreeSlices(a, clip, parentId, parentMoving, addedBlockItems, addedBlockBoxes, addedInlineItems, addedInlineBoxes);
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
                // 表示リストの中の項目は、記憶の位置から appliedExactDx/appliedExactDy だけ動いている（箱の位置は記憶の基準を足して見る）
                final float ax = m.appliedFragOnly ? 0f : m.appliedExactDx, ay = m.appliedFragOnly ? 0f : m.appliedExactDy;
                final float fx = m.appliedExactDx, fy = m.appliedExactDy;
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

                    int curOx = Math.round(b.x), curOy = Math.round(b.y);
                    boolean hasRelOrigin = relMode && PaintOrder.boxHasOwnNode(b);
                    int curPox = 0, curPoy = 0;
                    if (relMode) {
                        String bParentId = m.parentId != null ? m.parentId : parentIdForBox(b);
                        LayoutBox pNode = PaintOrder.effectiveParentNodeBox(b, bParentId);
                        curPox = pNode != null ? Math.round(pNode.x) : 0;
                        curPoy = pNode != null ? Math.round(pNode.y) : 0;
                    }
                    int partOx = hasRelOrigin ? curOx : curPox, partOy = hasRelOrigin ? curOy : curPoy;
                    int oldPartOx = hasRelOrigin ? m.originX : m.parentOriginX, oldPartOy = hasRelOrigin ? m.originY : m.parentOriginY;

                    int itemCount = b.dlEnd - b.dlStart;
                    if (isTextNode && !b.inlineFragments.isEmpty()) {
                        int numFrags = b.inlineFragments.size();
                        if (numFrags == 1 && itemCount == 1 && currentDisplayList.get(b.dlStart).type() == ElementType.TEXT) {
                            // 最頻ケース: 単一フラグメント・単一TEXTアイテム（リスト行のテキストなど）
                            LayoutBox.InlineFragment f0 = b.inlineFragments.get(0);
                            int newTop = Math.round(f0.y - partOy);
                            int newLeft = Math.round(f0.x - partOx);
                            int newH = Math.round(f0.y + f0.h - partOy) - newTop;
                            int newW = Math.round(f0.x + f0.w - partOx) - newLeft;
                            DisplayItem it = currentDisplayList.get(b.dlStart);
                            RenderMeta meta = it.meta();
                            if (meta != null && meta.clipRect() != null && expectedClip != null && !expectedClip.equals(meta.clipRect())) {
                                ClipRect nextClip = relMode ? PaintSupport.rebaseSubClip(meta.clipRect(), m.clip, expectedClip, curOx, curOy) : expectedClip;
                                if (!sameClip(nextClip, meta.clipRect())) meta = meta.withClip(nextClip);
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
                                int baseTop = Math.round(f0.y - partOy);
                                int baseLeft = Math.round(f0.x - partOx);
                                float oldFragY = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[1] + fy : f0.y;
                                float oldFragX = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[0] + fx : f0.x;
                                int oldBaseTop = Math.round(oldFragY - oldPartOy);
                                int oldBaseLeft = Math.round(oldFragX - oldPartOx);
                                for (int idx = b.dlStart; idx < b.dlEnd; idx++) {
                                    DisplayItem it = currentDisplayList.get(idx);
                                    RenderMeta meta = it.meta();
                                    if (meta != null && meta.clipRect() != null && expectedClip != null && !expectedClip.equals(meta.clipRect())) {
                                        ClipRect nextClip = relMode ? PaintSupport.rebaseSubClip(meta.clipRect(), m.clip, expectedClip, curOx, curOy) : expectedClip;
                                        if (!sameClip(nextClip, meta.clipRect())) meta = meta.withClip(nextClip);
                                    }
                                    if (it.type() == ElementType.TEXT) {
                                        LayoutBox.InlineFragment f = validFrags.get(fIdx++);
                                        int newTop = Math.round(f.y - partOy);
                                        int newLeft = Math.round(f.x - partOx);
                                        int newH = Math.round(f.y + f.h - partOy) - newTop;
                                        int newW = Math.round(f.x + f.w - partOx) - newLeft;
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
                                        int newX = curOx - curPox;
                                        int newY = curOy - curPoy;
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
                                int targetTop = Math.round(f0.y - partOy);
                                int targetLeft = Math.round(f0.x - partOx);
                                float oldFragY = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[1] + fy : f0.y;
                                float oldFragX = m.fragNums != null && m.fragNums.length >= 2 ? m.fragNums[0] + fx : f0.x;
                                int oldTop = Math.round(oldFragY - oldPartOy);
                                int oldLeft = Math.round(oldFragX - oldPartOx);
                                int targetH = Math.round(f0.y + f0.h - partOy) - targetTop;
                                int targetW = Math.round(f0.x + f0.w - partOx) - targetLeft;
                                int oldHInt = Math.round(oldFragY + f0.h - oldPartOy) - oldTop;
                                int oldWInt = Math.round(oldFragX + f0.w - oldPartOx) - oldLeft;
                                for (int idx = b.dlStart; idx < b.dlEnd; idx++) {
                                    DisplayItem it = currentDisplayList.get(idx);
                                    RenderMeta meta = it.meta();
                                    if (meta != null && meta.clipRect() != null && expectedClip != null && !expectedClip.equals(meta.clipRect())) {
                                        ClipRect nextClip = relMode ? PaintSupport.rebaseSubClip(meta.clipRect(), m.clip, expectedClip, curOx, curOy) : expectedClip;
                                        if (!sameClip(nextClip, meta.clipRect())) meta = meta.withClip(nextClip);
                                    }
                                    if (it.id() != null && b.node != null && it.id().equals(b.node.key())) {
                                        int newX = curOx - curPox;
                                        int newY = curOy - curPoy;
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
                        int targetTop = Math.round(b.y - partOy);
                        int targetLeft = Math.round(b.x - partOx);
                        float oldBoxY = m.nums != null && m.nums.length >= 2 ? m.nums[1] + ay : b.y;
                        float oldBoxX = m.nums != null && m.nums.length >= 2 ? m.nums[0] + ax : b.x;
                        float oldBoxH = m.nums != null && m.nums.length >= 4 ? m.nums[3] : b.h;
                        float oldBoxW = m.nums != null && m.nums.length >= 4 ? m.nums[2] : b.w;
                        int oldTop = Math.round(oldBoxY - oldPartOy);
                        int oldLeft = Math.round(oldBoxX - oldPartOx);
                        int targetH = Math.round(b.y + b.h - curOy) - (hasRelOrigin ? 0 : targetTop);
                        int targetW = Math.round(b.x + b.w - curOx) - (hasRelOrigin ? 0 : targetLeft);
                        int oldHInt = Math.round(oldBoxY + oldBoxH - oldPartOy) - oldTop;
                        int oldWInt = Math.round(oldBoxX + oldBoxW - oldPartOx) - oldLeft;
                        int shiftX = targetLeft - oldLeft;
                        int shiftY = targetTop - oldTop;
                        boolean sameDim = (targetH == oldHInt && targetW == oldWInt);

                        for (int idx = b.dlStart; idx < b.dlEnd; idx++) {
                            DisplayItem it = currentDisplayList.get(idx);
                            int newX, newY, newW, newH;
                            if (hasRelOrigin && idx == b.dlStart && it.type() == ElementType.NODE && it.hints().relativeOrigin()) {
                                newX = curOx - curPox;
                                newY = curOy - curPoy;
                                newW = targetW;
                                newH = targetH;
                            } else if (sameDim) {
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
                                ClipRect nextClip = relMode ? PaintSupport.rebaseSubClip(meta.clipRect(), m.clip, expectedClip, curOx, curOy) : expectedClip;
                                if (!sameClip(nextClip, meta.clipRect())) meta = meta.withClip(nextClip);
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

                    if (m.itemCoords != null) {
                        if (m.items != null && m.itemCoords.length == m.items.size() * 4 && itemCount >= m.items.size()) {
                            int odx = relMode ? (partOx - oldPartOx) : 0;
                            int ody = relMode ? (partOy - oldPartOy) : 0;
                            float boxShiftX = (m.nums != null && m.nums.length >= 2) ? ((b.x - m.nums[0]) - odx) : 0f;
                            float boxShiftY = (m.nums != null && m.nums.length >= 2) ? ((b.y - m.nums[1]) - ody) : 0f;
                            float fragShiftX = (!b.inlineFragments.isEmpty() && m.fragNums != null && m.fragNums.length >= 2)
                                    ? ((b.inlineFragments.get(0).x - m.fragNums[0]) - odx) : boxShiftX;
                            float fragShiftY = (!b.inlineFragments.isEmpty() && m.fragNums != null && m.fragNums.length >= 2)
                                    ? ((b.inlineFragments.get(0).y - m.fragNums[1]) - ody) : boxShiftY;
                            boolean valid = true;
                            for (int i = 0, nItems = m.items.size(); i < nItems; i++) {
                                DisplayItem cur = currentDisplayList.get(b.dlStart + i);
                                float nx, ny;
                                if (hasRelOrigin && i == 0 && cur.type() == ElementType.NODE && cur.hints().relativeOrigin()) {
                                    nx = b.x - curPox;
                                    ny = b.y - curPoy;
                                } else {
                                    boolean isFragItem = !b.inlineFragments.isEmpty() && movesWithFragment(cur);
                                    nx = m.itemCoords[i * 4] + (isFragItem ? fragShiftX : boxShiftX);
                                    ny = m.itemCoords[i * 4 + 1] + (isFragItem ? fragShiftY : boxShiftY);
                                }
                                float fw = m.itemCoords[i * 4 + 2], fh = m.itemCoords[i * 4 + 3];
                                int rx = Math.round(nx), ry = Math.round(ny);
                                int rw = Math.round(nx + fw) - rx, rh = Math.round(ny + fh) - ry;
                                if (rx != cur.x() || ry != cur.y() || rw != cur.width() || rh != cur.height()) {
                                    valid = false;
                                    break;
                                }
                                m.itemCoords[i * 4] = nx;
                                m.itemCoords[i * 4 + 1] = ny;
                            }
                            if (!valid) m.itemCoords = null;
                        } else {
                            m.itemCoords = null;
                        }
                    }
                    m.appliedDx = m.appliedDy = 0; // 記憶を今のリストの状態に揃える（下で項目も取り直す）
                    m.appliedExactDx = m.appliedExactDy = 0f;
                    m.appliedOriginDx = m.appliedOriginDy = 0;
                    m.appliedFragOnly = false;
                    m.appliedNonUniform = false;
                    if (m.nums != null && m.nums.length >= 2) {
                        m.nums[0] = b.x;
                        m.nums[1] = b.y;
                    }
                    if (relMode) {
                        m.originX = curOx;
                        m.originY = curOy;
                        m.parentOriginX = curPox;
                        m.parentOriginY = curPoy;
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
                        if (hChanged) am.itemCoords = null;
                        am.items = List.copyOf(currentDisplayList.subList(anc.dlStart, memoEnd));
                        am.itemsMutable = false;
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
            int ancOx = (relMode && PaintOrder.boxHasOwnNode(anc)) ? Math.round(anc.x) : 0;
            int ancOy = (relMode && PaintOrder.boxHasOwnNode(anc)) ? Math.round(anc.y) : 0;
            int barThickness = 4;
            if (wantsV && vtIdx >= 0 && vIdx >= 0) {
                float curY = scrollState != null ? scrollState.getScrollY(anc.node) : 0f;
                float trackX = anc.x + anc.w - anc.bR - barThickness - 2;
                float trackY = anc.y + anc.bT + 2;
                float trackH = anc.h - anc.bT - anc.bB - 4;
                float thumbH = Math.max(12, trackH * (anc.h / scrollH));
                float maxScrollY = Math.max(1f, scrollH - anc.h);
                float thumbY = trackY + (trackH - thumbH) * Math.clamp(curY / maxScrollY, 0f, 1f);

                int vtLeft = Math.round(trackX - ancOx);
                int vtTop = Math.round(trackY - ancOy);
                int vtW = Math.round(trackX - ancOx + barThickness) - vtLeft;
                int vtH = Math.round(trackY - ancOy + trackH) - vtTop;

                int vLeft = Math.round(trackX - ancOx);
                int vTop = Math.round(thumbY - ancOy);
                int vW = Math.round(trackX - ancOx + barThickness) - vLeft;
                int vH = Math.round(thumbY - ancOy + thumbH) - vTop;

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
                    int svLeft = Math.round(anc.x - ancOx);
                    int svTop = Math.round(anc.y - ancOy);
                    int svW = Math.round(anc.x - ancOx + anc.w) - svLeft;
                    int svH = Math.round(anc.y - ancOy + anc.h) - svTop;
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

                int htLeft = Math.round(trackX - ancOx);
                int htTop = Math.round(trackY - ancOy);
                int htW = Math.round(trackX - ancOx + trackW) - htLeft;
                int htH = Math.round(trackY - ancOy + barThickness) - htTop;

                int hLeft = Math.round(thumbX - ancOx);
                int hTop = Math.round(trackY - ancOy);
                int hW = Math.round(thumbX - ancOx + thumbW) - hLeft;
                int hH = Math.round(trackY - ancOy + barThickness) - hTop;

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
                    int shLeft = Math.round(anc.x - ancOx);
                    int shTop = Math.round(anc.y - ancOy);
                    int shW = Math.round(anc.x - ancOx + anc.w) - shLeft;
                    int shH = Math.round(anc.y - ancOy + anc.h) - shTop;
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
