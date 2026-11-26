package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.css.model.Position;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.Map;

/**
 * 断片の到達範囲（Engine v3 M2b-1。旧 {@code ScrollExtentCalc.updateScrollExtent} の移植）。断片木から求める純粋な関数で、結果は
 * {@code {scrollWidth, scrollHeight, 子孫のはみ出しの右端, 下端}}（どれも自分の border-box の左上が基準）。
 *
 * <ul>
 *   <li>中身の到達範囲: 流れの子と行の原子・インライン要素の箱の margin-box の右端と下端、文字片の右端と下端（ベースライン ＋ descent
 *       も含む）。最後の流れの子の下の余白は、余白が親を突き抜けるときは含めない。絶対配置の子は含めない。</li>
 *   <li>子孫のはみ出し: 子の border-box と、子が overflow を切らなければ子のはみ出し。</li>
 *   <li>{@code scroll = max(自分の大きさ, 中身の到達範囲 ＋ 終わりの padding と border, 子孫のはみ出し)}。</li>
 * </ul>
 *
 * <p>描画（M2c）の入力になる値で、今は一時アダプタが旧描画の {@code scrollWidth} などへ写す。1 回の写しの間だけの表
 * {@code memo}（断片 → 結果）で子の結果を使い回す（断片は不変なので値は変わらない）。</p>
 */
public final class ScrollableOverflow {
    private ScrollableOverflow() { }

    /** {@code f} の {@code {scrollWidth, scrollHeight, 子孫のはみ出しの右端, 下端}}。 */
    public static float[] of(LayoutFragment f, Map<LayoutFragment, float[]> memo, LayoutSession s) {
        float[] r = memo.get(f);
        if (r != null) return r;
        r = compute(f, memo, s);
        memo.put(f, r);
        return r;
    }

    private static float[] compute(LayoutFragment f, Map<LayoutFragment, float[]> memo, LayoutSession s) {
        float w = f.width, h = f.height;
        if (f.box == null) return new float[]{w, h, w, h};
        Style st = f.box.style;
        BoxGeometry g = new BoxGeometry(st, f.space.availableWidth, s);
        Acc a = new Acc(f.insetLeft, f.insetTop, w, h);
        boolean any = false;
        if (f.lines != null) {
            lines(f.lines, f.insetLeft, f.insetTop, a, memo, s);
            any = true;
        }
        if (f.flow != null) {
            boolean escapes = !BoxGeometry.establishesBfc(st) && g.pB == 0 && g.bB == 0 && Float.isNaN(g.specifiedHeight(st, Float.NaN, s));
            FlowChunk[] cs = f.flow.chunks;
            int lastK = -1, lastI = -1;
            if (escapes) {
                for (int k = cs.length - 1; k >= 0 && lastK < 0; k--) {
                    for (int i = cs[k].frags.length - 1; i >= 0; i--) {
                        LayoutFragment c = cs[k].frags[i];
                        if (c.box != null && c.box.kind.blockLevel()) {
                            lastK = k;
                            lastI = i;
                            break;
                        }
                    }
                }
            }
            for (int k = 0; k < cs.length; k++) {
                FlowChunk c = cs[k];
                for (int i = 0; i < c.frags.length; i++) {
                    LayoutFragment ch = c.frags[i];
                    float x = f.insetLeft + c.x[i], y = f.insetTop + f.flow.y(k, i);
                    if (ch.box == null) { // 混在の流れの匿名の塊: 中の行
                        if (ch.lines != null) lines(ch.lines, x, y, a, memo, s);
                        continue;
                    }
                    if (ch.box.pseudo == BoxNode.Pseudo.ORTHOGONAL) {
                        orthogonal(f.box, ch, x, y, a, memo, s);
                    } else {
                        child(ch, x, y, k == lastK && i == lastI, a, memo, s);
                    }
                    any = true;
                }
            }
        }
        if (f.oof != null) { // 流れの外の断片のうち、絶対配置でないもの（fieldset の legend・外側のマーカー）
            for (PlacedFragment p : f.oof) {
                BoxNode b = p.frag().box;
                Position pos = b == null ? null : b.style.box.position;
                if (pos == Position.ABSOLUTE || pos == Position.FIXED) continue;
                child(p.frag(), p.x(), p.y(), false, a, memo, s);
                any = true;
            }
        }
        boolean visible = (st.box.overflowX == null || st.box.overflowX == Overflow.VISIBLE)
                && (st.box.overflowY == null || st.box.overflowY == Overflow.VISIBLE);
        // 枠線を重ねる表（クリップしない）: ブラウザはセルの端まで測る（外周の枠線の半分と padding を足さず、箱の大きさで下駄を履かせない。
        // 旧 ScrollExtentCalc の isCollapsedTable と同じ。G9）
        if (visible && TableBorders.collapses(f.box)) return new float[]{a.extR, a.extB, a.descR, a.descB};
        if (!any) return new float[]{Math.max(w, g.pL + g.bL + g.pR + g.bR), Math.max(h, g.pT + g.bT + g.pB + g.bB), w, h};
        return new float[]{Math.max(Math.max(w, a.extR + g.pR + g.bR), a.descR), Math.max(Math.max(h, a.extB + g.pB + g.bB), a.descB),
                a.descR, a.descB};
    }

    /** 中身の到達範囲（{@code ext*}）と子孫のはみ出し（{@code desc*}）の集計。 */
    private static final class Acc {
        float extR, extB, descR, descB;

        Acc(float insetL, float insetT, float w, float h) {
            extR = insetL;
            extB = insetT;
            descR = w;
            descB = h;
        }

        void ext(float r, float b) {
            extR = Math.max(extR, r);
            extB = Math.max(extB, b);
        }

        void desc(float r, float b) {
            descR = Math.max(descR, r);
            descB = Math.max(descB, b);
        }
    }

    /** 箱の子 {@code c}（border-box の左上が {@code (x, y)}）。{@code last} は余白が突き抜ける最後の流れの子（下の余白を含めない）。 */
    private static void child(LayoutFragment c, float x, float y, boolean last, Acc a, Map<LayoutFragment, float[]> memo, LayoutSession s) {
        BoxGeometry cg = new BoxGeometry(c.box.style, c.space.availableWidth, s);
        a.ext(x + c.width + cg.mR, y + c.height + (last ? 0 : cg.mB));
        a.desc(x + c.width, y + c.height);
        Style cs = c.box.style;
        boolean clips = (cs.box.overflowX != null && cs.box.overflowX != Overflow.VISIBLE)
                || (cs.box.overflowY != null && cs.box.overflowY != Overflow.VISIBLE);
        if (!clips) {
            float[] r = of(c, memo, s);
            a.desc(x + r[2], y + r[3]);
        }
    }

    /**
     * 直交フローの包む箱 {@code c}（M2b-5。{@code (x, y)} は回転の原点）: 描画の {@code transform} で回した物理の矩形で数える（旧
     * {@code ScrollExtentCalc} と同じ）。縦書きの根は 90°（{@code sideways-lr} は -90°）、横書きへ戻す根は逆向き、{@code vertical-lr} は転置。
     */
    private static void orthogonal(BoxNode root, LayoutFragment c, float x, float y, Acc a, Map<LayoutFragment, float[]> memo, LayoutSession s) {
        int code = root.orthogonal;
        int flow = com.nishiyu.enderframelib.core.box.OrthogonalBoxes.verticalFlow(code);
        boolean ret = com.nishiyu.enderframelib.core.box.OrthogonalBoxes.returnsToHorizontal(code);
        boolean transposed = com.nishiyu.enderframelib.core.css.WritingModes.isTransposed(flow);
        boolean ccw = com.nishiyu.enderframelib.core.css.WritingModes.isCounterClockwise(flow);
        boolean plus90 = !transposed && (ret ? ccw : !ccw); // 90°: 論理の (W, H) は x 方向へ -H、y 方向へ +W
        float[] r = of(c, memo, s);
        float lw = Math.max(c.width, r[2]), lh = Math.max(c.height, r[3]);
        float right, bottom;
        if (transposed) {
            right = x + lh;
            bottom = y + lw;
        } else if (plus90) {
            right = x;
            bottom = y + lw;
        } else {
            right = x + lh;
            bottom = y;
        }
        a.ext(right, bottom);
        a.desc(right, bottom);
    }

    /** 行の文字片・インライン要素の箱・原子（行の原点が {@code (ox, oy)}）。 */
    private static void lines(LineFragment[] lines, float ox, float oy, Acc a, Map<LayoutFragment, float[]> memo, LayoutSession s) {
        for (LineFragment l : lines) {
            float lx = ox + l.x(), ly = oy + l.y();
            for (LineFragment.Piece p : l.pieces()) {
                Style st = p.text().style;
                FontFace face = s.face(st);
                FontStyle fs = st.facts.fontStyle;
                float runH = InlineFlow.lineHeight(st, face), runBase = InlineFlow.runBaseline(st, s);
                float top = ly + (l.baseline() - runBase) + p.dy();
                float r = lx + p.x() + p.width(), b = top + Math.max(runH, runBase + face.descent(fs));
                a.ext(r, b);
                a.desc(r, b);
            }
            for (LineFragment.Span sp : l.spans()) {
                float r = lx + sp.x() + sp.width(), b = ly + sp.y() + sp.height();
                a.ext(r, b);
                a.desc(r, b);
            }
            for (PlacedFragment at : l.atoms()) child(at.frag(), lx + at.x(), ly + at.y(), false, a, memo, s);
        }
    }
}
