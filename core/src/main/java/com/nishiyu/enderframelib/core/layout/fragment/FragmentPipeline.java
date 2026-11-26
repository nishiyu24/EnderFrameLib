package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.diag.PipelineStage;
import com.nishiyu.enderframelib.core.diag.StageProfiler;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.BoxTreeCompiler;
import com.nishiyu.enderframelib.core.box.StyleSource;
import com.nishiyu.enderframelib.core.css.model.ContainerType;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.ArrayList;
import java.util.List;

/**
 * 新経路の 1 フレーム（Engine v3 M2b-5）: 箱の木を増分で作り（{@link BoxTreeCompiler}）、整形する（{@link FragmentLayout}）。
 *
 * <p>コンテナ問い合わせ（{@code @container}・{@code cqw} などの単位）は 2 段: 整形した後で問い合わせコンテナ（{@code container-type} が
 * normal でない要素）の内容ボックスの寸法を測り、前と違う容器があればその部分木だけを解き直して整形し直す（旧
 * {@code RenderPipeline} と同じく最大 {@link #MAX_CONTAINER_PASSES} 回。寸法は 1 ビューの状態として {@link StyleSource} が記録する）。
 * コンテナを使わない文書は測らない。</p>
 */
public final class FragmentPipeline {
    /** 容器の寸法の確定を繰り返す上限（旧 {@code RenderPipeline.MAX_CONTAINER_PASSES} と同じ）。 */
    static final int MAX_CONTAINER_PASSES = 4;

    private StyleSource styles;
    private final BoxTreeCompiler compiler;
    private final FragmentLayout layout;
    private BoxNode boxRoot;

    public FragmentPipeline(StyleSource styles, LayoutSession session) {
        this.styles = styles;
        this.compiler = new BoxTreeCompiler(styles);
        this.layout = new FragmentLayout(session);
    }

    /**
     * スタイルの解き方を差し替える（ビューポートの大きさの変化）。次のフレームは全ての要素を解き直すが、箱の木は前回と照合して値が同じ箱を
     * 前回の参照のまま使い（{@link BoxTreeCompiler#restyleWith}）、整形は前回の断片木をキャッシュにする（制約が同じ部分木は整形しない）。
     */
    public void restyleWith(StyleSource styles) {
        this.styles = styles;
        compiler.restyleWith(styles);
    }

    public BoxTreeCompiler compiler() { return compiler; }
    public FragmentLayout layout() { return layout; }
    /** 直前のフレームの箱の木。 */
    public BoxNode boxRoot() { return boxRoot; }

    /**
     * 要素 {@code root} の部分木だけを、一から組む（HUD の追跡表示など文書の一部を別の面に描く用）。根の幅は根自身の指定に従う
     * （文書の根と違ってビューポートの幅に固定しない）。
     */
    public LayoutFragment frameSubtree(HtmlNode root, int viewportW, int viewportH) {
        if (styles.sheet() != null) layout.session().positionTryRules(styles.sheet().positionTryRules);
        boxRoot = compiler.compile(root, null);
        return layout.layout(boxRoot, viewportW, viewportH, false);
    }

    /** 1 フレーム。{@code ledger} は凍結済みの台帳（null なら全体を作り直す）。 */
    public LayoutFragment frame(HtmlNode domRoot, ChangeLedger ledger, int viewportW, int viewportH) {
        if (styles.sheet() != null) layout.session().positionTryRules(styles.sheet().positionTryRules);
        long t0 = StageProfiler.begin(PipelineStage.BOX_TREE);
        boxRoot = compiler.compile(domRoot, ledger);
        StageProfiler.end(PipelineStage.BOX_TREE, t0);
        long t1 = StageProfiler.begin(PipelineStage.LAYOUT);
        LayoutFragment f = layout.layout(boxRoot, viewportW, viewportH);
        StageProfiler.end(PipelineStage.LAYOUT, t1);
        if (!styles.usesContainers()) return f;
        for (int pass = 0; pass < MAX_CONTAINER_PASSES; pass++) {
            List<HtmlNode> changed = new ArrayList<>();
            measure(f, changed);
            if (changed.isEmpty()) break;
            boxRoot = compiler.restyleContainers(domRoot, changed);
            f = layout.layout(boxRoot, viewportW, viewportH);
        }
        return f;
    }

    /** 問い合わせコンテナの内容ボックスの寸法を記録し、前と違った容器を {@code changed} に足す。 */
    private void measure(LayoutFragment f, List<HtmlNode> changed) {
        BoxNode b = f.box;
        if (b != null && b.pseudo == BoxNode.Pseudo.NONE && b.node.nodeType == NodeType.ELEMENT) {
            ContainerType t = b.computed.containerType();
            if (t != ContainerType.NORMAL) {
                BoxGeometry g = new BoxGeometry(b.style, f.space.availableWidth, layout.session());
                float w = Math.max(0, f.width - g.hdec());
                float h = t == ContainerType.INLINE_SIZE ? Float.NaN : Math.max(0, f.height - g.vdec());
                if (styles.setContainerSize(b.node, w, h)) changed.add(b.node);
            }
        }
        if (f.lines != null) {
            for (LineFragment l : f.lines) for (PlacedFragment a : l.atoms()) measure(a.frag(), changed);
        }
        if (f.flow != null) f.flow.forEach((c, x, y) -> measure(c, changed));
        if (f.oof != null) for (PlacedFragment p : f.oof) measure(p.frag(), changed);
    }
}
