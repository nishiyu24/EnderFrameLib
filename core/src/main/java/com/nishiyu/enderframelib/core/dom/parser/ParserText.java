package com.nishiyu.enderframelib.core.dom.parser;

import static com.nishiyu.enderframelib.core.dom.parser.TagSets.PRESERVE_WHITESPACE_TAGS;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

/** ツリー構築が読む字句の判定と、テキストの節に入れる文字の整え方。 */
final class ParserText {

    private ParserText() {}

    static final int MAX_TEXT = 16384;
    static final int MAX_RAW_TEXT = 1 << 20;

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
        // WhiteSpaceProcessing.normalize が畳む）。以前はここで畳んでいて、CSS で white-space: pre / pre-wrap を指定しても
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
