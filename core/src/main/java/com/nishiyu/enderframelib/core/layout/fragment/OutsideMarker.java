package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

/**
 * 流れの外の {@code ::marker}（{@code list-style-position: outside}。Engine v3 M2b-1。旧 {@code BlockLayoutEngine.placeOutsideMarkers}
 * の移植）。マーカーは縮小して合わせた幅（折り返さない {@code white-space: pre}）で独立に整形し、リスト項目の border-box の左外に、
 * 右の余白の端が項目の左端に接するように置く。縦は項目の最初の行のベースラインへマーカーのベースラインを揃え、行が無ければ内容の上端。
 * 項目の内在サイズ・高さ・ベースラインには寄与しない。置いた断片は項目の {@link LayoutFragment#oof} に持つ（border-box 基準）。
 */
final class OutsideMarker {
    private OutsideMarker() { }

    /** {@code item}（整形済みの {@code laidOut}）の外側のマーカーを置く（無ければ null）。{@code cw} は項目の内容の幅。 */
    static PlacedFragment place(BoxNode item, LayoutFragment laidOut, float cw, LayoutFragment prior, LayoutSession s) {
        BoxNode m = item.outsideMarker();
        if (m == null || m.kind == BoxKind.NONE) return null;
        BoxGeometry mg = new BoxGeometry(m.style, cw, s);
        float w = LayoutUnit.snap(mg.clampWidth(m.style, s.cache().minMax(m, s).max, cw, s));
        LayoutFragment f = s.cache().layout(m, s.space(cw, w, Float.NaN, Float.NaN, ConstraintSpace.FORMATTING_ROOT), priorOf(prior, m), s);
        float base = InlineFlow.baselineOf(laidOut, true), own = InlineFlow.baselineOf(f, true);
        float y = !Float.isNaN(base) && !Float.isNaN(own) ? base - own : laidOut.insetTop;
        return new PlacedFragment(f, LayoutUnit.snap(-(f.width + mg.mR)), LayoutUnit.snap(y));
    }

    /** {@code extra} を先頭に足した流れの外の断片の並び。 */
    static PlacedFragment[] prepend(PlacedFragment extra, PlacedFragment[] oof) {
        PlacedFragment[] all = new PlacedFragment[1 + (oof == null ? 0 : oof.length)];
        all[0] = extra;
        if (oof != null) System.arraycopy(oof, 0, all, 1, oof.length);
        return all;
    }

    private static LayoutFragment priorOf(LayoutFragment prior, BoxNode marker) {
        if (prior == null || prior.oof == null) return null;
        for (PlacedFragment p : prior.oof) {
            BoxNode b = p.frag().box;
            if (b != null && b.pseudo == BoxNode.Pseudo.MARKER && b.node == marker.node) return p.frag();
        }
        return null;
    }
}
