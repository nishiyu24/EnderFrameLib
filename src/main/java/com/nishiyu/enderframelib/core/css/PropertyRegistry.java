package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.ValueComputer.CssWideKeyword;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * CSS プロパティの知識の唯一の出所（設計の軸 5）。
 *
 * 初期値・継承の有無・値の型・アニメーション可否・無効化種別（paint / layout）・
 * ショートハンドの展開を、プロパティごとにここで 1 回だけ宣言する。
 *
 * 何が壊れていたか: 以前は継承するプロパティ（{@code INHERITABLE_PROPS}）、
 * 再描画だけで済むプロパティ（{@code PAINT_ONLY_PROPS}）、ショートハンドの展開
 * （{@code expand*}）と「ショートハンドを宣言したら先行するロングハンドを消す」処理が
 * {@link StyleResolver} の別々の場所に書かれていて、プロパティを 1 つ足すたびに
 * 4 箇所を揃える必要があった。片方だけ直すと、例えば {@code flex} の後に書いた
 * {@code flex-grow} が先行ルールのものに負ける、といった食い違いが黙って起きる。
 *
 * <p>ここに無いプロパティも算出スタイルには残る（読み手がいれば効く）。扱いは
 * 「継承しない・初期値不明・変化したら再レイアウト」で、従来の既定と同じ。
 */
public final class PropertyRegistry {

    /** 値が変わったときに必要な無効化。 */
    public enum Invalidation {
        /** 寸法・配置・折り返しに効くので measure / layout からやり直す。 */
        LAYOUT,
        /** 既存の箱のまま描き直すだけで済む。 */
        PAINT
    }

    /** 値の型（算出値の解き方を選ぶための分類）。 */
    public enum ValueType {
        LENGTH, LENGTH_PERCENTAGE, NUMBER, INTEGER, COLOR, KEYWORD, TIME, STRING, LIST, OTHER
    }

    /**
     * 1 つのプロパティの宣言。
     *
     * @param initial   初期値。UA に依存して決まらないもの（{@code font-family} など）は null
     * @param longhands ショートハンドなら直下のロングハンド（宣言順）。ロングハンドなら空
     * @param expander  ショートハンドの値をロングハンドの値に分ける関数。null のショートハンドは
     *                  読み手がショートハンドの文字列を直接読む（{@code transition} など）
     */
    public record Property(String name, String initial, boolean inherited, ValueType type,
                           boolean animatable, Invalidation invalidation,
                           List<String> longhands, Function<String, Map<String, String>> expander) {
        public boolean isShorthand() {
            return !longhands.isEmpty();
        }
    }

    private static final Map<String, Property> PROPERTIES = new LinkedHashMap<>();

    /**
     * 展開の順序。値は {@code putIfAbsent} で入るので、先に展開したものが勝つ。
     * {@code border} → {@code border-color} / {@code -width} / {@code -style} → {@code border-<辺>} の順は、
     * {@code border-color} の個別色が {@code border-top} の色より優先される従来の挙動を保つため。
     */
    private static final List<String> EXPANSION_ORDER = List.of(
            "margin", "padding",
            "grid-row-gap", "grid-column-gap", "gap", "grid-gap",
            "flex", "flex-flow", "overflow", "inset",
            "place-content", "place-items", "place-self",
            "font", "list-style", "outline",
            "border", "border-color", "border-width", "border-style",
            "border-top", "border-right", "border-bottom", "border-left"
    );

    private static final String[] SIDES = {"top", "right", "bottom", "left"};

    static {
        // ---- 表示と位置
        layout("display", "inline", false, ValueType.KEYWORD, false);
        layout("position", "static", false, ValueType.KEYWORD, false);
        for (String side : SIDES) layout(side, "auto", false, ValueType.LENGTH_PERCENTAGE, true);
        shorthand("inset", Invalidation.LAYOUT, ShorthandExpanders::inset, "top", "right", "bottom", "left");
        layout("float", "none", false, ValueType.KEYWORD, false);
        layout("clear", "none", false, ValueType.KEYWORD, false);
        paint("z-index", "auto", false, ValueType.INTEGER, true);
        paint("visibility", "visible", true, ValueType.KEYWORD, true);

        // ---- 箱の寸法
        for (String p : List.of("width", "height")) layout(p, "auto", false, ValueType.LENGTH_PERCENTAGE, true);
        for (String p : List.of("min-width", "min-height")) layout(p, "auto", false, ValueType.LENGTH_PERCENTAGE, true);
        for (String p : List.of("max-width", "max-height")) layout(p, "none", false, ValueType.LENGTH_PERCENTAGE, true);
        layout("box-sizing", "content-box", false, ValueType.KEYWORD, false);
        layout("aspect-ratio", "auto", false, ValueType.OTHER, true);
        for (String box : List.of("margin", "padding")) {
            for (String side : SIDES) layout(box + "-" + side, "0", false, ValueType.LENGTH_PERCENTAGE, true);
        }
        shorthand("margin", Invalidation.LAYOUT, v -> ShorthandExpanders.box("margin", v),
                "margin-top", "margin-right", "margin-bottom", "margin-left");
        shorthand("padding", Invalidation.LAYOUT, v -> ShorthandExpanders.box("padding", v),
                "padding-top", "padding-right", "padding-bottom", "padding-left");

        // ---- ボーダー
        for (String side : SIDES) {
            layout("border-" + side + "-width", "medium", false, ValueType.LENGTH, true);
            // border-style が none なら幅は 0 になるので、スタイルの変化も寸法に効く
            layout("border-" + side + "-style", "none", false, ValueType.KEYWORD, false);
            paint("border-" + side + "-color", "currentcolor", false, ValueType.COLOR, true);
            shorthand("border-" + side, Invalidation.LAYOUT, ShorthandExpanders.borderSide(side),
                    "border-" + side + "-width", "border-" + side + "-style", "border-" + side + "-color");
        }
        shorthand("border-width", Invalidation.LAYOUT, v -> ShorthandExpanders.boxSuffixed("border", "width", v),
                "border-top-width", "border-right-width", "border-bottom-width", "border-left-width");
        shorthand("border-style", Invalidation.LAYOUT, v -> ShorthandExpanders.boxSuffixed("border", "style", v),
                "border-top-style", "border-right-style", "border-bottom-style", "border-left-style");
        shorthand("border-color", Invalidation.PAINT, v -> ShorthandExpanders.boxSuffixed("border", "color", v),
                "border-top-color", "border-right-color", "border-bottom-color", "border-left-color");
        // border はロングハンドではなく辺ごとのショートハンドへ展開する（辺のショートハンドがさらに展開する）
        shorthand("border", Invalidation.LAYOUT, ShorthandExpanders::border,
                "border-top", "border-right", "border-bottom", "border-left",
                "border-width", "border-style", "border-color");
        // border-radius は読み手（描画）がショートハンドの文字列を直接読むので、角ごとには展開しない
        paint("border-radius", "0", false, ValueType.LENGTH_PERCENTAGE, true);
        for (String corner : List.of("top-left", "top-right", "bottom-right", "bottom-left")) {
            paint("border-" + corner + "-radius", "0", false, ValueType.LENGTH_PERCENTAGE, true);
        }
        paint("outline-color", "currentcolor", false, ValueType.COLOR, true);
        paint("outline-style", "none", false, ValueType.KEYWORD, false);
        paint("outline-width", "medium", false, ValueType.LENGTH, true);
        paint("outline-offset", "0", false, ValueType.LENGTH, true);
        shorthand("outline", Invalidation.PAINT, ShorthandExpanders::outline,
                "outline-width", "outline-style", "outline-color");

        // ---- overflow
        layout("overflow-x", "visible", false, ValueType.KEYWORD, false);
        layout("overflow-y", "visible", false, ValueType.KEYWORD, false);
        shorthand("overflow", Invalidation.LAYOUT, ShorthandExpanders::overflow, "overflow-x", "overflow-y");
        layout("scrollbar-width", "auto", false, ValueType.KEYWORD, false);

        // ---- flex
        layout("flex-direction", "row", false, ValueType.KEYWORD, false);
        layout("flex-wrap", "nowrap", false, ValueType.KEYWORD, false);
        shorthand("flex-flow", Invalidation.LAYOUT, ShorthandExpanders::flexFlow, "flex-direction", "flex-wrap");
        layout("flex-grow", "0", false, ValueType.NUMBER, true);
        layout("flex-shrink", "1", false, ValueType.NUMBER, true);
        layout("flex-basis", "auto", false, ValueType.LENGTH_PERCENTAGE, true);
        shorthand("flex", Invalidation.LAYOUT, ShorthandExpanders::flex, "flex-grow", "flex-shrink", "flex-basis");
        layout("order", "0", false, ValueType.INTEGER, true);

        // ---- 揃え（box alignment）
        layout("justify-content", "normal", false, ValueType.KEYWORD, false);
        layout("align-content", "normal", false, ValueType.KEYWORD, false);
        layout("justify-items", "legacy", false, ValueType.KEYWORD, false);
        layout("align-items", "normal", false, ValueType.KEYWORD, false);
        layout("justify-self", "auto", false, ValueType.KEYWORD, false);
        layout("align-self", "auto", false, ValueType.KEYWORD, false);
        shorthand("place-content", Invalidation.LAYOUT, v -> ShorthandExpanders.pair("align-content", "justify-content", v),
                "align-content", "justify-content");
        shorthand("place-items", Invalidation.LAYOUT, v -> ShorthandExpanders.pair("align-items", "justify-items", v),
                "align-items", "justify-items");
        shorthand("place-self", Invalidation.LAYOUT, v -> ShorthandExpanders.pair("align-self", "justify-self", v),
                "align-self", "justify-self");
        layout("row-gap", "normal", false, ValueType.LENGTH_PERCENTAGE, true);
        layout("column-gap", "normal", false, ValueType.LENGTH_PERCENTAGE, true);
        shorthand("gap", Invalidation.LAYOUT, ShorthandExpanders::gap, "row-gap", "column-gap");
        // 旧名の別名。gap が無いときだけ効く（展開順で gap が先に入る）
        shorthand("grid-gap", Invalidation.LAYOUT, ShorthandExpanders::gap, "row-gap", "column-gap");
        shorthand("grid-row-gap", Invalidation.LAYOUT, v -> Map.of("row-gap", v.trim()), "row-gap");
        shorthand("grid-column-gap", Invalidation.LAYOUT, v -> Map.of("column-gap", v.trim()), "column-gap");

        // ---- grid
        // grid-column / grid-row / grid-area はレイアウトがショートハンドの文字列を直接読む（W4 で展開する）
        for (String p : List.of("grid-template-columns", "grid-template-rows", "grid-template-areas")) {
            layout(p, "none", false, ValueType.LIST, true);
        }
        layout("grid-auto-columns", "auto", false, ValueType.LIST, true);
        layout("grid-auto-rows", "auto", false, ValueType.LIST, true);
        layout("grid-auto-flow", "row", false, ValueType.KEYWORD, false);
        for (String p : List.of("grid-column-start", "grid-column-end", "grid-row-start", "grid-row-end",
                "grid-column", "grid-row", "grid-area")) {
            layout(p, "auto", false, ValueType.OTHER, false);
        }

        // ---- 表・リスト
        layout("table-layout", "auto", false, ValueType.KEYWORD, false);
        layout("border-collapse", "separate", true, ValueType.KEYWORD, false);
        layout("border-spacing", "0", true, ValueType.LENGTH, true);
        layout("caption-side", "top", true, ValueType.KEYWORD, false);
        paint("empty-cells", "show", true, ValueType.KEYWORD, false);
        layout("list-style-type", "disc", true, ValueType.OTHER, false);
        layout("list-style-position", "outside", true, ValueType.KEYWORD, false);
        layout("list-style-image", "none", true, ValueType.OTHER, false);
        shorthand("list-style", Invalidation.LAYOUT, ShorthandExpanders::listStyle,
                "list-style-type", "list-style-position", "list-style-image");

        // ---- テキストとフォント
        layout("font-family", null, true, ValueType.LIST, false);
        layout("font-size", "medium", true, ValueType.LENGTH_PERCENTAGE, true);
        layout("font-weight", "normal", true, ValueType.OTHER, true);
        layout("font-style", "normal", true, ValueType.KEYWORD, false);
        layout("font-variant", "normal", true, ValueType.KEYWORD, false);
        layout("line-height", "normal", true, ValueType.OTHER, true);
        shorthand("font", Invalidation.LAYOUT, ShorthandExpanders::font,
                "font-style", "font-variant", "font-weight", "font-size", "line-height", "font-family");
        layout("letter-spacing", "normal", true, ValueType.LENGTH, true);
        layout("word-spacing", "normal", true, ValueType.LENGTH, true);
        layout("text-indent", "0", true, ValueType.LENGTH_PERCENTAGE, true);
        layout("text-align", "start", true, ValueType.KEYWORD, false);
        layout("text-transform", "none", true, ValueType.KEYWORD, false);
        layout("white-space", "normal", true, ValueType.KEYWORD, false);
        layout("word-break", "normal", true, ValueType.KEYWORD, false);
        layout("overflow-wrap", "normal", true, ValueType.KEYWORD, false);
        layout("direction", "ltr", true, ValueType.KEYWORD, false);
        layout("vertical-align", "baseline", false, ValueType.LENGTH_PERCENTAGE, true);
        layout("text-overflow", "clip", false, ValueType.KEYWORD, false);
        paint("color", "black", true, ValueType.COLOR, true);
        // CSS では text-decoration は継承しないが、装飾は子孫のテキストに伝播して描かれる。
        // 伝播の仕組みが無いので、ここでは継承で代用している（描画が子のテキストノードで読む）。
        paint("text-decoration", "none", true, ValueType.OTHER, false);
        paint("text-shadow", "none", true, ValueType.LIST, true);

        // ---- 生成内容・置換要素
        layout("content", "normal", false, ValueType.STRING, false);
        layout("src", null, false, ValueType.STRING, false);
        layout("object-fit", "fill", false, ValueType.KEYWORD, false);

        // ---- 描画
        paint("background-color", "transparent", false, ValueType.COLOR, true);
        paint("background-image", "none", false, ValueType.LIST, false);
        paint("background-position", "0% 0%", false, ValueType.LIST, true);
        paint("background-size", "auto", false, ValueType.LIST, true);
        paint("background-repeat", "repeat", false, ValueType.LIST, false);
        paint("background-clip", "border-box", false, ValueType.LIST, false);
        paint("background-origin", "padding-box", false, ValueType.LIST, false);
        paint("background-attachment", "scroll", false, ValueType.LIST, false);
        // background は描画がショートハンドの文字列を直接読む（W5 でレイヤーに展開する）
        shorthand("background", Invalidation.PAINT, null,
                "background-color", "background-image", "background-position", "background-size",
                "background-repeat", "background-clip", "background-origin", "background-attachment");
        paint("box-shadow", "none", false, ValueType.LIST, true);
        paint("opacity", "1", false, ValueType.NUMBER, true);
        paint("transform", "none", false, ValueType.LIST, true);
        paint("transform-origin", "50% 50%", false, ValueType.LIST, true);
        paint("filter", "none", false, ValueType.LIST, true);
        paint("mix-blend-mode", "normal", false, ValueType.KEYWORD, false);
        paint("isolation", "auto", false, ValueType.KEYWORD, false);
        paint("cursor", "auto", true, ValueType.OTHER, false);
        paint("pointer-events", "auto", true, ValueType.KEYWORD, false);
        paint("user-select", "auto", false, ValueType.KEYWORD, false);

        // ---- トランジション・アニメーション（読み手がショートハンドとロングハンドを両方読む）
        paint("transition-property", "all", false, ValueType.LIST, false);
        paint("transition-duration", "0s", false, ValueType.TIME, false);
        paint("transition-delay", "0s", false, ValueType.TIME, false);
        paint("transition-timing-function", "ease", false, ValueType.LIST, false);
        shorthand("transition", Invalidation.PAINT, null,
                "transition-property", "transition-duration", "transition-delay", "transition-timing-function");
        paint("animation-name", "none", false, ValueType.LIST, false);
        paint("animation-duration", "0s", false, ValueType.TIME, false);
        paint("animation-delay", "0s", false, ValueType.TIME, false);
        paint("animation-timing-function", "ease", false, ValueType.LIST, false);
        paint("animation-iteration-count", "1", false, ValueType.LIST, false);
        paint("animation-direction", "normal", false, ValueType.LIST, false);
        paint("animation-fill-mode", "none", false, ValueType.LIST, false);
        shorthand("animation", Invalidation.PAINT, null,
                "animation-name", "animation-duration", "animation-delay", "animation-timing-function",
                "animation-iteration-count", "animation-direction", "animation-fill-mode");
    }

    private PropertyRegistry() {}

    private static void layout(String name, String initial, boolean inherited, ValueType type, boolean animatable) {
        register(new Property(name, initial, inherited, type, animatable, Invalidation.LAYOUT, List.of(), null));
    }

    private static void paint(String name, String initial, boolean inherited, ValueType type, boolean animatable) {
        register(new Property(name, initial, inherited, type, animatable, Invalidation.PAINT, List.of(), null));
    }

    private static void shorthand(String name, Invalidation invalidation,
                                  Function<String, Map<String, String>> expander, String... longhands) {
        boolean inherited = true;
        for (String l : longhands) inherited &= PROPERTIES.get(l).inherited();
        register(new Property(name, null, inherited, ValueType.OTHER, false, invalidation, List.of(longhands), expander));
    }

    private static void register(Property p) {
        if (PROPERTIES.put(p.name(), p) != null) {
            throw new IllegalStateException("プロパティの二重登録: " + p.name());
        }
    }

    /** 登録済みのプロパティ。未登録なら null。 */
    public static Property get(String name) {
        return PROPERTIES.get(name);
    }

    public static boolean isKnown(String name) {
        return PROPERTIES.containsKey(name);
    }

    public static Collection<Property> all() {
        return Collections.unmodifiableCollection(PROPERTIES.values());
    }

    /** 継承するプロパティか。未登録のものは継承しない。 */
    public static boolean isInherited(String name) {
        Property p = PROPERTIES.get(name);
        return p != null && p.inherited();
    }

    /**
     * 値の変化が再描画だけで済むか。未登録のものは再レイアウトが要るとみなす
     * （効くかどうか分からない値で寸法を古いままにしないため）。
     */
    public static boolean isPaintOnly(String name) {
        Property p = PROPERTIES.get(name);
        return p != null && p.invalidation() == Invalidation.PAINT;
    }

    /** 初期値。未登録か UA 依存なら null。 */
    public static String initialValue(String name) {
        Property p = PROPERTIES.get(name);
        return p != null ? p.initial() : null;
    }

    /**
     * ショートハンドが最終的に設定するプロパティすべて（入れ子のショートハンドを含む）。
     * カスケードで「後のショートハンドが先のロングハンドを上書きする」のに使う。
     */
    public static Set<String> subproperties(String shorthand) {
        Set<String> cached = SUBPROPERTIES.get(shorthand);
        return cached != null ? cached : Set.of();
    }

    /** カスケードが宣言ごとに引くので、登録の直後に 1 回だけ閉包を作っておく。 */
    private static final Map<String, Set<String>> SUBPROPERTIES = buildSubproperties();

    private static Map<String, Set<String>> buildSubproperties() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Property p : PROPERTIES.values()) {
            if (!p.isShorthand()) continue;
            Set<String> subs = new LinkedHashSet<>();
            collectSubproperties(p.name(), subs);
            out.put(p.name(), Collections.unmodifiableSet(subs));
        }
        return out;
    }

    private static void collectSubproperties(String name, Set<String> out) {
        Property p = PROPERTIES.get(name);
        if (p == null) return;
        for (String l : p.longhands()) {
            if (out.add(l)) collectSubproperties(l, out);
        }
    }

    /** {@code all} が触るプロパティ（ロングハンドすべて。{@code direction} は CSS の規定どおり除く）。 */
    public static List<String> allLonghands() {
        List<String> out = new ArrayList<>();
        for (Property p : PROPERTIES.values()) {
            if (!p.isShorthand() && !"direction".equals(p.name())) out.add(p.name());
        }
        return out;
    }

    /**
     * 算出スタイルのショートハンドをロングハンドへ展開する（{@code putIfAbsent} なので
     * 既にあるロングハンドが優先）。値が CSS 全域キーワードなら全ロングハンドに同じキーワードを配る。
     */
    public static void expandShorthands(Map<String, String> style) {
        for (String name : EXPANSION_ORDER) {
            String value = style.get(name);
            if (value == null || value.isBlank()) continue;
            Property p = PROPERTIES.get(name);
            if (CssWideKeyword.of(value) != null) {
                for (String l : p.longhands()) style.putIfAbsent(l, value.trim());
                continue;
            }
            Map<String, String> parts = p.expander().apply(value.trim());
            for (Map.Entry<String, String> e : parts.entrySet()) {
                if (e.getValue() != null) style.putIfAbsent(e.getKey(), e.getValue());
            }
        }
    }
}
