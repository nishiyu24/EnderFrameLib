package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.HtmlNode.NodeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.atomic.AtomicInteger;

public class HtmlParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(HtmlParser.class);

    private static final int MAX_NODES = 5000;
    private static final int MAX_DEPTH = 100;

    // VoidElements に統合したため、ここでは PRESERVE_WHITESPACE_TAGS のみ定義
    private static final Set<String> PRESERVE_WHITESPACE_TAGS = Set.of("textarea", "pre", "code", "script", "style");

    public static HtmlNode parse(String html) {
        // パーサ呼び出しの度にIDカウンタをリセットし、安定したIDを生成する
        NodeId.resetCounter();

        HtmlNode root = new HtmlNode(NodeType.ROOT, "root");
        root.setId("html_root");
        Stack<HtmlNode> stack = new Stack<>();
        stack.push(root);

        int nodeCount = 0;

        for (HtmlTokenizer.Token token : HtmlTokenizer.tokenize(html)) {
            if (++nodeCount > MAX_NODES) {
                LOGGER.warn("HTML Parser: MAX_NODES ({}) exceeded. Truncating document.", MAX_NODES);
                break;
            }

            if (token.type == HtmlTokenizer.Token.Type.START_TAG) {
                if (stack.size() > MAX_DEPTH) continue;

                closeImpliedElements(stack, token.name);

                HtmlNode node = new HtmlNode(NodeType.ELEMENT, token.name);
                node.parent = stack.peek();

                for (var entry : token.attrs.entrySet()) {
                    String key = entry.getKey();
                    String val = entry.getValue();
                    if (val != null && val.length() > 4096) val = val.substring(0, 4096);
                    node.attrs.put(key, val);
                }

                if (node.getId().isEmpty()) {
                    node.setId(NodeId.generateId());
                }

                stack.peek().children.add(node);

                boolean isSelfClosing = token.isSelfClosing || VoidElements.isVoid(token.name);
                if (!isSelfClosing) {
                    stack.push(node);
                }

            } else if (token.type == HtmlTokenizer.Token.Type.END_TAG) {
                int matchIndex = -1;
                for (int i = stack.size() - 1; i > 0; i--) {
                    if (stack.get(i).tag.equals(token.name)) {
                        matchIndex = i;
                        break;
                    }
                }
                if (matchIndex != -1) {
                    while (stack.size() > matchIndex) stack.pop();
                }

            } else if (token.type == HtmlTokenizer.Token.Type.TEXT) {
                HtmlNode parent = stack.peek();
                boolean preserveWs = token.isRawText || PRESERVE_WHITESPACE_TAGS.contains(parent.tag);

                String text;
                if (preserveWs) {
                    text = token.text.replace("\r\n", "\n");
                    if (text.startsWith("\n")) text = text.substring(1);
                    if (text.endsWith("\n")) text = text.substring(0, text.length() - 1);
                } else {
                    String raw = token.text.replaceAll("[\\r\\n\\t]+", " ").replaceAll(" +", " ");
                    String trimmed = raw.trim();
                    if (trimmed.isEmpty()) {
                        if (!token.text.contains("\n") && !token.text.contains("\r") && !parent.children.isEmpty()) {
                            text = " ";
                        } else {
                            text = "";
                        }
                    } else {
                        boolean leadSpace = !token.text.isEmpty() && Character.isWhitespace(token.text.charAt(0))
                                && token.text.charAt(0) != '\n' && token.text.charAt(0) != '\r';
                        boolean trailSpace = !token.text.isEmpty() && Character.isWhitespace(token.text.charAt(token.text.length() - 1))
                                && token.text.charAt(token.text.length() - 1) != '\n' && token.text.charAt(token.text.length() - 1) != '\r';
                        text = (leadSpace ? " " : "") + trimmed + (trailSpace ? " " : "");
                    }
                }

                if (text.length() > 16384) text = text.substring(0, 16384);

                if (!text.isEmpty()) {
                    HtmlNode textNode = HtmlNode.createTextNode(text);
                    textNode.setId(NodeId.generateTextId());
                    textNode.parent = parent;
                    parent.children.add(textNode);

                    if ("textarea".equals(parent.tag) && !parent.attrs.containsKey("value")) {
                        parent.attrs.put("value", text);
                    }
                }
            }
        }
        return root;
    }

    /** {@code <p>} を暗黙に閉じる開始タグ（HTML の仕様に沿った最小限の集合）。 */
    private static final Set<String> CLOSES_PARAGRAPH = Set.of(
            "p", "div", "section", "article", "header", "footer", "main", "nav", "aside",
            "ul", "ol", "li", "dl", "table", "blockquote", "pre", "hr", "form", "fieldset",
            "h1", "h2", "h3", "h4", "h5", "h6", "details", "figure", "address");

    /**
     * 閉じタグが省略された {@code <p>} / {@code <li>} を、次の開始タグが来た時点で閉じる。
     *
     * これが無いと {@code <li>a<li>b} が入れ子のリストになり、
     * 1 つ目の項目の中に 2 つ目が入ってしまう。
     */
    private static void closeImpliedElements(Stack<HtmlNode> stack, String startingTag) {
        String tag = startingTag.toLowerCase();

        // <li> は、同じリスト内の次の <li> が始まったら閉じる
        if (tag.equals("li")) {
            if (stack.size() > 1 && "li".equals(stack.peek().tag)) stack.pop();
            return;
        }
        // <dt>/<dd> も同様に兄弟同士で閉じ合う
        if (tag.equals("dt") || tag.equals("dd")) {
            if (stack.size() > 1 && ("dt".equals(stack.peek().tag) || "dd".equals(stack.peek().tag))) stack.pop();
            return;
        }
        // <p> はブロック要素の開始で閉じる
        if (CLOSES_PARAGRAPH.contains(tag)) {
            while (stack.size() > 1 && "p".equals(stack.peek().tag)) stack.pop();
        }
    }

    public static class HtmlTokenizer {

        private static final Set<String> RAW_TEXT_TAGS = Set.of("textarea", "script", "style");

        public static List<Token> tokenize(String html) {
            List<Token> tokens = new ArrayList<>();
            if (html == null || html.isEmpty()) return tokens;

            int i = 0;
            int len = html.length();
            StringBuilder textBuffer = new StringBuilder();

            while (i < len) {
                // コメントのスキップ <!-- ... -->
                if (html.startsWith("<!--", i)) {
                    flushText(tokens, textBuffer, true); // true: エンティティデコードを行う
                    int end = html.indexOf("-->", i + 4);
                    if (end == -1) break;
                    i = end + 3;
                    continue;
                }

                // DOCTYPE等のスキップ <! ... >
                if (html.startsWith("<!", i)) {
                    flushText(tokens, textBuffer, true);
                    int end = html.indexOf(">", i + 2);
                    if (end == -1) break;
                    i = end + 1;
                    continue;
                }

                if (html.charAt(i) == '<') {
                    flushText(tokens, textBuffer, true);
                    i++;
                    boolean isEndTag = false;
                    if (i < len && html.charAt(i) == '/') {
                        isEndTag = true;
                        i++;
                    }

                    int nameStart = i;
                    while (i < len && isValidTagNameChar(html.charAt(i))) i++;
                    String tagName = html.substring(nameStart, i).toLowerCase();

                    if (tagName.isEmpty()) {
                        textBuffer.append('<');
                        if (isEndTag) textBuffer.append('/');
                        continue;
                    }

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
                        t.attrs.put(attrName, decodeHtmlEntities(attrValue));
                    }
                    tokens.add(t);

                    // script, style, textarea 内部の処理
                    if (!isEndTag && !t.isSelfClosing && RAW_TEXT_TAGS.contains(tagName)) {
                        int closePos = findClosingTagIgnoreCase(html, i, tagName);
                        if (closePos != -1) {
                            String rawContent = html.substring(i, closePos);
                            if (!rawContent.isEmpty()) {
                                Token rawToken = new Token();
                                rawToken.type = Token.Type.TEXT;
                                // ★ 修正: script / style の場合はエンティティを復号しない
                                if (tagName.equals("script") || tagName.equals("style")) {
                                    rawToken.text = rawContent;
                                } else {
                                    rawToken.text = decodeHtmlEntities(rawContent);
                                }
                                rawToken.isRawText = true;
                                tokens.add(rawToken);
                            }
                            i = closePos;
                        }
                    }
                } else {
                    textBuffer.append(html.charAt(i));
                    i++;
                }
            }
            flushText(tokens, textBuffer, true);
            return tokens;
        }

        private static int findClosingTagIgnoreCase(String html, int fromIndex, String tagName) {
            String target = "</" + tagName;
            int targetLen = target.length();
            int max = html.length() - targetLen;
            for (int idx = fromIndex; idx <= max; idx++) {
                if (html.regionMatches(true, idx, target, 0, targetLen)) {
                    int after = idx + targetLen;
                    if (after < html.length()) {
                        char nextChar = html.charAt(after);
                        if (nextChar == '>' || Character.isWhitespace(nextChar)) {
                            return idx;
                        }
                    }
                }
            }
            return -1;
        }

        // 引数を追加し、エンティティデコードするかどうかを制御
        private static void flushText(List<Token> tokens, StringBuilder textBuffer, boolean decodeEntities) {
            if (!textBuffer.isEmpty()) {
                Token t = new Token();
                t.type = Token.Type.TEXT;
                t.text = decodeEntities ? decodeHtmlEntities(textBuffer.toString()) : textBuffer.toString();
                t.isRawText = false;
                tokens.add(t);
                textBuffer.setLength(0);
            }
        }

        public static String decodeHtmlEntities(String s) {
            if (s == null || !s.contains("&")) return s;

            // 数値参照を先に解く。名前付きより後にすると "&amp;#39;" が二重に復号されてしまう。
            // 名前付きの中では &amp; を最後に置き、"&amp;lt;" が "<" にならないようにする。
            return decodeNumericReferences(s)
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&apos;", "'")
                    .replace("&nbsp;", " ")
                    .replace("&amp;", "&");
        }

        /**
         * 数値文字参照（{@code &#38;} / {@code &#x1F600;}）を復号する。
         * 以前は {@code &#39;} だけを個別に置換していたため、それ以外は生のまま表示されていた。
         */
        private static String decodeNumericReferences(String s) {
            if (!s.contains("&#")) return s;

            StringBuilder out = new StringBuilder(s.length());
            int i = 0;
            while (i < s.length()) {
                if (s.charAt(i) != '&' || i + 2 >= s.length() || s.charAt(i + 1) != '#') {
                    out.append(s.charAt(i));
                    i++;
                    continue;
                }

                int digitsStart = i + 2;
                int radix = 10;
                if (s.charAt(digitsStart) == 'x' || s.charAt(digitsStart) == 'X') {
                    radix = 16;
                    digitsStart++;
                }

                int end = digitsStart;
                while (end < s.length() && Character.digit(s.charAt(end), radix) >= 0) end++;

                // 桁が 1 つも無い、または ';' で閉じていないものは参照として扱わない
                if (end == digitsStart || end >= s.length() || s.charAt(end) != ';' || end - digitsStart > 7) {
                    out.append(s.charAt(i));
                    i++;
                    continue;
                }

                try {
                    int codePoint = Integer.parseInt(s.substring(digitsStart, end), radix);
                    if (Character.isValidCodePoint(codePoint)) {
                        out.appendCodePoint(codePoint);
                    } else {
                        out.append(s, i, end + 1);
                    }
                } catch (NumberFormatException e) {
                    out.append(s, i, end + 1);
                }
                i = end + 1;
            }
            return out.toString();
        }

        private static boolean isValidTagNameChar(char c) {
            return Character.isLetterOrDigit(c) || c == '-';
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
     * 空要素（閉じタグを持たない要素）の定義。
     * 複数箇所にあった定義を1本化します。
     */
    public static class VoidElements {
        // HTML標準の空要素 + EnderFrame固有の空要素
        public static final Set<String> TAGS = Set.of(
                "br", "hr", "img", "input", "meta", "link",
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