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
        return of(f, memo, s, null);
    }

    /**
     * {@link #of(LayoutFragment, Map, LayoutSession)} と同じ値。{@code chunks} は流れのチャンク {@code k} の到達範囲（{@link #chunk}。
     * 呼び手が作ったか、使い回したチャンクが持っていた値。null の要素・配列はその場で求める）。8,000 子の流れでも、使い回したチャンクの
     * 中は下りない（M3b-4c-2b-3a）。
     */
    public static float[] of(LayoutFragment f, Map<LayoutFragment, float[]> memo, LayoutSession s, float[][] chunks) {
        float[] r = memo.get(f);
        if (r != null) return r;
        r = compute(f, memo, s, chunks);
        memo.put(f, r);
        return r;
    }

    /** {@link #chunk} の結果の添字。 */
    private static final int C_EXT_R = 0, C_EXT_B = 1, C_EXT_B_LAST = 2, C_DESC_R = 3, C_DESC_B = 4, C_FLAGS = 5;
    /** {@link #chunk} の印: 箱の子があった・流れのブロックの子があった・使い回せない（直交フローの包む箱は親の箱の値を読む）。 */
    private static final int F_ANY = 1, F_BLOCK = 2, F_LOCAL = 4;

    /**
     * 断片 {@code f} の一様なチャンク {@code k}（{@link ChunkedFlow#isUniformChunk}）の子の到達範囲。値はチャンクの原点（親の内容ボックスの
     * 左上から {@code chunkY[k]} 下）からで、チャンク（{@link FlowChunk} の {@code ==}）が同じなら同じ値になる（親の値を読まない）。
     * {@code {中身の右端, 下端, 最後の流れのブロックの子の下の余白を除いた下端, 子孫のはみ出しの右端, 下端, 印}}。直交フローの包む箱を含む
     * チャンクは親の箱を読むので、使い回せない印を付ける（{@link #reusable}）。
     */
    public static float[] chunk(LayoutFragment f, int k, Map<LayoutFragment, float[]> memo, LayoutSession s) {
        FlowChunk c = f.flow.chunks[k];
        Acc a = new Acc(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
        float extBLast = Float.NEGATIVE_INFINITY;
        int flags = 0, lastI = -1;
        for (int i = c.frags.length - 1; i >= 0; i--) {
            LayoutFragment ch = c.frags[i];
            if (ch.box != null && ch.box.kind.blockLevel()) {
                lastI = i;
                break;
            }
        }
        for (int i = 0; i < c.frags.length; i++) {
            LayoutFragment ch = c.frags[i];
            float x = c.x[i], y = c.y[i];
            if (ch.box == null) {
                if (ch.lines == null) continue;
                lines(ch.lines, x, y, a, memo, s);
                if (lastI >= 0 && i > lastI) {
                    Acc tail = new Acc(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
                    lines(ch.lines, x, y, tail, memo, s);
                    extBLast = Math.max(extBLast, tail.extB);
                }
                continue;
            }
            float beforeB = a.extB;
            if (ch.box.pseudo == BoxNode.Pseudo.ORTHOGONAL) {
                orthogonal(f.box, ch, x, y, a, memo, s);
                flags |= F_LOCAL;
            } else {
                child(ch, x, y, false, a, memo, s);
            }
            if (i == lastI) {
                // 最後の流れのブロックの子だけ下の余白を含めない値も持つ（余白が親を突き抜けるときに親が使う。直交フローの包む箱は余白を足さない）
                extBLast = Math.max(beforeB, ch.box.pseudo == BoxNode.Pseudo.ORTHOGONAL ? a.extB : y + ch.height);
                flags |= F_BLOCK;
            } else if (lastI >= 0 && i > lastI) {
                // 最後の流れのブロックの子より後ろの子（float などの箱）は、その子の分だけを足す
                Acc tail = new Acc(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY);
                if (ch.box.pseudo == BoxNode.Pseudo.ORTHOGONAL) orthogonal(f.box, ch, x, y, tail, memo, s);
                else child(ch, x, y, false, tail, memo, s);
                extBLast = Math.max(extBLast, tail.extB);
            }
            flags |= F_ANY;
        }
        if (lastI < 0) extBLast = a.extB;
        return new float[]{a.extR, a.extB, extBLast, a.descR, a.descB, flags};
    }

    /** {@link #chunk} の結果を、同じチャンクを使い回す層が持ち続けてよいか（親の箱を読まない）。 */
    public static boolean reusable(float[] chunk) {
        return chunk != null && (((int) chunk[C_FLAGS]) & F_LOCAL) == 0;
    }

    private static float[] compute(LayoutFragment f, Map<LayoutFragment, float[]> memo, LayoutSession s, float[][] given) {
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
                if (f.flow.isUniformChunk(k)) {
                    // 一様なチャンクはチャンクの原点からの値を足す（使い回したチャンクは呼び手が持っていた値。中を下りない）
                    float[] e = given != null && k < given.length && given[k] != null ? given[k] : chunk(f, k, memo, s);
                    int fl = (int) e[C_FLAGS];
                    float ox = f.insetLeft, oy = f.insetTop + f.flow.chunkY[k];
                    if (e[C_EXT_R] != Float.NEGATIVE_INFINITY) {
                        a.ext(ox + e[C_EXT_R], oy + (k == lastK ? e[C_EXT_B_LAST] : e[C_EXT_B]));
                    }
                    if (e[C_DESC_R] != Float.NEGATIVE_INFINITY) a.desc(ox + e[C_DESC_R], oy + e[C_DESC_B]);
                    if ((fl & F_ANY) != 0) any = true;
                    continue;
                }
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
