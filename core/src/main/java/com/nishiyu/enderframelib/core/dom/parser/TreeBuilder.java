package com.nishiyu.enderframelib.core.dom.parser;

import static com.nishiyu.enderframelib.core.dom.parser.ParserText.MAX_RAW_TEXT;
import static com.nishiyu.enderframelib.core.dom.parser.ParserText.MAX_TEXT;
import static com.nishiyu.enderframelib.core.dom.parser.ParserText.isEnd;
import static com.nishiyu.enderframelib.core.dom.parser.ParserText.isStart;
import static com.nishiyu.enderframelib.core.dom.parser.ParserText.isText;
import static com.nishiyu.enderframelib.core.dom.parser.ParserText.isWhitespace;
import static com.nishiyu.enderframelib.core.dom.parser.ParserText.normalizeText;
import static com.nishiyu.enderframelib.core.dom.parser.TagSets.*;

import com.nishiyu.enderframelib.core.dom.node.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WHATWG HTML のツリー構築（§13.2.6）の 1 回分の状態と、文書の先頭の挿入モード・開いている要素のスタック・挿入。
 * 書式要素の一覧は {@link ActiveFormatting}、本文の挿入モードは {@link BodyMode}、表の挿入モードは {@link TableModes} が持つ
 * （どれもスタックを共有する）。
 */
final class TreeBuilder {

    /** 挿入モード（§13.2.4.1）。表の中の文字を集める in table text は {@link #insertText} が兼ねる。 */
    enum Mode {
        BEFORE_HEAD, IN_HEAD, AFTER_HEAD, IN_BODY,
        IN_TABLE, IN_CAPTION, IN_COLUMN_GROUP, IN_TABLE_BODY, IN_ROW, IN_CELL, IN_TEMPLATE
    }

    enum Scope { DEFAULT, LIST_ITEM, BUTTON, TABLE }

    final HtmlNode root;
    final List<HtmlNode> stack = new ArrayList<>();
    final boolean fragment;
    final String context;

    HtmlNode htmlEl;
    HtmlNode headEl;
    HtmlNode bodyEl;
    /** 表の中の「その他」を本文の規則で処理している間。挿入先が表なら表の前に出す。 */
    boolean fosterParenting;
    int nodeCount;
    /** active formatting elements の一覧と adoption agency。 */
    final ActiveFormatting fmt = new ActiveFormatting(this);
    /** 表の挿入モード。 */
    final TableModes tables = new TableModes(this);
    /** 本文の挿入モード（in body）。 */
    final BodyMode body = new BodyMode(this);

    final ParseLimits limits;

    TreeBuilder(boolean fragment, String context, ParseLimits limits) {
        this.limits = limits;
        this.fragment = fragment;
        this.context = context;
        this.root = fragment ? new HtmlNode(NodeType.ROOT, "root") : new HtmlDocument();
        root.setId("html_root");
        if (fragment) stack.add(root);
    }

    void run(String html) {
        for (HtmlTokenizer.Token token : HtmlTokenizer.tokenize(html)) {
            if (++nodeCount > limits.maxTokens()) {
                String msg = "HTML Parser: token limit (" + limits.maxTokens() + ") exceeded.";
                if (limits.strict()) throw new ParseLimits.HtmlLimitExceededException(msg);
                HtmlParser.LOGGER.warn("{} Truncating document.", msg);
                break;
            }
            process(token);
        }
    }

    // ---------- 挿入モード ----------

    /** 挿入モードを適切に戻す（§13.2.4.1）。断片ではスタックの底を文脈の要素とみなす。 */
    Mode mode() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            HtmlNode node = stack.get(i);
            boolean last = i == 0;
            String tag = last && fragment ? context : node.tag;
            switch (tag) {
                case "td", "th" -> { if (!last) return Mode.IN_CELL; }
                case "tr" -> { return Mode.IN_ROW; }
                case "tbody", "thead", "tfoot" -> { return Mode.IN_TABLE_BODY; }
                case "caption" -> { return Mode.IN_CAPTION; }
                case "colgroup" -> { return Mode.IN_COLUMN_GROUP; }
                case "table" -> { return Mode.IN_TABLE; }
                // template の中身の最初の要素がモードを決める。中に要素が開いたら本文の規則に戻る
                case "template" -> { return i == stack.size() - 1 ? Mode.IN_TEMPLATE : Mode.IN_BODY; }
                case "head" -> { if (!last) return Mode.IN_HEAD; }
                case "body" -> { return Mode.IN_BODY; }
                case "html" -> {
                    if (fragment) return Mode.IN_BODY;
                    if (headEl == null) return Mode.BEFORE_HEAD;
                    return bodyEl == null ? Mode.AFTER_HEAD : Mode.IN_BODY;
                }
                default -> { }
            }
        }
        if (!fragment && htmlEl == null) return Mode.BEFORE_HEAD;
        return Mode.IN_BODY;
    }

    void process(HtmlTokenizer.Token token) {
        if (!fragment && htmlEl == null) {
            if (token.type == HtmlTokenizer.Token.Type.TEXT && isWhitespace(token.text)) return;
            htmlEl = createElement("html", token.type == HtmlTokenizer.Token.Type.START_TAG && token.name.equals("html") ? token.attrs : Map.of());
            root.children.add(htmlEl);
            htmlEl.parent = root;
            stack.add(htmlEl);
            if (token.type == HtmlTokenizer.Token.Type.START_TAG && token.name.equals("html")) return;
        }
        dispatch(token, mode());
    }

    void dispatch(HtmlTokenizer.Token token, Mode mode) {
        switch (mode) {
            case BEFORE_HEAD -> beforeHead(token);
            case IN_HEAD -> inHead(token);
            case AFTER_HEAD -> afterHead(token);
            case IN_BODY -> body.inBody(token);
            case IN_TABLE -> tables.inTable(token);
            case IN_CAPTION -> tables.inCaption(token);
            case IN_COLUMN_GROUP -> tables.inColumnGroup(token);
            case IN_TABLE_BODY -> tables.inTableBody(token);
            case IN_ROW -> tables.inRow(token);
            case IN_CELL -> tables.inCell(token);
            case IN_TEMPLATE -> tables.inTemplate(token);
        }
    }

    // ---------- 文書の先頭（html / head / body） ----------

    void beforeHead(HtmlTokenizer.Token t) {
        if (isText(t) && isWhitespace(t.text)) return;
        if (isStart(t, "html")) { mergeAttributes(htmlEl, t.attrs); return; }
        if (isStart(t, "head")) { headEl = insertElement(t.name, t.attrs, false); return; }
        if (t.type == HtmlTokenizer.Token.Type.END_TAG && !Set.of("head", "body", "html", "br").contains(t.name)) return;
        headEl = insertElement("head", Map.of(), false);
        dispatch(t, Mode.IN_HEAD);
    }

    void inHead(HtmlTokenizer.Token t) {
        if (isText(t) && isWhitespace(t.text) && !t.isRawText) return;
        if (isStart(t, "html")) { mergeAttributes(htmlEl, t.attrs); return; }
        if (t.type == HtmlTokenizer.Token.Type.START_TAG && (HEAD_CONTENT.contains(t.name) || t.name.equals("noscript"))) {
            insertElement(t.name, t.attrs, VoidElements.isVoid(t.name));
            return;
        }
        if (t.type == HtmlTokenizer.Token.Type.END_TAG && t.name.equals("head")) { popUntil("head"); return; }
        if (isStart(t, "head")) return;
        // head の中の要素（style / title など）は自分の終了タグで閉じる
        if (t.type == HtmlTokenizer.Token.Type.END_TAG && !currentNode().tag.equals("head")) {
            if (currentNode().tag.equals(t.name)) stack.removeLast();
            return;
        }
        if (isText(t) && !currentNode().tag.equals("head")) { insertText(t); return; }
        if (t.type == HtmlTokenizer.Token.Type.END_TAG && !Set.of("body", "html", "br").contains(t.name)) return;
        popUntil("head");
        dispatch(t, Mode.AFTER_HEAD);
    }

    void afterHead(HtmlTokenizer.Token t) {
        if (isText(t) && isWhitespace(t.text)) return;
        if (isStart(t, "html")) { mergeAttributes(htmlEl, t.attrs); return; }
        if (isStart(t, "body")) { bodyEl = insertElement("body", t.attrs, false); return; }
        if (isStart(t, "head")) return;
        if (t.type == HtmlTokenizer.Token.Type.START_TAG && HEAD_CONTENT.contains(t.name)) {
            // head の後に書かれた head の要素は head に戻して入れる。中身を持つ要素（style / title）は
            // head ごと開いたままにし、その終了タグの後の in head の「その他」が head を閉じる
            stack.add(headEl);
            insertElement(t.name, t.attrs, VoidElements.isVoid(t.name));
            if (currentNode() == headEl) stack.removeLast();
            return;
        }
        if (t.type == HtmlTokenizer.Token.Type.END_TAG && !Set.of("body", "html", "br").contains(t.name)) return;
        ensureBody();
        dispatch(t, Mode.IN_BODY);
    }

    /** 文書に body が無ければ補う。空の文書も html / head / body を持つ。 */
    void ensureBody() {
        if (fragment || bodyEl != null) return;
        if (htmlEl == null) {
            htmlEl = createElement("html", Map.of());
            root.children.add(htmlEl);
            htmlEl.parent = root;
            stack.add(htmlEl);
        }
        if (headEl == null) {
            headEl = createElement("head", Map.of());
            htmlEl.children.add(headEl);
            headEl.parent = htmlEl;
        }
        while (stack.size() > 1) stack.removeLast();
        bodyEl = insertElement("body", Map.of(), false);
    }

    /** 開いている要素に {@code svg} / {@code math} があるか（外来要素の中か）。 */
    boolean inForeignContent() {
        for (int i = stack.size() - 1; i >= 0; i--) {
            String tag = stack.get(i).tag;
            if (tag.equals("svg") || tag.equals("math")) return true;
        }
        return false;
    }

    // ---------- スタックの操作（スタックの底は文書では html、断片では文脈を表すルートで、どちらも閉じない） ----------

    HtmlNode currentNode() {
        return stack.getLast();
    }

    boolean hasOpen(String tag) {
        for (int i = stack.size() - 1; i > 0; i--) {
            if (stack.get(i).tag.equals(tag)) return true;
        }
        return false;
    }

    boolean inScope(String tag, Scope scope) {
        for (int i = stack.size() - 1; i > 0; i--) {
            String t = stack.get(i).tag;
            if (t.equals(tag)) return true;
            if (scope == Scope.TABLE) {
                if (t.equals("table") || t.equals("template")) return false;
                continue;
            }
            if (SCOPE_BOUNDARY.contains(t)) return false;
            if (scope == Scope.LIST_ITEM && (t.equals("ol") || t.equals("ul"))) return false;
            if (scope == Scope.BUTTON && t.equals("button")) return false;
        }
        return false;
    }

    void generateImpliedEndTags(String except) {
        while (stack.size() > 1) {
            String t = currentNode().tag;
            if (!IMPLIED_END.contains(t) || t.equals(except)) return;
            stack.removeLast();
        }
    }

    void popUntil(String tag) {
        while (stack.size() > 1) {
            if (stack.removeLast().tag.equals(tag)) return;
        }
    }

    void popUntilAny(Set<String> tags) {
        while (stack.size() > 1) {
            if (tags.contains(stack.removeLast().tag)) return;
        }
    }

    /** 表の文脈まで戻す（clear the stack back to a … context）。底（html / 文脈）では止まる。 */
    void clearBackTo(Set<String> tags) {
        while (stack.size() > 1 && !tags.contains(currentNode().tag)) stack.removeLast();
    }

    // ---------- 挿入 ----------

    HtmlNode createElement(String tag, Map<String, String> attrs) {
        HtmlNode node = new HtmlElement(tag);
        for (var entry : attrs.entrySet()) {
            String val = entry.getValue();
            if (val != null && val.length() > 4096) val = val.substring(0, 4096);
            node.attrs.put(entry.getKey(), val);
        }
        return node;
    }

    HtmlNode insertElement(String tag, Map<String, String> attrs, boolean isVoid) {
        HtmlNode node = createElement(tag, attrs);
        insertNode(node);
        if (!isVoid) stack.add(node);
        if ("body".equals(tag) && !fragment) bodyEl = node;
        if (!isVoid && MARKER_ELEMENTS.contains(tag) && !inForeignContent()) fmt.pushMarker(node);
        return node;
    }

    /**
     * 適切な挿入先（§13.2.6.1）に入れる。表の中で表に直接入らない要素と文字は、
     * いちばん内側の表の直前に出す（foster parenting）。{@code template} は中身（content）に入れる。
     */
    void insertNode(HtmlNode node) {
        HtmlNode target = currentNode();
        if (fosterParenting && FOSTER_TARGETS.contains(target.tag)) {
            HtmlNode table = lastOpen("table");
            if (table != null && table.parent != null) {
                HtmlNode parent = table.parent;
                node.parent = parent;
                parent.children.add(parent.children.indexOf(table), node);
                return;
            }
        }
        HtmlNode host = childHost(target);
        node.parent = host;
        host.children.add(node);
    }

    /** 要素の子の置き場。{@code template} だけは不活性な content に置く。 */
    static HtmlNode childHost(HtmlNode node) {
        return node instanceof HtmlElement el && el.isTemplateElement() ? el.getContent() : node;
    }

    HtmlNode lastOpen(String tag) {
        for (int i = stack.size() - 1; i >= 0; i--) {
            if (stack.get(i).tag.equals(tag)) return stack.get(i);
        }
        return null;
    }

    /** 後から現れた {@code <html>} / {@code <body>} の属性は、まだ無いものだけ足す。 */
    void mergeAttributes(HtmlNode node, Map<String, String> attrs) {
        if (node == null) return;
        for (var entry : attrs.entrySet()) {
            if (!node.attrs.containsKey(entry.getKey())) {
                node.attrs.put(entry.getKey(), entry.getValue());
                if ("inert".equals(entry.getKey()) && root instanceof HtmlDocument doc) doc.noteInertAttribute(1);
            }
        }
    }

    void insertText(HtmlTokenizer.Token token) {
        HtmlNode target = currentNode();
        HtmlNode parent = childHost(target);
        boolean fostered = false;
        if (fosterParenting && FOSTER_TARGETS.contains(target.tag)) {
            HtmlNode table = lastOpen("table");
            if (table != null && table.parent != null) {
                parent = table.parent;
                fostered = true;
            }
        }
        String text = normalizeText(token, parent);
        // <style> / <script> の中身はシート全体が意味を持つので、通常テキストより大きい上限にする
        int maxText = token.isRawText ? MAX_RAW_TEXT : MAX_TEXT;
        if (text.length() > maxText) text = text.substring(0, maxText);
        if (text.isEmpty()) return;

        int at = fostered ? parent.children.indexOf(lastOpen("table")) : parent.children.size();
        // 捨てたタグを挟んだ文字（"a<tr>b"）や表の前に出した文字は、隣のテキストノードに繋げる
        HtmlNode previous = at > 0 ? parent.children.get(at - 1) : null;
        if (previous != null && previous.nodeType == NodeType.TEXT) {
            previous.text = previous.text + text;
        } else {
            HtmlNode textNode = new HtmlText(text);
            textNode.parent = parent;
            parent.children.add(at, textNode);
        }

        if ("textarea".equals(parent.tag) && !parent.attrs.containsKey("value")) {
            parent.attrs.put("value", text);
        }
    }
}
