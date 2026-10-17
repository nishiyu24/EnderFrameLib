package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 破壊的解体後の薄いファサード。
 * 外部のクラス（UIParser, LayoutBox など）からの既存呼び出しを新しい基盤に中継します。
 */
public class CssParser {

    private static final Pattern CSS_PATTERN = Pattern.compile("([^{]+)\\{([^}]+)}");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?s)/\\*.*?\\*/");
    private static final Pattern FONT_FACE_PATTERN = Pattern.compile("(?is)@font-face\\s*\\{[^}]*}");

    public static Consumer<String> fontFaceCallback = null;

    /**
     * @deprecated ColorParser.parseColor を直接使用してください。
     */
    @Deprecated
    public static int parseColor(String c) {
        return ColorParser.parseColor(c);
    }

    /**
     * @deprecated LengthResolver.parseDim を直接使用してください。
     */
    @Deprecated
    public static int parseDim(String val, int maxPixels, int rootW, int rootH) {
        return LengthResolver.parseDim(val, maxPixels, rootW, rootH);
    }

    /**
     * @deprecated LengthResolver.parseBoxEdgesStr を直接使用してください。
     */
    @Deprecated
    public static String[] parseBoxEdgesStr(String val) {
        return LengthResolver.parseBoxEdgesStr(val);
    }

    /**
     * @deprecated LengthResolver.parseBorderRadius を直接使用してください。
     */
    @Deprecated
    public static int[] parseBorderRadius(String val, int maxPixels, int rootW, int rootH) {
        return LengthResolver.parseBorderRadius(val, maxPixels, rootW, rootH);
    }

    /**
     * 後方互換性用の中継メソッド。
     * LayoutBox 等が ComputedStyle を直接受け取るように改修されるフェーズ3で削除予定。
     */
    @Deprecated
    public static Map<String, String> computeNodeStyle(HtmlNode node, Map<String, String> inheritedStyle, StyleSheet sheet, Map<String, String> env) {
        boolean isHovered = node.attrs.containsKey("hover");
        boolean isActive = false;
        boolean isFocused = false;

        StyleSet styleSet = StyleResolver.resolveStyleSet(node, inheritedStyle, sheet);
        Map<String, String> currentStateRaw = styleSet.computeCurrentState(isHovered, isActive, isFocused);

        ComputedStyle computed = StyleResolver.finalizeComputedStyle(currentStateRaw, sheet, env);
        return new HashMap<>(computed.getRawMap());
    }

    public static StyleSheet parse(String css, int rootW) {
        StyleSheet sheet = new StyleSheet();
        if (css == null || css.isEmpty()) return sheet;
        css = COMMENT_PATTERN.matcher(css).replaceAll("");

        if (fontFaceCallback != null && css.toLowerCase().contains("@font-face")) {
            fontFaceCallback.accept(css);
        }
        css = FONT_FACE_PATTERN.matcher(css).replaceAll("");

        css = AtRuleProcessor.processAtRules(css, rootW);

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

                    // A-8 修正: :root 宣言は変数 (--var) 以外も破棄せずに base スタイルとして適用できるようにする
                    if (selectorsStr.contains(":root")) {
                        if (key.startsWith("--")) variables.put(key, val);
                    }
                }
            }
            for (String selector : selectorsStr.split(",")) {
                selector = selector.trim();
                if (selector.isEmpty()) continue;

                StyleRule rule = new StyleRule(selector);
                rule.properties.putAll(properties);
                sheet.rules.add(rule);
            }
        }
        sheet.variables.putAll(variables);

        sheet.rules.sort((a, b) -> a.specificity.compareTo(b.specificity));
        return sheet;
    }

    // 後方互換用のエイリアス
    public static class SelectorToken extends com.nishiyu.enderframelib.core.css.SelectorToken {
        public SelectorToken(String sel) { super(sel); }
    }
    public static class StyleRule extends com.nishiyu.enderframelib.core.css.StyleRule {
        public StyleRule(String sel) { super(sel); }
    }
}