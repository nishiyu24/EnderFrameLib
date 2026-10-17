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
 * 対話状態（:hover など）は文書から切り離されているため、ここでは照合せず
 * {@link Selector} のフラグとして上位（{@link StyleSet}）に渡される。
 */
public final class SelectorMatcher {

    private SelectorMatcher() {}

    /** セレクタ全体（結合子を含む）が node に一致するか。 */
    public static boolean matches(Selector selector, HtmlNode node) {
        List<SelectorToken> compounds = selector.compounds();
        List<Character> combinators = selector.combinators();
        if (compounds.isEmpty()) return false;

        int pIdx = compounds.size() - 1;
        if (!matchesCompound(compounds.get(pIdx), node)) return false;
        pIdx--;

        HtmlNode current = node;
        while (pIdx >= 0 && current != null) {
            char combinator = combinators.get(pIdx);
            SelectorToken expected = compounds.get(pIdx);

            switch (combinator) {
                case '>' -> {
                    current = current.parent;
                    if (current == null || !matchesCompound(expected, current)) return false;
                }
                case '+' -> {
                    current = previousElementSibling(current);
                    if (current == null || !matchesCompound(expected, current)) return false;
                }
                case '~' -> {
                    current = previousElementSibling(current);
                    while (current != null && !matchesCompound(expected, current)) {
                        current = previousElementSibling(current);
                    }
                    if (current == null) return false;
                }
                default -> { // ' ' 子孫結合子
                    current = current.parent;
                    while (current != null && !matchesCompound(expected, current)) {
                        current = current.parent;
                    }
                    if (current == null) return false;
                }
            }
            pIdx--;
        }
        return pIdx < 0;
    }

    /** 複合セレクタ 1 単位が node に一致するか。 */
    public static boolean matchesCompound(SelectorToken token, HtmlNode node) {
        for (SelectorToken notToken : token.notSelectors) {
            if (matchesCompound(notToken, node)) return false;
        }

        if (token.isRoot && node.nodeType != NodeType.ROOT && !"html".equals(node.tag)) return false;
        if (token.id != null && !token.id.equals(node.getId())) return false;
        if (!token.tag.equals("*") && !token.tag.equals(node.tag)) return false;

        for (String c : token.classes) {
            if (!node.hasClass(c)) return false;
        }

        if (token.isDisabled && !node.attrs.containsKey("disabled")) return false;
        // 対話状態を HtmlNode から切り離したため、:checked は静的属性のみを見る
        if (token.isChecked && !node.attrs.containsKey("checked")) return false;

        if (token.isEmpty && hasContent(node)) return false;

        if (!matchesAttributes(token, node)) return false;

        return matchesStructuralPosition(token, node);
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
