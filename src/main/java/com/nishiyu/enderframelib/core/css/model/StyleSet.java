package com.nishiyu.enderframelib.core.css.model;

import com.nishiyu.enderframelib.core.css.*;
import com.nishiyu.enderframelib.core.css.model.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ノードに対する適用可能なルール集合を保持し、要素状態に応じた宣言を解決する型。
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
    public Map<String, String> resolve(StateLookup stateLookup) {
        return StyleResolver.resolveDeclarations(node, inheritedStyle, sheet, stateLookup);
    }

    /** このノードが :hover / :active / :focus* 規則の対象になり得るか。 */
    public boolean hasInteractiveRules() {
        return hasInteractiveRules;
    }
}
