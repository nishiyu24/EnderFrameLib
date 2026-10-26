package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;

import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;
import com.nishiyu.enderframelib.core.css.LengthResolver.Metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 指定値から算出値を作る段。
 *
 * 子が継承するのは<b>算出値</b>なので、ここで値を「親に掛け直されない形」にしておく。
 *
 * <ul>
 *   <li>CSS 全域キーワード（{@code inherit} / {@code initial} / {@code unset}）を親の算出値か初期値に置き換える</li>
 *   <li>{@code font-size} を親のフォントサイズとビューポートで px に解く</li>
 *   <li>それ以外の値の {@code em} / {@code rem} / {@code lh} / {@code rlh} / {@code cap} / コンテナ単位（{@code cqw} など）と
 *       絶対長さを px に解く（{@code %} とビューポート単位は使用値の段で解く）</li>
 *   <li>{@code line-height} の {@code %} を px に解く（単位の無い数値はそのまま継承する）</li>
 *   <li>{@code currentcolor} を {@code color} の算出値に置き換える</li>
 * </ul>
 *
 * 何が壊れていたか: 以前は子がカスケード直後の文字列を継承していたため、
 * {@code font-size: 2em} を指定した要素の子孫が 2em を自分の親（既に 32px）に掛け直して
 * 複利で大きくなり、{@code width: inherit} は 0 になっていた。{@code font-size} の
 * {@code vw} はビューポート 0 で解かれて無効扱いになり、既定のサイズのままだった。
 * 単位の換算は値の文字を 1 つずつ走査していたので、{@code #} や識別子の途中の数字を取り違えかねなかった。
 * いまはトークンの上で、寸法のトークンだけを換算する。
 */
final class ValueComputer {

    /**
     * @param parent         親の算出スタイル（ルートでは空）
     * @param parentFontSize 親のフォントサイズ（px）
     * @param rootFontSize   ルート要素のフォントサイズ（px、{@code rem} の基準）
     * @param containerW     最も近い問い合わせコンテナの幅（px）。コンテナが無い・測っていなければ NaN
     * @param containerH     同じく高さ
     * @param rootLineHeight ルート要素の行の高さ（px、{@code rlh} の基準）。分からなければ NaN（{@code normal} の高さ）
     */
    record Context(Map<String, String> parent, float parentFontSize, float rootFontSize,
                   int viewportW, int viewportH, float containerW, float containerH, float rootLineHeight) {

        /** コンテナとルートの行の高さを知らない文脈。 */
        Context(Map<String, String> parent, float parentFontSize, float rootFontSize, int viewportW, int viewportH) {
            this(parent, parentFontSize, rootFontSize, viewportW, viewportH, Float.NaN, Float.NaN, Float.NaN);
        }

        /** ルートの行の高さを知らない文脈。 */
        Context(Map<String, String> parent, float parentFontSize, float rootFontSize, int viewportW, int viewportH,
                float containerW, float containerH) {
            this(parent, parentFontSize, rootFontSize, viewportW, viewportH, containerW, containerH, Float.NaN);
        }

        /** {@code rlh} の基準。ルートの行の高さが分からなければ、ルートの {@code normal}。 */
        float rootLineHeightPx() {
            return Float.isNaN(rootLineHeight) ? rootFontSize * LengthResolver.NORMAL_LINE_HEIGHT_EM : rootLineHeight;
        }
    }

    /** 絶対サイズのキーワード（CSS Fonts の medium = 16px からの比率表）。 */
    private static final Map<String, Float> ABSOLUTE_SIZES = Map.of(
            "xx-small", 9f, "x-small", 10f, "small", 13f, "medium", 16f,
            "large", 18f, "x-large", 24f, "xx-large", 32f, "xxx-large", 48f);
    /** {@code smaller} / {@code larger} の比率。 */
    private static final float RELATIVE_SIZE_RATIO = 1.2f;

    /** 長さとして解かない（文字列・名前・URL を持つ）プロパティ。 */
    private static final Set<String> OPAQUE_PROPERTIES = Set.of(
            CssProperty.FONT_FAMILY, CssProperty.CONTENT, CssProperty.SRC,
            CssProperty.ANIMATION_NAME, CssProperty.TRANSITION_PROPERTY,
            "grid-template-areas", "list-style-image", "background-image", "font");

    private ValueComputer() {}

    static void compute(Map<String, String> style, Context ctx) {
        resolveCssWideKeywords(style, ctx);

        String fontSize = style.get(CssProperty.FONT_SIZE);
        float ownFontSize = ctx.parentFontSize();
        if (fontSize != null) {
            ownFontSize = resolveFontSize(fontSize, ctx);
            style.put(CssProperty.FONT_SIZE, px(ownFontSize));
        }

        if ("currentcolor".equalsIgnoreCase(trimmed(style.get(CssProperty.COLOR)))) {
            putInheritedOrInitial(style, CssProperty.COLOR, ctx);
        }
        String color = style.get(CssProperty.COLOR);
        if (color == null) color = ctx.parent().get(CssProperty.COLOR);
        if (color == null) color = PropertyRegistry.initialValue(CssProperty.COLOR);

        Metrics metrics = unitMetrics(style, ctx, ownFontSize);

        List<String> keys = new ArrayList<>(style.keySet());
        for (String key : keys) {
            if (key.startsWith("--") || CssProperty.FONT_SIZE.equals(key)) continue;
            String value = style.get(key);
            if (value == null) continue;

            if (CssProperty.LINE_HEIGHT.equals(key) && value.trim().endsWith("%")) {
                Float pct = parseNumber(value.trim().substring(0, value.trim().length() - 1));
                if (pct != null) value = px(ownFontSize * pct / 100f);
            }
            if (!OPAQUE_PROPERTIES.contains(key)) {
                value = absolutizeUnits(value, metrics);
            }
            if (!CssProperty.COLOR.equals(key)) {
                value = replaceCurrentColor(value, color);
            }
            style.put(key, value);
        }
        computeLineWidths(style);
        computeBlockSizeKeywords(style);
    }

    /**
     * ブロック方向の寸法（{@code height} / {@code min-height} / {@code max-height}）の
     * {@code min-content} / {@code max-content} / {@code fit-content} は、初期値と同じに振る舞う
     * （CSS Sizing 3 §5.1）。算出値を初期値に直しておけば、レイアウトの側は特別扱いしなくてよい。
     */
    private static void computeBlockSizeKeywords(Map<String, String> style) {
        for (String key : BLOCK_SIZE_PROPERTIES) {
            String value = style.get(key);
            if (value == null) continue;
            String v = value.trim().toLowerCase(Locale.ROOT);
            if (v.equals("min-content") || v.equals("max-content") || v.equals("fit-content") || v.startsWith("fit-content(")) {
                style.put(key, key.equals(CssProperty.MAX_HEIGHT) ? "none" : "auto");
            }
        }
    }

    private static final String[] BLOCK_SIZE_PROPERTIES = {CssProperty.HEIGHT, CssProperty.MIN_HEIGHT, CssProperty.MAX_HEIGHT};

    /**
     * 値の中の単位を px にするときの基準。{@code lh} はこの要素の行の高さ（数値なら font-size 倍、{@code normal} は
     * ビットマップフォントの行間込みの高さ）、{@code rlh} はルートの {@code normal}。
     */
    private static Metrics unitMetrics(Map<String, String> style, Context ctx, float ownFontSize) {
        String declared = trimmed(style.get(CssProperty.LINE_HEIGHT));
        if (declared == null) declared = trimmed(ctx.parent().get(CssProperty.LINE_HEIGHT));
        float lineHeight = lineHeightPx(declared, ownFontSize, ctx.rootFontSize(), ctx.viewportW(), ctx.viewportH());
        return new Metrics(0, ctx.viewportW(), ctx.viewportH(), ownFontSize, ctx.rootFontSize(), lineHeight,
                ctx.rootLineHeightPx(), ctx.containerW(), ctx.containerH(),
                GlyphUnits.of(style, ctx.parent(), ownFontSize));
    }

    /**
     * {@code line-height} の宣言を px にする。数値は {@code font-size} 倍、長さはその長さ、{@code normal}（と読めない値）は
     * ビットマップフォントの行間込みの高さ。
     */
    static float lineHeightPx(String declared, float fontSize, float rootFontSize, int viewportW, int viewportH) {
        float lineHeight = fontSize * LengthResolver.NORMAL_LINE_HEIGHT_EM;
        if (declared == null || declared.equalsIgnoreCase("normal")) return lineHeight;
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(declared));
        Token t = values.size() == 1 ? CssSyntax.tokenOf(values.getFirst()) : null;
        if (t != null && t.type() == Type.NUMBER) {
            lineHeight = (float) (t.number() * fontSize);
        } else if (t != null && t.type() == Type.DIMENSION) {
            float px = LengthResolver.toPixels((float) t.number(), t.unit(),
                    Metrics.of(0, viewportW, viewportH, fontSize, rootFontSize));
            if (!Float.isNaN(px)) lineHeight = px;
        }
        return lineHeight;
    }

    private static final String[] SIDES = {"top", "right", "bottom", "left"};

    /** 枠線の幅のキーワード（CSS Backgrounds 3 §3.3 の UA の推奨値）。 */
    private static final Map<String, String> LINE_WIDTH_KEYWORDS = Map.of("thin", "1px", "medium", "3px", "thick", "5px");

    /**
     * {@code border-*-width} / {@code outline-width} の算出値。線種が {@code none} / {@code hidden} なら 0、
     * 線種があって幅が書かれていなければ初期値の {@code medium}（3px）。
     *
     * 何が壊れていたか: 以前は幅を線種と無関係に読んでいたため、{@code border-width: 2px} だけの箱に
     * 線種なしの枠が付き（ブラウザでは 0）、{@code border-style: solid} だけの箱には枠が付かなかった
     * （ブラウザでは 3px）。
     */
    private static void computeLineWidths(Map<String, String> style) {
        for (String side : SIDES) {
            computeLineWidth(style, "border-" + side + "-style", "border-" + side + "-width");
        }
        computeLineWidth(style, "outline-style", "outline-width");
        computeLineWidth(style, "column-rule-style", "column-rule-width");
    }

    private static void computeLineWidth(Map<String, String> style, String styleKey, String widthKey) {
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

    private static void resolveCssWideKeywords(Map<String, String> style, Context ctx) {
        List<String> keys = new ArrayList<>(style.keySet());
        for (String key : keys) {
            CssWideKeyword kw = CssWideKeyword.of(style.get(key));
            if (kw == null) continue;
            switch (kw) {
                case INHERIT -> putInheritedOrInitial(style, key, ctx);
                case INITIAL -> putInitial(style, key);
                // カスケードで解けなかった revert / revert-layer（UA 自身の revert や var() 経由）は unset と同じ
                case UNSET, REVERT, REVERT_LAYER -> {
                    if (PropertyRegistry.isInherited(key) || key.startsWith("--")) putInheritedOrInitial(style, key, ctx);
                    else putInitial(style, key);
                }
            }
        }
    }

    private static void putInheritedOrInitial(Map<String, String> style, String key, Context ctx) {
        String parentValue = ctx.parent().get(key);
        if (parentValue != null) style.put(key, parentValue);
        else putInitial(style, key);
    }

    private static void putInitial(Map<String, String> style, String key) {
        String initial = PropertyRegistry.initialValue(key);
        if (initial != null) style.put(key, initial);
        else style.remove(key);
    }

    /** font-size の em / % / smaller / larger は「親の」フォントサイズが基準。 */
    static float resolveFontSize(String value, Context ctx) {
        String v = value.trim().toLowerCase(Locale.ROOT);
        float parent = ctx.parentFontSize();
        Float absolute = ABSOLUTE_SIZES.get(v);
        if (absolute != null) return absolute;
        if ("smaller".equals(v)) return parent / RELATIVE_SIZE_RATIO;
        if ("larger".equals(v)) return parent * RELATIVE_SIZE_RATIO;

        // font-size の中では、em / lh などの基準は親のフォントサイズ（自身のサイズを決めている最中なので）
        Metrics metrics = new Metrics(parent, ctx.viewportW(), ctx.viewportH(), parent, ctx.rootFontSize(),
                parent * LengthResolver.NORMAL_LINE_HEIGHT_EM, ctx.rootFontSize() * LengthResolver.NORMAL_LINE_HEIGHT_EM,
                ctx.containerW(), ctx.containerH(), GlyphUnits.of(Map.of(), ctx.parent(), parent));
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(value));
        if (values.size() != 1) return parent;
        float result;
        if (values.getFirst() instanceof CssSyntax.Function f) {
            LengthResolver.Quantity q = LengthResolver.evaluateMath(CssSyntax.serialize(f), metrics);
            result = q == null || (q.dimension() != LengthResolver.Dimension.LENGTH
                    && q.dimension() != LengthResolver.Dimension.NUMBER) ? Float.NaN : (float) q.value();
        } else {
            Token t = CssSyntax.tokenOf(values.getFirst());
            result = t == null ? Float.NaN : switch (t.type()) {
                // 単位の無い数値は px とみなす（エンジンの従来の扱い）
                case NUMBER -> (float) t.number();
                case PERCENTAGE -> parent * (float) t.number() / 100f;
                case DIMENSION -> LengthResolver.toPixels((float) t.number(), t.unit(), metrics);
                default -> Float.NaN;
            };
        }
        // 負のサイズと解けない値は宣言ごと無効なので、親の値を継承したのと同じになる
        return !Float.isNaN(result) && result >= 0 ? result : parent;
    }

    /**
     * 値の中のフォント相対の長さ（{@code em} / {@code rem} / {@code lh} / {@code rlh} / {@code cap} / {@code ch} / {@code ex}）・
     * コンテナ単位と絶対長さ（{@code pt} / {@code pc} / {@code in} / {@code cm} / {@code mm} / {@code q}）を px に置き換える。
     * 置き換えるのは寸法のトークンだけで、文字列・{@code url()}・識別子・16 進の色の中は触らない。
     * コンテナを知らない文脈の {@code cq*} は、仕様どおり小ビューポート単位（{@code vw} / {@code vh} …）に直す。
     */
    static String absolutizeUnits(String value, Metrics metrics) {
        boolean[] changed = {false};
        List<ComponentValue> values = CssSyntax.parseComponentValues(value);
        String result = CssSyntax.serialize(values, token -> {
            if (token.type() != Type.DIMENSION) return token.raw();
            String unit = token.unit().toLowerCase(Locale.ROOT);
            String replacement = replaceUnit(token, unit, metrics);
            if (replacement == null) return token.raw();
            changed[0] = true;
            return replacement;
        });
        return changed[0] ? result : value;
    }

    /** 換算して置き換える文字列。換算しない単位（px・ビューポート単位・時間・角度など）は null。 */
    private static String replaceUnit(Token token, String unit, Metrics metrics) {
        float n = (float) token.number();
        switch (unit) {
            case "px", "vw", "vh", "vmin", "vmax", "vi", "vb", "svw", "svh", "svmin", "svmax", "svi", "svb",
                 "lvw", "lvh", "lvmin", "lvmax", "lvi", "lvb", "dvw", "dvh", "dvmin", "dvmax", "dvi", "dvb":
                return null;
            case "cqw", "cqh", "cqi", "cqb", "cqmin", "cqmax":
                if (!containerKnows(unit, metrics)) {
                    // コンテナが無い: 小ビューポート単位にする（レイアウトがビューポートで解く）
                    String viewportUnit = switch (unit) {
                        case "cqw", "cqi" -> "vw";
                        case "cqh", "cqb" -> "vh";
                        case "cqmin" -> "vmin";
                        default -> "vmax";
                    };
                    return numberText(token.number()) + viewportUnit;
                }
                return px(n * LengthResolver.lengthUnitInPixels(unit, metrics));
            case "em", "rem", "lh", "rlh", "cap", "ic", "ch", "ex":
                return px(n * LengthResolver.lengthUnitInPixels(unit, metrics));
            default: {
                float absolute = LengthResolver.absoluteToPixels(1f, unit);
                return Float.isNaN(absolute) ? null : px(n * absolute);
            }
        }
    }

    /** そのコンテナ単位が使う軸（幅・高さ）の寸法を、問い合わせコンテナが答えられるか。 */
    private static boolean containerKnows(String unit, Metrics metrics) {
        boolean width = !Float.isNaN(metrics.containerW());
        boolean height = !Float.isNaN(metrics.containerH());
        return switch (unit) {
            case "cqw", "cqi" -> width;
            case "cqh", "cqb" -> height;
            default -> width && height;
        };
    }

    private static String numberText(double number) {
        return number == Math.rint(number) ? Long.toString((long) number) : Double.toString(number);
    }

    /** {@code currentcolor} を色の値で置き換える。識別子の一部（{@code mycurrentcolor}）は触らない。 */
    private static String replaceCurrentColor(String value, String color) {
        if (color == null || !value.toLowerCase(Locale.ROOT).contains("currentcolor")) return value;
        boolean[] changed = {false};
        String result = CssSyntax.serialize(CssSyntax.parseComponentValues(value), token -> {
            if (token.type() == Type.IDENT && token.value().equalsIgnoreCase("currentcolor")) {
                changed[0] = true;
                return color;
            }
            return token.raw();
        });
        return changed[0] ? result : value;
    }

    private static String trimmed(String s) {
        return s == null ? null : s.trim();
    }

    private static Float parseNumber(String s) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** px の値を算出値の文字列にする。整数なら小数点を付けない（ゴールデンの差分を読みやすくするため）。 */
    static String px(float value) {
        float rounded = Math.round(value);
        if (Math.abs(value - rounded) < 1e-4f) return (long) rounded + "px";
        String s = String.format(Locale.ROOT, "%.4f", value);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s + "px";
    }

    /**
     * どのプロパティにも書ける CSS 全域キーワード。
     *
     * {@code revert} / {@code revert-layer} はカスケードの中で（巻き戻し先のオリジン・レイヤーが分かる場所で）、
     * それ以外は算出値を決める段（{@link ValueComputer}）で解く。
     */
    public enum CssWideKeyword {
        INHERIT, INITIAL, UNSET, REVERT, REVERT_LAYER;

        /** 値が CSS 全域キーワードならそれを、そうでなければ null を返す。 */
        public static CssWideKeyword of(String value) {
            if (value == null) return null;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "inherit" -> INHERIT;
                case "initial" -> INITIAL;
                case "unset" -> UNSET;
                case "revert" -> REVERT;
                case "revert-layer" -> REVERT_LAYER;
                default -> null;
            };
        }
    }
}
