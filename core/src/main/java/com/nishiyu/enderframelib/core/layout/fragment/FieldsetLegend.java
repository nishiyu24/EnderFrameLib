package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

/**
 * fieldset の描画される legend（Engine v3 M2b-1。HTML §15.3.13 の rendered legend。旧 {@code BoxMeasurer.legendSpill}・
 * {@code ScrollExtentCalc.placeRenderedLegend} の移植）。fieldset の最初の legend の子（float でも位置指定でもないもの）は
 * 流れに入らず、縮小して合わせた幅で上の枠線にまたがって置かれる。中身は legend が枠線からはみ出した分（{@link Placed#spill}）だけ下がる。
 */
final class FieldsetLegend {
    private FieldsetLegend() { }

    /** 置いた legend（fieldset の border-box 基準）と、中身を下げる量。 */
    record Placed(PlacedFragment frag, float spill) { }

    /** fieldset の描画される legend（無ければ null）。中身の表示型（ブロック・flex・グリッド）に依らない（中身は匿名の箱が持つ）。 */
    static BoxNode of(BoxNode fieldset) {
        if (fieldset.kind == BoxKind.TEXT || fieldset.kind == BoxKind.NONE || fieldset.kind == BoxKind.CONTENTS
                || fieldset.replaced != null || fieldset.pseudo != BoxNode.Pseudo.NONE || !ElementTraits.isFieldset(fieldset.node.tag)) return null;
        for (BoxNode c : fieldset.children.toList()) {
            if (c.kind == BoxKind.TEXT || c.kind == BoxKind.NONE || !ElementTraits.isLegend(c.node.tag)) continue;
            return OutOfFlow.is(c) || c.style.facts.floating ? null : c;
        }
        return null;
    }

    /**
     * legend を内容ボックスの幅 {@code cw} の中で縮小して合わせ、上の枠線の中央に揃えて置く（{@code top = max(0, (bT − h) / 2)}）。
     * 左右の auto 余白は余りを配る。{@code prior} は fieldset の前回の断片（置いた legend を候補に使う）。
     */
    static Placed place(BoxNode legend, BoxGeometry fg, float cw, LayoutFragment prior, LayoutSession s) {
        BoxGeometry lg = new BoxGeometry(legend.style, cw, s);
        float avail = Math.max(0, cw - lg.mL - lg.mR);
        float lw = lg.specifiedWidth(legend, cw, avail, s);
        if (Float.isNaN(lw)) {
            MinMaxSizes mm = s.cache().minMax(legend, s);
            lw = Math.min(mm.max, Math.max(mm.min, avail));
        }
        lw = LayoutUnit.snap(lg.clampWidth(legend.style, lw, cw, s));
        LayoutFragment f = s.cache().layout(legend, s.space(cw, lw, Float.NaN, Float.NaN, ConstraintSpace.FORMATTING_ROOT), priorOf(prior, legend), s);
        boolean autoL = legend.style.facts.autoMarginL, autoR = legend.style.facts.autoMarginR;
        float free = cw - f.width - (autoL ? 0 : lg.mL) - (autoR ? 0 : lg.mR);
        float ml = autoL && autoR ? Math.max(0, free / 2) : autoL ? Math.max(0, free) : lg.mL;
        float top = Math.max(0, (fg.bT - f.height) / 2);
        float spill = Math.max(0, top + f.height + lg.mB - fg.bT);
        return new Placed(new PlacedFragment(f, LayoutUnit.snap(fg.bL + fg.pL + ml), LayoutUnit.snap(top)), LayoutUnit.snap(spill));
    }

    /** legend を除いた内在サイズに、legend の寄与（余白を含む）を max で合わせる（fieldset は legend より狭くならない）。 */
    static MinMaxSizes withLegend(MinMaxSizes content, BoxNode fieldset, BoxGeometry fg, LayoutSession s) {
        BoxNode legend = of(fieldset);
        if (legend == null) return content;
        BoxGeometry lg = new BoxGeometry(legend.style, Float.NaN, s);
        MinMaxSizes m = lg.contribution(legend.style, s.cache().minMax(legend, s), s);
        float margins = BlockFlow.fixedMargin(legend.style, legend.style.box.marginLeft, s) + BlockFlow.fixedMargin(legend.style, legend.style.box.marginRight, s);
        return new MinMaxSizes(Math.max(content.min, m.min + margins + fg.hdec()), Math.max(content.max, m.max + margins + fg.hdec()));
    }

    private static LayoutFragment priorOf(LayoutFragment prior, BoxNode legend) {
        if (prior == null || prior.oof == null) return null;
        for (PlacedFragment p : prior.oof) if (p.frag().box != null && p.frag().box.node == legend.node) return p.frag();
        return null;
    }
}
