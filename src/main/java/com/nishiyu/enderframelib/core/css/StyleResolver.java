package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StyleResolver {

    private static final Set<String> INHERITABLE_PROPS = Set.of(
            "color", "text-align", "font-family", "font-size", "line-height", "text-transform", "font-weight",
            // A-7 継承プロパティの不足分を追加
            "font-style", "text-decoration", "text-shadow", "white-space", "letter-spacing", "visibility", "cursor", "direction"
    );

    private static final String[] DIRECTIONS = {"top", "right", "bottom", "left"};
    private static final Pattern VAR_PATTERN = Pattern.compile("var\\(\\s*(--[\\w-]+)(?:\\s*,\\s*([^)]+))?\\s*\\)");
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");

    public static StyleSet resolveStyleSet(HtmlNode node, Map<String, String> inheritedStyle, StyleSheet sheet) {
        StyleSet styleSet = new StyleSet();

        for (String k : INHERITABLE_PROPS) {
            if (inheritedStyle.containsKey(k)) styleSet.base.put(k, inheritedStyle.get(k));
        }

        for (Map.Entry<String, String> entry : inheritedStyle.entrySet()) {
            if (entry.getKey().startsWith("--")) styleSet.base.put(entry.getKey(), entry.getValue());
        }

        for (StyleRule rule : sheet.rules) {
            if (!rule.isBefore && !rule.isAfter && rule.matches(node)) {
                if (rule.isActive) {
                    styleSet.active.putAll(rule.properties);
                } else if (rule.isFocus) {
                    styleSet.focus.putAll(rule.properties);
                } else if (rule.isHover) {
                    styleSet.hover.putAll(rule.properties);
                } else {
                    styleSet.base.putAll(rule.properties);
                }
            }
        }

        // A-1 修正: `styleSet.base.putAll(node.attrs);` は削除されました。
        // HTML属性は算術スタイルに混ぜず、必要な消費者が直接 node.attrs を読みます。

        if (node.attrs.containsKey("style")) {
            String inlineStyle = node.attrs.get("style");
            for (String prop : inlineStyle.split(";")) {
                int colon = prop.indexOf(':');
                if (colon > 0) {
                    String key = prop.substring(0, colon).trim().toLowerCase();
                    String val = prop.substring(colon + 1).trim();
                    styleSet.base.put(key, val);
                    // インラインスタイルはホバー等も上書きする
                    styleSet.hover.put(key, val);
                    styleSet.active.put(key, val);
                    styleSet.focus.put(key, val);
                }
            }
        }

        return styleSet;
    }

    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet, Map<String, String> env) {
        Map<String, String> style = new HashMap<>(rawStyle);
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
                        if (resolved == null && sheet != null) resolved = sheet.variables.get(varName);

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

        // margin / padding のショートハンドを 4 辺のロングハンドへ展開する。
        // putIfAbsent なので、個別指定 (margin-top 等) があればそちらが優先される。
        expandBoxShorthand(style, "margin");
        expandBoxShorthand(style, "padding");
        expandGapShorthand(style);

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
                String[] parts = borderProp.trim().split("\\s+");
                if (parts.length > 0) style.putIfAbsent("border-" + dir + "-width", parts[0]);
            }
        }

        // A-8 修正: border-width ショートハンド展開
        if (style.containsKey("border-width")) {
            String[] edges = LengthResolver.parseBoxEdgesStr(style.get("border-width"));
            style.putIfAbsent("border-top-width", edges[0]);
            style.putIfAbsent("border-right-width", edges[1]);
            style.putIfAbsent("border-bottom-width", edges[2]);
            style.putIfAbsent("border-left-width", edges[3]);
        }

        return new ComputedStyle(style);
    }

    /**
     * {@code margin} / {@code padding} のショートハンドを {@code -top/-right/-bottom/-left} に展開する。
     * ロングハンドが既にある場合はそちらを優先する（CSS のショートハンド展開と同じ）。
     */
    private static void expandBoxShorthand(Map<String, String> style, String property) {
        String shorthand = style.get(property);
        if (shorthand == null || shorthand.trim().isEmpty()) return;

        String[] edges = LengthResolver.parseBoxEdgesStr(shorthand);
        for (int i = 0; i < DIRECTIONS.length; i++) {
            if (edges[i] != null) style.putIfAbsent(property + "-" + DIRECTIONS[i], edges[i]);
        }
    }

    /**
     * {@code gap: <row-gap> <column-gap>} を {@code row-gap} / {@code column-gap} に展開する。
     * 値が 1 つだけなら両軸に適用される（CSS の gap ショートハンドと同じ）。
     * {@code grid-gap} / {@code grid-row-gap} / {@code grid-column-gap} も別名として受け付ける。
     */
    private static void expandGapShorthand(Map<String, String> style) {
        if (style.containsKey("grid-row-gap")) style.putIfAbsent("row-gap", style.get("grid-row-gap"));
        if (style.containsKey("grid-column-gap")) style.putIfAbsent("column-gap", style.get("grid-column-gap"));

        String shorthand = style.get("gap");
        if (shorthand == null || shorthand.trim().isEmpty()) shorthand = style.get("grid-gap");
        if (shorthand == null || shorthand.trim().isEmpty()) return;

        String[] parts = WHITESPACE_SPLIT.split(shorthand.trim());
        String rowGap = parts[0];
        String columnGap = parts.length >= 2 ? parts[1] : parts[0];

        style.putIfAbsent("row-gap", rowGap);
        style.putIfAbsent("column-gap", columnGap);
    }
}