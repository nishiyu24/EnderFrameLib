package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.dom.InteractionState;
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
 * <p>スタイルの解き方（{@link StyleSource}）はビューポート・環境変数・規則の版を持つので、そのどれかが変わったら作り直して全体を組む。
 * それ以外のフレームは台帳に載った変化だけを、前回の箱の木・断片木・描画用の木・描画の記憶から増分で作る。</p>
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

    /**
     * 1 フレームを作る。{@code ledger} はこのフレームの凍結済みの台帳（null なら全体を作り直す）。ビューポート・環境変数・規則・書体・
     * スクロールの状態の入れ物が前回と違えば、全体を作り直す。
     */
    List<DisplayItem> frame(Document doc, ChangeLedger ledger, int w, int h, float devicePixelRatio, Map<String, String> envNow,
                            InteractionState state, ScrollState scrollState, FontResolver fontResolver) {
        boolean rebuild = pipeline == null || w != width || h != height || doc.sheet != sheet || !Objects.equals(envNow, env)
                || fontResolver != fonts || scrollState != scroll || Float.compare(devicePixelRatio, dpr) != 0;
        if (rebuild) {
            width = w;
            height = h;
            sheet = doc.sheet;
            env = envNow == null ? Map.of() : Map.copyOf(envNow);
            fonts = fontResolver;
            scroll = scrollState;
            dpr = devicePixelRatio;
            LayoutSession session = new LayoutSession(fontResolver);
            pipeline = new FragmentPipeline(new StyleSource(doc.sheet, state, env, w, h), session);
            tree = new PaintTreeBuilder(session);
            painter = new TreePainter(w, h, doc.sheet != null ? doc.sheet.keyframes : Map.of(), scrollState);
            painter.setDevicePixelRatio(devicePixelRatio);
            ledger = null;
        }
        LayoutFragment frag = pipeline.frame(doc.root, ledger, w, h);
        root = tree.build(frag, pipeline.boxRoot());
        return painter.paint(root, ledger);
    }

    /** 直近のフレームの描画用の木の根（スクロールの位置を含む見え方。問い合わせが読む。まだ無ければ null）。 */
    PaintView root() {
        return root != null ? PaintView.root(root, scroll) : null;
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

    /** 文書を入れ替えた（前回の木を捨てて次のフレームで全体を作る）。 */
    void reset() {
        pipeline = null;
        root = null;
    }
}
