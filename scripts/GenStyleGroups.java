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
        if (BOX.contains(n) || n.startsWith("margin-") || n.startsWith("padding-")
                || (n.startsWith("border-") && (n.endsWith("-width") || n.endsWith("-style")))) return "BoxGeomStyle";
        if (TABLE.contains(n)) return "TableStyle";
        if (RARE_NAMES.contains(n) || n.startsWith("transition-") || n.startsWith("animation-") || n.startsWith("position-")
                || n.startsWith("column-")) return "RareStyle";
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
            s.append("    private static final int ID_").append(f.name).append(" = ComputedStyle.idOf(\"").append(f.prop).append("\");\n");
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
            s.append("    /** {@code ").append(f.prop).append("}。 */\n");
            s.append("    public final ").append(type(f)).append(' ').append(f.name).append(";\n");
        }
        if (inherited) {
            s.append("    /** 文書のルートのフォントサイズ（px）。{@code rem} の基準で、ルートから子孫へ受け継がれる。 */\n");
            s.append("    public final float rootFontSizePx;\n");
        }
        s.append("\n    private ").append(cls).append("(ComputedStyle cs) {\n");
        if (hasLength) s.append("        CssExprs.Builder ex = new CssExprs.Builder();\n");
        for (Field f : fields) {
            if (f.kind == Kind.LINE_HEIGHT) {
                s.append("        long lh = StyleReader.lineHeightBits(cs, ID_").append(f.name).append(");\n");
                s.append("        this.lineHeightKind = (byte) (lh >>> 32);\n");
                s.append("        this.lineHeightValue = Float.intBitsToFloat((int) lh);\n");
            } else {
                s.append("        this.").append(f.name).append(" = ").append(read(f)).append(";\n");
            }
        }
        if (inherited) s.append("        this.rootFontSizePx = cs != null ? cs.rootFontSizePx() : ComputedStyle.DEFAULT_FONT_SIZE;\n");
        if (hasLength) s.append("        this.exprs = ex.build();\n");
        s.append("    }\n\n");
        s.append("    /** 算出スタイルから、この群の値を取り出す。 */\n");
        s.append("    public static ").append(cls).append(" of(ComputedStyle cs) {\n        return new ").append(cls).append("(cs);\n    }\n\n");
        s.append("    @Override\n    public boolean equals(Object other) {\n        if (this == other) return true;\n");
        s.append("        if (!(other instanceof ").append(cls).append(" o)) return false;\n        return ");
        List<String> terms = new ArrayList<>();
        if (hasLength) terms.add("exprs.equals(o.exprs)");
        for (Field f : fields) {
            terms.add(eq(f));
            if (f.kind == Kind.LINE_HEIGHT) terms.add("lineHeightKind == o.lineHeightKind");
        }
        if (inherited) terms.add("Float.floatToIntBits(rootFontSizePx) == Float.floatToIntBits(o.rootFontSizePx)");
        s.append(String.join("\n                && ", terms)).append(";\n    }\n\n");
        s.append("    @Override\n    public int hashCode() {\n        int h = ").append(fields.size()).append(";\n");
        for (Field f : fields) {
            s.append("        h = h * 31 + ").append(hash(f)).append(";\n");
        }
        s.append("        return h;\n    }\n}\n");
        return s.toString();
    }
}
