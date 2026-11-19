package com.nishiyu.enderframelib.core.dom.parser;

import com.nishiyu.enderframelib.core.dom.*;
import com.nishiyu.enderframelib.core.dom.node.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** HTML の字句解析（タグ・文字・コメント・RCDATA / RAWTEXT の中身）。木の組み立ては {@link HtmlParser}。A8 で {@code HtmlParser} から分けた。 */
public class HtmlTokenizer {

    /** 中身をタグとして読まない要素。RCDATA（textarea / title）は文字参照を解き、RAWTEXT は解かない。 */
    private static final Set<String> RCDATA_TAGS = Set.of("textarea", "title");
    private static final Set<String> RAWTEXT_TAGS = Set.of("script", "style", "xmp", "iframe", "noembed", "noframes");

    public static List<Token> tokenize(String html) {
        List<Token> tokens = new ArrayList<>();
        if (html == null || html.isEmpty()) return tokens;

        // 先頭の BOM（U+FEFF）は符号化の印で、文書の文字ではない（Encoding §6 "decode"）
        int i = html.charAt(0) == '﻿' ? 1 : 0;
        int len = html.length();
        StringBuilder textBuffer = new StringBuilder();

        while (i < len) {
            // コメントのスキップ <!-- ... -->
            if (html.startsWith("<!--", i)) {
                flushText(tokens, textBuffer);
                // "<!-->" と "<!--->" は空のコメント（§13.2.5.43）。それ以外は "-->" か "--!>" まで
                if (html.startsWith(">", i + 4)) { i += 5; continue; }
                if (html.startsWith("->", i + 4)) { i += 6; continue; }
                int end = html.indexOf("-->", i + 4);
                int bang = html.indexOf("--!>", i + 4);
                if (bang >= 0 && (end < 0 || bang < end)) { i = bang + 4; continue; }
                // 閉じていないコメントは入力の終わりまで続く（残りは文書に入らない）
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
            // 要素ごとに同じ "li" や "id" の String が残らないよう、名前は 1 つに共有する（語彙は文書の大きさに依らず少ない）
            String tagName = html.substring(nameStart, i).toLowerCase().intern();

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
                String attrName = html.substring(attrNameStart, i).toLowerCase().intern();

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

    static int findClosingTagIgnoreCase(String html, int fromIndex, String tagName) {
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

    static boolean isAsciiAlpha(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    static boolean isValidTagNameChar(char c) {
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
