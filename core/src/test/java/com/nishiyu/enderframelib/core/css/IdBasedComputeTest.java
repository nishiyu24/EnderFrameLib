package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.LengthResolver.Metrics;
import com.nishiyu.enderframelib.core.css.ValueComputer.CssWideKeyword;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Milestone 2: {@code ValueComputer.compute} と {@code PropertyRegistry.expandShorthands} を、名前の {@code Map} 走査から
 * 添字での配列走査（{@link StyleTable}）へ移しても、結果が旧実装（名前で引く版。ここに基準として写してある）と同じ。
 */
class IdBasedComputeTest {

    // ==================== 基準: 名前で引く旧実装（HEAD の ValueComputer / PropertyRegistry をそのまま写したもの） ====================

    private static final java.util.Set<String> OPAQUE = java.util.Set.of(
            CssProperty.FONT_FAMILY, CssProperty.CONTENT, CssProperty.SRC,
            CssProperty.ANIMATION_NAME, CssProperty.TRANSITION_PROPERTY,
            "grid-template-areas", "list-style-image", "background-image", "font");
    private static final String[] SIDES = {"top", "right", "bottom", "left"};
    private static final Map<String, String> LINE_WIDTH_KEYWORDS = Map.of("thin", "1px", "medium", "3px", "thick", "5px");

    private static String trimmed(String s) {
        return s == null ? null : s.trim();
    }

    private static void referenceCompute(Map<String, String> style, ValueComputer.Context ctx) {
        refWide(style, ctx);
        String fontSize = style.get(CssProperty.FONT_SIZE);
        float own = ctx.parentFontSize();
        if (fontSize != null) {
            own = ValueComputer.resolveFontSize(fontSize, ctx);
            style.put(CssProperty.FONT_SIZE, ValueComputer.px(own));
        }
        if ("currentcolor".equalsIgnoreCase(trimmed(style.get(CssProperty.COLOR)))) refInheritedOrInitial(style, CssProperty.COLOR, ctx);
        String color = style.get(CssProperty.COLOR);
        if (color == null) color = ctx.parent().get(CssProperty.COLOR);
        if (color == null) color = PropertyRegistry.initialValue(CssProperty.COLOR);

        String declared = trimmed(style.get(CssProperty.LINE_HEIGHT));
        if (declared == null) declared = trimmed(ctx.parent().get(CssProperty.LINE_HEIGHT));
        float lineHeight = ValueComputer.lineHeightPx(declared, own, ctx.rootFontSize(), ctx.viewportW(), ctx.viewportH());
        Metrics metrics = new Metrics(0, ctx.viewportW(), ctx.viewportH(), own, ctx.rootFontSize(), lineHeight,
                ctx.rootLineHeightPx(), ctx.containerW(), ctx.containerH(), GlyphUnits.of(style, ctx.parent(), own));
        for (String key : new ArrayList<>(style.keySet())) {
            if (key.startsWith("--") || CssProperty.FONT_SIZE.equals(key)) continue;
            String value = style.get(key);
            if (value == null) continue;
            if (CssProperty.LINE_HEIGHT.equals(key) && value.trim().endsWith("%")) {
                try {
                    float pct = Float.parseFloat(value.trim().substring(0, value.trim().length() - 1).trim());
                    value = ValueComputer.px(own * pct / 100f);
                } catch (NumberFormatException ignored) {
                }
            }
            if (!OPAQUE.contains(key)) value = ValueComputer.absolutizeUnits(value, metrics);
            if (!CssProperty.COLOR.equals(key)) value = ValueComputer.replaceCurrentColor(value, color);
            style.put(key, value);
        }
        for (String side : SIDES) refLineWidth(style, "border-" + side + "-style", "border-" + side + "-width");
        refLineWidth(style, "outline-style", "outline-width");
        refLineWidth(style, "column-rule-style", "column-rule-width");
        for (String key : new String[]{CssProperty.HEIGHT, CssProperty.MIN_HEIGHT, CssProperty.MAX_HEIGHT}) {
            String value = style.get(key);
            if (value == null) continue;
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (v.equals("min-content") || v.equals("max-content") || v.equals("fit-content") || v.startsWith("fit-content(")) {
                style.put(key, key.equals(CssProperty.MAX_HEIGHT) ? "none" : "auto");
            }
        }
    }

    private static void refLineWidth(Map<String, String> style, String styleKey, String widthKey) {
        String lineStyle = trimmed(style.get(styleKey));
        if (lineStyle == null || "none".equalsIgnoreCase(lineStyle) || "hidden".equalsIgnoreCase(lineStyle)) {
            if (style.containsKey(widthKey)) style.put(widthKey, "0");
            return;
        }
        String width = trimmed(style.get(widthKey));
        if (width == null) {
            style.put(widthKey, LINE_WIDTH_KEYWORDS.get("medium"));
        } else {
            String keyword = LINE_WIDTH_KEYWORDS.get(width.toLowerCase(Locale.ROOT));
            if (keyword != null) style.put(widthKey, keyword);
        }
    }

    private static void refWide(Map<String, String> style, ValueComputer.Context ctx) {
        for (String key : new ArrayList<>(style.keySet())) {
            CssWideKeyword kw = oldWideOf(style.get(key));
            if (kw == null) continue;
            switch (kw) {
                case INHERIT -> refInheritedOrInitial(style, key, ctx);
                case INITIAL -> refInitial(style, key);
                case UNSET, REVERT, REVERT_LAYER -> {
                    if (PropertyRegistry.isInherited(key) || key.startsWith("--")) refInheritedOrInitial(style, key, ctx);
                    else refInitial(style, key);
                }
            }
        }
    }

    private static CssWideKeyword oldWideOf(String value) {
        if (value == null) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "inherit" -> CssWideKeyword.INHERIT;
            case "initial" -> CssWideKeyword.INITIAL;
            case "unset" -> CssWideKeyword.UNSET;
            case "revert" -> CssWideKeyword.REVERT;
            case "revert-layer" -> CssWideKeyword.REVERT_LAYER;
            default -> null;
        };
    }

    private static void refInheritedOrInitial(Map<String, String> style, String key, ValueComputer.Context ctx) {
        String parentValue = ctx.parent().get(key);
        if (parentValue != null) style.put(key, parentValue);
        else refInitial(style, key);
    }

    private static void refInitial(Map<String, String> style, String key) {
        String initial = PropertyRegistry.initialValue(key);
        if (initial != null) style.put(key, initial);
        else style.remove(key);
    }

    private static void referenceExpand(Map<String, String> style) {
        for (String name : PropertyRegistry.expansionOrder()) {
            String value = style.get(name);
            if (value == null || value.isBlank()) continue;
            PropertyRegistry.Property p = PropertyRegistry.get(name);
            if (oldWideOf(value) != null) {
                for (String l : p.longhands()) style.putIfAbsent(l, value.trim());
                continue;
            }
            Map<String, String> parts = new LinkedHashMap<>(p.expander().apply(value.trim()));
            if (parts.isEmpty()) {
                for (String l : p.longhands()) style.putIfAbsent(l, "unset");
                continue;
            }
            for (Map.Entry<String, String> e : parts.entrySet()) {
                if (e.getValue() != null) style.putIfAbsent(e.getKey(), e.getValue());
            }
            for (String l : p.longhands()) {
                PropertyRegistry.Property longhand = PropertyRegistry.get(l);
                if (parts.get(l) == null && !longhand.isShorthand() && longhand.initial() != null) style.putIfAbsent(l, longhand.initial());
            }
        }
    }

    // ==================== 乱数入力 ====================

    private static final String[] VALUES = {"inherit", "INHERIT", " initial ", "unset", "revert", "revert-layer", "10px", "2em", "1.5rem",
            "50%", "10vw", "3vh", "0", "auto", "none", "red", "#fff", "currentcolor", "CurrentColor", "1px solid currentcolor",
            "rgb(1, 2, 3)", "calc(1em + 2px)", "min-content", "max-content", "fit-content", "fit-content(20px)", "Fit-Content(10px)",
            "thin", "medium", " THICK ", "solid", "dashed", "hidden", "bold", "serif", "12pt", "1in", "5cqw", "garbage", "", " ",
            "1px 2px", "1px 2px 3px", "1px 2px 3px 4px", "1px 2px 3px 4px 5px", "url(a.png)", "\"text\"", "normal", "1.4", "150%"};

    private static final String[] PROPS = {"font-size", "color", "line-height", "width", "height", "min-height", "max-height", "margin-top",
            "padding-left", "border-top-width", "border-top-style", "border-right-width", "border-right-style", "border-bottom-style",
            "border-left-width", "outline-style", "outline-width", "column-rule-style", "column-rule-width", "font-family", "content",
            "background-image", "background-color", "display", "opacity", "text-align", "visibility", "cursor", "white-space",
            "--a", "--b", "--c", "made-up-prop", "perspective", "rotate"};

    private static Map<String, String> randomStyle(Random rnd) {
        Map<String, String> m = new LinkedHashMap<>();
        int n = rnd.nextInt(18);
        for (int i = 0; i < n; i++) m.put(PROPS[rnd.nextInt(PROPS.length)], VALUES[rnd.nextInt(VALUES.length)]);
        return m;
    }

    @Test
    void computeMatchesTheNameBasedReference() {
        Random rnd = new Random(20261116);
        for (int round = 0; round < 6000; round++) {
            ComputedStyle parent = new ComputedStyle(randomStyle(rnd), 16f, 16f);
            Map<String, String> src = randomStyle(rnd);
            float pfs = 8 + rnd.nextInt(20);
            ValueComputer.Context ctx = rnd.nextBoolean()
                    ? new ValueComputer.Context(parent.asMap(), pfs, 16f, 800, 600, 400f, 300f, 20f)
                    : new ValueComputer.Context(Map.of(), pfs, 16f, 800, 600);

            Map<String, String> expected = new LinkedHashMap<>(src);
            referenceCompute(expected, ctx);

            StyleTable table = StyleTable.copyOf(src);
            ValueComputer.compute(table, ctx);
            assertEquals(expected, new LinkedHashMap<>(table), "round " + round + " src=" + src);
            assertEquals(expected.size(), table.size(), "size " + round);

            Map<String, String> plain = new LinkedHashMap<>(src);
            ValueComputer.compute(plain, ctx);
            assertEquals(expected, plain, "素のマップ round " + round);
        }
    }

    @Test
    void expandShorthandsMatchesTheNameBasedReference() {
        List<String> shorthands = new ArrayList<>(PropertyRegistry.expansionOrder());
        List<String> longhands = new ArrayList<>();
        for (PropertyRegistry.Property p : PropertyRegistry.all()) if (!p.isShorthand()) longhands.add(p.name());
        Random rnd = new Random(20261117);
        for (int round = 0; round < 8000; round++) {
            Map<String, String> src = new LinkedHashMap<>();
            int n = 1 + rnd.nextInt(5);
            for (int i = 0; i < n; i++) src.put(shorthands.get(rnd.nextInt(shorthands.size())), VALUES[rnd.nextInt(VALUES.length)]);
            int m = rnd.nextInt(4);
            for (int i = 0; i < m; i++) src.put(longhands.get(rnd.nextInt(longhands.size())), VALUES[rnd.nextInt(VALUES.length)]);
            if (rnd.nextInt(4) == 0) src.put("--x", "1");

            Map<String, String> expected = new LinkedHashMap<>(src);
            referenceExpand(expected);

            StyleTable table = StyleTable.copyOf(src);
            PropertyRegistry.expandShorthands(table);
            assertEquals(expected, new LinkedHashMap<>(table), "round " + round + " src=" + src);

            Map<String, String> plain = new LinkedHashMap<>(src);
            PropertyRegistry.expandShorthands(plain);
            assertEquals(expected, plain, "素のマップ round " + round);
        }
    }

    @Test
    void expansionIsRememberedPerValue() {
        // 同じ宣言を何度展開しても同じ結果（記憶した展開結果が汚れない）
        for (int i = 0; i < 3; i++) {
            StyleTable t = new StyleTable();
            t.put("border", "1px solid red");
            t.put("border-top-width", "9px");
            PropertyRegistry.expandShorthands(t);
            assertEquals("9px", t.get("border-top-width"), "既にあるロングハンドが優先");
            assertEquals("1px", t.get("border-left-width"));
            assertEquals("solid", t.get("border-left-style"));
            assertEquals("red", t.get("border-left-color"));
        }
    }
}
