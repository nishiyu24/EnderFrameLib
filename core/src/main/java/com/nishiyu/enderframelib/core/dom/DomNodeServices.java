package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.css.Selector;
import com.nishiyu.enderframelib.core.css.SelectorMatcher;
import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.core.dom.node.DocumentState;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeServices;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import java.util.List;

/** {@link NodeServices} の同梱の実装（セレクタ・HTML の解析・対話状態は、ノードより上の層にある）。{@link java.util.ServiceLoader} で見つかる。 */
public final class DomNodeServices implements NodeServices {
    @Override
    public HtmlNode querySelector(HtmlNode root, String selectors) {
        return SelectorEngine.querySelector(root, selectors);
    }

    @Override
    public List<HtmlNode> querySelectorAll(HtmlNode root, String selectors) {
        return SelectorEngine.querySelectorAll(root, selectors);
    }

    @Override
    public boolean matches(HtmlElement element, String selectors) {
        for (Selector s : SelectorEngine.getOrParse(selectors)) {
            if (SelectorMatcher.matches(s, element)) return true;
        }
        return false;
    }

    @Override
    public DocumentState newDocumentState() {
        return new InteractionState();
    }

    @Override
    public HtmlNode parseFragment(String html, String contextTag) {
        return HtmlParser.parseFragment(html, contextTag);
    }
}
