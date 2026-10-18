package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1 つの DOM ノードに対するスタイル解決の文脈。
 *
 * 以前は {@code base / hover / active / focus} の 4 マップに分けて {@code putAll} で重ねていたため、
 * 詳細度や出現順に関わらず {@code :hover} が基底スタイルに勝ち、{@code :active} が {@code :focus} に勝っていた。
 * 現在は状態をカスケードの入力（{@link SelectorMatcher.StateLookup}）として渡し、
 * 詳細度と出現順に従った単一のカスケードで解決する。
 */
public class StyleSet {

    private final HtmlNode node;
    private final Map<String, String> inheritedStyle;
    private final StyleSheet sheet;
    private final boolean hasInteractiveRules;

    public StyleSet(HtmlNode node, Map<String, String> inheritedStyle, StyleSheet sheet, boolean hasInteractiveRules) {
        this.node = node;
        this.inheritedStyle = inheritedStyle != null ? new LinkedHashMap<>(inheritedStyle) : Map.of();
        this.sheet = sheet;
        this.hasInteractiveRules = hasInteractiveRules;
    }

    /** 対象ノード自身の要素状態を指定して、適用すべき宣言をカスケード解決して返す。 */
    public Map<String, String> resolve(ElementState state) {
        ElementState effective = ElementState.fromNodeAttributes(node)
                .with(state != null ? state.flags() : 0);
        return resolve(n -> n == node ? effective : ElementState.fromNodeAttributes(n));
    }

    /** 文書ツリーの要素状態参照先を指定して、適用すべき宣言をカスケード解決して返す。 */
    public Map<String, String> resolve(SelectorMatcher.StateLookup stateLookup) {
        return StyleResolver.resolveDeclarations(node, inheritedStyle, sheet, stateLookup);
    }

    /** このノードが :hover / :active / :focus* 規則の対象になり得るか。 */
    public boolean hasInteractiveRules() {
        return hasInteractiveRules;
    }
}

