import com.nishiyu.enderframelib.core.css.PropertyRegistry;
import com.nishiyu.enderframelib.core.css.PropertyRegistry.Property;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code PropertyRegistry} から、型付きスタイルの公開 API（{@code api/ui/style} の {@code Keyword} と {@code *Properties} の群）を生成する。
 *
 * <p>実行: {@code java -cp core/build/classes/java/main scripts/GenTypedCss.java core/src/main/java/com/nishiyu/enderframelib/api/ui/style}</p>
 *
 * <p>生成物は手で直さない。プロパティを足したら {@code PropertyRegistry} へ登録してこれを再実行する
 * （関数の無いプロパティや、エンジンと食い違う列挙があれば {@code TypedCssCoverageTest} が落ちる）。</p>
 *
 * <p>値の型は登録表の型から決める。登録表がキーワードの列挙を持たないキーワード型（{@code display} など）は {@link #KEYWORDS} で補い、
 * ショートハンドなど登録表だけでは形が決まらないものは {@link #special} に書く。型の決まらないものは {@code Raw}（逃げ道）を受ける。</p>
 */
public final class GenTypedCss {
    private GenTypedCss() {}

    private static final String PKG = "com.nishiyu.enderframelib.api.ui.style";
    private static final String HEADER = "// 生成物（scripts/GenTypedCss.java が PropertyRegistry から作る）。手で直さない。\n";

    /** 登録表がキーワードの列挙を持たないプロパティの、キーワードの一覧（列挙名 → CSS の書き方）。 */
    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();

    /** 列挙名 → それを使うプロパティ（KEYWORDS 由来でないもの）。 */
    private static final Map<String, String> ENUM_FOR_PROPERTY = new LinkedHashMap<>();

    static {
        kw("display", "Display", "none", "block", "inline", "inline-block", "flex", "inline-flex", "grid", "inline-grid",
                "flow-root", "contents", "list-item", "table", "inline-table", "table-row", "table-cell", "table-row-group",
                "table-header-group", "table-footer-group", "table-column", "table-column-group", "table-caption");
        kw("column-fill", "ColumnFill", "auto", "balance");
        kw("column-span", "ColumnSpan", "none", "all");
        kw("grid-auto-flow", "GridAutoFlow", "row", "column", "dense", "row dense", "column dense");
        kw("empty-cells", "EmptyCells", "show", "hide");
        kw("font-variant", "FontVariant", "normal", "small-caps");
        kw("font-kerning", "FontKerning", "auto", "normal", "none");
        kw("text-align-last", "TextAlignLast", "auto", "start", "end", "left", "right", "center", "justify");
        kw("text-justify", "TextJustify", "auto", "none", "inter-word", "inter-character");
        kw("text-wrap-style", "TextWrapStyle", "auto", "balance", "pretty", "stable");
        kw("text-wrap", "TextWrap", "wrap", "nowrap", "balance", "pretty", "stable");
        kw("white-space", "WhiteSpace", "normal", "nowrap", "pre", "pre-wrap", "pre-line", "break-spaces");
        kw("word-break", "WordBreak", "normal", "break-all", "keep-all", "break-word");
        kw("direction", "Direction", "ltr", "rtl");
        kw("appearance", "Appearance", "none", "auto");
        kw("position-try-order", "PositionTryOrder", "normal", "most-width", "most-height", "most-block-size", "most-inline-size");
        kw("object-fit", "ObjectFit", "fill", "contain", "cover", "none", "scale-down");
        kw("writing-mode", "WritingMode", "horizontal-tb", "vertical-rl", "vertical-lr");
        kw("text-orientation", "TextOrientation", "mixed", "upright", "sideways");
        kw("break-inside", "BreakInside", "auto", "avoid", "avoid-page", "avoid-column");
        kw("mix-blend-mode", "MixBlendMode", "normal", "multiply", "screen", "overlay", "darken", "lighten", "color-dodge",
                "color-burn", "hard-light", "soft-light", "difference", "exclusion", "hue", "saturation", "color", "luminosity",
                "plus-lighter");
        kw("user-select", "UserSelect", "auto", "none", "text", "all", "contain");
        kw("cursor", "Cursor", "auto", "default", "none", "pointer", "text", "move", "wait", "progress", "help", "crosshair",
                "not-allowed", "grab", "grabbing", "col-resize", "row-resize", "ew-resize", "ns-resize", "nwse-resize",
                "nesw-resize", "context-menu", "cell", "copy", "alias", "no-drop", "zoom-in", "zoom-out", "vertical-text",
                "all-scroll");
        kw("font-weight", "FontWeight", "normal", "bold", "bolder", "lighter");
        kw("list-style-type", "ListStyleType", "none", "disc", "circle", "square", "decimal", "decimal-leading-zero",
                "lower-roman", "upper-roman", "lower-alpha", "upper-alpha", "lower-latin", "upper-latin", "lower-greek");
        kw("font-family", "FontFamily", "serif", "sans-serif", "monospace", "cursive", "fantasy", "system-ui");
    }

    private static void kw(String property, String enumName, String... values) {
        if (KEYWORDS.put(enumName, List.of(values)) != null) throw new IllegalStateException("列挙名の重複: " + enumName);
        ENUM_FOR_PROPERTY.put(property, enumName);
    }

    public static void main(String[] args) throws IOException {
        Path out = Path.of(args[0]);
        Map<String, List<String>> enums = new LinkedHashMap<>();
        enums.put("None", List.of("none"));
        Map<String, List<String>> methods = new LinkedHashMap<>();
        Map<String, List<String>> raw = new LinkedHashMap<>();
        for (String g : GROUPS) {
            methods.put(g, new ArrayList<>());
            raw.put(g, new ArrayList<>());
        }
        for (Property p : PropertyRegistry.all()) {
            if (p.name().startsWith("-")) continue; // ベンダー接頭辞は公開しない
            if (p.keywords() != null) {
                String name = p.keywords().getSimpleName();
                List<String> values = new ArrayList<>();
                for (Object c : p.keywords().getEnumConstants()) {
                    values.add(((Enum<?>) c).name().toLowerCase(Locale.ROOT).replace('_', '-'));
                }
                List<String> prev = enums.putIfAbsent(name, values);
                if (prev != null && !prev.equals(values)) throw new IllegalStateException("列挙名の衝突: " + name);
                ENUM_FOR_PROPERTY.put(p.name(), name);
            }
        }
        for (var e : KEYWORDS.entrySet()) {
            if (enums.putIfAbsent(e.getKey(), e.getValue()) != null) throw new IllegalStateException("列挙名の衝突: " + e.getKey());
        }
        int total = 0, rawCount = 0;
        for (Property p : PropertyRegistry.all()) {
            if (p.name().startsWith("-")) continue;
            String g = group(p.name());
            List<String> m = special(p);
            if (m == null) m = byType(p);
            if (m == null) {
                m = List.of(sig(p, "Raw v", "v"));
                raw.get(g).add(p.name());
                rawCount++;
            }
            methods.get(g).add("    // " + p.name() + (p.isShorthand() ? "（ショートハンド）" : ""));
            methods.get(g).addAll(m);
            total++;
        }
        Files.writeString(out.resolve("Keyword.java"), renderKeywords(enums), StandardCharsets.UTF_8);
        Files.deleteIfExists(out.resolve("CssProperties.java")); // 旧: 1 クラスにまとめていた頃の生成物
        String parent = null;
        for (String g : GROUPS) {
            long count = methods.get(g).stream().filter(l -> !l.startsWith("    //")).count() + 2; // + 補助の d() 2 つ
            if (count > 100) throw new IllegalStateException(g + " のメソッドが " + count + " 個（クラスの形のラチェットは 100 まで。群を分ける）");
            Files.writeString(out.resolve(g + ".java"), renderProperties(g, parent, methods.get(g), raw.get(g)), StandardCharsets.UTF_8);
            parent = g;
        }
        System.out.println("enums=" + enums.size() + " properties=" + total + " raw=" + rawCount + " last=" + parent);
    }

    /** プロパティ関数の群（継承の鎖の順。{@code Css} が最後の群を継承する）。1 群のメソッドは 100 まで。 */
    static final List<String> GROUPS = List.of("BoxProperties", "BorderProperties", "VisualProperties", "FlexGridProperties",
            "TextProperties", "OtherProperties");

    static String group(String n) {
        if (n.startsWith("flex") || n.startsWith("grid") || n.startsWith("align-") || n.startsWith("justify-")
                || n.startsWith("place-") || n.equals("gap") || n.endsWith("-gap") || n.equals("order")
                || n.equals("columns") || n.startsWith("column-")) return "FlexGridProperties";
        if (n.startsWith("font") || n.startsWith("text-") || n.startsWith("list-style") || n.startsWith("word-")
                || n.equals("color") || n.equals("line-height") || n.equals("letter-spacing") || n.equals("white-space")
                || n.equals("overflow-wrap") || n.equals("direction") || n.equals("writing-mode") || n.equals("tab-size")
                || n.equals("text-decoration") || n.equals("text-wrap") || n.equals("vertical-align")) return "TextProperties";
        if (n.startsWith("border") || n.startsWith("outline")) return "BorderProperties";
        if (n.startsWith("background") || n.startsWith("mask")
                || n.startsWith("transform") || n.equals("box-shadow") || n.equals("opacity") || n.equals("rotate")
                || n.equals("translate") || n.equals("scale") || n.equals("perspective") || n.equals("filter")
                || n.equals("backdrop-filter") || n.equals("clip-path") || n.equals("mix-blend-mode") || n.equals("isolation")
                || n.startsWith("object-") || n.equals("accent-color") || n.equals("visibility")) return "VisualProperties";
        if (n.startsWith("margin") || n.startsWith("padding") || n.startsWith("overflow")
                || n.equals("display") || n.equals("position") || n.equals("top") || n.equals("right") || n.equals("bottom")
                || n.equals("left") || n.equals("inset") || n.equals("float") || n.equals("clear") || n.equals("z-index")
                || n.endsWith("width") && !n.contains("-rule") || n.endsWith("height") || n.equals("box-sizing")
                || n.equals("aspect-ratio") || n.equals("scrollbar-width") || n.equals("table-layout")
                || n.equals("caption-side") || n.equals("empty-cells")) return "BoxProperties";
        return "OtherProperties";
    }

    // ---- 型からの割り当て

    private static List<String> byType(Property p) {
        String n = p.name();
        String enumName = ENUM_FOR_PROPERTY.get(n);
        if (enumName != null && !p.isShorthand()) return List.of(sig(p, enumName + " v", "v"));
        if (p.isShorthand()) return null;
        List<String> out = new ArrayList<>();
        switch (p.type()) {
            case LENGTH, LENGTH_PERCENTAGE -> {
                out.add(sig(p, "Value<Length> v", "v"));
                out.add(sig(p, "State<? extends Value<Length>> v", "v"));
                if ("none".equals(p.initial())) out.add(sig(p, "None v", "v"));
            }
            case COLOR -> {
                out.add(sig(p, "Value<Color> v", "v"));
                out.add(sig(p, "State<? extends Value<Color>> v", "v"));
            }
            case NUMBER -> out.add(sig(p, "double v", "CssValues.num(v)"));
            case INTEGER -> out.add(sig(p, "int v", "CssValues.integer(v)"));
            case TIME -> out.add(sig(p, "Value<Time> v", "v"));
            default -> {
                return null;
            }
        }
        return out;
    }

    /** 登録表の型だけでは形が決まらないもの（ショートハンド・特殊な値）。 */
    private static List<String> special(Property p) {
        String n = p.name();
        return switch (n) {
            case "inset", "margin", "padding", "border-radius" -> List.of(sides(p, "Value<Length>"));
            case "border-width" -> List.of(sides(p, "Value<Length>"));
            case "border-style" -> List.of(sides(p, "BorderStyle"));
            case "border-color" -> List.of(sides(p, "Value<Color>"));
            case "border", "border-top", "border-right", "border-bottom", "border-left", "outline", "column-rule" -> List.of(
                    sig(p, "Value<Length> width, BorderStyle style, Value<Color> color", "CssValues.join(width, style, color)"),
                    sig(p, "None v", "v"));
            case "grid-row-gap", "grid-column-gap", "contain-intrinsic-width", "contain-intrinsic-height" -> List.of(
                    sig(p, "Value<Length> v", "v"));
            case "tab-size" -> List.of(sig(p, "int v", "CssValues.integer(v)"), sig(p, "Value<Length> v", "v"));
            case "list-style-type" -> List.of(sig(p, "ListStyleType v", "v"));
            case "gap", "grid-gap" -> List.of(
                    sig(p, "Value<Length> both", "both"),
                    sig(p, "Value<Length> row, Value<Length> column", "CssValues.join(row, column)"));
            case "overflow" -> List.of(
                    sig(p, "Overflow both", "both"),
                    sig(p, "Overflow x, Overflow y", "CssValues.join(x, y)"));
            case "flex-flow" -> List.of(sig(p, "FlexDirection direction, FlexWrap wrap", "CssValues.join(direction, wrap)"));
            case "flex" -> List.of(
                    sig(p, "double grow", "CssValues.num(grow)"),
                    sig(p, "double grow, double shrink, Value<Length> basis",
                            "CssValues.join(CssValues.num(grow), CssValues.num(shrink), basis)"),
                    sig(p, "None v", "v"));
            case "place-content", "place-items", "place-self" -> List.of(
                    sig(p, "Alignment both", "both"),
                    sig(p, "Alignment align, Alignment justify", "CssValues.join(align, justify)"));
            case "background" -> List.of(
                    sig(p, "Value<Color> v", "v"),
                    sig(p, "State<? extends Value<Color>> v", "v"),
                    sig(p, "Raw v", "v"));
            case "font-weight" -> List.of(sig(p, "FontWeight v", "v"), sig(p, "int v", "CssValues.weight(v)"));
            case "font-family" -> List.of(sig(p, "FontFamily v", "v"), sig(p, "Raw v", "v"));
            case "line-height" -> List.of(sig(p, "double v", "CssValues.num(v)"), sig(p, "Value<Length> v", "v"));
            case "cursor" -> List.of(sig(p, "Cursor v", "v"));
            case "text-wrap" -> List.of(sig(p, "TextWrap v", "v"));
            case "accent-color" -> List.of(sig(p, "Value<Color> v", "v"));
            case "aspect-ratio" -> List.of(sig(p, "double width, double height", "CssValues.ratio(width, height)"));
            default -> null;
        };
    }

    private static String sides(Property p, String type) {
        String safe = type.contains("<") ? "@SafeVarargs " : ""; // 型引数を持つ可変長引数だけ（具象型に付けるとエラー）
        return "    " + safe + "public static Declaration " + method(p.name()) + "(" + type + "... sides) { return d(\""
                + p.name() + "\", CssValues.sides(sides)); }";
    }

    private static String sig(Property p, String params, String value) {
        return "    public static Declaration " + method(p.name()) + "(" + params + ") { return d(\"" + p.name() + "\", " + value + "); }";
    }

    /** プロパティ名 → 関数名（camelCase。予約語は DOM と同じく {@code css} を前に付ける）。 */
    static String method(String property) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : property.toCharArray()) {
            if (c == '-') {
                up = true;
                continue;
            }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        String r = sb.toString();
        return r.equals("float") ? "cssFloat" : r;
    }

    // ---- 出力

    private static String renderKeywords(Map<String, List<String>> enums) {
        StringBuilder s = new StringBuilder(HEADER);
        s.append("package ").append(PKG).append(";\n\n");
        s.append("import org.jetbrains.annotations.ApiStatus;\n\n");
        s.append("/**\n * キーワードだけを値に取るプロパティの値（{@code PropertyRegistry} のキーワード列挙と、生成器の表から作る）。\n");
        s.append(" * {@code import static ...style.Keyword.*} して {@code Display.FLEX} のように使う。\n */\n");
        s.append("@ApiStatus.AvailableSince(\"1.0\")\npublic final class Keyword {\n\n    private Keyword() {}\n");
        for (var e : enums.entrySet()) {
            s.append("\n    public enum ").append(e.getKey()).append(" implements CssValue {\n");
            StringBuilder line = new StringBuilder("        ");
            List<String> values = e.getValue();
            for (int i = 0; i < values.size(); i++) {
                String v = values.get(i);
                String c = v.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_') + "(\"" + v + "\")";
                String sep = i + 1 < values.size() ? ", " : ";";
                if (line.length() + c.length() + sep.length() > 120) {
                    s.append(line.toString().stripTrailing()).append('\n');
                    line = new StringBuilder("        ");
                }
                line.append(c).append(sep);
            }
            s.append(line).append('\n');
            s.append("        private final String css;\n\n");
            s.append("        ").append(e.getKey()).append("(String css) { this.css = css; }\n\n");
            s.append("        @Override public String css() { return css; }\n    }\n");
        }
        return s.append("}\n").toString();
    }

    private static String renderProperties(String name, String parent, List<String> methods, List<String> raw) {
        StringBuilder s = new StringBuilder(HEADER);
        s.append("package ").append(PKG).append(";\n\n");
        s.append("import com.nishiyu.enderframelib.api.ui.State;\n");
        s.append("import org.jetbrains.annotations.ApiStatus;\n\n");
        s.append("import static ").append(PKG).append(".Keyword.*;\n\n");
        s.append("/**\n * プロパティ関数の群（{@code PropertyRegistry} から生成）。{@link Css} が全群を継承するので {@code import static ...style.Css.*} で使う。\n");
        if (!raw.isEmpty()) {
            s.append(" *\n * <p>まだ型の無いもの（{@link Raw} を受ける）: ").append(String.join(", ", raw)).append("。</p>\n");
        }
        s.append(" */\n@ApiStatus.AvailableSince(\"1.0\")\npublic abstract class ").append(name);
        if (parent != null) s.append(" extends ").append(parent);
        s.append(" {\n\n    ").append(name).append("() {}\n\n");
        s.append("    private static Declaration d(String property, CssValue value) { return Declaration.of(property, value); }\n\n");
        s.append("    private static Declaration d(String property, State<? extends CssValue> value) { return Declaration.of(property, value); }\n\n");
        for (String m : methods) s.append(m).append('\n');
        return s.append("}\n").toString();
    }
}
