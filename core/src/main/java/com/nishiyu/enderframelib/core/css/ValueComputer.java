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

    /** 添字ごとの、長さとして解かない（文字列・名前・URL を持つ）プロパティか。 */
    private static final boolean[] OPAQUE_BY_ID = new boolean[ComputedStyle.CssPropertyId.count()];

    static {
        for (String name : OPAQUE_PROPERTIES) {
            int id = ComputedStyle.idOf(name);
            if (id >= 0) OPAQUE_BY_ID[id] = true;
        }
    }

    /**
     * 算出値を決める。作業表（{@link StyleTable}）なら、プロパティを名前ではなく添字で配列から直接読み書きする。
     * 素のマップ（テストや埋め込み側が直に渡すもの）は作業表に写して算出し、結果を書き戻す。
     */
    static void compute(Map<String, String> style, Context ctx) {
        if (style instanceof StyleTable table) {
            computeTable(table, ctx);
            return;
        }
        StyleTable table = StyleTable.copyOf(style);
        computeTable(table, ctx);
        style.clear();
        style.putAll(table);
    }

    private static void computeTable(StyleTable style, Context ctx) {
        resolveCssWideKeywords(style, ctx);
        CssValue[] v = style.values;

        String fontSize = style.textById(CssProperty.Id.FONT_SIZE);
        float ownFontSize = ctx.parentFontSize();
        if (fontSize != null) {
            ownFontSize = resolveFontSize(fontSize, ctx);
            style.putById(CssProperty.Id.FONT_SIZE, px(ownFontSize));
        }

        if (KeywordMatch.is(style.textById(CssProperty.Id.COLOR), "currentcolor")) {
            putInheritedOrInitial(style, CssProperty.Id.COLOR, ctx);
        }
        String color = style.textById(CssProperty.Id.COLOR);
        if (color == null) color = ComputedStyle.parentValue(ctx.parent(), CssProperty.Id.COLOR);
        if (color == null) color = ComputedStyle.initialOf(CssProperty.Id.COLOR);

        Metrics metrics = unitMetrics(style, ctx, ownFontSize);

        // ビューポート・コンテナ基準の単位を使う値があるか。単位の付いた数値（寸法トークン）を持たない値は使い得ないので、
        // その近道（mayContainDimension）を通った値だけを候補にして、線幅・ブロック寸法の最終処理のあとに候補だけ確かめる
        // （この 2 つは値を単位なしの "0" / "auto" などに書き換え得る）。font-size は px に解いた値で、使わない
        int[] candidates = new int[16];
        int candidateCount = 0;
        for (int id = 0; id < v.length; id++) {
            CssValue cv = v[id];
            if (cv == null || id == CssProperty.Id.FONT_SIZE) continue;
            String done = finishValue(cv, id == CssProperty.Id.LINE_HEIGHT, id == CssProperty.Id.COLOR, OPAQUE_BY_ID[id],
                    ownFontSize, metrics, color);
            if (done != cv.text) {
                cv = CssValue.of(done); // 書き換わった値だけ正規の値にする（同じ文字列なら元の値のまま）
                v[id] = cv;
            }
            if (cv.mayContainDimension()) {
                if (candidateCount < candidates.length) candidates[candidateCount] = id;
                candidateCount++;
            }
        }
        boolean relative = false;
        // 添字を持たない宣言（未登録の名前）。{@code --*} は値を解かない
        if (style.extras != null) {
            for (String key : style.extras.keySet().toArray(new String[0])) {
                if (key.startsWith("--")) continue;
                String value = style.extras.get(key);
                if (value == null) continue;
                String done = finishValue(CssValue.of(value), CssProperty.LINE_HEIGHT.equals(key), CssProperty.COLOR.equals(key),
                        OPAQUE_PROPERTIES.contains(key), ownFontSize, metrics, color);
                style.put(key, done);
                if (!relative && mayContainDimension(done) && ComputedStyle.scanRelativeUnit(done)) relative = true;
            }
            // {@code --*} は値を解かないが、単位の判定には含める（算出スタイルが読む {@code var()} の先の値になり得る）
            if (!relative) {
                for (String value : style.extras.values()) {
                    if (value != null && mayContainDimension(value) && ComputedStyle.scanRelativeUnit(value)) {
                        relative = true;
                        break;
                    }
                }
            }
        }
        computeLineWidths(style);
        computeBlockSizeKeywords(style);
        if (!relative) {
            if (candidateCount > candidates.length) {
                // 候補が多いときは最終の値を全部確かめる
                for (int id = 0; id < v.length && !relative; id++) {
                    if (v[id] != null && v[id].usesRelativeUnit()) relative = true;
                }
            } else {
                for (int k = 0; k < candidateCount && !relative; k++) {
                    CssValue finalValue = v[candidates[k]];
                    if (finalValue != null && finalValue.usesRelativeUnit()) relative = true;
                }
            }
        }
        style.relativeUnits = (byte) (relative ? 1 : 0);
    }

    /** 1 つの宣言の算出値: {@code line-height} の %・単位の px 化・{@code currentcolor} の置き換え。 */
    private static String finishValue(CssValue cv, boolean lineHeight, boolean isColor, boolean opaque,
                                      float fontPx, Metrics metrics, String currentColor) {
        String value = cv.text;
        // 値ごとに覚えた「寸法トークンを持ち得るか」「currentcolor を含むか」で、字句解析・走査を丸ごと省く
        boolean dimension = cv.mayContainDimension();
        boolean currentColorMentioned = cv.mentionsCurrentColor();
        if (lineHeight && value.trim().endsWith("%")) {
            Float pct = parseNumber(value.trim().substring(0, value.trim().length() - 1));
            if (pct != null) {
                value = px(fontPx * pct / 100f);
                dimension = true;
                currentColorMentioned = false;
            }
        }
        if (!opaque && dimension) value = absolutizeUnits(value, metrics);
        if (!isColor && currentColorMentioned) value = replaceCurrentColor(value, currentColor);
        return value;
    }

    /**
     * ブロック方向の寸法（{@code height} / {@code min-height} / {@code max-height}）の
     * {@code min-content} / {@code max-content} / {@code fit-content} は、初期値と同じに振る舞う
     * （CSS Sizing 3 §5.1）。算出値を初期値に直しておけば、レイアウトの側は特別扱いしなくてよい。
     */
    private static void computeBlockSizeKeywords(StyleTable style) {
        CssValue[] v = style.values;
        for (int id : BLOCK_SIZE_IDS) {
            CssValue cv = v[id];
            if (cv == null) continue;
            String value = cv.text;
            if (KeywordMatch.is(value, "min-content") || KeywordMatch.is(value, "max-content")
                    || KeywordMatch.is(value, "fit-content") || KeywordMatch.trimmedStartsWithIgnoreCase(value, "fit-content(")) {
                v[id] = id == CssProperty.Id.MAX_HEIGHT ? V_NONE : V_AUTO;
            }
        }
    }

    private static final CssValue V_NONE = CssValue.of("none"), V_AUTO = CssValue.of("auto"), V_ZERO = CssValue.of("0"),
            V_1PX = CssValue.of("1px"), V_3PX = CssValue.of("3px"), V_5PX = CssValue.of("5px");

    private static final int[] BLOCK_SIZE_IDS = {CssProperty.Id.HEIGHT, CssProperty.Id.MIN_HEIGHT, CssProperty.Id.MAX_HEIGHT};

    /**
     * 値の中の単位を px にするときの基準。{@code lh} はこの要素の行の高さ（数値なら font-size 倍、{@code normal} は
     * ビットマップフォントの行間込みの高さ）、{@code rlh} はルートの {@code normal}。
     */
    private static Metrics unitMetrics(StyleTable style, Context ctx, float ownFontSize) {
        String declared = trimmed(style.textById(CssProperty.Id.LINE_HEIGHT));
        if (declared == null) declared = trimmed(ComputedStyle.parentValue(ctx.parent(), CssProperty.Id.LINE_HEIGHT));
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

    /** 線種と幅の添字の組: 四辺の枠線・アウトライン・段の区切り線。 */
    private static final int[][] LINE_PAIRS = {
            {CssProperty.Id.BORDER_TOP_STYLE, CssProperty.Id.BORDER_TOP_WIDTH},
            {CssProperty.Id.BORDER_RIGHT_STYLE, CssProperty.Id.BORDER_RIGHT_WIDTH},
            {CssProperty.Id.BORDER_BOTTOM_STYLE, CssProperty.Id.BORDER_BOTTOM_WIDTH},
            {CssProperty.Id.BORDER_LEFT_STYLE, CssProperty.Id.BORDER_LEFT_WIDTH},
            {CssProperty.Id.OUTLINE_STYLE, CssProperty.Id.OUTLINE_WIDTH},
            {CssProperty.Id.COLUMN_RULE_STYLE, CssProperty.Id.COLUMN_RULE_WIDTH}};

    /**
     * {@code border-*-width} / {@code outline-width} の算出値。線種が {@code none} / {@code hidden} なら 0、
     * 線種があって幅が書かれていなければ初期値の {@code medium}（3px）。枠線の幅のキーワードは
     * CSS Backgrounds 3 §3.3 の UA の推奨値（thin 1px / medium 3px / thick 5px）。
     *
     * 何が壊れていたか: 以前は幅を線種と無関係に読んでいたため、{@code border-width: 2px} だけの箱に
     * 線種なしの枠が付き（ブラウザでは 0）、{@code border-style: solid} だけの箱には枠が付かなかった
     * （ブラウザでは 3px）。
     */
    private static void computeLineWidths(StyleTable style) {
        CssValue[] v = style.values;
        for (int[] pair : LINE_PAIRS) {
            int widthId = pair[1];
            CssValue styleValue = v[pair[0]];
            String lineStyle = styleValue != null ? styleValue.text : null;
            if (lineStyle == null || KeywordMatch.is(lineStyle, "none") || KeywordMatch.is(lineStyle, "hidden")) {
                if (v[widthId] != null) v[widthId] = V_ZERO;
                continue;
            }
            CssValue widthValue = v[widthId];
            if (widthValue == null) {
                style.putValueById(widthId, V_3PX);
                continue;
            }
            String width = widthValue.text;
            if (width.isBlank()) continue;
            if (KeywordMatch.is(width, "thin")) v[widthId] = V_1PX;
            else if (KeywordMatch.is(width, "medium")) v[widthId] = V_3PX;
            else if (KeywordMatch.is(width, "thick")) v[widthId] = V_5PX;
        }
    }

    private static void resolveCssWideKeywords(StyleTable style, Context ctx) {
        CssValue[] v = style.values;
        for (int id = 0; id < v.length; id++) {
            CssValue cv = v[id];
            if (cv == null) continue;
            CssWideKeyword kw = cv.wide(); // 値ごとに 1 回だけ判定して覚える
            if (kw == null) continue;
            switch (kw) {
                case INHERIT -> putInheritedOrInitial(style, id, ctx);
                case INITIAL -> putInitial(style, id);
                // カスケードで解けなかった revert / revert-layer（UA 自身の revert や var() 経由）は unset と同じ
                case UNSET, REVERT, REVERT_LAYER -> {
                    if (ComputedStyle.isInheritedId(id)) putInheritedOrInitial(style, id, ctx);
                    else putInitial(style, id);
                }
            }
        }
        // 添字を持たない宣言（{@code --*} と未登録の名前）
        if (style.extras == null) return;
        for (String key : style.extras.keySet().toArray(new String[0])) {
            CssWideKeyword kw = CssWideKeyword.of(style.extras.get(key));
            if (kw == null) continue;
            switch (kw) {
                case INHERIT -> putInheritedOrInitial(style, key, ctx);
                case INITIAL -> putInitial(style, key);
                case UNSET, REVERT, REVERT_LAYER -> {
                    if (PropertyRegistry.isInherited(key) || key.startsWith("--")) putInheritedOrInitial(style, key, ctx);
                    else putInitial(style, key);
                }
            }
        }
    }

    private static void putInheritedOrInitial(StyleTable style, int id, Context ctx) {
        CssValue parentValue = ComputedStyle.parentCssValue(ctx.parent(), id);
        if (parentValue != null) style.putValueById(id, parentValue);
        else putInitial(style, id);
    }

    private static void putInitial(StyleTable style, int id) {
        style.putValueById(id, ComputedStyle.initialValueOf(id));
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
        // 寸法のトークン（数値の直後に単位）が 1 つも無い値（キーワード・色・単位なしの数値）は、字句解析も直列化もしない
        if (!mayContainDimension(value)) return value;
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

    /**
     * 値に寸法のトークン（数値の直後に識別子が続く形）があり得るか。偽なら確実に無い（{@code 12px} の {@code 2p} のように、
     * 数字の直後に英字・{@code _}・{@code -}・{@code \}・非 ASCII が来る所が無い）。真は「あるかもしれない」。
     */
    static boolean mayContainDimension(String value) {
        for (int i = 0, n = value.length() - 1; i < n; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') continue;
            char d = value.charAt(i + 1);
            if ((d >= 'a' && d <= 'z') || (d >= 'A' && d <= 'Z') || d == '_' || d == '-' || d == '\\' || d > 127) return true;
        }
        return false;
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
    static String replaceCurrentColor(String value, String color) {
        if (color == null || !containsIgnoreCase(value, "currentcolor")) return value;
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

    /** {@code value.toLowerCase().contains(lowerNeedle)} と同じ（新しい文字列を作らない）。 */
    private static boolean containsIgnoreCase(String value, String lowerNeedle) {
        int m = lowerNeedle.length();
        for (int i = 0, last = value.length() - m; i <= last; i++) {
            if (value.regionMatches(true, i, lowerNeedle, 0, m)) return true;
        }
        return false;
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
        return CssNumbers.px(value);
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
            // 宣言ごと・算出のたびに全値へ呼ばれる。トリムの位置を 1 回だけ数え、長さで候補を絞ってから照合する（文字列は作らない）
            int s = 0, e = value.length();
            while (s < e && value.charAt(s) <= ' ') s++;
            while (e > s && value.charAt(e - 1) <= ' ') e--;
            return switch (e - s) {
                case 5 -> value.regionMatches(true, s, "unset", 0, 5) ? UNSET : null;
                case 6 -> value.regionMatches(true, s, "revert", 0, 6) ? REVERT : null;
                case 7 -> value.regionMatches(true, s, "inherit", 0, 7) ? INHERIT
                        : value.regionMatches(true, s, "initial", 0, 7) ? INITIAL : null;
                case 12 -> value.regionMatches(true, s, "revert-layer", 0, 12) ? REVERT_LAYER : null;
                default -> null;
            };
        }
    }
}
