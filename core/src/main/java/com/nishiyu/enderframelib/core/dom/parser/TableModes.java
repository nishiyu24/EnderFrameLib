package com.nishiyu.enderframelib.core.dom.parser;

import static com.nishiyu.enderframelib.core.dom.parser.ParserText.isEnd;
import static com.nishiyu.enderframelib.core.dom.parser.ParserText.isStart;
import static com.nishiyu.enderframelib.core.dom.parser.ParserText.isText;
import static com.nishiyu.enderframelib.core.dom.parser.ParserText.isWhitespace;
import static com.nishiyu.enderframelib.core.dom.parser.TagSets.FOSTER_TARGETS;
import static com.nishiyu.enderframelib.core.dom.parser.TagSets.TABLE_PARTS;
import static com.nishiyu.enderframelib.core.dom.parser.TagSets.TABLE_SECTIONS;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.TreeBuilder.Mode;
import com.nishiyu.enderframelib.core.dom.parser.TreeBuilder.Scope;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 表の挿入モード（in table / in caption / in column group / in table body / in row / in cell、§13.2.6.4.9〜15）と
 * template の中身（§13.2.6.4.18）。表に直接入らない「その他」は {@link BodyMode#inBody} の規則へ回し、
 * 挿入先が表なら表の前へ出す（foster parenting）。
 */
final class TableModes {

    private final TreeBuilder b;
    private final List<HtmlNode> stack;

    TableModes(TreeBuilder b) {
        this.b = b;
        this.stack = b.stack;
    }

    void inTable(HtmlTokenizer.Token t) {
        if (isText(t)) {
            if (FOSTER_TARGETS.contains(b.currentNode().tag) && !isWhitespace(t.text)) {
                b.fosterParenting = true;
                b.insertText(t);
                b.fosterParenting = false;
            } else if (!FOSTER_TARGETS.contains(b.currentNode().tag)) {
                b.insertText(t);
            }
            return;
        }
        String tag = t.name;
        if (t.type == HtmlTokenizer.Token.Type.START_TAG) {
            switch (tag) {
                case "caption", "colgroup", "tbody", "tfoot", "thead" -> {
                    b.clearBackTo(Set.of("table", "template"));
                    b.insertElement(tag, t.attrs, false);
                    return;
                }
                case "col" -> {
                    b.clearBackTo(Set.of("table", "template"));
                    b.insertElement("colgroup", Map.of(), false);
                    b.dispatch(t, Mode.IN_COLUMN_GROUP);
                    return;
                }
                case "td", "th", "tr" -> {
                    b.clearBackTo(Set.of("table", "template"));
                    b.insertElement("tbody", Map.of(), false);
                    b.dispatch(t, Mode.IN_TABLE_BODY);
                    return;
                }
                case "table" -> {
                    if (!b.inScope("table", Scope.TABLE)) return;
                    b.popUntil("table");
                    b.dispatch(t, b.mode());
                    return;
                }
                case "style", "script", "template" -> {
                    b.insertElement(tag, t.attrs, false);
                    return;
                }
                case "input" -> {
                    if ("hidden".equalsIgnoreCase(t.attrs.getOrDefault("type", ""))) {
                        b.insertElement(tag, t.attrs, true);
                        return;
                    }
                }
                case "form" -> {
                    if (b.hasOpen("form")) return;
                    b.insertElement(tag, t.attrs, true);
                    return;
                }
                default -> { }
            }
        } else {
            switch (tag) {
                case "table" -> {
                    if (b.inScope("table", Scope.TABLE)) b.popUntil("table");
                    return;
                }
                case "body", "caption", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr" -> {
                    return;
                }
                default -> { }
            }
        }
        // 表の中の「その他」は本文の規則で処理し、挿入先が表なら表の前へ出す
        boolean previous = b.fosterParenting;
        b.fosterParenting = true;
        b.body.inBody(t);
        b.fosterParenting = previous;
    }

    void inCaption(HtmlTokenizer.Token t) {
        boolean closesCaption = (t.type == HtmlTokenizer.Token.Type.START_TAG && TABLE_PARTS.contains(t.name))
                || isEnd(t, "table") || isEnd(t, "caption");
        if (closesCaption) {
            if (!b.inScope("caption", Scope.TABLE)) return;
            b.generateImpliedEndTags(null);
            b.popUntil("caption");
            if (!isEnd(t, "caption")) b.dispatch(t, b.mode());
            return;
        }
        if (t.type == HtmlTokenizer.Token.Type.END_TAG && Set.of("body", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr").contains(t.name)) return;
        b.body.inBody(t);
    }

    void inColumnGroup(HtmlTokenizer.Token t) {
        if (isText(t) && isWhitespace(t.text)) return;
        if (isStart(t, "col")) { b.insertElement("col", t.attrs, true); return; }
        if (isEnd(t, "col")) return;
        if (isEnd(t, "colgroup")) {
            if (b.currentNode().tag.equals("colgroup")) stack.removeLast();
            return;
        }
        if (isStart(t, "template") || isEnd(t, "template")) { b.body.inBody(t); return; }
        if (!b.currentNode().tag.equals("colgroup")) return;
        stack.removeLast();
        b.dispatch(t, b.mode());
    }

    void inTableBody(HtmlTokenizer.Token t) {
        String tag = t.name;
        if (isStart(t, "tr")) {
            b.clearBackTo(Set.of("tbody", "tfoot", "thead", "template"));
            b.insertElement("tr", t.attrs, false);
            return;
        }
        if (isStart(t, "td") || isStart(t, "th")) {
            b.clearBackTo(Set.of("tbody", "tfoot", "thead", "template"));
            b.insertElement("tr", Map.of(), false);
            b.dispatch(t, Mode.IN_ROW);
            return;
        }
        if (t.type == HtmlTokenizer.Token.Type.END_TAG && TABLE_SECTIONS.contains(tag)) {
            if (!b.inScope(tag, Scope.TABLE)) return;
            b.clearBackTo(Set.of("tbody", "tfoot", "thead", "template"));
            popSection();
            return;
        }
        boolean leavesSection = (t.type == HtmlTokenizer.Token.Type.START_TAG && Set.of("caption", "col", "colgroup", "tbody", "tfoot", "thead").contains(tag))
                || isEnd(t, "table");
        if (leavesSection) {
            if (TABLE_SECTIONS.stream().noneMatch(s -> b.inScope(s, Scope.TABLE))) return;
            b.clearBackTo(Set.of("tbody", "tfoot", "thead", "template"));
            popSection();
            b.dispatch(t, b.mode());
            return;
        }
        if (t.type == HtmlTokenizer.Token.Type.END_TAG && Set.of("body", "caption", "col", "colgroup", "html", "td", "th", "tr").contains(tag)) return;
        inTable(t);
    }

    void inRow(HtmlTokenizer.Token t) {
        String tag = t.name;
        if (isStart(t, "td") || isStart(t, "th")) {
            b.clearBackTo(Set.of("tr", "template"));
            b.insertElement(tag, t.attrs, false);
            return;
        }
        if (isEnd(t, "tr")) {
            if (!b.inScope("tr", Scope.TABLE)) return;
            b.clearBackTo(Set.of("tr", "template"));
            if (b.currentNode().tag.equals("tr")) stack.removeLast();
            return;
        }
        boolean leavesRow = (t.type == HtmlTokenizer.Token.Type.START_TAG && Set.of("caption", "col", "colgroup", "tbody", "tfoot", "thead", "tr").contains(tag))
                || isEnd(t, "table")
                || (t.type == HtmlTokenizer.Token.Type.END_TAG && TABLE_SECTIONS.contains(tag) && b.inScope(tag, Scope.TABLE));
        if (leavesRow) {
            if (!b.inScope("tr", Scope.TABLE)) return;
            b.clearBackTo(Set.of("tr", "template"));
            if (!b.currentNode().tag.equals("tr")) return;
            stack.removeLast();
            b.dispatch(t, b.mode());
            return;
        }
        if (t.type == HtmlTokenizer.Token.Type.END_TAG && Set.of("body", "caption", "col", "colgroup", "html", "td", "th", "tbody", "tfoot", "thead").contains(tag)) return;
        inTable(t);
    }

    void inCell(HtmlTokenizer.Token t) {
        String tag = t.name;
        if (isEnd(t, "td") || isEnd(t, "th")) {
            if (!b.inScope(tag, Scope.TABLE)) return;
            b.generateImpliedEndTags(null);
            b.popUntilAny(Set.of("td", "th"));
            return;
        }
        boolean leavesCell = (t.type == HtmlTokenizer.Token.Type.START_TAG && TABLE_PARTS.contains(tag))
                || (t.type == HtmlTokenizer.Token.Type.END_TAG && Set.of("table", "tbody", "tfoot", "thead", "tr").contains(tag) && b.inScope(tag, Scope.TABLE));
        if (leavesCell) {
            if (!b.inScope("td", Scope.TABLE) && !b.inScope("th", Scope.TABLE)) return;
            b.generateImpliedEndTags(null);
            b.popUntilAny(Set.of("td", "th"));
            b.dispatch(t, b.mode());
            return;
        }
        if (t.type == HtmlTokenizer.Token.Type.END_TAG && Set.of("body", "caption", "col", "colgroup", "html", "table", "tbody", "tfoot", "thead", "tr").contains(tag)) return;
        b.body.inBody(t);
    }

    /** template の中身は最上位に表の部品を置ける（§13.2.6.4.18）。 */
    void inTemplate(HtmlTokenizer.Token t) {
        if (t.type == HtmlTokenizer.Token.Type.START_TAG) {
            switch (t.name) {
                case "caption", "colgroup", "tbody", "tfoot", "thead", "tr", "td", "th" -> {
                    b.insertElement(t.name, t.attrs, false);
                    return;
                }
                case "col" -> {
                    b.insertElement("col", t.attrs, true);
                    return;
                }
                default -> { }
            }
        }
        b.body.inBody(t);
    }

    void popSection() {
        if (TABLE_SECTIONS.contains(b.currentNode().tag)) stack.removeLast();
    }
}
