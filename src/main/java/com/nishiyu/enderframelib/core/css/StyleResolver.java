package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.dom.HtmlNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class StyleResolver {

    private static final Set<String> INHERITABLE_PROPS = Set.of(
            "color", "text-align", "font-family", "font-size", "line-height", "text-transform", "font-weight",
            // A-7 継承プロパティの不足分を追加
            "font-style", "text-decoration", "text-shadow", "white-space", "letter-spacing", "visibility", "cursor", "direction",
            // F2 継承する折り返し関連（word-break / overflow-wrap は CSS でも継承する）
            "word-break", "overflow-wrap"
    );

    /**
     * 値が変わってもボックスの寸法・配置・テキスト折り返しには一切影響せず、
     * 既存の {@link com.nishiyu.enderframelib.core.layout.LayoutBox} からの再描画（repaint）だけで済むプロパティ群。
     * border ショートハンド自体は border-*-width に展開されるため、幅が変われば border-*-width 側で検出される。
     */
    private static final Set<String> PAINT_ONLY_PROPS = Set.of(
            CssProperty.COLOR,
            CssProperty.BACKGROUND,
            CssProperty.BACKGROUND_COLOR,
            "background-image",
            CssProperty.BORDER,
            CssProperty.BORDER_TOP,
            CssProperty.BORDER_RIGHT,
            CssProperty.BORDER_BOTTOM,
            CssProperty.BORDER_LEFT,
            CssProperty.BORDER_COLOR,
            "border-top-color", "border-right-color", "border-bottom-color", "border-left-color",
            CssProperty.BORDER_RADIUS,
            "border-top-left-radius", "border-top-right-radius", "border-bottom-right-radius", "border-bottom-left-radius",
            CssProperty.BOX_SHADOW,
            CssProperty.TEXT_SHADOW,
            CssProperty.OPACITY,
            CssProperty.VISIBILITY,
            CssProperty.TRANSFORM,
            CssProperty.TRANSFORM_ORIGIN,
            CssProperty.TRANSITION,
            CssProperty.TRANSITION_PROPERTY,
            CssProperty.TRANSITION_DURATION,
            CssProperty.TRANSITION_DELAY,
            CssProperty.TRANSITION_TIMING_FUNCTION,
            CssProperty.ANIMATION,
            CssProperty.ANIMATION_NAME,
            CssProperty.ANIMATION_DURATION,
            CssProperty.ANIMATION_DELAY,
            CssProperty.ANIMATION_TIMING_FUNCTION,
            CssProperty.ANIMATION_ITERATION_COUNT,
            CssProperty.ANIMATION_DIRECTION,
            CssProperty.ANIMATION_FILL_MODE,
            CssProperty.TEXT_DECORATION,
            CssProperty.CURSOR,
            CssProperty.Z_INDEX,
            "outline", "outline-color", "outline-offset"
    );

    private static final String[] DIRECTIONS = {"top", "right", "bottom", "left"};
    private static final Pattern VAR_PATTERN = Pattern.compile("var\\(\\s*(--[\\w-]+)(?:\\s*,\\s*([^)]+))?\\s*\\)");
    private static final Pattern WHITESPACE_SPLIT = Pattern.compile("\\s+");
    /** 単位の無い数値（flex ショートハンドで grow / shrink と basis を見分けるため）。 */
    private static final Pattern UNITLESS_NUMBER = Pattern.compile("\\d*\\.?\\d+");

    public static StyleSet resolveStyleSet(HtmlNode node, Map<String, String> inheritedStyle, StyleSheet sheet) {
        boolean interactive = hasInteractiveRules(node, sheet);
        return new StyleSet(node, inheritedStyle, sheet, interactive);
    }

    /**
     * 指定された状態参照先のもとで、継承・シート規則（詳細度＋出現順）・インラインスタイル・!important を
     * CSS カスケード順に沿って 1 パスで解決する。
     */
    public static Map<String, String> resolveDeclarations(HtmlNode node, Map<String, String> inheritedStyle,
                                                          StyleSheet sheet, SelectorMatcher.StateLookup stateLookup) {
        SelectorMatcher.StateLookup lookup = stateLookup != null ? stateLookup : SelectorMatcher.STATIC_ATTRIBUTES;
        Map<String, String> result = new LinkedHashMap<>();

        if (inheritedStyle != null) {
            for (String k : INHERITABLE_PROPS) {
                if (inheritedStyle.containsKey(k)) result.put(k, inheritedStyle.get(k));
            }
            for (Map.Entry<String, String> entry : inheritedStyle.entrySet()) {
                if (entry.getKey().startsWith("--")) result.put(entry.getKey(), entry.getValue());
            }
        }

        Map<String, String> inlineNormal = null;
        Map<String, String> inlineImportant = null;
        if (node != null && node.attrs.containsKey("style")) {
            inlineNormal = new LinkedHashMap<>();
            inlineImportant = new LinkedHashMap<>();
            for (Map.Entry<String, String> decl : CssTokenizer.parseDeclarations(node.attrs.get("style")).entrySet()) {
                String val = decl.getValue();
                String stripped = stripImportant(val);
                if (stripped != null) {
                    inlineImportant.put(decl.getKey(), stripped);
                } else {
                    inlineNormal.put(decl.getKey(), val);
                }
            }
        }

        if (sheet != null && node != null) {
            // 1. 通常宣言（UA -> Author、詳細度＋出現順）
            for (StyleRule rule : sheet.rules) {
                if (!rule.isBefore && !rule.isAfter && !rule.specificity.important() && rule.matches(node, lookup)) {
                    applyDeclarations(result, rule.properties);
                }
            }
        }

        // 2. インラインの通常宣言（Author 通常より強く、!important より弱い）
        if (inlineNormal != null && !inlineNormal.isEmpty()) {
            applyDeclarations(result, inlineNormal);
        }

        if (sheet != null && node != null) {
            // 3. 作者の !important 宣言
            for (StyleRule rule : sheet.rules) {
                if (!rule.isBefore && !rule.isAfter && rule.specificity.important()
                        && rule.specificity.origin() != CascadeOrigin.USER_AGENT
                        && rule.matches(node, lookup)) {
                    applyDeclarations(result, rule.properties);
                }
            }
        }

        // 4. インラインの !important 宣言
        if (inlineImportant != null && !inlineImportant.isEmpty()) {
            applyDeclarations(result, inlineImportant);
        }

        if (sheet != null && node != null) {
            // 5. UA の !important 宣言（CSS カスケードで最優先）
            for (StyleRule rule : sheet.rules) {
                if (!rule.isBefore && !rule.isAfter && rule.specificity.important()
                        && rule.specificity.origin() == CascadeOrigin.USER_AGENT
                        && rule.matches(node, lookup)) {
                    applyDeclarations(result, rule.properties);
                }
            }
        }

        return result;
    }

    /**
     * 後続ルールのショートハンド宣言が先行ルールのロングハンド宣言を上書きできるよう、
     * 展開先のロングハンドを一旦クリアしてから宣言を適用する。
     */
    private static void applyDeclarations(Map<String, String> target, Map<String, String> decls) {
        if (decls.containsKey(CssProperty.MARGIN)) {
            for (String dir : DIRECTIONS) target.remove("margin-" + dir);
        }
        if (decls.containsKey(CssProperty.PADDING)) {
            for (String dir : DIRECTIONS) target.remove("padding-" + dir);
        }
        if (decls.containsKey(CssProperty.BORDER)) {
            for (String dir : DIRECTIONS) {
                target.remove("border-" + dir);
                target.remove("border-" + dir + "-width");
                target.remove("border-" + dir + "-color");
            }
            target.remove(CssProperty.BORDER_WIDTH);
            target.remove(CssProperty.BORDER_COLOR);
        }
        if (decls.containsKey(CssProperty.BORDER_WIDTH)) {
            for (String dir : DIRECTIONS) target.remove("border-" + dir + "-width");
        }
        if (decls.containsKey(CssProperty.BORDER_COLOR)) {
            for (String dir : DIRECTIONS) target.remove("border-" + dir + "-color");
        }
        for (String dir : DIRECTIONS) {
            if (decls.containsKey("border-" + dir)) {
                target.remove("border-" + dir + "-width");
                target.remove("border-" + dir + "-color");
            }
        }
        if (decls.containsKey(CssProperty.TRANSITION)) {
            target.remove(CssProperty.TRANSITION_PROPERTY);
            target.remove(CssProperty.TRANSITION_DURATION);
            target.remove(CssProperty.TRANSITION_DELAY);
            target.remove(CssProperty.TRANSITION_TIMING_FUNCTION);
        }
        if (decls.containsKey(CssProperty.ANIMATION)) {
            target.remove(CssProperty.ANIMATION_NAME);
            target.remove(CssProperty.ANIMATION_DURATION);
            target.remove(CssProperty.ANIMATION_DELAY);
            target.remove(CssProperty.ANIMATION_TIMING_FUNCTION);
            target.remove(CssProperty.ANIMATION_ITERATION_COUNT);
            target.remove(CssProperty.ANIMATION_DIRECTION);
            target.remove(CssProperty.ANIMATION_FILL_MODE);
        }
        target.putAll(decls);
    }

    private static String stripImportant(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        int bang = trimmed.lastIndexOf('!');
        if (bang < 0) return null;
        if (!trimmed.substring(bang + 1).trim().equalsIgnoreCase("important")) return null;
        return trimmed.substring(0, bang).trim();
    }

    /**
     * このノードがシート内の対話疑似クラス（:hover / :active / :focus*）を含む複合セレクタの
     * 対象になり得るかを判定する。
     */
    public static boolean hasInteractiveRules(HtmlNode node, StyleSheet sheet) {
        if (node == null || sheet == null) return false;
        for (StyleRule rule : sheet.rules) {
            if ((rule.stateMask & ElementState.INTERACTIVE_MASK) == 0) continue;
            for (SelectorToken token : rule.parsedSelector().compounds()) {
                int mask = token.stateFlags;
                for (SelectorToken nt : token.notSelectors) mask |= nt.stateFlags;
                if ((mask & ElementState.INTERACTIVE_MASK) != 0
                        && SelectorMatcher.matchesCompoundWithoutInteractiveState(token, node)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 2 つの算出スタイルの差分がレイアウト（measure / layout）の再実行を要するかどうかを判定する。
     * ペイント専用プロパティしか変わっていなければ false を返し、repaint のみで済ませる。
     */
    public static boolean isLayoutAffectingChange(ComputedStyle oldStyle, ComputedStyle newStyle) {
        if (oldStyle == null || newStyle == null) return true;
        if (Float.compare(oldStyle.fontSizePx(), newStyle.fontSizePx()) != 0
                || Float.compare(oldStyle.rootFontSizePx(), newStyle.rootFontSizePx()) != 0) {
            return true;
        }
        Map<String, String> m1 = oldStyle.asMap();
        Map<String, String> m2 = newStyle.asMap();
        if (m1.equals(m2)) return false;

        for (Map.Entry<String, String> e : m1.entrySet()) {
            String key = e.getKey();
            if (!java.util.Objects.equals(e.getValue(), m2.get(key)) && !PAINT_ONLY_PROPS.contains(key)) {
                return true;
            }
        }
        for (Map.Entry<String, String> e : m2.entrySet()) {
            String key = e.getKey();
            if (!m1.containsKey(key) && !PAINT_ONLY_PROPS.contains(key)) {
                return true;
            }
        }
        return false;
    }

    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet, Map<String, String> env) {
        return finalizeComputedStyle(rawStyle, sheet, env, ComputedStyle.DEFAULT_FONT_SIZE, ComputedStyle.DEFAULT_FONT_SIZE);
    }

    /**
     * 親のフォントサイズを基準に算出スタイルを確定する。
     * em は要素自身のフォントサイズ、rem はルートのフォントサイズが基準になる。
     */
    public static ComputedStyle finalizeComputedStyle(Map<String, String> rawStyle, StyleSheet sheet,
                                                      Map<String, String> env,
                                                      float parentFontSize, float rootFontSize) {
        Map<String, String> style = new LinkedHashMap<>(rawStyle);
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
        expandFlexShorthand(style);
        expandOverflowShorthand(style);
        expandInsetShorthand(style);

        if (style.containsKey(CssProperty.BORDER)) {
            String border = style.get(CssProperty.BORDER);
            style.putIfAbsent(CssProperty.BORDER_TOP, border);
            style.putIfAbsent(CssProperty.BORDER_RIGHT, border);
            style.putIfAbsent(CssProperty.BORDER_BOTTOM, border);
            style.putIfAbsent(CssProperty.BORDER_LEFT, border);
        }

        // border-color ショートハンドの展開（border-<dir> からの色抽出より先に行い、個別色指定を優先させる）
        if (style.containsKey(CssProperty.BORDER_COLOR)) {
            String[] colors = parseBoxTokens(style.get(CssProperty.BORDER_COLOR));
            style.putIfAbsent("border-top-color", colors[0]);
            style.putIfAbsent("border-right-color", colors[1]);
            style.putIfAbsent("border-bottom-color", colors[2]);
            style.putIfAbsent("border-left-color", colors[3]);
        }

        // A-8 修正: border-width ショートハンド展開
        if (style.containsKey(CssProperty.BORDER_WIDTH)) {
            String[] edges = LengthResolver.parseBoxEdgesStr(style.get(CssProperty.BORDER_WIDTH));
            style.putIfAbsent(CssProperty.BORDER_TOP_WIDTH, edges[0]);
            style.putIfAbsent(CssProperty.BORDER_RIGHT_WIDTH, edges[1]);
            style.putIfAbsent(CssProperty.BORDER_BOTTOM_WIDTH, edges[2]);
            style.putIfAbsent(CssProperty.BORDER_LEFT_WIDTH, edges[3]);
        }

        for (String dir : DIRECTIONS) {
            String borderProp = style.get("border-" + dir);
            if (borderProp != null && !borderProp.equals("none")) {
                List<String> parts = splitTopLevelTokens(borderProp.trim());
                if (!parts.isEmpty()) {
                    style.putIfAbsent("border-" + dir + "-width", parts.getFirst());
                }
                if (parts.size() >= 3) {
                    style.putIfAbsent("border-" + dir + "-color", parts.getLast());
                }
            }
        }

        return ComputedStyle.of(style, parentFontSize, rootFontSize);
    }

    /** 括弧内の空白（rgba(r, g, b, a) 等）で分割しない 1〜4 辺展開。 */
    private static String[] parseBoxTokens(String val) {
        if (val == null || val.isBlank()) return new String[]{"transparent", "transparent", "transparent", "transparent"};
        List<String> parts = splitTopLevelTokens(val.trim());
        if (parts.isEmpty()) return new String[]{"transparent", "transparent", "transparent", "transparent"};
        return switch (parts.size()) {
            case 1 -> new String[]{parts.get(0), parts.get(0), parts.get(0), parts.get(0)};
            case 2 -> new String[]{parts.get(0), parts.get(1), parts.get(0), parts.get(1)};
            case 3 -> new String[]{parts.get(0), parts.get(1), parts.get(2), parts.get(1)};
            default -> new String[]{parts.get(0), parts.get(1), parts.get(2), parts.get(3)};
        };
    }

    private static List<String> splitTopLevelTokens(String s) {
        List<String> out = new java.util.ArrayList<>(4);
        int depth = 0;
        int start = -1;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            if (depth == 0 && Character.isWhitespace(c)) {
                if (start >= 0) {
                    out.add(s.substring(start, i));
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
        if (start >= 0) out.add(s.substring(start));
        return out;
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
        if (style.containsKey(CssProperty.GRID_ROW_GAP)) style.putIfAbsent(CssProperty.ROW_GAP, style.get(CssProperty.GRID_ROW_GAP));
        if (style.containsKey(CssProperty.GRID_COLUMN_GAP)) style.putIfAbsent(CssProperty.COLUMN_GAP, style.get(CssProperty.GRID_COLUMN_GAP));

        String shorthand = style.get(CssProperty.GAP);
        if (shorthand == null || shorthand.trim().isEmpty()) shorthand = style.get(CssProperty.GRID_GAP);
        if (shorthand == null || shorthand.trim().isEmpty()) return;

        String[] parts = WHITESPACE_SPLIT.split(shorthand.trim());
        String rowGap = parts[0];
        String columnGap = parts.length >= 2 ? parts[1] : parts[0];

        style.putIfAbsent(CssProperty.ROW_GAP, rowGap);
        style.putIfAbsent(CssProperty.COLUMN_GAP, columnGap);
    }

    /**
     * {@code flex: <grow> <shrink> <basis>} をロングハンドに展開する。
     *
     * 何が壊れていたか: この展開が無かったため {@code flex: 1} が
     * {@code flex-grow} の読み手（{@link com.nishiyu.enderframelib.core.layout.FlexLayoutEngine}）に
     * 一切届かず、ua.css の {@code th, td { flex: 1 }} が no-op で表の列幅が決まらなかった。
     *
     * LIMITATION(制約): {@code flex-basis} は展開するがレイアウトがまだ読まない。
     * 基本サイズは内容サイズと {@code width} から決まる。
     */
    private static void expandFlexShorthand(Map<String, String> style) {
        String shorthand = style.get(CssProperty.FLEX);
        if (shorthand == null || shorthand.trim().isEmpty()) return;

        String value = shorthand.trim().toLowerCase();
        String grow = null, shrink = null, basis = null;

        switch (value) {
            case "none" -> { grow = "0"; shrink = "0"; basis = "auto"; }
            case "auto" -> { grow = "1"; shrink = "1"; basis = "auto"; }
            case "initial" -> { grow = "0"; shrink = "1"; basis = "auto"; }
            default -> {
                for (String part : WHITESPACE_SPLIT.split(value)) {
                    if (UNITLESS_NUMBER.matcher(part).matches()) {
                        if (grow == null) grow = part;
                        else if (shrink == null) shrink = part;
                    } else if (basis == null) {
                        basis = part;
                    }
                }
                // 単一の数値 (flex: 1) は 1 1 0%、単一の長さ (flex: 30px) は 1 1 30px
                if (grow == null) grow = "1";
                if (shrink == null) shrink = "1";
                if (basis == null) basis = "0%";
            }
        }

        style.putIfAbsent(CssProperty.FLEX_GROW, grow);
        style.putIfAbsent(CssProperty.FLEX_SHRINK, shrink);
        style.putIfAbsent(CssProperty.FLEX_BASIS, basis);
    }

    /**
     * {@code overflow} を {@code overflow-x} / {@code overflow-y} に展開する。
     * 読み手が短縮形と軸別で分かれていて、どちらを見るかで挙動が変わっていたため、
     * 軸別のロングハンドを常に用意しておく。
     */
    private static void expandOverflowShorthand(Map<String, String> style) {
        String shorthand = style.get(CssProperty.OVERFLOW);
        if (shorthand == null || shorthand.trim().isEmpty()) return;

        String[] parts = WHITESPACE_SPLIT.split(shorthand.trim());
        style.putIfAbsent(CssProperty.OVERFLOW_X, parts[0]);
        style.putIfAbsent(CssProperty.OVERFLOW_Y, parts.length >= 2 ? parts[1] : parts[0]);
    }

    /** {@code inset} を {@code top/right/bottom/left} に展開する。 */
    private static void expandInsetShorthand(Map<String, String> style) {
        String shorthand = style.get("inset");
        if (shorthand == null || shorthand.trim().isEmpty()) return;

        String[] edges = LengthResolver.parseBoxEdgesStr(shorthand);
        String[] props = {CssProperty.TOP, CssProperty.RIGHT, CssProperty.BOTTOM, CssProperty.LEFT};
        for (int i = 0; i < props.length; i++) {
            if (edges[i] != null) style.putIfAbsent(props[i], edges[i]);
        }
    }
}
