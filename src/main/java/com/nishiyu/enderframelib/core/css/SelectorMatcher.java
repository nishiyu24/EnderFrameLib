package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;
import com.nishiyu.enderframelib.core.dom.NodeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * パース済みセレクタと DOM ノードの照合。
 * 以前は {@link StyleRule} と {@link SelectorToken} に分かれて埋まっていた照合ロジックをここに集約した。
 *
 * 対話状態（:hover / :active / :focus / :focus-visible / :focus-within）および
 * フォーム状態（:checked / :disabled / :indeterminate / :open）は {@link StateLookup} 経由で
 * 複合セレクタごとに照合する。
 */
public final class SelectorMatcher {

    /** 任意の DOM ノードに対する現在の {@link ElementState} を返す関数。 */
    @FunctionalInterface
    public interface StateLookup {
        ElementState stateOf(HtmlNode node);
    }

    /** 実行時の対話状態が無い場合、DOM ノードの静的属性だけから状態を導出する既定の参照先。 */
    public static final StateLookup STATIC_ATTRIBUTES = ElementState::fromNodeAttributes;

    private SelectorMatcher() {}

    /** セレクタ全体（結合子を含む）が静的属性のみで node に一致するか。 */
    public static boolean matches(Selector selector, HtmlNode node) {
        return matches(selector, node, STATIC_ATTRIBUTES);
    }

    /** セレクタ全体（結合子を含む）が指定の要素状態のもとで node に一致するか。 */
    public static boolean matches(Selector selector, HtmlNode node, StateLookup stateLookup) {
        StateLookup lookup = stateLookup != null ? stateLookup : STATIC_ATTRIBUTES;
        List<SelectorToken> compounds = selector.compounds();
        List<Character> combinators = selector.combinators();
        if (compounds.isEmpty()) return false;

        int pIdx = compounds.size() - 1;
        if (!matchesCompound(compounds.get(pIdx), node, lookup)) return false;
        pIdx--;

        HtmlNode current = node;
        while (pIdx >= 0 && current != null) {
            char combinator = combinators.get(pIdx);
            SelectorToken expected = compounds.get(pIdx);

            switch (combinator) {
                case '>' -> {
                    current = current.parent;
                    if (current == null || !matchesCompound(expected, current, lookup)) return false;
                }
                case '+' -> {
                    current = previousElementSibling(current);
                    if (current == null || !matchesCompound(expected, current, lookup)) return false;
                }
                case '~' -> {
                    current = previousElementSibling(current);
                    while (current != null && !matchesCompound(expected, current, lookup)) {
                        current = previousElementSibling(current);
                    }
                    if (current == null) return false;
                }
                default -> { // ' ' 子孫結合子
                    current = current.parent;
                    while (current != null && !matchesCompound(expected, current, lookup)) {
                        current = current.parent;
                    }
                    if (current == null) return false;
                }
            }
            pIdx--;
        }
        return pIdx < 0;
    }

    /** 複合セレクタ 1 単位が静的属性のみで node に一致するか。 */
    public static boolean matchesCompound(SelectorToken token, HtmlNode node) {
        return matchesCompound(token, node, STATIC_ATTRIBUTES);
    }

    /** 複合セレクタ 1 単位が指定の要素状態のもとで node に一致するか。 */
    public static boolean matchesCompound(SelectorToken token, HtmlNode node, StateLookup stateLookup) {
        StateLookup lookup = stateLookup != null ? stateLookup : STATIC_ATTRIBUTES;

        for (SelectorToken notToken : token.notSelectors) {
            if (matchesCompound(notToken, node, lookup)) return false;
        }

        if (!matchesCompoundWithoutInteractiveState(token, node)) return false;

        if (token.stateFlags != 0) {
            ElementState state = lookup.stateOf(node);
            if (state == null) state = ElementState.NONE;

            int nonFocusWithin = token.stateFlags & ~ElementState.FOCUS_WITHIN;
            if (nonFocusWithin != 0 && !state.has(nonFocusWithin)) return false;

            if ((token.stateFlags & ElementState.FOCUS_WITHIN) != 0) {
                // :focus-within は祖先方向の照合（自身に FOCUS_WITHIN が立っているか、子孫に FOCUS があるとき成立）。
                // 以前は :focus と同一フラグだったため、子孫ではなく自分のフォーカスで誤って成立していた。
                if (!state.focusWithin() && !hasFocusedDescendant(node, lookup)) return false;
            }
        }

        return true;
    }

    /**
     * 対話状態（:hover / :active / :focus*）を除いた構造・属性条件が node に一致するか。
     * ヒットテストインデックスが「この要素はホバー等の状態変化でスタイルが変わり得るか」を判定するために使う。
     */
    public static boolean matchesCompoundWithoutInteractiveState(SelectorToken token, HtmlNode node) {
        if (node == null) return false;
        if (token.isRoot && node.nodeType != NodeType.ROOT && !"html".equals(node.tag)) return false;
        if (token.id != null && !token.id.equals(node.getId())) return false;
        if (!token.tag.equals("*") && !token.tag.equals(node.tag)) return false;

        for (String c : token.classes) {
            if (!node.hasClass(c)) return false;
        }

        if (token.isEmpty && hasContent(node)) return false;
        if (!matchesAttributes(token, node)) return false;
        return matchesStructuralPosition(token, node);
    }

    private static boolean hasFocusedDescendant(HtmlNode node, StateLookup stateLookup) {
        if (node == null || stateLookup == null) return false;
        for (HtmlNode child : node.children) {
            ElementState childState = stateLookup.stateOf(child);
            if (childState != null && (childState.focus() || childState.focusWithin())) return true;
            if (hasFocusedDescendant(child, stateLookup)) return true;
        }
        return false;
    }

    private static boolean matchesAttributes(SelectorToken token, HtmlNode node) {
        for (String req : token.requiredExistsAttrs) {
            if (!node.attrs.containsKey(req)) return false;
        }
        for (Map.Entry<String, String> req : token.requiredAttrs.entrySet()) {
            String actual = node.attrs.get(req.getKey());
            if (actual == null) return false;
            if (req.getValue() != null && !req.getValue().equals(actual)) return false;
        }
        for (Map.Entry<String, String> req : token.prefixAttrs.entrySet()) {
            String actual = node.attrs.get(req.getKey());
            if (actual == null || !actual.startsWith(req.getValue())) return false;
        }
        for (Map.Entry<String, String> req : token.suffixAttrs.entrySet()) {
            String actual = node.attrs.get(req.getKey());
            if (actual == null || !actual.endsWith(req.getValue())) return false;
        }
        for (Map.Entry<String, String> req : token.containsAttrs.entrySet()) {
            String actual = node.attrs.get(req.getKey());
            if (actual == null || !actual.contains(req.getValue())) return false;
        }
        for (Map.Entry<String, String> req : token.wordAttrs.entrySet()) {
            String actual = node.attrs.get(req.getKey());
            if (actual == null) return false;
            boolean found = false;
            for (String word : actual.trim().split("\\s+")) {
                if (word.equals(req.getValue())) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        for (Map.Entry<String, String> req : token.dashAttrs.entrySet()) {
            String actual = node.attrs.get(req.getKey());
            if (actual == null) return false;
            if (!actual.equals(req.getValue()) && !actual.startsWith(req.getValue() + "-")) return false;
        }
        return true;
    }

    private static boolean matchesStructuralPosition(SelectorToken token, HtmlNode node) {
        boolean needsPosition = token.nthChild != -1 || token.isFirstChild || token.isLastChild;
        if (!needsPosition || node.parent == null) return true;

        List<HtmlNode> siblings = elementSiblings(node.parent);
        int idx = siblings.indexOf(node) + 1; // 1-based
        if (idx <= 0) return false;
        if (token.isFirstChild && idx != 1) return false;
        if (token.isLastChild && idx != siblings.size()) return false;
        if (token.nthChild > 0 && idx != token.nthChild) return false;
        if (token.nthChild == SelectorParser.NTH_ODD && idx % 2 == 0) return false;
        if (token.nthChild == SelectorParser.NTH_EVEN && idx % 2 != 0) return false;
        return true;
    }

    private static boolean hasContent(HtmlNode node) {
        if (node.text != null && !node.text.trim().isEmpty()) return true;
        for (HtmlNode c : node.children) {
            if (c.isPseudoNode) continue;
            if (c.nodeType != NodeType.TEXT) return true;
            if (c.text != null && !c.text.trim().isEmpty()) return true;
        }
        return false;
    }

    /** 要素ノードだけを対象にした兄弟リスト（テキスト・template・疑似ノードは数えない）。 */
    private static List<HtmlNode> elementSiblings(HtmlNode parent) {
        List<HtmlNode> out = new ArrayList<>();
        for (HtmlNode child : parent.children) {
            if (isCountableElement(child)) out.add(child);
        }
        return out;
    }

    private static HtmlNode previousElementSibling(HtmlNode node) {
        if (node == null || node.parent == null) return null;
        HtmlNode prev = null;
        for (HtmlNode child : node.parent.children) {
            if (child == node) return prev;
            if (isCountableElement(child)) prev = child;
        }
        return null;
    }

    private static boolean isCountableElement(HtmlNode node) {
        return node.nodeType == NodeType.ELEMENT && !node.tag.equals("template") && !node.isPseudoNode;
    }
}
