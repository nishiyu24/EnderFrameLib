package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class StyleRule {
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    public String selector;
    public Specificity specificity; // Specificity オブジェクトを使用
    public boolean isHover = false;
    public boolean isActive = false;
    public boolean isFocus = false;
    public boolean isBefore = false;
    public boolean isAfter = false;
    public Map<String, String> properties = new HashMap<>();

    private final List<SelectorToken> tokens = new ArrayList<>();
    private final List<Character> combinators = new ArrayList<>();

    public StyleRule(String selector) {
        this.specificity = Specificity.calculate(selector, 0);

        if (selector.contains("::before") || selector.contains(":before")) {
            this.isBefore = true;
            selector = selector.replace("::before", "").replace(":before", "");
        }
        if (selector.contains("::after") || selector.contains(":after")) {
            this.isAfter = true;
            selector = selector.replace("::after", "").replace(":after", "");
        }
        this.selector = selector.trim();
        parseSelectorTokens(this.selector);
    }

    private void parseSelectorTokens(String sel) {
        sel = WHITESPACE_SPLIT.matcher(sel).replaceAll(" ")
                .replace(" > ", ">").replace(" >", ">").replace("> ", ">")
                .replace(" + ", "+").replace(" +", "+").replace("+ ", "+")
                .replace(" ~ ", "~").replace(" ~", "~").replace("~ ", "~").trim();

        int lastIdx = 0;
        for (int i = 0; i < sel.length(); i++) {
            char c = sel.charAt(i);
            if (c == ' ' || c == '>' || c == '+' || c == '~') {
                tokens.add(new SelectorToken(sel.substring(lastIdx, i)));
                combinators.add(c);
                lastIdx = i + 1;
            }
        }
        tokens.add(new SelectorToken(sel.substring(lastIdx)));

        boolean hasCombinators = !combinators.isEmpty();
        for (SelectorToken t : tokens) {
            if (t.parsedHover) {
                // A-2 修正: 結合子の有無に関わらず、ルール全体として isHover をセットする
                this.isHover = true;
            }
            if (t.parsedActive) {
                this.isActive = true;
            }
            if (t.parsedFocus) {
                this.isFocus = true;
            }
        }
    }

    public boolean matches(HtmlNode node) {
        if (tokens.isEmpty()) return false;
        int pIdx = tokens.size() - 1;

        if (!tokens.get(pIdx).matches(node)) return false;
        pIdx--;

        HtmlNode current = node;
        while (pIdx >= 0 && current != null) {
            char combinator = combinators.get(pIdx);
            SelectorToken expected = tokens.get(pIdx);

            if (combinator == '>') {
                current = current.parent;
                if (current == null || !expected.matches(current)) return false;
                pIdx--;
            } else if (combinator == ' ') {
                current = current.parent;
                while (current != null && !expected.matches(current)) {
                    current = current.parent;
                }
                if (current == null) return false;
                pIdx--;
            } else if (combinator == '+') {
                current = getPreviousSibling(current);
                if (current == null || !expected.matches(current)) return false;
                pIdx--;
            } else if (combinator == '~') {
                current = getPreviousSibling(current);
                while (current != null && !expected.matches(current)) {
                    current = getPreviousSibling(current);
                }
                if (current == null) return false;
                pIdx--;
            }
        }
        return pIdx < 0;
    }

    private HtmlNode getPreviousSibling(HtmlNode node) {
        if (node == null || node.parent == null) return null;
        HtmlNode prev = null;
        for (HtmlNode child : node.parent.children) {
            if (child == node) return prev;
            if (!child.tag.startsWith("#") && !child.tag.equals("template") && !child.isPseudoNode) prev = child;
        }
        return null;
    }
}