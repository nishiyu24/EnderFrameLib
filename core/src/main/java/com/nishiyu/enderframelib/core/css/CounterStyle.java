package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Declaration;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code @counter-style} 1 つ（CSS Counter Styles 3 §3）。
 *
 * <p>記述子は出現順に読み、同じ記述子が複数あれば「最後の有効な値」だけを使う（§3）。
 * {@code system: extends <name>} のときは、指定されなかった記述子を継承するので、
 * 省略された記述子は {@code null} のまま残す。実際の合成は {@link CounterStyles} が行う。
 *
 * <p>LIMITATION(制約): {@code symbols} / {@code additive-symbols} の {@code <image>}
 * （{@code url(...)}）は読まない。画像を含む記述子は無効として扱う。
 */
public final class CounterStyle {

    /** カウンタの算法（CSS Counter Styles 3 §3.1）。 */
    public enum System {
        CYCLIC, NUMERIC, ALPHABETIC, SYMBOLIC, ADDITIVE, FIXED, EXTENDS
    }

    /** {@code additive-symbols} の 1 組（記号と重み）。 */
    public record AdditiveSymbol(String symbol, int weight) {}

    /** {@code range} の 1 区間（両端を含む）。{@code Integer.MIN_VALUE}/{@code MAX_VALUE} は無限大。 */
    public record Range(int lower, int upper) {
        public boolean contains(int value) {
            return value >= lower && value <= upper;
        }
    }

    private final String name;
    private final System system;
    private final String extendsName;
    private final int fixedFirst;
    private final List<String> symbols;
    private final List<AdditiveSymbol> additiveSymbols;
    private final String negativePrefix;
    private final String negativeSuffix;
    private final boolean negativeSpecified;
    private final String prefix;
    private final String suffix;
    private final List<Range> ranges;
    private final Integer padWidth;
    private final String padSymbol;
    private final String fallback;

    private CounterStyle(String name, System system, String extendsName, int fixedFirst,
                         List<String> symbols, List<AdditiveSymbol> additiveSymbols,
                         String negativePrefix, String negativeSuffix, boolean negativeSpecified,
                         String prefix, String suffix, List<Range> ranges,
                         Integer padWidth, String padSymbol, String fallback) {
        this.name = name;
        this.system = system;
        this.extendsName = extendsName;
        this.fixedFirst = fixedFirst;
        this.symbols = symbols;
        this.additiveSymbols = additiveSymbols;
        this.negativePrefix = negativePrefix;
        this.negativeSuffix = negativeSuffix;
        this.negativeSpecified = negativeSpecified;
        this.prefix = prefix;
        this.suffix = suffix;
        this.ranges = ranges;
        this.padWidth = padWidth;
        this.padSymbol = padSymbol;
        this.fallback = fallback;
    }

    /**
     * {@code @counter-style <name>} の宣言列を読む。必要な記述子が無い・値が読めないなど、
     * 規則として成立しないときは {@code null}（その名前の規則は無かったことになる）。
     *
     * @param name         規則の名前（{@code <custom-ident>} なので大文字小文字はそのまま）
     * @param declarations ブロックの中の宣言（出現順）
     */
    public static CounterStyle fromDeclarations(String name, List<Declaration> declarations) {
        System system = System.SYMBOLIC;
        String extendsName = null;
        int fixedFirst = 1;
        List<String> symbols = null;
        List<AdditiveSymbol> additiveSymbols = null;
        String negativePrefix = null;
        String negativeSuffix = null;
        boolean negativeSpecified = false;
        String prefix = null;
        String suffix = null;
        List<Range> ranges = null;
        Integer padWidth = null;
        String padSymbol = null;
        String fallback = null;

        for (Declaration declaration : declarations) {
            String property = declaration.name().toLowerCase(Locale.ROOT);
            List<ComponentValue> value = declaration.value();
            switch (property) {
                case "system" -> {
                    SystemSpec spec = parseSystem(value);
                    if (spec != null) {
                        system = spec.system();
                        extendsName = spec.extendsName();
                        fixedFirst = spec.fixedFirst();
                    }
                }
                case "symbols" -> {
                    List<String> parsed = parseSymbols(value);
                    if (parsed != null) symbols = parsed;
                }
                case "additive-symbols" -> {
                    List<AdditiveSymbol> parsed = parseAdditiveSymbols(value);
                    if (parsed != null) additiveSymbols = parsed;
                }
                case "negative" -> {
                    List<String> parsed = parseSymbols(value);
                    if (parsed != null && parsed.size() <= 2) {
                        negativeSpecified = true;
                        negativePrefix = parsed.getFirst();
                        negativeSuffix = parsed.size() > 1 ? parsed.get(1) : "";
                    }
                }
                case "prefix" -> {
                    List<String> parsed = parseSingleSymbol(value);
                    if (parsed != null) prefix = parsed.getFirst();
                }
                case "suffix" -> {
                    List<String> parsed = parseSingleSymbol(value);
                    if (parsed != null) suffix = parsed.getFirst();
                }
                case "range" -> {
                    if (isAuto(value)) {
                        ranges = null;
                    } else {
                        List<Range> parsed = parseRanges(value);
                        if (parsed != null) ranges = parsed;
                    }
                }
                case "pad" -> {
                    PadSpec parsed = parsePad(value);
                    if (parsed != null) {
                        padWidth = parsed.width();
                        padSymbol = parsed.symbol();
                    }
                }
                case "fallback" -> {
                    String parsed = parseName(value);
                    if (parsed != null) fallback = parsed;
                }
                default -> {
                    // speak-as など、表示に使わない記述子は読まない
                }
            }
        }

        if (system == System.EXTENDS) {
            // extends は symbols / additive-symbols を持つと規則ごと無効（§3.1.7）
            if (extendsName == null || symbols != null || additiveSymbols != null) return null;
        } else if (system == System.ADDITIVE) {
            if (additiveSymbols == null || additiveSymbols.isEmpty()) return null;
        } else {
            if (symbols == null) return null;
            int minimum = system == System.NUMERIC || system == System.ALPHABETIC ? 2 : 1;
            if (symbols.size() < minimum) return null;
        }

        return new CounterStyle(name, system, extendsName, fixedFirst, symbols, additiveSymbols,
                negativePrefix, negativeSuffix, negativeSpecified, prefix, suffix, ranges,
                padWidth, padSymbol, fallback);
    }

    public String name() { return name; }
    public System system() { return system; }
    public String extendsName() { return extendsName; }
    public int fixedFirst() { return fixedFirst; }
    public List<String> symbols() { return symbols; }
    public List<AdditiveSymbol> additiveSymbols() { return additiveSymbols; }
    public String negativePrefix() { return negativePrefix; }
    public String negativeSuffix() { return negativeSuffix; }
    public boolean negativeSpecified() { return negativeSpecified; }
    public String prefix() { return prefix; }
    public String suffix() { return suffix; }
    public List<Range> ranges() { return ranges; }
    public Integer padWidth() { return padWidth; }
    public String padSymbol() { return padSymbol; }
    public String fallback() { return fallback; }

    // ==========================================
    // 記述子の読み取り
    // ==========================================

    private record SystemSpec(System system, String extendsName, int fixedFirst) {}

    private static SystemSpec parseSystem(List<ComponentValue> value) {
        List<ComponentValue> parts = significant(value);
        if (parts.isEmpty()) return null;
        Token first = CssSyntax.tokenOf(parts.getFirst());
        if (first == null || first.type() != Type.IDENT) return null;
        String keyword = first.value().toLowerCase(Locale.ROOT);
        return switch (keyword) {
            case "cyclic" -> parts.size() == 1 ? new SystemSpec(System.CYCLIC, null, 1) : null;
            case "numeric" -> parts.size() == 1 ? new SystemSpec(System.NUMERIC, null, 1) : null;
            case "alphabetic" -> parts.size() == 1 ? new SystemSpec(System.ALPHABETIC, null, 1) : null;
            case "symbolic" -> parts.size() == 1 ? new SystemSpec(System.SYMBOLIC, null, 1) : null;
            case "additive" -> parts.size() == 1 ? new SystemSpec(System.ADDITIVE, null, 1) : null;
            case "fixed" -> {
                if (parts.size() == 1) yield new SystemSpec(System.FIXED, null, 1);
                if (parts.size() != 2) yield null;
                Integer firstValue = integerOf(CssSyntax.tokenOf(parts.get(1)));
                yield firstValue == null ? null : new SystemSpec(System.FIXED, null, firstValue);
            }
            case "extends" -> {
                if (parts.size() != 2) yield null;
                Token base = CssSyntax.tokenOf(parts.get(1));
                if (base == null || base.type() != Type.IDENT || isNonSymbolIdent(base.value())
                        || base.value().equalsIgnoreCase("none")) yield null;
                yield new SystemSpec(System.EXTENDS, base.value(), 1);
            }
            default -> null;
        };
    }

    /** {@code symbols} / {@code negative} の記号列。読めない記号（画像など）があれば null。 */
    private static List<String> parseSymbols(List<ComponentValue> value) {
        List<ComponentValue> parts = significant(value);
        if (parts.isEmpty()) return null;
        List<String> out = new ArrayList<>();
        for (ComponentValue part : parts) {
            String symbol = symbolOf(part);
            if (symbol == null) return null;
            out.add(symbol);
        }
        return out;
    }

    /** {@code prefix} / {@code suffix} の 1 記号。 */
    private static List<String> parseSingleSymbol(List<ComponentValue> value) {
        List<ComponentValue> parts = significant(value);
        if (parts.size() != 1) return null;
        String symbol = symbolOf(parts.getFirst());
        return symbol == null ? null : List.of(symbol);
    }

    private static String symbolOf(ComponentValue value) {
        Token token = CssSyntax.tokenOf(value);
        if (token == null) return null;
        return switch (token.type()) {
            case STRING -> token.value();
            case IDENT -> isNonSymbolIdent(token.value()) ? null : token.value();
            default -> null;
        };
    }

    /** {@code <custom-ident>} から外れる識別子（CSS-wide キーワードと {@code default}）。 */
    private static boolean isNonSymbolIdent(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "initial", "inherit", "unset", "revert", "revert-layer", "default" -> true;
            default -> false;
        };
    }

    private static List<AdditiveSymbol> parseAdditiveSymbols(List<ComponentValue> value) {
        List<AdditiveSymbol> out = new ArrayList<>();
        int previousWeight = Integer.MAX_VALUE;
        for (List<ComponentValue> group : CssSyntax.split(value, Type.COMMA)) {
            List<ComponentValue> parts = significant(group);
            Integer weight = null;
            String symbol = null;
            for (ComponentValue part : parts) {
                Token token = CssSyntax.tokenOf(part);
                if (token == null) return null;
                if (token.type() == Type.NUMBER && token.flag()) {
                    if (weight != null) return null;
                    weight = integerOf(token);
                    if (weight == null) return null;
                } else if (token.type() == Type.STRING || token.type() == Type.IDENT) {
                    if (symbol != null || (token.type() == Type.IDENT && isNonSymbolIdent(token.value()))) {
                        return null;
                    }
                    symbol = token.value();
                } else {
                    return null;
                }
            }
            if (weight == null || symbol == null || weight < 0 || weight >= previousWeight) return null;
            out.add(new AdditiveSymbol(symbol, weight));
            previousWeight = weight;
        }
        return out.isEmpty() ? null : out;
    }

    private static boolean isAuto(List<ComponentValue> value) {
        List<ComponentValue> parts = significant(value);
        if (parts.size() != 1) return false;
        Token token = CssSyntax.tokenOf(parts.getFirst());
        return token != null && token.type() == Type.IDENT && token.value().equalsIgnoreCase("auto");
    }

    private static List<Range> parseRanges(List<ComponentValue> value) {
        List<Range> out = new ArrayList<>();
        for (List<ComponentValue> group : CssSyntax.split(value, Type.COMMA)) {
            List<ComponentValue> parts = significant(group);
            if (parts.size() != 2) return null;
            Integer lower = boundOf(parts.get(0), true);
            Integer upper = boundOf(parts.get(1), false);
            if (lower == null || upper == null || lower > upper) return null;
            out.add(new Range(lower, upper));
        }
        return out.isEmpty() ? null : out;
    }

    private static Integer boundOf(ComponentValue value, boolean lower) {
        Token token = CssSyntax.tokenOf(value);
        if (token == null) return null;
        if (token.type() == Type.IDENT && token.value().equalsIgnoreCase("infinite")) {
            return lower ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        }
        return integerOf(token);
    }

    private record PadSpec(int width, String symbol) {}

    private static PadSpec parsePad(List<ComponentValue> value) {
        List<ComponentValue> parts = significant(value);
        if (parts.size() != 2) return null;
        Integer width = null;
        String symbol = null;
        for (ComponentValue part : parts) {
            Token token = CssSyntax.tokenOf(part);
            if (token == null) return null;
            if (token.type() == Type.NUMBER && token.flag()) {
                if (width != null) return null;
                width = integerOf(token);
                if (width == null || width < 0) return null;
            } else if (token.type() == Type.STRING || token.type() == Type.IDENT) {
                if (symbol != null) return null;
                symbol = token.value();
            } else {
                return null;
            }
        }
        return width == null || symbol == null ? null : new PadSpec(width, symbol);
    }

    private static String parseName(List<ComponentValue> value) {
        List<ComponentValue> parts = significant(value);
        if (parts.size() != 1) return null;
        Token token = CssSyntax.tokenOf(parts.getFirst());
        if (token == null || token.type() != Type.IDENT || isNonSymbolIdent(token.value())
                || token.value().equalsIgnoreCase("none")) {
            return null;
        }
        return token.value();
    }

    /** CSS の整数。{@code int} に収まらない値は読めないものとして {@code null}。 */
    private static Integer integerOf(Token token) {
        if (token == null || token.type() != Type.NUMBER || !token.flag()) return null;
        double number = token.number();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) return null;
        return (int) number;
    }

    private static List<ComponentValue> significant(List<ComponentValue> values) {
        List<ComponentValue> out = new ArrayList<>();
        for (ComponentValue value : CssSyntax.trim(values)) {
            if (!CssSyntax.isWhitespace(value)) out.add(value);
        }
        return out;
    }
}
