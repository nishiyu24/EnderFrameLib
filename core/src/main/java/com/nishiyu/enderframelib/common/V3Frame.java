package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.box.RememberedScroll;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.ChangeKind;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.fragment.AnchorScroll;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentPipeline;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.SceneDisplayListAdapter;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.scene.DisplayScene;
import com.nishiyu.enderframelib.core.paint.scene.DynamicSceneState;
import com.nishiyu.enderframelib.core.paint.scene.SceneAnchorFollow;
import com.nishiyu.enderframelib.core.paint.scene.SceneEmitter;
import com.nishiyu.enderframelib.core.paint.scene.SceneLayer;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 新経路（Engine v3）の 1 ビューの 1 フレーム（M3b-2）: 凍結した変更台帳 → 箱の木（{@code BoxTreeCompiler}）→ 断片
 * （{@code FragmentLayout}）→ {@link DisplayScene}（{@link SceneEmitter}）→ 表示リスト（一時アダプタ {@link SceneDisplayListAdapter}）。
 * 入口は台帳 1 本で、状態はこのビューに閉じる（プロセス共有はしない）。
 *
 * <p>{@link DisplayScene} は親相対座標・スクロール 0・unstuck で不変のため、スクロールだけの変化では {@code DisplayScene} を作り直さず
 * {@link DynamicSceneState} を渡して描く。台帳の {@code SCROLL} 祖先の照合は不要になった。</p>
 */
final class V3Frame {
    private FragmentPipeline pipeline;
    private SceneEmitter emitter;
    private SceneDisplayListAdapter adapter;
    private StyleSheet sheet;
    private Map<String, String> env;
    private FontResolver fonts;
    private int width = -1, height = -1;
    private float dpr = 1f;
    private ScrollState scroll;
    private DisplayScene scene;
    /**
     * 流れの外の要素のアンカーの覚えたスクロール量（G8。計画書 §3.2「G8 の後半の設計」）。ビューの履歴で、アンカーの再計算点
     * （{@link #settleAnchors}）でだけ書き換え、台帳の {@link ChangeKind#ANCHOR} で箱の木へ入る。全体の作り直しをまたいで持ち、文書の
     * 入れ替えで捨てる。
     */
    private final Map<HtmlNode, RememberedScroll> remembered = new java.util.IdentityHashMap<>();
    /** 次の全体の作り直しに渡す、作り直す前の要素の算出スタイル（{@link #rebuildKeepingHistory}。そのフレームだけ持つ）。 */
    private Map<HtmlNode, com.nishiyu.enderframelib.core.css.Style> history;

    /**
     * 1 フレームを作る。{@code ledger} はこのフレームの凍結済みの台帳（null なら全体を作り直す）。環境変数・規則・書体・装置の画素比・
     * スクロールの状態の入れ物が前回と違えば、全体を作り直す。ビューポートの大きさだけの変化は増分で組む。
     *
     * <p>シーンの根が前回と同じ実体で、台帳が再スタイルとスクロールだけなら null を返す（前回の表示リストがスクロールの移動量を除いて
     * そのまま使える）。呼ぶ側はスクロールの移動で済ませるか、{@link #paint} で描く。</p>
     */
    List<DisplayItem> frame(Document doc, ChangeLedger ledger, int w, int h, float devicePixelRatio, Map<String, String> envNow,
                            InteractionState state, ScrollState scrollState, FontResolver fontResolver) {
        boolean rebuild = pipeline == null || doc.sheet != sheet || !Objects.equals(envNow == null ? Map.of() : envNow, env)
                || fontResolver != fonts || scrollState != scroll || Float.compare(devicePixelRatio, dpr) != 0;
        boolean resized = !rebuild && (w != width || h != height);
        if (resized) {
            width = w;
            height = h;
            pipeline.restyleWith(new StyleSource(doc.sheet, state, env, w, h));
            adapter.setViewport(w, h);
        }
        StyleSource fresh = null;
        if (rebuild) {
            width = w;
            height = h;
            sheet = doc.sheet;
            env = envNow == null ? Map.of() : Map.copyOf(envNow);
            fonts = fontResolver;
            scroll = scrollState;
            dpr = devicePixelRatio;
            LayoutSession session = new LayoutSession(fontResolver);
            fresh = new StyleSource(doc.sheet, state, env, w, h);
            fresh.history(history);
            history = null;
            pipeline = new FragmentPipeline(fresh, session);
            pipeline.compiler().remembered(remembered);
            Map<String, com.nishiyu.enderframelib.core.css.model.Keyframes> kf = doc.sheet != null ? doc.sheet.keyframes : Map.of();
            emitter = new SceneEmitter(session, kf);
            com.nishiyu.enderframelib.core.paint.LayerOps layerOps = new com.nishiyu.enderframelib.core.paint.LayerOps(w, h, kf);
            layerOps.setDevicePixelRatio(devicePixelRatio);
            emitter.opsSource(layerOps);
            adapter = new SceneDisplayListAdapter(w, h, kf);
            adapter.setDevicePixelRatio(devicePixelRatio);
            ledger = null;
        }
        LayoutFragment frag = pipeline.frame(doc.root, ledger, w, h);
        if (fresh != null) fresh.history(null);
        SceneLayer prevRoot = scene != null ? scene.root : null;
        long t0 = StageProfiler.begin(PipelineStage.PAINT_TREE);
        scene = emitter.emit(frag, pipeline.boxRoot(), w, h);
        StageProfiler.end(PipelineStage.PAINT_TREE, t0);
        settleAnchors(doc, w, h);
        if (ledger != null && !resized && scene.root == prevRoot && onlyRestyleOrScroll(ledger)) return null;
        return paint();
    }

    /**
     * アンカーの再計算点（G8。CSS Anchor Positioning 1 §3.3・§6.5）。
     */
    private void settleAnchors(Document doc, int w, int h) {
        if (remembered.isEmpty() && !SceneAnchorFollow.any(scene)) return;
        List<SceneLayer> nodes = new java.util.ArrayList<>();
        SceneAnchorFollow.collect(scene, nodes);
        Map<HtmlNode, RememberedScroll> keep = new java.util.IdentityHashMap<>();
        ChangeLedger recalc = null;
        for (SceneLayer n : nodes) {
            HtmlNode el = n.node;
            if (el == null || el.isPseudoNode) continue;
            AnchorScroll a = n.follow;
            RememberedScroll known = remembered.get(el);
            RememberedScroll now = SceneAnchorFollow.snapshot(a, scroll);
            boolean point = known == null ? !now.zero() : SceneAnchorFollow.needsRecalculation(a, scroll);
            keep.put(el, point || known == null ? now : known);
            if (point) {
                if (recalc == null) recalc = new ChangeLedger();
                recalc.append(el, ChangeKind.ANCHOR);
            }
        }
        remembered.clear();
        remembered.putAll(keep);
        if (recalc == null) return;
        recalc.freeze();
        long t0 = StageProfiler.begin(PipelineStage.PAINT_TREE);
        LayoutFragment frag = pipeline.frame(doc.root, recalc, w, h);
        scene = emitter.emit(frag, pipeline.boxRoot(), w, h);
        StageProfiler.end(PipelineStage.PAINT_TREE, t0);
    }

    /**
     * 要素 {@code root} の部分木だけを、ビューポート {@code w}×{@code h} の根として一から組んで描く（{@code FrameRender#subtree}）。
     */
    List<DisplayItem> subtree(HtmlNode root, StyleSheet sheet, int w, int h, ScrollState scrollState, FontResolver fontResolver) {
        width = w;
        height = h;
        this.sheet = sheet;
        scroll = scrollState;
        fonts = fontResolver;
        LayoutSession session = new LayoutSession(fontResolver);
        pipeline = new FragmentPipeline(new StyleSource(sheet, new InteractionState(), Map.of(), w, h), session);
        remembered.clear();
        Map<String, com.nishiyu.enderframelib.core.css.model.Keyframes> kf = sheet != null ? sheet.keyframes : Map.of();
        emitter = new SceneEmitter(session, kf);
        emitter.opsSource(new com.nishiyu.enderframelib.core.paint.LayerOps(w, h, kf));
        adapter = new SceneDisplayListAdapter(w, h, kf);
        LayoutFragment frag = pipeline.frameSubtree(root, w, h);
        this.scene = emitter.emit(frag, pipeline.boxRoot(), w, h);
        return paint();
    }

    /**
     * 台帳を凍結する前の準備: DOM の変更を規則の範囲（兄弟・祖先・{@code :has()}）へ広げる無効化セットを引くシートを結び、{@code :has()} の
     * 規則があれば汚れた節の祖先を台帳に足す。
     */
    void beforeFreeze(InteractionState state, StyleSheet sheet) {
        state.bindStyleSheet(sheet);
        if (sheet != null && sheet.usesHasSelectors()) state.propagateDirtyToAncestors();
        else state.discardUnscopedLog();
    }

    /** 直近のシーンから表示リストを作る（{@link #frame} が null を返したあと、スクロールの移動で済ませられないとき）。 */
    List<DisplayItem> paint() {
        long t0 = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
        try {
            return adapter.emit(scene, DynamicSceneState.of(scroll, width, height));
        } finally {
            StageProfiler.end(PipelineStage.DISPLAY_LIST, t0);
        }
    }

    private static boolean onlyRestyleOrScroll(ChangeLedger ledger) {
        if (ledger.frozenOverflow()) return false;
        for (int i = 0, n = ledger.frozenSize(); i < n; i++) {
            if (!ledger.frozenIs(i, ChangeKind.STYLE) && !ledger.frozenIs(i, ChangeKind.STYLE_SUBTREE) && !ledger.frozenIs(i, ChangeKind.SCROLL)) return false;
            if (ledger.frozenNode(i) == null && ledger.frozenIs(i, ChangeKind.STYLE)) return false;
        }
        return true;
    }

    /** 前回描いた時点（{@code atBuild}）から、シーン内のスクロールコンテナの位置が変わったか（非スクロール要素の値は描画に効かない）。 */
    boolean hasEffectiveScrollChange(ScrollState state, Map<HtmlNode, float[]> atBuild) {
        if (state == null || scene == null) return false;
        boolean[] changed = {false};
        state.changedSince(atBuild, n -> {
            if (changed[0] || n == null) return;
            SceneLayer l = scene.layerByNodeId(n.nodeId());
            if (l != null && l.isScrollContainer()) changed[0] = true;
        });
        return changed[0];
    }

    /** 覚えたスクロール量の表（G8）。 */
    Map<HtmlNode, RememberedScroll> remembered() {
        return java.util.Collections.unmodifiableMap(remembered);
    }

    /** 直近のフレームの描画シーン（まだ無ければ null）。 */
    DisplayScene scene() {
        return scene;
    }

    /** 直近のシーンがあるか。 */
    boolean hasScene() {
        return scene != null;
    }

    /** 直近のフレームに対する動的状態（スクロール位置・ビューポート寸法。シーンが無ければ null）。 */
    DynamicSceneState dynamicState(ScrollState currentScroll) {
        return scene != null ? DynamicSceneState.of(currentScroll != null ? currentScroll : scroll, width, height) : null;
    }

    /** シーン生成器（テストが再利用数を読む。まだ無ければ null）。 */
    SceneEmitter emitter() {
        return emitter;
    }

    /** 直近のフレームで中身を 1 つの節にまとめたスクロールコンテナ（スクロールだけの更新で節を動かす）。 */
    List<ScrollContentInfo> scrollContents() {
        return adapter != null ? adapter.scrollContents() : List.of();
    }

    /** 要素 {@code node} の算出スタイル（CSSOM の {@code getComputedStyle}）。 */
    com.nishiyu.enderframelib.core.css.ComputedStyle styleOf(HtmlNode node) {
        com.nishiyu.enderframelib.core.box.BoxNode b = pipeline != null ? pipeline.boxRoot() : null;
        if (b == null || node == null) return null;
        java.util.ArrayDeque<HtmlNode> path = new java.util.ArrayDeque<>();
        for (HtmlNode cur = node; cur != null && cur != b.node; cur = cur.parent) path.push(cur);
        HtmlNode parent = b.node;
        for (HtmlNode cur : path) {
            int i = parent == null ? -1 : parent.children.indexOf(cur);
            if (i < 0 || i >= b.children.size()) return null;
            b = b.children.get(i);
            parent = cur;
        }
        return b.node == node && b.kind != com.nishiyu.enderframelib.core.box.BoxKind.TEXT ? new com.nishiyu.enderframelib.core.css.ComputedStyle(b.computed) : null;
    }

    /** 前回の木と記憶を捨てて次のフレームで全体を作る（トランジションの履歴と覚えたスクロール量は引き継ぐ）。 */
    void rebuildKeepingHistory() {
        Map<HtmlNode, com.nishiyu.enderframelib.core.css.Style> before = pipeline != null && pipeline.boxRoot() != null
                ? com.nishiyu.enderframelib.core.box.BoxTreeCompiler.elementStyles(pipeline.boxRoot()) : null;
        Map<HtmlNode, RememberedScroll> keepScroll = new java.util.IdentityHashMap<>(remembered);
        reset();
        history = before;
        remembered.putAll(keepScroll);
    }

    /** 文書を入れ替えた（前回の木を捨てて次のフレームで全体を作る）。 */
    void reset() {
        history = null;
        remembered.clear();
        pipeline = null;
        scene = null;
    }
}
