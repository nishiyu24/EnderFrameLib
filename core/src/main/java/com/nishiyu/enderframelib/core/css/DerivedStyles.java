package com.nishiyu.enderframelib.core.css;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** {@link ComputedStyle} の派生スタイル（匿名生成箱・::marker・::first-line・縦書き軸・サイズ上書き）構築ヘルパ。 */
final class DerivedStyles {

    private DerivedStyles() {}

    static final Object KEY_MARKER_OWNER = new Object(), KEY_MARKER_DECLARED = new Object();
    static final Object[] VERTICAL_AXIS_KEYS = {null, new Object(), new Object(), null, new Object()};
    static final Object[][][] ORTHO_KEYS = new Object[4][5][2];
    static {
        for (int t = 0; t < 4; t++) {
            for (int d = 0; d < 5; d++) {
                ORTHO_KEYS[t][d][0] = new Object();
                ORTHO_KEYS[t][d][1] = new Object();
            }
        }
    }

    private static final String[] FIRST_LINE_PROPS = {
            CssProperty.COLOR, CssProperty.FONT_FAMILY, CssProperty.FONT_SIZE, CssProperty.FONT_STYLE,
            CssProperty.FONT_WEIGHT, CssProperty.FONT_KERNING, CssProperty.LETTER_SPACING, CssProperty.LINE_HEIGHT,
            CssProperty.TEXT_TRANSFORM, CssProperty.TEXT_DECORATION, CssProperty.TEXT_SHADOW, CssProperty.VERTICAL_ALIGN,
            CssProperty.WEBKIT_TEXT_STROKE_WIDTH, CssProperty.WEBKIT_TEXT_STROKE_COLOR};

    private static final String[][] AXIS_EDGES = {
            {"margin-top", "margin-right", "margin-bottom", "margin-left"},
            {"padding-top", "padding-right", "padding-bottom", "padding-left"},
            {"border-top-width", "border-right-width", "border-bottom-width", "border-left-width"},
            {"border-top-style", "border-right-style", "border-bottom-style", "border-left-style"},
            {"border-top-color", "border-right-color", "border-bottom-color", "border-left-color"},
            {"top", "right", "bottom", "left"}};
    private static final String[] AXIS_CORNERS = {
            "border-top-left-radius", "border-top-right-radius", "border-bottom-right-radius", "border-bottom-left-radius"};
    private static final int[][] EDGE_PERM = {null, {1, 2, 3, 0}, {3, 2, 1, 0}, {1, 2, 3, 0}, {3, 0, 1, 2}};
    private static final int[][] CORNER_PERM = {null, {1, 2, 3, 0}, {0, 3, 2, 1}, {1, 2, 3, 0}, {3, 0, 1, 2}};

    private static final int[] ORTHO_FLEX = {
            CssPropertyIndex.FLEX_DIRECTION, CssPropertyIndex.FLEX_WRAP,
            CssPropertyIndex.JUSTIFY_CONTENT, CssPropertyIndex.ALIGN_ITEMS, CssPropertyIndex.ALIGN_CONTENT,
            CssPropertyIndex.ROW_GAP, CssPropertyIndex.COLUMN_GAP};
    private static final int[] ORTHO_GRID = {
            CssPropertyIndex.GRID_TEMPLATE_COLUMNS, CssPropertyIndex.GRID_TEMPLATE_ROWS, CssPropertyIndex.GRID_TEMPLATE_AREAS,
            CssPropertyIndex.GRID_AUTO_FLOW, CssPropertyIndex.GRID_AUTO_COLUMNS, CssPropertyIndex.GRID_AUTO_ROWS,
            CssPropertyIndex.JUSTIFY_ITEMS, CssPropertyIndex.JUSTIFY_CONTENT,
            CssPropertyIndex.ALIGN_ITEMS, CssPropertyIndex.ALIGN_CONTENT,
            CssPropertyIndex.ROW_GAP, CssPropertyIndex.COLUMN_GAP};
    private static final int[] ORTHO_TABLE = {
            CssPropertyIndex.BORDER_COLLAPSE, CssPropertyIndex.BORDER_SPACING,
            CssPropertyIndex.TABLE_LAYOUT, CssPropertyIndex.CAPTION_SIDE, CssPropertyIndex.EMPTY_CELLS};
    private static final int[] ORTHO_BOX_SIZES = {
            CssPropertyIndex.WIDTH, CssPropertyIndex.MIN_WIDTH, CssPropertyIndex.MAX_WIDTH,
            CssPropertyIndex.HEIGHT, CssPropertyIndex.MIN_HEIGHT, CssPropertyIndex.MAX_HEIGHT,
            CssPropertyIndex.BOX_SIZING, CssPropertyIndex.ASPECT_RATIO};

    static ComputedStyle markerFromOwner(ComputedStyle parent) {
        StyleTable tbl = new StyleTable();
        StyleProperties.inheritInto(parent.typed(), tbl);
        tbl.putById(CssPropertyIndex.DISPLAY, "inline-block");
        tbl.putById(CssPropertyIndex.WHITE_SPACE, "pre");
        tbl.putById(CssPropertyIndex.TEXT_TRANSFORM, "none");
        return ComputedStyle.ofOwned(tbl, parent.fontSizePx(), parent.rootFontSizePx(), parent.typed(), false);
    }

    static ComputedStyle markerFromDeclared(ComputedStyle declared) {
        Map<String, String> m = declared.typed().toCssMap();
        m.remove(CssProperty.CONTENT);
        m.put(CssProperty.DISPLAY, "inline-block");
        m.put(CssProperty.WHITE_SPACE, "pre");
        m.put(CssProperty.TEXT_TRANSFORM, "none");
        return new ComputedStyle(m, declared.fontSizePx(), declared.rootFontSizePx());
    }

    static ComputedStyle orthogonalWrapper(ComputedStyle root, int transformKind, String displayValue, boolean copyBoxSizes) {
        StyleTable tbl = new StyleTable();
        StyleProperties.inheritInto(root.typed(), tbl);
        tbl.putById(CssPropertyIndex.DISPLAY, displayValue);
        if (transformKind != 0) {
            tbl.putById(CssPropertyIndex.TRANSFORM, transformKind == 3 ? "matrix(0,1,1,0,0,0)"
                    : transformKind == 2 ? "rotate(-90deg)" : "rotate(90deg)");
            tbl.putById(CssPropertyIndex.TRANSFORM_ORIGIN, "0px 0px");
            int[] grp = switch (displayValue) {
                case "flex" -> ORTHO_FLEX;
                case "grid" -> ORTHO_GRID;
                case "table" -> ORTHO_TABLE;
                default -> null;
            };
            if (grp != null) copyProps(root.typed(), tbl, grp);
            if (copyBoxSizes) copyProps(root.typed(), tbl, ORTHO_BOX_SIZES);
        }
        return ComputedStyle.ofOwned(tbl, root.fontSizePx(), root.rootFontSizePx(), root.typed(), false);
    }

    private static void copyProps(Style src, StyleTable dst, int[] ids) {
        for (int id : ids) {
            String v = src.cssProperty(ComputedStyle.CssPropertyId.name(id));
            if (v != null) dst.putById(id, v);
        }
    }

    static ComputedStyle firstLineChild(ComputedStyle base, ComputedStyle ownerStyle, ComputedStyle firstLineStyle) {
        Map<String, String> m = null;
        boolean fontChanged = false;
        Style baseTyped = base.typed(), flTyped = firstLineStyle.typed(), parTyped = ownerStyle.typed();
        for (String p : FIRST_LINE_PROPS) {
            String fl = flTyped.cssProperty(p);
            String own = baseTyped.cssProperty(p);
            String par = parTyped.cssProperty(p);
            if (!Objects.equals(fl, own) && Objects.equals(own, par)) {
                if (m == null) m = baseTyped.toCssMap();
                if (fl == null) m.remove(p); else m.put(p, fl);
                if (CssProperty.FONT_SIZE.equals(p)) fontChanged = true;
            }
        }
        if (m == null) return base;
        float fs = fontChanged ? firstLineStyle.fontSizePx() : base.fontSizePx();
        return new ComputedStyle(m, fs, base.rootFontSizePx());
    }

    static ComputedStyle verticalAxisStyle(ComputedStyle base, int norm) {
        Map<String, String> src = base.typed().toCssMap();
        boolean any = src.containsKey(CssProperty.OVERFLOW_X) || src.containsKey(CssProperty.OVERFLOW_Y)
                || src.containsKey(CssProperty.ASPECT_RATIO);
        for (String p : new String[]{"width", "height", "min-width", "min-height", "max-width", "max-height"}) any |= src.containsKey(p);
        for (String[] g : AXIS_EDGES) for (String p : g) any |= src.containsKey(p);
        for (String p : AXIS_CORNERS) any |= src.containsKey(p);
        if (!any) return base;
        Map<String, String> out = new LinkedHashMap<>(src);
        for (String[] pair : new String[][]{{"width", "height"}, {"min-width", "min-height"}, {"max-width", "max-height"}}) {
            swapMap(out, src, pair[0], pair[1]);
        }
        int[] ep = EDGE_PERM[norm], cp = CORNER_PERM[norm];
        for (String[] g : AXIS_EDGES) for (int i = 0; i < 4; i++) putOrRemove(out, g[i], src.get(g[ep[i]]));
        if (src.containsKey(CssProperty.OVERFLOW_X) || src.containsKey(CssProperty.OVERFLOW_Y)) {
            putOrRemove(out, CssProperty.OVERFLOW_X, src.getOrDefault(CssProperty.OVERFLOW_Y, base.overflowY().cssName()));
            putOrRemove(out, CssProperty.OVERFLOW_Y, src.getOrDefault(CssProperty.OVERFLOW_X, base.overflowX().cssName()));
        }
        for (int i = 0; i < 4; i++) {
            String cv = src.get(AXIS_CORNERS[cp[i]]);
            if (cv != null) out.put(AXIS_CORNERS[i], swapRadiusPair(cv));
            else out.remove(AXIS_CORNERS[i]);
        }
        String ar = src.get(CssProperty.ASPECT_RATIO);
        if (ar != null) out.put(CssProperty.ASPECT_RATIO, invertAspectRatio(ar));
        return new ComputedStyle(out, base.fontSizePx(), base.rootFontSizePx());
    }

    static ComputedStyle withSizingOverrides(ComputedStyle base, String w, String h, String minW, String maxW, String minH, String maxH) {
        Map<String, String> m = base.typed().toCssMap();
        if (w != null) m.put(CssProperty.WIDTH, w); if (h != null) m.put(CssProperty.HEIGHT, h);
        if (minW != null) m.put(CssProperty.MIN_WIDTH, minW); if (maxW != null) m.put(CssProperty.MAX_WIDTH, maxW);
        if (minH != null) m.put(CssProperty.MIN_HEIGHT, minH); if (maxH != null) m.put(CssProperty.MAX_HEIGHT, maxH);
        return new ComputedStyle(m, base.fontSizePx(), base.rootFontSizePx());
    }

    private static void swapMap(Map<String, String> out, Map<String, String> src, String a, String b) {
        putOrRemove(out, a, src.get(b)); putOrRemove(out, b, src.get(a));
    }
    private static void putOrRemove(Map<String, String> out, String k, String v) {
        if (v == null) out.remove(k); else out.put(k, v);
    }
    private static String swapRadiusPair(String s) {
        String t = s.trim();
        int sp = t.indexOf(' ');
        if (sp < 0 || t.indexOf('(', 0) >= 0) return s;
        String a = t.substring(0, sp).trim(), b = t.substring(sp + 1).trim();
        return (a.isEmpty() || b.isEmpty() || b.indexOf(' ') >= 0) ? s : b + " " + a;
    }
    private static String invertAspectRatio(String s) {
        int slash = s.indexOf('/');
        if (slash > 0) return s.substring(slash + 1).trim() + " / " + s.substring(0, slash).trim();
        try {
            double num = Double.parseDouble(s.trim());
            return num > 0 ? "1 / " + s.trim() : s;
        } catch (NumberFormatException e) { return s; }
    }
}
