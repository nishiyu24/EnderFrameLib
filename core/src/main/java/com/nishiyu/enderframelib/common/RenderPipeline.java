package com.nishiyu.enderframelib.common;

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

    @FunctionalInterface
    public interface ILayoutBoxRenderer {
        List<DisplayItem> render(LayoutBox rootBox, int rootW, int rootH,
                                Map<String, Keyframes> keyframes,
                                ScrollState scrollState);
    }

    private static final int LAYOUT_PASSES = 1;
    /** レイアウトの通し番号（{@link LayoutContext#layoutRun()}）。 */
    private static final java.util.concurrent.atomic.AtomicLong LAYOUT_RUNS = new java.util.concurrent.atomic.AtomicLong();
    private static final int MAX_CONTAINER_PASSES = 4;

    public static ILayoutBoxRenderer clientBoxRenderer = null;
    public static IntrinsicSizeResolver intrinsicSizeResolver = null;

    private static ILayoutBoxRenderer effectiveRenderer() {
        return clientBoxRenderer != null ? clientBoxRenderer : UiDocumentCompiler.clientBoxRenderer;
    }

    private static IntrinsicSizeResolver effectiveIntrinsicResolver() {
        return intrinsicSizeResolver != null ? intrinsicSizeResolver : UiDocumentCompiler.intrinsicSizeResolver;
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

    /**
     * 差分レイアウトを有効にするか。false なら {@code previousRoot} を渡されても全部を作り直して測り直す
     * （差分レイアウトを疑うときの切り分け用）。
     */
    public static volatile boolean incrementalLayout = true;

    /**
     * 再スタイルの走査で、汚れていない部分木を丸ごと飛ばすか。false なら全ての箱をたどる
     * （飛ばし方を疑うときの切り分け用。結果は同じでなければならない）。
     */
    public static volatile boolean pruneCleanSubtrees = true;

    /** 直近の {@link #layoutDocument} で作った箱の数・スタイルを算出し直した数・前回の箱を使い回した数（調べる用）。 */
    public static volatile String lastBuildStats = "";

    /** 診断用: true の間、各段の割り当て量（KB、呼び出しスレッド）を {@link #lastBuildStats} に足す。既定は false で費用なし。 */
    public static volatile boolean trackAllocations = false;

    /** 診断用: {@link #trackAllocations} のとき、スタイル解決の段ごとの割り当て（バイト）。 */
    public static long allocResolve, allocCompute, allocAnimated;

    /** 呼び出しスレッドがこれまでに割り当てたバイト数（{@link #trackAllocations} のときだけ呼ぶ）。 */
    public static long threadAllocatedBytes() {
        return ((com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean())
                .getThreadAllocatedBytes(Thread.currentThread().getId());
    }
    private static int statBoxes, statRestyled, statReused;
    private static String statPhases = "";
    private static long passMeasureMs, passLayoutMs, passMeasured;

    /**
     * 文書をレイアウトする。{@code previousRoot}（同じ文書の前回の結果）があれば差分レイアウトにする:
     * 箱は DOM の節ごとに使い回し、中身も入力も前回と同じ部分木は measure を飛ばす。
     * 結果は全部を作り直したときと同じ（{@code IncrementalLayoutTest} が確かめる）。
     */
    public static LayoutBox layoutDocument(Document doc, int rootW, int rootH, Map<String, String> env,
                                           ScrollState scrollState, FontResolver fonts, InteractionState interactionState,
                                           LayoutBox previousRoot) {
        boolean incremental = incrementalLayout && previousRoot != null;
        Map<String, String> rootStyleRaw = rootViewportStyle(rootW, rootH);

        InteractionState effectiveState = interactionState != null ? interactionState : new InteractionState();
        effectiveState.bindStyleSheet(doc.sheet);
        if (doc.sheet.updateMediaEnvironment(doc.sheet.mediaEnvironment().withSize(rootW, rootH))) {
            effectiveState.markAllDirty();
        }
        boolean hasContainers = doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries();

        LayoutBox rootBox = incremental ? previousRoot : null;
        for (int settle = 0; settle <= MAX_CONTAINER_PASSES; settle++) {
            Map<HtmlNode, LayoutBox> reusable = incremental ? LayoutBox.reusableBoxes(rootBox) : null;
            statBoxes = statRestyled = statReused = 0;
            allocResolve = allocCompute = allocAnimated = 0;
            long tA = System.nanoTime();
            long profBuild = StageProfiler.begin(PipelineStage.STYLE_BUILD);
            boolean track = trackAllocations;
            long aA = track ? threadAllocatedBytes() : 0;
            // 共有した結果は残す（restyleLayoutTree と同じ理由。シート・環境・版が変われば ShareCache が捨てる）
            try {
                rootBox = buildLayoutTree(doc.root, rootStyleRaw, doc.sheet, env, FORCE_NONE,
                        ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(rootW, rootH),
                        effectiveState, StyleResolver.hasSiblingStateRules(doc.sheet), reusable);
            } finally {
                StageProfiler.end(PipelineStage.STYLE_BUILD, profBuild);
            }
            long tB = System.nanoTime();
            long aB = track ? threadAllocatedBytes() : 0;
            long profBox = StageProfiler.begin(PipelineStage.BOX_TREE);
            BoxTreeBuilder.build(rootBox, CounterStyles.of(doc.sheet.counterStyles));
            StageProfiler.end(PipelineStage.BOX_TREE, profBox);
            long tC = System.nanoTime();
            long aC = track ? threadAllocatedBytes() : 0;
            if (incremental) LayoutBox.markLayoutChanges(rootBox);
            long tD = System.nanoTime();
            runLayoutPasses(rootBox, rootW, rootH, doc.sheet.mediaEnvironment().resolution(), scrollState, fonts, incremental);
            long tE = System.nanoTime();
            long aE = track ? threadAllocatedBytes() : 0;
            statPhases = "build=" + (tB - tA) / 1_000_000 + " boxTree=" + (tC - tB) / 1_000_000 + " mark=" + (tD - tC) / 1_000_000
                    + " passes=" + (tE - tD) / 1_000_000 + "(measure=" + passMeasureMs + "/" + passMeasured + "boxes layout=" + passLayoutMs + ")"
                    + (track ? " allocKB[resolve=" + allocResolve / 1024 + " compute=" + allocCompute / 1024 + " anim=" + allocAnimated / 1024 + " build=" + (aB - aA) / 1024 + " boxTree=" + (aC - aB) / 1024 + " passes=" + (aE - aC) / 1024 + "]" : "");
            if (!hasContainers || !measureContainers(rootBox, effectiveState)) break;
        }
        lastBuildStats = "boxes=" + statBoxes + " restyled=" + statRestyled + " reused=" + statReused + " " + statPhases;
        return rootBox;
    }

    /**
     * 部分木（HUD トラッカーなど）をレイアウトしてルートの {@link LayoutBox} を返す。
     */
    public static LayoutBox layoutSubtree(HtmlNode root, StyleSheet sheet, int rootW, int rootH,
                                          Map<String, String> env, ScrollState scrollState, FontResolver fonts) {
        LayoutBox rootBox = buildLayoutTree(root, Map.of("display", "inline-block"), sheet, env, false);
        BoxTreeBuilder.build(rootBox, CounterStyles.of(sheet.counterStyles));
        runLayoutPasses(rootBox, rootW, rootH, sheet.mediaEnvironment().resolution(), scrollState, fonts, false);
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
        runLayoutPasses(rootBox, rootW, rootH, devicePixelRatio, scrollState, fonts, reuseMeasurements, true);
    }

    /**
     * @param clearMarks 配置まで終えたあとに、全ての箱の「変わった」印を外す。全体の印付けのあとは、変わった箱もそうでない箱も
     *                   印が付いたまま残り、次の局所更新（経路だけの印付け）が経路の外の箱を「変わった」と読んで配置を使い回せない
     */
    private static void runLayoutPasses(LayoutBox rootBox, int rootW, int rootH, float devicePixelRatio,
                                        ScrollState scrollState, FontResolver fonts, boolean reuseMeasurements, boolean clearMarks) {
        rootBox.linkParents();
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
        LayoutBox.resetPlacements(rootBox, reuseMeasurements ? run : -1);
        rootBox.layout(0, 0, rootW, rootH, ctx);
        long m2 = System.nanoTime();
        StageProfiler.record(PipelineStage.LAYOUT_MEASURE, m1 - m0);
        StageProfiler.record(PipelineStage.LAYOUT_PLACE, m2 - m1);
        passMeasureMs = (m1 - m0) / 1_000_000;
        passMeasured = LayoutBox.measurementsComputed() - measuredBefore;
        passLayoutMs = (System.nanoTime() - m1) / 1_000_000;
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

    /** 局所レイアウトを有効にするか。false なら常に木全体の印付けをする（切り分け用。結果は同じでなければならない）。 */
    public static volatile boolean localRelayout = true;

    /** 直近の {@link #relayoutInPlace} が経路だけの印付け（局所）で済んだか。調べる・テスト用。 */
    public static volatile boolean lastRelayoutWasLocal;

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
        List<LayoutBox> path = localRelayout && changed != null && !changed.isEmpty()
                ? LayoutBox.markLayoutChangesAlong(rootBox, changed, shallow) : null;
        lastRelayoutWasLocal = path != null;
        if (path == null) LayoutBox.markLayoutChanges(rootBox);
        runLayoutPasses(rootBox, rootW, rootH, devicePixelRatio, scrollState, fonts, true, path == null);
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
        return buildLayoutTree(node, parentBox.style.asMap(), sheet, env, FORCE_SUBTREE, parentBox.style.fontSizePx(), scope,
                interactionState, StyleResolver.hasSiblingStateRules(sheet), null);
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
        LayoutBox.resetPlacements(rootBox);
        rootBox.layout(0, 0, rootW, rootH, ctx);
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
        String position = newStyle.get(CssProperty.POSITION);
        if (!Objects.equals(position, oldStyle.get(CssProperty.POSITION))) return false;
        boolean offsetOnly = "relative".equals(position) || "sticky".equals(position);
        boolean outOfFlow = "absolute".equals(position) || "fixed".equals(position);
        if (!offsetOnly && !outOfFlow) return false;

        boolean[] axes = {false, false, false}; // 横, 縦, 他（位置の指定では吸収できない変更）
        ComputedStyle.diff(oldStyle, newStyle, (key, paintOnly, inherited) -> {
            if (paintOnly) return;
            switch (key) {
                case CssProperty.LEFT, CssProperty.RIGHT -> axes[0] = true;
                case CssProperty.TOP, CssProperty.BOTTOM -> axes[1] = true;
                default -> axes[2] = true;
            }
        });
        if (axes[2]) return false;
        boolean horizontal = axes[0];
        boolean vertical = axes[1];
        if (offsetOnly) return true;
        if (horizontal && (bothInsets(oldStyle, CssProperty.LEFT, CssProperty.RIGHT)
                || bothInsets(newStyle, CssProperty.LEFT, CssProperty.RIGHT))) {
            return false;
        }
        return !vertical || !(bothInsets(oldStyle, CssProperty.TOP, CssProperty.BOTTOM)
                || bothInsets(newStyle, CssProperty.TOP, CssProperty.BOTTOM));
    }

    private static boolean bothInsets(ComputedStyle style, String start, String end) {
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
    static final List<LayoutBox> layoutChangedBoxes = new ArrayList<>();

    /** 局所再レイアウト（箱の木を作り直さない）を有効にするか。false なら常に木を作り直す（切り分け用。結果は同じでなければならない）。 */
    public static volatile boolean inPlaceRestyleRelayout = true;

    /**
     * 箱の木の形（匿名箱・生成箱・表示の種類）に響かず、寸法の入力にだけなる非継承プロパティか。
     * これだけが変わった再スタイルは、箱の木を作り直さず測り直しだけで足りる。
     */
    static boolean isGeometryOnlyProperty(String name) {
        return switch (name) {
            case CssProperty.WIDTH, CssProperty.HEIGHT, CssProperty.MIN_WIDTH, CssProperty.MAX_WIDTH,
                 CssProperty.MIN_HEIGHT, CssProperty.MAX_HEIGHT, CssProperty.LEFT, CssProperty.RIGHT,
                 CssProperty.TOP, CssProperty.BOTTOM, "flex-grow", "flex-shrink", "flex-basis" -> true;
            default -> name.startsWith("margin-") || name.startsWith("padding-");
        };
    }

    private static boolean isGeometryOnlyChange(ComputedStyle oldStyle, ComputedStyle newStyle) {
        if (oldStyle == null || newStyle == null) return false;
        if (Float.compare(oldStyle.fontSizePx(), newStyle.fontSizePx()) != 0
                || Float.compare(oldStyle.rootFontSizePx(), newStyle.rootFontSizePx()) != 0) {
            return false;
        }
        boolean[] ok = {true};
        ComputedStyle.diff(oldStyle, newStyle, (name, paintOnly, inherited) -> {
            if (!paintOnly && (inherited || !isGeometryOnlyProperty(name))) ok[0] = false;
        });
        return ok[0];
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
                || flags.isStyleDirty
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
        if (previous == null) return Propagation.INHERITED;
        if (Float.compare(previous.fontSizePx(), computed.fontSizePx()) != 0
                || Float.compare(previous.rootFontSizePx(), computed.rootFontSizePx()) != 0) {
            return Propagation.INHERITED;
        }
        if (ComputedStyle.sameDeclarations(previous, computed)) return Propagation.NONE;
        Propagation[] result = {Propagation.NONE};
        ComputedStyle.diff(previous, computed, (name, paintOnly, inherited) -> {
            if (inherited) result[0] = Propagation.INHERITED;
            else if (result[0] == Propagation.NONE) result[0] = Propagation.NON_INHERITED;
        });
        return result[0];
    }

    private static int childForceOf(boolean subtreeDirty, Propagation propagation, StyleSheet sheet) {
        if (subtreeDirty) return FORCE_SUBTREE;
        if (propagation == Propagation.INHERITED
                || (propagation == Propagation.NON_INHERITED && sheet != null && sheet.usesInheritKeyword())) {
            return FORCE_SELF;
        }
        return FORCE_NONE;
    }

    /** 正規表現にかける前の足切り: ビューポート依存の書き方（{@code vw} {@code cqw} {@code var(} {@code env(}）は必ず v・q・( のどれかを含む。 */
    private static final String[] VIEWPORT_SUFFIXES = {"w", "h", "i", "b", "min", "max"};

    /** 末尾の単位（w / h / i / b / min / max）が {@code at} から始まり、その直後が英字でないか。 */
    private static boolean viewportSuffixAt(String v, int at) {
        for (String suffix : VIEWPORT_SUFFIXES) {
            int end = at + suffix.length();
            if (v.regionMatches(true, at, suffix, 0, suffix.length())
                    && (end >= v.length() || !isAsciiLetter(v.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    /**
     * 値がビューポート（vw/vh…）・コンテナ（cqw…）の単位、{@code var()}、{@code env()} を含むか。
     * {@link #VIEWPORT_DEPENDENT_VALUE} と同じ判定を、{@code Matcher} を作らずに行う。
     */
    static boolean dependsOnViewportValue(String v) {
        int n = v.length();
        for (int i = 0; i < n; i++) {
            char c = v.charAt(i);
            if (c == '(' && i >= 3) {
                if (v.regionMatches(true, i - 3, "var", 0, 3) || v.regionMatches(true, i - 3, "env", 0, 3)) return true;
            }
            if (c >= '0' && c <= '9' && i + 1 < n) {
                int j = i + 1;
                char d = v.charAt(j);
                if ((d == 's' || d == 'S' || d == 'l' || d == 'L' || d == 'd' || d == 'D') && j + 1 < n
                        && (v.charAt(j + 1) == 'v' || v.charAt(j + 1) == 'V') && viewportSuffixAt(v, j + 2)) {
                    return true;
                }
                if ((d == 'v' || d == 'V') && viewportSuffixAt(v, j + 1)) return true;
                if ((d == 'c' || d == 'C') && j + 1 < n && (v.charAt(j + 1) == 'q' || v.charAt(j + 1) == 'Q')
                        && viewportSuffixAt(v, j + 2)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean mayDependOnViewport(String value) {
        for (int i = 0, n = value.length(); i < n; i++) {
            char c = value.charAt(i);
            if (c == '(' || c == 'v' || c == 'V' || c == 'q' || c == 'Q') return true;
        }
        return false;
    }

    private static boolean dependsOnViewport(HtmlNode node, Map<String, String> rawStyle) {
        // ルートはビューポートの寸法を継承元の表として受け取る
        if (node.parent == null) return true;
        for (String value : rawStyle.values()) {
            if (value != null && mayDependOnViewport(value) && dependsOnViewportValue(value)) return true;
        }
        return false;
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
                        interactionState, null,
                        (StateLookup) n -> n == node ? ElementState.of(bits) : interactionState.stateOf(n)));
    }

    private static StateStyleMemo.Entry computeEntry(HtmlNode node, StyleSet styleSet,
                                                     Map<String, String> inheritedStyle, StyleSheet sheet,
                                                     Map<String, String> env, float parentFontSize, StyleScope scope,
                                                     InteractionState interactionState, ContainerBox container,
                                                     StateLookup lookup) {
        StyleResolver.Shared shared = interactionState.styleShare.resolve(node, inheritedStyle, sheet, lookup, env,
                parentFontSize, scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(),
                container != null ? container : containerFor(node, interactionState));
        if (shared != null) return new StateStyleMemo.Entry(shared.raw(), shared.computed());
        if (styleSet == null) styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
        Map<String, String> raw = styleSet.resolve(lookup);
        if (node.attrs.containsKey("hidden")) {
            raw.put(CssProperty.DISPLAY, "none");
        }
        return new StateStyleMemo.Entry(raw, computeStyle(raw, inheritedStyle, sheet, env, parentFontSize, scope,
                container != null ? container : containerFor(node, interactionState)));
    }

    private static void cacheStyle(InteractionState.StateFlags flags, HtmlNode node, Map<String, String> rawStyle,
                                   ComputedStyle computed, float parentFontSize, StyleScope scope,
                                   boolean hasInteractive) {
        flags.computedStyleCache = rawStyle;
        flags.computedStyle = computed;
        flags.cachedParentFontSize = parentFontSize;
        flags.cachedRootFontSize = scope.rootFontSize();
        flags.cachedRootLineHeight = scope.rootLineHeight();
        flags.cachedViewportW = scope.viewportW();
        flags.cachedViewportH = scope.viewportH();
        flags.viewportDependent = dependsOnViewport(node, rawStyle);
        flags.hasInteractiveRules = hasInteractive;
        flags.clearStyleDirtyFlag();
        flags.subtreeDirty = false;
        flags.dirtyBelow = false;
        flags.unscoped = false;
        String inlineStyle = node.attrs.get("style");
        flags.inheritsExplicitly = inlineStyle != null && inlineStyle.toLowerCase(java.util.Locale.ROOT).contains("inherit");
    }

    private static ComputedStyle computeStyle(Map<String, String> rawStyle, Map<String, String> parentStyle,
                                              StyleSheet sheet,
                                              Map<String, String> env, float parentFontSize, StyleScope scope,
                                              ContainerBox container) {
        return StyleResolver.finalizeComputedStyle(rawStyle, sheet, env, parentStyle,
                parentFontSize, scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH(), container);
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
        return interactionState != null
                ? interactionState.nearestSizeContainer(node.parent)
                : ContainerBox.NONE_SIZE;
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
        interactionState.pruneRestyle = pruneCleanSubtrees && rootFlags != null
                && rootFlags.cachedViewportW == rootW && rootFlags.cachedViewportH == rootH;
        // 共有の結果は再スタイルをまたいで残す（鍵は親の算出表の同一性・掛かった規則・数値の入力。シートの版・環境が変われば ShareCache が捨てる）。
        // 親が変わらない要素の「前に解いた規則の組」へ戻る往復（class の付け外しなど）が、解き直しなしで済む
        RenderPipeline.layoutChangedBoxes.clear();
        long prof = StageProfiler.begin(PipelineStage.RESTYLE_LOCAL);
        try {
            return restyleBoxRecursive(rootBox, rootStyleRaw, doc.sheet, env, FORCE_NONE,
                    ComputedStyle.DEFAULT_FONT_SIZE, StyleScope.initial(rootW, rootH),
                    interactionState, StyleResolver.hasSiblingStateRules(doc.sheet));
        } finally {
            StageProfiler.end(PipelineStage.RESTYLE_LOCAL, prof);
        }
    }

    private static RestyleResult restyleBoxRecursive(LayoutBox box, Map<String, String> inheritedStyle,
                                                     StyleSheet sheet,
                                                     Map<String, String> env, int force,
                                                     float parentFontSize, StyleScope scope,
                                                     InteractionState interactionState, boolean siblingInvalidation) {
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
        boolean subtreeDirty = flags.subtreeDirty || force == FORCE_SUBTREE;

        if (isDirty) {
            ComputedStyle prevStyle = box.style;
            ContainerBox container = containerFor(node, interactionState);
            // StyleSet は表を引けなかったとき（computeEntry）だけ作る
            StateStyleMemo.Entry entry = styleEntry(node, null, inheritedStyle, sheet, env, parentFontSize, scope,
                    interactionState, container, force, flags);
            Map<String, String> rawStyle = entry.raw();
            computed = entry.computed();
            Map<String, String> animation = animationValues(node, prevStyle, computed, inheritedStyle, sheet, interactionState);
            if (!animation.isEmpty()) {
                rawStyle = new java.util.LinkedHashMap<>(rawStyle);
                rawStyle.putAll(animation);
                computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope, container);
            }
            boolean hasInteractive = StyleResolver.hasInteractiveRules(node, sheet);

            if (prevStyle == null || !ComputedStyle.sameDeclarations(prevStyle, computed)) {
                styleChanged = true;
                if (StyleResolver.isLayoutAffectingChange(prevStyle, computed)) {
                    if (isPositionOnlyChange(prevStyle, computed)) {
                        repositionRequired = true;
                    } else {
                        layoutRequired = true;
                        if (!isGeometryOnlyChange(prevStyle, computed)) structural = true;
                        else RenderPipeline.layoutChangedBoxes.add(box);
                    }
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
            resolveHighlightPseudos(box, computed, sheet, env, scope.enter(node, computed), interactionState);
            BoxTreeBuilder.restyleGeneratedBoxes(box);
            box.hasInteractiveRules = hasInteractive;
            cacheStyle(flags, node, rawStyle, computed, parentFontSize, scope, hasInteractive);
        } else {
            computed = flags.computedStyle;
            childForce = subtreeDirty ? FORCE_SUBTREE : FORCE_NONE;
            flags.subtreeDirty = false;
        }
        flags.dirtyBelow = false; // 子孫は、このあと必要なものだけ走査して片付ける

        float nextParentFontSize = computed.fontSizePx();
        StyleScope childScope = scope.enter(node, computed);

        if (isDirty && !computed.skipsContents()) {
            for (int pi = 0; pi < GENERATED_PSEUDOS.length; pi++) {
                PseudoType type = GENERATED_PSEUDOS[pi];
                RestyleResult pseudoRes = restylePseudoElement(box, type, computed, sheet, env, childScope,
                        interactionState, siblingInvalidation);
                styleChanged |= pseudoRes.styleChanged();
                layoutRequired |= pseudoRes.layoutRequired();
                structural |= pseudoRes.structural();
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
            if (forceChild == FORCE_NONE && interactionState.pruneRestyle && canSkipRestyle(interactionState.get(child.node), nextParentFontSize, childScope)) {
                continue; // 自分も子孫も汚れていない部分木は、算出し直すものが無い（stateChanged は汚れていなければ false）
            }
            RestyleResult childRes = restyleBoxRecursive(child, computed.asMap(), sheet, env,
                    forceChild, nextParentFontSize, childScope, interactionState,
                    siblingInvalidation);
            styleChanged |= childRes.styleChanged();
            layoutRequired |= childRes.layoutRequired();
            repositionRequired |= childRes.repositionRequired();
            structural |= childRes.structural();
            siblingForceDirty |= stateChanged;
        }

        if (!styleChanged && !layoutRequired && !repositionRequired && !structural) return RestyleResult.UNCHANGED;
        return new RestyleResult(styleChanged, layoutRequired, repositionRequired, structural);
    }

    private static final PseudoType[] GENERATED_PSEUDOS = PseudoType.GENERATED.toArray(new PseudoType[0]);

    /**
     * この箱の部分木を丸ごと飛ばしてよいか: 算出スタイルのキャッシュが有効で、自分にも子孫にも汚れが無い。
     * ビューポートが変わったとき（子孫の {@code vw} などが古くなる）は走査側が {@code pruneRestyle} を切る。
     */
    private static boolean canSkipRestyle(InteractionState.StateFlags flags, float parentFontSize, StyleScope scope) {
        return flags != null && !flags.isStyleDirty && !flags.subtreeDirty && !flags.dirtyBelow
                && styleCacheState(flags, parentFontSize, scope) == StyleCache.VALID;
    }

    private static boolean isStateDirty(InteractionState interactionState, HtmlNode node) {
        if (interactionState == null) return false;
        InteractionState.StateFlags flags = interactionState.get(node);
        return flags == null || flags.isStyleDirty;
    }

    private static RestyleResult restylePseudoElement(LayoutBox owner, PseudoType type, ComputedStyle ownerStyle,
                                                      StyleSheet sheet,
                                                      Map<String, String> env, StyleScope scope,
                                                      InteractionState interactionState, boolean siblingInvalidation) {
        String ownerKey = owner.node.key();
        String suffix = type == PseudoType.BEFORE ? "_before" : "_after";
        LayoutBox existing = null;
        for (int ci = 0, cn = owner.elementChildren.size(); ci < cn; ci++) {
            LayoutBox child = owner.elementChildren.get(ci);
            if (child.node.isPseudoNode && isPseudoKey(child.node.key(), ownerKey, suffix)) {
                existing = child;
                break;
            }
        }
        ComputedStyle next = resolvePseudoStyle(owner.node, type, ownerStyle, sheet, env, scope, interactionState);
        if (existing == null && next == null) return RestyleResult.UNCHANGED;

        if (existing == null || next == null) {
            if (existing != null) {
                owner.elementChildren.remove(existing);
            } else {
                LayoutBox created = buildPseudoBox(owner.node, type, next, owner.hasInteractiveRules, sheet, env,
                        scope, interactionState, siblingInvalidation);
                owner.addElementChild(created);
                if (type == PseudoType.BEFORE) {
                    owner.elementChildren.remove(created);
                    owner.elementChildren.add(0, created);
                }
            }
            return new RestyleResult(true, true);
        }

        boolean styleChanged = !ComputedStyle.sameDeclarations(existing.style, next);
        boolean layoutRequired = styleChanged && StyleResolver.isLayoutAffectingChange(existing.style, next);
        existing.updateStyle(next);
        existing.hasInteractiveRules = owner.hasInteractiveRules;
        BoxTreeBuilder.restyleGeneratedBoxes(existing);
        for (int ti = 0; ti < existing.elementChildren.size(); ti++) {
            LayoutBox text = existing.elementChildren.get(ti);
            RestyleResult textRes = restyleBoxRecursive(text, next.asMap(), sheet, env, FORCE_SUBTREE, next.fontSizePx(),
                    scope, interactionState, siblingInvalidation);
            styleChanged |= textRes.styleChanged();
            layoutRequired |= textRes.layoutRequired();
        }
        return new RestyleResult(styleChanged, layoutRequired);
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
            return buildLayoutTree(node, inheritedStyle, sheet, env, forceDirty ? FORCE_SUBTREE : FORCE_NONE,
                    parentFontSize, scope, interactionState, siblingInvalidation, null);
        } finally {
            if (interactionState != null) interactionState.styleShare.clear();
        }
    }

    /**
     * 箱の木を組む。{@code reusable} があれば、同じ DOM の節の前回の箱を作り直さずに使い回す（React の差分適用と同じく、
     * 変わらない部分の実体を保つ）。使い回した箱は前回の measure の結果を持っているので、中身も入力も変わっていなければ
     * {@link LayoutBox#measure} がそれを使う。表の中は使い回さない（{@link LayoutBox#reusableBoxes} を参照）。
     */
    private static LayoutBox buildLayoutTree(HtmlNode node, Map<String, String> inheritedStyle,
                                             StyleSheet sheet,
                                             Map<String, String> env, int force,
                                             float parentFontSize, StyleScope scope, InteractionState interactionState,
                                             boolean siblingInvalidation, Map<HtmlNode, LayoutBox> reusable) {
        InteractionState.StateFlags flags = interactionState != null ? interactionState.getOrCreate(node) : null;
        StyleCache cache = force != FORCE_NONE ? StyleCache.INVALID : styleCacheState(flags, parentFontSize, scope);
        boolean needsRestyle = cache != StyleCache.VALID;
        statBoxes++;
        if (needsRestyle) statRestyled++;

        ComputedStyle computed;
        boolean hasInteractive;
        int childForce = FORCE_NONE;
        boolean childForceIfInherits = false;
        boolean subtreeDirty = (flags != null && flags.subtreeDirty) || force == FORCE_SUBTREE;

        if (needsRestyle) {
            boolean tk = trackAllocations;
            long k0 = tk ? threadAllocatedBytes() : 0;
            StyleSet styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
            ContainerBox container = containerFor(node, interactionState);
            Map<String, String> rawStyle;
            ComputedStyle previous = flags != null ? flags.computedStyle : null;
            long k1;
            long k2;
            if (interactionState != null) {
                StateStyleMemo.Entry entry = styleEntry(node, styleSet, inheritedStyle, sheet, env, parentFontSize,
                        scope, interactionState, container, force, flags);
                rawStyle = entry.raw();
                computed = entry.computed();
                k1 = k2 = tk ? threadAllocatedBytes() : 0;
            } else {
                rawStyle = styleSet.resolve(ElementState.NONE);
                if (node.attrs.containsKey("hidden")) {
                    rawStyle.put(CssProperty.DISPLAY, "none");
                }
                k1 = tk ? threadAllocatedBytes() : 0;
                computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope, container);
                k2 = tk ? threadAllocatedBytes() : 0;
            }
            Map<String, String> animation = animationValues(node, previous, computed, inheritedStyle, sheet, interactionState);
            if (!animation.isEmpty()) {
                rawStyle = new java.util.LinkedHashMap<>(rawStyle);
                rawStyle.putAll(animation);
                computed = computeStyle(rawStyle, inheritedStyle, sheet, env, parentFontSize, scope, container);
            }
            hasInteractive = styleSet.hasInteractiveRules();
            if (tk) {
                allocResolve += k1 - k0;
                allocCompute += k2 - k1;
                allocAnimated += threadAllocatedBytes() - k2;
            }

            Propagation propagation = propagationOf(previous, computed);
            childForce = childForceOf(subtreeDirty, propagation, sheet);
            childForceIfInherits = propagation == Propagation.NON_INHERITED;
            if (flags != null) cacheStyle(flags, node, rawStyle, computed, parentFontSize, scope, hasInteractive);
        } else {
            computed = flags.computedStyle;
            hasInteractive = flags.hasInteractiveRules;
            childForce = subtreeDirty ? FORCE_SUBTREE : FORCE_NONE;
            flags.subtreeDirty = false;
            flags.dirtyBelow = false;
        }
        Map<String, String> childInherited = computed.asMap();
        StyleScope childScope = scope.enter(node, computed);

        LayoutBox box = reusable != null ? reusable.remove(node) : null;
        List<LayoutBox> previousChildren = List.of();
        boolean reusedBox = false;
        if (box != null) {
            previousChildren = new ArrayList<>(box.elementChildren);
            box.elementChildren.clear();
            box.refreshStyle(computed);
            if (box.display.isNone()) box = null;
            else {
                box.resetEngineOutputs();
                reusedBox = true;
                statReused++;
            }
        }
        if (box == null) {
            box = new LayoutBox(node, computed);
            previousChildren = List.of();
        }
        // 表のエンジンは中の箱へ直接書き込むので、表の中は作り直す。display: none の部分木は測られないので、
        // 使い回すと前回の寸法が残る（新しく作った箱は 0）。コンテナクエリの寸法などが読むので作り直す
        Map<HtmlNode, LayoutBox> childReusable = LayoutBox.isTableBox(box) || box.display.isNone() ? null : reusable;
        box.hasInteractiveRules = hasInteractive;
        // 持ち主のスタイルのキャッシュが有効なら、状態も祖先・兄弟も前回のままなので疑似要素の規則の当たり方も前回のまま。
        // 疑似要素だけがビューポート単位を使うこともあるので、ビューポートが同じときに限って前回の疑似要素をそのまま使う
        // （再スタイルの経路 restyleBoxRecursive も、汚れていない要素の疑似要素は解き直さない）
        long viewportKey = ((long) scope.viewportW() << 32) | (scope.viewportH() & 0xffffffffL);
        boolean reusePseudos = reusedBox && !needsRestyle && box.pseudoStylesViewport == viewportKey;
        if (!reusePseudos) {
            resolveHighlightPseudos(box, computed, sheet, env, childScope, interactionState);
            box.pseudoStylesViewport = viewportKey;
        }

        // content-visibility: hidden の中身は測らず描かない（箱の木に入れない。疑似要素も同じ）
        boolean skipContents = computed.skipsContents();
        if (skipContents) {
            // 子を持たない箱として扱う
        } else if (reusePseudos) {
            LayoutBox before = previousPseudo(previousChildren, node, PseudoType.BEFORE);
            if (before != null) box.addElementChild(before);
        } else {
            ComputedStyle beforeStyle = resolvePseudoStyle(node, PseudoType.BEFORE, computed, sheet, env, childScope, interactionState);
            if (beforeStyle != null) {
                box.addElementChild(buildPseudoBox(node, PseudoType.BEFORE, beforeStyle, hasInteractive, sheet, env,
                        childScope, interactionState, siblingInvalidation, childReusable, previousChildren));
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
                        computed.fontSizePx(), childScope, interactionState, siblingInvalidation, childReusable));
                siblingForceDirty |= stateChanged;
            }
        }

        if (skipContents) {
            // 同上
        } else if (reusePseudos) {
            LayoutBox after = previousPseudo(previousChildren, node, PseudoType.AFTER);
            if (after != null) box.addElementChild(after);
        } else {
            ComputedStyle afterStyle = resolvePseudoStyle(node, PseudoType.AFTER, computed, sheet, env, childScope, interactionState);
            if (afterStyle != null) {
                box.addElementChild(buildPseudoBox(node, PseudoType.AFTER, afterStyle, hasInteractive, sheet, env,
                        childScope, interactionState, siblingInvalidation, childReusable, previousChildren));
            }
        }

        return box;
    }

    /** 持ち主の前回の子から、id が {@code pseudoId} の疑似要素の箱を探す。無ければ null。 */
    private static LayoutBox previousPseudo(List<LayoutBox> previousChildren, HtmlNode owner, PseudoType type) {
        String pseudoId = null;
        for (LayoutBox previous : previousChildren) {
            if (!previous.node.isPseudoNode) continue;
            if (pseudoId == null) pseudoId = pseudoElementId(owner, type); // 疑似要素の箱がある要素だけ id を作る
            if (pseudoId.equals(previous.node.key())) return previous;
        }
        return null;
    }

    private static void resolveHighlightPseudos(LayoutBox box, ComputedStyle computed,
                                                StyleSheet sheet,
                                                Map<String, String> env, StyleScope childScope,
                                                InteractionState interactionState) {
        HtmlNode node = box.node;
        box.markerStyle = box.display.listItem()
                ? resolvePseudoStyle(node, PseudoType.MARKER, computed, sheet, env, childScope, interactionState)
                : null;
        boolean textInput = "input".equals(node.tag) || "textarea".equals(node.tag);
        box.placeholderStyle = textInput
                ? resolvePseudoStyle(node, PseudoType.PLACEHOLDER, computed, sheet, env, childScope, interactionState)
                : null;
    }

    private static ComputedStyle resolvePseudoStyle(HtmlNode owner, PseudoType type, ComputedStyle ownerStyle,
                                                    StyleSheet sheet,
                                                    Map<String, String> env, StyleScope scope,
                                                    InteractionState interactionState) {
        StateLookup lookup =
                interactionState != null ? interactionState : SelectorMatcher.STATIC_ATTRIBUTES;
        return StyleResolver.resolvePseudoStyle(owner, type, sheet, lookup, ownerStyle, env,
                scope.rootFontSize(), scope.rootLineHeight(), scope.viewportW(), scope.viewportH());
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
                                           InteractionState interactionState, boolean siblingInvalidation) {
        return buildPseudoBox(owner, type, style, ownerInteractive, sheet, env, scope, interactionState,
                siblingInvalidation, null, List.of());
    }

    /**
     * {@code ::before} / {@code ::after} の箱を作る。持ち主の前回の子に同じ id の疑似要素があれば、
     * その合成ノードと箱を使い回す（中の文字は {@link BoxTreeBuilder} が組むたびに入れ直す）。
     */
    private static LayoutBox buildPseudoBox(HtmlNode owner, PseudoType type, ComputedStyle style, boolean ownerInteractive,
                                           StyleSheet sheet,
                                           Map<String, String> env, StyleScope scope,
                                           InteractionState interactionState, boolean siblingInvalidation,
                                           Map<HtmlNode, LayoutBox> reusable, List<LayoutBox> ownerPreviousChildren) {
        String pseudoId = pseudoElementId(owner, type);
        LayoutBox box = null;
        if (reusable != null) {
            for (LayoutBox previous : ownerPreviousChildren) {
                if (previous.node.isPseudoNode && pseudoId.equals(previous.node.key())
                        && previous.node.children.size() == 1) {
                    box = previous;
                    break;
                }
            }
        }

        HtmlNode textNode;
        if (box != null) {
            box.node.parent = owner;
            box.elementChildren.clear();
            box.updateStyle(style);
            textNode = box.node.children.getFirst();
        } else {
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
        box.addElementChild(buildLayoutTree(textNode, style.asMap(), sheet, env, FORCE_SUBTREE, style.fontSizePx(), scope,
                interactionState, siblingInvalidation, reusable));
        return box;
    }
}
