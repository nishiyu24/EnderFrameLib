package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords.Clear;
import com.nishiyu.enderframelib.core.css.model.CssKeywords.FloatSide;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

import java.util.Arrays;

/**
 * float（Engine v3 M2b-1）。float の除外領域（{@link Space}）と配置。
 *
 * <p>同じ容器の中の float を左右に積み（入らなければ下へ）、{@code clear} で後ろの箱を下げ、インラインの行が float を避ける。
 * 除外領域は同じ BFC の中で共有する（CSS 2.2 §9.5）: 親は独立した整形コンテキストを作らないブロックの子へ、その子の上端より下に掛かる
 * 矩形を {@link ConstraintSpace#exclusions} に相対座標で渡し（掛からない子は {@link Space#EMPTY} で鍵を変えない）、子が置いた float は
 * 子の断片の {@link LayoutFragment#floatsOut} で親へ返り、後ろの兄弟が避ける。独立した整形コンテキストの根は float を含んで高さを決め、
 * 外の float とは重ならずに横に縮むか、入らなければ下がる（{@link BlockFlow}）。範囲外: 行が収まらないときの行の押し下げ。</p>
 */
final class Floats {
    private Floats() { }

    /** 置いた float の margin-box の矩形の不変の列（容器の内容ボックス基準）。 */
    static final class Space {
        static final Space EMPTY = new Space(new float[0][]);
        /** {左なら 0 / 右なら 1, x0, x1, y0, y1}。 */
        private final float[][] rects;

        private Space(float[][] rects) {
            this.rects = rects;
        }

        boolean isEmpty() { return rects.length == 0; }

        int size() { return rects.length; }

        /** 原点を {@code (dx, dy)} だけ動かした座標での同じ領域（{@code y1 <= 0} になる、上に抜けた矩形は落とす）。 */
        Space translate(float dx, float dy) {
            if (rects.length == 0 || (dx == 0 && dy == 0)) return this;
            float[][] out = new float[rects.length][];
            int n = 0;
            for (float[] f : rects) {
                if (f[4] + dy <= 0) continue;
                out[n++] = new float[]{f[0], f[1] + dx, f[2] + dx, f[3] + dy, f[4] + dy};
            }
            return n == 0 ? EMPTY : new Space(n == out.length ? out : Arrays.copyOf(out, n));
        }

        /** {@code from} 番目以降の矩形（子が足した分）。 */
        Space suffix(int from) {
            return from >= rects.length ? EMPTY : from == 0 ? this : new Space(Arrays.copyOfRange(rects, from, rects.length));
        }

        /** {@code o} の矩形を後ろに足した領域。 */
        Space merge(Space o) {
            if (o.rects.length == 0) return this;
            if (rects.length == 0) return o;
            float[][] r = Arrays.copyOf(rects, rects.length + o.rects.length);
            System.arraycopy(o.rects, 0, r, rects.length, o.rects.length);
            return new Space(r);
        }

        /** {@code y} より下で最初に float が終わる位置（無ければ +∞）。 */
        float nextBottom(float y) {
            float next = Float.POSITIVE_INFINITY;
            for (float[] f : rects) if (f[4] > y) next = Math.min(next, f[4]);
            return next;
        }

        /** 帯 {@code [y0, y1)} に掛かる float があるか。 */
        boolean intrudes(float y0, float y1) {
            for (float[] f : rects) if (f[4] > y0 && f[3] < y1) return true;
            return false;
        }

        /** 値等価（制約の鍵 {@link ConstraintSpace} に載る）。 */
        @Override
        public boolean equals(Object o) {
            return this == o || o instanceof Space sp && Arrays.deepEquals(rects, sp.rects);
        }

        @Override
        public int hashCode() {
            return Arrays.deepHashCode(rects);
        }

        Space add(boolean right, float x0, float x1, float y0, float y1) {
            float[][] r = Arrays.copyOf(rects, rects.length + 1);
            r[rects.length] = new float[]{right ? 1 : 0, x0, x1, y0, y1};
            return new Space(r);
        }

        /** 帯 {@code [y0, y1)} で使える {左端, 右端}（幅 {@code cw} の内容ボックス基準）。 */
        float[] available(float y0, float y1, float cw) {
            float l = 0, r = cw;
            for (float[] f : rects) {
                if (f[4] <= y0 || f[3] >= y1) continue;
                if (f[0] == 0) l = Math.max(l, f[2]);
                else r = Math.min(r, f[1]);
            }
            return new float[]{l, r};
        }

        /** {@code clear} の後に箱の上端が来られる位置（その側の float の下端の最大。無ければ -∞）。 */
        float clearance(Clear c) {
            float y = Float.NEGATIVE_INFINITY;
            for (float[] f : rects) {
                boolean left = f[0] == 0;
                if (c == Clear.BOTH || (c == Clear.LEFT || c == Clear.INLINE_START) == left
                        && (c == Clear.LEFT || c == Clear.RIGHT || c == Clear.INLINE_START || c == Clear.INLINE_END)) {
                    y = Math.max(y, f[4]);
                }
            }
            return y;
        }

        /** 一番下の float の下端（無ければ 0）。 */
        float bottom() {
            float y = 0;
            for (float[] f : rects) y = Math.max(y, f[4]);
            return y;
        }

        /** 新しい float の上端の下限（前の float の上端より上には置かない。float が無ければ -∞）。 */
        float lastTop() {
            float y = Float.NEGATIVE_INFINITY;
            for (float[] f : rects) y = Math.max(y, f[3]);
            return y;
        }
    }

    /** 流れから外れて左右に寄る float か（流れの外の位置指定は除く。flex の項目では呼ばない）。 */
    static boolean is(BoxNode b) {
        if (b.kind.atomicInline() || b.kind.blockLevel()) {
            FloatSide f = b.style.box.floatValue;
            return f != null && f != FloatSide.NONE && !OutOfFlow.is(b);
        }
        return false;
    }

    static boolean right(Style st) {
        FloatSide f = st.box.floatValue;
        return f == FloatSide.RIGHT || f == FloatSide.INLINE_END;
    }

    /** 置いた結果（{@code x}, {@code y} は内容ボックス基準の border-box の左上）と、足した後の除外領域。 */
    record Placed(LayoutFragment frag, float x, float y, Space space) { }

    /** float を 1 つ整形して、流れの位置 {@code flowY} 以降で入る所に置く。 */
    static Placed place(BoxNode box, Space space, float flowY, float cw, float pctBase, LayoutFragment prior, LayoutSession s) {
        Style st = box.style;
        BoxGeometry g = new BoxGeometry(st, cw, s);
        float w = g.specifiedWidth(box, cw, cw - g.mL - g.mR, s);
        if (Float.isNaN(w)) { // 縮小して合わせる
            MinMaxSizes mm = s.cache().minMax(box, s);
            w = Math.min(Math.max(mm.min, cw - g.mL - g.mR), mm.max);
        }
        w = LayoutUnit.snap(g.clampWidth(st, Math.max(0, w), cw, s));
        float pct = BoxGeometry.usesPercentHeight(st) ? pctBase : Float.NaN;
        LayoutFragment f = s.cache().layout(box, s.space(cw, w, Float.NaN, pct, ConstraintSpace.FORMATTING_ROOT), prior, s);
        float outerW = g.mL + f.width + g.mR, outerH = g.mT + f.height + g.mB;
        boolean right = right(st);
        float y = Math.max(flowY, space.lastTop());
        Clear c = st.box.clear;
        if (c != null && c != Clear.NONE) y = Math.max(y, space.clearance(c));
        float[] av = space.available(y, y + Math.max(outerH, 0.01f), cw);
        for (int guard = 0; guard < 64 && av[1] - av[0] < outerW - 0.01f && !space.isEmpty(); guard++) {
            float next = space.nextBottom(y); // 入らない: 次に float が終わる所まで下げる
            if (next == Float.POSITIVE_INFINITY) break;
            y = next;
            av = space.available(y, y + Math.max(outerH, 0.01f), cw);
        }
        float x0 = right ? av[1] - outerW : av[0];
        Space added = space.add(right, x0, x0 + outerW, y, y + outerH);
        return new Placed(f, LayoutUnit.snap(x0 + g.mL), LayoutUnit.snap(y + g.mT), added);
    }
}
