package com.nishiyu.enderframelib.core.css;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * トークナイズされたブロック列を {@link StyleSheet} に積み上げるパーサ。
 *
 * {@code @media} は文字列として平坦化せず、ブロックの本文をそのまま再帰的にパースする。
 * （以前は @media の中身を文字列として切り貼りしてから正規表現で再マッチしていた）
 */
public final class CssRuleParser {

    /** @media / @supports のネストが異常に深い CSS で再帰が暴走しないための上限。 */
    private static final int MAX_AT_RULE_DEPTH = 16;

    private CssRuleParser() {}

    public static void parseInto(String css, StyleSheet sheet, int rootW, CascadeOrigin origin) {
        parseInto(css, sheet, rootW, origin, 0);
    }

    private static void parseInto(String css, StyleSheet sheet, int rootW, CascadeOrigin origin, int depth) {
        if (depth > MAX_AT_RULE_DEPTH) return;

        for (CssTokenizer.Block block : CssTokenizer.tokenize(css)) {
            if (block.isAtRule()) {
                handleAtRule(block, sheet, rootW, origin, depth);
            } else {
                handleStyleRule(block, sheet, origin);
            }
        }
    }

    private static void handleAtRule(CssTokenizer.Block block, StyleSheet sheet, int rootW,
                                     CascadeOrigin origin, int depth) {
        switch (block.atRuleName().toLowerCase()) {
            case "@media" -> {
                if (AtRuleProcessor.evaluateMediaQuery(block.atRuleParams(), rootW)) {
                    parseInto(block.body(), sheet, rootW, origin, depth + 1);
                }
            }
            case "@supports" -> parseInto(block.body(), sheet, rootW, origin, depth + 1);
            case "@keyframes", "@-webkit-keyframes" -> collectKeyframes(block, sheet);
            case "@font-face" -> {
                // フォント登録は CssParser.fontFaceCallback が CSS 全体を受け取って処理する
            }
            default -> {
                // 未対応のアットルール（@page / @layer など）は無視する
            }
        }
    }

    /**
     * {@code @keyframes} を {@code "0`color: red|100`color: blue|"} 形式に符号化する。
     * （レンダラ側 WebAnimator がこの形式を読む）
     */
    private static void collectKeyframes(CssTokenizer.Block block, StyleSheet sheet) {
        String name = block.atRuleParams().trim();
        if (name.isEmpty()) return;

        StringBuilder encoded = new StringBuilder();
        for (CssTokenizer.Block step : CssTokenizer.tokenize(block.body())) {
            String declarations = step.body().trim();
            for (String stop : CssTokenizer.splitSelectorList(step.prelude())) {
                String percent = stop.trim().replace("%", "");
                if (percent.equalsIgnoreCase("from")) percent = "0";
                if (percent.equalsIgnoreCase("to")) percent = "100";
                encoded.append(percent).append('`').append(declarations).append('|');
            }
        }
        sheet.keyframes.put(name, encoded.toString());
    }

    private static void handleStyleRule(CssTokenizer.Block block, StyleSheet sheet, CascadeOrigin origin) {
        Map<String, String> declarations = CssTokenizer.parseDeclarations(block.body());
        if (declarations.isEmpty()) return;

        // !important を分離する。通常宣言と important 宣言は別ルールとして積み、
        // Specificity の important フラグでカスケードの勝敗を決める。
        Map<String, String> normal = new LinkedHashMap<>();
        Map<String, String> important = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : declarations.entrySet()) {
            String val = e.getValue();
            String stripped = stripImportant(val);
            if (stripped != null) {
                important.put(e.getKey(), stripped);
            } else {
                normal.put(e.getKey(), val);
            }
        }

        List<String> selectors = CssTokenizer.splitSelectorList(block.prelude());
        for (String selectorText : selectors) {
            if (!normal.isEmpty()) {
                addRule(sheet, selectorText, origin, false, normal);
            }
            if (!important.isEmpty()) {
                addRule(sheet, selectorText, origin, true, important);
            }
        }

        // :root のカスタムプロパティはシート全体の変数表に集める
        collectRootVariables(selectors, declarations, sheet);
    }

    private static void addRule(StyleSheet sheet, String selectorText, CascadeOrigin origin,
                                boolean important, Map<String, String> properties) {
        StyleRule rule = new StyleRule(SelectorParser.parse(selectorText, origin, important));
        rule.properties.putAll(properties);
        sheet.rules.add(rule);
    }

    private static void collectRootVariables(List<String> selectors, Map<String, String> declarations,
                                             StyleSheet sheet) {
        boolean isRootRule = false;
        for (String s : selectors) {
            if (s.contains(":root")) {
                isRootRule = true;
                break;
            }
        }
        if (!isRootRule) return;

        for (Map.Entry<String, String> e : declarations.entrySet()) {
            if (e.getKey().startsWith("--")) {
                String stripped = stripImportant(e.getValue());
                sheet.variables.put(e.getKey(), stripped != null ? stripped : e.getValue());
            }
        }
    }

    /** {@code !important} が付いていれば、それを除いた値を返す。付いていなければ null。 */
    private static String stripImportant(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        int bang = trimmed.lastIndexOf('!');
        if (bang < 0) return null;
        if (!trimmed.substring(bang + 1).trim().equalsIgnoreCase("important")) return null;
        return trimmed.substring(0, bang).trim();
    }
}
