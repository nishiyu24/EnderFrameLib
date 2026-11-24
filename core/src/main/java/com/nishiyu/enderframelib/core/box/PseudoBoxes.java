package com.nishiyu.enderframelib.core.box;

import com.nishiyu.enderframelib.core.css.CounterStyles;
import com.nishiyu.enderframelib.core.css.Style;
import com.nishiyu.enderframelib.core.dom.node.ElementTraits;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 疑似要素 {@code ::before} / {@code ::after} の箱（Engine v3 M2b-1。計画書 §3.4.2-5）。DOM に疑似の節は作らず、
 * {@link BoxNode#pseudo} の種類と持ち主の節（{@link BoxNode#node}）の組で表す。中身は {@code content} を評価した文字の節 1 つ。
 *
 * <p>前回の同じ種類の疑似要素の箱があり、スタイル・種類・文字が同じなら前回の参照をそのまま返す（下流のキャッシュが当たる）。
 * {@code content} の評価（文字列・{@code attr()}・{@code counter()}・{@code counters()}）は旧 {@code BoxTreeBuilder.evaluateContent}
 * のアルゴリズムの移植。{@code open-quote} などの引用符は旧経路と同じく何も出さない。</p>
 */
final class PseudoBoxes {
    private PseudoBoxes() { }

    /** 疑似要素を作る要素か（置換要素・値を持つフォーム部品・空要素には作らない。CSS 2.2 §12.1）。 */
    static boolean capable(HtmlNode owner) {
        String t = owner.tag == null ? "" : owner.tag.toLowerCase(Locale.ROOT);
        return !(ElementTraits.isValueControl(t) || ElementTraits.isBuiltinReplacedMedia(t) || t.equals("br") || t.equals("wbr"));
    }

    /**
     * 疑似要素 1 つの箱（作らないなら null）。{@code prev} は前回の同じ種類の箱、{@code counters} はカウンタを使う文書でだけ非 null
     * （疑似要素自身のカウンタの宣言を当ててから {@code content} を評価する）。
     */
    static BoxNode build(BoxNode.Pseudo which, HtmlNode owner, Style ownerStyle, BoxNode prev, StyleSource styles, Counters counters,
                         CounterStyles counterStyles) {
        Style st = styles.pseudo(owner, ownerStyle, which == BoxNode.Pseudo.AFTER);
        if (st == null) return null;
        if (counters != null) counters.apply(owner, st, false);
        String raw = evaluate(st.rare.content, owner, counters, counterStyles);
        BoxNode prevText = prev != null && prev.children.size() == 1 ? prev.children.get(0) : null;
        if (prev != null) st = st.shareEqualGroups(prev.style);
        BoxKind kind = BoxKind.of(owner, st);
        if (kind == BoxKind.NONE || kind == BoxKind.TEXT) return null;
        Style ts = styles.text(st);
        if (prevText != null) ts = ts.shareEqualGroups(prevText.style);
        String text = TextContent.of(raw, ts);
        if (prevText != null && prevText.text != null && prevText.text.equals(text)) text = prevText.text;
        if (prev != null && prev.style == st && prev.kind == kind && prevText != null && prevText.style == ts && prevText.text == text) {
            return prev;
        }
        BoxNode tb = new BoxNode(owner, ts, BoxKind.TEXT, text, BoxChunk.EMPTY, which, null, null, null);
        return new BoxNode(owner, st, kind, null, new BoxChunk(new BoxNode[]{tb}), which, null, null, null);
    }

    /** {@code content} の値（文字列・{@code attr()}・{@code counter()}・{@code counters()} の並び）を評価する。 */
    static String evaluate(String content, HtmlNode owner, Counters counters, CounterStyles cs) {
        if (content == null) return "";
        StringBuilder out = new StringBuilder();
        int i = 0, n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < n && content.charAt(end) != c) {
                    char ch = content.charAt(end);
                    if (ch == '\\' && end + 1 < n) ch = content.charAt(++end);
                    out.append(ch);
                    end++;
                }
                i = Math.min(n, end + 1);
            } else {
                int open = content.indexOf('(', i), space = indexOfWhitespace(content, i);
                if (open >= 0 && (space < 0 || open < space)) {
                    int close = matchingParen(content, open);
                    String name = content.substring(i, open).trim().toLowerCase(Locale.ROOT);
                    out.append(function(name, content.substring(open + 1, close).trim(), owner, counters, cs));
                    i = close + 1;
                } else { // open-quote などのキーワード: 引用符の入れ子は持たないので何も出さない
                    i = space < 0 ? n : space;
                }
            }
        }
        return out.toString();
    }

    private static String function(String name, String args, HtmlNode owner, Counters counters, CounterStyles cs) {
        List<String> parts = splitArgs(args);
        return switch (name) {
            case "attr" -> owner != null && !parts.isEmpty() ? owner.attrs.getOrDefault(parts.getFirst(), "") : "";
            case "counter" -> parts.isEmpty() ? ""
                    : cs.representation(counters != null ? counters.value(parts.get(0)) : 0, parts.size() > 1 ? parts.get(1) : "decimal");
            case "counters" -> {
                if (parts.size() < 2) yield "";
                String sep = unquote(parts.get(1)), style = parts.size() > 2 ? parts.get(2) : "decimal";
                List<String> formatted = new ArrayList<>();
                if (counters != null) for (int v : counters.values(parts.get(0))) formatted.add(cs.representation(v, style));
                if (formatted.isEmpty()) formatted.add(cs.representation(0, style));
                yield String.join(sep, formatted);
            }
            default -> "";
        };
    }

    private static List<String> splitArgs(String args) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < args.length(); i++) {
            char c = args.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
                cur.append(c);
            } else if (c == ',') {
                parts.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) parts.add(cur.toString().trim());
        return parts;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && (s.startsWith("\"") || s.startsWith("'")) ? s.substring(1, s.length() - 1) : s;
    }

    private static int indexOfWhitespace(String s, int from) {
        for (int i = from; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return s.length() - 1;
    }
}
