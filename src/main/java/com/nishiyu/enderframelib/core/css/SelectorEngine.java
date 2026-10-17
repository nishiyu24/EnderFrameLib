package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HtmlNode の querySelector などを処理するエンジン。
 * セレクタ文字列のパース結果をキャッシュし、毎回 new StyleRule するのを防ぎます。
 */
public class SelectorEngine {

    private static final Map<String, CssParser.StyleRule> SELECTOR_CACHE = new ConcurrentHashMap<>();

    private static CssParser.StyleRule getOrParse(String selector) {
        String clean = selector.trim();
        return SELECTOR_CACHE.computeIfAbsent(clean, CssParser.StyleRule::new);
    }

    public static HtmlNode querySelector(HtmlNode root, String selector) {
        CssParser.StyleRule rule = getOrParse(selector);
        return querySelectorInternal(root, rule);
    }

    private static HtmlNode querySelectorInternal(HtmlNode node, CssParser.StyleRule rule) {
        if (rule.matches(node)) return node;
        for (HtmlNode child : node.children) {
            HtmlNode found = querySelectorInternal(child, rule);
            if (found != null) return found;
        }
        return null;
    }

    public static List<HtmlNode> querySelectorAll(HtmlNode root, String selector) {
        List<HtmlNode> results = new ArrayList<>();
        CssParser.StyleRule rule = getOrParse(selector);
        querySelectorAllInternal(root, rule, results);
        return results;
    }

    private static void querySelectorAllInternal(HtmlNode node, CssParser.StyleRule rule, List<HtmlNode> results) {
        if (rule.matches(node)) results.add(node);
        for (HtmlNode child : node.children) {
            querySelectorAllInternal(child, rule, results);
        }
    }

    public static void clearCache() {
        SELECTOR_CACHE.clear();
    }
}