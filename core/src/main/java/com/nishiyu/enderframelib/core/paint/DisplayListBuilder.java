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
import com.nishiyu.enderframelib.core.paint.RenderTask;

public class DisplayListBuilder {
    /** StackingSubtreePatch（切り出した協力クラス）。 */
    private final StackingSubtreePatch stackingPatch = new StackingSubtreePatch(this);
    // 単位なしの 0（例: translate(-50%, 0)）も受け付けるため単位は省略可能（省略時は px 扱い）

    static volatile java.util.function.BooleanSupplier parentRelativeSource =
            () -> !Boolean.getBoolean("enderframe.paint.noparentrelative");

    public static void setParentRelativeSource(java.util.function.BooleanSupplier source) {
        parentRelativeSource = source != null ? source : () -> true;
    }

    private static final Set<String> NON_FRAGMENT_TAGS = Set.of(
            "#text", "img",
            "input", "select", "progress", "meter", "textarea", "canvas", "svg"
    );

    private int rootW;

    int rootH;

    private final Map<String, Keyframes> keyframes;

    final ScrollState scrollState;

    /** 要素の部品の id（{@code _txt_3} など）に振る通し番号。描画のたびに 0 から数える。 */
    private final AtomicInteger fragIds = new AtomicInteger();
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
            public void owned(LayoutBox box, Chunk chunk) {
                BoxMemo m = boxMemo.get(box);
                if (m != null) m.chunk = chunk;
            }
        }, DisplayListBuilder::memoizable);
    }

    /** 切り分け用: false なら部分木の保持（{@link Chunk}）を使わない。結果は同じでなければならない。 */
    public static void setChunksEnabled(boolean enabled) {
        PaintOrder.chunksEnabled = enabled;
    }

    /** 診断: 部分木の保持を再利用した回数の累計。 */
    public static long chunksReplayed() {
        return PaintOrder.STAT_CHUNK_REPLAYED.sum();
    }

    /** 直近に描画またはパッチされた完全な表示リスト（Segmented DisplayList）。 */
    List<DisplayItem> currentDisplayList;
    /** 直近に描画されたボックス（描画順 = 表示リスト上の dlStart 昇順）。 */
    final List<LayoutBox> renderedBoxes = new ArrayList<>();
    /** スクロールバーを持ちうる（overflow: scroll / auto）描画済みの箱。差分パッチで全箱を走査せずに済ませるための集合（描画のたびに作り直し、子の増減で更新する）。 */
    final Set<LayoutBox> scrollBoxes = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 窓付きの描画で項目を省いた箱（画面外）。差分パッチが、窓の出入りが無いかを調べるのに使う。 */
    final List<LayoutBox> culledBoxes = new ArrayList<>();
    /** 直近の描画のルートボックス。 */
    LayoutBox currentRoot;

    /** 診断: 表示リストの差分スライスパッチ成功回数（全ボックス走査スキップ）。 */
    /** 差分パッチで、項目を作り直さず塗りをセルへ書いて済ませた項目の数（B9 の M3）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_REPAINT_IN_PLACE = new java.util.concurrent.atomic.LongAdder();
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

        // 部分木の保持は、全ての箱を描く描画でだけ（平行移動・画面外の省略では項目が欠ける／動く）。再利用は変更印が使える描画でだけ
        order.beginRender(buildSerial, buildEpoch, generation, !translating && !windowed, useChangeStamps && !translating);
        prunedOwners = 0;
        pruneWindowed = windowed;
        pruneSweeping = generation % PaintOrder.SWEEP_EVERY == 0;
        order.subtreePruner = cull && PRUNE_ENABLED ? pruner : null;
        List<RenderTask> paintTaskList;
        try {
            paintTaskList = order.paintTasks(box);
        } finally {
            order.subtreePruner = null;
        }
        ownerCount += prunedOwners;
        ownerCount += executeTasks(paintTaskList, elements, cull, windowed, 0, renderedBoxes);
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

    /**
     * 描画順のタスク（{@link PaintOrder#paintTasks} / {@link PaintOrder#stackingSubtreeTasks}）を順に実行して項目を出し、
     * 箱ごとのスライス（{@code dlStart} / {@code dlEnd} / {@code dlOrder}）・描画順の箱の一覧・窓の外の記録を付ける。
     * レイヤーに描く箱は、錨の項目に出し終えた項目の数（span）を書く。全体の作り直しと部分木の組み直し（B12）で同じ手順を使う。
     *
     * @param orderBase 描画順の箱の一覧での、{@code outBoxes} の先頭の位置（全体なら 0）
     * @return 持ち主（箱を持つタスク）の数
     */
    int executeTasks(List<RenderTask> paintTaskList, List<DisplayItem> elements, boolean cull, boolean windowed,
                             int orderBase, List<LayoutBox> outBoxes) {
        int ownerCount = 0;
        // レイヤーに描く箱: 開始の印の次に出る錨の位置を覚え、終了の印でそこから出し終えた項目の数（span）を錨に書く
        int[] openLayerStarts = null;
        LayerSpec[] openSpecs = null;
        LayoutBox[] openBoxes = null;
        int layerDepth = 0;
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
            owner.dlOrder = orderBase + outBoxes.size();
            outBoxes.add(owner);
            if (owner.node != null && PaintOrder.factsOf(owner.style).scrollbars()) scrollBoxes.add(owner);
        }
        return ownerCount;
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
    private boolean pruneOffscreenSubtree(LayoutBox c, boolean ancestorHasMoving, Object[] prepared, int index) {
        Object pre = prepared != null ? prepared[index] : null;
        if (pre == PREP_REJECT || pre == PREP_NOT_CULLABLE) return false;
        if (pre == null && !prunableRoot(c, ancestorHasMoving)) return false; // PREP_KNOWN と走査済みは入口の条件を通っている
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
        PrunedSubtree fresh;
        if (pre instanceof PrunedSubtree p) {
            fresh = p; // 先に並列で走査済み（覚えが無いか古いと分かった箱だけ）
        } else {
            pruneScrolls.clear();
            pruneVisited.clear();
            if (!cullableSubtree(c, c, pruneVisited, pruneScrolls)) return false;
            fresh = new PrunedSubtree(
                    pruneVisited.isEmpty() ? EMPTY_BOX_ARRAY : pruneVisited.toArray(EMPTY_BOX_ARRAY),
                    pruneScrolls.isEmpty() ? EMPTY_NODE_ARRAY : pruneScrolls.toArray(EMPTY_NODE_ARRAY),
                    LayoutBox.paintEpoch());
        }
        if (fresh.scrolls.length <= 8) {
            if (pruneCache.size() > Math.max(8192, lastOwnerCount)) pruneCache.clear(); // 消えた箱の分が溜まり続けないように
            pruneCache.put(c, fresh);
        }
        if (fresh.premarked) {
            fresh.applyRecord(); // 箱ごとの書き込みは先の並列の判定で済んでいる
            fresh.premarked = false;
        } else {
            fresh.record();
        }
        fresh.recordedSerial = buildSerial;
        fresh.recordedWindowed = pruneWindowed;
        STAT_PRUNED_SUBTREES.increment();
        return true;
    }

    /** 部分木ごと省く判定の入口の条件（箱そのものが持ち主になりうる・動かない・窓の判定に掛からない）。 */
    private boolean prunableRoot(LayoutBox c, boolean ancestorHasMoving) {
        if (c.isAnonymous || c.node == null || c.display == null || c.style == null || c.display.isNone()
                || "#text".equals(c.node.tag)) {
            return false;
        }
        return !ancestorHasMoving && !PaintOrder.hasMovingProps(c.style) && !inWindowGeometry(c);
    }

    /** 先の判定の結果: 入口の条件で外れた／部分木が省けない／入口は通り、覚えがまだ使える。 */
    private static final Object PREP_REJECT = new Object();
    private static final Object PREP_NOT_CULLABLE = new Object();
    private static final Object PREP_KNOWN = new Object();
    /** 先に並列で判定する子の並びの下限（これ未満は配る費用の方が大きい）。 */
    static final int PREPARE_MIN_CHILDREN = 64;
    /** 診断: 先の並列の判定で走査し、箱ごとの記録まで済ませた部分木の数（並列が無効〔{@code -Denderframe.layout.noparallel=true}〕なら直列の判定だけになる）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_PREPARED_SUBTREES = new java.util.concurrent.atomic.LongAdder();

    private final PaintOrder.SubtreePruner pruner = new PaintOrder.SubtreePruner() {
        @Override
        public boolean prune(LayoutBox box, boolean ancestorHasMoving, Object[] prepared, int index) {
            return pruneOffscreenSubtree(box, ancestorHasMoving, prepared, index);
        }

        @Override
        public Object[] prepare(List<LayoutBox> kids, boolean ancestorHasMoving) {
            return preparePrune(kids, ancestorHasMoving);
        }
    };

    /**
     * 大きな子の並びについて、部分木ごと省けるかの走査（{@link #cullableSubtree}）を先に並列で済ませる（B11）。全行が
     * 測り直されるフレーム（{@code font-size} の変更）では全部の箱に描画の印が付き、画面外の行ごとの覚えが毎回外れて、
     * 2,000 行の走査が表示リストの組み立て（直列）の大半を占めていた。各要素は、入口の条件で外れた・覚えがまだ使える（null）・
     * 省けない・省ける（走査済みの {@link PrunedSubtree}、まだ記録していない）のどれか。走査は箱の木・位置・スタイルを読むだけで、
     * 書くのは各行の結果の欄だけ（組み立ての状態には触れない）。記録・覚えへの登録は従来どおり直列の {@link #pruneOffscreenSubtree}。
     */
    private Object[] preparePrune(List<LayoutBox> kids, boolean ancestorHasMoving) {
        int n = kids.size();
        if (n < PREPARE_MIN_CHILDREN || !com.nishiyu.enderframelib.core.layout.LayoutTaskPool.parallelEnabled
                || com.nishiyu.enderframelib.core.layout.LayoutTaskPool.participants() < 2 || com.nishiyu.enderframelib.core.layout.LayoutTaskPool.inWorker()) {
            return null;
        }
        Object[] out = new Object[n];
        long epoch = LayoutBox.paintEpoch();
        int grain = com.nishiyu.enderframelib.core.layout.LayoutTaskPool.grainFor(n, 16);
        com.nishiyu.enderframelib.core.layout.LayoutTaskPool.forRange(n, grain, null, i -> {
            LayoutBox c = kids.get(i);
            if (!prunableRoot(c, ancestorHasMoving)) {
                out[i] = PREP_REJECT;
                return;
            }
            PrunedSubtree known = pruneCache.get(c);
            if (known != null && known.stillValidAt(epoch) && !known.anyShifted()) { // 覚えが使える（直列側で使う）
                out[i] = PREP_KNOWN;
                return;
            }
            ArrayList<LayoutBox> visited = new ArrayList<>();
            ArrayList<com.nishiyu.enderframelib.core.dom.node.HtmlNode> scrolls = new ArrayList<>(0);
            if (!cullableSubtree(c, c, visited, scrolls)) {
                out[i] = PREP_NOT_CULLABLE;
                return;
            }
            PrunedSubtree fresh = new PrunedSubtree(visited.toArray(EMPTY_BOX_ARRAY),
                    scrolls.isEmpty() ? EMPTY_NODE_ARRAY : scrolls.toArray(EMPTY_NODE_ARRAY), epoch);
            // 走査済みの部分木は直列側で必ず省かれる（覚えは古いと分かっている）ので、箱ごとの書き込みもここで済ませる
            visited.clear(); // 走査に使ったリストを、持ち主の作業用に使い回す
            fresh.markBoxes(visited);
            fresh.premarked = true;
            out[i] = fresh;
            STAT_PREPARED_SUBTREES.increment();
        });
        return out;
    }

    private static final LayoutBox[] EMPTY_BOX_ARRAY = new LayoutBox[0];
    private static final com.nishiyu.enderframelib.core.dom.node.HtmlNode[] EMPTY_NODE_ARRAY =
            new com.nishiyu.enderframelib.core.dom.node.HtmlNode[0];
    /** 直列の記録（{@link PrunedSubtree#record}）が窓の外の持ち主を集める作業用のリスト。 */
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

        /** 先の並列の判定（{@link #preparePrune}）で {@link #markBoxes} を済ませ、まだ {@link #applyRecord} していない。 */
        boolean premarked;

        /** 部分木の箱ごとの記録を残す（箱ごとに省いたときと同じ）。 */
        void record() {
            markBoxes(pruneGeoScratch);
            applyRecord();
        }

        /**
         * 記録のうち、箱ごとの書き込み（項目の位置の取り消し・記憶の保持）。書くのは部分木の箱と、その箱の記憶だけなので、
         * 別々の部分木なら並列に呼べる（{@code scratch} はスレッドごとに別のもの）。持ち主の数と窓の外の記録に入れる箱を自分に覚える。
         */
        void markBoxes(List<LayoutBox> scratch) {
            scratch.clear();
            int n = 0;
            for (LayoutBox b : boxes) {
                if (!isPruneOwner(b)) continue;
                n++;
                markPruned(b);
                if (isGeometryOwner(b)) scratch.add(b);
            }
            ownerCount = n;
            geometryOwners = scratch.isEmpty() ? EMPTY_BOX_ARRAY : scratch.toArray(EMPTY_BOX_ARRAY);
            scratch.clear();
        }

        /** 記録のうち、組み立て全体の数と窓の外の一覧へ足す分（直列。木の順に足す）。 */
        void applyRecord() {
            prunedOwners += ownerCount;
            if (pruneWindowed) for (LayoutBox b : geometryOwners) culledBoxes.add(b); // addAll(asList) は包みと配列の写しを確保する
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

        /** {@link #stillValid} と同じ判定で、覚えの世代を書き換えない（並列の先の判定用）。 */
        boolean stillValidAt(long now) {
            if (now == epoch) return true;
            for (LayoutBox b : boxes) if (b.paintStamp > epoch) return false;
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

    /**
     * {@code top} の部分木を丸ごと省けるか。辿った箱を {@code visited}、中のスクロールコンテナの節を {@code scrolls} へ足す。
     * 読むだけで組み立ての状態を書かないので、別々の部分木なら並列に呼べる（{@link #preparePrune}）。
     */
    private boolean cullableSubtree(LayoutBox b, LayoutBox top, List<LayoutBox> visited,
                                    List<com.nishiyu.enderframelib.core.dom.node.HtmlNode> scrolls) {
        visited.add(b);
        if (b.display != null && b.display.isNone()) return true;
        if (b.style != null && b.isScrollContainer()) scrolls.add(b.node);
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
                if (!cullableSubtree(kids.get(i), top, visited, scrolls)) return false;
            }
        }
        return true;
    }

    /** 部分木ごと省いた箱の、箱ごとに省いたときと同じ記録のうち、箱の側の書き込み（数と窓の外の一覧は {@link PrunedSubtree#applyRecord}）。 */
    private void markPruned(LayoutBox b) {
        if (pruneSweeping) { // 記憶の掃除はこの世代でだけ使われを見る。それ以外の世代では、記憶を引かずに済ませる
            BoxMemo kept = boxMemo.get(b);
            if (kept != null) kept.generation = generation;
        }
        b.dlStart = -1;
        b.paintCulled = true;
        b.dlEnd = -1;
        b.dlOrder = -1;
    }

    /** 直近の描画で出した項目の数（次の描画の出力リストの初期容量に使う）。 */
    int lastElementCount;
    /** 直近の描画の、項目を持ちうる箱の数（省いた箱も含む）。窓付きの表示リストにするかの判定に使う。 */
    private int lastOwnerCount;
    /** 窓付きで作った表示リストの数（検証用）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_WINDOWED_BUILDS = new java.util.concurrent.atomic.LongAdder();
    /** 直近の描画が窓付き（画面外の箱を省いた）か。 */
    boolean windowedBuild;
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
        List<LayoutBox.InlineFragment> frags = at.inlineFragments;
        if (frags != null && !frags.isEmpty()) {
            // 行に分かれた箱は、断片の範囲で判定する（A6b ⑤）。インラインの中の箱は箱自身の x / y が (0, 0) のまま
            // （位置は断片にだけある）ことがあり、以前は窓の中と判定された。親（行・外側の span）は省かれて、
            // この箱の項目だけが親の節なしで出て、スクロールする箱の上端に描かれた（scrolllist・noscrollnode・種 7）
            for (int i = 0, n = frags.size(); i < n; i++) {
                LayoutBox.InlineFragment f = frags.get(i);
                if (f.x - sx + f.w >= -CULL_MARGIN && f.x - sx <= rootW + CULL_MARGIN
                        && f.y - sy + f.h >= -CULL_MARGIN && f.y - sy <= rootH + CULL_MARGIN) return true;
            }
            return false;
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

    int movingEpochSeq;
    int activeMovingEpoch;
    private final ArrayList<LayoutBox> movingPathScratch = new ArrayList<>();

    boolean hasMovingAncestor(LayoutBox box) {
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
                anchor.baseTranslateX(), anchor.baseTranslateY(), anchor.scaleX(), anchor.scaleY(), anchor.rotate(),
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


    boolean parentRelative = true;

    final java.util.IdentityHashMap<LayoutBox, BoxMemo> boxMemo = new java.util.IdentityHashMap<>();
    /** 子の挿入・削除の差分パッチ（{@link #patchChildSlices}）。A8 で切り出した。 */
    private final ChildSlicePatch childPatch = new ChildSlicePatch(this);
    int generation;
    /** 変更印で署名の比較を省くか（呼び出し側が、変わった箱が分かっている局所更新のときだけ true にする）。 */
    private boolean useChangeStamps;
    long buildEpoch;
    int buildSerial;
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
    boolean translating;
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
                || m.suppressShadow != suppressTextShadow || !BoxMemo.sameClip(m.clip, clip)
                || !BoxMemo.sameStyle(m.placeholderStyle, box.placeholderStyle)
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
        if (!BoxMemo.sameStyle(m.style, box.style) || !BoxMemo.sameStyle(m.firstLineStyle, box.firstLineStyle)
                || !BoxMemo.sameStyle(m.placeholderStyle, box.placeholderStyle)
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
        boolean intMove = BoxMemo.isIntegerTranslation(localDx) && BoxMemo.isIntegerTranslation(localDy);
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
        if (m.nums.length != BoxMemo.NUMS_LENGTH) return matchFail(5);
        for (int i = 2; i < BoxMemo.NUMS_LENGTH; i++) if (Float.compare(m.nums[i], BoxMemo.numAt(box, i)) != 0) return matchFail(6);
        boolean clipSame = BoxMemo.sameClip(m.clip, clip);
        boolean clipChainSame = clipSame || (parentRelative && BoxMemo.sameClipChain(m.clip, clip));
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
            if (depth >= m.ancestors.length || !BoxMemo.sameStyle(m.ancestors[depth], a.style)) return matchFail(11);
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
            if (m.fragRefs[r++] != f.box || !BoxMemo.sameStyle((com.nishiyu.enderframelib.core.css.ComputedStyle) m.fragRefs[r++], f.box != null ? f.box.style : null)) return matchFail(17);
            List<LayoutBox> parents = f.parents;
            int pn = parents == null ? 0 : parents.size();
            if (!(m.fragRefs[r++] instanceof Integer count) || count != pn) return matchFail(18);
            for (int k = 0; k < pn; k++) {
                if (m.fragRefs[r++] != parents.get(k) || !BoxMemo.sameStyle((com.nishiyu.enderframelib.core.css.ComputedStyle) m.fragRefs[r++], parents.get(k).style)) return matchFail(19);
            }
            com.nishiyu.enderframelib.core.css.ComputedStyle ownerFl = f.firstLineOwner != null ? f.firstLineOwner.firstLineStyle : null;
            if (m.fragRefs[r++] != f.firstLineOwner || !BoxMemo.sameStyle((com.nishiyu.enderframelib.core.css.ComputedStyle) m.fragRefs[r++], ownerFl)) return matchFail(17);
        }
        if (parentRelative && !moved) {
            if (!clipSame && BoxMemo.clipDifferenceOutsideItems(m, clip, box, scrollState, hasOwnNode ? curOx : curPox, hasOwnNode ? curOy : curPoy, 0, 0, null)) {
                clipSame = true;
            }
            if (!relPosChanged && !scrollChanged && clipSame) {
                BoxMemo.syncParentRelPositionOnly(m, box, curOx, curOy, curPox, curPoy, dx, dy);
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
                    && !BoxMemo.hasMovingShadowShape(m.items, true, fragmentsOnly)) {
                boolean effClipSame = clipSame || BoxMemo.clipDifferenceOutsideItems(
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
                    && !BoxMemo.hasMovingShadowShape(m.items, true, fragmentsOnly)) {
                boolean effClipSame = clipSame || BoxMemo.clipDifferenceOutsideItems(
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
            if (!isBoxNode && fragmentsOnly && !BoxMemo.movesWithFragment(it)) {
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
                            && (BoxMemo.nearHalfBoundary(box.x, m.nums[0], bdx) || BoxMemo.nearHalfBoundary(box.y, m.nums[1], bdy)
                            || BoxMemo.nearHalfBoundary(box.x + box.w, m.nums[0] + m.nums[2], bdx)
                            || BoxMemo.nearHalfBoundary(box.y + box.h, m.nums[1] + m.nums[3], bdy))) {
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
                if (BoxMemo.nearHalfBoundary(nx, fx, dx) || BoxMemo.nearHalfBoundary(ny, fy, dy)
                        || BoxMemo.nearHalfBoundary(nx + fw, fx + fw, dx) || BoxMemo.nearHalfBoundary(ny + fh, fy + fh, dy)) {
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

    /**
     * 項目が平行移動だけで作り直せるか: 座標を持つのが x・y と（前回のクリップそのものの）クリップだけで、
     * 箱の座標で決まる図形（影・グラデーション・clip-path・backdrop）や、画面に固定の背景を持たない。
     */
    private static final String[] EMPTY_STRINGS = new String[0];
    private static final float[] EMPTY_FLOATS = new float[0];
    private static final Object[] EMPTY_OBJECTS = new Object[0];

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
        m.ancestors = BoxMemo.ancestorStyles(box, m.ancestors);
        m.nums = BoxMemo.fillNums(box, m.nums);
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
        m.translatable = BoxMemo.survivesTranslation(box, clip, items, parentRelative);
        m.carriesContentExtent = BoxMemo.carriesContentExtent(items);
        m.nodeAndTextOnly = BoxMemo.onlyNodeAndText(items);
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
                BoxMemo.appendNodePatched(elements, m, box, clipRect, hitNewRelX, hitNewRelY,
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
                BoxMemo.appendTranslated(elements, m, clipRect, hitDx, hitDy, hitIntDx, hitIntDy, hitFragmentsOnly,
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
            drawBoxSelfUncached(elements, box, clipRect, parentId, m != null ? m.items : null);
            int count = elements.size() - start;
            List<DisplayItem> built = BoxMemo.sliceCopy(m, elements, start);
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

    /**
     * @param prevItems この箱が前に出した項目（無ければ null）。メタ・ヒントを作り直さず使い回す候補（{@code reuseMeta}・{@code hintsReuse}）。
     *                  差分パッチ（{@link #patchBoxes}）でも、表示リストの前のスライスを渡す（以前は null で、hover のたびに全部作り直していた）
     */
    private void drawBoxSelfUncached(List<DisplayItem> elements, LayoutBox box, ClipRect clipRect, String parentId, List<DisplayItem> prevItems) {
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

            ItemProps partsEvents = ItemProps.populateForParts(scratchPartsProps, box, id, transSpec, prevPartsAttrs);
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

    ClipRect clipForBox(LayoutBox box) {
        BoxMemo m = boxMemo.get(box);
        if (m != null && m.clip != null) return m.clip;
        // 記憶が無い箱（記憶を切った構成・記憶できない箱）は、親の中身の clip を組み立て直す（A6b ④）。
        // 以前は祖先の記憶の clip を探していたが、それは祖先「自身の」項目の clip で、祖先が overflow で切る箱（スクロールする箱）なら
        // 子の clip とは違う。祖先にも記憶が無いと null になり、:hover が外れて描き直した行がスクロールする箱の外へはみ出して描かれた
        return box.parent != null ? order.contentClipOf(box.parent) : null;
    }

    String contentParentIdOf(LayoutBox box) {
        if (box == null) return null;
        PaintOrder.ScrollContent sc = order.scrollContentOf(box);
        if (sc != null) return sc.contentId;
        return box.node != null ? box.node.key() : null;
    }

    String parentIdForBox(LayoutBox box) {
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

    /**
     * 兄弟の「ブロック層の末尾／先頭」を探すための収集。インラインレベルの箱・flex / grid アイテム（{@link PaintSupport#isAtomicInline}）・float・位置指定は
     * 中身ごと後ろの層で描かれるので、その箱も子孫も入れない（入れると、層の末尾がリストの末尾を指してしまう）。
     */
    private void collectBlockPhaseBoxes(LayoutBox box, List<LayoutBox> out) {
        if (box == null || box.w <= 0 || box.h <= 0 || (box.display != null && box.display.isNone())) return;
        if (!box.isAnonymous) {
            if (box.display.isInlineLevel() || (box.node != null && box.node.nodeType == NodeType.TEXT) || PaintSupport.isAtomicInline(box)
                    || BlockLayoutEngine.isFloating(box) || PaintOrder.isPositioned(box) || order.createsStackingContext(box)) return; // float・位置指定は後ろの層（§6-109）
            out.add(box);
        }
        if (box.children != null) for (LayoutBox c : box.children) collectBlockPhaseBoxes(c, out);
    }

    /**
     * {@link #collectBlockPhaseBoxes} の残り（インラインレベルの箱・アトミックな箱とその子孫）。木の順。
     * 1 回の走査で、インライン層の箱の下にいるかを引数で運ぶ（以前はブロック層の集合を作って木の順の一覧から引いており、
     * 兄弟ごとにリスト 3 つと IdentityHashMap を作って、子の増減 1 回で数十 KB を確保した）。
     */
    private void collectInlinePhaseBoxes(LayoutBox box, List<LayoutBox> out) { collectInlinePhaseBoxes(box, out, false); }

    private void collectInlinePhaseBoxes(LayoutBox box, List<LayoutBox> out, boolean underInline) {
        if (box == null || box.w <= 0 || box.h <= 0 || (box.display != null && box.display.isNone()) || PaintOrder.isPositioned(box) || order.createsStackingContext(box)
                || (!underInline && BlockLayoutEngine.isFloating(box))) return; // 位置指定・float の中身はその層で描く（アトミックな箱の中の float はその箱の中。§6-109）
        boolean inline = underInline;
        if (!box.isAnonymous) {
            inline |= box.display.isInlineLevel() || (box.node != null && box.node.nodeType == NodeType.TEXT) || PaintSupport.isAtomicInline(box);
            if (inline) out.add(box);
        }
        if (box.children != null) for (LayoutBox c : box.children) collectInlinePhaseBoxes(c, out, inline);
    }

    /** 挿入位置の探索で兄弟ごとに使い回す一覧（子パッチは描画のスレッドだけが走らせる）。 */
    private final ArrayList<LayoutBox> phaseScratch = new ArrayList<>();

    List<LayoutBox> blockPhaseOf(LayoutBox c) {
        phaseScratch.clear();
        collectBlockPhaseBoxes(c, phaseScratch);
        return phaseScratch;
    }

    List<LayoutBox> inlinePhaseOf(LayoutBox c) {
        phaseScratch.clear();
        collectInlinePhaseBoxes(c, phaseScratch);
        return phaseScratch;
    }

    void collectSubtreeSlices(LayoutBox box, SubtreeSlices out) {
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

    void renderSubtreeSlices(LayoutBox box, ClipRect clip, String parentId, boolean ancestorHasMoving,
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

    static void markSubtree(LayoutBox b, Set<LayoutBox> out) {
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
            List<DisplayItem> viaStacking = STACKING_PATCH_ENABLED ? stackingPatch.patchViaStackingSubtrees(boxes) : null;
            if (viaStacking == null) STAT_SLICE_PATCH_FALLBACKS.increment();
            return viaStacking;
        }
        if (sorted.isEmpty()) {
            return currentDisplayList;
        }

        decor.setDevicePixelRatio(currentRoot.devicePixelRatio);
        boolean relMode = parentRelativeSource.getAsBoolean();
        this.parentRelative = relMode;
        order.setParentRelative(relMode);
        patchSortedSlices(sorted, relMode);

        syncCanvasBackground(sorted);
        generation++;
        buildSerial++;
        buildEpoch = LayoutBox.paintEpoch();
        lastElementCount = currentDisplayList.size();
        STAT_SLICE_PATCH_HITS.increment();
        return currentDisplayList;
    }

    // ------------------------------------------------------------------ B12: スタッキングコンテキストの部分木の組み直し

    /**
     * 切り分け用（テストが切り替える）: false ならスタッキングコンテキストの部分木の組み直しを使わない（全体の作り直しに落ちる。
     * 結果は同じでなければならない。{@code StackingSubtreePatchTest}）。起動時のスイッチは足さない（近道のスイッチ数のラチェット）。
     */
    public static volatile boolean STACKING_PATCH_ENABLED = true;
    /** 診断: 組み直した部分木の数（スタッキングコンテキストの部分木の組み直しで差分パッチが成り立つたびに 1 以上増える）。 */
    public static final java.util.concurrent.atomic.LongAdder STAT_STACKING_PATCH_HITS = new java.util.concurrent.atomic.LongAdder();

    PaintSupport.CaptureList stackItemsScratch;
    final List<LayoutBox> stackBoxesScratch = new ArrayList<>();

    /** 降順（{@code dlStart} の大きい順）に並んだ箱のスライスを、それぞれ作り直して差し替える（{@link #patchBoxes} の本体）。 */
    void patchSortedSlices(List<LayoutBox> sorted, boolean relMode) {
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
                List<DisplayItem> prevSlice = b.dlStart >= 0 && b.dlEnd <= currentDisplayList.size()
                        ? currentDisplayList.subList(b.dlStart, b.dlEnd) : null;
                newItems.setReusePrev(prevSlice);
                drawBoxSelfUncached(newItems, b, clip, parentId, prevSlice);
            } finally {
                newItems.setReusePrev(null);
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
                    int slot = b.dlStart + i;
                    DisplayItem was = currentDisplayList.get(slot), now = newItems.get(i);
                    // 塗り（色・背景色・不透明度）だけが違うなら、項目を作り直さず前の項目のセルへ書く（B9 の M3）。変更の検出は
                    // セルの通し番号が担う（ItemDiff）。文字は下ごしらえ（SoftwareRenderBackend.textSetups）が項目の同一性で引くので対象外
                    if (was.type() != ElementType.TEXT && was.sameExceptPaint(now)) {
                        if (was.repaintFrom(now)) STAT_REPAINT_IN_PLACE.increment();
                    } else {
                        currentDisplayList.set(slot, now);
                    }
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
    }

    /**
     * キャンバスの背景（先頭の {@code bg_root}）は、背景を譲った要素（ルート、無ければ body）の背景色で、箱のスライスの外にある。
     * その要素を差し替えたら（子孫の :hover で body の背景が変わるなど）、色を合わせる（A6b ④。無いと 1 フレーム古い色が残る）。
     */
    void syncCanvasBackground(List<LayoutBox> patched) {
        LayoutBox source = canvasBackgroundSource;
        if (source == null || currentDisplayList.isEmpty() || !patched.contains(source)) return;
        DisplayItem first = currentDisplayList.get(0);
        if (!"bg_root".equals(first.id())) return;
        int color = ColorParser.parseColor(source.style.getOrDefault(CssPropertyIndex.BACKGROUND_COLOR, "transparent"));
        if (color == first.bgColor() || color == 0x00000000) return; // 透明になる場合は項目ごと無くなる（全面の組み直しでしか表せない）
        DisplayItem now = new DisplayItem(
                first.id(), first.baseNodeId(), first.type(), first.x(), first.y(), first.width(), first.height(),
                first.text(), first.color(), color, first.events(),
                first.radius(), first.opacity(), first.baseTranslateX(), first.baseTranslateY(),
                first.scaleX(), first.scaleY(), first.rotate(), first.transitionDuration(),
                first.animDef(), first.fitMode(), first.meta(), first.key());
        if (!first.repaintFrom(now)) currentDisplayList.set(0, now);
    }

    /**
     * 子要素の増減（CHILD_PATCH）に対して、削除されたボックスと追加されたボックスのスライスを差分差し替え可能か判定する。
     * （R2 で後続座標シフト・親寸法更新のチャンク化とともに本格成立させるため、現時点では理由 12 でフォールバック）
     */
    public boolean canPatchChildSlices(LayoutBox parentBox, List<LayoutBox> removed, List<LayoutBox> added, LayoutBox afterBox) {
        if (currentDisplayList == null || currentDisplayList.isEmpty() || currentRoot == null) return cant(1, 0);
        if (parentBox == null || (removed.isEmpty() && added.isEmpty())) return cant(1, 1);
        if (parentBox.contentAutoSkipped || parentBox.childrenDeferred || parentBox.isVirtualizedFlow()) return cant(1, 12);
        // 後続の箱の座標の反映（手順 7）と祖先の寸法の反映（手順 8）は、箱ごとの記憶が基準。記憶を切っていると基準が無く、古い座標・寸法が残る（A6b ④）
        if (!MEMO_ENABLED) return cant(1, 14);
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
        return childPatch.apply(parentBox, removed, added, afterBox);
    }
}
