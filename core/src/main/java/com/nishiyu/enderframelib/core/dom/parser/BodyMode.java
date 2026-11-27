package com.nishiyu.enderframelib.core.dom.parser;

import static com.nishiyu.enderframelib.core.dom.parser.ParserText.isWhitespace;
import static com.nishiyu.enderframelib.core.dom.parser.TagSets.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.VoidElements;
import com.nishiyu.enderframelib.core.dom.parser.TreeBuilder.Scope;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 本文の挿入モード（in body、§13.2.6.4.7）: 開始タグ・終了タグごとの暗黙の終了・書式要素の再構築・入れ子の深さの上限。
 * 表の中の「その他」も {@link TableModes} からここへ回る。終了タグの一般規則（{@link #anyOtherEndTag}）は adoption agency
 * （{@link ActiveFormatting}）も使う。スタックと挿入は {@link TreeBuilder} のもの（M5-3a で {@code TreeBuilder} から出した）。
 */
final class BodyMode {

    private final TreeBuilder b;
    private final List<HtmlNode> stack;
    /** 入れ子の深さの上限を超えた警告を 1 回だけ出した。 */
    private boolean depthWarned;

    BodyMode(TreeBuilder b) {
        this.b = b;
        this.stack = b.stack;
    }

    void inBody(HtmlTokenizer.Token t) {
        switch (t.type) {
            case TEXT -> {
                if (!isWhitespace(t.text) && !t.isRawText) b.fmt.reconstructFormatting();
                b.insertText(t);
            }
            case START_TAG -> inBodyStart(t);
            case END_TAG -> inBodyEnd(t);
        }
    }

    void inBodyStart(HtmlTokenizer.Token t) {
        String tag = t.name;
        if (stack.size() > b.limits.maxDepth() && !VoidElements.isVoid(tag)) {
            String msg = "HTML Parser: nesting depth limit (" + b.limits.maxDepth() + ") exceeded at <" + tag + ">.";
            if (b.limits.strict()) throw new ParseLimits.HtmlLimitExceededException(msg);
            if (!depthWarned) {
                depthWarned = true;
                HtmlParser.LOGGER.warn("{} Dropping deeper elements.", msg);
            }
            return;
        }

        // SVG / MathML の外来要素は、HTML の暗黙終了や要素名の読み替えを受けず、自己終了タグ（{@code <rect/>}）で閉じる
        if (tag.equals("svg") || tag.equals("math") || b.inForeignContent()) {
            if (!b.inForeignContent()) b.fmt.reconstructFormatting();
            b.insertElement(tag, t.attrs, t.isSelfClosing);
            return;
        }

        switch (tag) {
            case "html" -> { if (!b.fragment) b.mergeAttributes(b.htmlEl, t.attrs); }
            case "body" -> { if (!b.fragment && b.bodyEl != null) b.mergeAttributes(b.bodyEl, t.attrs); }
            case "head", "caption", "col", "colgroup", "frame", "tbody", "td", "tfoot", "th", "thead", "tr" -> {
                // 表の外の表の部品は捨てる（中身は親に入る）
            }
            case "li" -> {
                closeListItems(Set.of("li"));
                b.insertElement(tag, t.attrs, false);
            }
            case "dd", "dt" -> {
                closeListItems(Set.of("dd", "dt"));
                b.insertElement(tag, t.attrs, false);
            }
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                closePIfInButtonScope();
                if (HEADINGS.contains(b.currentNode().tag)) stack.removeLast();
                b.insertElement(tag, t.attrs, false);
            }
            case "form" -> {
                if (b.hasOpen("form")) return;
                closePIfInButtonScope();
                b.insertElement(tag, t.attrs, false);
            }
            case "button" -> {
                if (b.inScope("button", Scope.DEFAULT)) {
                    b.generateImpliedEndTags(null);
                    b.popUntil("button");
                }
                b.fmt.reconstructFormatting();
                b.insertElement(tag, t.attrs, false);
            }
            case "a" -> {
                // 一覧に開きかけの a があれば adoption agency で閉じる（a は入れ子にならない）
                ActiveFormatting.FormatEntry open = b.fmt.lastFormatting("a");
                if (open != null) {
                    b.fmt.adoptionAgency("a");
                    b.fmt.formatting.remove(open);
                    stack.remove(open.node);
                }
                b.fmt.reconstructFormatting();
                b.fmt.pushFormatting(b.insertElement(tag, t.attrs, false), t.attrs);
            }
            case "b", "big", "code", "em", "font", "i", "s", "small", "strike", "strong", "tt", "u" -> {
                b.fmt.reconstructFormatting();
                b.fmt.pushFormatting(b.insertElement(tag, t.attrs, false), t.attrs);
            }
            case "nobr" -> {
                b.fmt.reconstructFormatting();
                if (b.inScope("nobr", Scope.DEFAULT)) {
                    b.fmt.adoptionAgency("nobr");
                    b.fmt.reconstructFormatting();
                }
                b.fmt.pushFormatting(b.insertElement(tag, t.attrs, false), t.attrs);
            }
            case "option" -> {
                if (b.currentNode().tag.equals("option")) stack.removeLast();
                b.fmt.reconstructFormatting();
                b.insertElement(tag, t.attrs, false);
            }
            case "optgroup" -> {
                if (b.currentNode().tag.equals("option")) stack.removeLast();
                if (b.currentNode().tag.equals("optgroup") && b.hasOpen("select")) stack.removeLast();
                b.fmt.reconstructFormatting();
                b.insertElement(tag, t.attrs, false);
            }
            case "rb", "rtc" -> {
                if (b.inScope("ruby", Scope.DEFAULT)) b.generateImpliedEndTags(null);
                b.insertElement(tag, t.attrs, false);
            }
            case "rp", "rt" -> {
                if (b.inScope("ruby", Scope.DEFAULT)) b.generateImpliedEndTags("rtc");
                b.insertElement(tag, t.attrs, false);
            }
            case "image" -> {
                b.fmt.reconstructFormatting();
                b.insertElement("img", t.attrs, true);
            }
            default -> {
                if (CLOSES_P.contains(tag)) closePIfInButtonScope();
                else b.fmt.reconstructFormatting();
                b.insertElement(tag, t.attrs, VoidElements.isVoid(tag));
            }
        }
    }

    void inBodyEnd(HtmlTokenizer.Token t) {
        String tag = t.name;
        switch (tag) {
            case "body", "html", "head" -> {
                // </body> の後に書かれた内容も body に入る（after body が in body に戻すのと同じ）
            }
            case "template" -> {
                // template の中身は content にあり、要素の子ではない。開いている子をまとめて閉じる
                if (!b.inScope("template", Scope.DEFAULT)) return;
                b.generateImpliedEndTags(null);
                b.popUntil("template");
            }
            case "p" -> {
                if (!b.inScope("p", Scope.BUTTON)) {
                    // 開いていない </p> は空の段落になる
                    b.insertElement("p", Map.of(), false);
                }
                closeP();
            }
            case "li" -> {
                if (!b.inScope("li", Scope.LIST_ITEM)) return;
                b.generateImpliedEndTags("li");
                b.popUntil("li");
            }
            case "dd", "dt" -> {
                if (!b.inScope(tag, Scope.DEFAULT)) return;
                b.generateImpliedEndTags(tag);
                b.popUntil(tag);
            }
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                if (HEADINGS.stream().noneMatch(h -> b.inScope(h, Scope.DEFAULT))) return;
                b.generateImpliedEndTags(null);
                while (stack.size() > 1 && !HEADINGS.contains(stack.removeLast().tag)) { }
            }
            case "br" -> {
                b.fmt.reconstructFormatting();
                b.insertElement("br", Map.of(), true);
            }
            case "a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small", "strike", "strong", "tt", "u" ->
                    b.fmt.adoptionAgency(tag);
            default -> {
                if (BLOCK_END_TAGS.contains(tag)) {
                    if (!b.inScope(tag, Scope.DEFAULT)) return;
                    b.generateImpliedEndTags(null);
                    b.popUntil(tag);
                    return;
                }
                anyOtherEndTag(tag);
            }
        }
    }


    /** 終了タグの一般規則: 同名の要素まで閉じる。途中に特殊な要素があれば無視する。 */
    void anyOtherEndTag(String tag) {
        for (int i = stack.size() - 1; i > 0; i--) {
            HtmlNode node = stack.get(i);
            if (node.tag.equals(tag)) {
                b.generateImpliedEndTags(tag);
                while (stack.size() > i) stack.removeLast();
                return;
            }
            if (SPECIAL.contains(node.tag)) return;
        }
    }

    /** {@code li} / {@code dd} / {@code dt} の開始で、開いている同類の項目を閉じる。 */
    void closeListItems(Set<String> siblings) {
        for (int i = stack.size() - 1; i > 0; i--) {
            HtmlNode node = stack.get(i);
            if (siblings.contains(node.tag)) {
                b.generateImpliedEndTags(node.tag);
                while (stack.size() > i) stack.removeLast();
                break;
            }
            if (SPECIAL.contains(node.tag) && !Set.of("address", "div", "p").contains(node.tag)) break;
        }
        closePIfInButtonScope();
    }

    void closePIfInButtonScope() {
        if (b.inScope("p", Scope.BUTTON)) closeP();
    }

    void closeP() {
        b.generateImpliedEndTags("p");
        b.popUntil("p");
    }
}
