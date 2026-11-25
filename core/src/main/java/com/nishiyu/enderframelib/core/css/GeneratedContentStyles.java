package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.StateLookup;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;

import java.util.Map;

/**
 * 生成内容（{@code ::before} / {@code ::after}・CSS のカウンタ）のスタイル側の入口（Engine v3 M2b-1）。css の外の層（{@code core/box}）が
 * {@link StyleResolver.PseudoType}・{@link StyleSheet.StyleRule} の入れ子型に触れずに使うためのトップレベルの関数。
 */
public final class GeneratedContentStyles {
    private GeneratedContentStyles() { }

    /**
     * {@code ::before}（{@code after} なら {@code ::after}）の算出スタイル（{@link StyleResolver#resolvePseudoStyle} そのまま）。
     * 箱を作らない（{@code content} が none / normal・{@code display: none}・規則が無い）なら null。
     */
    public static ComputedStyle resolve(HtmlNode owner, boolean after, StyleSheet sheet, StateLookup lookup, ComputedStyle ownerStyle,
                                        Map<String, String> env, float rootFontSize, float rootLineHeight, int viewportW, int viewportH) {
        return StyleResolver.resolvePseudoStyle(owner, after ? StyleResolver.PseudoType.AFTER : StyleResolver.PseudoType.BEFORE, sheet,
                lookup, ownerStyle, env, rootFontSize, rootLineHeight, viewportW, viewportH);
    }

    /**
     * {@code ::marker} の規則が宣言したスタイル（{@link StyleResolver#resolvePseudoStyle} の MARKER。規則が無ければ null）。
     * 箱のスタイルは持ち主の {@link ComputedStyle#derivedMarker} で UA の上書きを掛けて作る。
     */
    public static ComputedStyle markerDeclared(HtmlNode owner, StyleSheet sheet, StateLookup lookup, ComputedStyle ownerStyle,
                                               Map<String, String> env, float rootFontSize, float rootLineHeight, int viewportW, int viewportH) {
        return StyleResolver.resolvePseudoStyle(owner, StyleResolver.PseudoType.MARKER, sheet, lookup, ownerStyle, env, rootFontSize,
                rootLineHeight, viewportW, viewportH);
    }

    /** {@code list-style-position: inside}（マーカーを流れの中の原子にする）か。 */
    public static boolean markerInside(Style st) {
        return st.inherited.listStylePosition == com.nishiyu.enderframelib.core.css.model.CssKeywords.ListStylePosition.INSIDE;
    }

    /**
     * 文書が CSS のカウンタを使うか（{@code counter-*} の宣言が list-item 以外の名前を持つ・値に {@code counter()} / {@code counters()}）。
     * UA の暗黙の {@code list-item}（{@code menu, ol, ul { counter-reset: list-item }}）だけなら使わない扱い。
     */
    public static boolean usesCounters(StyleSheet sheet) {
        for (StyleSheet.StyleRule rule : sheet.rules) {
            for (var e : rule.properties.entrySet()) {
                String key = e.getKey(), value = e.getValue();
                if (value == null) continue;
                if ((key.startsWith("counter-") && namesOtherThanListItem(value)) || value.contains("counter(") || value.contains("counters(")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean namesOtherThanListItem(String value) {
        for (String t : value.trim().split("\\s+")) {
            if (t.isEmpty() || t.equalsIgnoreCase("none") || t.equalsIgnoreCase("list-item") || t.matches("[+-]?\\d+")) continue;
            return true;
        }
        return false;
    }
}
