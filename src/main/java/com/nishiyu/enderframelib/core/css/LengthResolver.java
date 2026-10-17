package com.nishiyu.enderframelib.core.css;

import java.util.regex.Pattern;

public class LengthResolver {
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    public static int parseDim(String val, int maxPixels, int rootW, int rootH, float elementFontSize, float rootFontSize) {
        if (val == null || val.equals("auto")) return 0;
        val = val.trim();

        if (val.startsWith("clamp(") && val.endsWith(")")) {
            String inner = val.substring(6, val.length() - 1);
            String[] args = inner.split(",");
            if (args.length == 3) {
                int min = parseDim(args[0].trim(), maxPixels, rootW, rootH, elementFontSize, rootFontSize);
                int pref = parseDim(args[1].trim(), maxPixels, rootW, rootH, elementFontSize, rootFontSize);
                int max = parseDim(args[2].trim(), maxPixels, rootW, rootH, elementFontSize, rootFontSize);
                return Math.max(min, Math.min(pref, max));
            }
        }

        if (val.startsWith("calc(") && val.endsWith(")")) {
            String inner = val.substring(5, val.length() - 1).trim();
            CalcEvaluator evaluator = new CalcEvaluator(inner, maxPixels, rootW, rootH, elementFontSize, rootFontSize);
            return (int) evaluator.evaluate();
        }

        try {
            if (val.endsWith("px")) return (int) Float.parseFloat(val.substring(0, val.length() - 2));
            if (val.endsWith("%")) return (int) (maxPixels * Float.parseFloat(val.substring(0, val.length() - 1)) / 100f);
            if (val.endsWith("vw")) return (int) (rootW * Float.parseFloat(val.substring(0, val.length() - 2)) / 100f);
            if (val.endsWith("vh")) return (int) (rootH * Float.parseFloat(val.substring(0, val.length() - 2)) / 100f);
            if (val.endsWith("rem")) return (int) (rootFontSize * Float.parseFloat(val.substring(0, val.length() - 3)));
            if (val.endsWith("em")) return (int) (elementFontSize * Float.parseFloat(val.substring(0, val.length() - 2)));
            return (int) Float.parseFloat(val);
        } catch (Exception ignored) {
        }
        return 0; // 全失敗時は0にフォールバック（意図的）
    }

    // 後方互換用（elementFontSize, rootFontSize が不明な場合は 16.0f を仮定）
    public static int parseDim(String val, int maxPixels, int rootW, int rootH) {
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

        public int resolve(int maxPixels, int rootW, int rootH, float elementFontSize, float rootFontSize) {
            return switch (unit) {
                case PIXELS -> (int) value;
                case PERCENT -> (int) (maxPixels * value / 100f);
                case VW -> (int) (rootW * value / 100f);
                case VH -> (int) (rootH * value / 100f);
                case EM -> (int) (elementFontSize * value);
                case REM -> (int) (rootFontSize * value);
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

    public static int[] parseBorderRadius(String val, int maxPixels, int rootW, int rootH) {
        String[] edges = parseBoxEdgesStr(val != null ? val : "0");
        int rTL = parseDim(edges[0], maxPixels, rootW, rootH);
        int rTR = parseDim(edges[1], maxPixels, rootW, rootH);
        int rBR = parseDim(edges[2], maxPixels, rootW, rootH);
        int rBL = parseDim(edges[3], maxPixels, rootW, rootH);
        return new int[]{rTL, rTR, rBR, rBL};
    }
}