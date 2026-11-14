package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.paint.ILayoutBoxRenderer;
import com.nishiyu.enderframelib.core.paint.PaintOrder;
import com.nishiyu.enderframelib.core.paint.model.*;

import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.model.ContainerBox;
import com.nishiyu.enderframelib.core.css.model.ContainerType;
import com.nishiyu.enderframelib.core.css.CounterStyles;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.PropertyRegistry;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.css.model.Keyframes;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;
import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.css.StyleAnimator;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.StyleResolver.PseudoType;
import com.nishiyu.enderframelib.core.css.model.StyleSet;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.layout.BoxTreeBuilder;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.jetbrains.annotations.ApiStatus;

import com.nishiyu.enderframelib.core.kernel.compiler.StateStyleMemo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 描画パイプラインの一元管理（スタイル解決・ボックスツリー・レイアウト計算・ディスプレイリスト生成）。
 */
@ApiStatus.Internal
public class RenderPipeline {

    private static final int LAYOUT_PASSES = 1;
    /** レイアウトの通し番号（{@link LayoutContext#layoutRun()}）。 */
    private static final java.util.concurrent.atomic.AtomicLong LAYOUT_RUNS = new java.util.concurrent.atomic.AtomicLong();
    private static final int MAX_CONTAINER_PASSES = 4;

    private static ILayoutBoxRenderer effectiveRenderer() {
        return com.nishiyu.enderframelib.core.EngineContext.current().boxRenderer;
    }

    private static IntrinsicSizeResolver effectiveIntrinsicResolver() {
        return com.nishiyu.enderframelib.core.EngineContext.current().intrinsicSizeResolver;
    }

    public static List<DisplayItem> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env, ScrollState scrollState, FontResolver fonts) {
        return renderDocument(doc, rootW, rootH, env, scrollState, fonts, new InteractionState());
    }

    public static List<DisplayItem> renderDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                                  ScrollState scrollState, FontResolver fonts, InteractionState interactionState) {
        ILayoutBoxRenderer renderer = effectiveRenderer();
        if (renderer == null) return new ArrayList<>();

        LayoutBox rootBox = layoutDocument(doc, rootW, rootH, env, scrollState, fonts, interactionState);
        return renderer.render(rootBox, rootW, rootH, doc.sheet.keyframes, scrollState);
    }

    public static List<DisplayItem> renderBox(LayoutBox rootBox, int rootW, int rootH, Map<String, Keyframes> keyframes, ScrollState scrollState) {
        ILayoutBoxRenderer renderer = effectiveRenderer();
        if (renderer == null) return new ArrayList<>();
        return renderer.render(rootBox, rootW, rootH, keyframes, scrollState);
    }

    public static LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                           ScrollState scrollState, FontResolver fonts) {
        return layoutDocument(doc, rootW, rootH, env, scrollState, fonts, new InteractionState());
    }

    public static LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                           ScrollState scrollState, FontResolver fonts, InteractionState interactionState) {
        return layoutDocument(doc, rootW, rootH, env, scrollState, fonts, interactionState, null);
    }

    private static final ThreadLocal<com.nishiyu.enderframelib.core.diag.PipelineStats> STATS =
            ThreadLocal.withInitial(com.nishiyu.enderframelib.core.diag.PipelineStats::new);

    private static com.nishiyu.enderframelib.core.diag.PipelineStats stats() {
        return STATS.get();
    }

    private static com.nishiyu.enderframelib.core.EngineContext ctx() {
        return com.nishiyu.enderframelib.core.EngineContext.current();
    }

    /** このスレッドの直近の {@link #layoutDocument} で作った箱の数・算出し直した数・使い回した数（調べる用）。 */
    public static String lastBuildStats() {
        return stats().lastBuildStats;
    }

    /** このスレッドの直近の {@link #relayoutInPlace} が経路だけの印付け（局所）で済んだか。調べる・テスト用。 */
    public static boolean lastRelayoutWasLocal() {
        return stats().lastRelayoutWasLocal;
    }

    /** 呼び出しスレッドがこれまでに割り当てたバイト数（{@link EngineContext#trackAllocations} のときだけ呼ぶ）。 */
    public static long threadAllocatedBytes() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    /**
     * 文書をレイアウトする。{@code previousRoot}（同じ文書の前回の結果）があれば差分レイアウトにする:
     * 箱は DOM の節ごとに使い回し、中身も入力も前回と同じ部分木は measure を飛ばす。
     * 結果は全部を作り直したときと同じ（{@code IncrementalLayoutTest} が確かめる）。
     */
    /** 切り分け用: 前回の箱が無い（新品の箱の）レイアウトでも、同じパスの中の同一入力の測定を使い回す。 */
    public static volatile boolean FRESH_LAYOUT_PASS_REUSE = !Boolean.getBoolean("enderframe.layout.nofreshreuse");

    public static LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                           ScrollState scrollState, FontResolver fonts, InteractionState interactionState,
                                           LayoutBox previousRoot) {
        boolean incremental = ctx().incrementalLayout && previousRoot != null;
        Map<String, String> rootStyleRaw = rootViewportStyle(rootW, rootH);

        InteractionState effectiveState = interactionState != null ? interactionState : new InteractionState();
        effectiveState.bindStyleSheet(doc.sheet);
        boolean mediaChanged = doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(rootW, rootH));
        if (mediaChanged) {
            effectiveState.markAllDirty();
        }
        boolean hasContainers = doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries();

        if (incremental && !mediaChanged && !hasContainers && canSkipRebuild(doc, previousRoot, rootW, rootH, effectiveState)) {
            LayoutBox fast = relayoutRootOnly(doc, previousRoot, rootW, rootH, rootStyleRaw, env, scrollState, fonts, effectiveState);
            if (fast != null) return fast;
        }

        LayoutBox rootBox = incremental ? previousRoot : null;
        for (int settle = 0; settle <= MAX_CONTAINER_PASSES; settle++) {
            LayoutBox.ReusableTable prevReusable = incremental ? LayoutBox.acquireReusableTable(rootBox, doc.root) : null;
            LayoutBox.ReusableTable nextReusable = LayoutBox.ReusableTable.createForBuild(doc.root, prevReusable);
            stats().boxes = stats().restyled = stats().reused = 0;
            stats().allocResolve = stats().allocCompute = stats().allocAnimated = 0;
            long tA = System.nanoTime();
            long profBuild = StageProfiler.begin(PipelineStage.STYLE_BUILD);
            boolean track = ctx().trackAllocations;
            long aA = track ? threadAllocatedBytes() : 0;
            // 共有した結果は残す（restyleLayoutTree と同じ理由。シート・環境・版が変われば ShareCache が捨てる）
            try {
                rootBox = buildLayoutTree(doc.root, rootStyleRaw, doc.sheet, env, FORCE_NONE,
                        ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(rootW, rootH),
                        effectiveState, StyleResolver.hasSiblingStateRules(doc.sheet),
                        prevReusable, nextReusable, false, null,
                        containerFor(doc.root, effectiveState), false);
            } finally {
                StageProfiler.end(PipelineStage.STYLE_BUILD, profBuild);
            }
            nextReusable.releaseAfterBuild(prevReusable);
            rootBox.setReusableTable(nextReusable);
            long tB = System.nanoTime();
            long aB = track ? threadAllocatedBytes() : 0;
            long profBox = StageProfiler.begin(PipelineStage.BOX_TREE);
            BoxTreeBuilder.build(rootBox, CounterStyles.of(doc.sheet.counterStyles));
            StageProfiler.end(PipelineStage.BOX_TREE, profBox);
            effectiveState.lastBuiltStructureVersion = effectiveState.domStructureVersion;
            long tC = System.nanoTime();
            long aC = track ? threadAllocatedBytes() : 0;
            if (incremental) LayoutBox.markLayoutChanges(rootBox);
            rootBox.hasUnmarkedChanges = false;
            rootBox.hasStructuralPending = false;
            long tD = System.nanoTime();
            runLayoutPasses(rootBox, rootW, rootH, doc.sheet.mediaEnvironment().resolution(), scrollState, fonts,
                    incremental || FRESH_LAYOUT_PASS_REUSE, true, false);
            long tE = System.nanoTime();
            long aE = track ? threadAllocatedBytes() : 0;
            stats().phases = "build=" + (tB - tA) / 1_000_000 + " boxTree=" + (tC - tB) / 1_000_000 + " mark=" + (tD - tC) / 1_000_000
                    + " passes=" + (tE - tD) / 1_000_000 + "(measure=" + stats().passMeasureMs + "/" + stats().passMeasured + "boxes layout=" + stats().passLayoutMs + ")"
                    + (track ? " allocKB[resolve=" + stats().allocResolve / 1024 + " compute=" + stats().allocCompute / 1024 + " anim=" + stats().allocAnimated / 1024 + " build=" + (aB - aA) / 1024 + " boxTree=" + (aC - aB) / 1024 + " passes=" + (aE - aC) / 1024 + "]" : "");
            if (!hasContainers || !measureContainers(rootBox, effectiveState)) break;
        }
        stats().lastBuildStats = "boxes=" + stats().boxes + " restyled=" + stats().restyled + " reused=" + stats().reused + " " + stats().phases;
        return rootBox;
    }

    /** 切り分け用: {@code -Denderframe.layout.noresizeskip=true} で、幅変更でも毎回スタイルと箱の木を組み直す。 */
    public static volatile boolean RESIZE_SKIP_REBUILD = !Boolean.getBoolean("enderframe.layout.noresizeskip");

    private static final java.util.concurrent.atomic.AtomicLong RESIZE_SKIPS = new java.util.concurrent.atomic.AtomicLong();

    /** {@link #layoutDocument} がスタイルと箱の木の組み直しを飛ばした回数（テスト・計測用）。 */
    public static long resizeRebuildSkips() {
        return RESIZE_SKIPS.get();
    }

    /**
     * ビューポートだけが変わった再レイアウトで、箱の木のスタイル算出と {@link BoxTreeBuilder} を飛ばしてよいか。
     * メディア環境の結果・コンテナ・汚れ・動いているアニメーションが無く、ルート以外の要素と疑似要素の算出値が
     * ビューポートに依らないとき、ルート以外の算出スタイルも箱の木も前回のまま正しい。
     */
    private static boolean canSkipRebuild(Document doc, LayoutBox previousRoot, int rootW, int rootH, InteractionState state) {
        if (!RESIZE_SKIP_REBUILD || previousRoot == null || previousRoot.node != doc.root || previousRoot.hasStructuralPending) return false;
        InteractionState.StateFlags rootFlags = state.get(previousRoot.node);
        if (rootFlags == null || rootFlags.computedStyle == null || rootFlags.isStyleDirty() || rootFlags.subtreeDirty()
                || rootFlags.dirtyBelow() || state.hasDirtyNodes()) {
            return false;
        }
        if (state.domStructureVersion != state.lastBuiltStructureVersion) return false;
        if (state.animator().isRunning(state.animationTimeMs())) return false;
        return viewportFreeBelowRoot(previousRoot, doc.sheet, state);
    }

    /** ルート以外の要素の算出値（と疑似要素・{@code ::marker} などの値）がビューポートに依らないか。世代が同じ間は結果を使い回す。 */
    private static boolean viewportFreeBelowRoot(LayoutBox root, StyleSheet sheet, InteractionState state) {
        int version = sheet.rules.version();
        if (state.viewportFreeRoot == root && state.viewportFreeEpoch == state.viewportDependenceEpoch
                && state.viewportFreeSheet == sheet && state.viewportFreeVersion == version) {
            return state.viewportFreeResult;
        }
        boolean free = !sheet.declaresViewportDependentValues() || !hasGeneratedBoxes(root);
        for (int i = 0, n = root.elementChildren.size(); free && i < n; i++) free = viewportFree(root.elementChildren.get(i), state);
        state.viewportFreeRoot = root;
        state.viewportFreeEpoch = state.viewportDependenceEpoch;
        state.viewportFreeSheet = sheet;
        state.viewportFreeVersion = version;
        state.viewportFreeResult = free;
        return free;
    }

    private static boolean hasGeneratedBoxes(LayoutBox box) {
        if (box.node.isPseudoNode || box.markerStyle != null || box.placeholderStyle != null || box.backdropStyle != null) return true;
        for (int i = 0, n = box.elementChildren.size(); i < n; i++) {
            if (hasGeneratedBoxes(box.elementChildren.get(i))) return true;
        }
        return false;
    }

    private static boolean viewportFree(LayoutBox box, InteractionState state) {
        // 表のエンジンは中の箱へ直接書き込む（箱の木の組み直しで作り直す前提。buildLayoutTree の reusable の扱いと同じ）ので、表は飛ばさない
        if (LayoutBox.isTableBox(box)) return false;
        // 疑似要素の箱の節は算出スタイルを持たない（値は持ち主の規則から解く。シート全体の判定が受け持つ）
        if (!box.node.isPseudoNode) {
            InteractionState.StateFlags flags = state.get(box.node);
            if (flags == null || !flags.viewportDependenceKnown() || flags.viewportDependent) return false;
        }
        for (int i = 0, n = box.elementChildren.size(); i < n; i++) {
            if (!viewportFree(box.elementChildren.get(i), state)) return false;
        }
        return true;
    }

    /**
     * 幅変更の近道: ルートだけ再スタイルし（幅・最小の高さがビューポート由来）、箱の木はそのまま測り直して配置し直す。
     * ルートの変化が構造に及ぶ（表示の種類など）ときは null を返し、呼び出し側が全体を組み直す。
     */
    private static LayoutBox relayoutRootOnly(Document doc, LayoutBox rootBox, int rootW, int rootH,
                                              Map<String, String> rootStyleRaw, Map<String, String> env,
                                              ScrollState scrollState, FontResolver fonts, InteractionState state) {
        long tA = System.nanoTime();
        state.pruneRestyle = true;
        List<LayoutBox> layoutChangedList = layoutChangedBoxes();
        List<LayoutBox> styleChangedList = styleChangedBoxes();
        layoutChangedList.clear();
        styleChangedList.clear();
        state.restyleDescended = false;
        int result;
        long prof = StageProfiler.begin(PipelineStage.RESTYLE_LOCAL);
        try {
            result = restyleBoxRecursive(rootBox, rootStyleRaw, doc.sheet, env, FORCE_NONE, ComputedStyle.DEFAULT_FONT_SIZE,
                    StyleScope.initial(rootW, rootH), state, StyleResolver.hasSiblingStateRules(doc.sheet),
                    containerFor(rootBox.node, state), false, layoutChangedList, styleChangedList);
        } finally {
            StageProfiler.end(PipelineStage.RESTYLE_LOCAL, prof);
            state.pruneRestyle = false;
        }
        if ((result & RESTYLE_STRUCTURAL) != 0) return null;
        long tB = System.nanoTime();
        if (!state.restyleDescended && !rootBox.hasUnmarkedChanges) LayoutBox.markRootRestyleChanges(rootBox);
        else {
            LayoutBox.markLayoutChanges(rootBox);
            rootBox.hasUnmarkedChanges = false;
        }
        runLayoutPasses(rootBox, rootW, rootH, doc.sheet.mediaEnvironment().resolution(), scrollState, fonts, true, true, false);
        RESIZE_SKIPS.incrementAndGet();
        stats().boxes = stats().restyled = stats().reused = 0;
        stats().lastBuildStats = "boxes=0 restyled=0 reused=0 skipped=resize restyle=" + (tB - tA) / 1000 + "us "
                + "passes=" + (System.nanoTime() - tB) / 1000 + "us";
        return rootBox;
    }

    /**
     * 部分木（HUD トラッカーなど）をレイアウトしてルートの {@link LayoutBox} を返す。
     */
    public static LayoutBox layoutSubtree(HtmlNode root, StyleSheet sheet, int rootW, int rootH,
                                          Map<String, String> env, ScrollState scrollState, FontResolver fonts) {
        LayoutBox rootBox = buildLayoutTree(root, Map.of("display", "inline-block"), sheet, env, false);
        BoxTreeBuilder.build(rootBox, CounterStyles.of(sheet.counterStyles));
        runLayoutPasses(rootBox, rootW, rootH, sheet.mediaEnvironment().resolution(), scrollState, fonts, false, true, false);
        return rootBox;
    }

    private static boolean measureContainers(LayoutBox box, InteractionState interactionState) {
        boolean changed = false;
        if (box.node != null && !box.node.isPseudoNode) {
            InteractionState.StateFlags flags = interactionState.get(box.node);
            ContainerBox container = flags == null ? null : interactionState.containerOf(box.node);
            if (container != null && container.isSizeContainer()) {
                float width = Math.max(0, box.w - box.pL - box.pR - box.bL - box.bR);
                float height = container.type() == ContainerType.INLINE_SIZE
                        ? Float.NaN : Math.max(0, box.h - box.pT - box.pB - box.bT - box.bB);
                changed |= interactionState.setContainerSize(box.node, width, height);
            }
        }
        for (LayoutBox child : box.elementChildren) {
            changed |= measureContainers(child, interactionState);
        }
        return changed;
    }

    public static void runLayoutPasses(LayoutBox rootBox, int rootW, int rootH, ScrollState scrollState, FontResolver fonts) {
        runLayoutPasses(rootBox, rootW, rootH, 1f, scrollState, fonts, false);
    }

    private static void runLayoutPasses(LayoutBox rootBox, int rootW, int rootH, float devicePixelRatio,
                                        ScrollState scrollState, FontResolver fonts, boolean reuseMeasurements) {
        runLayoutPasses(rootBox, rootW, rootH, devicePixelRatio, scrollState, fonts, reuseMeasurements, true, true);
    }

    /**
     * @param clearMarks 配置まで終えたあとに、全ての箱の「変わった」印を外す。全体の印付けのあとは、変わった箱もそうでない箱も
     *                   印が付いたまま残り、次の局所更新（経路だけの印付け）が経路の外の箱を「変わった」と読んで配置を使い回せない
     */
    private static void runLayoutPasses(LayoutBox rootBox, int rootW, int rootH, float devicePixelRatio,
                                        ScrollState scrollState, FontResolver fonts, boolean reuseMeasurements, boolean clearMarks,
                                        boolean linkParents) {
        // 箱の木を作り替えていない再レイアウト（relayoutInPlace）は、親リンクが張られたままなので木全体を辿り直さない
        // （測定と配置が子を迎えるたびに adopt するので、増えた箱も張られる）
        if (linkParents) rootBox.linkParents();
        Viewport viewport = new Viewport(rootW, rootH, devicePixelRatio);
        rootBox.devicePixelRatio = viewport.devicePixelRatio();
        long run = LAYOUT_RUNS.incrementAndGet();
        LayoutContext ctx = null;
        FormattingContext fmtCtx = FormattingContext.BLOCK;

        long m0 = System.nanoTime();
        long measuredBefore = LayoutBox.measurementsComputed();
        for (int pass = 0; pass < LAYOUT_PASSES; pass++) {
            ctx = new LayoutContext(viewport, effectiveIntrinsicResolver(), scrollState, fonts, reuseMeasurements, run, pass == 0);
            rootBox.measure(rootW, rootH, fmtCtx, ctx);
        }
        long m1 = System.nanoTime();
        List<LayoutBox> staleFrags = STALE_FRAG_BOXES.get();
        staleFrags.clear();
        LayoutBox.resetPlacementsFast(rootBox, reuseMeasurements ? run : -1, staleFrags);
        try {
            rootBox.layout(0, 0, rootW, rootH, ctx);
        } finally {
            LayoutBox.finishResetPlacements(staleFrags);
        }
        long m2 = System.nanoTime();
        StageProfiler.record(PipelineStage.LAYOUT_MEASURE, m1 - m0);
        StageProfiler.record(PipelineStage.LAYOUT_PLACE, m2 - m1);
        stats().passMeasureMs = (m1 - m0) / 1_000_000;
        stats().passMeasured = LayoutBox.measurementsComputed() - measuredBefore;
        stats().passLayoutMs = (System.nanoTime() - m1) / 1_000_000;
        if (clearMarks) LayoutBox.clearAllSubtreeMarks(rootBox);
    }

    /**
     * 箱の木を作り直さずに、前回の箱の木のまま測り直して配置し直す。文字の箱の {@link LayoutBox#refreshStyle} 済みの
     * 変化だけを入力にする（{@code UiView} の TEXT_PATCH）。スタイルの解決・匿名箱の整形（{@link BoxTreeBuilder}）を省き、
     * 変わった箱とその祖先（再レイアウト境界まで）だけを測り直す。
     */
    public static void relayoutInPlace(LayoutBox rootBox, int rootW, int rootH, float devicePixelRatio,
                                       ScrollState scrollState, FontResolver fonts) {
        relayoutInPlace(rootBox, rootW, rootH, devicePixelRatio, scrollState, fonts, null);
    }

    /**
     * @param changed 変わったと分かっている箱（文字の箱）。経路だけの印付けで済むなら木全体の走査を省く。null なら木全体
     */
    public static void relayoutInPlace(LayoutBox rootBox, int rootW, int rootH, float devicePixelRatio,
                                       ScrollState scrollState, FontResolver fonts,
                                       java.util.Collection<LayoutBox> changed) {
        relayoutInPlace(rootBox, rootW, rootH, devicePixelRatio, scrollState, fonts, changed, java.util.Set.of());
    }

    /**
     * @param shallow {@code changed} のうち、子の並びだけが変わった箱（子の箱自体は前回のまま）。部分木を全体版で
     *                印付けせず、片付けもしない（{@link LayoutBox#markLayoutChangesAlong}）
     */
    public static void relayoutInPlace(LayoutBox rootBox, int rootW, int rootH, float devicePixelRatio,
                                       ScrollState scrollState, FontResolver fonts,
                                       java.util.Collection<LayoutBox> changed, java.util.Set<LayoutBox> shallow) {
        List<LayoutBox> path = ctx().localRelayout && changed != null && !changed.isEmpty()
                ? LayoutBox.markLayoutChangesAlong(rootBox, changed, shallow) : null;
        stats().lastRelayoutWasLocal = path != null;
        if (path == null) LayoutBox.markLayoutChanges(rootBox);
        rootBox.hasUnmarkedChanges = false;
        rootBox.hasStructuralPending = false;
        runLayoutPasses(rootBox, rootW, rootH, devicePixelRatio, scrollState, fonts, true, path == null, false);
        if (path != null) {
            LayoutBox.clearSubtreeMarks(path);
            // 子を持つ変更箱の部分木は全体版で印を付けたので、全体版と同じ片付けをする
            for (LayoutBox c : changed) if (!c.children.isEmpty() && !shallow.contains(c)) LayoutBox.clearAllSubtreeMarks(c);
        }
    }

    /**
     * 局所パッチ（子の増減）: {@code node} の箱の部分木を、親の箱の下に単独で組む。親・祖先・兄弟には触れない。
     * 箱は要素ツリーの箱（{@link LayoutBox#elementChildren} に入れる単位）で、{@link BoxTreeBuilder} の組み立ては呼び出し側が行う。
     * スタイルは親の算出スタイルを継承元に、全体の組み立て（{@link #layoutDocument}）と同じ規則で解く。
     */
    public static LayoutBox buildDetachedBox(HtmlNode node, LayoutBox parentBox, StyleSheet sheet, Map<String, String> env,
                                             int rootW, int rootH, InteractionState interactionState) {
        ArrayList<LayoutBox> chain = new ArrayList<>();
        for (LayoutBox b = parentBox; b != null; b = b.parent) chain.add(b);
        StyleScope scope = StyleScope.initial(rootW, rootH);
        for (int i = chain.size() - 1; i >= 0; i--) {
            LayoutBox b = chain.get(i);
            if (!b.isAnonymous && b.node != null) scope = scope.enter(b.node, b.style);
        }
        ContainerBox parentContainer = interactionState != null && parentBox != null && parentBox.node != null
                ? interactionState.nearestSizeContainer(parentBox.node)
                : ContainerBox.NONE_SIZE;
        boolean parentMoving = false;
        if (parentBox != null) {
            if (parentBox.movingStamped) {
                parentMoving = parentBox.selfOrAncestorMoving;
            } else {
                for (int i = chain.size() - 1; i >= 0; i--) {
                    if (PaintOrder.hasMovingProps(chain.get(i).style)) {
                        parentMoving = true;
                        break;
                    }
                }
            }
        }
        return buildLayoutTree(node, parentBox.style.asMap(), sheet, env, FORCE_SUBTREE, parentBox.style.fontSizePx(), scope,
                interactionState, StyleResolver.hasSiblingStateRules(sheet), null, null, false, null, parentContainer, parentMoving);
    }

    /**
     * 測った寸法はそのままに、位置だけを決め直す（measure を飛ばして layout だけ走らせる）。
     *
     * <p>スクロール量や配置済みの箱の {@code top} / {@code left} は寸法に響かず、{@link LayoutBox#layout} の中でしか読まれない。
     * スクロールバーやウィンドウのドラッグのように毎フレーム変わる値で、木の作り直しと 2 回の measure を省く。</p>
     */
    public static void repositionLayout(LayoutBox rootBox, int rootW, int rootH, ScrollState scrollState, FontResolver fonts) {
        LayoutContext ctx = new LayoutContext(new Viewport(rootW, rootH, rootBox.devicePixelRatio),
                effectiveIntrinsicResolver(), scrollState, fonts);
        long prof = StageProfiler.begin(PipelineStage.REPOSITION);
        List<LayoutBox> staleFrags = STALE_FRAG_BOXES.get();
        staleFrags.clear();
        LayoutBox.resetPlacementsFast(rootBox, -1, staleFrags);
        try {
            rootBox.layout(0, 0, rootW, rootH, ctx);
        } finally {
            LayoutBox.finishResetPlacements(staleFrags);
        }
        StageProfiler.end(PipelineStage.REPOSITION, prof);
    }

    /**
     * 変わったのが配置済みの箱の inset（{@code top} / {@code left} / {@code right} / {@code bottom}）だけで、
     * 寸法に響かないか。響かなければ {@link #repositionLayout} で足りる。
     *
     * <p>相対配置・sticky の inset は layout で足すずれにしか使わない。絶対配置の inset は、両側とも指定された軸だけが
     * 寸法を決める（CSS 2.2 §10.3.7 / §10.6.4）ので、その軸が動いたときは measure からやり直す。</p>
     */
    static boolean isPositionOnlyChange(ComputedStyle oldStyle, ComputedStyle newStyle) {
        if (oldStyle == null || newStyle == null) return false;
        if (Float.compare(oldStyle.fontSizePx(), newStyle.fontSizePx()) != 0
                || Float.compare(oldStyle.rootFontSizePx(), newStyle.rootFontSizePx()) != 0) {
            return false;
        }
        String position = newStyle.get(CssPropertyIndex.POSITION);
        if (!Objects.equals(position, oldStyle.get(CssPropertyIndex.POSITION))) return false;
        boolean offsetOnly = "relative".equals(position) || "sticky".equals(position);
        boolean outOfFlow = "absolute".equals(position) || "fixed".equals(position);
        if (!offsetOnly && !outOfFlow) return false;

        int axes = oldStyle.classifyPositionOnlyDiff(newStyle);
        if (axes < 0) return false;
        boolean horizontal = (axes & 1) != 0;
        boolean vertical = (axes & 2) != 0;
        if (offsetOnly) return true;
        if (horizontal && (bothInsets(oldStyle, CssPropertyIndex.LEFT, CssPropertyIndex.RIGHT)
                || bothInsets(newStyle, CssPropertyIndex.LEFT, CssPropertyIndex.RIGHT))) {
            return false;
        }
        return !vertical || !(bothInsets(oldStyle, CssPropertyIndex.TOP, CssPropertyIndex.BOTTOM)
                || bothInsets(newStyle, CssPropertyIndex.TOP, CssPropertyIndex.BOTTOM));
    }

    private static boolean bothInsets(ComputedStyle style, int start, int end) {
        return !isAutoInset(style.get(start)) && !isAutoInset(style.get(end));
    }

    private static boolean isAutoInset(String value) {
        return value == null || value.isBlank() || "auto".equalsIgnoreCase(value.trim());
    }

    private static volatile Object[] rootStyleCache; // {w, h, 読み取り専用の写し}

    /** 再スタイルの走査用: 同じビューポートの間は同じ読み取り専用の写しを使い回す（毎回の HashMap を避ける）。 */
    private static Map<String, String> cachedRootViewportStyle(int rootW, int rootH) {
        Object[] c = rootStyleCache;
        if (c != null && (int) c[0] == rootW && (int) c[1] == rootH) {
            @SuppressWarnings("unchecked") Map<String, String> m = (Map<String, String>) c[2];
            return m;
        }
        Map<String, String> m = java.util.Collections.unmodifiableMap(rootViewportStyle(rootW, rootH));
        rootStyleCache = new Object[] {rootW, rootH, m};
        return m;
    }

    public static Map<String, String> rootViewportStyle(int rootW, int rootH) {
        Map<String, String> rootStyleRaw = new HashMap<>();
        rootStyleRaw.put("width", String.valueOf(rootW));
        rootStyleRaw.put("min-height", String.valueOf(rootH));
        rootStyleRaw.put("box-sizing", "border-box");
        return rootStyleRaw;
    }

    /**
     * 再スタイルの結果。{@code layoutRequired} は寸法から測り直す（measure + layout）、
     * {@code repositionRequired} は寸法は変わらず位置だけ決め直せば足りる（layout のみ）ことを表す。
     */
    private static final int RESTYLE_UNCHANGED = 0;
    private static final int RESTYLE_STYLE_CHANGED = 1;
    private static final int RESTYLE_LAYOUT_REQUIRED = 2;
    private static final int RESTYLE_REPOSITION_REQUIRED = 4;
    private static final int RESTYLE_STRUCTURAL = 8;

    private static RestyleResult toRestyleResult(int mask) {
        if (mask == RESTYLE_UNCHANGED) return RestyleResult.UNCHANGED;
        return new RestyleResult(
                (mask & RESTYLE_STYLE_CHANGED) != 0,
                (mask & RESTYLE_LAYOUT_REQUIRED) != 0,
                (mask & RESTYLE_REPOSITION_REQUIRED) != 0,
                (mask & RESTYLE_STRUCTURAL) != 0);
    }

    public record RestyleResult(boolean styleChanged, boolean layoutRequired, boolean repositionRequired,
                                boolean structural) {
        /** 何も変わらなかった結果（不変なので共有する）。 */
        public static final RestyleResult UNCHANGED = new RestyleResult(false, false, false, false);

        public RestyleResult(boolean styleChanged, boolean layoutRequired) {
            this(styleChanged, layoutRequired, false, layoutRequired);
        }

        public RestyleResult(boolean styleChanged, boolean layoutRequired, boolean repositionRequired) {
            this(styleChanged, layoutRequired, repositionRequired, layoutRequired);
        }
    }

    /**
     * 直近の {@link #restyleLayoutTree} で、寸法の入力だけが変わった箱（箱の木の形は変わらない）。
     * {@link #relayoutInPlace} に渡して、印付けを経路だけにする。UI スレッド専用（他の統計と同じ前提）。
     */
    private static final ThreadLocal<List<LayoutBox>> LAYOUT_CHANGED_BOXES = ThreadLocal.withInitial(ArrayList::new);

    /** 直近の {@link #restyleLayoutTree} で、スタイル（描画のみ）が変わった箱。UI スレッド専用。 */
    private static final ThreadLocal<List<LayoutBox>> STYLE_CHANGED_BOXES = ThreadLocal.withInitial(ArrayList::new);

    /** resetPlacementsFast でフラグメント遅延破棄待ちになったボックスの一時リスト。 */
    private static final ThreadLocal<List<LayoutBox>> STALE_FRAG_BOXES = ThreadLocal.withInitial(ArrayList::new);

    /** このスレッドの印付け用の一時リスト（複数のビューが別スレッドで再レイアウトしても混ざらない）。 */
    static List<LayoutBox> layoutChangedBoxes() {
        return LAYOUT_CHANGED_BOXES.get();
    }

    /** 直近の再スタイルで描画スタイルのみが変化したボックス群。DisplayList スライスパッチ用。 */
    public static List<LayoutBox> styleChangedBoxes() {
        return STYLE_CHANGED_BOXES.get();
    }

    private static boolean isGeometryOnlyChange(ComputedStyle oldStyle, ComputedStyle newStyle) {
        return oldStyle != null && newStyle != null && !oldStyle.hasStructureAffectingDiff(newStyle);
    }

    private record StyleScope(int viewportW, int viewportH, float rootFontSize, float rootLineHeight) {
        // 不変なので、直前と同じ値なら同じインスタンスを返す（再スタイルのたびに作らない）
        private static volatile StyleScope lastInitial, lastEntered;

        static StyleScope initial(int viewportW, int viewportH) {
            StyleScope c = lastInitial;
            if (c != null && c.viewportW == viewportW && c.viewportH == viewportH) return c;
            return lastInitial = new StyleScope(viewportW, viewportH, ComputedStyle.DEFAULT_FONT_SIZE, Float.NaN);
        }

        StyleScope enter(HtmlNode node, ComputedStyle computed) {
            if (!"html".equalsIgnoreCase(node.tag)) return this;
            StyleScope c = lastEntered;
            float fs = computed.fontSizePx(), lh = computed.lineHeightPx();
            if (c != null && c.viewportW == viewportW && c.viewportH == viewportH
                    && Float.floatToIntBits(c.rootFontSize) == Float.floatToIntBits(fs)
                    && Float.floatToIntBits(c.rootLineHeight) == Float.floatToIntBits(lh)) return c;
            return lastEntered = new StyleScope(viewportW, viewportH, fs, lh);
        }
    }

    /**
     * 算出スタイルのキャッシュの状態。
     * <ul>
     *   <li>{@link #VALID}: そのまま使える</li>
     *   <li>{@link #VIEWPORT_STALE}: ビューポートだけが変わり、宣言値がビューポートに依存する。算出し直すが、
     *       結果が前と同じなら子孫へ再スタイルを強いない（React の bailout と同じ考え方）</li>
     *   <li>{@link #INVALID}: 状態・DOM・継承元が変わった。子孫もまとめて算出し直す</li>
     * </ul>
     */
    private enum StyleCache { VALID, VIEWPORT_STALE, INVALID }

    /** 宣言値がビューポートの大きさで変わり得るか（{@code vw} / {@code svh} / {@code cqw} など、または参照先の分からない {@code var()}）。 */
    private static final Pattern VIEWPORT_DEPENDENT_VALUE = Pattern.compile(
            "\\d(?:[sld]?v(?:w|h|i|b|min|max)|cq(?:w|h|i|b|min|max))(?![a-z])|var\\(|env\\(",
            Pattern.CASE_INSENSITIVE);

    private static StyleCache styleCacheState(InteractionState.StateFlags flags, float parentFontSize, StyleScope scope) {
        if (flags == null
                || flags.isStyleDirty()
                || flags.computedStyle == null
                || Float.compare(flags.cachedParentFontSize, parentFontSize) != 0
                || Float.compare(flags.cachedRootFontSize, scope.rootFontSize()) != 0
                || Float.compare(flags.cachedRootLineHeight, scope.rootLineHeight()) != 0) {
            return StyleCache.INVALID;
        }
        boolean sameViewport = flags.cachedViewportW == scope.viewportW() && flags.cachedViewportH == scope.viewportH();
        return sameViewport || !flags.viewportDependent ? StyleCache.VALID : StyleCache.VIEWPORT_STALE;
    }

    /** 親から子へ渡す「算出し直せ」の強さ。 */
    private static final int FORCE_NONE = 0;
    /** この要素だけ算出し直す（継承元が変わった・兄弟に状態の変化がある）。子孫への強制は、算出結果から改めて決める。 */
    private static final int FORCE_SELF = 1;
    /** この要素と全ての子孫を算出し直す（祖先の状態・class・属性を読む規則が子孫に効き得る）。 */
    private static final int FORCE_SUBTREE = 2;

    /** 算出し直した結果が、子孫の算出に及ぶ度合い。 */
    private enum Propagation {
        /** 子孫が読む値は変わっていない。子孫はそれぞれのキャッシュの状態で判断する。 */
        NONE,
        /** 継承しないプロパティだけが変わった。{@code inherit} を明示した子孫だけが影響を受ける。 */
        NON_INHERITED,
        /** 継承する値（カスタムプロパティ・フォントサイズを含む）が変わった。子孫は全て算出し直す。 */
        INHERITED
    }

    /**
     * 算出し直した要素の子孫へ、どこまで再スタイルを及ぼすか。
     * <ul>
     *   <li>要素自身が子孫の規則の入力になる（{@code .a:hover .b}）なら {@code subtreeDirty}（無効化セットが立てる）で全部</li>
     *   <li>継承する値が変わったなら全部。子孫の継承元が変わる</li>
     *   <li>継承しない値だけが変わったなら、{@code inherit} を使う規則・style 属性が無い限り子孫は影響を受けない
     *       （背景色の変化で子孫全員を再スタイルしない）</li>
     * </ul>
     */
    private static Propagation propagationOf(ComputedStyle previous, ComputedStyle computed) {
        return switch (computed.classifyPropagationFrom(previous)) {
            case 0 -> Propagation.NONE;
            case 1 -> Propagation.NON_INHERITED;
            default -> Propagation.INHERITED;
        };
    }

    private static int childForceOf(boolean subtreeDirty, Propagation propagation, StyleSheet sheet) {
        if (subtreeDirty) return FORCE_SUBTREE;
        if (propagation == Propagation.INHERITED
                || (propagation == Propagation.NON_INHERITED && sheet != null && sheet.usesInheritKeyword())) {
            return FORCE_SELF;
        }
        return FORCE_NONE;
    }

    /** 値がビューポート・コンテナ基準の単位、{@code var()}、{@code env()} を含むか（判定の本体は {@link StyleResolver}）。 */
    static boolean dependsOnViewportValue(String v) {
        return StyleResolver.valueDependsOnViewport(v);
    }

    /**
     * 要素の生宣言と算出スタイル。自身だけの再スタイルで、規則が対象の状態だけを読むなら、状態 → 算出スタイルの表
     * （{@link StateStyleMemo}）を引く（:hover の往復でセレクタ照合もカスケードもしない）。
     * 表を使えない／入力が変わったときは、従来どおり（スタイル共有 → カスケード）で作る。
     */
    private static StateStyleMemo.Entry styleEntry(HtmlNode node, StyleSet styleSet, Map<String, String> inheritedStyle,
                                                   StyleSheet sheet, Map<String, String> env, float parentFontSize,
                                                   StyleScope scope, InteractionState interactionState,
                                                   ContainerBox container, int force,
                                                   InteractionState.StateFlags flags) {
        boolean tableOk = flags != null && container == ContainerBox.NONE_SIZE
                // FORCE_SELF は「親の算出スタイルが変わった」だけ（兄弟・祖先の変化は FORCE_SUBTREE）。親の値は表の鍵に入っている
                && (force == FORCE_NONE || force == FORCE_SELF) && sheet != null
                && !sheet.usesHasSelectors(); // :has() は子孫・後続の兄弟の変化で祖先が汚れる（自身の状態だけでは決まらない）
        if (!tableOk) {
            if (flags != null && flags.stateMemo != null) flags.stateMemo.invalidate();
            return computeEntry(node, styleSet, inheritedStyle, sheet, env, parentFontSize, scope, interactionState,
                    container, interactionState);
        }
        if (flags.stateMemo == null) flags.stateMemo = new StateStyleMemo();
        StateStyleMemo.Entry cached = flags.stateMemo.peek(node, sheet, inheritedStyle, env, parentFontSize,
                scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(),
                interactionState.stateBitsOf(node));
        if (cached != null) return cached;
        return flags.stateMemo.resolve(node, sheet, inheritedStyle, env, parentFontSize, scope.rootFontSize(),
                scope.rootLineHeight(), scope.viewportW(), scope.viewportH(),
                interactionState.stateBitsOf(node), true,
                bits -> computeEntry(node, styleSet, inheritedStyle, sheet, env, parentFontSize, scope,
                        interactionState, container,
                        (StateLookup) n -> n == node ? ElementState.of(bits) : interactionState.stateOf(n)));
    }

    private static StateStyleMemo.Entry computeEntry(HtmlNode node, StyleSet styleSet,
                                                     Map<String, String> inheritedStyle, StyleSheet sheet,
                                                     Map<String, String> env, float parentFontSize, StyleScope scope,
                                                     InteractionState interactionState, ContainerBox container,
                                                     StateLookup lookup) {
        ContainerBox targetContainer = container != null ? container : containerFor(node, interactionState);
        StyleResolver.Shared shared = interactionState.styleShare.resolve(node, inheritedStyle, sheet, lookup, env,
                parentFontSize, scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(),
                targetContainer);
        if (shared != null) return new StateStyleMemo.Entry(shared.computed(), shared.viewportDependent());
        if (styleSet == null) styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
        Map<String, String> raw = styleSet.resolve(lookup);
        if (node.attrs.containsKey("hidden")) {
            raw.put(CssProperty.DISPLAY, "none");
        }
        boolean viewportDependent = node.parent == null || StyleResolver.rawDependsOnViewport(raw);
        ComputedStyle computed = computeStyleOwned(raw, inheritedStyle, sheet, env, parentFontSize, scope, targetContainer);
        return new StateStyleMemo.Entry(computed, viewportDependent);
    }

    /**
     * 前回求めた {@code hasInteractiveRules} を、そのまま使ってよいか。入力（シート・規則の版・この節の属性・親）が前回と同じで、
     * この節自身が汚れておらず、祖先・兄弟の変化による強制（{@link #FORCE_SUBTREE}）も無いこと。
     * 親の算出スタイルが変わっただけ（継承の変化。{@link #FORCE_SELF}）なら、対話状態の規則の当たり方は変わらない。
     */
    private static boolean canReuseInteractive(InteractionState.StateFlags flags, HtmlNode node, StyleSheet sheet, int force) {
        return flags != null && sheet != null && force != FORCE_SUBTREE
                && flags.interactiveSheet == sheet
                && flags.interactiveVersion == sheet.rules.version()
                && flags.interactiveAttrVersion == node.attrVersion()
                && flags.interactiveParent == node.parent
                && (flags.dirtyBits() & (InteractionState.StateFlags.DIRTY_STYLE | InteractionState.StateFlags.DIRTY_UNSCOPED)) == 0;
    }

    private static void cacheStyle(InteractionState.StateFlags flags, HtmlNode node, boolean viewportDependent,
                                   ComputedStyle computed, float parentFontSize, StyleScope scope,
                                   boolean hasInteractive, StyleSheet sheet) {
        // カスケード解決後の生宣言（rawStyle）は、節に持たせない（要素ごとに 1 表・約 1 KB が残っていた）
        flags.computedStyle = computed;
        flags.cachedParentFontSize = parentFontSize;
        flags.cachedRootFontSize = scope.rootFontSize();
        flags.cachedRootLineHeight = scope.rootLineHeight();
        flags.cachedViewportW = scope.viewportW();
        flags.cachedViewportH = scope.viewportH();
        flags.noteViewportDependence(node.parent == null, node.parent == null || viewportDependent);
        flags.hasInteractiveRules = hasInteractive;
        flags.interactiveSheet = sheet;
        flags.interactiveVersion = sheet != null ? sheet.rules.version() : -1;
        flags.interactiveAttrVersion = node.attrVersion();
        flags.interactiveParent = node.parent;
        flags.clearStyleDirtyFlag();
        flags.clearSubtreeDirty();
        flags.clearDirtyBelow();
        flags.clearUnscoped();
        int attrVer = node.attrVersion();
        if (flags.inheritsAttrVersion != attrVer) {
            String inlineStyle = node.attrs.get("style");
            flags.inheritsExplicitly = InteractionState.containsInheritIgnoreCase(inlineStyle);
            flags.inheritsAttrVersion = attrVer;
        }
    }

    private static ComputedStyle computeStyle(Map<String, String> rawStyle, Map<String, String> parentStyle,
                                              StyleSheet sheet,
                                              Map<String, String> env, float parentFontSize, StyleScope scope,
                                              ContainerBox container) {
        return StyleResolver.finalizeComputedStyle(rawStyle, sheet, env, parentStyle,
                parentFontSize, scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(), container);
    }

    private static ComputedStyle computeStyleOwned(Map<String, String> rawStyle, Map<String, String> parentStyle,
                                                   StyleSheet sheet,
                                                   Map<String, String> env, float parentFontSize, StyleScope scope,
                                                   ContainerBox container) {
        return StyleResolver.finalizeComputedStyleOwned(rawStyle, sheet, env, parentStyle,
                parentFontSize, scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(), container);
    }

    private static ComputedStyle recomputeWithAnimation(HtmlNode node, StyleSet styleSet,
                                                      Map<String, String> inheritedStyle, StyleSheet sheet,
                                                      Map<String, String> env, float parentFontSize, StyleScope scope,
                                                      InteractionState interactionState, ContainerBox container,
                                                      Map<String, String> animation) {
        StateLookup lookup = interactionState != null ? interactionState : SelectorMatcher.STATIC_ATTRIBUTES;
        Map<String, String> raw = styleSet != null
                ? styleSet.resolve(lookup)
                : StyleResolver.resolveDeclarations(node, inheritedStyle, sheet, lookup);
        if (node.attrs.containsKey("hidden")) raw.put(CssProperty.DISPLAY, "none");
        raw.putAll(animation);
        return computeStyleOwned(raw, inheritedStyle, sheet, env, parentFontSize, scope, container);
    }

    /**
     * トランジションと {@code @keyframes} の値（{@link StyleAnimator}）。動いているものが無ければ空。
     * {@code opacity} / {@code transform} / 色は描画項目の補間が担うので対象外。
     */
    private static Map<String, String> animationValues(HtmlNode node, ComputedStyle before, ComputedStyle target,
                                                       Map<String, String> inheritedStyle, StyleSheet sheet,
                                                       InteractionState interactionState) {
        if (interactionState == null || node.isPseudoNode || sheet == null) return Map.of();
        long now = interactionState.animationTimeMs();
        StyleAnimator animator = interactionState.animator();
        animator.observe(node, before, target, inheritedStyle, sheet.keyframes, now);
        return animator.sample(node, now);
    }

    private static ContainerBox containerFor(HtmlNode node, InteractionState interactionState) {
        return interactionState != null && node != null && node.parent != null
                ? interactionState.nearestSizeContainer(node.parent)
                : ContainerBox.NONE_SIZE;
    }

    private static boolean isSizeContainerStyle(ComputedStyle style) {
        if (style == null) return false;
        String kw = style.keyword(CssPropertyIndex.CONTAINER_TYPE, "normal");
        return "size".equals(kw) || "inline-size".equals(kw);
    }

    /** 要素自身がサイズコンテナならその {@link ContainerBox}、そうでなければ親から引き継いだ {@code parentContainer} をそのまま返す。 */
    private static ContainerBox containerForSelf(HtmlNode node, ComputedStyle computed, ContainerBox parentContainer,
                                                 InteractionState interactionState) {
        if (interactionState == null || !isSizeContainerStyle(computed)) return parentContainer;
        ContainerBox self = interactionState.containerOf(node);
        return self != null && self.isSizeContainer() ? self : parentContainer;
    }

    public static RestyleResult restyleLayoutTree(LayoutBox rootBox, Document doc, int rootW, int rootH,
                                                  Map<String, String> env, InteractionState interactionState) {
        if (rootBox == null || doc == null || interactionState == null) {
            return new RestyleResult(true, true);
        }
        Map<String, String> rootStyleRaw = cachedRootViewportStyle(rootW, rootH);
        interactionState.bindStyleSheet(doc.sheet);
        if (doc.sheet != null && doc.sheet.usesHasSelectors()) interactionState.propagateDirtyToAncestors();
        else interactionState.discardUnscopedLog();
        // 飛ばしてよいのは、前回の走査と同じビューポートのとき（変わると、飛ばした子孫の vw や var() が古くなる）
        InteractionState.StateFlags rootFlags = interactionState.get(rootBox.node);
        interactionState.pruneRestyle = ctx().pruneCleanSubtrees && rootFlags != null
                && rootFlags.cachedViewportW == rootW && rootFlags.cachedViewportH == rootH;
        // 共有の結果は再スタイルをまたいで残す（鍵は親の算出表の同一性・掛かった規則・数値の入力。シートの版・環境が変われば ShareCache が捨てる）。
        // 親が変わらない要素の「前に解いた規則の組」へ戻る往復（class の付け外しなど）が、解き直しなしで済む
        List<LayoutBox> layoutChangedList = RenderPipeline.layoutChangedBoxes();
        List<LayoutBox> styleChangedList = RenderPipeline.styleChangedBoxes();
        layoutChangedList.clear();
        styleChangedList.clear();
        long prof = StageProfiler.begin(PipelineStage.RESTYLE_LOCAL);
        try {
            ContainerBox rootContainer = containerFor(rootBox.node, interactionState);
            int resMask = restyleBoxRecursive(rootBox, rootStyleRaw, doc.sheet, env, FORCE_NONE,
                    ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(rootW, rootH),
                    interactionState, StyleResolver.hasSiblingStateRules(doc.sheet), rootContainer, false,
                    layoutChangedList, styleChangedList);
            if ((resMask & RESTYLE_LAYOUT_REQUIRED) != 0) rootBox.hasUnmarkedChanges = true;
            if ((resMask & RESTYLE_STRUCTURAL) != 0) rootBox.hasStructuralPending = true;
            return toRestyleResult(resMask);
        } finally {
            StageProfiler.end(PipelineStage.RESTYLE_LOCAL, prof);
        }
    }

    private static int restyleBoxRecursive(LayoutBox box, Map<String, String> inheritedStyle,
                                                     StyleSheet sheet,
                                                     Map<String, String> env, int force,
                                                     float parentFontSize, StyleScope scope,
                                                     InteractionState interactionState, boolean siblingInvalidation,
                                                     ContainerBox parentSizeContainer, boolean ancestorHasMoving,
                                                     List<LayoutBox> layoutChangedList, List<LayoutBox> styleChangedList) {
        HtmlNode node = box.node;
        InteractionState.StateFlags flags = interactionState.getOrCreate(node);
        StyleCache cache = force != FORCE_NONE ? StyleCache.INVALID : styleCacheState(flags, parentFontSize, scope);
        boolean isDirty = cache != StyleCache.VALID;

        boolean styleChanged = false;
        boolean layoutRequired = false;
        boolean repositionRequired = false;
        boolean structural = false;
        int childForce = FORCE_NONE;
        boolean childForceIfInherits = false;
        ComputedStyle computed;
        // 要素自身が子孫の規則の入力になる変化（祖先の状態・class を読む規則）。算出値が変わらなくても子孫を算出し直す。
        // 祖先から受けた FORCE_SUBTREE も、さらに下の子孫まで続ける
        boolean subtreeDirty = flags.subtreeDirty() || force == FORCE_SUBTREE;
        ContainerBox selfContainer;
        boolean selfOrAncestorMoving;

        if (isDirty) {
            ComputedStyle prevStyle = box.style;
            ContainerBox container = parentSizeContainer;
            // StyleSet は表を引けなかったとき（computeEntry）だけ作る
            StateStyleMemo.Entry entry = styleEntry(node, null, inheritedStyle, sheet, env, parentFontSize, scope,
                    interactionState, container, force, flags);
            boolean viewportDependent = entry.viewportDependent();
            computed = entry.computed();
            Map<String, String> animation = animationValues(node, prevStyle, computed, inheritedStyle, sheet, interactionState);
            if (!animation.isEmpty()) {
                computed = recomputeWithAnimation(node, null, inheritedStyle, sheet, env, parentFontSize, scope,
                        interactionState, container, animation);
                viewportDependent |= StyleResolver.rawDependsOnViewport(animation);
            }
            boolean hasInteractive = canReuseInteractive(flags, node, sheet, force)
                    ? flags.hasInteractiveRules : StyleResolver.hasInteractiveRules(node, sheet);

            if (prevStyle == null || !ComputedStyle.sameDeclarations(prevStyle, computed)) {
                styleChanged = true;
                if (StyleResolver.isLayoutAffectingChange(prevStyle, computed)) {
                    if (isPositionOnlyChange(prevStyle, computed)) {
                        repositionRequired = true;
                    } else {
                        layoutRequired = true;
                        if (box.touchedByTableEngine() || !isGeometryOnlyChange(prevStyle, computed)) structural = true;
                        else layoutChangedList.add(box);
                    }
                } else {
                    styleChangedList.add(box);
                }
            }

            if ("details".equalsIgnoreCase(node.tag)) {
                boolean isOpen = interactionState.stateOf(node).open();
                boolean wasOpen = box.elementChildren.size() > 1;
                if (isOpen != wasOpen) {
                    styleChanged = true;
                    layoutRequired = true;
                    structural = true;
                }
            }

            Propagation propagation = propagationOf(prevStyle, computed);
            childForce = childForceOf(subtreeDirty, propagation, sheet);
            childForceIfInherits = propagation == Propagation.NON_INHERITED;
            box.updateStyle(computed);
            cacheStyle(flags, node, viewportDependent, computed, parentFontSize, scope, hasInteractive, sheet);
            selfContainer = containerForSelf(node, computed, parentSizeContainer, interactionState);
            selfOrAncestorMoving = stampMoving(box, computed, ancestorHasMoving);
            ComputedStyle prevMarkerStyle = box.markerStyle;
            ComputedStyle prevPlaceholderStyle = box.placeholderStyle;
            ComputedStyle prevBackdropStyle = box.backdropStyle;
            resolveHighlightPseudos(box, computed, sheet, env, scope.enter(node, computed), interactionState, selfContainer);
            if (box.display.listItem() && prevMarkerStyle != box.markerStyle) {
                styleChanged = true;
                if (StyleResolver.isLayoutAffectingChange(prevMarkerStyle, box.markerStyle)) {
                    layoutRequired = true;
                    if (!isGeometryOnlyChange(prevMarkerStyle, box.markerStyle)) structural = true;
                    else layoutChangedList.add(box);
                }
            }
            if (prevPlaceholderStyle != box.placeholderStyle
                    && StyleResolver.isLayoutAffectingChange(prevPlaceholderStyle, box.placeholderStyle)) {
                styleChanged = true;
                layoutRequired = true;
                layoutChangedList.add(box);
            }
            if (prevBackdropStyle != box.backdropStyle) styleChanged = true;
            if (styleChanged || prevMarkerStyle != box.markerStyle) {
                BoxTreeBuilder.restyleGeneratedBoxes(box);
            }
            box.hasInteractiveRules = hasInteractive;
        } else {
            computed = flags.computedStyle;
            selfContainer = containerForSelf(node, computed, parentSizeContainer, interactionState);
            selfOrAncestorMoving = box.movingStamped && box.ancestorMoving == ancestorHasMoving
                    ? box.selfOrAncestorMoving
                    : stampMoving(box, computed, ancestorHasMoving);
            childForce = subtreeDirty ? FORCE_SUBTREE : FORCE_NONE;
            flags.clearSubtreeDirty();
        }
        flags.clearDirtyBelow(); // 子孫は、このあと必要なものだけ走査して片付ける

        float nextParentFontSize = computed.fontSizePx();
        StyleScope childScope = scope.enter(node, computed);

        int acc = (styleChanged ? RESTYLE_STYLE_CHANGED : 0)
                | (layoutRequired ? RESTYLE_LAYOUT_REQUIRED : 0)
                | (repositionRequired ? RESTYLE_REPOSITION_REQUIRED : 0)
                | (structural ? RESTYLE_STRUCTURAL : 0);

        if (isDirty && !computed.skipsContents()) {
            for (int pi = 0; pi < GENERATED_PSEUDOS.length; pi++) {
                PseudoType type = GENERATED_PSEUDOS[pi];
                acc |= restylePseudoElement(box, type, computed, sheet, env, childScope,
                        interactionState, siblingInvalidation, selfContainer, selfOrAncestorMoving,
                        layoutChangedList, styleChangedList);
            }
        }

        boolean siblingForceDirty = false;
        List<LayoutBox> kids = box.elementChildren;
        for (int ci = 0; ci < kids.size(); ci++) {
            LayoutBox child = kids.get(ci);
            if (child.node.isPseudoNode) continue;
            boolean stateChanged = siblingInvalidation && isStateDirty(interactionState, child.node);
            int forceChild = siblingForceDirty ? FORCE_SUBTREE : childForce;
            if (forceChild == FORCE_NONE && childForceIfInherits) {
                InteractionState.StateFlags childFlags = interactionState.get(child.node);
                if (childFlags != null && childFlags.inheritsExplicitly) forceChild = FORCE_SELF;
            }
            if (forceChild == FORCE_NONE && interactionState.pruneRestyle
                    && canSkipRestyle(interactionState.get(child.node), nextParentFontSize, childScope)
                    && child.movingStamped && child.ancestorMoving == selfOrAncestorMoving) {
                continue; // 自分も子孫も汚れていない部分木は、算出し直すものが無い（stateChanged は汚れていなければ false）
            }
            interactionState.restyleDescended = true;
            acc |= restyleBoxRecursive(child, computed.asMap(), sheet, env,
                    forceChild, nextParentFontSize, childScope, interactionState,
                    siblingInvalidation, selfContainer, selfOrAncestorMoving, layoutChangedList, styleChangedList);
            siblingForceDirty |= stateChanged;
        }

        return acc;
    }

    private static final PseudoType[] GENERATED_PSEUDOS = PseudoType.GENERATED.toArray(new PseudoType[0]);

    /**
     * この箱の部分木を丸ごと飛ばしてよいか: 算出スタイルのキャッシュが有効で、自分にも子孫にも汚れが無い。
     * ビューポートが変わったとき（子孫の {@code vw} などが古くなる）は走査側が {@code pruneRestyle} を切る。
     */
    private static boolean canSkipRestyle(InteractionState.StateFlags flags, float parentFontSize, StyleScope scope) {
        return flags != null && !flags.isStyleDirty() && !flags.subtreeDirty() && !flags.dirtyBelow()
                && styleCacheState(flags, parentFontSize, scope) == StyleCache.VALID;
    }

    private static boolean isStateDirty(InteractionState interactionState, HtmlNode node) {
        if (interactionState == null) return false;
        InteractionState.StateFlags flags = interactionState.get(node);
        return flags == null || flags.isStyleDirty();
    }

    private static int restylePseudoElement(LayoutBox owner, PseudoType type, ComputedStyle ownerStyle,
                                            StyleSheet sheet,
                                            Map<String, String> env, StyleScope scope,
                                            InteractionState interactionState, boolean siblingInvalidation,
                                            ContainerBox ownerSizeContainer, boolean ownerSelfOrAncestorMoving,
                                            List<LayoutBox> layoutChangedList, List<LayoutBox> styleChangedList) {
        LayoutBox existing = null;
        if (owner.hasPseudoElementChild) {
            String ownerKey = owner.node.key();
            String suffix = type == PseudoType.BEFORE ? "_before" : "_after";
            for (int ci = 0, cn = owner.elementChildren.size(); ci < cn; ci++) {
                LayoutBox child = owner.elementChildren.get(ci);
                if (child.node.isPseudoNode && isPseudoKey(child.node.key(), ownerKey, suffix)) {
                    existing = child;
                    break;
                }
            }
        }
        ComputedStyle next = resolvePseudoStyle(owner.node, type, ownerStyle, sheet, env, scope, interactionState, ownerSizeContainer);
        if (existing == null && next == null) return RESTYLE_UNCHANGED;

        if (existing == null || next == null) {
            if (existing != null) {
                owner.elementChildren.remove(existing);
            } else {
                LayoutBox created = buildPseudoBox(owner.node, type, next, owner.hasInteractiveRules, sheet, env,
                        scope, interactionState, siblingInvalidation, ownerSizeContainer, ownerSelfOrAncestorMoving);
                owner.addElementChild(created);
                if (type == PseudoType.BEFORE) {
                    owner.elementChildren.remove(created);
                    owner.elementChildren.add(0, created);
                }
            }
            return RESTYLE_STYLE_CHANGED | RESTYLE_LAYOUT_REQUIRED | RESTYLE_STRUCTURAL;
        }

        boolean styleChanged = !ComputedStyle.sameDeclarations(existing.style, next);
        boolean layoutRequired = styleChanged && StyleResolver.isLayoutAffectingChange(existing.style, next);
        boolean repositionRequired = false;
        boolean structural = false;
        if (layoutRequired) {
            if (isPositionOnlyChange(existing.style, next)) {
                layoutRequired = false;
                repositionRequired = true;
            } else if (existing.touchedByTableEngine() || !isGeometryOnlyChange(existing.style, next)) {
                structural = true;
            } else {
                layoutChangedList.add(existing);
            }
        } else if (styleChanged) {
            styleChangedList.add(existing);
        }
        existing.updateStyle(next);
        boolean pseudoMoving = stampMoving(existing, next, ownerSelfOrAncestorMoving);
        existing.hasInteractiveRules = owner.hasInteractiveRules;
        if (styleChanged) BoxTreeBuilder.restyleGeneratedBoxes(existing);
        int acc = (styleChanged ? RESTYLE_STYLE_CHANGED : 0)
                | (layoutRequired ? RESTYLE_LAYOUT_REQUIRED : 0)
                | (repositionRequired ? RESTYLE_REPOSITION_REQUIRED : 0)
                | (structural ? RESTYLE_STRUCTURAL : 0);
        for (int ti = 0; ti < existing.elementChildren.size(); ti++) {
            LayoutBox text = existing.elementChildren.get(ti);
            acc |= restyleBoxRecursive(text, next.asMap(), sheet, env, FORCE_SUBTREE, next.fontSizePx(),
                    scope, interactionState, siblingInvalidation, ownerSizeContainer, pseudoMoving,
                    layoutChangedList, styleChangedList);
        }
        return acc;
    }

    public static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                            StyleSheet sheet,
                                            Map<String, String> env, boolean forceDirty) {
        return buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty, new InteractionState());
    }

    public static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                            StyleSheet sheet,
                                            Map<String, String> env, boolean forceDirty,
                                            InteractionState interactionState) {
        InteractionState effectiveState = interactionState != null ? interactionState : new InteractionState();
        return buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty,
                ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(0, 0), effectiveState,
                StyleResolver.hasSiblingStateRules(sheet));
    }

    public static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                            StyleSheet sheet,
                                            Map<String, String> env, boolean forceDirty,
                                            float parentFontSize, StyleScope scope, InteractionState interactionState,
                                            boolean siblingInvalidation) {
        if (interactionState != null) interactionState.styleShare.clear();
        try {
            ContainerBox rootContainer = containerFor(node, interactionState);
            return buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty ? FORCE_SUBTREE : FORCE_NONE,
                    parentFontSize, scope, interactionState, siblingInvalidation, null, null, false, null, rootContainer, false);
        } finally {
            if (interactionState != null) interactionState.styleShare.clear();
        }
    }

    /**
     * 箱の木を組む。{@code prevReusable} があれば、同じ DOM の節の前回の箱を作り直さずに使い回す（React の差分適用と同じく、
     * 変わらない部分の実体を保つ）。同時に {@code nextReusable} があれば次フレーム用の再利用テーブルを構築中に登録し、
     * 木組前の全木走査（collectReusableBoxes）を不要にする。表の中は使い回さない（{@link LayoutBox#recordReusableForBuild} を参照）。
     */
    private static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                             StyleSheet sheet,
                                             Map<String, String> env, int force,
                                             float parentFontSize, StyleScope scope, InteractionState interactionState,
                                             boolean siblingInvalidation,
                                             LayoutBox.ReusableTable prevReusable,
                                             LayoutBox.ReusableTable nextReusable,
                                             boolean inTableScope,
                                             LayoutBox directPrev,
                                             ContainerBox parentSizeContainer, boolean ancestorHasMoving) {
        InteractionState.StateFlags flags = interactionState != null ? interactionState.getOrCreate(node) : null;
        StyleCache cache = force != FORCE_NONE ? StyleCache.INVALID : styleCacheState(flags, parentFontSize, scope);
        boolean needsRestyle = cache != StyleCache.VALID;
        stats().boxes++;
        if (needsRestyle) stats().restyled++;

        ComputedStyle computed;
        boolean hasInteractive;
        int childForce = FORCE_NONE;
        boolean childForceIfInherits = false;
        boolean subtreeDirty = (flags != null && flags.subtreeDirty()) || force == FORCE_SUBTREE;

        if (needsRestyle) {
            boolean tk = ctx().trackAllocations;
            long k0 = tk ? threadAllocatedBytes() : 0;
            StyleSet styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
            ContainerBox container = parentSizeContainer;
            boolean viewportDependent;
            ComputedStyle previous = flags != null ? flags.computedStyle : null;
            long k1;
            long k2;
            if (interactionState != null) {
                StateStyleMemo.Entry entry = styleEntry(node, styleSet, inheritedStyle, sheet, env, parentFontSize,
                        scope, interactionState, container, force, flags);
                viewportDependent = entry.viewportDependent();
                computed = entry.computed();
                k1 = k2 = tk ? threadAllocatedBytes() : 0;
            } else {
                Map<String, String> rawStyle = styleSet.resolve(ElementState.NONE);
                if (node.attrs.containsKey("hidden")) {
                    rawStyle.put(CssProperty.DISPLAY, "none");
                }
                viewportDependent = flags != null && (node.parent == null || StyleResolver.rawDependsOnViewport(rawStyle));
                k1 = tk ? threadAllocatedBytes() : 0;
                computed = computeStyleOwned(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope, container);
                k2 = tk ? threadAllocatedBytes() : 0;
            }
            Map<String, String> animation = animationValues(node, previous, computed, inheritedStyle, sheet, interactionState);
            if (!animation.isEmpty()) {
                computed = recomputeWithAnimation(node, styleSet, inheritedStyle, sheet, env, parentFontSize, scope,
                        interactionState, container, animation);
                viewportDependent |= StyleResolver.rawDependsOnViewport(animation);
            }
            hasInteractive = styleSet.hasInteractiveRules();
            if (tk) {
                stats().allocResolve += k1 - k0;
                stats().allocCompute += k2 - k1;
                stats().allocAnimated += threadAllocatedBytes() - k2;
            }

            Propagation propagation = propagationOf(previous, computed);
            childForce = childForceOf(subtreeDirty, propagation, sheet);
            childForceIfInherits = propagation == Propagation.NON_INHERITED;
            if (flags != null) cacheStyle(flags, node, viewportDependent, computed, parentFontSize, scope, hasInteractive, sheet);
        } else {
            computed = flags.computedStyle;
            hasInteractive = flags.hasInteractiveRules;
            childForce = subtreeDirty ? FORCE_SUBTREE : FORCE_NONE;
            flags.clearSubtreeDirty();
            flags.clearDirtyBelow();
        }
        Map<String, String> childInherited = computed.asMap();
        StyleScope childScope = scope.enter(node, computed);

        LayoutBox box = directPrev != null && directPrev.node == node
                ? directPrev
                : (prevReusable != null ? prevReusable.take(node) : null);
        LayoutBox prevBefore = null;
        LayoutBox prevAfter = null;
        boolean reusedBox = false;
        if (box != null) {
            if (box.hasPseudoElementChild) {
                List<LayoutBox> ec = box.elementChildren;
                String ownerKey = node.key();
                for (int i = 0, n = ec.size(); i < n; i++) {
                    LayoutBox ch = ec.get(i);
                    if (!ch.node.isPseudoNode) continue;
                    String k = ch.node.key();
                    if (prevBefore == null && isPseudoKey(k, ownerKey, "_before")) prevBefore = ch;
                    else if (prevAfter == null && isPseudoKey(k, ownerKey, "_after")) prevAfter = ch;
                }
                box.hasPseudoElementChild = false;
            }
            box.elementChildren.clear();
            box.refreshStyle(computed);
            if (box.display.isNone() || LayoutBox.isInternalTableBox(box)) {
                box = null;
                prevBefore = null;
                prevAfter = null;
            } else {
                box.resetEngineOutputs();
                reusedBox = true;
                stats().reused++;
            }
        }
        if (box == null) {
            box = new LayoutBox(node, computed);
        }
        // 表のエンジンは中の箱へ直接書き込むので、表の中は作り直す。display: none の部分木は測られないので、
        // 使い回すと前回の寸法が残る（新しく作った箱は 0）。コンテナクエリの寸法などが読むので作り直す
        boolean entersTable = LayoutBox.recordReusableForBuild(inTableScope ? null : nextReusable, box);
        boolean childInTableScope = inTableScope || entersTable;
        boolean stopChildReuse = childInTableScope || box.display.isNone();
        LayoutBox.ReusableTable childPrevReusable = stopChildReuse ? null : prevReusable;
        LayoutBox.ReusableTable childNextReusable = childInTableScope ? null : nextReusable;
        box.hasInteractiveRules = hasInteractive;
        boolean selfOrAncestorMoving = stampMoving(box, computed, ancestorHasMoving);
        // 持ち主のスタイルのキャッシュが有効なら、状態も祖先・兄弟も前回のままなので疑似要素の規則の当たり方も前回のまま。
        // 疑似要素だけがビューポート単位を使うこともあるので、ビューポートが同じときに限って前回の疑似要素をそのまま使う
        // （再スタイルの経路 restyleBoxRecursive も、汚れていない要素の疑似要素は解き直さない）
        long viewportKey = ((long) scope.viewportW() << 32) | (scope.viewportH() & 0xffffffffL);
        ContainerBox selfContainer = containerForSelf(node, computed, parentSizeContainer, interactionState);
        boolean reusePseudos = reusedBox && !needsRestyle && box.pseudoStylesViewport == viewportKey;
        if (!reusePseudos) {
            resolveHighlightPseudos(box, computed, sheet, env, childScope, interactionState, selfContainer);
            box.pseudoStylesViewport = viewportKey;
        }

        // content-visibility: hidden の中身は測らず描かない（箱の木に入れない。疑似要素も同じ）
        boolean skipContents = computed.skipsContents();
        if (skipContents) {
            // 子を持たない箱として扱う
        } else if (reusePseudos) {
            if (prevBefore != null) {
                stampMovingSubtree(prevBefore, selfOrAncestorMoving);
                box.addElementChild(prevBefore);
            }
        } else {
            ComputedStyle beforeStyle = resolvePseudoStyle(node, PseudoType.BEFORE, computed, sheet, env, childScope, interactionState, selfContainer);
            if (beforeStyle != null) {
                box.addElementChild(buildPseudoBox(node, PseudoType.BEFORE, beforeStyle, hasInteractive, sheet, env,
                        childScope, interactionState, siblingInvalidation, selfContainer, selfOrAncestorMoving, !stopChildReuse, prevBefore));
            }
        }

        // svg の子（図形）は箱にしない。svg 要素が木を読んで自分でラスタライズする
        if (!skipContents && !"textarea".equalsIgnoreCase(node.tag) && !"svg".equalsIgnoreCase(node.tag)) {
            boolean isOpen = interactionState != null
                    ? interactionState.stateOf(node).open()
                    : node.attrs.containsKey("open");
            boolean isClosedDetails = "details".equalsIgnoreCase(node.tag) && !isOpen;
            boolean siblingForceDirty = false;
            for (HtmlNode child : node.children) {
                if (child.tag.equals("#text") && child.text.isEmpty()) continue;
                if (isClosedDetails && !"summary".equalsIgnoreCase(child.tag)) continue;
                boolean stateChanged = siblingInvalidation && isStateDirty(interactionState, child);
                int forceChild = siblingForceDirty ? FORCE_SUBTREE : childForce;
                if (forceChild == FORCE_NONE && childForceIfInherits && interactionState != null) {
                    InteractionState.StateFlags childFlags = interactionState.get(child);
                    if (childFlags != null && childFlags.inheritsExplicitly) forceChild = FORCE_SELF;
                }
                box.addElementChild(buildLayoutTree(child, childInherited, sheet, env, forceChild,
                        computed.fontSizePx(), childScope, interactionState, siblingInvalidation,
                        childPrevReusable, childNextReusable, childInTableScope, null,
                        selfContainer, selfOrAncestorMoving));
                siblingForceDirty |= stateChanged;
            }
        }

        if (skipContents) {
            // 同上
        } else if (reusePseudos) {
            if (prevAfter != null) {
                stampMovingSubtree(prevAfter, selfOrAncestorMoving);
                box.addElementChild(prevAfter);
            }
        } else {
            ComputedStyle afterStyle = resolvePseudoStyle(node, PseudoType.AFTER, computed, sheet, env, childScope, interactionState, selfContainer);
            if (afterStyle != null) {
                box.addElementChild(buildPseudoBox(node, PseudoType.AFTER, afterStyle, hasInteractive, sheet, env,
                        childScope, interactionState, siblingInvalidation, selfContainer, selfOrAncestorMoving, !stopChildReuse, prevAfter));
            }
        }

        return box;
    }

    private static void resolveHighlightPseudos(LayoutBox box, ComputedStyle computed,
                                                StyleSheet sheet,
                                                Map<String, String> env, StyleScope childScope,
                                                InteractionState interactionState, ContainerBox selfContainer) {
        HtmlNode node = box.node;
        box.markerStyle = box.display.listItem()
                ? resolvePseudoStyle(node, PseudoType.MARKER, computed, sheet, env, childScope, interactionState, selfContainer)
                : null;
        boolean textInput = "input".equals(node.tag) || "textarea".equals(node.tag);
        box.placeholderStyle = textInput
                ? resolvePseudoStyle(node, PseudoType.PLACEHOLDER, computed, sheet, env, childScope, interactionState, selfContainer)
                : null;
        // Top Layer の箱（dialog・popover）だけが ::backdrop を持つ
        boolean topLayerCapable = "dialog".equals(node.tag) || node.attrs != null && node.attrs.containsKey("popover");
        box.backdropStyle = topLayerCapable
                ? resolvePseudoStyle(node, PseudoType.BACKDROP, computed, sheet, env, childScope, interactionState, selfContainer)
                : null;
    }

    private static ComputedStyle resolvePseudoStyle(HtmlNode owner, PseudoType type, ComputedStyle ownerStyle,
                                                    StyleSheet sheet,
                                                    Map<String, String> env, StyleScope scope,
                                                    InteractionState interactionState, ContainerBox selfContainer) {
        StateLookup lookup =
                interactionState != null ? interactionState : SelectorMatcher.STATIC_ATTRIBUTES;
        return StyleResolver.resolvePseudoStyle(owner, type, sheet, lookup, ownerStyle, env,
                scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(), selfContainer);
    }

    /** {@code key.equals(ownerKey + suffix)} を文字列を作らずに判定する。 */
    private static boolean isPseudoKey(String key, String ownerKey, String suffix) {
        return key != null && key.length() == ownerKey.length() + suffix.length()
                && key.startsWith(ownerKey) && key.endsWith(suffix);
    }

    private static String pseudoElementId(HtmlNode owner, PseudoType type) {
        return owner.key() + (type == PseudoType.BEFORE ? "_before" : "_after");
    }

    private static LayoutBox buildPseudoBox(HtmlNode owner, PseudoType type, ComputedStyle style, boolean ownerInteractive,
                                           StyleSheet sheet,
                                           Map<String, String> env, StyleScope scope,
                                           InteractionState interactionState, boolean siblingInvalidation,
                                           ContainerBox ownerSizeContainer, boolean ownerSelfOrAncestorMoving) {
        return buildPseudoBox(owner, type, style, ownerInteractive, sheet, env, scope, interactionState,
                siblingInvalidation, ownerSizeContainer, ownerSelfOrAncestorMoving, false, null);
    }

    /**
     * {@code ::before} / {@code ::after} の箱を作る。持ち主の前回の子に同じ id の疑似要素があれば、
     * その合成ノードと箱を使い回す（中の文字は {@link BoxTreeBuilder} が組むたびに入れ直す）。
     */
    private static LayoutBox buildPseudoBox(HtmlNode owner, PseudoType type, ComputedStyle style, boolean ownerInteractive,
                                           StyleSheet sheet,
                                           Map<String, String> env, StyleScope scope,
                                           InteractionState interactionState, boolean siblingInvalidation,
                                           ContainerBox ownerSizeContainer, boolean ownerSelfOrAncestorMoving,
                                           boolean canReuse, LayoutBox prevPseudo) {
        LayoutBox box = null;
        LayoutBox prevTextBox = null;
        if (canReuse && prevPseudo != null && prevPseudo.node.children.size() == 1) {
            box = prevPseudo;
            prevTextBox = box.elementChildren.isEmpty() ? null : box.elementChildren.getFirst();
        }

        HtmlNode textNode;
        if (box != null) {
            box.node.parent = owner;
            box.elementChildren.clear();
            box.hasPseudoElementChild = false;
            box.updateStyle(style);
            textNode = box.node.children.getFirst();
        } else {
            String pseudoId = pseudoElementId(owner, type);
            HtmlNode pseudo = new HtmlNode("span");
            pseudo.isPseudoNode = true;
            pseudo.parent = owner;
            pseudo.setId(pseudoId);

            textNode = new HtmlText("");
            textNode.parent = pseudo;
            textNode.setId(pseudo.key() + "_txt");
            pseudo.children.add(textNode);
            box = new LayoutBox(pseudo, style);
        }
        box.hasInteractiveRules = ownerInteractive;
        boolean pseudoMoving = stampMoving(box, style, ownerSelfOrAncestorMoving);
        box.addElementChild(buildLayoutTree(textNode, style.asMap(), sheet, env, FORCE_SUBTREE, style.fontSizePx(), scope,
                interactionState, siblingInvalidation, null, null, false, prevTextBox, ownerSizeContainer, pseudoMoving));
        return box;
    }

    private static boolean stampMoving(LayoutBox box, ComputedStyle computed, boolean ancestorHasMoving) {
        boolean selfOrAncestor = ancestorHasMoving || PaintOrder.hasMovingProps(computed);
        box.ancestorMoving = ancestorHasMoving;
        box.selfOrAncestorMoving = selfOrAncestor;
        box.movingStamped = true;
        return selfOrAncestor;
    }

    private static void stampMovingSubtree(LayoutBox box, boolean ancestorHasMoving) {
        if (box.movingStamped && box.ancestorMoving == ancestorHasMoving) return;
        boolean selfOrAncestor = stampMoving(box, box.style, ancestorHasMoving);
        for (int i = 0, n = box.elementChildren.size(); i < n; i++) {
            stampMovingSubtree(box.elementChildren.get(i), selfOrAncestor);
        }
    }
}
