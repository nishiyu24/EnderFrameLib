package com.nishiyu.enderframelib.core.css;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CSS を「プレリュード ＋ 波括弧ブロック」の列に切り出すトークナイザ。
 *
 * 以前の CSS_PATTERN 正規表現 ("([^{]+)\\{([^}]+)}") は、ネストした波括弧
 * (@media / @keyframes)・文字列リテラル・url() の中身を区別できなかった。
 * ここでは文字単位に走査して以下を正しく扱う。
 *
 * - ネストした波括弧（本文を丸ごと取り出し、呼び出し側が再帰的に処理する）
 * - '...' / "..." の文字列リテラル（中の波括弧・セミコロン・カンマは区切りではない）
 * - url(...)（引用符なしでもセミコロンや波括弧を含み得る）
 * - コメント（文字列リテラルの中のものは削除しない）
 */
public final class CssTokenizer {

    private CssTokenizer() {}

    /**
     * 1 つの波括弧ブロック。
     * prelude は波括弧の前の部分（セレクタリスト、または @media などのアットルール）、
     * body は波括弧の中身（ネストしたブロックを含み得る生テキスト）。
     */
    public record Block(String prelude, String body) {
        /** @media などのアットルールなら true。 */
        public boolean isAtRule() {
            return prelude.startsWith("@");
        }

        /** アットルール名（"@media" など）。アットルールでなければ空文字列。 */
        public String atRuleName() {
            if (!isAtRule()) return "";
            int end = 1;
            while (end < prelude.length() && !Character.isWhitespace(prelude.charAt(end))
                    && prelude.charAt(end) != '(' && prelude.charAt(end) != '{') {
                end++;
            }
            return prelude.substring(0, end);
        }

        /** アットルールの引数部（アットルール名を除いた条件式など）。 */
        public String atRuleParams() {
            return isAtRule() ? prelude.substring(atRuleName().length()).trim() : "";
        }
    }

    /** トップレベルのブロック列に切り出す。波括弧を伴わない @import 等は捨てる。 */
    public static List<Block> tokenize(String css) {
        List<Block> blocks = new ArrayList<>();
        if (css == null || css.isEmpty()) return blocks;

        String src = stripComments(css);
        int i = 0;
        int len = src.length();
        StringBuilder prelude = new StringBuilder();

        while (i < len) {
            char c = src.charAt(i);

            if (c == '"' || c == '\'') {
                int end = skipString(src, i);
                prelude.append(src, i, end);
                i = end;
            } else if (c == ';') {
                // 波括弧を伴わないアットルール（@import / @charset）や迷子の宣言は捨てる
                prelude.setLength(0);
                i++;
            } else if (c == '{') {
                int bodyStart = i + 1;
                int bodyEnd = findBlockEnd(src, bodyStart);
                blocks.add(new Block(prelude.toString().trim(), src.substring(bodyStart, bodyEnd)));
                prelude.setLength(0);
                i = bodyEnd < len ? bodyEnd + 1 : len; // 閉じ波括弧を読み飛ばす
            } else if (c == '}') {
                // 対応しない閉じ波括弧は無視する
                prelude.setLength(0);
                i++;
            } else {
                prelude.append(c);
                i++;
            }
        }
        return blocks;
    }

    /**
     * 宣言ブロックの本文を プロパティ -> 値 に分解する。
     * セミコロンの分割は文字列・丸括弧（url() や calc()）を考慮する。
     * 宣言順を保つため LinkedHashMap を返す。
     */
    public static Map<String, String> parseDeclarations(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return out;

        // style 属性のように未処理のテキストが渡ることもあるため、ここでもコメントを落とす
        for (String decl : splitTopLevel(stripComments(body), ';')) {
            int colon = indexOfTopLevel(decl, ':');
            if (colon <= 0) continue;
            String key = decl.substring(0, colon).trim().toLowerCase();
            String val = decl.substring(colon + 1).trim();
            if (key.isEmpty() || val.isEmpty()) continue;
            out.put(key, val);
        }
        return out;
    }

    /**
     * セレクタリストをカンマで分割する。
     * :not(a, b) や [title=","] の中のカンマでは分割しない。
     */
    public static List<String> splitSelectorList(String selectors) {
        List<String> out = new ArrayList<>();
        for (String s : splitTopLevel(selectors, ',')) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * 文字列・丸括弧・角括弧・波括弧の外側にある sep でのみ分割する。
     * 区切り文字自体は結果に含まれない。
     */
    public static List<String> splitTopLevel(String s, char sep) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;

        int depth = 0;
        int start = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipString(s, i);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
            else if (c == sep && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
            i++;
        }
        out.add(s.substring(start));
        return out;
    }

    /** 文字列・括弧の外側にある最初の target の位置。見つからなければ -1。 */
    public static int indexOfTopLevel(String s, char target) {
        int depth = 0;
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipString(s, i);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') depth = Math.max(0, depth - 1);
            else if (c == target && depth == 0) return i;
            i++;
        }
        return -1;
    }

    /**
     * start 位置（開き波括弧の直後）から対応する閉じ波括弧の位置を返す。
     * 見つからなければ文字列長（＝暗黙の閉じ）。
     */
    private static int findBlockEnd(String s, int start) {
        int depth = 1;
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipString(s, i);
                continue;
            }
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
            i++;
        }
        return s.length();
    }

    /** i 位置の引用符で始まる文字列を読み飛ばし、閉じ引用符の次の位置を返す。 */
    private static int skipString(String s, int i) {
        char quote = s.charAt(i);
        int j = i + 1;
        while (j < s.length()) {
            char c = s.charAt(j);
            if (c == '\\') {
                j += 2;
                continue;
            }
            if (c == quote) return j + 1;
            j++;
        }
        return s.length();
    }

    /** 文字列リテラルの中を壊さずにコメントを削除する。 */
    public static String stripComments(String css) {
        StringBuilder sb = new StringBuilder(css.length());
        int i = 0;
        while (i < css.length()) {
            char c = css.charAt(i);
            if (c == '"' || c == '\'') {
                int end = skipString(css, i);
                sb.append(css, i, end);
                i = end;
            } else if (c == '/' && i + 1 < css.length() && css.charAt(i + 1) == '*') {
                int end = css.indexOf("*/", i + 2);
                i = (end == -1) ? css.length() : end + 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }
}
