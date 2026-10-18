package com.nishiyu.enderframelib.core.css;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code @media} と {@code @supports} の条件式の評価。
 *
 * ブロックの切り出し自体は {@link CssTokenizer} が行うため、以前の
 * 「@media の中身を文字列として平坦化してから正規表現で再マッチする」処理は不要になった。
 * ここに残るのは条件の真偽判定だけ。
 *
 * 評価規則は CSS に従い、1 つのクエリ内の {@code and} は AND、
 * クエリリストの {@code ,} は OR。
 *
 * 何が壊れていたか: 以前は {@code min-width} / {@code max-width} を正規表現で拾うだけで、
 * それ以外の特性（{@code prefers-*}・{@code orientation}・{@code height} など）と
 * {@code @supports} の条件は「無いもの」として常に真になっていた。
 * {@code @media (prefers-color-scheme: dark)} と {@code (prefers-color-scheme: light)} の
 * 両方のルールが同時に効いていた。
 */
public final class AtRuleProcessor {

    private AtRuleProcessor() {}

    /** 幅だけが分かっている呼び出し用。 */
    public static boolean evaluateMediaQuery(String condition, int rootW) {
        return evaluateMediaQuery(condition, MediaEnvironment.of(rootW, 0));
    }

    /**
     * メディアクエリリストを評価する。
     *
     * @param condition {@code @media} を除いた条件部（{@code "(min-width: 500px) and (max-width: 1000px)"} など）
     */
    public static boolean evaluateMediaQuery(String condition, MediaEnvironment env) {
        if (condition == null) return true;
        String cond = condition.trim();
        if (cond.toLowerCase(Locale.ROOT).startsWith("@media")) cond = cond.substring("@media".length()).trim();
        if (cond.isEmpty()) return true;

        // クエリリストのカンマは OR
        for (String query : CssTokenizer.splitSelectorList(cond)) {
            if (evaluateSingleQuery(query, env)) return true;
        }
        return false;
    }

    /**
     * {@code [not | only]? <media-type> [and <condition>]?} または {@code <condition>}。
     * メディアタイプは {@code all} と {@code screen} が真、それ以外（{@code print} など）は偽。
     */
    private static boolean evaluateSingleQuery(String query, MediaEnvironment env) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.startsWith("(") || q.startsWith("not (") || q.startsWith("not(")) {
            return evaluateCondition(q, feature -> evaluateMediaFeature(feature, env));
        }

        boolean negated = false;
        if (q.startsWith("only ")) {
            q = q.substring(5).trim();
        } else if (q.startsWith("not ")) {
            negated = true;
            q = q.substring(4).trim();
        }
        int sp = q.indexOf(' ');
        String type = sp < 0 ? q : q.substring(0, sp);
        String rest = sp < 0 ? "" : q.substring(sp + 1).trim();

        boolean result = "all".equals(type) || "screen".equals(type);
        if (!rest.isEmpty()) {
            if (!rest.startsWith("and")) return false; // 構文として無効なクエリは not all
            result &= evaluateCondition(rest.substring(3).trim(), feature -> evaluateMediaFeature(feature, env));
        }
        return negated != result;
    }

    /** {@code @supports} の条件部を評価する。 */
    public static boolean evaluateSupports(String condition) {
        if (condition == null || condition.isBlank()) return false;
        return evaluateCondition(condition.trim(), AtRuleProcessor::evaluateSupportsFeature);
    }

    /** 括弧の中身を評価する関数（{@code @media} と {@code @supports} で中身の意味だけが違う）。 */
    private interface FeatureTest {
        boolean test(String inner);
    }

    /**
     * {@code not <in-parens>} / {@code <in-parens> [and <in-parens>]*} / {@code <in-parens> [or <in-parens>]*}。
     * {@code <in-parens>} は入れ子の条件か、括弧で囲んだ特性。
     */
    private static boolean evaluateCondition(String condition, FeatureTest test) {
        String c = condition.trim();
        String lower = c.toLowerCase(Locale.ROOT);
        if (lower.startsWith("not ") || lower.startsWith("not(")) {
            return !evaluateInParens(c.substring(3).trim(), test);
        }

        List<String> operands = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        int i = 0;
        while (i < c.length()) {
            while (i < c.length() && Character.isWhitespace(c.charAt(i))) i++;
            if (i >= c.length()) break;
            int end = c.charAt(i) == '(' ? matchingParen(c, i) + 1 : functionEnd(c, i);
            if (end <= i) return false;
            operands.add(c.substring(i, end));
            i = end;
            while (i < c.length() && Character.isWhitespace(c.charAt(i))) i++;
            if (i >= c.length()) break;
            int wordEnd = i;
            while (wordEnd < c.length() && Character.isLetter(c.charAt(wordEnd))) wordEnd++;
            operators.add(c.substring(i, wordEnd).toLowerCase(Locale.ROOT));
            i = wordEnd;
        }
        if (operands.isEmpty()) return false;

        boolean isAnd = operators.stream().allMatch("and"::equals);
        boolean isOr = operators.stream().allMatch("or"::equals);
        if (!isAnd && !isOr) return false; // and と or の混在は括弧が要る（CSS の規定）

        boolean result = isAnd;
        for (String operand : operands) {
            boolean v = evaluateInParens(operand, test);
            result = isAnd ? result && v : result || v;
        }
        return result;
    }

    private static boolean evaluateInParens(String s, FeatureTest test) {
        String t = s.trim();
        if (t.startsWith("(") && matchingParen(t, 0) == t.length() - 1) {
            String inner = t.substring(1, t.length() - 1).trim();
            String lower = inner.toLowerCase(Locale.ROOT);
            if (inner.startsWith("(") || lower.startsWith("not ") || lower.startsWith("not(")) {
                return evaluateCondition(inner, test);
            }
            return test.test(inner);
        }
        // selector(...) などの関数形
        return test.test(t);
    }

    // ---- @media の特性

    private static boolean evaluateMediaFeature(String feature, MediaEnvironment env) {
        String f = feature.trim().toLowerCase(Locale.ROOT);
        int colon = f.indexOf(':');
        if (colon < 0) {
            if (f.contains("<") || f.contains(">") || f.contains("=")) return evaluateRange(f, env);
            return evaluateBooleanFeature(f, env);
        }
        String name = f.substring(0, colon).trim();
        String value = f.substring(colon + 1).trim();

        switch (name) {
            case "width", "min-width", "max-width" -> {
                return compare(name, env.width(), parseLength(value));
            }
            case "height", "min-height", "max-height" -> {
                return compare(name, env.height(), parseLength(value));
            }
            case "aspect-ratio", "min-aspect-ratio", "max-aspect-ratio" -> {
                double ratio = parseRatio(value);
                double actual = env.height() > 0 ? (double) env.width() / env.height() : Double.NaN;
                return !Double.isNaN(ratio) && !Double.isNaN(actual) && compare(name, actual, ratio);
            }
            case "orientation" -> {
                boolean portrait = env.height() >= env.width();
                return "portrait".equals(value) ? portrait : "landscape".equals(value) && !portrait;
            }
            case "prefers-color-scheme" -> {
                return "dark".equals(value) ? env.prefersDark() : "light".equals(value) && !env.prefersDark();
            }
            case "prefers-reduced-motion" -> {
                return "reduce".equals(value) ? env.prefersReducedMotion()
                        : "no-preference".equals(value) && !env.prefersReducedMotion();
            }
            case "prefers-contrast", "forced-colors", "prefers-reduced-transparency", "prefers-reduced-data" -> {
                return "no-preference".equals(value) || "none".equals(value);
            }
            // Minecraft はマウスで操作する（精密なポインタがあり、ホバーできる）
            case "hover", "any-hover" -> {
                return "hover".equals(value);
            }
            case "pointer", "any-pointer" -> {
                return "fine".equals(value);
            }
            case "color" -> {
                return parseLength(value) <= 8;
            }
            case "scripting" -> {
                return "none".equals(value);
            }
            default -> {
                return false; // 未知の特性は偽（CSS の規定）
            }
        }
    }

    private static boolean evaluateBooleanFeature(String name, MediaEnvironment env) {
        return switch (name) {
            case "width" -> env.width() > 0;
            case "height" -> env.height() > 0;
            case "color", "hover", "any-hover", "pointer", "any-pointer" -> true;
            case "prefers-reduced-motion" -> env.prefersReducedMotion();
            default -> false;
        };
    }

    /** 範囲構文 {@code (width >= 600px)} / {@code (400px < width <= 700px)}。 */
    private static boolean evaluateRange(String f, MediaEnvironment env) {
        List<String> parts = new ArrayList<>();
        List<String> ops = new ArrayList<>();
        int i = 0;
        int start = 0;
        while (i < f.length()) {
            char ch = f.charAt(i);
            if (ch == '<' || ch == '>' || ch == '=') {
                parts.add(f.substring(start, i).trim());
                int opEnd = i + 1;
                if (opEnd < f.length() && f.charAt(opEnd) == '=') opEnd++;
                ops.add(f.substring(i, opEnd));
                i = start = opEnd;
            } else {
                i++;
            }
        }
        parts.add(f.substring(start).trim());
        if (parts.size() != ops.size() + 1 || parts.size() < 2 || parts.size() > 3) return false;

        boolean result = true;
        for (int k = 0; k < ops.size(); k++) {
            double left = rangeOperand(parts.get(k), env);
            double right = rangeOperand(parts.get(k + 1), env);
            if (Double.isNaN(left) || Double.isNaN(right)) return false;
            result &= switch (ops.get(k)) {
                case "<" -> left < right;
                case "<=" -> left <= right;
                case ">" -> left > right;
                case ">=" -> left >= right;
                case "=" -> left == right;
                default -> false;
            };
        }
        return result;
    }

    private static double rangeOperand(String s, MediaEnvironment env) {
        return switch (s) {
            case "width" -> env.width();
            case "height" -> env.height();
            default -> parseLength(s);
        };
    }

    private static boolean compare(String name, double actual, double limit) {
        if (Double.isNaN(limit)) return false;
        if (name.startsWith("min-")) return actual >= limit;
        if (name.startsWith("max-")) return actual <= limit;
        return actual == limit;
    }

    /** メディアクエリの長さ。em / rem は初期値の 16px（CSS の規定でルートの font-size ではない）。 */
    private static double parseLength(String v) {
        String s = v.trim();
        double scale = 1;
        if (s.endsWith("px")) s = s.substring(0, s.length() - 2);
        else if (s.endsWith("rem")) { s = s.substring(0, s.length() - 3); scale = 16; }
        else if (s.endsWith("em")) { s = s.substring(0, s.length() - 2); scale = 16; }
        try {
            return Double.parseDouble(s.trim()) * scale;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double parseRatio(String v) {
        int slash = v.indexOf('/');
        try {
            if (slash < 0) return Double.parseDouble(v.trim());
            double den = Double.parseDouble(v.substring(slash + 1).trim());
            return den == 0 ? Double.NaN : Double.parseDouble(v.substring(0, slash).trim()) / den;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    // ---- @supports の特性

    /**
     * {@code (property: value)} は登録済みのプロパティ（とカスタムプロパティ）なら真、
     * ショートハンドは値を展開できるときだけ真。{@code selector(...)} はセレクタが解析できれば真。
     */
    private static boolean evaluateSupportsFeature(String feature) {
        String f = feature.trim();
        String lower = f.toLowerCase(Locale.ROOT);
        if (lower.startsWith("selector(") && f.endsWith(")")) {
            try {
                SelectorParser.parse(f.substring(9, f.length() - 1).trim(), CascadeOrigin.AUTHOR, false);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
        int colon = f.indexOf(':');
        if (colon <= 0) return false;
        String property = f.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String value = f.substring(colon + 1).trim();
        if (value.isEmpty()) return false;
        if (property.startsWith("--")) return true;
        PropertyRegistry.Property p = PropertyRegistry.get(property);
        if (p == null) return false;
        if (p.expander() != null && CssWideKeyword.of(value) == null && !value.contains("var(")) {
            return !p.expander().apply(value).isEmpty();
        }
        return true;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** {@code selector(...)} のような関数形の終わり。関数でなければ次の空白まで。 */
    private static int functionEnd(String s, int start) {
        int i = start;
        while (i < s.length() && !Character.isWhitespace(s.charAt(i)) && s.charAt(i) != '(') i++;
        if (i < s.length() && s.charAt(i) == '(') {
            int close = matchingParen(s, i);
            return close < 0 ? -1 : close + 1;
        }
        return i;
    }
}
