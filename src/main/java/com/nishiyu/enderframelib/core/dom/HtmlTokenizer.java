package com.nishiyu.enderframelib.core.dom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HtmlTokenizer {

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
            if (html.startsWith("<!", i)) {
                flushText(tokens, textBuffer);
                int end = html.indexOf(">", i + 2);
                if (end == -1) break;
                i = end + 1;
                continue;
            }

            if (html.charAt(i) == '<') {
                flushText(tokens, textBuffer);
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
                    t.attrs.put(attrName, attrValue);
                }
                tokens.add(t);
            } else {
                textBuffer.append(html.charAt(i));
                i++;
            }
        }
        flushText(tokens, textBuffer);
        return tokens;
    }

    private static void flushText(List<Token> tokens, StringBuilder textBuffer) {
        if (!textBuffer.isEmpty()) {
            Token t = new Token();
            t.type = Token.Type.TEXT;
            t.text = textBuffer.toString();
            tokens.add(t);
            textBuffer.setLength(0);
        }
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

        public enum Type {START_TAG, END_TAG, TEXT}
    }
}