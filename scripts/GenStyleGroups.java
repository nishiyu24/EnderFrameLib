import com.nishiyu.enderframelib.core.css.PropertyRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase M1: {@code PropertyRegistry} から、型付きスタイル群（{@code InheritedStyle} ほか 5 つ）のソースを生成する。
 *
 * <p>実行: {@code java -cp core/build/classes/java/main scripts/GenStyleGroups.java core/src/main/java/.../core/css}</p>
 *
 * <p>生成物は手で直さない。プロパティを足したら {@code PropertyRegistry} へ登録し、これを再実行する
 * （どの群にも属さないプロパティがあれば {@code StyleGroupCoverageTest} が落ちる）。</p>
 */
public final class GenStyleGroups {
    private GenStyleGroups() {}

    private static final Set<String> BOX = Set.of("display", "position", "top", "right", "bottom", "left", "float", "clear",
            "z-index", "vertical-align", "width", "height", "min-width", "min-height", "max-width", "max-height",
            "box-sizing", "aspect-ratio", "overflow-x", "overflow-y", "scrollbar-width", "text-overflow");
    private static final Set<String> TABLE = Set.of("table-layout");
    private static final Set<String> RARE_NAMES = Set.of("content", "src", "break-inside", "perspective", "object-fit",
            "appearance", "user-select", "contain", "content-visibility", "contain-intrinsic-width", "contain-intrinsic-height",
            "container-type", "container-name", "anchor-name", "position-anchor", "position-area", "position-try-fallbacks",
            "position-try-order", "counter-reset", "counter-increment", "counter-set", "transition", "animation");
    private static final Set<String> VISUAL_NAMES = Set.of("opacity", "transform", "transform-origin", "rotate", "translate",
            "scale", "filter", "mix-blend-mode", "isolation", "clip-path", "backdrop-filter", "box-shadow", "object-position");

    private static String group(PropertyRegistry.Property p) {
        String n = p.name();
        if (p.inherited()) return "InheritedStyle";
        if (n.startsWith("border-image")) return "VisualStyle";
        if (BOX.contains(n) || n.startsWith("margin-") || n.startsWith("padding-")
                || (n.startsWith("border-") && (n.endsWith("-width") || n.endsWith("-style")))) return "BoxGeomStyle";
        if (TABLE.contains(n)) return "TableStyle";
        if (RARE_NAMES.contains(n) || n.startsWith("transition-") || n.startsWith("animation-") || n.startsWith("position-")
                || (n.startsWith("column-") && !n.equals("column-gap"))) return "RareStyle";
        if (n.startsWith("flex-") || n.equals("order") || n.startsWith("justify-") || n.startsWith("align-")
                || n.endsWith("-gap") || n.equals("gap") || n.startsWith("grid-")) return "FlexGridStyle";
        if (VISUAL_NAMES.contains(n) || n.startsWith("background-") || n.startsWith("border-") || n.startsWith("outline-")
                || n.startsWith("mask-")) return "VisualStyle";
        return "RareStyle";
    }

    private enum Kind { LENGTH, NUMBER, COLOR, ENUM, TEXT, FONT_SIZE, FONT_WEIGHT, LINE_HEIGHT }

    private record Field(String prop, String name, Kind kind, String enumType) {}

    private static final Set<String> RESERVED = Set.of("float", "default", "static", "final", "int", "long", "short", "double", "char", "byte", "boolean", "class", "new", "return", "switch", "case", "import", "package", "this", "super", "void", "while", "for", "if", "else", "do", "break", "continue", "true", "false", "null", "native", "transient", "volatile", "assert", "enum", "interface", "abstract", "private", "public", "protected", "try", "catch", "finally", "throw", "throws", "extends", "implements", "instanceof", "synchronized", "strictfp", "goto", "const");

    private static String camel(String prop) {
        String p = prop.startsWith("-webkit-") ? "webkit-" + prop.substring(8) : prop;
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : p.toCharArray()) {
            if (c == '-') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        String r = sb.toString();
        return RESERVED.contains(r) ? r + "Value" : r;
    }

    private static Field fieldOf(PropertyRegistry.Property p) {
        String n = p.name();
        Kind k = switch (n) {
            case "font-size" -> Kind.FONT_SIZE;
            case "font-weight" -> Kind.FONT_WEIGHT;
            case "line-height" -> Kind.LINE_HEIGHT;
            case "border-spacing", "border-top-left-radius", "border-top-right-radius",
                 "border-bottom-right-radius", "border-bottom-left-radius" -> Kind.TEXT; // 縦横 2 値を取れる（長さ 1 個では持てない）
            default -> p.keywords() != null ? Kind.ENUM : switch (p.type()) {
                case LENGTH, LENGTH_PERCENTAGE -> Kind.LENGTH;
                case NUMBER, INTEGER -> Kind.NUMBER;
                case COLOR -> Kind.COLOR;
                default -> Kind.TEXT;
            };
        };
        String et = p.keywords() != null ? p.keywords().getCanonicalName() : null;
        return new Field(n, k == Kind.LINE_HEIGHT ? "lineHeightValue" : camel(n), k, et);
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Map<String, List<Field>> groups = new LinkedHashMap<>();
        for (String g : List.of("InheritedStyle", "BoxGeomStyle", "VisualStyle", "FlexGridStyle", "TableStyle", "RareStyle")) {
            groups.put(g, new ArrayList<>());
        }
        for (PropertyRegistry.Property p : PropertyRegistry.all()) {
            // 展開されて消えるショートハンドは持たない。展開器の無いショートハンド（transition / animation）は文字列で持つ。
            if (p.isShorthand() && p.expander() != null) continue;
            groups.get(group(p)).add(fieldOf(p));
        }
        for (var e : groups.entrySet()) {
            Files.writeString(out.resolve(e.getKey() + ".java"), render(e.getKey(), e.getValue()), StandardCharsets.UTF_8);
        }
        Files.writeString(out.resolve("StyleProperties.java"), renderProperties(groups), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("StyleDiff.java"), renderDiffClass(groups), StandardCharsets.UTF_8);
    }

    private static String renderProperties(Map<String, List<Field>> groups) {
        StringBuilder s = new StringBuilder();
        s.append("package com.nishiyu.enderframelib.core.css;\n\n");
        s.append("/** 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: {@link Style} から名前で CSS 文字列表現を取り出す。 */\n");
        s.append("public final class StyleProperties {\n    private StyleProperties() {}\n\n");
        s.append("    private static final String[] INSPECTABLE = {\n");
        int n = 0;
        StringBuilder cases = new StringBuilder();
        for (var e : groups.entrySet()) {
            String accessor = switch (e.getKey()) {
                case "InheritedStyle" -> "s.inherited";
                case "BoxGeomStyle" -> "s.box";
                case "VisualStyle" -> "s.visual";
                case "FlexGridStyle" -> "s.flexGrid";
                case "TableStyle" -> "s.table";
                default -> "s.rare";
            };
            for (Field f : e.getValue()) {
                if (n++ > 0) s.append(", ");
                if (n % 8 == 1) s.append("\n        ");
                s.append('"').append(f.prop).append('"');
                cases.append("            case \"").append(f.prop).append("\" -> ").append(formatExpr(accessor, f)).append(";\n");
            }
        }
        s.append("\n    };\n\n");
        s.append("    private static final byte[] GROUP_BITS = initGroupBits();\n");
        s.append("    static int groupBit(int id) { return id >= 0 && id < GROUP_BITS.length ? GROUP_BITS[id] : 0; }\n");
        s.append("    private static byte[] initGroupBits() {\n");
        s.append("        byte[] b = new byte[ComputedStyle.CssPropertyId.count()];\n");
        int gIdx = 0;
        for (var e : groups.entrySet()) {
            int bit = 1 << gIdx++;
            for (Field f : e.getValue()) {
                s.append("        b[").append(e.getKey()).append(".ID_").append(f.name).append("] = ").append(bit).append(";\n");
            }
        }
        s.append("        return b;\n    }\n\n");
        s.append("    public static String get(Style s, String name) {\n");
        s.append("        if (s == null || name == null) return null;\n");
        s.append("        return switch (name) {\n").append(cases);
        s.append("            default -> s.custom != null ? s.custom.get(name) : null;\n        };\n    }\n\n");
        s.append("    public static java.util.Map<String, String> toMap(Style s) {\n");
        s.append("        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();\n");
        s.append("        if (s == null) return m;\n");
        s.append("        String[] defs = DefaultsHolder.VALS;\n");
        s.append("        for (int i = 0; i < INSPECTABLE.length; i++) {\n");
        s.append("            String v = get(s, INSPECTABLE[i]);\n");
        s.append("            if (v != null && !v.equals(defs[i])) m.put(INSPECTABLE[i], v);\n        }\n");
        s.append("        if (s.custom != null) m.putAll(s.custom.asMap());\n        return m;\n    }\n\n");
        s.append("    private static final class DefaultsHolder {\n");
        s.append("        static final String[] VALS = init();\n");
        s.append("        private static String[] init() {\n");
        s.append("            String[] d = new String[INSPECTABLE.length];\n");
        s.append("            for (int i = 0; i < d.length; i++) d[i] = get(Style.DEFAULT, INSPECTABLE[i]);\n");
        s.append("            return d;\n        }\n    }\n\n");
        s.append("    private static String num(float v) { return Float.isNaN(v) ? null : v == (long) v ? Long.toString((long) v) : Float.toString(v); }\n");
        s.append("    private static String px(float v) { return num(v) + \"px\"; }\n");
        s.append("    private static String lh(byte k, float v) {\n");
        s.append("        return switch (k) {\n");
        s.append("            case ComputedStyle.LH_NORMAL -> \"normal\";\n");
        s.append("            case ComputedStyle.LH_PX -> px(v);\n");
        s.append("            case ComputedStyle.LH_PERCENT -> num(v * 100f) + \"%\";\n");
        s.append("            case ComputedStyle.LH_EM -> num(v) + \"em\";\n");
        s.append("            default -> num(v);\n        };\n    }\n");
        s.append("    private static String col(int argb) {\n");
        s.append("        int a = (argb >>> 24) & 0xFF, r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;\n");
        s.append("        if (a == 0xFF) return CssNumbers.hexRgb(r, g, b);\n");
        s.append("        return argb == 0 ? \"transparent\" : \"rgba(\" + r + \",\" + g + \",\" + b + \",\" + CssNumbers.trimmed4(a / 255.0) + \")\";\n    }\n\n");
        s.append("    public static void inheritInto(Style parent, StyleTable into) {\n");
        s.append("        if (parent == null || parent == Style.DEFAULT) return;\n");
        s.append("        InheritedStyle inh = parent.inherited;\n");
        s.append("        if (inh != InheritedStyle.DEFAULT) {\n");
        for (Field f : groups.get("InheritedStyle")) {
            s.append(inheritStmt(f));
        }
        s.append("        }\n        if (parent.custom != null) {\n            for (var e : parent.custom.asMap().entrySet()) {\n");
        s.append("                String k = e.getKey();\n                if (k.startsWith(\"--\") || StyleResolver.inheritsAsLonghand(k)) into.put(k, e.getValue());\n");
        s.append("            }\n        }\n    }\n\n");
        s.append("    /** CSSOM {@code getComputedStyle} 形式（色は {@code rgb(...)} / {@code rgba(...)}）。 */\n");
        s.append("    public static String getCssom(Style s, String name) {\n");
        s.append("        if (s == null || name == null) return null;\n");
        s.append("        return switch (name) {\n");
        s.append("            case \"color\" -> s.inherited.hasColor ? rgbCol(s.inherited.color) : null;\n");
        s.append("            case \"background-color\" -> s.visual.hasBackgroundColor ? rgbCol(s.visual.backgroundColor) : null;\n");
        s.append("            case \"border-top-color\" -> (s.visual.currentColorMask & 1) != 0 ? null : rgbCol(s.visual.borderTopColor);\n");
        s.append("            case \"border-right-color\" -> (s.visual.currentColorMask & 2) != 0 ? null : rgbCol(s.visual.borderRightColor);\n");
        s.append("            case \"border-bottom-color\" -> (s.visual.currentColorMask & 4) != 0 ? null : rgbCol(s.visual.borderBottomColor);\n");
        s.append("            case \"border-left-color\" -> (s.visual.currentColorMask & 8) != 0 ? null : rgbCol(s.visual.borderLeftColor);\n");
        s.append("            case \"outline-color\" -> (s.visual.currentColorMask & 32) != 0 ? \"invert\" : ((s.visual.currentColorMask & 16) != 0 ? null : rgbCol(s.visual.outlineColor));\n");
        s.append("            case \"column-rule-color\" -> s.rare.columnRuleColorCurrent ? null : rgbCol(s.rare.columnRuleColor);\n");
        s.append("            default -> get(s, name);\n");
        s.append("        };\n    }\n\n");
        s.append("    private static String rgbCol(int argb) {\n");
        s.append("        int a = (argb >>> 24) & 0xFF, r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;\n");
        s.append("        if (a == 0xFF) return \"rgb(\" + r + \", \" + g + \", \" + b + \")\";\n");
        s.append("        return argb == 0 ? \"rgba(0, 0, 0, 0)\" : \"rgba(\" + r + \", \" + g + \", \" + b + \", \" + CssNumbers.trimmed4(a / 255.0) + \")\";\n    }\n}\n");
        return s.toString();
    }

    private static String inheritStmt(Field f) {
        String id = "InheritedStyle.ID_" + f.name;
        return switch (f.kind) {
            case COLOR -> "            if (inh.hasColor) into.putById(" + id + ", col(inh.color));\n";
            case FONT_SIZE -> "            if (Float.floatToIntBits(inh.fontSize) != Float.floatToIntBits(InheritedStyle.DEFAULT.fontSize)) into.putById(" + id + ", px(inh.fontSize));\n";
            case FONT_WEIGHT -> "            if (inh.fontWeight != 400) into.putById(" + id + ", inh.fontWeight == 700 ? \"bold\" : Integer.toString(inh.fontWeight));\n";
            case LINE_HEIGHT -> "            if (inh.lineHeightKind != InheritedStyle.DEFAULT.lineHeightKind || Float.floatToIntBits(inh.lineHeightValue) != Float.floatToIntBits(InheritedStyle.DEFAULT.lineHeightValue)) into.putById(" + id + ", lh(inh.lineHeightKind, inh.lineHeightValue));\n";
            case LENGTH -> "            if (CssLength.isDeclared(inh." + f.name + ")) into.putById(" + id + ", CssLength.toCssString(inh." + f.name + ", inh.exprs));\n";
            case NUMBER -> "            if (!Float.isNaN(inh." + f.name + ")) into.putById(" + id + ", num(inh." + f.name + "));\n";
            case ENUM -> "            if (inh." + f.name + " != InheritedStyle.DEFAULT." + f.name + " && inh." + f.name + " != null) into.putById(" + id + ", inh." + f.name + ".cssName());\n";
            case TEXT -> "            if (inh." + f.name + " != null) into.putById(" + id + ", inh." + f.name + ");\n";
        };
    }

    private static String formatExpr(String g, Field f) {
        if (f.prop.equals("color")) return g + ".hasColor ? col(" + g + ".color) : null";
        if (f.prop.equals("background-color")) return g + ".hasBackgroundColor ? col(" + g + ".backgroundColor) : null";
        if (f.prop.equals("border-top-color")) return "(" + g + ".currentColorMask & 1) != 0 ? null : col(" + g + ".borderTopColor)";
        if (f.prop.equals("border-right-color")) return "(" + g + ".currentColorMask & 2) != 0 ? null : col(" + g + ".borderRightColor)";
        if (f.prop.equals("border-bottom-color")) return "(" + g + ".currentColorMask & 4) != 0 ? null : col(" + g + ".borderBottomColor)";
        if (f.prop.equals("border-left-color")) return "(" + g + ".currentColorMask & 8) != 0 ? null : col(" + g + ".borderLeftColor)";
        if (f.prop.equals("outline-color")) return "(" + g + ".currentColorMask & 32) != 0 ? \"invert\" : ((" + g + ".currentColorMask & 16) != 0 ? null : col(" + g + ".outlineColor))";
        if (f.prop.equals("column-rule-color")) return g + ".columnRuleColorCurrent ? null : col(" + g + ".columnRuleColor)";
        return switch (f.kind) {
            case LENGTH -> "CssLength.toCssString(" + g + "." + f.name + ", " + g + ".exprs)";
            case NUMBER -> "num(" + g + "." + f.name + ")";
            case COLOR -> "col(" + g + "." + f.name + ")";
            case ENUM -> (f.prop.equals("justify-content") || f.prop.equals("align-content"))
                    ? "(" + g + "." + f.name + "Safe ? \"safe \" : \"\") + " + g + "." + f.name + ".cssName()"
                    : g + "." + f.name + " != null ? " + g + "." + f.name + ".cssName() : null";
            case TEXT -> g + "." + f.name;
            case FONT_SIZE -> "px(" + g + ".fontSize)";
            case FONT_WEIGHT -> g + ".fontWeight == 700 ? \"bold\" : " + g + ".fontWeight == 400 ? \"normal\" : Integer.toString(" + g + ".fontWeight)";
            case LINE_HEIGHT -> "lh(" + g + ".lineHeightKind, " + g + ".lineHeightValue)";
        };
    }

    private static String type(Field f) {
        return switch (f.kind) {
            case LENGTH -> "long";
            case NUMBER, FONT_SIZE -> "float";
            case COLOR, FONT_WEIGHT -> "int";
            case ENUM -> f.enumType;
            case TEXT -> "String";
            case LINE_HEIGHT -> "float";
        };
    }

    private static String read(Field f) {
        String id = "ID_" + f.name;
        return switch (f.kind) {
            case LENGTH -> "StyleReader.length(cs, " + id + ", ex)";
            case NUMBER -> "StyleReader.number(cs, " + id + ")";
            case COLOR -> "StyleReader.color(cs, " + id + ")";
            case ENUM -> "StyleReader.keyword(cs, " + id + ", " + f.enumType + ".class)";
            case TEXT -> "StyleReader.text(cs, " + id + ")";
            case FONT_SIZE -> "cs != null ? cs.fontSizePx() : ComputedStyle.DEFAULT_FONT_SIZE";
            case FONT_WEIGHT -> "StyleReader.fontWeight(cs, " + id + ")";
            case LINE_HEIGHT -> "StyleReader.lineHeightValue(cs, " + id + ")";
        };
    }

    private static String eq(Field f) {
        return switch (f.kind) {
            case NUMBER, FONT_SIZE, LINE_HEIGHT -> "Float.floatToIntBits(" + f.name + ") == Float.floatToIntBits(o." + f.name + ")";
            case TEXT -> "java.util.Objects.equals(" + f.name + ", o." + f.name + ")";
            case ENUM -> f.name + " == o." + f.name;
            default -> f.name + " == o." + f.name;
        };
    }

    private static String hash(Field f) {
        return switch (f.kind) {
            case LENGTH -> "Long.hashCode(" + f.name + ")";
            case NUMBER, FONT_SIZE, LINE_HEIGHT -> "Float.floatToIntBits(" + f.name + ")";
            case COLOR, FONT_WEIGHT -> f.name;
            case ENUM -> "System.identityHashCode(" + f.name + ")";
            case TEXT -> "java.util.Objects.hashCode(" + f.name + ")";
        };
    }

    private static String render(String cls, List<Field> fields) {
        boolean inherited = cls.equals("InheritedStyle");
        boolean hasLength = fields.stream().anyMatch(f -> f.kind == Kind.LENGTH);
        StringBuilder s = new StringBuilder();
        s.append("package com.nishiyu.enderframelib.core.css;\n\n");
        s.append("/**\n * 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: ");
        s.append(inherited ? "子孫へ継承されるプロパティの型付き不変構造体。" : "継承されないプロパティの型付き不変構造体。");
        s.append("\n * 値は構築時に 1 回だけ解いた型付きのフィールドで、文字列を読み直さない。{@link #equals} は全フィールドの値等価。\n */\n");
        s.append("public final class ").append(cls).append(" {\n");
        for (Field f : fields) {
            s.append(inherited ? "    static final int ID_" : "    static final int ID_")
             .append(f.name).append(" = ComputedStyle.idOf(\"").append(f.prop).append("\");\n");
        }
        s.append("\n    /** 宣言が 1 つも無いときの共有インスタンス（初期値だけ）。ID_* の初期化より後に置く。 */\n");
        s.append("    public static final ").append(cls).append(" DEFAULT = new ").append(cls).append("(null);\n\n");
        if (hasLength) {
            s.append("    /** {@code calc()} 等の式の表。式を持たない値は共有の空表。 */\n    public final CssExprs exprs;\n");
        }
        for (Field f : fields) {
            if (f.kind == Kind.LINE_HEIGHT) {
                s.append("    /** {@code line-height} の種類（{@code ComputedStyle.LH_*}）。 */\n    public final byte lineHeightKind;\n");
            }
            if (f.prop.equals("justify-content") || f.prop.equals("align-content")) {
                s.append("    /** {@code ").append(f.prop).append("} に {@code safe} 修飾があるか。 */\n    public final boolean ").append(f.name).append("Safe;\n");
            }
            s.append("    /** {@code ").append(f.prop).append("}。 */\n");
            s.append("    public final ").append(type(f)).append(' ').append(f.name).append(";\n");
        }
        if (inherited) {
            s.append("    /** 文書のルートのフォントサイズ（px）。{@code rem} の基準で、ルートから子孫へ受け継がれる。 */\n");
            s.append("    public final float rootFontSizePx;\n");
            s.append("    /** {@code color} が明示宣言（または継承）されているか（{@code ::placeholder}/{@code ::first-line} 用）。 */\n");
            s.append("    public final boolean hasColor;\n");
            s.append("    /** 書体と字間の不変指定（継承群と同一寿命）。 */\n");
            s.append("    public final com.nishiyu.enderframelib.core.text.model.FontStyle fontStyleSpec;\n");
        }
        if (cls.equals("VisualStyle")) {
            s.append("    /** {@code border-*-color}(bit 0..3) と {@code outline-color}(bit 4) が {@code currentcolor}/{@code invert}/未宣言か。 */\n");
            s.append("    public final byte currentColorMask;\n");
            s.append("    /** {@code background-color} が明示宣言されたか（canvas 背景判定用）。 */\n");
            s.append("    public final boolean hasBackgroundColor;\n");
        }
        if (cls.equals("RareStyle")) {
            s.append("    /** {@code column-rule-color} が {@code currentcolor}/未宣言か。 */\n");
            s.append("    public final boolean columnRuleColorCurrent;\n");
        }
        s.append("\n    private ").append(cls).append("(StyleReader.Source cs) {\n");
        if (hasLength) s.append("        CssExprs.Builder ex = new CssExprs.Builder();\n");
        for (Field f : fields) {
            if (f.kind == Kind.LINE_HEIGHT) {
                s.append("        long lh = StyleReader.lineHeightBits(cs, ID_").append(f.name).append(");\n");
                s.append("        this.lineHeightKind = (byte) (lh >>> 32);\n");
                s.append("        this.lineHeightValue = Float.intBitsToFloat((int) lh);\n");
            } else {
                if (f.prop.equals("justify-content") || f.prop.equals("align-content")) {
                    s.append("        this.").append(f.name).append("Safe = StyleReader.isSafeKeyword(cs, ID_").append(f.name).append(");\n");
                }
                s.append("        this.").append(f.name).append(" = ").append(read(f)).append(";\n");
            }
        }
        if (inherited) {
            s.append("        this.rootFontSizePx = cs != null ? cs.rootFontSizePx() : ComputedStyle.DEFAULT_FONT_SIZE;\n");
            s.append("        this.hasColor = StyleReader.isDeclared(cs, ID_color);\n");
        }
        if (cls.equals("VisualStyle")) {
            s.append("        int cc = 0;\n");
            s.append("        if (StyleReader.isCurrentColor(cs, ID_borderTopColor, false)) cc |= 1;\n");
            s.append("        if (StyleReader.isCurrentColor(cs, ID_borderRightColor, false)) cc |= 2;\n");
            s.append("        if (StyleReader.isCurrentColor(cs, ID_borderBottomColor, false)) cc |= 4;\n");
            s.append("        if (StyleReader.isCurrentColor(cs, ID_borderLeftColor, false)) cc |= 8;\n");
            s.append("        if (StyleReader.isCurrentColor(cs, ID_outlineColor, true)) cc |= 16;\n");
            s.append("        if (StyleReader.isInvertKeyword(cs, ID_outlineColor)) cc |= 32;\n");
            s.append("        this.currentColorMask = (byte) cc;\n");
            s.append("        this.hasBackgroundColor = StyleReader.isDeclared(cs, ID_backgroundColor);\n");
        }
        if (cls.equals("RareStyle")) {
            s.append("        this.columnRuleColorCurrent = StyleReader.isCurrentColor(cs, ID_columnRuleColor, false);\n");
        }
        if (hasLength) s.append("        this.exprs = ex.build();\n");
        if (inherited) {
            s.append("        this.fontStyleSpec = new com.nishiyu.enderframelib.core.text.model.FontStyle(this.fontSize, this.fontWeight >= 600,\n");
            s.append("                this.fontStyle != com.nishiyu.enderframelib.core.css.model.CssKeywords.FontStyle.NORMAL,\n");
            s.append("                CssLength.resolve(this.letterSpacing, this.exprs, 0, this.fontSize, this.rootFontSizePx, 0, 0), this.fontWeight,\n");
            s.append("                !com.nishiyu.enderframelib.core.kernel.style.KeywordMatch.is(this.fontKerning, \"none\"));\n");
        }
        s.append("    }\n\n");
        if (cls.equals("BoxGeomStyle")) {
            s.append("    /** {@code left/right} (bit 0)・{@code top/bottom} (bit 1) だけが違うならそのビット和、それ以外の差は -1。 */\n");
            s.append("    static int positionOnlyAxes(BoxGeomStyle a, BoxGeomStyle b) { return 0; }\n");
        }
        s.append("    /** 宣言入力から、この群の値を取り出す。 */\n");
        s.append("    public static ").append(cls).append(" of(StyleReader.Source cs) {\n        return new ").append(cls).append("(cs);\n    }\n\n");
        s.append("    @Override\n    public boolean equals(Object other) {\n        if (this == other) return true;\n");
        s.append("        if (!(other instanceof ").append(cls).append(" o)) return false;\n        return ");
        List<String> terms = new ArrayList<>();
        if (hasLength) terms.add("exprs.equals(o.exprs)");
        for (Field f : fields) {
            terms.add(eq(f));
            if (f.kind == Kind.LINE_HEIGHT) terms.add("lineHeightKind == o.lineHeightKind");
            if (f.prop.equals("justify-content") || f.prop.equals("align-content")) {
                terms.add(f.name + "Safe == o." + f.name + "Safe");
            }
        }
        if (inherited) {
            terms.add("Float.floatToIntBits(rootFontSizePx) == Float.floatToIntBits(o.rootFontSizePx)");
            terms.add("hasColor == o.hasColor");
        }
        if (cls.equals("VisualStyle")) {
            terms.add("currentColorMask == o.currentColorMask");
            terms.add("hasBackgroundColor == o.hasBackgroundColor");
        }
        if (cls.equals("RareStyle")) {
            terms.add("columnRuleColorCurrent == o.columnRuleColorCurrent");
        }
        s.append(String.join("\n                && ", terms)).append(";\n    }\n\n");
        s.append("    @Override\n    public int hashCode() {\n        int h = ").append(fields.size()).append(";\n");
        for (Field f : fields) {
            s.append("        h = h * 31 + ").append(hash(f)).append(";\n");
        }
        s.append("        return h;\n    }\n}\n");
        return s.toString();
    }

    private static String renderDiffClass(Map<String, List<Field>> groups) {
        StringBuilder s = new StringBuilder();
        s.append("package com.nishiyu.enderframelib.core.css;\n\n");
        s.append("/** 生成物（{@code scripts/GenStyleGroups.java}。手で直さない）: 2 つの {@link Style} の型付き差分。 */\n");
        s.append("final class StyleDiff {\n    private StyleDiff() {}\n\n");
        s.append("    static void diff(Style a, Style b, Style.DiffVisitor v) {\n");
        s.append("        if (a == b) return;\n");
        for (var e : groups.entrySet()) {
            String cls = e.getKey();
            String field = switch (cls) {
                case "InheritedStyle" -> "inherited";
                case "BoxGeomStyle" -> "box";
                case "VisualStyle" -> "visual";
                case "FlexGridStyle" -> "flexGrid";
                case "TableStyle" -> "table";
                default -> "rare";
            };
            s.append("        if (a.").append(field).append(" != b.").append(field).append(") {\n");
            s.append("            ").append(cls).append(" x = a.").append(field).append(", y = b.").append(field).append(";\n");
            for (Field f : e.getValue()) {
                boolean po = PropertyRegistry.isPaintOnly(f.prop);
                boolean inh = PropertyRegistry.isInherited(f.prop);
                s.append("            if (").append(diffCond("x", "y", f)).append(") v.changed(\"")
                 .append(f.prop).append("\", ").append(po).append(", ").append(inh).append(");\n");
            }
            s.append("        }\n");
        }
        s.append("        if (a.custom != b.custom && (a.custom == null || !a.custom.equals(b.custom))) {\n");
        s.append("            java.util.Map<String, String> ex = a.custom != null ? a.custom.asMap() : java.util.Map.of();\n");
        s.append("            java.util.Map<String, String> ey = b.custom != null ? b.custom.asMap() : java.util.Map.of();\n");
        s.append("            ex.forEach((k, val) -> { if (!java.util.Objects.equals(val, ey.get(k))) v.changed(k, false, k.startsWith(\"--\")); });\n");
        s.append("            ey.forEach((k, val) -> { if (!ex.containsKey(k)) v.changed(k, false, k.startsWith(\"--\")); });\n");
        s.append("        }\n    }\n\n");
        s.append("    static boolean hasLayoutDiff(Style a, Style b) {\n");
        s.append("        if (a == b) return false;\n");
        for (var e : groups.entrySet()) {
            String cls = e.getKey();
            String field = switch (cls) {
                case "InheritedStyle" -> "inherited";
                case "BoxGeomStyle" -> "box";
                case "VisualStyle" -> "visual";
                case "FlexGridStyle" -> "flexGrid";
                case "TableStyle" -> "table";
                default -> "rare";
            };
            List<Field> layoutFields = e.getValue().stream().filter(f -> !PropertyRegistry.isPaintOnly(f.prop)).toList();
            if (layoutFields.isEmpty()) continue;
            s.append("        if (a.").append(field).append(" != b.").append(field).append(") {\n");
            s.append("            ").append(cls).append(" x = a.").append(field).append(", y = b.").append(field).append(";\n");
            for (Field f : layoutFields) {
                s.append("            if (").append(diffCond("x", "y", f)).append(") return true;\n");
            }
            s.append("        }\n");
        }
        s.append("        return !java.util.Objects.equals(a.custom, b.custom);\n    }\n\n");
        s.append("    static int positionOnlyAxes(Style a, Style b) {\n");
        s.append("        if (a == b) return 0;\n");
        for (var e : groups.entrySet()) {
            String cls = e.getKey();
            String field = switch (cls) {
                case "InheritedStyle" -> "inherited";
                case "BoxGeomStyle" -> "box";
                case "VisualStyle" -> "visual";
                case "FlexGridStyle" -> "flexGrid";
                case "TableStyle" -> "table";
                default -> "rare";
            };
            List<Field> nonPosLayout = e.getValue().stream()
                    .filter(f -> !PropertyRegistry.isPaintOnly(f.prop)
                            && !f.name.equals("left") && !f.name.equals("right")
                            && !f.name.equals("top") && !f.name.equals("bottom"))
                    .toList();
            if (nonPosLayout.isEmpty()) continue;
            s.append("        if (a.").append(field).append(" != b.").append(field).append(") {\n");
            s.append("            ").append(cls).append(" x = a.").append(field).append(", y = b.").append(field).append(";\n");
            for (Field f : nonPosLayout) {
                s.append("            if (").append(diffCond("x", "y", f)).append(") return -1;\n");
            }
            s.append("        }\n");
        }
        s.append("        if (!java.util.Objects.equals(a.custom, b.custom)) return -1;\n");
        s.append("        int axes = 0;\n");
        s.append("        if (a.box != b.box) {\n");
        s.append("            BoxGeomStyle x = a.box, y = b.box;\n");
        s.append("            if (").append(diffCond("x", "y", new Field("left", "left", Kind.LENGTH, null)))
         .append(" || ").append(diffCond("x", "y", new Field("right", "right", Kind.LENGTH, null))).append(") axes |= 1;\n");
        s.append("            if (").append(diffCond("x", "y", new Field("top", "top", Kind.LENGTH, null)))
         .append(" || ").append(diffCond("x", "y", new Field("bottom", "bottom", Kind.LENGTH, null))).append(") axes |= 2;\n");
        s.append("        }\n");
        s.append("        return axes;\n    }\n}\n");
        return s.toString();
    }

    private static String diffCond(String a, String b, Field f) {
        if (f.prop.equals("color")) return a + ".hasColor != " + b + ".hasColor || (" + a + ".hasColor && " + a + ".color != " + b + ".color)";
        if (f.prop.equals("background-color")) return a + ".hasBackgroundColor != " + b + ".hasBackgroundColor || (" + a + ".hasBackgroundColor && " + a + ".backgroundColor != " + b + ".backgroundColor)";
        if (f.prop.equals("border-top-color")) return colorMaskDiff(a, b, 1, f.name);
        if (f.prop.equals("border-right-color")) return colorMaskDiff(a, b, 2, f.name);
        if (f.prop.equals("border-bottom-color")) return colorMaskDiff(a, b, 4, f.name);
        if (f.prop.equals("border-left-color")) return colorMaskDiff(a, b, 8, f.name);
        if (f.prop.equals("outline-color")) return "((" + a + ".currentColorMask & 48) != (" + b + ".currentColorMask & 48)) || (("
                + a + ".currentColorMask & 16) == 0 && " + a + "." + f.name + " != " + b + "." + f.name + ")";
        if (f.prop.equals("column-rule-color")) return a + ".columnRuleColorCurrent != " + b + ".columnRuleColorCurrent || (!" + a + ".columnRuleColorCurrent && " + a + ".columnRuleColor != " + b + ".columnRuleColor)";
        return switch (f.kind) {
            case LENGTH -> "!CssLength.same(" + a + "." + f.name + ", " + a + ".exprs, " + b + "." + f.name + ", " + b + ".exprs)";
            case NUMBER, FONT_SIZE -> "Float.floatToIntBits(" + a + "." + f.name + ") != Float.floatToIntBits(" + b + "." + f.name + ")";
            case LINE_HEIGHT -> a + ".lineHeightKind != " + b + ".lineHeightKind || Float.floatToIntBits(" + a + ".lineHeightValue) != Float.floatToIntBits(" + b + ".lineHeightValue)";
            case FONT_WEIGHT -> a + ".fontWeight != " + b + ".fontWeight";
            case ENUM -> (f.prop.equals("justify-content") || f.prop.equals("align-content"))
                    ? a + "." + f.name + " != " + b + "." + f.name + " || " + a + "." + f.name + "Safe != " + b + "." + f.name + "Safe"
                    : a + "." + f.name + " != " + b + "." + f.name;
            case TEXT -> "!java.util.Objects.equals(" + a + "." + f.name + ", " + b + "." + f.name + ")";
            case COLOR -> a + "." + f.name + " != " + b + "." + f.name;
        };
    }

    private static String colorMaskDiff(String a, String b, int bit, String field) {
        return "((" + a + ".currentColorMask & " + bit + ") != (" + b + ".currentColorMask & " + bit + ")) || (("
                + a + ".currentColorMask & " + bit + ") == 0 && " + a + "." + field + " != " + b + "." + field + ")";
    }
}
