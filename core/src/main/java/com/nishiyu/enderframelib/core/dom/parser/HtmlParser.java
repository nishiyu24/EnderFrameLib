package com.nishiyu.enderframelib.core.dom.parser;

import com.nishiyu.enderframelib.core.dom.node.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * 書式要素（{@code a b big code em font i nobr s small strike strong tt u}）は、仕様どおり
 * active formatting elements の一覧に入れる（§13.2.4.3）。{@code <p><b>a<p>b} の 2 つ目の段落は太字になり
 * （再構築）、{@code <b><i></b></i>} や {@code <a><div></a>} の誤った入れ子は adoption agency（§13.2.6.4.7）で
 * 木を組み替える。{@code td} / {@code th} / {@code caption} / {@code applet} / {@code object} / {@code marquee} /
 * {@code template} の開始は一覧にマーカーを置き、その要素が閉じたらマーカー以降の項目を捨てる。
 *
 * {@code <template>} の中身は要素の子ではなく、不活性な {@code DocumentFragment}（content）に置く。
 * パーサの挿入先・{@code innerHTML} / {@code outerHTML}・{@code cloneNode}・ネットワークの直列化が
 * 同じ置き場を読み、{@code Bindings} は content の子を雛形にする。
 *
 * LIMITATION(制約): SVG / MathML の外来要素は HTML の暗黙終了を受けず自己終了タグで閉じるが、
 * 名前空間は持たない（要素名と属性名は小文字のまま。{@code SvgRenderer} が小文字で読む）。
 * README の "Not supported" に対応。
 */
public class HtmlParser {

    static final Logger LOGGER = LoggerFactory.getLogger(HtmlParser.class);

    /** 文脈が差し込む既定の上限（A13）。null なら {@link ParseLimits#DEFAULT}。 */
    public interface Source { ParseLimits parseLimits(); }
    private static volatile java.util.function.Supplier<Source> source = () -> () -> null;
    /** このスレッドの文脈の値を引く口を結ぶ（{@code EngineContext} が呼ぶ）。 */
    public static void bindSource(java.util.function.Supplier<Source> s) { source = s == null ? () -> () -> null : s; }

    public static ParseLimits defaultLimits() {
        ParseLimits l = source.get().parseLimits();
        return l != null ? l : ParseLimits.DEFAULT;
    }
    private static final int MAX_TEXT = 16384;
    private static final int MAX_RAW_TEXT = 1 << 20;

    private static final Set<String> PRESERVE_WHITESPACE_TAGS = Set.of("textarea", "pre", "code", "script", "style");

    /**
     * 断片を {@code body} の文脈で解析する（innerHTML と同じ）。
     * ルートの子が断片の最上位のノードになる。
     */
    public static HtmlNode parse(String html) {
        return parseFragment(html, "body");
    }

    public static HtmlNode parse(String html, ParseLimits limits) {
        return parseFragment(html, "body", limits);
    }

    /**
     * XHTML（XML として読む文書。ブラウザなら {@code application/xhtml+xml}・拡張子 {@code .xht} / {@code .xhtml}）を、
     * HTML として読んで同じ木になる文字列へ直す。本パーサは HTML の構文しか持たないので、XML の書き方のうち意味が変わる 2 つを書き換える:
     * <ul>
     *   <li>void でない要素の自己終了 {@code <div/>} → {@code <div></div>}（HTML では {@code /} が無視され、後ろの兄弟が子になる）。</li>
     *   <li>CDATA 節 {@code <![CDATA[...]]>} → 中身（{@code style}/{@code script} の中ではそのまま、それ以外では文字として逃がす）。</li>
     * </ul>
     * 文書が XHTML かどうかは内容からは決められない（{@code xmlns} 付きの text/html も多い）ので、呼び出し側が種類を知っているときに使う。
     */
    public static String xhtmlToHtml(String xhtml) {
        int len = xhtml.length();
        StringBuilder out = new StringBuilder(len + 64);
        int i = 0;
        while (i < len) {
            char c = xhtml.charAt(i);
            if (c != '<') { out.append(c); i++; continue; }
            if (xhtml.startsWith("<!--", i)) {
                int end = xhtml.indexOf("-->", i + 4);
                int stop = end < 0 ? len : end + 3;
                out.append(xhtml, i, stop);
                i = stop;
                continue;
            }
            if (xhtml.startsWith("<![CDATA[", i)) {
                int end = xhtml.indexOf("]]>", i + 9);
                int stop = end < 0 ? len : end;
                for (int k = i + 9; k < stop; k++) {
                    char d = xhtml.charAt(k);
                    out.append(d == '&' ? "&amp;" : d == '<' ? "&lt;" : String.valueOf(d));
                }
                i = end < 0 ? len : end + 3;
                continue;
            }
            int nameStart = i + 1;
            int j = nameStart;
            while (j < len && HtmlTokenizer.isValidTagNameChar(xhtml.charAt(j))) j++;
            if (j == nameStart || !HtmlTokenizer.isAsciiAlpha(xhtml.charAt(nameStart))) { out.append(c); i++; continue; }
            String name = xhtml.substring(nameStart, j).toLowerCase();
            // タグの終わり（引用符の中の '>' は飛ばす）
            char q = 0;
            int k = j;
            for (; k < len; k++) {
                char d = xhtml.charAt(k);
                if (q != 0) { if (d == q) q = 0; }
                else if (d == '"' || d == '\'') q = d;
                else if (d == '>') break;
            }
            if (k >= len) { out.append(xhtml, i, len); break; }
            boolean selfClosing = xhtml.charAt(k - 1) == '/';
            if (selfClosing && !VoidElements.isVoid(name)) {
                out.append(xhtml, i, k - 1).append("></").append(name).append('>');
                i = k + 1;
                continue;
            }
            out.append(xhtml, i, k + 1);
            i = k + 1;
            if (!selfClosing && (name.equals("style") || name.equals("script"))) {
                // 中身は生の文字。CDATA の印だけ外す（XML では印は中身に入らない）
                int close = HtmlTokenizer.findClosingTagIgnoreCase(xhtml, i, name);
                int stop = close < 0 ? len : close;
                out.append(xhtml.substring(i, stop).replace("<![CDATA[", "").replace("]]>", ""));
                i = stop;
            }
        }
        return out.toString();
    }

    /**
     * 文書として解析する。ルートの子は {@code html} の 1 つだけで、その下に {@code head} と {@code body} がある。
     * 書かれていなければ補う。
     */
    public static HtmlNode parseDocument(String html) {
        return parseDocument(html, defaultLimits());
    }

    public static HtmlNode parseDocument(String html, ParseLimits limits) {
        TreeBuilder builder = new TreeBuilder(false, null, limits);
        builder.run(html);
        builder.ensureBody();
        builder.root.sealNodeIds(); // 節の鍵は文書順の番号から導く。文字列を属性へ振らない
        return builder.root;
    }

    /**
     * 断片を文脈の要素（{@code contextTag}）の中身として解析する（HTML §13.4 の断片の解析）。
     * {@code tbody} の文脈なら {@code <tr>} がそのまま行になり、{@code template} の文脈なら
     * 表の部品を最上位に置ける。
     */
    public static HtmlNode parseFragment(String html, String contextTag) {
        return parseFragment(html, contextTag, defaultLimits());
    }

    public static HtmlNode parseFragment(String html, String contextTag, ParseLimits limits) {
        String context = contextTag == null || contextTag.isEmpty() ? "body" : contextTag.toLowerCase();
        TreeBuilder builder = new TreeBuilder(true, context, limits);
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
        /** active formatting elements（§13.2.4.3）。マーカーは {@link FormatEntry#marker} が true の項目。 */
        final List<FormatEntry> formatting = new ArrayList<>();

        final ParseLimits limits;
        boolean depthWarned;

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
                    LOGGER.warn("{} Truncating document.", msg);
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
                case TEXT -> {
                    if (!isWhitespace(t.text) && !t.isRawText) reconstructFormatting();
                    insertText(t);
                }
                case START_TAG -> inBodyStart(t);
                case END_TAG -> inBodyEnd(t);
            }
        }

        /** 開いている要素に {@code svg} / {@code math} があるか（外来要素の中か）。 */
        private boolean inForeignContent() {
            for (int i = stack.size() - 1; i >= 0; i--) {
                String tag = stack.get(i).tag;
                if (tag.equals("svg") || tag.equals("math")) return true;
            }
            return false;
        }

        void inBodyStart(HtmlTokenizer.Token t) {
            String tag = t.name;
            if (stack.size() > limits.maxDepth() && !VoidElements.isVoid(tag)) {
                String msg = "HTML Parser: nesting depth limit (" + limits.maxDepth() + ") exceeded at <" + tag + ">.";
                if (limits.strict()) throw new ParseLimits.HtmlLimitExceededException(msg);
                if (!depthWarned) {
                    depthWarned = true;
                    LOGGER.warn("{} Dropping deeper elements.", msg);
                }
                return;
            }

            // SVG / MathML の外来要素は、HTML の暗黙終了や要素名の読み替えを受けず、自己終了タグ（{@code <rect/>}）で閉じる
            if (tag.equals("svg") || tag.equals("math") || inForeignContent()) {
                if (!inForeignContent()) reconstructFormatting();
                insertElement(tag, t.attrs, t.isSelfClosing);
                return;
            }

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
                    reconstructFormatting();
                    insertElement(tag, t.attrs, false);
                }
                case "a" -> {
                    // 一覧に開きかけの a があれば adoption agency で閉じる（a は入れ子にならない）
                    FormatEntry open = lastFormatting("a");
                    if (open != null) {
                        adoptionAgency("a");
                        formatting.remove(open);
                        stack.remove(open.node);
                    }
                    reconstructFormatting();
                    pushFormatting(insertElement(tag, t.attrs, false), t.attrs);
                }
                case "b", "big", "code", "em", "font", "i", "s", "small", "strike", "strong", "tt", "u" -> {
                    reconstructFormatting();
                    pushFormatting(insertElement(tag, t.attrs, false), t.attrs);
                }
                case "nobr" -> {
                    reconstructFormatting();
                    if (inScope("nobr", Scope.DEFAULT)) {
                        adoptionAgency("nobr");
                        reconstructFormatting();
                    }
                    pushFormatting(insertElement(tag, t.attrs, false), t.attrs);
                }
                case "option" -> {
                    if (currentNode().tag.equals("option")) stack.removeLast();
                    reconstructFormatting();
                    insertElement(tag, t.attrs, false);
                }
                case "optgroup" -> {
                    if (currentNode().tag.equals("option")) stack.removeLast();
                    if (currentNode().tag.equals("optgroup") && hasOpen("select")) stack.removeLast();
                    reconstructFormatting();
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
                case "image" -> {
                    reconstructFormatting();
                    insertElement("img", t.attrs, true);
                }
                default -> {
                    if (CLOSES_P.contains(tag)) closePIfInButtonScope();
                    else reconstructFormatting();
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
                case "template" -> {
                    // template の中身は content にあり、要素の子ではない。開いている子をまとめて閉じる
                    if (!inScope("template", Scope.DEFAULT)) return;
                    generateImpliedEndTags(null);
                    popUntil("template");
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
                case "br" -> {
                    reconstructFormatting();
                    insertElement("br", Map.of(), true);
                }
                case "a", "b", "big", "code", "em", "font", "i", "nobr", "s", "small", "strike", "strong", "tt", "u" ->
                        adoptionAgency(tag);
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

        // ---------- 書式要素の一覧（§13.2.4.3）と adoption agency（§13.2.6.4.7） ----------

        /** マーカーを置く要素。これが閉じるとマーカー以降の項目を捨てる。 */
        private static final Set<String> MARKER_ELEMENTS = Set.of("applet", "caption", "marquee", "object", "td", "th", "template");

        /** 一覧の項目。マーカーは {@code node} がその要素（閉じたら項目ごと捨てる）、書式要素は作った時の属性を持つ。 */
        static final class FormatEntry {
            final boolean marker;
            final HtmlNode node;
            final String tag;
            final Map<String, String> attrs;

            FormatEntry(boolean marker, HtmlNode node, String tag, Map<String, String> attrs) {
                this.marker = marker;
                this.node = node;
                this.tag = tag;
                this.attrs = attrs;
            }
        }

        /** 持ち主の要素がスタックから消えたマーカーと、それ以降の項目を捨てる（マーカー以降を掃除する手順の代わり）。 */
        void pruneFormatting() {
            for (int i = formatting.size() - 1; i >= 0; i--) {
                FormatEntry e = formatting.get(i);
                if (e.marker && !stack.contains(e.node)) {
                    while (formatting.size() > i) formatting.removeLast();
                }
            }
        }

        /** 最後のマーカーより後で、{@code tag} の最後の項目。 */
        FormatEntry lastFormatting(String tag) {
            pruneFormatting();
            for (int i = formatting.size() - 1; i >= 0; i--) {
                FormatEntry e = formatting.get(i);
                if (e.marker) return null;
                if (e.tag.equals(tag)) return e;
            }
            return null;
        }

        void pushMarker(HtmlNode owner) {
            formatting.add(new FormatEntry(true, owner, owner.tag, Map.of()));
        }

        /** 書式要素を一覧に入れる。同じ要素名・同じ属性が最後のマーカー以降に 3 つあれば最も古いものを捨てる（Noah's Ark）。 */
        void pushFormatting(HtmlNode node, Map<String, String> attrs) {
            pruneFormatting();
            Map<String, String> copy = new LinkedHashMap<>(attrs);
            int same = 0, oldest = -1;
            for (int i = formatting.size() - 1; i >= 0; i--) {
                FormatEntry e = formatting.get(i);
                if (e.marker) break;
                if (e.tag.equals(node.tag) && e.attrs.equals(copy)) {
                    same++;
                    oldest = i;
                }
            }
            if (same >= 3) formatting.remove(oldest);
            formatting.add(new FormatEntry(false, node, node.tag, copy));
        }

        /** 一覧の書式要素のうちスタックに無いものを、開き直す（§13.2.4.3 の再構築）。 */
        void reconstructFormatting() {
            pruneFormatting();
            if (formatting.isEmpty()) return;
            FormatEntry last = formatting.getLast();
            if (last.marker || stack.contains(last.node)) return;
            int i = formatting.size() - 1;
            while (i > 0) {
                FormatEntry e = formatting.get(i - 1);
                if (e.marker || stack.contains(e.node)) break;
                i--;
            }
            for (; i < formatting.size(); i++) {
                FormatEntry old = formatting.get(i);
                HtmlNode fresh = insertElement(old.tag, old.attrs, false);
                formatting.set(i, new FormatEntry(false, fresh, old.tag, old.attrs));
            }
        }

        /** 誤って入れ子になった書式要素の終了タグで木を組み替える（外側 8 回・内側 3 回まで）。 */
        void adoptionAgency(String subject) {
            HtmlNode current = currentNode();
            if (current.tag.equals(subject) && formatting.stream().noneMatch(e -> !e.marker && e.node == current)) {
                stack.removeLast();
                return;
            }
            for (int outer = 0; outer < 8; outer++) {
                FormatEntry fe = lastFormatting(subject);
                if (fe == null) {
                    anyOtherEndTag(subject);
                    return;
                }
                int feIdx = stack.indexOf(fe.node);
                if (feIdx < 0) {
                    formatting.remove(fe);
                    return;
                }
                if (!inScope(subject, Scope.DEFAULT)) return;

                int blockIdx = -1;
                for (int i = feIdx + 1; i < stack.size(); i++) {
                    if (SPECIAL.contains(stack.get(i).tag)) {
                        blockIdx = i;
                        break;
                    }
                }
                if (blockIdx < 0) {
                    while (stack.size() > feIdx) stack.removeLast();
                    formatting.remove(fe);
                    return;
                }
                HtmlNode furthestBlock = stack.get(blockIdx);
                HtmlNode commonAncestor = stack.get(feIdx - 1);
                int bookmark = formatting.indexOf(fe);

                HtmlNode node = furthestBlock, lastNode = furthestBlock;
                int nodeIdx = blockIdx;
                for (int inner = 1; ; inner++) {
                    nodeIdx--;
                    node = stack.get(nodeIdx);
                    if (node == fe.node) break;
                    FormatEntry entry = entryOf(node);
                    if (inner > 3 && entry != null) {
                        int at = formatting.indexOf(entry);
                        formatting.remove(at);
                        if (at < bookmark) bookmark--;
                        entry = null;
                    }
                    if (entry == null) {
                        stack.remove(nodeIdx);
                        continue;
                    }
                    HtmlNode fresh = createElement(entry.tag, entry.attrs);
                    FormatEntry replaced = new FormatEntry(false, fresh, entry.tag, entry.attrs);
                    int at = formatting.indexOf(entry);
                    formatting.set(at, replaced);
                    stack.set(nodeIdx, fresh);
                    node = fresh;
                    if (lastNode == furthestBlock) bookmark = at + 1;
                    detach(lastNode);
                    lastNode.parent = node;
                    node.children.add(lastNode);
                    lastNode = node;
                }
                detach(lastNode);
                insertAt(commonAncestor, lastNode);

                HtmlNode fresh = createElement(fe.tag, fe.attrs);
                HtmlNode blockHost = childHost(furthestBlock);
                for (HtmlNode child : blockHost.children) child.parent = fresh;
                fresh.children.addAll(blockHost.children);
                blockHost.children.clear();
                fresh.parent = blockHost;
                blockHost.children.add(fresh);

                formatting.remove(fe);
                bookmark = Math.min(bookmark, formatting.size());
                formatting.add(bookmark, new FormatEntry(false, fresh, fe.tag, fe.attrs));
                stack.remove(fe.node);
                stack.add(stack.indexOf(furthestBlock) + 1, fresh);
            }
        }

        private FormatEntry entryOf(HtmlNode node) {
            for (FormatEntry e : formatting) if (!e.marker && e.node == node) return e;
            return null;
        }

        private static void detach(HtmlNode node) {
            if (node.parent != null) node.parent.children.remove(node);
            node.parent = null;
        }

        /** {@code target} の子として入れる。表の部品なら表の前に出す（foster parenting）。 */
        private void insertAt(HtmlNode target, HtmlNode node) {
            if (FOSTER_TARGETS.contains(target.tag)) {
                HtmlNode table = lastOpen("table");
                if (table != null && table.parent != null) {
                    node.parent = table.parent;
                    table.parent.children.add(table.parent.children.indexOf(table), node);
                    return;
                }
            }
            HtmlNode host = childHost(target);
            node.parent = host;
            host.children.add(node);
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
            return node;
        }

        HtmlNode insertElement(String tag, Map<String, String> attrs, boolean isVoid) {
            HtmlNode node = createElement(tag, attrs);
            insertNode(node);
            if (!isVoid) stack.add(node);
            if ("body".equals(tag) && !fragment) bodyEl = node;
            if (!isVoid && MARKER_ELEMENTS.contains(tag) && !inForeignContent()) pushMarker(node);
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

        /** 要素の間の改行を含む空白だけのテキストを節として残すか（調査用の切り替え）。 */
        static final boolean KEEP_NEWLINE_WHITESPACE = com.nishiyu.enderframelib.core.kernel.EngineFlags.KEEP_NEWLINE_WHITESPACE;

        static String normalizeText(HtmlTokenizer.Token token, HtmlNode parent) {
            boolean preserveWs = token.isRawText || PRESERVE_WHITESPACE_TAGS.contains(parent.tag);
            if (preserveWs) {
                String text = token.text.replace("\r\n", "\n");
                if (text.startsWith("\n")) text = text.substring(1);
                if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
                return text;
            }
            // 改行・タブも連続する空白も無い文字列は、正規表現（Matcher の生成・置換結果の確保）を通さない
            String raw = needsCollapse(token.text) ? token.text.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" +", " ") : token.text;
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                // 要素の間の空白だけのテキスト（改行を含む）も原文で残す。畳まれる空白なら 1 つの空白（inline-block の隙間）、
                // white-space: pre 系なら改行のまま。ブロックの間の空白はボックスツリーが捨てる。要素の先頭の空白は作らない
                if (parent.children.isEmpty()) return "";
                if (!KEEP_NEWLINE_WHITESPACE && (token.text.indexOf('\n') >= 0 || token.text.indexOf('\r') >= 0)) return "";
                String t = token.text;
                return t.indexOf('\r') >= 0 ? t.replace("\r\n", "\n").replace('\r', '\n') : t;
            }
            // 文字を含むテキストは原文のまま持つ（DOM の値は原文。空白の畳み方は white-space で決まり、レイアウトの
            // TextShaper.normalize が畳む）。以前はここで畳んでいて、CSS で white-space: pre / pre-wrap を指定しても
            // 空白の並びや tab が残らず、要素の後の改行（<b>a</b>⏎b）も空白にならなかった
            String t = token.text;
            return t.indexOf('\r') >= 0 ? t.replace("\r\n", "\n").replace('\r', '\n') : t;
        }

        /** 改行・タブがある、または空白が 2 つ以上続く（畳む必要がある）か。 */
        private static boolean needsCollapse(String s) {
            boolean prevSpace = false;
            for (int i = 0, n = s.length(); i < n; i++) {
                char c = s.charAt(i);
                if (c == '\r' || c == '\n' || c == '\t') return true;
                if (c == ' ') {
                    if (prevSpace) return true;
                    prevSpace = true;
                } else {
                    prevSpace = false;
                }
            }
            return false;
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
}
