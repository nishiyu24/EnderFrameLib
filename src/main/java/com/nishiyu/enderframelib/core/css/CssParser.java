package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CssParser {
    private static final Pattern CSS_PATTERN = Pattern.compile("([^{]+)\\{([^}]+)}");
    private static final Pattern GRADIENT_COLOR_PATTERN = Pattern.compile("(#([0-9a-f]{3,8})|rgba?\\([^)]+\\)|hsla?\\([^)]+\\))");
    private static final Pattern RGBA_PATTERN = Pattern.compile("rgba?\\s*\\(\\s*(\\d+)\\s*,\\s*(\\d+)\\s*,\\s*(\\d+)(?:\\s*,\\s*([\\d.]+))?\\s*\\)");
    private static final Pattern HSLA_PATTERN = Pattern.compile("hsla?\\s*\\(\\s*([-\\d.]+)(?:deg)?\\s*,\\s*([\\d.]+)%\\s*,\\s*([\\d.]+)%(?:\\s*,\\s*([\\d.]+))?\\s*\\)");
    private static final Pattern VAR_PATTERN = Pattern.compile("var\\(\\s*(--[\\w-]+)(?:\\s*,\\s*([^)]+))?\\s*\\)");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern FONT_FACE_PATTERN = Pattern.compile("(?is)@font-face\\s*\\{[^}]*}");
    private static final Pattern MAX_WIDTH_MEDIA_PATTERN = Pattern.compile("max-width:\\s*(\\d+)px", Pattern.CASE_INSENSITIVE);
    private static final Pattern MIN_WIDTH_MEDIA_PATTERN = Pattern.compile("min-width:\\s*(\\d+)px", Pattern.CASE_INSENSITIVE);
    private static final Pattern NOT_SELECTOR_PATTERN = Pattern.compile(":not\\(([^)]+)\\)");
    private static final Pattern NTH_CHILD_PATTERN = Pattern.compile(":nth-child\\(([^)]+)\\)");
    private static final Pattern ATTR_SELECTOR_PATTERN = Pattern.compile("\\[([^]=^*$~|]+)(?:([^*$=~|]?=)[\\\"']?([^]\\\"']*)[\\\"']?)?]");
    private static final Pattern BRACKET_STRIP_PATTERN = Pattern.compile("\\[.*?]");
    private static final Pattern SPEC_CLEAN_PATTERN = Pattern.compile("[#.\\[:].*");
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    private static final Set<String> INHERITABLE_PROPS = Set.of(
            "color", "text-align", "font-family", "font-size", "line-height", "text-transform", "font-weight"
    );
    private static final String[] DIRECTIONS = {"top", "right", "bottom", "left"};

    public static Consumer<String> fontFaceCallback = null;

    public static int parseColor(String c) {
        if (c == null || c.isEmpty()) return 0x00000000;
        if (c.equalsIgnoreCase("transparent")) return 0x00000000;
        c = c.toLowerCase().trim();

        if (c.startsWith("linear-gradient") || c.startsWith("radial-gradient")) {
            Matcher m = GRADIENT_COLOR_PATTERN.matcher(c);
            if (m.find()) return parseColor(m.group(1));
            return 0xFF555555;
        }

        if (c.startsWith("rgba") || c.startsWith("rgb")) {
            Matcher m = RGBA_PATTERN.matcher(c);
            if (m.find()) {
                int r = Math.clamp(Integer.parseInt(m.group(1)), 0, 255);
                int g = Math.clamp(Integer.parseInt(m.group(2)), 0, 255);
                int b = Math.clamp(Integer.parseInt(m.group(3)), 0, 255);
                float a = m.group(4) != null ? Math.clamp(Float.parseFloat(m.group(4)), 0f, 1f) : 1.0f;
                int alpha = (int) (a * 255);
                return (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }

        if (c.startsWith("hsla") || c.startsWith("hsl")) {
            Matcher m = HSLA_PATTERN.matcher(c);
            if (m.find()) {
                float h = Float.parseFloat(m.group(1));
                float s = Math.clamp(Float.parseFloat(m.group(2)) / 100.0f, 0f, 1f);
                float l = Math.clamp(Float.parseFloat(m.group(3)) / 100.0f, 0f, 1f);
                float a = m.group(4) != null ? Math.clamp(Float.parseFloat(m.group(4)), 0f, 1f) : 1.0f;
                return hslToArgb(h, s, l, a);
            }
        }

        int rgb = switch (c) {
            case "red" -> 0xFF5555;
            case "green", "lime" -> 0x55FF55;
            case "blue" -> 0x5555FF;
            case "yellow" -> 0xFFFF55;
            case "aqua", "cyan" -> 0x55FFFF;
            case "magenta", "purple" -> 0xFF55FF;
            case "orange", "gold" -> 0xFFAA00;
            case "black" -> 0x000000;
            case "white" -> 0xFFFFFF;
            case "gray", "grey" -> 0xAAAAAA;
            case "darkgray", "darkgrey" -> 0x555555;
            case "lightgray", "lightgrey", "silver" -> 0xD3D3D3;
            case "coral" -> 0xFF7F50;
            case "crimson" -> 0xDC143C;
            case "dodgerblue" -> 0x1E90FF;
            case "skyblue" -> 0x87CEEB;
            case "teal" -> 0x008080;
            case "indigo" -> 0x4B0082;
            case "violet" -> 0xEE82EE;
            case "pink" -> 0xFFC0CB;
            case "salmon" -> 0xFA8072;
            case "tomato" -> 0xFF6347;
            case "turquoise" -> 0x40E0D0;
            case "wheat" -> 0xF5DEB3;
            case "rebeccapurple" -> 0x663399;
            default -> {
                if (c.startsWith("#")) {
                    try {
                        if (c.length() == 9) yield (int) Long.parseLong(c.substring(1), 16);
                        if (c.length() == 7) yield Integer.parseInt(c.substring(1), 16);
                        if (c.length() == 4) {
                            int r = Integer.parseInt(c.substring(1, 2), 16);
                            int g = Integer.parseInt(c.substring(2, 3), 16);
                            int b = Integer.parseInt(c.substring(3, 4), 16);
                            r = (r << 4) | r;
                            g = (g << 4) | g;
                            b = (b << 4) | b;
                            yield (r << 16) | (g << 8) | b;
                        }
                    } catch (Exception ignored) {
                    }
                }
                yield 0xFFFFFF;
            }
        };
        return (c.length() == 9 && c.startsWith("#")) ? rgb : (0xFF000000 | rgb);
    }

    private static int hslToArgb(float h, float s, float l, float a) {
        h = ((h % 360.0f) + 360.0f) % 360.0f;
        float c = (1.0f - Math.abs(2.0f * l - 1.0f)) * s;
        float x = c * (1.0f - Math.abs(((h / 60.0f) % 2.0f) - 1.0f));
        float m = l - c / 2.0f;
        float r1 = 0, g1 = 0, b1 = 0;
        if (h < 60) { r1 = c; g1 = x; }
        else if (h < 120) { r1 = x; g1 = c; }
        else if (h < 180) { g1 = c; b1 = x; }
        else if (h < 240) { g1 = x; b1 = c; }
        else if (h < 300) { r1 = x; b1 = c; }
        else { r1 = c; b1 = x; }
        int r = Math.clamp(Math.round((r1 + m) * 255.0f), 0, 255);
        int g = Math.clamp(Math.round((g1 + m) * 255.0f), 0, 255);
        int b = Math.clamp(Math.round((b1 + m) * 255.0f), 0, 255);
        int alpha = Math.clamp(Math.round(a * 255.0f), 0, 255);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    public static int parseDim(String val, int maxPixels, int rootW, int rootH) {
        if (val == null || val.equals("auto")) return 0;
        val = val.trim();

        if (val.startsWith("clamp(") && val.endsWith(")")) {
            String inner = val.substring(6, val.length() - 1);
            String[] args = inner.split(",");
            if (args.length == 3) {
                int min = parseDim(args[0].trim(), maxPixels, rootW, rootH);
                int pref = parseDim(args[1].trim(), maxPixels, rootW, rootH);
                int max = parseDim(args[2].trim(), maxPixels, rootW, rootH);
                return Math.max(min, Math.min(pref, max));
            }
        }

        if (val.startsWith("calc(") && val.endsWith(")")) {
            String inner = val.substring(5, val.length() - 1).trim();
            return (int) evalMath(inner, maxPixels, rootW, rootH);
        }

        try {
            if (val.endsWith("px")) return (int) Float.parseFloat(val.substring(0, val.length() - 2));
            if (val.endsWith("%")) return (int) (maxPixels * Float.parseFloat(val.substring(0, val.length() - 1)) / 100f);
            if (val.endsWith("vw")) return (int) (rootW * Float.parseFloat(val.substring(0, val.length() - 2)) / 100f);
            if (val.endsWith("vh")) return (int) (rootH * Float.parseFloat(val.substring(0, val.length() - 2)) / 100f);
            if (val.endsWith("rem")) return (int) (16 * Float.parseFloat(val.substring(0, val.length() - 3)));
            if (val.endsWith("em")) return (int) (16 * Float.parseFloat(val.substring(0, val.length() - 2)));
            return (int) Float.parseFloat(val);
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static double evalMath(String str, int maxPixels, int rootW, int rootH) {
        return new Object() {
            int pos = -1, ch;
            void nextChar() { ch = (++pos < str.length()) ? str.charAt(pos) : -1; }
            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }
            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < str.length()) return 0;
                return x;
            }
            double parseExpression() {
                double x = parseTerm();
                for (; ; ) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }
            double parseTerm() {
                double x = parseFactor();
                for (; ; ) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else return x;
                }
            }
            double parseFactor() {
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
                            case "rem", "em" -> 16 * val;
                            default -> val;
                        };
                    } catch (Exception e) {
                        x = 0;
                    }
                } else {
                    return 0;
                }
                return x;
            }
        }.parse();
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

    public static Map<String, String> computeNodeStyle(HtmlNode node, Map<String, String> inheritedStyle, StyleSheet sheet, Map<String, String> env) {
        if (!node.isDirty && node.computedStyle != null) {
            return node.computedStyle;
        }

        Map<String, String> style = new HashMap<>();
        for (String k : INHERITABLE_PROPS) {
            if (inheritedStyle.containsKey(k)) style.put(k, inheritedStyle.get(k));
        }

        for (Map.Entry<String, String> entry : inheritedStyle.entrySet()) {
            if (entry.getKey().startsWith("--")) style.put(entry.getKey(), entry.getValue());
        }

        for (StyleRule rule : sheet.rules) {
            if (!rule.isBefore && !rule.isAfter && rule.matches(node)) {
                if (rule.isActive) {
                    for (Map.Entry<String, String> e : rule.properties.entrySet()) {
                        style.put("active-" + e.getKey(), e.getValue());
                    }
                } else if (rule.isFocus) {
                    for (Map.Entry<String, String> e : rule.properties.entrySet()) {
                        style.put("focus-" + e.getKey(), e.getValue());
                    }
                } else if (rule.isHover) {
                    for (Map.Entry<String, String> e : rule.properties.entrySet()) {
                        style.put("hover-" + e.getKey(), e.getValue());
                    }
                } else {
                    style.putAll(rule.properties);
                }
            }
        }

        style.putAll(node.attrs);

        if (node.attrs.containsKey("style")) {
            String inlineStyle = node.attrs.get("style");
            for (String prop : inlineStyle.split(";")) {
                int colon = prop.indexOf(':');
                if (colon > 0) {
                    String key = prop.substring(0, colon).trim().toLowerCase();
                    String val = prop.substring(colon + 1).trim();
                    style.put(key, val);
                    style.remove("hover-" + key);
                }
            }
        }

        boolean changed;
        int depth = 0;
        do {
            changed = false;
            for (Map.Entry<String, String> entry : style.entrySet()) {
                String val = entry.getValue();
                if (val != null && val.contains("var(")) {
                    Matcher vm = VAR_PATTERN.matcher(val);
                    StringBuilder sb = new StringBuilder();
                    while (vm.find()) {
                        String varName = vm.group(1).trim();
                        String fallback = vm.group(2) != null ? vm.group(2).trim() : "";
                        String resolved = style.get(varName);
                        if (resolved == null) resolved = sheet.variables.get(varName);

                        if (resolved == null && env != null) {
                            String envKey = varName.substring(2);
                            if (env.containsKey(envKey)) resolved = env.get(envKey);
                        }
                        if (resolved == null || resolved.isEmpty()) resolved = fallback;
                        vm.appendReplacement(sb, Matcher.quoteReplacement(resolved));
                    }
                    vm.appendTail(sb);
                    String newVal = sb.toString();
                    if (!newVal.equals(val)) {
                        entry.setValue(newVal);
                        changed = true;
                    }
                }
            }
            depth++;
        } while (changed && depth < 10);

        if (style.containsKey("border")) {
            String border = style.get("border");
            style.putIfAbsent("border-top", border);
            style.putIfAbsent("border-right", border);
            style.putIfAbsent("border-bottom", border);
            style.putIfAbsent("border-left", border);
        }

        for (String dir : DIRECTIONS) {
            String borderProp = style.get("border-" + dir);
            if (borderProp != null && !borderProp.equals("none")) {
                String[] parts = WHITESPACE_SPLIT.split(borderProp.trim());
                if (parts.length > 0) style.putIfAbsent("border-" + dir + "-width", parts[0]);
            }
        }

        node.computedStyle = style;
        node.isDirty = false;

        return style;
    }

    private static String processAtRules(String css, int rootW) {
        StringBuilder sb = new StringBuilder();
        int i = 0, len = css.length();
        while (i < len) {
            char c = css.charAt(i);
            if (c == '@') {
                int blockStart = i;
                while (i < len && css.charAt(i) != '{' && css.charAt(i) != ';') i++;
                if (i >= len) break;

                if (css.charAt(i) == ';') {
                    sb.append(css, blockStart, i + 1);
                    i++;
                    continue;
                }

                String condition = css.substring(blockStart, i).trim();
                i++;
                int braceCount = 1, contentStart = i;
                while (i < len) {
                    char ac = css.charAt(i);
                    if (ac == '{') braceCount++;
                    else if (ac == '}') {
                        braceCount--;
                        if (braceCount == 0) break;
                    }
                    i++;
                }

                if (condition.startsWith("@media")) {
                    boolean conditionMet = false;
                    Matcher m = MAX_WIDTH_MEDIA_PATTERN.matcher(condition);
                    if (m.find()) {
                        int maxWidth = Integer.parseInt(m.group(1));
                        if (rootW <= maxWidth) conditionMet = true;
                    }
                    Matcher minM = MIN_WIDTH_MEDIA_PATTERN.matcher(condition);
                    if (minM.find()) {
                        int minWidth = Integer.parseInt(minM.group(1));
                        if (rootW >= minWidth) conditionMet = true;
                    }
                    if (conditionMet && contentStart <= i && i <= len) {
                        sb.append(css, contentStart, i);
                    }
                } else if (contentStart <= i && i <= len) {
                    sb.append(css, blockStart, contentStart - 1);
                    sb.append("{").append(css, contentStart, i).append("}");
                }
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    public static StyleSheet parse(String css, int rootW) {
        StyleSheet sheet = new StyleSheet();
        if (css == null || css.isEmpty()) return sheet;
        css = COMMENT_PATTERN.matcher(css).replaceAll("");

        if (fontFaceCallback != null && css.toLowerCase().contains("@font-face")) {
            fontFaceCallback.accept(css);
        }
        css = FONT_FACE_PATTERN.matcher(css).replaceAll("");
        css = processAtRules(css, rootW);

        int kfIdx = 0;
        while ((kfIdx = css.indexOf("@keyframes", kfIdx)) != -1) {
            int nameStart = kfIdx + 10;
            int braceStart = css.indexOf("{", nameStart);
            if (braceStart == -1) break;
            String name = css.substring(nameStart, braceStart).trim();

            int braceCount = 1;
            int contentStart = braceStart + 1;
            int i = contentStart;
            while (i < css.length() && braceCount > 0) {
                if (css.charAt(i) == '{') braceCount++;
                else if (css.charAt(i) == '}') braceCount--;
                i++;
            }
            if (i > css.length()) break;

            String content = css.substring(contentStart, i - 1);
            StringBuilder parsedContent = new StringBuilder();
            Matcher m = CSS_PATTERN.matcher(content);
            while (m.find()) {
                String percentStr = m.group(1).trim().replace("%", "");
                if (percentStr.equals("from")) percentStr = "0";
                if (percentStr.equals("to")) percentStr = "100";
                parsedContent.append(percentStr).append("`").append(m.group(2).trim()).append("|");
            }
            sheet.keyframes.put(name, parsedContent.toString());

            css = css.substring(0, kfIdx) + css.substring(i);
        }

        Map<String, String> variables = new HashMap<>();
        Matcher m = CSS_PATTERN.matcher(css);
        while (m.find()) {
            String selectorsStr = m.group(1).trim();
            String propertiesStr = m.group(2).trim();
            Map<String, String> properties = new HashMap<>();
            for (String prop : propertiesStr.split(";")) {
                int colon = prop.indexOf(':');
                if (colon > 0) {
                    String key = prop.substring(0, colon).trim().toLowerCase();
                    String val = prop.substring(colon + 1).trim();

                    properties.put(key, val);
                    if (key.startsWith("--") && selectorsStr.contains(":root")) {
                        variables.put(key, val);
                    }
                }
            }
            for (String selector : selectorsStr.split(",")) {
                selector = selector.trim();
                if (selector.isEmpty() || selector.equals(":root")) continue;
                StyleRule rule = new StyleRule(selector);
                rule.properties.putAll(properties);
                sheet.rules.add(rule);
            }
        }
        sheet.variables.putAll(variables);
        sheet.rules.sort((a, b) -> Integer.compare(a.specificity, b.specificity));
        return sheet;
    }

    private static class SelectorToken {
        String rawSelector;
        String tag = "*";
        String id = null;
        List<String> classes = new ArrayList<>();
        Map<String, String> requiredAttrs = new HashMap<>();
        Map<String, String> prefixAttrs = new HashMap<>();
        Map<String, String> suffixAttrs = new HashMap<>();
        Map<String, String> containsAttrs = new HashMap<>();
        List<String> requiredExistsAttrs = new ArrayList<>();
        List<SelectorToken> notSelectors = new ArrayList<>();
        boolean isHover = false;
        boolean parsedHover = false;
        boolean parsedActive = false;
        boolean parsedFocus = false;
        boolean isChecked = false;
        boolean isDisabled = false;
        boolean isFirstChild = false;
        boolean isLastChild = false;
        boolean isEmpty = false;
        int nthChild = -1; // >0 = specific 1-based index, -2 = odd, -3 = even

        SelectorToken(String sel) {
            this.rawSelector = sel;

            Matcher notMatcher = NOT_SELECTOR_PATTERN.matcher(sel);
            while (notMatcher.find()) {
                String notSel = notMatcher.group(1).trim();
                notSelectors.add(new SelectorToken(notSel));
            }
            sel = notMatcher.replaceAll("");

            if (sel.contains(":hover")) {
                parsedHover = true;
                sel = sel.replace(":hover", "");
            }
            if (sel.contains(":active")) {
                parsedActive = true;
                sel = sel.replace(":active", "");
            }
            if (sel.contains(":focus-visible")) {
                parsedFocus = true;
                sel = sel.replace(":focus-visible", "");
            }
            if (sel.contains(":focus")) {
                parsedFocus = true;
                sel = sel.replace(":focus", "");
            }
            if (sel.contains(":checked")) {
                isChecked = true;
                sel = sel.replace(":checked", "");
            }
            if (sel.contains(":disabled")) {
                isDisabled = true;
                sel = sel.replace(":disabled", "");
            }
            if (sel.contains(":first-child")) {
                isFirstChild = true;
                sel = sel.replace(":first-child", "");
            }
            if (sel.contains(":last-child")) {
                isLastChild = true;
                sel = sel.replace(":last-child", "");
            }
            if (sel.contains(":empty")) {
                isEmpty = true;
                sel = sel.replace(":empty", "");
            }
            if (sel.contains(":nth-child")) {
                Matcher m = NTH_CHILD_PATTERN.matcher(sel);
                if (m.find()) {
                    String arg = m.group(1).trim().toLowerCase();
                    if ("odd".equals(arg) || "2n+1".equals(arg)) {
                        nthChild = -2;
                    } else if ("even".equals(arg) || "2n".equals(arg)) {
                        nthChild = -3;
                    } else {
                        try { nthChild = Integer.parseInt(arg); } catch (Exception ignored) {}
                    }
                    sel = m.replaceAll("");
                }
            }

            Matcher attrMatcher = ATTR_SELECTOR_PATTERN.matcher(sel);
            while (attrMatcher.find()) {
                String attrKey = attrMatcher.group(1).trim();
                String op = attrMatcher.group(2);
                String attrVal = attrMatcher.group(3);
                if (op != null && attrVal != null) {
                    switch (op) {
                        case "^=" -> prefixAttrs.put(attrKey, attrVal);
                        case "$=" -> suffixAttrs.put(attrKey, attrVal);
                        case "*=" -> containsAttrs.put(attrKey, attrVal);
                        default -> requiredAttrs.put(attrKey, attrVal);
                    }
                } else {
                    requiredExistsAttrs.add(attrKey);
                }
            }
            sel = BRACKET_STRIP_PATTERN.matcher(sel).replaceAll("");

            if (sel.contains("#")) {
                int hashIdx = sel.indexOf("#");
                String afterHash = sel.substring(hashIdx + 1);
                id = afterHash.split("\\.")[0];
                sel = sel.substring(0, hashIdx) + (afterHash.contains(".") ? afterHash.substring(afterHash.indexOf(".")) : "");
            }
            if (sel.contains(".")) {
                String[] cls = sel.split("\\.");
                tag = cls[0].isEmpty() ? "*" : cls[0];
                for (int i = 1; i < cls.length; i++) {
                    if (!cls[i].isEmpty()) classes.add(cls[i]);
                }
            } else if (!sel.isEmpty()) {
                tag = sel.trim();
            }
        }

        boolean matches(HtmlNode node) {
            for (SelectorToken notToken : notSelectors) {
                if (notToken.matches(node)) return false;
            }
            if (id != null && !id.equals(node.id)) return false;
            if (!tag.equals("*") && !tag.equals(node.tag)) return false;
            for (String c : classes) {
                if (!node.classes.contains(c)) return false;
            }
            if (isDisabled && !node.attrs.containsKey("disabled")) return false;

            if (isChecked) {
                boolean nodeChecked = node.isChecked || node.attrs.containsKey("checked");
                if (!nodeChecked) return false;
            }

            if (isHover) {
                boolean nodeHovered = node.isHovered || node.attrs.containsKey("hover");
                if (!nodeHovered) return false;
            }

            if (isEmpty) {
                boolean hasContent = (node.text != null && !node.text.trim().isEmpty());
                if (!hasContent) {
                    for (HtmlNode c : node.children) {
                        if (!c.isPseudoNode && (!c.tag.equals("#text") || (c.text != null && !c.text.trim().isEmpty()))) {
                            hasContent = true;
                            break;
                        }
                    }
                }
                if (hasContent) return false;
            }

            for (String req : requiredExistsAttrs) {
                if (!node.attrs.containsKey(req)) return false;
            }
            for (Map.Entry<String, String> req : requiredAttrs.entrySet()) {
                boolean nodeChecked = node.isChecked || node.attrs.containsKey("checked");
                if (req.getKey().equals("checked") && nodeChecked) continue;

                if (!node.attrs.containsKey(req.getKey())) return false;
                if (req.getValue() != null && !req.getValue().equals(node.attrs.get(req.getKey()))) return false;
            }
            for (Map.Entry<String, String> req : prefixAttrs.entrySet()) {
                String actual = node.attrs.get(req.getKey());
                if (actual == null || !actual.startsWith(req.getValue())) return false;
            }
            for (Map.Entry<String, String> req : suffixAttrs.entrySet()) {
                String actual = node.attrs.get(req.getKey());
                if (actual == null || !actual.endsWith(req.getValue())) return false;
            }
            for (Map.Entry<String, String> req : containsAttrs.entrySet()) {
                String actual = node.attrs.get(req.getKey());
                if (actual == null || !actual.contains(req.getValue())) return false;
            }

            if ((nthChild != -1 || isFirstChild || isLastChild) && node.parent != null) {
                List<HtmlNode> elementSiblings = new ArrayList<>();
                for (HtmlNode child : node.parent.children) {
                    if (!child.tag.startsWith("#") && !child.tag.equals("template") && !child.isPseudoNode) {
                        elementSiblings.add(child);
                    }
                }
                int idx = elementSiblings.indexOf(node) + 1; // 1-based
                if (idx <= 0) return false;
                if (isFirstChild && idx != 1) return false;
                if (isLastChild && idx != elementSiblings.size()) return false;
                if (nthChild > 0 && idx != nthChild) return false;
                if (nthChild == -2 && (idx % 2 == 0)) return false; // odd
                if (nthChild == -3 && (idx % 2 != 0)) return false; // even
            }
            return true;
        }
    }

    public static class StyleRule {
        public String selector;
        public int specificity;
        public boolean isHover = false;
        public boolean isActive = false;
        public boolean isFocus = false;
        public boolean isBefore = false;
        public boolean isAfter = false;
        public Map<String, String> properties = new HashMap<>();

        private final List<SelectorToken> tokens = new ArrayList<>();
        private final List<Character> combinators = new ArrayList<>();

        public StyleRule(String selector) {
            this.specificity = calculateSpecificity(selector);

            if (selector.contains("::before") || selector.contains(":before")) {
                this.isBefore = true;
                selector = selector.replace("::before", "").replace(":before", "");
            }
            if (selector.contains("::after") || selector.contains(":after")) {
                this.isAfter = true;
                selector = selector.replace("::after", "").replace(":after", "");
            }
            this.selector = selector.trim();
            parseSelectorTokens(this.selector);
        }

        private void parseSelectorTokens(String sel) {
            sel = WHITESPACE_SPLIT.matcher(sel).replaceAll(" ")
                    .replace(" > ", ">").replace(" >", ">").replace("> ", ">")
                    .replace(" + ", "+").replace(" +", "+").replace("+ ", "+")
                    .replace(" ~ ", "~").replace(" ~", "~").replace("~ ", "~").trim();

            int lastIdx = 0;
            for (int i = 0; i < sel.length(); i++) {
                char c = sel.charAt(i);
                if (c == ' ' || c == '>' || c == '+' || c == '~') {
                    tokens.add(new SelectorToken(sel.substring(lastIdx, i)));
                    combinators.add(c);
                    lastIdx = i + 1;
                }
            }
            tokens.add(new SelectorToken(sel.substring(lastIdx)));

            boolean hasCombinators = !combinators.isEmpty();
            for (SelectorToken t : tokens) {
                if (t.parsedHover) {
                    if (hasCombinators) {
                        t.isHover = true;
                        this.isHover = false;
                    } else {
                        t.isHover = false;
                        this.isHover = true;
                    }
                }
                if (t.parsedActive) {
                    this.isActive = true;
                }
                if (t.parsedFocus) {
                    this.isFocus = true;
                }
            }
        }

        private int calculateSpecificity(String sel) {
            int spec = 0;
            String[] parts = WHITESPACE_SPLIT.split(sel.trim());
            for (String part : parts) {
                for (int i = 0; i < part.length(); i++) {
                    char c = part.charAt(i);
                    if (c == '#') spec += 100;
                    else if (c == '.' || c == '[' || c == ':') spec += 10;
                }
                String cleanPart = SPEC_CLEAN_PATTERN.matcher(part).replaceAll("");
                if (!cleanPart.isEmpty() && !cleanPart.equals("*")) spec += 1;
            }
            return spec;
        }

        public boolean matches(HtmlNode node) {
            if (tokens.isEmpty()) return false;
            int pIdx = tokens.size() - 1;

            if (!tokens.get(pIdx).matches(node)) return false;
            pIdx--;

            HtmlNode current = node;
            while (pIdx >= 0 && current != null) {
                char combinator = combinators.get(pIdx);
                SelectorToken expected = tokens.get(pIdx);

                if (combinator == '>') {
                    current = current.parent;
                    if (current == null || !expected.matches(current)) return false;
                    pIdx--;
                } else if (combinator == ' ') {
                    current = current.parent;
                    while (current != null && !expected.matches(current)) {
                        current = current.parent;
                    }
                    if (current == null) return false;
                    pIdx--;
                } else if (combinator == '+') {
                    current = getPreviousSibling(current);
                    if (current == null || !expected.matches(current)) return false;
                    pIdx--;
                } else if (combinator == '~') {
                    current = getPreviousSibling(current);
                    while (current != null && !expected.matches(current)) {
                        current = getPreviousSibling(current);
                    }
                    if (current == null) return false;
                    pIdx--;
                }
            }
            return pIdx < 0;
        }

        private HtmlNode getPreviousSibling(HtmlNode node) {
            if (node == null || node.parent == null) return null;
            HtmlNode prev = null;
            for (HtmlNode child : node.parent.children) {
                if (child == node) return prev;
                if (!child.tag.startsWith("#") && !child.tag.equals("template") && !child.isPseudoNode) prev = child;
            }
            return null;
        }
    }

    public static class StyleSheet {
        public List<StyleRule> rules = new ArrayList<>();
        public Map<String, String> keyframes = new HashMap<>();
        public Map<String, String> variables = new HashMap<>();
    }
}