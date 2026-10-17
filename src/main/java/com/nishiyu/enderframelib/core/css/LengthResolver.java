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