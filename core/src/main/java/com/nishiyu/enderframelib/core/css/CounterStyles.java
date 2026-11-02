package com.nishiyu.enderframelib.core.css;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文書で使えるカウンタスタイルの集合と、値から表現への変換（CSS Counter Styles 3）。
 *
 * <p>作者の {@code @counter-style} と、CSS 2 / CSS 2.1 の定義済みスタイルを同じ経路で扱う。
 * 作者の名前は {@code <custom-ident>} なので大文字小文字を区別し、定義済みの名前は
 * ASCII 大文字小文字を区別しない。名前が無い・知らない名前は {@code decimal}（§5）。
 * {@code list-style-type} の文字列（{@code "→ "}）はマーカーだけの特別扱いで、
 * {@code counter()} のスタイル名ではない。
 *
 * <p>{@code prefix} / {@code suffix} は {@code ::marker} のマーカー表現にだけ付き、
 * {@code counter()} / {@code counters()} の戻り値には付かない（§2 の Note）。
 *
 * <p>LIMITATION(制約): {@code symbols()} 関数（匿名スタイル）は読まない。
 * {@code symbols} の画像記号も読まない（{@link CounterStyle} を参照）。
 */
public final class CounterStyles {

    /** 上限。仕様が要求する 60 文字より十分大きく、作者の極端な指定でメモリを食い尽くさないための歯止め。 */
    private static final int MAX_REPRESENTATION = 4096;

    /** {@code @counter-style} の名前として使えない名前（§3。CSS-wide キーワードと {@code default} も除く）。 */
    private static final Set<String> RESERVED_NAMES = Set.of(
            "none", "decimal", "disc", "square", "circle", "disclosure-open", "disclosure-closed",
            "initial", "inherit", "unset", "revert", "revert-layer", "default");

    private static final List<String> DECIMAL_DIGITS = List.of(
            "0", "1", "2", "3", "4", "5", "6", "7", "8", "9");
    private static final List<String> LOWER_ALPHA = codePoints("abcdefghijklmnopqrstuvwxyz");
    private static final List<String> UPPER_ALPHA = codePoints("ABCDEFGHIJKLMNOPQRSTUVWXYZ");
    private static final List<String> LOWER_GREEK = codePoints("αβγδεζηθικλμνξοπρστυφχψω");

    private static final Map<String, Resolved> BUILTINS = builtinTable();
    private static final CounterStyles BUILTIN_ONLY = new CounterStyles(Map.of());

    private final Map<String, CounterStyle> custom;

    public CounterStyles(Map<String, CounterStyle> custom) {
        this.custom = custom == null ? Map.of() : Map.copyOf(custom);
    }

    /** 定義済みスタイルだけの集合（共有インスタンス）。 */
    public static CounterStyles builtins() {
        return BUILTIN_ONLY;
    }

    /** 文書の作者定義から集合を作る。作者定義が無ければ共有の定義済み集合を返す。 */
    public static CounterStyles of(Map<String, CounterStyle> custom) {
        return custom == null || custom.isEmpty() ? BUILTIN_ONLY : new CounterStyles(custom);
    }

    /** {@code @counter-style} の名前として使えるか（予約された名前は規則ごと無効）。 */
    public static boolean isReservedName(String name) {
        return name != null && RESERVED_NAMES.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * {@code ::marker} のマーカー表現。{@code prefix + 表現 + suffix}。
     * {@code list-style-type: none} と空文字列は {@code null}（マーカーを出さない）。
     */
    public String marker(String styleName, int value) {
        if (styleName == null) return marker("decimal", value);
        String text = styleName.trim();
        if (text.isEmpty()) return null;
        char first = text.charAt(0);
        if ((first == '"' || first == '\'') && text.length() >= 2 && text.charAt(text.length() - 1) == first) {
            return text.substring(1, text.length() - 1);
        }
        if (text.equalsIgnoreCase("none")) return null;
        Resolved style = resolve(text, null);
        String representation = formatValue(style, value, null);
        return style.prefix() + representation + style.suffix();
    }

    /**
     * {@code counter()} / {@code counters()} の表現。{@code prefix} / {@code suffix} は付けない。
     * 知らない名前は {@code decimal}、{@code none} は空文字列。
     */
    public String representation(int value, String styleName) {
        if (styleName == null || styleName.isBlank()) return representation(value, "decimal");
        String name = styleName.trim();
        if (name.equalsIgnoreCase("none")) return "";
        return formatValue(resolve(name, null), value, null);
    }

    // ==========================================
    // スタイルの解決
    // ==========================================

    private record Resolved(String name, CounterStyle.System system, int fixedFirst,
                            List<String> symbols, List<CounterStyle.AdditiveSymbol> additiveSymbols,
                            String negativePrefix, String negativeSuffix,
                            String prefix, String suffix, List<CounterStyle.Range> ranges,
                            int padWidth, String padSymbol, String fallback) {

        boolean usesNegative() {
            return switch (system) {
                case SYMBOLIC, ALPHABETIC, NUMERIC, ADDITIVE -> true;
                default -> false;
            };
        }
    }

    private Resolved resolve(String name, Set<String> visiting) {
        String key = name == null ? "decimal" : name.trim();
        if (key.isEmpty() || key.equalsIgnoreCase("none")) key = "decimal";
        // 定義済みの名前は ASCII 小文字に直してから探す（§2.1）。作者が同名を上書きしていれば
        // その定義を、無ければ定義済みの定義を使う。作者名はそれ以外では大文字小文字を区別する。
        String lower = key.toLowerCase(Locale.ROOT);
        String lookup = BUILTINS.containsKey(lower) ? lower : key;
        CounterStyle style = custom.get(lookup);
        if (style != null) return resolveCustom(style, visiting);
        Resolved builtin = BUILTINS.get(lower);
        return builtin != null ? builtin : BUILTINS.get("decimal");
    }

    private Resolved resolveCustom(CounterStyle style, Set<String> visiting) {
        if (style.system() != CounterStyle.System.EXTENDS) {
            return new Resolved(style.name(), style.system(), style.fixedFirst(),
                    style.symbols() != null ? style.symbols() : List.of(),
                    style.additiveSymbols() != null ? style.additiveSymbols() : List.of(),
                    style.negativeSpecified() ? style.negativePrefix() : "-",
                    style.negativeSpecified() ? style.negativeSuffix() : "",
                    style.prefix() != null ? style.prefix() : "",
                    style.suffix() != null ? style.suffix() : ". ",
                    style.ranges(),
                    style.padWidth() != null ? style.padWidth() : 0,
                    style.padSymbol() != null ? style.padSymbol() : "",
                    style.fallback() != null ? style.fallback() : "decimal");
        }

        Set<String> active = visiting != null ? visiting : new HashSet<>();
        if (!active.add(style.name())) return BUILTINS.get("decimal");
        try {
            Resolved base = resolve(style.extendsName(), active);
            return new Resolved(style.name(), base.system(), base.fixedFirst(),
                    style.symbols() != null ? style.symbols() : base.symbols(),
                    style.additiveSymbols() != null ? style.additiveSymbols() : base.additiveSymbols(),
                    style.negativeSpecified() ? style.negativePrefix() : base.negativePrefix(),
                    style.negativeSpecified() ? style.negativeSuffix() : base.negativeSuffix(),
                    style.prefix() != null ? style.prefix() : base.prefix(),
                    style.suffix() != null ? style.suffix() : base.suffix(),
                    style.ranges() != null ? style.ranges() : base.ranges(),
                    style.padWidth() != null ? style.padWidth() : base.padWidth(),
                    style.padSymbol() != null ? style.padSymbol() : base.padSymbol(),
                    style.fallback() != null ? style.fallback() : base.fallback());
        } finally {
            active.remove(style.name());
        }
    }

    // ==========================================
    // 表現の生成
    // ==========================================

    private String formatValue(Resolved style, int value, Set<String> fallbacks) {
        if (!inRange(style, value)) return fallback(style, value, fallbacks);

        boolean negative = value < 0 && style.usesNegative();
        long magnitude = negative ? -(long) value : value;
        if (magnitude > Integer.MAX_VALUE) return fallback(style, value, fallbacks);

        String representation = algorithm(style, (int) magnitude);
        if (representation == null || representation.length() > MAX_REPRESENTATION) {
            return fallback(style, value, fallbacks);
        }

        if (style.padWidth() > 0 && !style.padSymbol().isEmpty()) {
            int length = graphemeCount(representation)
                    + (negative ? graphemeCount(style.negativePrefix()) + graphemeCount(style.negativeSuffix()) : 0);
            int difference = style.padWidth() - length;
            if (difference > 0) {
                if (difference > MAX_REPRESENTATION
                        || (long) difference * style.padSymbol().length() > MAX_REPRESENTATION) {
                    return fallback(style, value, fallbacks);
                }
                representation = style.padSymbol().repeat(difference) + representation;
            }
        }

        if (negative) representation = style.negativePrefix() + representation + style.negativeSuffix();
        return representation.length() > MAX_REPRESENTATION ? fallback(style, value, fallbacks) : representation;
    }

    private String fallback(Resolved style, int value, Set<String> chain) {
        String name = style.fallback() == null || style.fallback().isBlank()
                ? "decimal" : style.fallback().trim();
        if (name.equalsIgnoreCase("none")) name = "decimal";
        if (name.equals(style.name())) return Integer.toString(value);
        Set<String> active = chain != null ? chain : new HashSet<>();
        if (!active.add(style.name())) return Integer.toString(value);
        try {
            return formatValue(resolve(name, null), value, active);
        } finally {
            active.remove(style.name());
        }
    }

    private static boolean inRange(Resolved style, int value) {
        if (style.ranges() != null) {
            for (CounterStyle.Range range : style.ranges()) {
                if (range.contains(value)) return true;
            }
            return false;
        }
        return switch (style.system()) {
            case ALPHABETIC, SYMBOLIC -> value >= 1;
            case ADDITIVE -> value >= 0;
            default -> true;
        };
    }

    /** 初期表現。表現できない値は {@code null}（呼び出し側が fallback へ回す）。 */
    private static String algorithm(Resolved style, int value) {
        List<String> symbols = style.symbols();
        int count = symbols.size();
        switch (style.system()) {
            case CYCLIC -> {
                if (count == 0) return null;
                return symbols.get((int) Math.floorMod((long) value - 1, count));
            }
            case FIXED -> {
                long index = (long) value - style.fixedFirst();
                if (index < 0 || index >= count) return null;
                return symbols.get((int) index);
            }
            case SYMBOLIC -> {
                if (count == 0 || value <= 0) return null;
                int index = (value - 1) % count;
                int repetitions = (value - 1) / count + 1;
                if (repetitions > MAX_REPRESENTATION) return null;
                return symbols.get(index).repeat(repetitions);
            }
            case ALPHABETIC -> {
                if (count < 2 || value <= 0) return null;
                StringBuilder out = new StringBuilder();
                int remaining = value;
                while (remaining > 0) {
                    remaining--;
                    out.insert(0, symbols.get(remaining % count));
                    remaining /= count;
                    if (out.length() > MAX_REPRESENTATION) return null;
                }
                return out.toString();
            }
            case NUMERIC -> {
                if (count < 2) return null;
                if (value == 0) return symbols.get(0);
                StringBuilder out = new StringBuilder();
                int remaining = value;
                while (remaining > 0) {
                    out.insert(0, symbols.get(remaining % count));
                    remaining /= count;
                    if (out.length() > MAX_REPRESENTATION) return null;
                }
                return out.toString();
            }
            case ADDITIVE -> {
                if (value == 0) {
                    for (CounterStyle.AdditiveSymbol symbol : style.additiveSymbols()) {
                        if (symbol.weight() == 0) return symbol.symbol();
                    }
                    return null;
                }
                StringBuilder out = new StringBuilder();
                int remaining = value;
                for (CounterStyle.AdditiveSymbol symbol : style.additiveSymbols()) {
                    int weight = symbol.weight();
                    if (weight == 0 || weight > remaining) continue;
                    int repetitions = remaining / weight;
                    if ((long) repetitions * symbol.symbol().length() > MAX_REPRESENTATION) return null;
                    out.append(symbol.symbol().repeat(repetitions));
                    remaining -= weight * repetitions;
                    if (remaining == 0) return out.toString();
                    if (out.length() > MAX_REPRESENTATION) return null;
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static int graphemeCount(String text) {
        if (text.isEmpty()) return 0;
        // ASCII は 1 文字 = 1 書記素。マーカーの大半はここで済む
        boolean ascii = true;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7F) {
                ascii = false;
                break;
            }
        }
        if (ascii) return text.length();
        BreakIterator iterator = BreakIterator.getCharacterInstance(Locale.ROOT);
        iterator.setText(text);
        int count = 0;
        while (iterator.next() != BreakIterator.DONE) count++;
        return count;
    }

    // ==========================================
    // 定義済みスタイル
    // ==========================================

    private static Map<String, Resolved> builtinTable() {
        Map<String, Resolved> map = new LinkedHashMap<>();
        add(map, numeric("decimal", DECIMAL_DIGITS, 0, "", ". "));
        add(map, numeric("decimal-leading-zero", DECIMAL_DIGITS, 2, "0", ". "));
        add(map, alphabetic("lower-alpha", LOWER_ALPHA));
        add(map, alphabetic("lower-latin", LOWER_ALPHA));
        add(map, alphabetic("upper-alpha", UPPER_ALPHA));
        add(map, alphabetic("upper-latin", UPPER_ALPHA));
        add(map, alphabetic("lower-greek", LOWER_GREEK));
        add(map, roman("lower-roman", false));
        add(map, roman("upper-roman", true));
        add(map, cyclic("disc", "•"));
        add(map, cyclic("circle", "◦"));
        add(map, cyclic("square", "▪"));
        add(map, cyclic("disclosure-open", "▾"));
        add(map, cyclic("disclosure-closed", "▸"));
        return Map.copyOf(map);
    }

    private static void add(Map<String, Resolved> map, Resolved style) {
        map.put(style.name(), style);
    }

    private static Resolved numeric(String name, List<String> symbols, int padWidth, String padSymbol, String suffix) {
        return new Resolved(name, CounterStyle.System.NUMERIC, 1, symbols, List.of(),
                "-", "", "", suffix, null, padWidth, padSymbol, "decimal");
    }

    private static Resolved alphabetic(String name, List<String> symbols) {
        return new Resolved(name, CounterStyle.System.ALPHABETIC, 1, symbols, List.of(),
                "-", "", "", ". ", null, 0, "", "decimal");
    }

    private static Resolved cyclic(String name, String symbol) {
        return new Resolved(name, CounterStyle.System.CYCLIC, 1, List.of(symbol), List.of(),
                "-", "", "", " ", null, 0, "", "decimal");
    }

    private static Resolved roman(String name, boolean upper) {
        int[] weights = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        List<CounterStyle.AdditiveSymbol> symbols = new ArrayList<>();
        for (int i = 0; i < weights.length; i++) {
            symbols.add(new CounterStyle.AdditiveSymbol(upper ? numerals[i] : numerals[i].toLowerCase(Locale.ROOT), weights[i]));
        }
        return new Resolved(name, CounterStyle.System.ADDITIVE, 1, List.of(), List.copyOf(symbols),
                "-", "", "", ". ", List.of(new CounterStyle.Range(1, 3999)), 0, "", "decimal");
    }

    private static List<String> codePoints(String text) {
        return text.codePoints().mapToObj(codePoint -> new String(Character.toChars(codePoint))).toList();
    }
}
