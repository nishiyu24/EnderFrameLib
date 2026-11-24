package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.css.BoxGeomStyle;
import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;
import com.nishiyu.enderframelib.core.css.model.Overflow;
import com.nishiyu.enderframelib.core.kernel.LayoutUnit;

/**
 * 箱の縁（余白・枠線・パディング）と寸法の解決（Engine v3 M2a）。{@link Style} の型付きフィールドだけを読む。
 * 計算の途中の値で、断片には残さない（呼び出しごとの局所の値）。M2b の一時アダプタ（{@code FragmentProjection}）も
 * 描画用の縁を求めるのに使う。
 */
public final class BoxGeometry {
    public final float mT, mR, mB, mL;
    final boolean autoML, autoMR;
    public final float pT, pR, pB, pL, bT, bR, bB, bL;

    /** {@code cbw} は包含ブロックの幅（余白・パディングの % の基準）。 */
    public BoxGeometry(Style st, float cbw, LayoutSession s) {
        BoxGeomStyle b = st.box;
        mT = len(st, b.marginTop, cbw, s);
        mR = len(st, b.marginRight, cbw, s);
        mB = len(st, b.marginBottom, cbw, s);
        mL = len(st, b.marginLeft, cbw, s);
        autoML = CssLength.isAuto(b.marginLeft);
        autoMR = CssLength.isAuto(b.marginRight);
        pT = Math.max(0, len(st, b.paddingTop, cbw, s));
        pR = Math.max(0, len(st, b.paddingRight, cbw, s));
        pB = Math.max(0, len(st, b.paddingBottom, cbw, s));
        pL = Math.max(0, len(st, b.paddingLeft, cbw, s));
        bT = Math.max(0, len(st, b.borderTopWidth, cbw, s));
        bR = Math.max(0, len(st, b.borderRightWidth, cbw, s));
        bB = Math.max(0, len(st, b.borderBottomWidth, cbw, s));
        bL = Math.max(0, len(st, b.borderLeftWidth, cbw, s));
    }

    float hdec() { return pL + pR + bL + bR; }

    float vdec() { return pT + pB + bT + bB; }

    /** 長さを px へ（宣言なし・auto・基準の無い % は 0）。 */
    static float len(Style st, long v, float base, LayoutSession s) {
        if (!CssLength.isDeclared(v) || CssLength.isKeyword(v)) return 0f;
        if (CssLength.hasPercent(v, st.box.exprs) && !Float.isFinite(base)) return 0f;
        return LayoutUnit.snap(st.boxPx(v, Float.isFinite(base) ? base : 0f, s.viewportW(), s.viewportH()));
    }

    /** 確定した長さか（auto・内在サイズの語・基準の無い % でない）。 */
    static boolean definite(Style st, long v, float base) {
        if (!CssLength.isDeclared(v) || CssLength.isKeyword(v) || CssLength.isIntrinsicSize(v, st.box.exprs)) return false;
        return !CssLength.hasPercent(v, st.box.exprs) || Float.isFinite(base);
    }

    private boolean borderBox(Style st) {
        return st.box.boxSizing == CssKeywords.BoxSizing.BORDER_BOX;
    }

    /** 指定された幅（border-box）。auto なら NaN。 */
    float specifiedWidth(Style st, float cbw, LayoutSession s) {
        long v = st.box.width;
        if (!definite(st, v, cbw)) return Float.NaN;
        float w = len(st, v, cbw, s);
        return borderBox(st) ? Math.max(w, hdec()) : w + hdec();
    }

    /**
     * 指定された幅（border-box）。{@code width} が内在サイズの語（{@code min-content} / {@code max-content} / {@code fit-content}）なら
     * 箱の内容の内在サイズから決める（{@code fit-content} は {@code min(max(min-content, avail), max-content)}。{@code avail} は余白を
     * 引いた使える幅）。auto なら NaN。
     */
    float specifiedWidth(BoxNode box, float cbw, float avail, LayoutSession s) {
        Style st = box.style;
        float w = specifiedWidth(st, cbw, s);
        if (!Float.isNaN(w)) return w;
        long v = st.box.width;
        boolean minC = CssLength.isKeyword(v, CssLength.KW_MIN_CONTENT), maxC = CssLength.isKeyword(v, CssLength.KW_MAX_CONTENT);
        boolean fit = CssLength.isKeyword(v, CssLength.KW_FIT_CONTENT);
        if (!minC && !maxC && !fit) return Float.NaN;
        MinMaxSizes mm = s.cache().minMax(box, s); // 内容の内在サイズ（border-box）
        if (minC) return mm.min;
        if (maxC) return mm.max;
        return Math.min(Math.max(mm.min, Float.isFinite(avail) ? avail : mm.max), mm.max);
    }

    /** 指定された高さ（border-box）。auto・基準の無い % なら NaN。 */
    float specifiedHeight(Style st, float pctBase, LayoutSession s) {
        long v = st.box.height;
        if (!definite(st, v, pctBase)) return Float.NaN;
        float h = len(st, v, pctBase, s);
        return borderBox(st) ? Math.max(h, vdec()) : h + vdec();
    }

    /** {@code min-width} / {@code max-width} で幅（border-box）を挟む。 */
    float clampWidth(Style st, float w, float cbw, LayoutSession s) {
        long max = st.box.maxWidth, min = st.box.minWidth;
        if (definite(st, max, cbw)) {
            float m = len(st, max, cbw, s);
            w = Math.min(w, borderBox(st) ? m : m + hdec());
        }
        if (definite(st, min, cbw)) {
            float m = len(st, min, cbw, s);
            w = Math.max(w, borderBox(st) ? m : m + hdec());
        }
        return Math.max(w, hdec());
    }

    /** {@code min-height} / {@code max-height} で高さ（border-box）を挟む。 */
    float clampHeight(Style st, float h, float pctBase, LayoutSession s) {
        long max = st.box.maxHeight, min = st.box.minHeight;
        if (definite(st, max, pctBase)) {
            float m = len(st, max, pctBase, s);
            h = Math.min(h, borderBox(st) ? m : m + vdec());
        }
        if (definite(st, min, pctBase)) {
            float m = len(st, min, pctBase, s);
            h = Math.max(h, borderBox(st) ? m : m + vdec());
        }
        return Math.max(h, vdec());
    }

    /**
     * 親の内在サイズへの寄与（border-box）。指定の幅があればそれ、無ければ内容に基づく内在サイズを min/max の幅で挟む。
     * {@code content} は {@link FormattingEngine#computeMinMaxSizes}（自分の {@code width} を掛けない内容の内在サイズ）。
     */
    MinMaxSizes contribution(Style st, MinMaxSizes content, LayoutSession s) {
        float spec = specifiedWidth(st, Float.NaN, s);
        long v = st.box.width;
        if (Float.isNaN(spec) && CssLength.isKeyword(v, CssLength.KW_MIN_CONTENT)) spec = content.min;
        if (Float.isNaN(spec) && CssLength.isKeyword(v, CssLength.KW_MAX_CONTENT)) spec = content.max;
        if (!Float.isNaN(spec)) {
            float w = clampWidth(st, spec, Float.NaN, s);
            return new MinMaxSizes(w, w);
        }
        return new MinMaxSizes(clampWidth(st, content.min, Float.NaN, s), clampWidth(st, content.max, Float.NaN, s));
    }

    /**
     * 高さに % を使うか（子の制約に % の高さの基準を載せるかの判定。キーの粗粒化）。{@code position: relative} の % の
     * {@code top} / {@code bottom} も包含ブロックの高さが基準なので含める（WPT css-grid/relative-grandchild）。
     */
    static boolean usesPercentHeight(Style st) {
        BoxGeomStyle b = st.box;
        if (CssLength.hasPercent(b.height, b.exprs) || CssLength.hasPercent(b.minHeight, b.exprs)
                || CssLength.hasPercent(b.maxHeight, b.exprs)) return true;
        return b.position == com.nishiyu.enderframelib.core.css.model.Position.RELATIVE
                && (CssLength.hasPercent(b.top, b.exprs) || CssLength.hasPercent(b.bottom, b.exprs));
    }

    /** 独立した整形コンテキストを作るスタイルか（{@code overflow} が visible 以外・{@code flow-root}・grid・表）。 */
    static boolean establishesBfc(Style st) {
        Overflow ox = st.box.overflowX, oy = st.box.overflowY;
        if ((ox != null && ox != Overflow.VISIBLE) || (oy != null && oy != Overflow.VISIBLE)) return true;
        if (st.box.display == null) return false;
        // flow-root と、ブロックの流れ以外の表示型（grid・表など。M2b-3/4 までブロックとして並べる）は独立した整形コンテキスト
        return switch (st.box.display.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "flow-root", "grid", "inline-grid", "table", "inline-table", "table-cell", "table-caption" -> true;
            default -> false;
        };
    }
}
