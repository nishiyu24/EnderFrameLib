package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code querySelector} / {@code querySelectorAll} の実体。
 *
 * パース済みセレクタをキャッシュするため、呼び出しごとにセレクタを再トークナイズしない。
 * {@code core/dom} が {@code core/css} の内部型を直接 new する循環もここで断っている。
 */
public final class SelectorEngine {

    /** 信頼できない作者 HTML がキーになり得るため上限を設ける。 */
    private static final int MAX_CACHED_SELECTORS = 512;

    private static final Map<String, Selector> SELECTOR_CACHE = new ConcurrentHashMap<>();

    private SelectorEngine() {}

    public static Selector getOrParse(String selector) {
        String clean = selector == null ? "" : selector.trim();
        Selector cached = SELECTOR_CACHE.get(clean);
        if (cached != null) return cached;

        Selector parsed = SelectorParser.parse(clean);
        if (SELECTOR_CACHE.size() < MAX_CACHED_SELECTORS) {
            SELECTOR_CACHE.put(clean, parsed);
        }
        return parsed;
    }

    public static HtmlNode querySelector(HtmlNode root, String selector) {
        return querySelectorInternal(root, getOrParse(selector));
    }

    private static HtmlNode querySelectorInternal(HtmlNode node, Selector selector) {
        if (SelectorMatcher.matches(selector, node)) return node;
        for (HtmlNode child : node.children) {
            HtmlNode found = querySelectorInternal(child, selector);
            if (found != null) return found;
        }
        return null;
    }

    public static List<HtmlNode> querySelectorAll(HtmlNode root, String selector) {
        List<HtmlNode> results = new ArrayList<>();
        querySelectorAllInternal(root, getOrParse(selector), results);
        return results;
    }

    private static void querySelectorAllInternal(HtmlNode node, Selector selector, List<HtmlNode> results) {
        if (SelectorMatcher.matches(selector, node)) results.add(node);
        for (HtmlNode child : node.children) {
            querySelectorAllInternal(child, selector, results);
        }
    }

    public static void clearCache() {
        SELECTOR_CACHE.clear();
    }
}
