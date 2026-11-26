package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.ChangeKind;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.fragment.FragmentPipeline;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutFragment;
import com.nishiyu.enderframelib.core.layout.fragment.LayoutSession;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.TreePainter;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ScrollContentInfo;
import com.nishiyu.enderframelib.core.paint.tree.PaintNode;
import com.nishiyu.enderframelib.core.paint.tree.PaintTreeBuilder;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 新経路（Engine v3）の 1 ビューの 1 フレーム（M2c ④）: 凍結した変更台帳 → 箱の木（{@code BoxTreeCompiler}）→ 断片（{@code FragmentLayout}）→
 * 描画用の木（{@link PaintTreeBuilder}）→ 表示リスト（{@link TreePainter}）。入口は台帳 1 本で、旧経路の再スタイル・位置の決め直し・
 * 文字の差分・部分構築の分かれ道は持たない。状態はこのビューに閉じる（プロセス共有はしない）。
 *
 * <p>スタイルの解き方（{@link StyleSource}）はビューポート・環境変数・規則の版を持つ。環境変数・規則・書体・装置の画素比が変わったら
 * 作り直して全体を組む。ビューポートの大きさだけが変わったら解き方だけを差し替え（全ての要素を解き直すが、箱・断片・描画用の木は前回と
 * 照合して使い回す。描画の記憶はビューポートに依るので捨てる）、それ以外のフレームは台帳に載った変化だけを、前回の箱の木・断片木・
 * 描画用の木・描画の記憶から増分で作る。</p>
 */
final class V3Frame {
    private FragmentPipeline pipeline;
    private PaintTreeBuilder tree;
    private TreePainter painter;
    private StyleSheet sheet;
    private Map<String, String> env;
    private FontResolver fonts;
    private int width = -1, height = -1;
    private float dpr = 1f;
    private ScrollState scroll;
    private PaintNode root;
    /** 次の全体の作り直しに渡す、作り直す前の要素の算出スタイル（{@link #rebuildKeepingHistory}。そのフレームだけ持つ）。 */
    private Map<HtmlNode, com.nishiyu.enderframelib.core.css.Style> history;

    /**
     * 1 フレームを作る。{@code ledger} はこのフレームの凍結済みの台帳（null なら全体を作り直す）。環境変数・規則・書体・装置の画素比・
     * スクロールの状態の入れ物が前回と違えば、全体を作り直す。ビューポートの大きさだけの変化は増分で組む。
     *
     * <p>描画用の木が前回と同じ実体で、台帳が再スタイルとスクロールだけなら null を返す（前回の表示リストがスクロールの移動量を除いて
     * そのまま使える。描画器が木の外で読む DOM の値〔属性・文字〕の変化は台帳の別の種別で来るので、ここに入らない）。呼ぶ側は
     * スクロールの移動で済ませるか、{@link #paint} で描く。</p>
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
            painter.setViewport(w, h);
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
            tree = new PaintTreeBuilder(session, doc.sheet);
            painter = new TreePainter(w, h, doc.sheet != null ? doc.sheet.keyframes : Map.of(), scrollState);
            painter.setDevicePixelRatio(devicePixelRatio);
            ledger = null;
        }
        LayoutFragment frag = pipeline.frame(doc.root, ledger, w, h);
        if (fresh != null) fresh.history(null); // 作り直す前の値はこのフレームだけ
        PaintNode prev = root;
        long t0 = StageProfiler.begin(PipelineStage.PAINT_TREE);
        root = tree.build(frag, pipeline.boxRoot());
        StageProfiler.end(PipelineStage.PAINT_TREE, t0);
        // ビューポートが変われば木が同じでも描き直す（キャンバスの背景・固定配置・vw の描画だけの値）
        if (ledger != null && !resized && root == prev && onlyRestyleOrScroll(ledger)) return null;
        return paint(ledger);
    }

    /**
     * 台帳を凍結する前の準備: DOM の変更を規則の範囲（兄弟・祖先・{@code :has()}）へ広げる無効化セットを引くシートを結び、{@code :has()} の
     * 規則があれば汚れた節の祖先を台帳に足す（凍結の後に足すと次のフレームの世代に入り、1 フレーム遅れる）。
     */
    static void beforeFreeze(InteractionState state, StyleSheet sheet) {
        state.bindStyleSheet(sheet);
        if (sheet != null && sheet.usesHasSelectors()) state.propagateDirtyToAncestors();
        else state.discardUnscopedLog();
    }

    /** 直近のフレームの描画用の木を描く（{@link #frame} が null を返したあと、スクロールの移動で済ませられないとき）。 */
    List<DisplayItem> paint(ChangeLedger ledger) {
        long t0 = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
        try {
            return painter.paint(root, ledger);
        } finally {
            StageProfiler.end(PipelineStage.DISPLAY_LIST, t0);
        }
    }

    private static boolean onlyRestyleOrScroll(ChangeLedger ledger) {
        if (ledger.frozenOverflow()) return false;
        for (int i = 0, n = ledger.frozenSize(); i < n; i++) {
            if (!ledger.frozenIs(i, ChangeKind.STYLE) && !ledger.frozenIs(i, ChangeKind.STYLE_SUBTREE) && !ledger.frozenIs(i, ChangeKind.SCROLL)) return false;
            if (ledger.frozenNode(i) == null && ledger.frozenIs(i, ChangeKind.STYLE)) return false; // 絞れない再スタイル
        }
        return true;
    }

    /** 直近のフレームの描画用の木の根（スクロールの位置を含む見え方。問い合わせが読む。まだ無ければ null）。 */
    PaintView root() {
        return root != null ? PaintView.root(root, scroll, width, height) : null;
    }

    /** 直近のフレームで中身を 1 つの節にまとめたスクロールコンテナ（スクロールだけの更新で節を動かす）。 */
    List<ScrollContentInfo> scrollContents() {
        return painter != null ? painter.scrollContents() : List.of();
    }

    /** 直近のフレームで {@code node} の箱（無ければ null）。 */
    com.nishiyu.enderframelib.core.layout.PaintBox boxOf(HtmlNode node) {
        PaintView r = root();
        return r != null ? UiFocus.findBox(r, node) : null;
    }

    /**
     * 要素 {@code node} の算出スタイル（CSSOM の {@code getComputedStyle}）。最新の箱の木は DOM の子と 1 対 1 なので、根からの子の位置を
     * 辿る（{@code display: none} の要素も箱を持つ。その子孫と、まだ組んでいない節は null）。
     */
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

    /**
     * 前回の木と記憶を捨てて次のフレームで全体を作る（書体の登録の変化など台帳に載らない全体の変化・全再計算の計測）。文書は同じなので、
     * 作り直す前の要素の算出スタイルだけをトランジションの始まりの入力として渡す（捨てると遷移の途中のフレームが終点へ飛ぶ）。
     */
    void rebuildKeepingHistory() {
        Map<HtmlNode, com.nishiyu.enderframelib.core.css.Style> before = pipeline != null && pipeline.boxRoot() != null
                ? com.nishiyu.enderframelib.core.box.BoxTreeCompiler.elementStyles(pipeline.boxRoot()) : null;
        reset();
        history = before;
    }

    /** 文書を入れ替えた（前回の木を捨てて次のフレームで全体を作る）。 */
    void reset() {
        history = null;
        pipeline = null;
        root = null;
    }
}
