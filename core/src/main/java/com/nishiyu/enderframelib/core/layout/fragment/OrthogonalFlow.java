package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.OrthogonalBoxes;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * 直交フローの根（Engine v3 M2b-5。旧 {@code VerticalWritingEngine} / {@code HorizontalReturnEngine} の移植。CSS Writing Modes §7）。
 *
 * <p>根の流れの中身は包む箱（{@link BoxNode.Pseudo#ORTHOGONAL}）1 つに入っていて、その中は論理（横書き）の軸で組む（子孫の
 * スタイルは入力側が軸を入れ替えてある）。包む箱の {@code transform}（原点は左上）が描画で物理の向きにする。</p>
 *
 * <ul>
 *   <li>縦書きの根: 行の長さ（論理の幅）は根の内容の高さ（指定・包含ブロックの確定した高さ・無ければビューポートの高さ。CSS Writing
 *       Modes §7.3 の直交フローの使える大きさ）。根の幅が auto なら論理のブロック寸法（行を積んだ高さ）。90° の回転は左へ張り出すので
 *       右へずらして置き（行は右から左へ積む）、-90° は下へずらす。転置（{@code vertical-lr}）はずらさない。</li>
 *   <li>横書きへ戻す根（縦書きの中の {@code horizontal-tb}）: 根の大きさは包む箱の寸法を縦横に入れ替えたもの（根の
 *       {@code width} / {@code height} は包む箱が持つ）。包む箱の使える幅は根の % の高さの基準（外の縦書きの論理の高さ）、無ければ
 *       ビューポートの幅。</li>
 * </ul>
 *
 * <p>旧経路と同じ制約: 包む箱の中の位置指定の子孫で包含ブロックが根の外にあるものは、論理の座標のまま上へ渡る。</p>
 */
final class OrthogonalFlow implements FormattingEngine {
    static final OrthogonalFlow INSTANCE = new OrthogonalFlow();

    private OrthogonalFlow() { }

    @Override
    public LayoutFragment layout(BoxNode node, ConstraintSpace space, LayoutFragment prior, LayoutSession s) {
        Style st = node.style;
        float cbw = space.availableWidth;
        BoxGeometry g = new BoxGeometry(st, cbw, s);
        BoxNode wrapper = OrthogonalBoxes.wrapperOf(node);
        boolean ret = OrthogonalBoxes.returnsToHorizontal(node.orthogonal);
        int flow = OrthogonalBoxes.verticalFlow(node.orthogonal);
        boolean transposed = WritingModes.isTransposed(flow), ccw = WritingModes.isCounterClockwise(flow);
        float w, h, dx, dy;
        LayoutFragment wf;
        float ml = g.mL;
        if (!ret) {
            float inline = inlineSize(node, g, space, s);
            float spec = !Float.isNaN(space.fixedWidth) ? space.fixedWidth : g.specifiedWidth(node, cbw, cbw - g.mL - g.mR, s);
            float definiteBlock = Float.isNaN(spec) ? Float.NaN : Math.max(0, g.clampWidth(st, spec, cbw, s) - g.hdec());
            wf = wrapper == null ? null : s.cache().layout(wrapper, s.space(inline, Float.NaN, definiteBlock, definiteBlock,
                    ConstraintSpace.FORMATTING_ROOT), priorOf(prior, wrapper), s);
            float blockSize = wf == null ? 0 : wf.height;
            w = !Float.isNaN(space.fixedWidth) ? space.fixedWidth
                    : g.clampWidth(st, Float.isNaN(spec) ? blockSize + g.hdec() : spec, cbw, s);
            boolean table = node.kind.tableContainer();
            float contentH = table && wf != null ? wf.width : inline;
            h = !Float.isNaN(space.fixedHeight) ? space.fixedHeight : contentH + g.vdec();
            float innerW = Math.max(blockSize, w - g.hdec());
            dx = ccw || transposed ? 0 : innerW;
            dy = ccw && wf != null ? wf.width : 0;
            if (Float.isNaN(spec) && Float.isNaN(space.fixedWidth)) { // 縮小して合わせた幅の箱は auto 余白で寄せない
                ml = g.mL;
            } else if (g.autoML && g.autoMR) {
                ml = Math.max(0, (cbw - w) / 2f);
            } else if (g.autoML) {
                ml = Math.max(0, cbw - w - g.mR);
            }
        } else {
            float pct = space.percentHeightBase;
            float availW = !Float.isNaN(pct) && pct > 0 ? pct : s.viewportW();
            wf = wrapper == null ? null : s.cache().layout(wrapper, s.space(availW, Float.NaN, Float.NaN, Float.NaN,
                    ConstraintSpace.FORMATTING_ROOT), priorOf(prior, wrapper), s);
            float ww = wf == null ? 0 : wf.width, wh = wf == null ? 0 : wf.height;
            w = !Float.isNaN(space.fixedWidth) ? space.fixedWidth : wh + g.hdec();
            h = !Float.isNaN(space.fixedHeight) ? space.fixedHeight : ww + g.vdec();
            dx = !transposed && ccw ? wh : 0;
            dy = !transposed && !ccw ? ww : 0;
        }
        w = LayoutUnit.snap(w);
        h = LayoutUnit.snap(h);
        float insetL = g.bL + g.pL, insetT = g.bT + g.pT;

        List<LayoutFragment> frags = new ArrayList<>(1);
        List<float[]> pos = new ArrayList<>(1);
        List<OutOfFlow.Pending> pend = new ArrayList<>();
        if (wf != null) {
            float x = LayoutUnit.snap(dx), y = LayoutUnit.snap(dy); // 流れの塊の位置は内容ボックス基準
            frags.add(wf);
            pos.add(new float[]{x, y});
            if (wf.pending != null) for (OutOfFlow.Pending p : wf.pending) pend.add(p.shift(insetL + x, insetT + y));
        }
        for (int i = 0, n = node.flow.size(); i < n; i++) { // 根の直下に残した位置指定の子（静的位置は内容ボックスの左上）
            BoxNode k = node.flow.get(i);
            if (k != wrapper && OutOfFlow.is(k)) pend.add(new OutOfFlow.Pending(k, insetL, insetT));
        }
        ChunkedFlow chunks = null;
        if (!frags.isEmpty()) {
            float[] xs = {pos.get(0)[0]}, ys = {pos.get(0)[1]};
            FlowChunk fc = new FlowChunk(null, null, frags.toArray(new LayoutFragment[0]), xs, ys, Math.max(0, h - g.vdec()), 0, 0, 0, 0,
                    false, null, null, 0);
            chunks = new ChunkedFlow(new FlowChunk[]{fc}, new float[]{0}, 0, false);
        }
        OutOfFlow.Pending[] up = null;
        PlacedFragment[] oof = null;
        if (!pend.isEmpty()) {
            OutOfFlow.Split sp = OutOfFlow.split(pend, st); // 位置指定の箱は absolute だけを置き、fixed は上へ渡す
            if (!sp.here().isEmpty()) {
                oof = OutOfFlow.layout(sp.here(), g.bL, g.bT, Math.max(0, w - g.bL - g.bR), Math.max(0, h - g.bT - g.bB), prior,
                        Anchors.of(chunks, null, insetL, insetT), s);
            }
            up = OutOfFlow.withEscaped(sp.up(), oof); // 置いた箱の中の fixed も上へ渡す
        }
        return new LayoutFragment(node, space, w, h, LayoutUnit.snap(ml), Math.max(0, g.mT), Math.min(0, g.mT),
                Math.max(0, g.mB), Math.min(0, g.mB), insetL, insetT, false, chunks, null, up, oof, Float.NaN);
    }

    /**
     * 縦書きの根の行の長さ（内容ボックスの物理の高さ）: 親が決めた高さ・指定の高さ・包含ブロックの確定した高さ（余白を除く）・
     * ビューポートの高さの順（旧 {@code VerticalWritingEngine.inlineSize}）。{@code min-height} / {@code max-height} で挟む。
     */
    private static float inlineSize(BoxNode node, BoxGeometry g, ConstraintSpace space, LayoutSession s) {
        Style st = node.style;
        if (!Float.isNaN(space.fixedHeight)) return Math.max(0, space.fixedHeight - g.vdec());
        float pct = space.percentHeightBase;
        float spec = g.specifiedHeight(st, pct, s);
        float outer;
        if (!Float.isNaN(spec)) outer = spec;
        else if (!Float.isNaN(pct) && pct > 0) outer = pct - g.mT - g.mB;
        else outer = s.viewportH() - g.mT - g.mB;
        return Math.max(0, g.clampHeight(st, outer, pct, s) - g.vdec());
    }

    private static LayoutFragment priorOf(LayoutFragment prior, BoxNode wrapper) {
        if (prior == null || prior.flow == null) return null;
        FlowChunk[] cs = prior.flow.chunks;
        if (cs.length == 0 || cs[0].frags.length == 0) return null;
        LayoutFragment f = cs[0].frags[0];
        return f.box == wrapper ? f : null;
    }

    /**
     * 内在サイズ: 縦書きの根は行を積んだ論理のブロック寸法（行の長さはビューポートの高さか指定の高さ）、横書きへ戻す根は包む箱の論理の
     * 高さ（使える幅はビューポートの幅）。min と max は同じ（根の幅は内容を組んで決まる）。
     */
    @Override
    public MinMaxSizes computeMinMaxSizes(BoxNode node, MinMaxContext ctx, LayoutSession s) {
        Style st = node.style;
        BoxGeometry g = new BoxGeometry(st, Float.NaN, s);
        BoxNode wrapper = OrthogonalBoxes.wrapperOf(node);
        boolean ret = OrthogonalBoxes.returnsToHorizontal(node.orthogonal);
        float size;
        if (!ret) {
            float spec = g.specifiedWidth(st, Float.NaN, s);
            if (!Float.isNaN(spec)) {
                size = spec;
            } else {
                float inline = inlineSize(node, g, s.space(Float.NaN, Float.NaN, Float.NaN, Float.NaN, 0), s);
                LayoutFragment wf = wrapper == null ? null
                        : s.cache().layout(wrapper, s.space(inline, Float.NaN, Float.NaN, Float.NaN, ConstraintSpace.FORMATTING_ROOT), (LayoutFragment) null, s);
                size = (wf == null ? 0 : wf.height) + g.hdec();
            }
        } else {
            LayoutFragment wf = wrapper == null ? null
                    : s.cache().layout(wrapper, s.space(s.viewportW(), Float.NaN, Float.NaN, Float.NaN, ConstraintSpace.FORMATTING_ROOT), (LayoutFragment) null, s);
            size = (wf == null ? 0 : wf.height) + g.hdec();
        }
        size = LayoutUnit.snap(Math.max(0, size));
        return new MinMaxSizes(size, size);
    }

    /** 直交フローの根か（{@link FormattingEngine#of} が先に見る）。 */
    static boolean is(BoxNode n) {
        return n.orthogonal != 0 && n.kind != BoxKind.NONE;
    }
}
