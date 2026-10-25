package com.nishiyu.enderframelib.core.css;

import java.util.Locale;

/**
 * 算出値の補間（CSS Transitions / Animations の「値の補間」）。プロパティの型は {@link PropertyRegistry} の宣言から引く。
 *
 * <p>色は sRGB で、数値は線形に、長さは単位が同じなら数値のまま、違えば {@code calc()} で混ぜる。
 * 補間できない組（キーワードと長さ、{@code auto} など）は {@code null} を返し、呼び出し側が離散的に
 * （進行度 0.5 で切り替えて）扱う。</p>
 */
public final class ValueInterpolator {

    private ValueInterpolator() {}

    /**
     * @param t 0 で {@code from}、1 で {@code to}（イージング後の進行度。範囲外もそのまま外挿する）
     * @return 補間した値。補間できなければ null
     */
    public static String interpolate(String property, String from, String to, double t) {
        if (from == null || to == null) return null;
        PropertyRegistry.Property declared = PropertyRegistry.get(property);
        if (declared == null || !declared.animatable()) return null;
        String a = from.trim();
        String b = to.trim();
        if (a.equals(b)) return b;
        return switch (declared.type()) {
            case COLOR -> color(a, b, t);
            case NUMBER -> number(a, b, t, false);
            case INTEGER -> number(a, b, t, true);
            case LENGTH, LENGTH_PERCENTAGE -> length(property, a, b, t);
            default -> null;
        };
    }

    private static String color(String from, String to, double t) {
        if (!ColorParser.isColor(from) || !ColorParser.isColor(to)) return null;
        if (from.equalsIgnoreCase("currentcolor") || to.equalsIgnoreCase("currentcolor")) return null;
        if (t <= 0) return from;
        if (t >= 1) return to;
        return ColorParser.format(ColorParser.interpolate(ColorParser.parseColor(from), ColorParser.parseColor(to), t, "srgb"));
    }

    private static String number(String from, String to, double t, boolean integer) {
        Double a = parseNumber(from);
        Double b = parseNumber(to);
        if (a == null || b == null) return null;
        double v = a + (b - a) * t;
        return integer ? Long.toString(Math.round(v)) : format(v);
    }

    private static String length(String property, String from, String to, double t) {
        Dimension a = Dimension.parse(property, from);
        Dimension b = Dimension.parse(property, to);
        if (a != null && b != null && (a.unit.equals(b.unit) || a.unit.isEmpty() || b.unit.isEmpty())) {
            // 単位なしの 0 は相手の単位の 0
            String unit = a.unit.isEmpty() ? b.unit : a.unit;
            return format(a.value + (b.value - a.value) * t) + unit;
        }
        if (!isCalcOperand(from) || !isCalcOperand(to)) return null;
        // 単位が違う（px と %、calc() を含む）ときは calc() で混ぜる。解くのは長さの解決の段
        return "calc((" + from + ") * " + format(1 - t) + " + (" + to + ") * " + format(t) + ")";
    }

    /** {@code calc()} の中に置ける長さか（単純な長さか、{@code calc()} / {@code min()} などの数学関数）。 */
    private static boolean isCalcOperand(String value) {
        if (Dimension.parse(null, value) != null) return true;
        String v = value.toLowerCase(Locale.ROOT);
        return v.startsWith("calc(") || v.startsWith("min(") || v.startsWith("max(") || v.startsWith("clamp(");
    }

    private static Double parseNumber(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 小数 4 桁まで、末尾の 0 を落とした文字列（指数表記にしない）。 */
    static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e9) return Long.toString((long) v);
        String s = String.format(Locale.ROOT, "%.4f", v);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') end--;
        if (end > 0 && s.charAt(end - 1) == '.') end--;
        s = s.substring(0, end);
        return s.equals("-0") ? "0" : s;
    }

    /** 数値 + 単位（{@code 12px}・{@code 50%}・単位なしの {@code 0}）。 */
    private record Dimension(double value, String unit) {
        /** @param property 指定すれば、border-width のキーワード（thin / medium / thick）を px として読む */
        static Dimension parse(String property, String text) {
            String s = text.trim().toLowerCase(Locale.ROOT);
            if (property != null && property.startsWith("border-") && property.endsWith("-width")) {
                switch (s) {
                    case "thin" -> { return new Dimension(1, "px"); }
                    case "medium" -> { return new Dimension(3, "px"); }
                    case "thick" -> { return new Dimension(5, "px"); }
                    default -> { }
                }
            }
            int i = 0;
            int n = s.length();
            if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            int digitsStart = i;
            while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            if (i == digitsStart) return null;
            // 指数表記 (1e3px) は読まない。単位の文字と区別できないため
            double value;
            try {
                value = Double.parseDouble(s.substring(0, i));
            } catch (NumberFormatException e) {
                return null;
            }
            String unit = s.substring(i);
            if (!unit.equals("%") && !unit.chars().allMatch(Character::isLetter)) return null;
            if (unit.isEmpty() && value != 0) return null;
            return new Dimension(value, unit);
        }
    }
}
