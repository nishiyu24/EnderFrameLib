package com.nishiyu.enderframelib.core.dom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HtmlTokenizer {

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