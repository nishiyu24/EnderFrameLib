package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文字だけの変更を、箱の木を作り直さず測り直せる「変わった箱」として集める（K5。旧 {@code LocalPatches.tryTextPatch} の条件部）。
 * 集めた箱は、再スタイルが見つけた変わった箱と同じ入口〔{@code LocalPatches.tryRelayoutInPlace}〕で測り直す。
 * 条件に合わないとき（{@code null}）は、呼び出し側が全体の再レイアウトに退く。
 */
final class TextChanges {
    private TextChanges() { }

    /** 反映待ちの文字の変更があるか。 */
    static boolean hasPending(UiView view) {
        synchronized (view.pendingTextNodes) {
            return !view.pendingTextNodes.isEmpty();
        }
    }

    /** 変わった文字の節の箱（{@code changed} は集合から取り出したので重複しない）。局所の測り直しで済まない（条件に合わない）なら null。 */
    static List<LayoutBox> boxes(UiView view, List<HtmlNode> changed) {
        Document doc = view.document;
        if (changed.isEmpty() || view.layoutRoot == null || doc == null || doc.root == null || doc.sheet == null) return null;
        if (doc.sheet.declaresContainers() || doc.sheet.usesContainerQueries()) return null;
        Map<HtmlNode, LayoutBox> index = view.nodeBoxIndex();
        List<LayoutBox> out = new ArrayList<>(changed.size());
        for (int i = 0, n = changed.size(); i < n; i++) {
            HtmlNode node = changed.get(i);
            if (node.text == null || node.text.isBlank() || !node.isConnected() || node.parent == null) return null;
            if (!isPatchableParent(node.parent)) return null;
            LayoutBox box = index.get(node);
            // 前回の文字が空白だけだった箱は、前回の木に今の形で入っていたか分からない。表の中の箱は索引の値が null
            if (box == null || box.displayText() == null || box.displayText().isBlank()) return null;
            out.add(box);
        }
        return out;
    }

    /** 文字の箱の親が、箱の寸法の測り直しだけで済む要素か（文字が箱の寸法以外の意味を持つ要素を除く）。 */
    static boolean isPatchableParent(HtmlNode parent) {
        if (parent.nodeType != com.nishiyu.enderframelib.core.dom.node.NodeType.ELEMENT) return false;
        String tag = parent.tag == null ? "" : parent.tag.toLowerCase(Locale.ROOT);
        return switch (tag) {
            case "textarea", "select", "option", "optgroup", "style", "script", "title", "noscript", "svg", "template",
                 "table", "thead", "tbody", "tfoot", "tr", "td", "th", "caption", "colgroup", "col",
                 "legend", "summary", "details", "pre" -> false;
            default -> true;
        };
    }
}
