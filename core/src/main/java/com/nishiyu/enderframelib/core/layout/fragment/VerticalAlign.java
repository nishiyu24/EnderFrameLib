package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.css.CssLength;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

/**
 * {@code vertical-align}（Engine v3 M2b-1。CSS 2.2 §10.8.1）。行の中の項目（文字の並び・インライン水準の原子）を、親のベースラインから
 * どれだけ持ち上げるかを決める純粋な関数。旧経路の {@code LineBuilder.resolveVerticalAlignRaise} のアルゴリズムの移植。
 *
 * <ul>
 *   <li>{@code sub} / {@code super}・長さ・%（自分の {@code line-height} が基準）・{@code middle}（項目の中央を親の x の高さの半分へ）・
 *       {@code text-top} / {@code text-bottom}（親の文字の上端・下端へ）は持ち上げ量（上が正）。入れ子のインライン要素の分は足し合わせる。</li>
 *   <li>{@code top} / {@code bottom} は行の高さが決まった後で行の上端・下端へ置く（ベースラインの計算に加えない）。</li>
 * </ul>
 */
final class VerticalAlign {
    static final byte BASELINE = 0, TOP = 1, BOTTOM = 2;

    private VerticalAlign() { }

    /** 既定（{@code baseline}）か。 */
    static boolean isBaseline(Style st) {
        long va = st.box.verticalAlign;
        return !CssLength.isDeclared(va) || CssLength.isKeyword(va, CssLength.KW_BASELINE);
    }

    /** {@code top} / {@code bottom} か、それ以外（持ち上げで表す）か。 */
    static byte kind(Style st) {
        long va = st.box.verticalAlign;
        if (CssLength.isKeyword(va, CssLength.KW_TOP)) return TOP;
        if (CssLength.isKeyword(va, CssLength.KW_BOTTOM)) return BOTTOM;
        return BASELINE;
    }

    /**
     * {@code el}（親のスタイルは {@code parent}）が項目を持ち上げる量。{@code h} は項目の高さ、{@code above} は項目の上端から
     * そのベースラインまで。
     */
    static float raise(Style el, Style parent, float h, float above, LayoutSession s) {
        long va = el.box.verticalAlign;
        FontFace face = s.face(parent);
        FontStyle font = parent.facts.fontStyle;
        float parentFont = font.fontSizePx();
        if (CssLength.isKeyword(va, CssLength.KW_MIDDLE)) return h / 2 + face.xHeight(font) / 2 - above;
        if (CssLength.isKeyword(va, CssLength.KW_SUB)) return -(parentFont / 5 + 1);
        if (CssLength.isKeyword(va, CssLength.KW_SUPER)) return parentFont / 3 + 1;
        if (CssLength.isKeyword(va, CssLength.KW_TEXT_TOP)) return face.ascent(font) - above;
        if (CssLength.isKeyword(va, CssLength.KW_TEXT_BOTTOM)) return (h - above) - face.descent(font);
        if (CssLength.isKeyword(va)) return 0;
        float lh = InlineFlow.lineHeight(el, s.face(el));
        return el.px(va, el.box.exprs, lh, s.viewportW(), s.viewportH());
    }
}
