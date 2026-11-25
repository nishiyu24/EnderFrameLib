package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxKind;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.List;

/**
 * 位置指定（Engine v3 M2b-1）。
 *
 * <ul>
 *   <li><b>relative</b>: 流れは変えず、親が子を置く位置にずれ（{@code left/right/top/bottom}）を足す（{@link #relativeOffset}）。</li>
 *   <li><b>absolute / fixed</b>: 流れから外し、静的位置（あるはずだった余白の箱の左上）を {@link Pending} として断片に載せて上へ渡す。
 *       包含ブロック（{@code position} が static でない最も近い祖先、無ければ文書の根）が自分の大きさを決めた後で
 *       {@link #layout} で整形し、{@link PlacedFragment} として自分の断片に持つ（LayoutNG と同じ形。断片は純粋な出力のまま）。</li>
 * </ul>
 *
 * <p>M2b-1 の範囲外: {@code fixed} の包含ブロックはビューポートでなく文書の根（スクロール 0 のときに一致）・{@code sticky}。
 * {@code anchor()} などのアンカー位置指定は {@link AnchorPlacement}（M2b-5）。</p>
 */
final class OutOfFlow {
    private OutOfFlow() { }

    /**
     * 流れの外の箱の静的位置（余白の箱の左上。座標系は持ち主が決める）。flex・grid の容器の子では静的位置が点でなく領域
     * （{@code areaW} × {@code areaH}。NaN なら点）で、箱の大きさが決まった後でその中を {@code alignX} / {@code alignY}
     * （0 = 始点・0.5 = 中央・1 = 終点）で揃える（CSS Flexbox §4.1・CSS Grid §9.4）。
     */
    record Pending(BoxNode box, float x, float y, float areaW, float areaH, float alignX, float alignY) {
        Pending(BoxNode box, float x, float y) {
            this(box, x, y, Float.NaN, Float.NaN, 0, 0);
        }

        Pending shift(float dx, float dy) {
            return dx == 0 && dy == 0 ? this : new Pending(box, x + dx, y + dy, areaW, areaH, alignX, alignY);
        }

        /** 領域の中で、余白の箱の幅 {@code outerW}・高さ {@code outerH} の箱を揃えるずれ {dx, dy}。 */
        float dx(float outerW) {
            return Float.isNaN(areaW) ? 0 : alignX * (areaW - outerW);
        }

        float dy(float outerH) {
            return Float.isNaN(areaH) ? 0 : alignY * (areaH - outerH);
        }
    }

    static final Pending[] NONE = new Pending[0];

    /** 流れの外に出る箱（{@code position: absolute / fixed}）か。 */
    static boolean is(BoxNode b) {
        if (b.kind == BoxKind.NONE || b.kind == BoxKind.TEXT) return false;
        Position p = b.style.box.position;
        return p == Position.ABSOLUTE || p == Position.FIXED;
    }

    /** 流れの外の子孫の包含ブロックになるか（{@code position} が static でない。文書の根は呼び出し側が足す）。 */
    static boolean containsOutOfFlow(Style st) {
        Position p = st.box.position;
        return p != null && p != Position.STATIC;
    }

    /** 流れの外の子孫の振り分け: この箱が置く分（{@code here}）と、上へ渡す分（{@code up}。無ければ null）。 */
    record Split(List<Pending> here, Pending[] up) { }

    /**
     * 流れの外の子孫 {@code pend} を、箱（スタイル {@code st}）が包含ブロックとして置く分と上へ渡す分に分ける。位置指定の箱は
     * {@code absolute} だけを置き、{@code fixed}（包含ブロックはビューポート。新経路では文書の根）は上へ渡す（CSS 2.2 §10.1）。
     * 位置指定でない箱は全てを上へ渡す。
     */
    static Split split(List<Pending> pend, Style st) {
        if (!containsOutOfFlow(st)) return new Split(List.of(), pend.toArray(NONE));
        List<Pending> here = null, up = null;
        for (Pending p : pend) {
            if (p.box().style.box.position == Position.FIXED) {
                if (up == null) up = new java.util.ArrayList<>();
                up.add(p);
            } else {
                if (here == null) here = new java.util.ArrayList<>(pend.size());
                here.add(p);
            }
        }
        return new Split(here == null ? List.of() : here, up == null ? null : up.toArray(NONE));
    }

    /**
     * 置いた流れの外の断片 {@code placed} が上へ渡した {@code fixed}（置いた位置の分ずらして包含ブロックの border-box 基準にする）を
     * {@code up} に足した列。無ければ {@code up} のまま（null もあり得る）。
     */
    static Pending[] withEscaped(Pending[] up, PlacedFragment[] placed) {
        if (placed == null) return up;
        List<Pending> out = null;
        for (PlacedFragment p : placed) {
            Pending[] pend = p.frag().pending;
            if (pend == null) continue;
            if (out == null) {
                out = new java.util.ArrayList<>();
                if (up != null) out.addAll(List.of(up));
            }
            for (Pending q : pend) out.add(q.shift(p.x(), p.y()));
        }
        return out == null ? up : out.toArray(NONE);
    }

    /** {@code position: relative} のずれ {dx, dy}（ずれなしなら null）。{@code cbw}, {@code cbh} は % の基準。 */
    static float[] relativeOffset(Style st, float cbw, float cbh, LayoutSession s) {
        if (st.box.position != Position.RELATIVE) return null;
        float l = inset(st, st.box.left, cbw, s), r = inset(st, st.box.right, cbw, s);
        float t = inset(st, st.box.top, cbh, s), b = inset(st, st.box.bottom, cbh, s);
        float dx = !Float.isNaN(l) ? l : !Float.isNaN(r) ? -r : 0;
        float dy = !Float.isNaN(t) ? t : !Float.isNaN(b) ? -b : 0;
        return dx == 0 && dy == 0 ? null : new float[]{dx, dy};
    }

    /** インセット（{@code left} など）を px へ（auto・宣言なし・基準の無い % は NaN）。 */
    private static float inset(Style st, long v, float base, LayoutSession s) {
        if (!CssLength.isDeclared(v) || CssLength.isAuto(v) || CssLength.isKeyword(v)) return Float.NaN;
        if (CssLength.hasPercent(v, st.box.exprs) && !Float.isFinite(base)) return Float.NaN;
        return LayoutUnit.snap(st.boxPx(v, Float.isFinite(base) ? base : 0, s.viewportW(), s.viewportH()));
    }

    /**
     * 包含ブロックのパディングの箱（border-box 基準の左上 {@code cbX, cbY}・大きさ {@code cbW, cbH}）で、流れの外の箱を整形して置く。
     * {@code prior} は包含ブロックの前回の断片（置いた断片を子の候補に使う）。{@code anchors} は包含ブロックの中のアンカー（M2b-5。
     * {@code anchor()} などを使う箱は {@link AnchorPlacement} が置き、置いた断片は後ろの箱のアンカーになる）。
     */
    static PlacedFragment[] layout(List<Pending> list, float cbX, float cbY, float cbW, float cbH, LayoutFragment prior, Anchors anchors,
                                   LayoutSession s) {
        PlacedFragment[] out = new PlacedFragment[list.size()];
        for (int i = 0; i < out.length; i++) {
            Pending p = list.get(i);
            BoxNode box = p.box();
            Style st = box.style;
            if (AnchorPlacement.applies(st)) {
                out[i] = AnchorPlacement.place(p, cbX, cbY, cbW, cbH, prior, anchors, s);
                if (anchors != null) anchors.placed(out[i]);
                continue;
            }
            BoxGeometry g = new BoxGeometry(st, cbW, s);
            float l = inset(st, st.box.left, cbW, s), r = inset(st, st.box.right, cbW, s);
            float t = inset(st, st.box.top, cbH, s), b = inset(st, st.box.bottom, cbH, s);
            float w = g.specifiedWidth(box, cbW, cbW - (Float.isNaN(l) ? 0 : l) - (Float.isNaN(r) ? 0 : r) - g.mL - g.mR, s);
            if (Float.isNaN(w)) {
                if (!Float.isNaN(l) && !Float.isNaN(r)) {
                    w = cbW - l - r - g.mL - g.mR;
                } else { // 縮小して合わせる: min(max(min-content, 使える幅), max-content)
                    MinMaxSizes mm = s.cache().minMax(box, s);
                    float avail = cbW - (Float.isNaN(l) ? 0 : l) - (Float.isNaN(r) ? 0 : r) - g.mL - g.mR;
                    w = Math.min(Math.max(mm.min, avail), mm.max);
                }
            }
            w = LayoutUnit.snap(g.clampWidth(st, Math.max(0, w), cbW, s));
            float fixedH = g.specifiedHeight(st, cbH, s);
            if (Float.isNaN(fixedH) && !Float.isNaN(t) && !Float.isNaN(b)) fixedH = Math.max(0, cbH - t - b - g.mT - g.mB);
            if (!Float.isNaN(fixedH)) fixedH = LayoutUnit.snap(g.clampHeight(st, fixedH, cbH, s));
            ConstraintSpace space = s.space(cbW, w, fixedH, cbH, ConstraintSpace.FORMATTING_ROOT);
            LayoutFragment f = s.cache().layout(box, space, priorOf(prior, box), s);
            float x = !Float.isNaN(l) ? cbX + l + g.mL : !Float.isNaN(r) ? cbX + cbW - r - f.width - g.mR
                    : p.x() + g.mL + p.dx(f.width + g.mL + g.mR);
            float y = !Float.isNaN(t) ? cbY + t + g.mT : !Float.isNaN(b) ? cbY + cbH - b - f.height - g.mB
                    : p.y() + g.mT + p.dy(f.height + g.mT + g.mB);
            out[i] = new PlacedFragment(f, LayoutUnit.snap(x), LayoutUnit.snap(y));
            if (anchors != null) anchors.placed(out[i]);
        }
        return out;
    }

    static LayoutFragment priorOf(LayoutFragment prior, BoxNode box) {
        if (prior == null || prior.oof == null) return null;
        for (PlacedFragment p : prior.oof) if (p.frag().box != null && p.frag().box.node == box.node) return p.frag();
        return null;
    }
}
