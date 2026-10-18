package com.nishiyu.enderframelib.core.css;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * ショートハンドの値をロングハンドの値に分ける関数群。{@link PropertyRegistry} が
 * プロパティごとに 1 つずつ参照する。
 *
 * どれも「値の文字列 → ロングハンド名と値の対応」の純関数で、返した対応は
 * 算出スタイルに {@code putIfAbsent} で入る。値として解釈できなければ空の対応を返し、
 * カスケードはその宣言を無効として捨てる（先行するロングハンドを消さない）。
 */
final class ShorthandExpanders {

    private static final String[] SIDES = {"top", "right", "bottom", "left"};

    private static final Set<String> BORDER_STYLES = Set.of(
            "none", "hidden", "dotted", "dashed", "solid", "double", "groove", "ridge", "inset", "outset");
    private static final Set<String> BORDER_WIDTH_KEYWORDS = Set.of("thin", "medium", "thick");
    private static final Set<String> FLEX_DIRECTIONS = Set.of("row", "row-reverse", "column", "column-reverse");
    private static final Set<String> FLEX_WRAPS = Set.of("nowrap", "wrap", "wrap-reverse");
    private static final Set<String> FONT_STYLES = Set.of("italic", "oblique");
    private static final Set<String> FONT_VARIANTS = Set.of("small-caps");
    private static final Set<String> FONT_WEIGHTS = Set.of("bold", "bolder", "lighter");
    private static final Set<String> FONT_STRETCHES = Set.of(
            "ultra-condensed", "extra-condensed", "condensed", "semi-condensed",
            "semi-expanded", "expanded", "extra-expanded", "ultra-expanded");
    private static final Set<String> FONT_SIZE_KEYWORDS = Set.of(
            "xx-small", "x-small", "small", "medium", "large", "x-large", "xx-large", "xxx-large",
            "smaller", "larger");
    private static final Set<String> LIST_POSITIONS = Set.of("inside", "outside");

    /** 単位の無い数値（flex ショートハンドで grow / shrink と basis を見分けるため）。 */
    private static final Pattern UNITLESS_NUMBER = Pattern.compile("\\d*\\.?\\d+");
    private static final Pattern NUMERIC_START = Pattern.compile("^[+-]?(\\d|\\.\\d).*");

    private ShorthandExpanders() {}

    /** {@code margin} / {@code padding} の 1〜4 値を辺ごとに配る。 */
    static Map<String, String> box(String property, String value) {
        return boxSuffixed(property, null, value);
    }

    /** {@code border-width} のように辺の後ろに接尾辞が付く 1〜4 値。 */
    static Map<String, String> boxSuffixed(String prefix, String suffix, String value) {
        List<String> parts = splitTopLevelTokens(value);
        Map<String, String> out = new LinkedHashMap<>();
        if (parts.isEmpty() || parts.size() > 4) return out;
        String[] edges = switch (parts.size()) {
            case 1 -> new String[]{parts.get(0), parts.get(0), parts.get(0), parts.get(0)};
            case 2 -> new String[]{parts.get(0), parts.get(1), parts.get(0), parts.get(1)};
            case 3 -> new String[]{parts.get(0), parts.get(1), parts.get(2), parts.get(1)};
            default -> new String[]{parts.get(0), parts.get(1), parts.get(2), parts.get(3)};
        };
        for (int i = 0; i < SIDES.length; i++) {
            out.put(prefix + "-" + SIDES[i] + (suffix != null ? "-" + suffix : ""), edges[i]);
        }
        return out;
    }

    /** {@code inset} は辺の名前そのもの（top / right / bottom / left）に配る。 */
    static Map<String, String> inset(String value) {
        Map<String, String> edges = box("x", value);
        Map<String, String> out = new LinkedHashMap<>();
        for (String side : SIDES) {
            String v = edges.get("x-" + side);
            if (v != null) out.put(side, v);
        }
        return out;
    }

    /** {@code gap: <row-gap> <column-gap>}。値が 1 つなら両軸に同じ値。 */
    static Map<String, String> gap(String value) {
        List<String> parts = splitTopLevelTokens(value);
        Map<String, String> out = new LinkedHashMap<>();
        if (parts.isEmpty() || parts.size() > 2) return out;
        out.put("row-gap", parts.get(0));
        out.put("column-gap", parts.size() == 2 ? parts.get(1) : parts.get(0));
        return out;
    }

    /**
     * {@code flex: <grow> <shrink> <basis>}。
     *
     * 何が壊れていたか: この展開が無かったため {@code flex: 1} が
     * {@code flex-grow} の読み手（{@link com.nishiyu.enderframelib.core.layout.FlexLayoutEngine}）に
     * 一切届かず、ua.css の {@code th, td { flex: 1 }} が no-op で表の列幅が決まらなかった。
     */
    static Map<String, String> flex(String value) {
        String v = value.trim().toLowerCase();
        String grow = null, shrink = null, basis = null;
        switch (v) {
            case "none" -> { grow = "0"; shrink = "0"; basis = "auto"; }
            case "auto" -> { grow = "1"; shrink = "1"; basis = "auto"; }
            default -> {
                for (String part : splitTopLevelTokens(v)) {
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
        Map<String, String> out = new LinkedHashMap<>();
        out.put("flex-grow", grow);
        out.put("flex-shrink", shrink);
        out.put("flex-basis", basis);
        return out;
    }

    static Map<String, String> flexFlow(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String part : splitTopLevelTokens(value.toLowerCase())) {
            if (FLEX_DIRECTIONS.contains(part)) out.put("flex-direction", part);
            else if (FLEX_WRAPS.contains(part)) out.put("flex-wrap", part);
            else return Map.of();
        }
        return out;
    }

    /**
     * {@code overflow} を {@code overflow-x} / {@code overflow-y} に展開する。
     * 読み手が短縮形と軸別で分かれていて、どちらを見るかで挙動が変わっていたため、
     * 軸別のロングハンドを常に用意しておく。
     */
    static Map<String, String> overflow(String value) {
        List<String> parts = splitTopLevelTokens(value);
        Map<String, String> out = new LinkedHashMap<>();
        if (parts.isEmpty() || parts.size() > 2) return out;
        out.put("overflow-x", parts.get(0));
        out.put("overflow-y", parts.size() == 2 ? parts.get(1) : parts.get(0));
        return out;
    }

    /** {@code place-*: <align> <justify>}。値が 1 つなら両方に同じ値。 */
    static Map<String, String> pair(String first, String second, String value) {
        List<String> parts = splitTopLevelTokens(value);
        Map<String, String> out = new LinkedHashMap<>();
        if (parts.isEmpty() || parts.size() > 2) return out;
        out.put(first, parts.get(0));
        out.put(second, parts.size() == 2 ? parts.get(1) : parts.get(0));
        return out;
    }

    /** {@code border} は 4 辺のショートハンドに同じ値を配る（辺のショートハンドがさらに分ける）。 */
    static Map<String, String> border(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (borderParts(value, "border-x").isEmpty()) return out;
        for (String side : SIDES) out.put("border-" + side, value);
        return out;
    }

    /** {@code border-<辺>: <width> || <style> || <color>}。 */
    static Function<String, Map<String, String>> borderSide(String side) {
        return value -> borderParts(value, "border-" + side);
    }

    static Map<String, String> outline(String value) {
        return borderParts(value, "outline");
    }

    /**
     * 幅・線種・色を順不同で読み分ける。
     *
     * 何が壊れていたか: 以前は先頭を幅、3 つ以上あるときだけ末尾を色としていたため、
     * {@code border: solid 1px red} の幅が "solid" に、{@code border: 1px red} の色が
     * 無しになっていた。
     */
    private static Map<String, String> borderParts(String value, String prefix) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> parts = splitTopLevelTokens(value);
        if (parts.isEmpty() || parts.size() > 3) return out;
        for (String part : parts) {
            String lower = part.toLowerCase();
            // outline-style だけは auto（UA のフォーカスリング）を取る
            if (BORDER_STYLES.contains(lower) || ("auto".equals(lower) && "outline".equals(prefix))) {
                if (out.containsKey(prefix + "-style")) return Map.of();
                out.put(prefix + "-style", lower);
            } else if (BORDER_WIDTH_KEYWORDS.contains(lower) || NUMERIC_START.matcher(lower).matches()
                    || lower.startsWith("calc(") || lower.startsWith("min(") || lower.startsWith("max(")
                    || lower.startsWith("clamp(")) {
                if (out.containsKey(prefix + "-width")) return Map.of();
                out.put(prefix + "-width", part);
            } else {
                if (out.containsKey(prefix + "-color")) return Map.of();
                out.put(prefix + "-color", part);
            }
        }
        return out;
    }

    private static final Set<String> BG_REPEATS = Set.of("repeat-x", "repeat-y", "repeat", "space", "round", "no-repeat");
    private static final Set<String> BG_ATTACHMENTS = Set.of("scroll", "fixed", "local");
    private static final Set<String> BG_BOXES = Set.of("border-box", "padding-box", "content-box");
    private static final Set<String> BG_POSITION_KEYWORDS = Set.of("left", "center", "right", "top", "bottom");
    private static final Set<String> BG_SIZE_KEYWORDS = Set.of("auto", "cover", "contain");

    /**
     * {@code background: [<bg-layer>,]* <final-bg-layer>}（CSS Backgrounds 3 §3.10）。
     * 各ロングハンドはレイヤーごとの値をカンマで並べたリストになり、書かれなかった値は初期値に戻る。
     * 色は最後のレイヤーにだけ書ける。箱のキーワードが 1 つなら origin と clip の両方、2 つなら順に origin・clip。
     *
     * 何が壊れていたか: 以前はショートハンドを展開せず、描画が文字列全体を色として読んでいたため、
     * {@code background: url(a.png) red} は色も画像も出ず、{@code linear-gradient} は最初と最後の
     * 色だけを拾っていた。
     */
    static Map<String, String> background(String value) {
        List<String> layers = CssParser.CssTokenizer.splitTopLevel(value, ',');
        List<String> images = new ArrayList<>(), positions = new ArrayList<>(), sizes = new ArrayList<>(),
                repeats = new ArrayList<>(), attachments = new ArrayList<>(), origins = new ArrayList<>(),
                clips = new ArrayList<>();
        String color = "transparent";
        for (int li = 0; li < layers.size(); li++) {
            boolean last = li == layers.size() - 1;
            List<String> tokens = splitTopLevelTokens(spaceOutSlash(layers.get(li).trim()));
            if (tokens.isEmpty()) return Map.of();
            String image = null, attachment = null, layerColor = null;
            List<String> position = new ArrayList<>(), size = new ArrayList<>(), repeat = new ArrayList<>(),
                    boxes = new ArrayList<>();
            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                String lower = t.toLowerCase();
                if ("/".equals(t)) {
                    if (position.isEmpty() || !size.isEmpty()) return Map.of();
                    while (i + 1 < tokens.size() && isBackgroundSize(tokens.get(i + 1)) && size.size() < 2) {
                        size.add(tokens.get(++i));
                    }
                    if (size.isEmpty()) return Map.of();
                } else if ("none".equals(lower) || lower.startsWith("url(") || lower.contains("gradient(")) {
                    if (image != null) return Map.of();
                    image = t;
                } else if (BG_REPEATS.contains(lower)) {
                    if (repeat.size() >= 2) return Map.of();
                    repeat.add(lower);
                } else if (BG_ATTACHMENTS.contains(lower)) {
                    if (attachment != null) return Map.of();
                    attachment = lower;
                } else if (BG_BOXES.contains(lower)) {
                    if (boxes.size() >= 2) return Map.of();
                    boxes.add(lower);
                } else if (BG_POSITION_KEYWORDS.contains(lower) || NUMERIC_START.matcher(lower).matches()
                        || lower.startsWith("calc(")) {
                    if (position.size() >= 4) return Map.of();
                    position.add(t);
                } else if (last && layerColor == null) {
                    layerColor = t;
                } else {
                    return Map.of();
                }
            }
            images.add(image != null ? image : "none");
            positions.add(position.isEmpty() ? "0% 0%" : String.join(" ", position));
            sizes.add(size.isEmpty() ? "auto" : String.join(" ", size));
            repeats.add(repeat.isEmpty() ? "repeat" : String.join(" ", repeat));
            attachments.add(attachment != null ? attachment : "scroll");
            origins.add(boxes.isEmpty() ? "padding-box" : boxes.get(0));
            clips.add(boxes.isEmpty() ? "border-box" : boxes.get(boxes.size() - 1));
            if (layerColor != null) color = layerColor;
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("background-color", color);
        out.put("background-image", String.join(", ", images));
        out.put("background-position", String.join(", ", positions));
        out.put("background-size", String.join(", ", sizes));
        out.put("background-repeat", String.join(", ", repeats));
        out.put("background-attachment", String.join(", ", attachments));
        out.put("background-origin", String.join(", ", origins));
        out.put("background-clip", String.join(", ", clips));
        return out;
    }

    private static boolean isBackgroundSize(String token) {
        String lower = token.toLowerCase();
        return BG_SIZE_KEYWORDS.contains(lower) || NUMERIC_START.matcher(lower).matches() || lower.startsWith("calc(");
    }

    /** 括弧の外の {@code /} を前後の空白で区切る（{@code center/cover} を 3 語に分けるため）。 */
    private static String spaceOutSlash(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 4);
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && depth > 0) depth--;
            if (c == '/' && depth == 0) sb.append(" / ");
            else sb.append(c);
        }
        return sb.toString();
    }

    static Map<String, String> listStyle(String value) {
        Map<String, String> out = new LinkedHashMap<>();
        int nones = 0;
        for (String part : splitTopLevelTokens(value)) {
            String lower = part.toLowerCase();
            if (LIST_POSITIONS.contains(lower)) out.put("list-style-position", lower);
            else if (lower.startsWith("url(")) out.put("list-style-image", part);
            else if ("none".equals(lower)) nones++;
            else out.put("list-style-type", part);
        }
        // none は type と image のうち、明示されなかった方に入る
        if (nones > 0) {
            if (!out.containsKey("list-style-type")) { out.put("list-style-type", "none"); nones--; }
            if (nones > 0 && !out.containsKey("list-style-image")) out.put("list-style-image", "none");
        }
        out.putIfAbsent("list-style-type", "disc");
        out.putIfAbsent("list-style-position", "outside");
        out.putIfAbsent("list-style-image", "none");
        return out;
    }

    /**
     * {@code font: [<style> || <variant> || <weight> || <stretch>]? <size>[/<line-height>]? <family>}。
     * 書かれなかったロングハンドは初期値に戻る（CSS の font ショートハンドの規定）。
     * システムフォントのキーワード（{@code caption} など）は解釈しない。
     */
    static Map<String, String> font(String value) {
        List<String> parts = splitTopLevelTokens(value);
        String style = "normal", variant = "normal", weight = "normal";
        int i = 0;
        for (; i < parts.size(); i++) {
            String lower = parts.get(i).toLowerCase();
            if ("normal".equals(lower)) continue;
            if (FONT_STYLES.contains(lower)) style = lower;
            else if (FONT_VARIANTS.contains(lower)) variant = lower;
            else if (FONT_WEIGHTS.contains(lower) || lower.matches("[1-9]00")) weight = lower;
            else if (FONT_STRETCHES.contains(lower)) continue;
            else break;
        }
        if (i >= parts.size()) return Map.of();

        String sizeToken = parts.get(i++);
        String lineHeight = "normal";
        int slash = sizeToken.indexOf('/');
        if (slash >= 0) {
            lineHeight = sizeToken.substring(slash + 1);
            sizeToken = sizeToken.substring(0, slash);
            if (lineHeight.isEmpty() && i < parts.size()) lineHeight = parts.get(i++);
        } else if (i < parts.size() && parts.get(i).startsWith("/")) {
            lineHeight = parts.get(i).substring(1);
            i++;
            if (lineHeight.isEmpty() && i < parts.size()) lineHeight = parts.get(i++);
        }
        String sizeLower = sizeToken.toLowerCase();
        if (!FONT_SIZE_KEYWORDS.contains(sizeLower) && !NUMERIC_START.matcher(sizeLower).matches()
                && !sizeLower.startsWith("calc(")) {
            return Map.of();
        }
        if (i >= parts.size()) return Map.of(); // font-family は必須
        String family = String.join(" ", parts.subList(i, parts.size()));

        Map<String, String> out = new LinkedHashMap<>();
        out.put("font-style", style);
        out.put("font-variant", variant);
        out.put("font-weight", weight);
        out.put("font-size", sizeToken);
        out.put("line-height", lineHeight);
        out.put("font-family", family);
        return out;
    }

    /** 括弧内と文字列リテラル内の空白では分割しない。 */
    static List<String> splitTopLevelTokens(String s) {
        List<String> out = new ArrayList<>(4);
        int depth = 0;
        int start = -1;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                if (start < 0) start = i;
                continue;
            }
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
}
