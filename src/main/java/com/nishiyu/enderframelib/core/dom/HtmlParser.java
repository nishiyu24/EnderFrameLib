package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.HtmlDocument.HtmlText;
import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WHATWG HTML のツリー構築（§13.2.6）の主要な規則で DOM を組む。
 *
 * 文書（{@link #parseDocument}）は暗黙の {@code html} / {@code head} / {@code body} を補い、
 * 断片（{@link #parseFragment}）は innerHTML と同じく文脈の要素の挿入モードから始める。
 * 挿入モードは状態として持たず、開いている要素のスタックから「挿入モードを適切に戻す」
 * 手順（§13.2.4.1）で毎回求める。表と本文のモードはスタックの形だけで決まるため。
 *
 * 何が壊れていたか: 以前は暗黙終了が {@code p} / {@code li} / {@code dt} / {@code dd} の
 * 「直上の要素が同じなら閉じる」だけで、暗黙の {@code tbody}・{@code tr} / {@code td} /
 * {@code option} の暗黙終了・表の中に誤って置かれた要素の扱いが無く、断片の文書には
 * {@code body} が無かった（ua.css の {@code body { margin: 8px }} が効かなかった）。
 *
 * LIMITATION(制約): 書式要素の再構築（active formatting elements）と adoption agency は無い。
 * {@code <p><b>a<p>b} の 2 つ目の段落は太字にならず、{@code <b><i></b></i>} の誤った入れ子は
 * 終了タグの一般規則（特殊な要素を越えて閉じない）で閉じる。SVG / MathML の外来要素も
 * HTML の要素として組み、{@code <template>} の中身は別の断片にせず子として持つ（ua.css が隠す）。
 * README の "Not supported" に対応。
 */
public class HtmlParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(HtmlParser.class);

    private static final int MAX_NODES = 5000;
    private static final int MAX_DEPTH = 100;

    private static final Set<String> PRESERVE_WHITESPACE_TAGS = Set.of("textarea", "pre", "code", "script", "style");

    /**
     * 断片を {@code body} の文脈で解析する（innerHTML と同じ）。
     * ルートの子が断片の最上位のノードになる。
     */
    public static HtmlNode parse(String html) {
        return parseFragment(html, "body");
    }

    /**
     * 文書として解析する。ルートの子は {@code html} の 1 つだけで、その下に {@code head} と {@code body} がある。
     * 書かれていなければ補う。
     */
    public static HtmlNode parseDocument(String html) {
        TreeBuilder builder = new TreeBuilder(false, null);
        builder.run(html);
        builder.ensureBody();
        return builder.root;
    }

    /**
     * 断片を文脈の要素（{@code contextTag}）の中身として解析する（HTML §13.4 の断片の解析）。
     * {@code tbody} の文脈なら {@code <tr>} がそのまま行になり、{@code template} の文脈なら
     * 表の部品を最上位に置ける。
     */
    public static HtmlNode parseFragment(String html, String contextTag) {
        String context = contextTag == null || contextTag.isEmpty() ? "body" : contextTag.toLowerCase();
        TreeBuilder builder = new TreeBuilder(true, context);
        builder.run(html);
        return builder.root;
    }

    // ==========================================
    // ツリー構築
    // ==========================================

    /** 挿入モード（§13.2.4.1）。表の中の文字を集める in table text は {@link TreeBuilder#text} が兼ねる。 */
    private enum Mode {
        BEFORE_HEAD, IN_HEAD, AFTER_HEAD, IN_BODY,
        IN_TABLE, IN_CAPTION, IN_COLUMN_GROUP, IN_TABLE_BODY, IN_ROW, IN_CELL, IN_TEMPLATE
    }

    /** 特殊な要素（§13.2.4.2）。終了タグの一般規則はこれを越えて閉じない。 */
    private static final Set<String> SPECIAL = Set.of(
            "address", "applet", "area", "article", "aside", "base", "basefont", "bgsound", "blockquote",
            "body", "br", "button", "caption", "center", "col", "colgroup", "dd", "details", "dir", "div",
            "dl", "dt", "embed", "fieldset", "figcaption", "figure", "footer", "form", "frame", "frameset",
            "h1", "h2", "h3", "h4", "h5", "h6", "head", "header", "hgroup", "hr", "html", "iframe", "img",
            "input", "keygen", "li", "link", "listing", "main", "marquee", "menu", "meta", "nav", "noembed",
            "noframes", "noscript", "object", "ol", "p", "param", "plaintext", "pre", "script", "search",
            "section", "select", "source", "style", "summary", "table", "tbody", "td", "template", "textarea",
            "tfoot", "th", "thead", "title", "tr", "track", "ul", "wbr", "xmp");

    /** 開始タグが開いている {@code p} を閉じる要素（in body の規則）。 */
    private static final Set<String> CLOSES_P = Set.of(
            "address", "article", "aside", "blockquote", "center", "details", "dialog", "dir", "div", "dl",
            "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "main", "menu", "nav", "ol", "p",
            "search", "section", "summary", "ul", "pre", "listing", "form", "table", "hr", "xmp");

    /** 終了タグがスコープ内の同名の要素まで閉じるブロック（in body の規則）。 */
    private static final Set<String> BLOCK_END_TAGS = Set.of(
            "address", "article", "aside", "blockquote", "button", "center", "details", "dialog", "dir", "div",
            "dl", "fieldset", "figcaption", "figure", "footer", "header", "hgroup", "listing", "main", "menu",
            "nav", "ol", "pre", "search", "section", "select", "summary", "ul", "form");

    private static final Set<String> HEADINGS = Set.of("h1", "h2", "h3", "h4", "h5", "h6");

    /** {@code head} に入る要素（in head の規則）。本文の中に書かれたらその場に置く。 */
    private static final Set<String> HEAD_CONTENT = Set.of(
            "base", "basefont", "bgsound", "link", "meta", "noframes", "script", "style", "template", "title");

    /** 暗黙に終了タグを生成する要素（§13.2.6.3）。 */
    private static final Set<String> IMPLIED_END = Set.of("dd", "dt", "li", "optgroup", "option", "p", "rb", "rp", "rt", "rtc");

    /** 既定のスコープの境界（§13.2.4.2）。 */
    private static final Set<String> SCOPE_BOUNDARY = Set.of("applet", "caption", "html", "table", "td", "th", "marquee", "object", "template");

    private static final Set<String> TABLE_SECTIONS = Set.of("tbody", "thead", "tfoot");
    private static final Set<String> TABLE_PARTS = Set.of("caption", "col", "colgroup", "tbody", "td", "tfoot", "th", "thead", "tr");
    /** これが現在のノードのとき、表の中の文字と要素は表の前へ出す（foster parenting）。 */
    private static final Set<String> FOSTER_TARGETS = Set.of("table", "tbody", "tfoot", "thead", "tr");

    private enum Scope { DEFAULT, LIST_ITEM, BUTTON, TABLE }

    private static final class TreeBuilder {
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

        TreeBuilder(boolean fragment, String context) {
            this.fragment = fragment;
            this.context = context;
            this.root = fragment ? new HtmlNode(NodeType.ROOT, "root") : new HtmlDocument();
            // パーサ呼び出しの度にIDカウンタをリセットし、安定したIDを生成する
            NodeId.resetCounter();
            root.setId("html_root");
            if (fragment) stack.add(root);
        }

        void run(String html) {
            for (HtmlTokenizer.Token token : HtmlTokenizer.tokenize(html)) {
                if (++nodeCount > MAX_NODES) {
                    LOGGER.warn("HTML Parser: MAX_NODES ({}) exceeded. Truncating document.", MAX_NODES);
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
                case IN_BODY -> inBody(token);
                case IN_TABLE -> inTable(token);
                case IN_CAPTION -> inCaption(token);
                case IN_COLUMN_GROUP -> inColumnGroup(token);
                case IN_TABLE_BODY -> inTableBody(token);
                case IN_ROW -> inRow(token);
                case IN_CELL -> inCell(token);
                case IN_TEMPLATE -> inTemplate(token);
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

        // ---------- in body ----------

        void inBody(HtmlTokenizer.Token t) {
            switch (t.type) {
                case TEXT -> insertText(t);
                case START_TAG -> inBodyStart(t);
                case END_TAG -> inBodyEnd(t);
            }
        }

        void inBodyStart(HtmlTokenizer.Token t) {
            String tag = t.name;
            if (stack.size() > MAX_DEPTH && !VoidElements.isVoid(tag)) return;

            switch (tag) {
                case "html" -> { if (!fragment) mergeAttributes(htmlEl, t.attrs); }
                case "body" -> { if (!fragment && bodyEl != null) mergeAttributes(bodyEl, t.attrs); }
                case "head", "caption", "col", "colgroup", "frame", "tbody", "td", "tfoot", "th", "thead", "tr" -> {
                    // 表の外の表の部品は捨てる（中身は親に入る）
                }
                case "li" -> {
                    closeListItems(Set.of("li"));
                    insertElement(tag, t.attrs, false);
                }
                case "dd", "dt" -> {
                    closeListItems(Set.of("dd", "dt"));
                    insertElement(tag, t.attrs, false);
                }
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    closePIfInButtonScope();
                    if (HEADINGS.contains(currentNode().tag)) stack.removeLast();
                    insertElement(tag, t.attrs, false);
                }
                case "form" -> {
                    if (hasOpen("form")) return;
                    closePIfInButtonScope();
                    insertElement(tag, t.attrs, false);
                }
                case "button" -> {
                    if (inScope("button", Scope.DEFAULT)) {
                        generateImpliedEndTags(null);
                        popUntil("button");
                    }
                    insertElement(tag, t.attrs, false);
                }
                case "a" -> {
                    // adoption agency の代わりに、開いている a を閉じてから入れる（a は入れ子にならない）
                    if (inScope("a", Scope.DEFAULT)) popUntil("a");
                    insertElement(tag, t.attrs, false);
                }
                case "nobr" -> {
                    if (inScope("nobr", Scope.DEFAULT)) popUntil("nobr");
                    insertElement(tag, t.attrs, false);
                }
                case "option" -> {
                    if (currentNode().tag.equals("option")) stack.removeLast();
                    insertElement(tag, t.attrs, false);
                }
                case "optgroup" -> {
                    if (currentNode().tag.equals("option")) stack.removeLast();
                    if (currentNode().tag.equals("optgroup") && hasOpen("select")) stack.removeLast();
                    insertElement(tag, t.attrs, false);
                }
                case "rb", "rtc" -> {
                    if (inScope("ruby", Scope.DEFAULT)) generateImpliedEndTags(null);
                    insertElement(tag, t.attrs, false);
                }
                case "rp", "rt" -> {
                    if (inScope("ruby", Scope.DEFAULT)) generateImpliedEndTags("rtc");
                    insertElement(tag, t.attrs, false);
                }
                case "image" -> insertElement("img", t.attrs, true);
                default -> {
                    if (CLOSES_P.contains(tag)) closePIfInButtonScope();
                    insertElement(tag, t.attrs, VoidElements.isVoid(tag));
                }
            }
        }

        void inBodyEnd(HtmlTokenizer.Token t) {
            String tag = t.name;
            switch (tag) {
                case "body", "html", "head" -> {
                    // </body> の後に書かれた内容も body に入る（after body が in body に戻すのと同じ）
                }
                case "p" -> {
                    if (!inScope("p", Scope.BUTTON)) {
                        // 開いていない </p> は空の段落になる
                        insertElement("p", Map.of(), false);
                    }
                    closeP();
                }
                case "li" -> {
                    if (!inScope("li", Scope.LIST_ITEM)) return;
                    generateImpliedEndTags("li");
                    popUntil("li");
                }
                case "dd", "dt" -> {
                    if (!inScope(tag, Scope.DEFAULT)) return;
                    generateImpliedEndTags(tag);
                    popUntil(tag);
                }
                case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                    if (HEADINGS.stream().noneMatch(h -> inScope(h, Scope.DEFAULT))) return;
                    generateImpliedEndTags(null);
                    while (stack.size() > 1 && !HEADINGS.contains(stack.removeLast().tag)) { }
                }
                case "br" -> insertElement("br", Map.of(), true);
                default -> {
                    if (BLOCK_END_TAGS.contains(tag)) {
                        if (!inScope(tag, Scope.DEFAULT)) return;
                        generateImpliedEndTags(null);
                        popUntil(tag);
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
                    generateImpliedEndTags(tag);
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
                    generateImpliedEndTags(node.tag);
                    while (stack.size() > i) stack.removeLast();
                    break;
                }
                if (SPECIAL.contains(node.tag) && !Set.of("address", "div", "p").contains(node.tag)) break;
            }
            closePIfInButtonScope();
        }

        void closePIfInButtonScope() {
            if (inScope("p", Scope.BUTTON)) closeP();
        }

        void closeP() {
            generateImpliedEndTags("p");
            popUntil("p");
        }

        // ---------- 表 ----------

        void inTable(HtmlTokenizer.Token t) {
            if (isText(t)) {
                if (FOSTER_TARGETS.contains(currentNode().tag) && !isWhitespace(t.text)) {
                    fosterParenting = true;
                    insertText(t);
                    fosterParenting = false;
                } else if (!FOSTER_TARGETS.contains(currentNode().tag)) {
                    insertText(t);
                }
                return;
            }
            String tag = t.name;
            if (t.type == HtmlTokenizer.Token.Type.START_TAG) {
                switch (tag) {
                    case "caption", "colgroup", "tbody", "tfoot", "thead" -> {
                        clearBackTo(Set.of("table", "template"));
                        insertElement(tag, t.attrs, false);
                        return;
                    }
                    case "col" -> {
                        clearBackTo(Set.of("table", "template"));
                        insertElement("colgroup", Map.of(), false);
                        dispatch(t, Mode.IN_COLUMN_GROUP);
                        return;
                    }
                    case "td", "th", "tr" -> {
                        clearBackTo(Set.of("table", "template"));
                        insertElement("tbody", Map.of(), false);
                        dispatch(t, Mode.IN_TABLE_BODY);
                        return;
                    }
                    case "table" -> {
                        if (!inScope("table", Scope.TABLE)) return;
                        popUntil("table");
                        dispatch(t, mode());
                        return;
                    }
                    case "style", "script", "template" -> {
                        insertElement(tag, t.attrs, false);
                        return;
                    }
                    case "input" -> {
                        if ("hidden".equalsIgnoreCase(t.attrs.getOrDefault("type", ""))) {
                            insertElement(tag, t.attrs, true);
                            return;
                        }
                    }
                    case "form" -> {
                        if (hasOpen("form")) return;
                        insertElement(tag, t.attrs, true);
                        return;
                    }
                    default -> { }
                }
            } else {
                switch (tag) {
                    case "table" -> {
                        if (inScope("table", Scope.TABLE)) popUntil("table");
                        return;
                    }
                    case "body", "caption", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr" -> {
                        return;
                    }
                    default -> { }
                }
            }
            // 表の中の「その他」は本文の規則で処理し、挿入先が表なら表の前へ出す
            boolean previous = fosterParenting;
            fosterParenting = true;
            inBody(t);
            fosterParenting = previous;
        }

        void inCaption(HtmlTokenizer.Token t) {
            boolean closesCaption = (t.type == HtmlTokenizer.Token.Type.START_TAG && TABLE_PARTS.contains(t.name))
                    || isEnd(t, "table") || isEnd(t, "caption");
            if (closesCaption) {
                if (!inScope("caption", Scope.TABLE)) return;
                generateImpliedEndTags(null);
                popUntil("caption");
                if (!isEnd(t, "caption")) dispatch(t, mode());
                return;
            }
            if (t.type == HtmlTokenizer.Token.Type.END_TAG && Set.of("body", "col", "colgroup", "html", "tbody", "td", "tfoot", "th", "thead", "tr").contains(t.name)) return;
            inBody(t);
        }

        void inColumnGroup(HtmlTokenizer.Token t) {
            if (isText(t) && isWhitespace(t.text)) return;
            if (isStart(t, "col")) { insertElement("col", t.attrs, true); return; }
            if (isEnd(t, "col")) return;
            if (isEnd(t, "colgroup")) {
                if (currentNode().tag.equals("colgroup")) stack.removeLast();
                return;
            }
            if (isStart(t, "template") || isEnd(t, "template")) { inBody(t); return; }
            if (!currentNode().tag.equals("colgroup")) return;
            stack.removeLast();
            dispatch(t, mode());
        }

        void inTableBody(HtmlTokenizer.Token t) {
            String tag = t.name;
            if (isStart(t, "tr")) {
                clearBackTo(Set.of("tbody", "tfoot", "thead", "template"));
                insertElement("tr", t.attrs, false);
                return;
            }
            if (isStart(t, "td") || isStart(t, "th")) {
                clearBackTo(Set.of("tbody", "tfoot", "thead", "template"));
                insertElement("tr", Map.of(), false);
                dispatch(t, Mode.IN_ROW);
                return;
            }
            if (t.type == HtmlTokenizer.Token.Type.END_TAG && TABLE_SECTIONS.contains(tag)) {
                if (!inScope(tag, Scope.TABLE)) return;
                clearBackTo(Set.of("tbody", "tfoot", "thead", "template"));
                popSection();
                return;
            }
            boolean leavesSection = (t.type == HtmlTokenizer.Token.Type.START_TAG && Set.of("caption", "col", "colgroup", "tbody", "tfoot", "thead").contains(tag))
                    || isEnd(t, "table");
            if (leavesSection) {
                if (TABLE_SECTIONS.stream().noneMatch(s -> inScope(s, Scope.TABLE))) return;
                clearBackTo(Set.of("tbody", "tfoot", "thead", "template"));
                popSection();
                dispatch(t, mode());
                return;
            }
            if (t.type == HtmlTokenizer.Token.Type.END_TAG && Set.of("body", "caption", "col", "colgroup", "html", "td", "th", "tr").contains(tag)) return;
            inTable(t);
        }

        void inRow(HtmlTokenizer.Token t) {
            String tag = t.name;
            if (isStart(t, "td") || isStart(t, "th")) {
                clearBackTo(Set.of("tr", "template"));
                insertElement(tag, t.attrs, false);
                return;
            }
            if (isEnd(t, "tr")) {
                if (!inScope("tr", Scope.TABLE)) return;
                clearBackTo(Set.of("tr", "template"));
                if (currentNode().tag.equals("tr")) stack.removeLast();
                return;
            }
            boolean leavesRow = (t.type == HtmlTokenizer.Token.Type.START_TAG && Set.of("caption", "col", "colgroup", "tbody", "tfoot", "thead", "tr").contains(tag))
                    || isEnd(t, "table")
                    || (t.type == HtmlTokenizer.Token.Type.END_TAG && TABLE_SECTIONS.contains(tag) && inScope(tag, Scope.TABLE));
            if (leavesRow) {
                if (!inScope("tr", Scope.TABLE)) return;
                clearBackTo(Set.of("tr", "template"));
                if (!currentNode().tag.equals("tr")) return;
                stack.removeLast();
                dispatch(t, mode());
                return;
            }
            if (t.type == HtmlTokenizer.Token.Type.END_TAG && Set.of("body", "caption", "col", "colgroup", "html", "td", "th", "tbody", "tfoot", "thead").contains(tag)) return;
            inTable(t);
        }

        void inCell(HtmlTokenizer.Token t) {
            String tag = t.name;
            if (isEnd(t, "td") || isEnd(t, "th")) {
                if (!inScope(tag, Scope.TABLE)) return;
                generateImpliedEndTags(null);
                popUntilAny(Set.of("td", "th"));
                return;
            }
            boolean leavesCell = (t.type == HtmlTokenizer.Token.Type.START_TAG && TABLE_PARTS.contains(tag))
                    || (t.type == HtmlTokenizer.Token.Type.END_TAG && Set.of("table", "tbody", "tfoot", "thead", "tr").contains(tag) && inScope(tag, Scope.TABLE));
            if (leavesCell) {
                if (!inScope("td", Scope.TABLE) && !inScope("th", Scope.TABLE)) return;
                generateImpliedEndTags(null);
                popUntilAny(Set.of("td", "th"));
                dispatch(t, mode());
                return;
            }
            if (t.type == HtmlTokenizer.Token.Type.END_TAG && Set.of("body", "caption", "col", "colgroup", "html", "table", "tbody", "tfoot", "thead", "tr").contains(tag)) return;
            inBody(t);
        }

        /** template の中身は最上位に表の部品を置ける（§13.2.6.4.18）。 */
        void inTemplate(HtmlTokenizer.Token t) {
            if (t.type == HtmlTokenizer.Token.Type.START_TAG) {
                switch (t.name) {
                    case "caption", "colgroup", "tbody", "tfoot", "thead", "tr", "td", "th" -> {
                        insertElement(t.name, t.attrs, false);
                        return;
                    }
                    case "col" -> {
                        insertElement("col", t.attrs, true);
                        return;
                    }
                    default -> { }
                }
            }
            inBody(t);
        }

        void popSection() {
            if (TABLE_SECTIONS.contains(currentNode().tag)) stack.removeLast();
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
            if (node.getId().isEmpty()) node.setId(NodeId.generateId());
            return node;
        }

        HtmlNode insertElement(String tag, Map<String, String> attrs, boolean isVoid) {
            HtmlNode node = createElement(tag, attrs);
            insertNode(node);
            if (!isVoid) stack.add(node);
            if ("body".equals(tag) && !fragment) bodyEl = node;
            return node;
        }

        /**
         * 適切な挿入先（§13.2.6.1）に入れる。表の中で表に直接入らない要素と文字は、
         * いちばん内側の表の直前に出す（foster parenting）。
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
            node.parent = target;
            target.children.add(node);
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
                boolean autoId = entry.getKey().equals("id") && node.getId().startsWith("gen_");
                if (autoId || !node.attrs.containsKey(entry.getKey())) node.attrs.put(entry.getKey(), entry.getValue());
            }
        }

        void insertText(HtmlTokenizer.Token token) {
            HtmlNode parent = currentNode();
            if (fosterParenting && FOSTER_TARGETS.contains(parent.tag)) {
                HtmlNode table = lastOpen("table");
                if (table != null && table.parent != null) parent = table.parent;
            }
            String text = normalizeText(token, parent);
            if (text.length() > 16384) text = text.substring(0, 16384);
            if (text.isEmpty()) return;

            int at = parent.children.size();
            if (parent != currentNode()) {
                at = parent.children.indexOf(lastOpen("table"));
            }
            // 捨てたタグを挟んだ文字（"a<tr>b"）や表の前に出した文字は、隣のテキストノードに繋げる
            HtmlNode previous = at > 0 ? parent.children.get(at - 1) : null;
            if (previous != null && previous.nodeType == NodeType.TEXT) {
                previous.text = previous.text + text;
            } else {
                HtmlNode textNode = new HtmlText(text);
                textNode.setId(NodeId.generateTextId());
                textNode.parent = parent;
                parent.children.add(at, textNode);
            }

            if ("textarea".equals(parent.tag) && !parent.attrs.containsKey("value")) {
                parent.attrs.put("value", text);
            }
        }

        static String normalizeText(HtmlTokenizer.Token token, HtmlNode parent) {
            boolean preserveWs = token.isRawText || PRESERVE_WHITESPACE_TAGS.contains(parent.tag);
            if (preserveWs) {
                String text = token.text.replace("\r\n", "\n");
                if (text.startsWith("\n")) text = text.substring(1);
                if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
                return text;
            }
            String raw = token.text.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" +", " ");
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                boolean inlineGap = !token.text.contains("\n") && !token.text.contains("\r") && !parent.children.isEmpty();
                return inlineGap ? " " : "";
            }
            char first = token.text.charAt(0);
            char last = token.text.charAt(token.text.length() - 1);
            boolean leadSpace = Character.isWhitespace(first) && first != '\n' && first != '\r';
            boolean trailSpace = Character.isWhitespace(last) && last != '\n' && last != '\r';
            return (leadSpace ? " " : "") + trimmed + (trailSpace ? " " : "");
        }

        static boolean isText(HtmlTokenizer.Token t) {
            return t.type == HtmlTokenizer.Token.Type.TEXT;
        }

        static boolean isStart(HtmlTokenizer.Token t, String tag) {
            return t.type == HtmlTokenizer.Token.Type.START_TAG && t.name.equals(tag);
        }

        static boolean isEnd(HtmlTokenizer.Token t, String tag) {
            return t.type == HtmlTokenizer.Token.Type.END_TAG && t.name.equals(tag);
        }

        static boolean isWhitespace(String s) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r' && c != '\f') return false;
            }
            return true;
        }
    }

    // ==========================================
    // 字句解析
    // ==========================================

    public static class HtmlTokenizer {

        /** 中身をタグとして読まない要素。RCDATA（textarea / title）は文字参照を解き、RAWTEXT は解かない。 */
        private static final Set<String> RCDATA_TAGS = Set.of("textarea", "title");
        private static final Set<String> RAWTEXT_TAGS = Set.of("script", "style", "xmp", "iframe", "noembed", "noframes");

        public static List<Token> tokenize(String html) {
            List<Token> tokens = new ArrayList<>();
            if (html == null || html.isEmpty()) return tokens;

            int i = 0;
            int len = html.length();
            StringBuilder textBuffer = new StringBuilder();

            while (i < len) {
                // コメントのスキップ <!-- ... -->
                if (html.startsWith("<!--", i)) {
                    flushText(tokens, textBuffer);
                    int end = html.indexOf("-->", i + 4);
                    if (end == -1) break;
                    i = end + 3;
                    continue;
                }

                // DOCTYPE等のスキップ <! ... >
                if (html.startsWith("<!", i) || html.startsWith("<?", i)) {
                    flushText(tokens, textBuffer);
                    int end = html.indexOf(">", i + 2);
                    if (end == -1) break;
                    i = end + 1;
                    continue;
                }

                // タグ名は ASCII の英字で始まる。"<3" や "< b" は文字のまま
                boolean isEndTag = html.startsWith("</", i);
                int nameStart = i + (isEndTag ? 2 : 1);
                if (html.charAt(i) != '<' || nameStart >= len || !isAsciiAlpha(html.charAt(nameStart))) {
                    if (isEndTag && nameStart < len && html.charAt(nameStart) == '>') {
                        // "</>" は捨てる
                        i = nameStart + 1;
                        continue;
                    }
                    textBuffer.append(html.charAt(i));
                    i++;
                    continue;
                }

                flushText(tokens, textBuffer);
                i = nameStart;
                while (i < len && isValidTagNameChar(html.charAt(i))) i++;
                String tagName = html.substring(nameStart, i).toLowerCase();

                Token t = new Token();
                t.type = isEndTag ? Token.Type.END_TAG : Token.Type.START_TAG;
                t.name = tagName;

                while (i < len) {
                    char c = html.charAt(i);
                    if (Character.isWhitespace(c)) {
                        i++;
                        continue;
                    }
                    if (c == '>') {
                        i++;
                        break;
                    }
                    if (c == '/' && i + 1 < len && html.charAt(i + 1) == '>') {
                        t.isSelfClosing = true;
                        i += 2;
                        break;
                    }

                    int attrNameStart = i;
                    while (i < len && isValidAttrNameChar(html.charAt(i))) i++;
                    if (attrNameStart == i) {
                        i++;
                        continue;
                    }
                    String attrName = html.substring(attrNameStart, i).toLowerCase();

                    while (i < len && Character.isWhitespace(html.charAt(i))) i++;

                    String attrValue = "";
                    if (i < len && html.charAt(i) == '=') {
                        i++;
                        while (i < len && Character.isWhitespace(html.charAt(i))) i++;
                        if (i < len) {
                            char q = html.charAt(i);
                            if (q == '"' || q == '\'') {
                                i++;
                                int attrValStart = i;
                                while (i < len && html.charAt(i) != q) i++;
                                attrValue = html.substring(attrValStart, i);
                                if (i < len) i++;
                            } else {
                                int attrValStart = i;
                                while (i < len && html.charAt(i) != '>' && !Character.isWhitespace(html.charAt(i))) i++;
                                attrValue = html.substring(attrValStart, i);
                            }
                        }
                    }
                    // 同じ名前の属性は最初のものが勝つ（§13.2.5.33）
                    t.attrs.putIfAbsent(attrName, CharacterReferences.decode(attrValue, true));
                }
                tokens.add(t);

                boolean rcdata = RCDATA_TAGS.contains(tagName);
                if (!isEndTag && (rcdata || RAWTEXT_TAGS.contains(tagName))) {
                    int closePos = findClosingTagIgnoreCase(html, i, tagName);
                    int contentEnd = closePos != -1 ? closePos : len;
                    String rawContent = html.substring(i, contentEnd);
                    if (!rawContent.isEmpty()) {
                        Token rawToken = new Token();
                        rawToken.type = Token.Type.TEXT;
                        // script / style の中の "&amp;" は文字参照ではない
                        rawToken.text = rcdata ? CharacterReferences.decode(rawContent, false) : rawContent;
                        rawToken.isRawText = true;
                        tokens.add(rawToken);
                    }
                    i = contentEnd;
                }
            }
            flushText(tokens, textBuffer);
            return tokens;
        }

        private static int findClosingTagIgnoreCase(String html, int fromIndex, String tagName) {
            String target = "</" + tagName;
            int targetLen = target.length();
            int max = html.length() - targetLen;
            for (int idx = fromIndex; idx <= max; idx++) {
                if (html.regionMatches(true, idx, target, 0, targetLen)) {
                    int after = idx + targetLen;
                    if (after == html.length()) return idx;
                    char nextChar = html.charAt(after);
                    if (nextChar == '>' || nextChar == '/' || Character.isWhitespace(nextChar)) {
                        return idx;
                    }
                }
            }
            return -1;
        }

        private static void flushText(List<Token> tokens, StringBuilder textBuffer) {
            if (!textBuffer.isEmpty()) {
                Token t = new Token();
                t.type = Token.Type.TEXT;
                t.text = CharacterReferences.decode(textBuffer.toString(), false);
                t.isRawText = false;
                tokens.add(t);
                textBuffer.setLength(0);
            }
        }

        /** 本文の中の文字参照を解く。 */
        public static String decodeHtmlEntities(String s) {
            return CharacterReferences.decode(s, false);
        }

        private static boolean isAsciiAlpha(char c) {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
        }

        private static boolean isValidTagNameChar(char c) {
            return c != '>' && c != '/' && !Character.isWhitespace(c);
        }

        private static boolean isValidAttrNameChar(char c) {
            return c != '=' && c != '>' && c != '/' && !Character.isWhitespace(c);
        }

        public static class Token {
            public Type type;
            public String name = "";
            public String text = "";
            public Map<String, String> attrs = new LinkedHashMap<>();
            public boolean isSelfClosing = false;
            public boolean isRawText = false;

            public enum Type {START_TAG, END_TAG, TEXT}
        }
    }

    /**
     * 文字参照（§13.2.5.72〜80）。名前付きは WHATWG の表（{@code entities.txt}、2,231 項目）を最長一致で引く。
     *
     * 何が壊れていたか: 以前は名前付きが {@code &lt;} / {@code &gt;} / {@code &quot;} / {@code &apos;} /
     * {@code &nbsp;} / {@code &amp;} の 6 つだけで（{@code &nbsp;} は普通の空白になっていた）、
     * {@code &copy;} や {@code &hellip;} は生のまま表示され、{@code ;} の無い数値参照も解かなかった。
     * 1 回の走査で解くので {@code &amp;lt;} は {@code &lt;} という文字列のまま残る。
     */
    public static final class CharacterReferences {

        private static final String TABLE_RESOURCE = "/com/nishiyu/enderframelib/core/dom/entities.txt";
        private static final int LONGEST_NAME = 32;

        /** 0x80〜0x9F の数値参照は Windows-1252 の文字として読む（§13.2.5.80）。0 は対応なし。 */
        private static final int[] C1_REPLACEMENTS = {
                0x20AC, 0, 0x201A, 0x0192, 0x201E, 0x2026, 0x2020, 0x2021, 0x02C6, 0x2030, 0x0160, 0x2039, 0x0152, 0, 0x017D, 0,
                0, 0x2018, 0x2019, 0x201C, 0x201D, 0x2022, 0x2013, 0x2014, 0x02DC, 0x2122, 0x0161, 0x203A, 0x0153, 0, 0x017E, 0x0178};

        private static volatile Map<String, String> table;

        private CharacterReferences() {
        }

        /**
         * @param inAttribute 属性値の中か。属性値では {@code ;} の無い名前の直後が {@code =} か英数字なら解かない
         *                    （{@code href="?a=1&copy=2"} の {@code &copy} を著作権記号にしないため）
         */
        public static String decode(String s, boolean inAttribute) {
            if (s == null || s.indexOf('&') < 0) return s;

            StringBuilder out = new StringBuilder(s.length());
            int i = 0;
            int len = s.length();
            while (i < len) {
                char c = s.charAt(i);
                if (c != '&' || i + 1 >= len) {
                    out.append(c);
                    i++;
                    continue;
                }
                int consumed = s.charAt(i + 1) == '#' ? decodeNumeric(s, i, out) : decodeNamed(s, i, out, inAttribute);
                if (consumed == 0) {
                    out.append('&');
                    i++;
                } else {
                    i += consumed;
                }
            }
            return out.toString();
        }

        /** 数値参照を解いて消費した文字数を返す。桁が 1 つも無ければ 0（参照ではない）。 */
        private static int decodeNumeric(String s, int start, StringBuilder out) {
            int i = start + 2;
            int radix = 10;
            if (i < s.length() && (s.charAt(i) == 'x' || s.charAt(i) == 'X')) {
                radix = 16;
                i++;
            }
            int digitsStart = i;
            long value = 0;
            while (i < s.length() && Character.digit(s.charAt(i), radix) >= 0) {
                if (value <= 0x10FFFF) value = value * radix + Character.digit(s.charAt(i), radix);
                i++;
            }
            if (i == digitsStart) return 0;
            if (i < s.length() && s.charAt(i) == ';') i++;

            int codePoint;
            if (value == 0 || value > 0x10FFFF || (value >= 0xD800 && value <= 0xDFFF)) {
                codePoint = 0xFFFD;
            } else if (value >= 0x80 && value <= 0x9F && C1_REPLACEMENTS[(int) value - 0x80] != 0) {
                codePoint = C1_REPLACEMENTS[(int) value - 0x80];
            } else {
                codePoint = (int) value;
            }
            out.appendCodePoint(codePoint);
            return i - start;
        }

        private static int decodeNamed(String s, int start, StringBuilder out, boolean inAttribute) {
            Map<String, String> names = table();
            int nameStart = start + 1;
            int maxEnd = Math.min(s.length(), nameStart + LONGEST_NAME);
            for (int end = maxEnd; end > nameStart; end--) {
                String candidate = s.substring(nameStart, end);
                String chars = names.get(candidate);
                if (chars == null) continue;
                if (inAttribute && !candidate.endsWith(";") && end < s.length()) {
                    char next = s.charAt(end);
                    if (next == '=' || Character.isLetterOrDigit(next)) return 0;
                }
                out.append(chars);
                return end - start;
            }
            return 0;
        }

        private static Map<String, String> table() {
            Map<String, String> loaded = table;
            if (loaded == null) {
                synchronized (CharacterReferences.class) {
                    loaded = table;
                    if (loaded == null) {
                        loaded = load();
                        table = loaded;
                    }
                }
            }
            return loaded;
        }

        private static Map<String, String> load() {
            Map<String, String> names = new HashMap<>(4096);
            try (InputStream is = CharacterReferences.class.getResourceAsStream(TABLE_RESOURCE)) {
                if (is == null) {
                    LOGGER.error("HTML の文字参照の表が見つかりません: {}", TABLE_RESOURCE);
                    return names;
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.US_ASCII));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    int space = line.indexOf(' ');
                    StringBuilder chars = new StringBuilder(2);
                    for (String hex : line.substring(space + 1).split(",")) {
                        chars.appendCodePoint(Integer.parseInt(hex, 16));
                    }
                    names.put(line.substring(0, space), chars.toString());
                }
            } catch (IOException e) {
                LOGGER.error("HTML の文字参照の表を読めませんでした", e);
            }
            return names;
        }
    }

    /**
     * 空要素（閉じタグを持たない要素）の定義。
     * 複数箇所にあった定義を1本化します。
     */
    public static class VoidElements {
        // HTML標準の空要素（§13.1.2）+ EnderFrame固有の空要素
        public static final Set<String> TAGS = Set.of(
                "area", "base", "basefont", "bgsound", "br", "col", "embed", "frame", "hr", "img", "input",
                "keygen", "link", "meta", "param", "source", "track", "wbr",
                "mc-fluid", "mc-entity", "mc-scene", "mc-slot", "mc-slot-grid", "mc-inventory", "mc-bar"
        );

        public static boolean isVoid(String tagName) {
            return TAGS.contains(tagName != null ? tagName.toLowerCase() : "");
        }
    }

    /**
     * ノードIDの生成と管理。
     * プロセスグローバルの単調増加をやめ、ドキュメント/セッション内で安定したIDを提供します。
     */
    public static class NodeId {
        // セッションや再パース時にリセットできるローカルカウンタ（パーサのインスタンスに持たせるのが理想ですが、移行用として）
        private static final ThreadLocal<AtomicInteger> localAutoId = ThreadLocal.withInitial(() -> new AtomicInteger(0));

        public static void resetCounter() {
            localAutoId.get().set(0);
        }

        public static String generateId() {
            return "gen_" + localAutoId.get().incrementAndGet();
        }

        public static String generateTextId() {
            return "txt_" + localAutoId.get().incrementAndGet();
        }
    }
}
