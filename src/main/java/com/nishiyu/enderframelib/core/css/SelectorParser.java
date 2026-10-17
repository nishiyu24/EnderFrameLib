package com.nishiyu.enderframelib.core.css;

import java.util.ArrayList;
import java.util.List;

/**
 * セレクタ文字列を {@link Selector} に変換するパーサ。
 *
 * 以前は {@code sel.contains(":hover")} → {@code sel.replace(":hover", "")} という
 * 破壊的な文字列手術を疑似クラスごとに繰り返し、最後に残ったものをタグ名として扱っていた。
 * そのため {@code [href$=".png"]} のような属性セレクタが丸ごと壊れて
 * 「マッチしない」ではなく「全要素にマッチする」という危険な壊れ方をしていた。
 * ここでは文字単位に走査して、タグ・ID・クラス・属性・疑似クラス・疑似要素を個別に取り出す。
 */
public final class SelectorParser {

    /** nth-child(odd) を表す内部値。 */
    public static final int NTH_ODD = -2;
    /** nth-child(even) を表す内部値。 */
    public static final int NTH_EVEN = -3;

    private SelectorParser() {}

    public static Selector parse(String raw) {
        return parse(raw, CascadeOrigin.AUTHOR, false);
    }

    public static Selector parse(String raw, CascadeOrigin origin, boolean important) {
        String text = raw == null ? "" : raw.trim();

        List<SelectorToken> compounds = new ArrayList<>();
        List<Character> combinators = new ArrayList<>();
        int[] spec = new int[3]; // {id, class, type}

        boolean isBefore = false;
        boolean isAfter = false;

        for (String part : splitByCombinators(text, combinators)) {
            SelectorToken token = new SelectorToken();
            PseudoElement pe = parseCompoundInto(part, token, spec);
            if (pe == PseudoElement.BEFORE) isBefore = true;
            if (pe == PseudoElement.AFTER) isAfter = true;
            compounds.add(token);
        }

        if (compounds.isEmpty()) {
            compounds.add(new SelectorToken());
        }

        boolean isHover = false;
        boolean isActive = false;
        boolean isFocus = false;
        for (SelectorToken t : compounds) {
            // A-2 修正: 結合子の有無に関わらず、ルール全体として状態フラグを立てる
            isHover |= t.parsedHover;
            isActive |= t.parsedActive;
            isFocus |= t.parsedFocus;
        }

        // 疑似要素は構造上の照合対象から外すため、セレクタ本体のテキストからは取り除いた形を保持する
        String bodyText = text;
        if (isBefore) bodyText = bodyText.replace("::before", "").replace(":before", "");
        if (isAfter) bodyText = bodyText.replace("::after", "").replace(":after", "");

        Specificity specificity = new Specificity(origin, important, spec[0], spec[1], spec[2]);
        return new Selector(bodyText.trim(), compounds, combinators, isBefore, isAfter,
                isHover, isActive, isFocus, specificity);
    }

    /** 後方互換用。複合セレクタ 1 つを既存のトークンに書き込む。 */
    public static void parseCompoundInto(String sel, SelectorToken out) {
        parseCompoundInto(sel, out, new int[3]);
    }

    private enum PseudoElement { NONE, BEFORE, AFTER, OTHER }

    /**
     * 複合セレクタ 1 つ（結合子を含まない）をパースして {@code out} に書き込み、
     * 詳細度の加算分を {@code spec} に足し込む。
     */
    private static PseudoElement parseCompoundInto(String sel, SelectorToken out, int[] spec) {
        out.rawSelector = sel;
        PseudoElement pseudoElement = PseudoElement.NONE;

        int i = 0;
        int len = sel.length();
        boolean tagSeen = false;

        while (i < len) {
            char c = sel.charAt(i);

            if (c == '*') {
                if (!tagSeen) {
                    out.tag = "*";
                    tagSeen = true;
                }
                i++;
            } else if (c == '#') {
                int end = readIdent(sel, i + 1);
                if (end > i + 1) {
                    out.id = sel.substring(i + 1, end);
                    spec[0]++;
                }
                i = Math.max(end, i + 1);
            } else if (c == '.') {
                int end = readIdent(sel, i + 1);
                if (end > i + 1) {
                    out.classes.add(sel.substring(i + 1, end));
                    spec[1]++;
                }
                i = Math.max(end, i + 1);
            } else if (c == '[') {
                int end = skipBalanced(sel, i, '[', ']');
                parseAttribute(sel.substring(i + 1, Math.max(i + 1, end - 1)), out);
                spec[1]++;
                i = end;
            } else if (c == ':') {
                boolean doubleColon = i + 1 < len && sel.charAt(i + 1) == ':';
                int nameStart = i + (doubleColon ? 2 : 1);
                int nameEnd = readIdent(sel, nameStart);
                String name = sel.substring(nameStart, nameEnd).toLowerCase();

                String arg = null;
                int next = nameEnd;
                if (nameEnd < len && sel.charAt(nameEnd) == '(') {
                    next = skipBalanced(sel, nameEnd, '(', ')');
                    arg = sel.substring(nameEnd + 1, Math.max(nameEnd + 1, next - 1)).trim();
                }

                PseudoElement pe = applyPseudo(name, arg, doubleColon, out, spec);
                if (pe != PseudoElement.NONE) pseudoElement = pe;
                i = Math.max(next, i + 1);
            } else if (Character.isWhitespace(c)) {
                i++;
            } else {
                int end = readIdent(sel, i);
                if (end > i) {
                    if (!tagSeen) {
                        out.tag = sel.substring(i, end);
                        tagSeen = true;
                        spec[2]++;
                    }
                    i = end;
                } else {
                    i++; // 解釈できない文字は読み飛ばす
                }
            }
        }
        return pseudoElement;
    }

    /** 疑似クラス／疑似要素 1 つをトークンに反映する。 */
    private static PseudoElement applyPseudo(String name, String arg, boolean doubleColon,
                                             SelectorToken out, int[] spec) {
        // ::before / :before（旧記法）はいずれも疑似要素として扱う
        if (name.equals("before")) {
            spec[2]++;
            return PseudoElement.BEFORE;
        }
        if (name.equals("after")) {
            spec[2]++;
            return PseudoElement.AFTER;
        }
        if (doubleColon) {
            // 未対応の疑似要素（::placeholder など）。詳細度だけ数えて照合には影響させない
            spec[2]++;
            return PseudoElement.OTHER;
        }

        switch (name) {
            case "hover" -> out.parsedHover = true;
            case "active" -> out.parsedActive = true;
            case "focus", "focus-visible", "focus-within" -> out.parsedFocus = true;
            case "checked" -> out.isChecked = true;
            case "disabled" -> out.isDisabled = true;
            case "first-child" -> out.isFirstChild = true;
            case "last-child" -> out.isLastChild = true;
            case "empty" -> out.isEmpty = true;
            case "root" -> out.isRoot = true;
            case "nth-child" -> out.nthChild = parseNth(arg);
            case "not" -> {
                if (arg != null && !arg.isEmpty()) {
                    // :not() 自身は詳細度を持たず、引数の詳細度が採用される（CSS 仕様）
                    for (String inner : CssTokenizer.splitSelectorList(arg)) {
                        SelectorToken notToken = new SelectorToken();
                        parseCompoundInto(inner, notToken, spec);
                        out.notSelectors.add(notToken);
                    }
                }
                return PseudoElement.NONE;
            }
            default -> {
                // 未対応の疑似クラスは照合に影響させないが、詳細度としては 1 クラス分数える
            }
        }
        spec[1]++;
        return PseudoElement.NONE;
    }

    private static int parseNth(String arg) {
        if (arg == null) return -1;
        String a = arg.trim().toLowerCase();
        if (a.equals("odd") || a.equals("2n+1")) return NTH_ODD;
        if (a.equals("even") || a.equals("2n")) return NTH_EVEN;
        try {
            return Integer.parseInt(a);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** 角括弧の中身（括弧自体は含まない）を属性条件としてトークンに反映する。 */
    private static void parseAttribute(String inner, SelectorToken out) {
        String body = inner.trim();
        if (body.isEmpty()) return;

        // 大文字小文字を無視する修飾子 "i" / "s" は現状未対応のため落とす
        if (body.endsWith(" i") || body.endsWith(" I") || body.endsWith(" s") || body.endsWith(" S")) {
            body = body.substring(0, body.length() - 2).trim();
        }

        int eq = body.indexOf('=');
        if (eq < 0) {
            out.requiredExistsAttrs.add(body);
            return;
        }

        char opChar = eq > 0 ? body.charAt(eq - 1) : '=';
        boolean hasOpPrefix = opChar == '^' || opChar == '$' || opChar == '*' || opChar == '~' || opChar == '|';
        String name = body.substring(0, hasOpPrefix ? eq - 1 : eq).trim();
        String value = unquote(body.substring(eq + 1).trim());
        if (name.isEmpty()) return;

        if (!hasOpPrefix) {
            out.requiredAttrs.put(name, value);
            return;
        }
        switch (opChar) {
            case '^' -> out.prefixAttrs.put(name, value);
            case '$' -> out.suffixAttrs.put(name, value);
            case '*' -> out.containsAttrs.put(name, value);
            case '~' -> out.wordAttrs.put(name, value);
            case '|' -> out.dashAttrs.put(name, value);
            default -> out.requiredAttrs.put(name, value);
        }
    }

    private static String unquote(String s) {
        if (s.length() >= 2) {
            char first = s.charAt(0);
            if ((first == '"' || first == '\'') && s.charAt(s.length() - 1) == first) {
                return s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    /**
     * セレクタを結合子で分割する。
     * 角括弧・丸括弧の中の空白や記号では分割しない。
     * 結合子は compounds の隣接ペアに対応する順で {@code combinatorsOut} に積まれる。
     */
    private static List<String> splitByCombinators(String sel, List<Character> combinatorsOut) {
        List<String> parts = new ArrayList<>();
        int i = 0;
        int len = sel.length();
        Character pending = null;

        while (i < len) {
            while (i < len && Character.isWhitespace(sel.charAt(i))) i++;
            if (i >= len) break;

            char c = sel.charAt(i);
            if (c == '>' || c == '+' || c == '~') {
                pending = c;
                i++;
                continue;
            }

            int start = i;
            while (i < len) {
                char d = sel.charAt(i);
                if (Character.isWhitespace(d) || d == '>' || d == '+' || d == '~') break;
                if (d == '[') {
                    i = skipBalanced(sel, i, '[', ']');
                    continue;
                }
                if (d == '(') {
                    i = skipBalanced(sel, i, '(', ')');
                    continue;
                }
                i++;
            }

            String part = sel.substring(start, i);
            if (part.isEmpty()) continue;
            if (!parts.isEmpty()) combinatorsOut.add(pending != null ? pending : ' ');
            parts.add(part);
            pending = null;
        }
        return parts;
    }

    /** 識別子（タグ名・クラス名・属性名などに使える文字）の終端位置。 */
    private static int readIdent(String s, int start) {
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c > 127) {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    /**
     * {@code start} 位置の {@code open} に対応する {@code close} の「次の」位置を返す。
     * 引用符の中は数えない。閉じが無ければ文字列長。
     */
    private static int skipBalanced(String s, int start, char open, char close) {
        int depth = 0;
        int i = start;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(s, i);
                continue;
            }
            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i + 1;
            }
            i++;
        }
        return s.length();
    }

    private static int skipQuoted(String s, int i) {
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
}
