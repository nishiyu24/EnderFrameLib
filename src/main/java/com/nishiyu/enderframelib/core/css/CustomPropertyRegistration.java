package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;

import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;
import com.nishiyu.enderframelib.core.css.LengthResolver.Dimension;
import com.nishiyu.enderframelib.core.css.LengthResolver.Metrics;
import com.nishiyu.enderframelib.core.css.LengthResolver.Quantity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code @property} で登録したカスタムプロパティ（CSS Properties and Values API Level 1）。
 *
 * 登録すると、そのカスタムプロパティは <b>型を持つ</b>。
 * <ul>
 *   <li>構文（{@code syntax}）に合わない値の宣言は、算出時に無効になる（継承するなら継承値、しなければ初期値）</li>
 *   <li>{@code inherits: false} なら子へ継承しない</li>
 *   <li>値を書かなかったときの初期値（{@code initial-value}）がある</li>
 *   <li>算出値は正規の形になる（{@code 2em} は px へ、{@code 90deg} は度へ）</li>
 *   <li>構文が数・長さ・色・角度などなら、アニメーション・トランジションで補間できる（{@link #interpolate}）</li>
 * </ul>
 * 登録簿は文書（スタイルシート）ごとに持ち、プロセス全体の共有状態は無い。
 */
public final class CustomPropertyRegistration {

    /** 構文のデータ型（{@code <length>} など）と、名前そのもの（{@code auto} のような識別子の選択肢）。 */
    public enum DataType {
        LENGTH, NUMBER, PERCENTAGE, LENGTH_PERCENTAGE, COLOR, IMAGE, URL, INTEGER, ANGLE, TIME, RESOLUTION,
        TRANSFORM_FUNCTION, TRANSFORM_LIST, CUSTOM_IDENT, STRING, KEYWORD
    }

    /** 値の並び方。{@code +} は空白区切りの 1 つ以上、{@code #} はカンマ区切りの 1 つ以上。 */
    public enum Multiplier { ONE, SPACE_LIST, COMMA_LIST }

    /** 構文の選択肢 1 つ。{@code keyword} は {@link DataType#KEYWORD} のときの識別子。 */
    public record Component(DataType type, String keyword, Multiplier multiplier) {}

    private static final Set<String> TRANSFORM_FUNCTIONS = Set.of(
            "matrix", "matrix3d", "translate", "translatex", "translatey", "translatez", "translate3d",
            "scale", "scalex", "scaley", "scalez", "scale3d", "rotate", "rotatex", "rotatey", "rotatez", "rotate3d",
            "skew", "skewx", "skewy", "perspective");

    private final String name;
    private final String syntax;
    private final List<Component> components;
    private final boolean inherits;
    private final String initialValue;

    private CustomPropertyRegistration(String name, String syntax, List<Component> components,
                                       boolean inherits, String initialValue) {
        this.name = name;
        this.syntax = syntax;
        this.components = components;
        this.inherits = inherits;
        this.initialValue = initialValue;
    }

    public String name() {
        return name;
    }

    public String syntax() {
        return syntax;
    }

    public boolean inherits() {
        return inherits;
    }

    /** 初期値。普遍構文（{@code *}）で {@code initial-value} が無いときは null（初期値は「無い」）。 */
    public String initialValue() {
        return initialValue;
    }

    /** 構文が {@code *}（何でも受ける）か。 */
    public boolean isUniversal() {
        return components.isEmpty();
    }

    public List<Component> components() {
        return components;
    }

    // ==========================================
    // 登録（@property の読み取り）
    // ==========================================

    /**
     * {@code @property} の記述子から登録を作る。{@code syntax}・{@code inherits} が無い、構文が読めない、
     * {@code initial-value} が構文に合わない・算出に他の値を要する（{@code var()}・{@code em} など）・
     * 普遍構文でないのに無い、のいずれでも無効（null）。
     *
     * @param descriptors 記述子名 → 値の文字列（{@code syntax} の値は引用符付きのまま）
     */
    public static CustomPropertyRegistration fromDescriptors(String name, Map<String, String> descriptors) {
        if (name == null || !name.startsWith("--") || name.length() < 3) return null;
        String syntaxText = unquote(descriptors.get("syntax"));
        String inheritsText = descriptors.get("inherits");
        if (syntaxText == null || inheritsText == null) return null;
        boolean inherits;
        switch (inheritsText.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> inherits = true;
            case "false" -> inherits = false;
            default -> {
                return null;
            }
        }
        List<Component> components = parseSyntax(syntaxText);
        if (components == null) return null;

        String initial = descriptors.get("initial-value");
        CustomPropertyRegistration probe = new CustomPropertyRegistration(name, syntaxText.trim(), components, inherits, null);
        if (components.isEmpty()) {
            // 普遍構文は初期値を省ける（空の初期値は有効）
            return new CustomPropertyRegistration(name, syntaxText.trim(), components, inherits,
                    initial == null || initial.isBlank() ? null : initial.trim());
        }
        if (initial == null || initial.isBlank()) return null;
        String canonical = initial.trim();
        if (!probe.accepts(canonical) || !isComputationallyIndependent(canonical)) return null;
        return new CustomPropertyRegistration(name, syntaxText.trim(), components, inherits,
                probe.compute(canonical, Metrics.of(0, 0, 0, 16, 16)));
    }

    private static String unquote(String s) {
        if (s == null) return null;
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(s));
        if (values.size() != 1) return null;
        Token t = CssSyntax.tokenOf(values.getFirst());
        return t != null && t.type() == Type.STRING ? t.value() : null;
    }

    /** 他の値（フォントサイズ・変数・環境）に左右される値は初期値にできない。 */
    private static boolean isComputationallyIndependent(String value) {
        for (Token t : CssSyntax.tokenize(value)) {
            if (t.type() == Type.FUNCTION && (t.value().equalsIgnoreCase("var") || t.value().equalsIgnoreCase("env")
                    || t.value().equalsIgnoreCase("attr"))) {
                return false;
            }
            if (t.type() == Type.DIMENSION) {
                switch (t.unit().toLowerCase(Locale.ROOT)) {
                    case "em", "rem", "ex", "ch", "cap", "ic", "lh", "rlh", "cqw", "cqh", "cqi", "cqb", "cqmin", "cqmax" -> {
                        return false;
                    }
                    default -> {
                    }
                }
            }
            if (t.type() == Type.IDENT && t.value().equalsIgnoreCase("currentcolor")) return false;
        }
        return true;
    }

    /**
     * 構文を選択肢の列にする。{@code *} は空のリスト。{@code <length> | auto} のように {@code |} で区切り、
     * 各選択肢は {@code <型>}（後ろに {@code +} / {@code #} を付けられる）か識別子。読めなければ null。
     */
    static List<Component> parseSyntax(String text) {
        String s = text.trim();
        if (s.equals("*")) return List.of();
        if (s.isEmpty()) return null;
        List<Component> out = new ArrayList<>();
        for (String alternative : splitAlternatives(s)) {
            String a = alternative.trim();
            if (a.isEmpty()) return null;
            Multiplier multiplier = Multiplier.ONE;
            if (a.endsWith("+")) {
                multiplier = Multiplier.SPACE_LIST;
                a = a.substring(0, a.length() - 1).trim();
            } else if (a.endsWith("#")) {
                multiplier = Multiplier.COMMA_LIST;
                a = a.substring(0, a.length() - 1).trim();
            }
            if (a.startsWith("<") && a.endsWith(">")) {
                DataType type = dataType(a.substring(1, a.length() - 1).trim());
                if (type == null) return null;
                // <transform-list> はそれ自体が列なので + を付けられない
                if (type == DataType.TRANSFORM_LIST && multiplier != Multiplier.ONE) return null;
                out.add(new Component(type, null, multiplier));
            } else {
                if (multiplier != Multiplier.ONE || !isIdentifier(a)) return null;
                out.add(new Component(DataType.KEYWORD, a, Multiplier.ONE));
            }
        }
        return out;
    }

    private static List<String> splitAlternatives(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == '|' && depth == 0) {
                out.add(s.substring(from, i));
                from = i + 1;
            }
        }
        out.add(s.substring(from));
        return out;
    }

    private static DataType dataType(String name) {
        return switch (name) {
            case "length" -> DataType.LENGTH;
            case "number" -> DataType.NUMBER;
            case "percentage" -> DataType.PERCENTAGE;
            case "length-percentage" -> DataType.LENGTH_PERCENTAGE;
            case "color" -> DataType.COLOR;
            case "image" -> DataType.IMAGE;
            case "url" -> DataType.URL;
            case "integer" -> DataType.INTEGER;
            case "angle" -> DataType.ANGLE;
            case "time" -> DataType.TIME;
            case "resolution" -> DataType.RESOLUTION;
            case "transform-function" -> DataType.TRANSFORM_FUNCTION;
            case "transform-list" -> DataType.TRANSFORM_LIST;
            case "custom-ident" -> DataType.CUSTOM_IDENT;
            case "string" -> DataType.STRING;
            default -> null;
        };
    }

    private static boolean isIdentifier(String s) {
        List<Token> tokens = CssSyntax.tokenize(s);
        return tokens.size() == 1 && tokens.getFirst().type() == Type.IDENT && tokens.getFirst().raw().equals(s);
    }

    // ==========================================
    // 値の検証と算出
    // ==========================================

    /** 値（指定値）が構文に合うか。普遍構文は何でも受ける。 */
    public boolean accepts(String value) {
        if (value == null) return false;
        if (isUniversal()) return true;
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(value));
        if (values.isEmpty()) return false;
        return matchingAlternative(values) >= 0;
    }

    /** 値が最初に合う選択肢の番号。どれにも合わなければ -1。 */
    private int matchingAlternative(List<ComponentValue> values) {
        for (int i = 0; i < components.size(); i++) {
            if (matchesComponent(components.get(i), values)) return i;
        }
        return -1;
    }

    private static boolean matchesComponent(Component c, List<ComponentValue> values) {
        return switch (c.multiplier()) {
            case ONE -> matchesSingle(c, values);
            case SPACE_LIST -> {
                List<List<ComponentValue>> items = CssSyntax.splitOnWhitespace(values);
                yield !items.isEmpty() && items.stream().allMatch(item -> matchesSingle(c, item));
            }
            case COMMA_LIST -> {
                List<List<ComponentValue>> items = CssSyntax.split(values, Type.COMMA);
                yield items.stream().allMatch(item -> !item.isEmpty() && matchesSingle(c, item));
            }
        };
    }

    private static boolean matchesSingle(Component c, List<ComponentValue> values) {
        if (c.type() == DataType.TRANSFORM_LIST) {
            List<List<ComponentValue>> functions = CssSyntax.splitOnWhitespace(values);
            return !functions.isEmpty() && functions.stream()
                    .allMatch(f -> matchesSingle(new Component(DataType.TRANSFORM_FUNCTION, null, Multiplier.ONE), f));
        }
        List<ComponentValue> v = CssSyntax.trim(values);
        if (v.size() != 1) return false;
        ComponentValue only = v.getFirst();
        if (only instanceof CssSyntax.Function f) return matchesFunction(c, f);
        Token t = CssSyntax.tokenOf(only);
        if (t == null) return false;
        return switch (c.type()) {
            case LENGTH -> (t.type() == Type.DIMENSION && isLengthUnit(t.unit())) || (t.type() == Type.NUMBER && t.number() == 0);
            case NUMBER -> t.type() == Type.NUMBER;
            case INTEGER -> t.type() == Type.NUMBER && t.flag();
            case PERCENTAGE -> t.type() == Type.PERCENTAGE;
            case LENGTH_PERCENTAGE -> t.type() == Type.PERCENTAGE
                    || (t.type() == Type.DIMENSION && isLengthUnit(t.unit())) || (t.type() == Type.NUMBER && t.number() == 0);
            case COLOR -> ColorParser.isColor(CssSyntax.serialize(only));
            case ANGLE -> t.type() == Type.DIMENSION && LengthResolver.angleDegrees(t.number(), t.unit()) != null;
            case TIME -> t.type() == Type.DIMENSION && (t.unit().equalsIgnoreCase("s") || t.unit().equalsIgnoreCase("ms"));
            case RESOLUTION -> t.type() == Type.DIMENSION && switch (t.unit().toLowerCase(Locale.ROOT)) {
                case "dpi", "dpcm", "dppx", "x" -> true;
                default -> false;
            };
            case URL -> t.type() == Type.URL;
            case IMAGE -> t.type() == Type.URL;
            case STRING -> t.type() == Type.STRING;
            case CUSTOM_IDENT -> t.type() == Type.IDENT && !isReservedIdent(t.value());
            case KEYWORD -> t.type() == Type.IDENT && t.value().equals(c.keyword());
            default -> false;
        };
    }

    private static boolean matchesFunction(Component c, CssSyntax.Function f) {
        String name = f.name().toLowerCase(Locale.ROOT);
        switch (c.type()) {
            case TRANSFORM_FUNCTION:
                return TRANSFORM_FUNCTIONS.contains(name);
            case IMAGE:
                return name.equals("url") || name.endsWith("gradient") || name.equals("image-set");
            case URL:
                return name.equals("url");
            case COLOR:
                return ColorParser.isColor(CssSyntax.serialize(f));
            case LENGTH, NUMBER, INTEGER, PERCENTAGE, LENGTH_PERCENTAGE, ANGLE, TIME, RESOLUTION: {
                // 数式。% を長さにしない文脈で評価し、結果の次元がデータ型に合うかを見る
                Quantity q = LengthResolver.evaluateMath(f, Map.of());
                if (q == null) return false;
                return switch (c.type()) {
                    case LENGTH -> q.dimension() == Dimension.LENGTH;
                    case NUMBER -> q.dimension() == Dimension.NUMBER;
                    case INTEGER -> q.dimension() == Dimension.NUMBER && q.value() == Math.rint(q.value());
                    case PERCENTAGE -> q.dimension() == Dimension.PERCENT;
                    case LENGTH_PERCENTAGE -> q.dimension() == Dimension.LENGTH || q.dimension() == Dimension.PERCENT;
                    case ANGLE -> q.dimension() == Dimension.ANGLE;
                    case TIME -> q.dimension() == Dimension.TIME;
                    default -> q.dimension() == Dimension.RESOLUTION;
                };
            }
            default:
                return false;
        }
    }

    private static boolean isLengthUnit(String unit) {
        return !Float.isNaN(LengthResolver.lengthUnitInPixels(unit.toLowerCase(Locale.ROOT), Metrics.of(0, 0, 0, 16, 16)));
    }

    private static boolean isReservedIdent(String ident) {
        return switch (ident.toLowerCase(Locale.ROOT)) {
            case "inherit", "initial", "unset", "revert", "revert-layer", "default" -> true;
            default -> false;
        };
    }

    /**
     * 算出値（絶対化した正規の形）。長さは px、角度は度、時間は秒、数式は計算済み。
     * 構文に合わない値は null（呼び出し側が「算出時に無効」として扱う）。普遍構文は値のまま。
     *
     * @param metrics em / rem などを px にする基準（この要素のフォントサイズなど）
     */
    public String compute(String value, Metrics metrics) {
        if (value == null) return null;
        String trimmed = value.trim();
        if (isUniversal()) return trimmed;
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(trimmed));
        int alternative = matchingAlternative(values);
        if (alternative < 0) return null;
        return computeComponent(components.get(alternative), values, metrics);
    }

    private String computeComponent(Component c, List<ComponentValue> values, Metrics metrics) {
        switch (c.multiplier()) {
            case SPACE_LIST: {
                List<String> parts = new ArrayList<>();
                for (List<ComponentValue> item : CssSyntax.splitOnWhitespace(values)) parts.add(computeSingle(c, item, metrics));
                return String.join(" ", parts);
            }
            case COMMA_LIST: {
                List<String> parts = new ArrayList<>();
                for (List<ComponentValue> item : CssSyntax.split(values, Type.COMMA)) parts.add(computeSingle(c, item, metrics));
                return String.join(", ", parts);
            }
            default:
                return computeSingle(c, values, metrics);
        }
    }

    private static String computeSingle(Component c, List<ComponentValue> values, Metrics metrics) {
        if (c.type() == DataType.TRANSFORM_LIST) {
            List<String> parts = new ArrayList<>();
            for (List<ComponentValue> f : CssSyntax.splitOnWhitespace(values)) parts.add(CssSyntax.serialize(f));
            return String.join(" ", parts);
        }
        List<ComponentValue> v = CssSyntax.trim(values);
        ComponentValue only = v.getFirst();
        String text = CssSyntax.serialize(only);
        Quantity q = quantityOf(only, metrics);
        return switch (c.type()) {
            case LENGTH, LENGTH_PERCENTAGE -> q != null && q.dimension() == Dimension.LENGTH
                    ? number(q.value()) + "px" : text;
            case NUMBER, INTEGER -> q != null && q.dimension() == Dimension.NUMBER ? number(q.value()) : text;
            case ANGLE -> q != null && q.dimension() == Dimension.ANGLE ? number(q.value()) + "deg" : text;
            case TIME -> q != null && q.dimension() == Dimension.TIME ? number(q.value()) + "s" : text;
            case RESOLUTION -> q != null && q.dimension() == Dimension.RESOLUTION ? number(q.value()) + "dppx" : text;
            case PERCENTAGE -> q != null && q.dimension() == Dimension.PERCENT ? number(q.value()) + "%" : text;
            default -> text;
        };
    }

    /** 値 1 つを量にする（数式は評価し、数・寸法・割合のトークンは直に読む）。読めなければ null。 */
    private static Quantity quantityOf(ComponentValue v, Metrics metrics) {
        if (v instanceof CssSyntax.Function f) {
            return LengthResolver.evaluateMath(CssSyntax.serialize(f), metrics.withPercentBase(Float.NaN));
        }
        Token t = CssSyntax.tokenOf(v);
        if (t == null) return null;
        switch (t.type()) {
            case NUMBER:
                return new Quantity(t.number(), Dimension.NUMBER);
            case PERCENTAGE:
                return new Quantity(t.number(), Dimension.PERCENT);
            case DIMENSION: {
                Double degrees = LengthResolver.angleDegrees(t.number(), t.unit());
                if (degrees != null) return new Quantity(degrees, Dimension.ANGLE);
                String unit = t.unit().toLowerCase(Locale.ROOT);
                if (unit.equals("s")) return new Quantity(t.number(), Dimension.TIME);
                if (unit.equals("ms")) return new Quantity(t.number() / 1000.0, Dimension.TIME);
                if (unit.equals("dppx") || unit.equals("x")) return new Quantity(t.number(), Dimension.RESOLUTION);
                if (unit.equals("dpi")) return new Quantity(t.number() / 96.0, Dimension.RESOLUTION);
                if (unit.equals("dpcm")) return new Quantity(t.number() * 2.54 / 96.0, Dimension.RESOLUTION);
                float px = LengthResolver.toPixels((float) t.number(), unit, metrics);
                return Float.isNaN(px) ? null : new Quantity(px, Dimension.LENGTH);
            }
            default:
                return null;
        }
    }

    private static String number(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.6f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    // ==========================================
    // 補間（アニメーション・トランジション）
    // ==========================================

    /**
     * 算出値 {@code from} から {@code to} へ、進行度 {@code progress}（0〜1）で補間した値を返す。
     * 2 つの値が同じ選択肢の同じ型に合うときだけ型どおり補間する（数・長さ・割合・角度・時間・解像度は線形、
     * 色は CSS Color 4 の既定の補間空間 oklab、{@code <transform-list>} は同じ関数の並びなら関数ごと）。
     * 列は同じ長さなら要素ごと。それ以外（識別子・文字列・URL・普遍構文・型が合わない）は離散で、
     * 進行度 0.5 で切り替わる。
     */
    public String interpolate(String from, String to, double progress) {
        if (from == null || to == null) return progress < 0.5 ? from : to;
        if (isUniversal()) return discrete(from, to, progress);
        List<ComponentValue> a = CssSyntax.trim(CssSyntax.parseComponentValues(from));
        List<ComponentValue> b = CssSyntax.trim(CssSyntax.parseComponentValues(to));
        int ia = matchingAlternative(a);
        int ib = matchingAlternative(b);
        if (ia < 0 || ia != ib) return discrete(from, to, progress);
        Component c = components.get(ia);

        switch (c.multiplier()) {
            case SPACE_LIST: {
                List<List<ComponentValue>> ai = CssSyntax.splitOnWhitespace(a);
                List<List<ComponentValue>> bi = CssSyntax.splitOnWhitespace(b);
                return ai.size() == bi.size() ? mixItems(c, ai, bi, progress, " ", from, to) : discrete(from, to, progress);
            }
            case COMMA_LIST: {
                List<List<ComponentValue>> ai = CssSyntax.split(a, Type.COMMA);
                List<List<ComponentValue>> bi = CssSyntax.split(b, Type.COMMA);
                return ai.size() == bi.size() ? mixItems(c, ai, bi, progress, ", ", from, to) : discrete(from, to, progress);
            }
            default: {
                String mixed = interpolateSingle(c, a, b, progress);
                return mixed != null ? mixed : discrete(from, to, progress);
            }
        }
    }

    private static String mixItems(Component c, List<List<ComponentValue>> a, List<List<ComponentValue>> b,
                                   double progress, String separator, String from, String to) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            String mixed = interpolateSingle(c, a.get(i), b.get(i), progress);
            if (mixed == null) return discrete(from, to, progress);
            parts.add(mixed);
        }
        return String.join(separator, parts);
    }

    private static String discrete(String from, String to, double progress) {
        return progress < 0.5 ? from : to;
    }

    /** 同じ型の値 1 つずつを補間する。補間できない型・値は null。 */
    private static String interpolateSingle(Component c, List<ComponentValue> a, List<ComponentValue> b, double t) {
        switch (c.type()) {
            case COLOR: {
                String from = CssSyntax.serialize(a);
                String to = CssSyntax.serialize(b);
                return ColorParser.format(ColorParser.interpolate(ColorParser.parseColor(from), ColorParser.parseColor(to), t, "oklab"));
            }
            case TRANSFORM_LIST:
                return interpolateTransformList(a, b, t);
            case NUMBER, INTEGER, LENGTH, PERCENTAGE, LENGTH_PERCENTAGE, ANGLE, TIME, RESOLUTION: {
                List<ComponentValue> va = CssSyntax.trim(a);
                List<ComponentValue> vb = CssSyntax.trim(b);
                if (va.size() != 1 || vb.size() != 1) return null;
                Metrics metrics = Metrics.of(0, 0, 0, 16, 16);
                Quantity qa = quantityOf(va.getFirst(), metrics);
                Quantity qb = quantityOf(vb.getFirst(), metrics);
                if (qa == null || qb == null) return null;
                if (qa.dimension() != qb.dimension()) {
                    // <length-percentage> で長さと割合が混ざるときは calc() にする
                    return mixedLengthPercentage(qa, qb, t);
                }
                double mixed = qa.value() + (qb.value() - qa.value()) * t;
                if (c.type() == DataType.INTEGER) mixed = Math.rint(mixed);
                return switch (qa.dimension()) {
                    case LENGTH -> number(mixed) + "px";
                    case ANGLE -> number(mixed) + "deg";
                    case TIME -> number(mixed) + "s";
                    case RESOLUTION -> number(mixed) + "dppx";
                    case PERCENT -> number(mixed) + "%";
                    default -> number(mixed);
                };
            }
            default:
                return null;
        }
    }

    private static String mixedLengthPercentage(Quantity a, Quantity b, double t) {
        boolean lengthAndPercent = (a.dimension() == Dimension.LENGTH && b.dimension() == Dimension.PERCENT)
                || (a.dimension() == Dimension.PERCENT && b.dimension() == Dimension.LENGTH);
        if (!lengthAndPercent) return null;
        double length = (a.dimension() == Dimension.LENGTH ? a.value() * (1 - t) : 0)
                + (b.dimension() == Dimension.LENGTH ? b.value() * t : 0);
        double percent = (a.dimension() == Dimension.PERCENT ? a.value() * (1 - t) : 0)
                + (b.dimension() == Dimension.PERCENT ? b.value() * t : 0);
        return "calc(" + number(percent) + "% + " + number(length) + "px)";
    }

    /** 関数の並びと引数の数が同じ {@code <transform-list>} を、引数ごとに補間する。形が違えば null。 */
    private static String interpolateTransformList(List<ComponentValue> a, List<ComponentValue> b, double t) {
        List<List<ComponentValue>> fa = CssSyntax.splitOnWhitespace(a);
        List<List<ComponentValue>> fb = CssSyntax.splitOnWhitespace(b);
        if (fa.size() != fb.size()) return null;
        List<String> out = new ArrayList<>();
        Metrics metrics = Metrics.of(0, 0, 0, 16, 16);
        for (int i = 0; i < fa.size(); i++) {
            if (!(fa.get(i).getFirst() instanceof CssSyntax.Function x)
                    || !(fb.get(i).getFirst() instanceof CssSyntax.Function y)
                    || !x.name().equalsIgnoreCase(y.name())) {
                return null;
            }
            List<List<ComponentValue>> xa = CssSyntax.split(x.args(), Type.COMMA);
            List<List<ComponentValue>> ya = CssSyntax.split(y.args(), Type.COMMA);
            if (xa.size() != ya.size()) return null;
            List<String> args = new ArrayList<>();
            for (int k = 0; k < xa.size(); k++) {
                if (xa.get(k).size() != 1 || ya.get(k).size() != 1) return null;
                Quantity qa = quantityOf(xa.get(k).getFirst(), metrics);
                Quantity qb = quantityOf(ya.get(k).getFirst(), metrics);
                if (qa == null || qb == null || qa.dimension() != qb.dimension()) return null;
                double mixed = qa.value() + (qb.value() - qa.value()) * t;
                args.add(switch (qa.dimension()) {
                    case LENGTH -> number(mixed) + "px";
                    case ANGLE -> number(mixed) + "deg";
                    case PERCENT -> number(mixed) + "%";
                    default -> number(mixed);
                });
            }
            out.add(x.name().toLowerCase(Locale.ROOT) + "(" + String.join(", ", args) + ")");
        }
        return String.join(" ", out);
    }
}
