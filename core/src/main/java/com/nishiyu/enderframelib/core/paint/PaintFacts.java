package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.css.VisualStyle;
import com.nishiyu.enderframelib.core.css.model.CssKeywords;

/**
 * 算出スタイルだけから決まる、描画順の分類に使う値（M2c-3 で旧 {@code PaintOrder.StyleFacts} から移した）。箱ごとに十数回の文字列引きを
 * していた所を、スタイルごとに一度で済ませる（算出スタイルは不変なので、スタイルの派生値として持つ）。
 */
record PaintFacts(boolean displayNone, boolean zAuto, float opacity, boolean stackingByStyle, boolean hasMask,
                  String overflowX, String overflowY, boolean scrollbars, boolean hasAnimation, boolean hasMovingProps) {

    /** 切らない軸のクリップの端（描画とヒットテストの座標に十分遠い値）。 */
    static final float UNCLIPPED = 1.0e6f;

    /** Top Layer の箱（{@code dialog:modal} と表示中の {@code popover}）に UA シートが与える {@code z-index}。 */
    static final int TOP_LAYER_Z = Integer.MAX_VALUE;

    static PaintFacts of(ComputedStyle style) {
        if (style.derivedFacts() instanceof PaintFacts cached) return cached;
        Style st = style.typed();
        VisualStyle v = st.visual;
        boolean zAuto = Float.isNaN(v.zIndex);
        boolean mask = styleHasMask(style);
        boolean stacking = !isNone(v.transform) || !isNone(v.filter)
                || !isNone(v.clipPath) || !isNone(v.backdropFilter) || mask
                || v.isolation == CssKeywords.Isolation.ISOLATE
                || !"normal".equals(mixBlendMode(v));
        String[] overflow = overflowPair(style);
        boolean hasAnimation = v.animation != null || v.animationName != null;
        boolean hasMovingProps = hasAnimation || hasTransformOrFilterProps(st);
        PaintFacts facts = new PaintFacts(st.facts.displayNone, zAuto, Style.num(v.opacity, 1f), stacking, mask, overflow[0], overflow[1],
                isScrollbarOverflow(overflow[0]) || isScrollbarOverflow(overflow[1]), hasAnimation, hasMovingProps);
        style.derivedFacts(facts);
        return facts;
    }

    static boolean isNone(String value) {
        return value == null || "none".equals(value) || value.isBlank() || "none".equalsIgnoreCase(value.trim());
    }

    private static boolean hasTransformOrFilterProps(Style st) {
        VisualStyle v = st.visual;
        return !isNone(v.transform) || !isNone(v.translate) || !isNone(v.rotate)
                || !isNone(v.scale) || !isNone(v.filter) || !isNone(st.rare.perspective);
    }

    private static String mixBlendMode(VisualStyle v) {
        return v.mixBlendMode == null ? "normal" : v.mixBlendMode.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean styleHasMask(ComputedStyle style) {
        if (BoxDecorations.clipsText(style)) return true; // background-clip: text は文字のアルファを写すマスク
        for (BoxDecorations.BackgroundLayer layer : BoxDecorations.maskLayersView(style, 1.0)) {
            if (layer.url() != null || layer.isGradient()) return true;
        }
        return false;
    }

    /** {@code overflow-x} / {@code overflow-y}（片方だけが切るとき、もう片方の visible / clip は auto / hidden として読む）。 */
    private static String[] overflowPair(ComputedStyle style) {
        String x = style.overflowX().cssName();
        String y = style.overflowY().cssName();
        boolean xPlain = "visible".equals(x) || "clip".equals(x);
        boolean yPlain = "visible".equals(y) || "clip".equals(y);
        if (xPlain != yPlain) {
            if (xPlain) x = "visible".equals(x) ? "auto" : "hidden";
            else y = "visible".equals(y) ? "auto" : "hidden";
        }
        return new String[]{x, y};
    }

    private static boolean isScrollbarOverflow(String overflow) {
        return "scroll".equals(overflow) || "auto".equals(overflow);
    }
}
