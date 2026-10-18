package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.List;

/**
 * パース済みのセレクタ 1 本（カンマで区切られた 1 要素）。
 *
 * compounds と combinators は対になっていて、combinators.get(i) は
 * compounds.get(i) と compounds.get(i + 1) の間の結合子（' ' / '>' / '+' / '~'）を表す。
 */
public record Selector(
        String text,
        List<SelectorToken> compounds,
        List<Character> combinators,
        boolean isBefore,
        boolean isAfter,
        boolean isHover,
        boolean isActive,
        boolean isFocus,
        int stateMask,
        Specificity specificity
) {

    public Selector(
            String text,
            List<SelectorToken> compounds,
            List<Character> combinators,
            boolean isBefore,
            boolean isAfter,
            boolean isHover,
            boolean isActive,
            boolean isFocus,
            Specificity specificity
    ) {
        this(text, compounds, combinators, isBefore, isAfter, isHover, isActive, isFocus,
                (isHover ? ElementState.HOVER : 0)
                        | (isActive ? ElementState.ACTIVE : 0)
                        | (isFocus ? ElementState.FOCUS : 0),
                specificity);
    }

    public boolean matches(HtmlNode node) {
        return SelectorMatcher.matches(this, node);
    }

    public boolean matches(HtmlNode node, SelectorMatcher.StateLookup stateLookup) {
        return SelectorMatcher.matches(this, node, stateLookup);
    }

    /** オリジンだけを差し替えた同等のセレクタを返す（UA → 作者の昇格などに使う）。 */
    public Selector withOrigin(CascadeOrigin origin) {
        return new Selector(text, compounds, combinators, isBefore, isAfter, isHover, isActive, isFocus,
                stateMask, specificity.withOrigin(origin));
    }
}
