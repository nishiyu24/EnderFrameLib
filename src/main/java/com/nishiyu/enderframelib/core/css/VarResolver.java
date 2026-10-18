package com.nishiyu.enderframelib.core.css;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * {@code var()} の置換。
 *
 * 何が壊れていたか: 以前は {@code var\(\s*(--[\w-]+)(?:\s*,\s*([^)]+))?\s*\)} の正規表現で
 * 置き換えを 10 回まで繰り返していた。フォールバックに括弧を含む値
 * （{@code var(--c, rgb(0, 0, 0))}）や入れ子の {@code var()} が途中の {@code )} で切れ、
 * 循環参照（{@code --a: var(--b); --b: var(--a)}）は 10 回回って未解決の文字列のまま残った。
 * 未定義でフォールバックも無い参照は空文字列に置き換わり、宣言が黙って空になっていた。
 *
 * ここでは括弧の深さを数えて引数を切り出し、参照先のカスタムプロパティを再帰的に解く。
 * 循環に入ったもの、未定義でフォールバックも無いものは「算出時に無効」として null を返す
 * （呼び出し側はその宣言を {@code unset} として扱う。CSS の invalid at computed-value time）。
 */
final class VarResolver {

    /** 置換結果がこの長さを超えたら無効にする（{@code --a: var(--b) var(--b)} の倍々ゲーム対策）。 */
    private static final int MAX_LENGTH = 65536;

    private final Function<String, String> lookup;

    /**
     * @param lookup カスタムプロパティ名（{@code --x}）→ 置換前の値。未定義なら null
     */
    VarResolver(Function<String, String> lookup) {
        this.lookup = lookup;
    }

    /** {@code var()} を含まない値に置き換える。算出時に無効なら null。 */
    String substitute(String value) {
        return substitute(value, new HashSet<>());
    }

    /** カスタムプロパティ {@code name} の算出値（参照を解いた値）。循環・未定義なら null。 */
    String resolveCustomProperty(String name) {
        return resolveVariable(name, new HashSet<>());
    }

    private String substitute(String value, Set<String> resolving) {
        if (value == null) return null;
        if (!value.contains("var(")) return value;

        StringBuilder out = new StringBuilder();
        int i = 0;
        int len = value.length();
        while (i < len) {
            int start = indexOfVar(value, i);
            if (start < 0) {
                out.append(value, i, len);
                break;
            }
            out.append(value, i, start);
            int open = start + 3; // "var" の後ろの '('
            int close = matchingParen(value, open);
            if (close < 0) return null; // 閉じていない var( は構文として無効

            String args = value.substring(open + 1, close);
            int comma = topLevelComma(args);
            String name = (comma >= 0 ? args.substring(0, comma) : args).trim();
            String fallback = comma >= 0 ? args.substring(comma + 1).trim() : null;
            if (!name.startsWith("--")) return null;

            String resolved = resolveVariable(name, resolving);
            if (resolved == null && fallback != null) resolved = substitute(fallback, resolving);
            if (resolved == null) return null;

            out.append(resolved);
            if (out.length() > MAX_LENGTH) return null;
            i = close + 1;
        }
        return out.toString();
    }

    private String resolveVariable(String name, Set<String> resolving) {
        if (!resolving.add(name)) return null; // 循環
        try {
            String raw = lookup.apply(name);
            return raw == null ? null : substitute(raw, resolving);
        } finally {
            resolving.remove(name);
        }
    }

    /** 識別子の途中（{@code --my-var(} など）ではない {@code var(} の位置。 */
    private static int indexOfVar(String s, int from) {
        int idx = s.indexOf("var(", from);
        while (idx > 0) {
            char prev = s.charAt(idx - 1);
            if (!Character.isLetterOrDigit(prev) && prev != '-' && prev != '_') break;
            idx = s.indexOf("var(", idx + 1);
        }
        return idx;
    }

    private static int matchingParen(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static int topLevelComma(String s) {
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
