package com.nishiyu.enderframelib.core.css;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class LengthResolver {
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    /**
     * 長さの文字列をピクセルに解く。結果は丸めない。
     *
     * 何が壊れていたか: 以前は各単位の結果を int に切り捨てていたため、{@code 33.33%} や
     * {@code 1.5em} が端数を落とし、親子で積み重なるほどずれた（軸 3）。丸めは描画の 1 箇所だけ。
     */
    public static float parseDim(String val, float maxPixels, float rootW, float rootH, float elementFontSize, float rootFontSize) {
        if (val == null || val.equals("auto")) return 0;
        val = val.trim();

        if (val.startsWith("clamp(") && val.endsWith(")")) {
            List<String> args = splitTopLevel(val.substring(6, val.length() - 1));
            if (args.size() == 3) {
                float min = parseDim(args.get(0).trim(), maxPixels, rootW, rootH, elementFontSize, rootFontSize);
                float pref = parseDim(args.get(1).trim(), maxPixels, rootW, rootH, elementFontSize, rootFontSize);
                float max = parseDim(args.get(2).trim(), maxPixels, rootW, rootH, elementFontSize, rootFontSize);
                return Math.max(min, Math.min(pref, max));
            }
        }

        if (val.startsWith("min(") && val.endsWith(")")) {
            List<String> args = splitTopLevel(val.substring(4, val.length() - 1));
            if (!args.isEmpty()) {
                float result = Float.POSITIVE_INFINITY;
                for (String arg : args) {
                    result = Math.min(result, parseDim(arg.trim(), maxPixels, rootW, rootH, elementFontSize, rootFontSize));
                }
                return result == Float.POSITIVE_INFINITY ? 0 : result;
            }
        }

        if (val.startsWith("max(") && val.endsWith(")")) {
            List<String> args = splitTopLevel(val.substring(4, val.length() - 1));
            if (!args.isEmpty()) {
                float result = Float.NEGATIVE_INFINITY;
                for (String arg : args) {
                    result = Math.max(result, parseDim(arg.trim(), maxPixels, rootW, rootH, elementFontSize, rootFontSize));
                }
                return result == Float.NEGATIVE_INFINITY ? 0 : result;
            }
        }


        if (val.startsWith("calc(") && val.endsWith(")")) {
            String inner = val.substring(5, val.length() - 1).trim();
            CalcEvaluator evaluator = new CalcEvaluator(inner, maxPixels, rootW, rootH, elementFontSize, rootFontSize);
            return (float) evaluator.evaluate();
        }

        try {
            if (val.endsWith("px")) return Float.parseFloat(val.substring(0, val.length() - 2));
            if (val.endsWith("%")) return maxPixels * Float.parseFloat(val.substring(0, val.length() - 1)) / 100f;
            if (val.endsWith("vw")) return rootW * Float.parseFloat(val.substring(0, val.length() - 2)) / 100f;
            if (val.endsWith("vh")) return rootH * Float.parseFloat(val.substring(0, val.length() - 2)) / 100f;
            if (val.endsWith("rem")) return rootFontSize * Float.parseFloat(val.substring(0, val.length() - 3));
            if (val.endsWith("em")) return elementFontSize * Float.parseFloat(val.substring(0, val.length() - 2));
            return Float.parseFloat(val);
        } catch (Exception ignored) {
        }
        return 0; // 全失敗時は0にフォールバック（意図的）
    }

    // 後方互換用（elementFontSize, rootFontSize が不明な場合は 16.0f を仮定）
    public static float parseDim(String val, float maxPixels, float rootW, float rootH) {
        return parseDim(val, maxPixels, rootW, rootH, 16.0f, 16.0f);
    }

    // ==========================================
    // 1 回だけ分解しておく長さ表現
    // ==========================================

    /** 長さの単位。 */
    public enum Unit {
        /** px、または単位なしの数値 */
        PIXELS,
        /** % （親の対応する寸法が基準） */
        PERCENT,
        VW,
        VH,
        EM,
        REM,
        /** auto / 不正値（0 として扱う） */
        NONE,
        /** calc() / clamp() など、解決時に式を評価する必要があるもの */
        EXPRESSION
    }

    /**
     * 「単位と数値への分解」を済ませた長さ。
     * ComputedStyle がプロパティごとに 1 つ保持し、参照のたびに文字列を再パースしないようにする。
     * EXPRESSION だけは式の評価が必要なので、生の文字列を保持して解決時に {@link #parseDim} へ委ねる。
     */
    public record CompiledLength(Unit unit, float value, String raw) {

        public static final CompiledLength ZERO = new CompiledLength(Unit.NONE, 0f, null);

        public float resolve(float maxPixels, float rootW, float rootH, float elementFontSize, float rootFontSize) {
            return switch (unit) {
                case PIXELS -> value;
                case PERCENT -> maxPixels * value / 100f;
                case VW -> rootW * value / 100f;
                case VH -> rootH * value / 100f;
                case EM -> elementFontSize * value;
                case REM -> rootFontSize * value;
                case EXPRESSION -> parseDim(raw, maxPixels, rootW, rootH, elementFontSize, rootFontSize);
                case NONE -> 0;
            };
        }
    }

    /** 長さの文字列を単位と数値に分解する。失敗や auto は {@link CompiledLength#ZERO}。 */
    public static CompiledLength compile(String val) {
        if (val == null) return CompiledLength.ZERO;
        String v = val.trim();
        if (v.isEmpty() || v.equals("auto")) return CompiledLength.ZERO;

        // calc() / clamp() / min() / max() は式の評価が必要
        if (v.indexOf('(') >= 0) return new CompiledLength(Unit.EXPRESSION, 0f, v);

        Unit unit;
        String number;
        if (v.endsWith("px")) {
            unit = Unit.PIXELS;
            number = v.substring(0, v.length() - 2);
        } else if (v.endsWith("%")) {
            unit = Unit.PERCENT;
            number = v.substring(0, v.length() - 1);
        } else if (v.endsWith("vw")) {
            unit = Unit.VW;
            number = v.substring(0, v.length() - 2);
        } else if (v.endsWith("vh")) {
            unit = Unit.VH;
            number = v.substring(0, v.length() - 2);
        } else if (v.endsWith("rem")) {
            unit = Unit.REM;
            number = v.substring(0, v.length() - 3);
        } else if (v.endsWith("em")) {
            unit = Unit.EM;
            number = v.substring(0, v.length() - 2);
        } else {
            unit = Unit.PIXELS;
            number = v;
        }

        try {
            return new CompiledLength(unit, Float.parseFloat(number.trim()), v);
        } catch (NumberFormatException e) {
            return CompiledLength.ZERO;
        }
    }

    public static String[] parseBoxEdgesStr(String val) {
        if (val == null) return new String[]{"0", "0", "0", "0"};
        String[] parts = WHITESPACE_SPLIT.split(val.trim());
        String[] edges = new String[4];
        if (parts.length == 1) {
            edges[0] = edges[1] = edges[2] = edges[3] = parts[0];
        } else if (parts.length == 2) {
            edges[0] = edges[2] = parts[0];
            edges[1] = edges[3] = parts[1];
        } else if (parts.length == 3) {
            edges[0] = parts[0];
            edges[1] = edges[3] = parts[1];
            edges[2] = parts[2];
        } else if (parts.length >= 4) {
            edges[0] = parts[0];
            edges[1] = parts[1];
            edges[2] = parts[2];
            edges[3] = parts[3];
        }
        return edges;
    }

    public static float[] parseBorderRadius(String val, float maxPixels, float rootW, float rootH) {
        String[] edges = parseBoxEdgesStr(val != null ? val : "0");
        float rTL = parseDim(edges[0], maxPixels, rootW, rootH);
        float rTR = parseDim(edges[1], maxPixels, rootW, rootH);
        float rBR = parseDim(edges[2], maxPixels, rootW, rootH);
        float rBL = parseDim(edges[3], maxPixels, rootW, rootH);
        return new float[]{rTL, rTR, rBR, rBL};
    }

    private static List<String> splitTopLevel(String str) {
        List<String> list = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            else if (c == ',' && depth == 0) {
                list.add(str.substring(start, i).trim());
                start = i + 1;
            }
        }
        if (start < str.length()) {
            list.add(str.substring(start).trim());
        }
        return list;
    }

    public static class CalcEvaluator {
        private final String str;
        private final float maxPixels;
        private final float rootW;
        private final float rootH;
        private final float elementFontSize;
        private final float rootFontSize;
        private int pos = -1;
        private int ch;

        public CalcEvaluator(String str, float maxPixels, float rootW, float rootH, float elementFontSize, float rootFontSize) {
            this.str = str;
            this.maxPixels = maxPixels;
            this.rootW = rootW;
            this.rootH = rootH;
            this.elementFontSize = elementFontSize;
            this.rootFontSize = rootFontSize;
        }

        public double evaluate() {
            nextChar();
            double x = parseExpression();
            if (pos < str.length()) return 0;
            return x;
        }

        private void nextChar() {
            ch = (++pos < str.length()) ? str.charAt(pos) : -1;
        }

        private boolean eat(int charToEat) {
            while (ch == ' ') nextChar();
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }

        private double parseExpression() {
            double x = parseTerm();
            for (; ; ) {
                if (eat('+')) x += parseTerm();
                else if (eat('-')) x -= parseTerm();
                else return x;
            }
        }

        private double parseTerm() {
            double x = parseFactor();
            for (; ; ) {
                if (eat('*')) x *= parseFactor();
                else if (eat('/')) x /= parseFactor();
                else return x;
            }
        }

        private double parseFactor() {
            if (eat('+')) return parseFactor();
            if (eat('-')) return -parseFactor();
            double x;
            int startPos = this.pos;
            if (eat('(')) {
                x = parseExpression();
                eat(')');
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                String numStr = str.substring(startPos, this.pos);
                StringBuilder unitSb = new StringBuilder();
                while ((ch >= 'a' && ch <= 'z') || ch == '%') {
                    unitSb.append((char) ch);
                    nextChar();
                }
                String unit = unitSb.toString();
                try {
                    float val = Float.parseFloat(numStr);
                    x = switch (unit) {
                        case "%" -> maxPixels * val / 100f;
                        case "vw" -> rootW * val / 100f;
                        case "vh" -> rootH * val / 100f;
                        case "em" -> elementFontSize * val;
                        case "rem" -> rootFontSize * val;
                        default -> val; // px or unitless
                    };
                } catch (Exception e) {
                    x = 0;
                }
            } else {
                return 0;
            }
            return x;
        }
    }
}