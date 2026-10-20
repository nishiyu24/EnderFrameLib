package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.BitmapFontFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 長さの解決と、{@code calc()} などの数式（CSS Values 4 §10）の評価。
 *
 * 何が壊れていたか: 以前は長さを正規表現（{@code NUMBER_WITH_UNIT}）で読み、数式は文字を 1 つずつ読む
 * 自前の再帰下降だった。{@code round()} / {@code mod()} / 三角関数のような Level 4 の関数は読めず、
 * 引数の型（長さ・数・角度）も区別しないので、{@code calc(10px * 2px)} のような不正な式も値になっていた。
 * ここでは値を {@link CssSyntax} のコンポーネント値として読み、式は型（数・長さ・角度・時間・解像度）を
 * 持った量として評価する。型が合わない式は無効（0）になる。
 */
public class LengthResolver {

    /**
     * 数式の結果が無限大になったときに丸める先（CSS Values 4 §10.9 は「表せる最大の値」に丸めるとする）。
     * Chromium の LayoutUnit の上限に合わせた。
     */
    static final float LARGEST_LENGTH = 33554428f;

    /** 字形を測れないときの {@code ch} / {@code ex} の em に対する比（CSS Values 4 §6.1.1 の 0.5em）。 */
    static final float GLYPH_FALLBACK_EM = 0.5f;

    /** ビットマップフォントの大文字の高さ（em 比）。{@code cap} の値。 */
    static final float CAP_HEIGHT_EM = BitmapFontFace.MC_ASCENT / BitmapFontFace.MC_UNITS_PER_EM;

    /** {@code line-height: normal} の行の高さ（em 比）。{@code lh} の既定値。 */
    static final float NORMAL_LINE_HEIGHT_EM =
            (BitmapFontFace.MC_ASCENT + BitmapFontFace.MC_DESCENT) / BitmapFontFace.MC_UNITS_PER_EM
                    + BitmapFontFace.MC_LINE_GAP_RATIO;

    /**
     * 単位を px に直すときの文脈。
     *
     * @param percentBase   {@code %} の基準（px）。NaN なら {@code %} は長さにせず割合として扱う
     * @param fontSize      要素のフォントサイズ（px）。{@code em} の基準
     * @param rootFontSize  ルート要素のフォントサイズ（px）。{@code rem} の基準
     * @param lineHeight    要素の行の高さ（px）。{@code lh} の基準
     * @param rootLineHeight ルート要素の行の高さ（px）。{@code rlh} の基準
     * @param containerW    問い合わせコンテナの幅（px）。コンテナが無ければ NaN（{@code cq*} はビューポートに戻る）
     * @param containerH    問い合わせコンテナの高さ（px）。同上
     */
    public record Metrics(float percentBase, float rootW, float rootH, float fontSize, float rootFontSize,
                          float lineHeight, float rootLineHeight, float containerW, float containerH) {

        /** 行の高さとコンテナを知らない文脈（{@code lh} は {@code normal} の比、{@code cq*} はビューポート）。 */
        public static Metrics of(float percentBase, float rootW, float rootH, float fontSize, float rootFontSize) {
            return new Metrics(percentBase, rootW, rootH, fontSize, rootFontSize,
                    fontSize * NORMAL_LINE_HEIGHT_EM, rootFontSize * NORMAL_LINE_HEIGHT_EM, Float.NaN, Float.NaN);
        }

        Metrics withPercentBase(float base) {
            return new Metrics(base, rootW, rootH, fontSize, rootFontSize, lineHeight, rootLineHeight,
                    containerW, containerH);
        }
    }

    // ==========================================
    // 長さの文字列 → px
    // ==========================================

    /**
     * 長さの文字列をピクセルに解く。結果は丸めない。
     *
     * 何が壊れていたか: 以前は各単位の結果を int に切り捨てていたため、{@code 33.33%} や
     * {@code 1.5em} が端数を落とし、親子で積み重なるほどずれた（軸 3）。丸めは描画の 1 箇所だけ。
     */
    public static float parseDim(String val, float maxPixels, float rootW, float rootH, float elementFontSize, float rootFontSize) {
        return parseDim(val, Metrics.of(maxPixels, rootW, rootH, elementFontSize, rootFontSize));
    }

    // 後方互換用（elementFontSize, rootFontSize が不明な場合は 16.0f を仮定）
    public static float parseDim(String val, float maxPixels, float rootW, float rootH) {
        return parseDim(val, maxPixels, rootW, rootH, 16.0f, 16.0f);
    }

    public static float parseDim(String val, Metrics metrics) {
        if (val == null) return 0;
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(val));
        return parseDim(values, metrics);
    }

    /** 失敗・{@code auto}・長さでない値は 0（意図的なフォールバック）。 */
    static float parseDim(List<ComponentValue> values, Metrics metrics) {
        if (values.size() != 1) return 0;
        ComponentValue only = values.getFirst();
        if (only instanceof CssSyntax.Function f) {
            Quantity q = MathExpression.evaluate(f, metrics);
            return q == null ? 0 : sanitize(q.toLengthPixels());
        }
        Token t = CssSyntax.tokenOf(only);
        if (t == null) return 0;
        float px = tokenToPixels(t, metrics);
        return Float.isNaN(px) ? 0 : px;
    }

    /** 数値・寸法・割合のトークンを px にする。長さにならないトークンは NaN。 */
    private static float tokenToPixels(Token t, Metrics m) {
        return switch (t.type()) {
            case NUMBER -> (float) t.number();
            case PERCENTAGE -> m.percentBase() * (float) t.number() / 100f;
            case DIMENSION -> toPixels((float) t.number(), t.unit(), m);
            default -> Float.NaN;
        };
    }

    /**
     * 数値 1 つを単位に従って px に換算する。知らない単位なら NaN。
     *
     * 何が壊れていたか: 以前は px / % / vw / vh / em / rem しか知らず、{@code 50vmin} は通常の長さでは 0、
     * {@code calc()} の中では単位を落として 50px になっていた（{@code vmin} は末尾の判定で {@code in} にも化けうる）。
     */
    static float toPixels(float n, String unit, float percentBase, float rootW, float rootH,
                          float elementFontSize, float rootFontSize) {
        return toPixels(n, unit, Metrics.of(percentBase, rootW, rootH, elementFontSize, rootFontSize));
    }

    static float toPixels(float n, String unit, Metrics m) {
        String u = unit.toLowerCase(Locale.ROOT);
        if (u.isEmpty()) return n;
        if ("%".equals(u)) return m.percentBase() * n / 100f;
        float perUnit = lengthUnitInPixels(u, m);
        return Float.isNaN(perUnit) ? Float.NaN : n * perUnit;
    }

    /**
     * 長さの単位 1 つぶんの px。長さの単位でなければ NaN。
     *
     * ビューポートは動的なツールバーを持たないので、{@code sv*} / {@code lv*} / {@code dv*} は {@code v*} と同じ。
     * {@code cq*} はコンテナの寸法（{@code %} ではなく 1% = 1 単位）で、コンテナが無ければ小ビューポート単位に戻る。
     * LIMITATION: {@code ch} / {@code ex} は字形を測らず 0.5em で近似する（R7 のフォント指標を待つ）。README の "Not supported"
     */
    static float lengthUnitInPixels(String unit, Metrics m) {
        return switch (unit) {
            case "px" -> 1f;
            case "vw", "svw", "lvw", "dvw", "vi", "svi", "lvi", "dvi" -> m.rootW() / 100f;
            case "vh", "svh", "lvh", "dvh", "vb", "svb", "lvb", "dvb" -> m.rootH() / 100f;
            case "vmin", "svmin", "lvmin", "dvmin" -> Math.min(m.rootW(), m.rootH()) / 100f;
            case "vmax", "svmax", "lvmax", "dvmax" -> Math.max(m.rootW(), m.rootH()) / 100f;
            case "cqw", "cqi" -> orElse(m.containerW(), m.rootW()) / 100f;
            case "cqh", "cqb" -> orElse(m.containerH(), m.rootH()) / 100f;
            case "cqmin" -> Math.min(orElse(m.containerW(), m.rootW()), orElse(m.containerH(), m.rootH())) / 100f;
            case "cqmax" -> Math.max(orElse(m.containerW(), m.rootW()), orElse(m.containerH(), m.rootH())) / 100f;
            case "em" -> m.fontSize();
            case "rem" -> m.rootFontSize();
            case "lh" -> m.lineHeight();
            case "rlh" -> m.rootLineHeight();
            case "cap" -> m.fontSize() * CAP_HEIGHT_EM;
            case "ic" -> m.fontSize();
            case "ch", "ex" -> m.fontSize() * GLYPH_FALLBACK_EM;
            default -> absoluteToPixels(1f, unit);
        };
    }

    private static float orElse(float value, float fallback) {
        return Float.isNaN(value) ? fallback : value;
    }

    /** 絶対長さ（1in = 96px、CSS Values 4 §6.2）。絶対長さの単位でなければ NaN。 */
    static float absoluteToPixels(float n, String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "", "px" -> n;
            case "pt" -> n * 96f / 72f;
            case "pc" -> n * 16f;
            case "in" -> n * 96f;
            case "cm" -> n * 96f / 2.54f;
            case "mm" -> n * 96f / 25.4f;
            case "q" -> n * 96f / 101.6f;
            default -> Float.NaN;
        };
    }

    /** NaN は 0、無限大は表せる最大の長さに丸める（CSS Values 4 §10.9）。 */
    private static float sanitize(double v) {
        if (Double.isNaN(v)) return 0;
        if (v > LARGEST_LENGTH) return LARGEST_LENGTH;
        if (v < -LARGEST_LENGTH) return -LARGEST_LENGTH;
        return (float) v;
    }

    // ==========================================
    // 1 回だけ分解しておく長さ表現
    // ==========================================

    /** 長さの単位。 */
    public enum Unit {
        /** px、絶対長さ（pt / in など。分解の時点で px に直す）、または単位なしの数値 */
        PIXELS,
        /** % （親の対応する寸法が基準） */
        PERCENT,
        VW,
        VH,
        VMIN,
        VMAX,
        /** em（ch / ex / lh / cap は分解の時点で em に直す） */
        EM,
        REM,
        /** auto / 不正値（0 として扱う） */
        NONE,
        /** calc() / clamp() など、解決時に式を評価する必要があるもの */
        EXPRESSION
    }

    /**
     * 「単位と数値への分解」を済ませた長さ。
     * ComputedStyle がプロパティごとに 1 つ保持し、参照のたびに文字列を再パースしないようにする。
     * EXPRESSION だけは式の評価が必要なので、読み終えた式（コンポーネント値）を保持して解決時に評価する。
     */
    public record CompiledLength(Unit unit, float value, String raw, ComponentValue expression) {

        public static final CompiledLength ZERO = new CompiledLength(Unit.NONE, 0f, null, null);

        public CompiledLength(Unit unit, float value, String raw) {
            this(unit, value, raw, null);
        }

        public float resolve(float maxPixels, float rootW, float rootH, float elementFontSize, float rootFontSize) {
            return switch (unit) {
                case PIXELS -> value;
                case PERCENT -> maxPixels * value / 100f;
                case VW -> rootW * value / 100f;
                case VH -> rootH * value / 100f;
                case VMIN -> Math.min(rootW, rootH) * value / 100f;
                case VMAX -> Math.max(rootW, rootH) * value / 100f;
                case EM -> elementFontSize * value;
                case REM -> rootFontSize * value;
                case EXPRESSION -> parseDim(List.of(expression),
                        Metrics.of(maxPixels, rootW, rootH, elementFontSize, rootFontSize));
                case NONE -> 0;
            };
        }
    }

    /** 長さの文字列を単位と数値に分解する。失敗や auto は {@link CompiledLength#ZERO}。 */
    public static CompiledLength compile(String val) {
        if (val == null) return CompiledLength.ZERO;
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(val));
        if (values.size() != 1) return CompiledLength.ZERO;

        // calc() / clamp() / min() / max() などは式の評価が必要
        if (values.getFirst() instanceof CssSyntax.Function f) {
            return new CompiledLength(Unit.EXPRESSION, 0f, val.trim(), f);
        }
        Token t = CssSyntax.tokenOf(values.getFirst());
        if (t == null) return CompiledLength.ZERO;
        String raw = val.trim();
        float n = (float) t.number();
        return switch (t.type()) {
            case NUMBER -> new CompiledLength(Unit.PIXELS, n, raw);
            case PERCENTAGE -> new CompiledLength(Unit.PERCENT, n, raw);
            case DIMENSION -> compileDimension(n, t.unit().toLowerCase(Locale.ROOT), raw);
            default -> CompiledLength.ZERO;
        };
    }

    private static CompiledLength compileDimension(float n, String unit, String raw) {
        switch (unit) {
            case "vw", "svw", "lvw", "dvw", "vi", "svi", "lvi", "dvi":
                return new CompiledLength(Unit.VW, n, raw);
            case "vh", "svh", "lvh", "dvh", "vb", "svb", "lvb", "dvb":
                return new CompiledLength(Unit.VH, n, raw);
            case "vmin", "svmin", "lvmin", "dvmin":
                return new CompiledLength(Unit.VMIN, n, raw);
            case "vmax", "svmax", "lvmax", "dvmax":
                return new CompiledLength(Unit.VMAX, n, raw);
            case "em", "ic":
                return new CompiledLength(Unit.EM, n, raw);
            case "rem":
                return new CompiledLength(Unit.REM, n, raw);
            case "ch", "ex":
                return new CompiledLength(Unit.EM, n * GLYPH_FALLBACK_EM, raw);
            case "cap":
                return new CompiledLength(Unit.EM, n * CAP_HEIGHT_EM, raw);
            case "lh":
                return new CompiledLength(Unit.EM, n * NORMAL_LINE_HEIGHT_EM, raw);
            case "rlh":
                return new CompiledLength(Unit.REM, n * NORMAL_LINE_HEIGHT_EM, raw);
            default:
                // cq* はコンテナを知らない文脈ではビューポート単位に戻る（算出値の段で px になっていれば、ここには来ない）
                if (unit.startsWith("cq")) {
                    return compileDimension(n, unit.equals("cqw") || unit.equals("cqi") ? "vw"
                            : unit.equals("cqh") || unit.equals("cqb") ? "vh"
                            : unit.equals("cqmin") ? "vmin" : unit.equals("cqmax") ? "vmax" : "?", raw);
                }
                float px = absoluteToPixels(n, unit);
                return Float.isNaN(px) ? CompiledLength.ZERO : new CompiledLength(Unit.PIXELS, px, raw);
        }
    }

    /** 空白区切りの 1〜4 値を上右下左に配る（{@code margin} などの 4 辺の規則）。 */
    public static String[] parseBoxEdgesStr(String val) {
        if (val == null) return new String[]{"0", "0", "0", "0"};
        List<String> parts = new ArrayList<>();
        for (List<ComponentValue> part : CssSyntax.splitOnWhitespace(CssSyntax.parseComponentValues(val))) {
            parts.add(CssSyntax.serialize(part));
        }
        String[] edges = new String[4];
        if (parts.size() == 1) {
            edges[0] = edges[1] = edges[2] = edges[3] = parts.get(0);
        } else if (parts.size() == 2) {
            edges[0] = edges[2] = parts.get(0);
            edges[1] = edges[3] = parts.get(1);
        } else if (parts.size() == 3) {
            edges[0] = parts.get(0);
            edges[1] = edges[3] = parts.get(1);
            edges[2] = parts.get(2);
        } else if (parts.size() >= 4) {
            edges[0] = parts.get(0);
            edges[1] = parts.get(1);
            edges[2] = parts.get(2);
            edges[3] = parts.get(3);
        }
        return edges;
    }

    private static final String[] CORNER_PROPERTIES = {
            CssProperty.BORDER_TOP_LEFT_RADIUS, CssProperty.BORDER_TOP_RIGHT_RADIUS,
            CssProperty.BORDER_BOTTOM_RIGHT_RADIUS, CssProperty.BORDER_BOTTOM_LEFT_RADIUS};

    /**
     * 四隅の半径（左上・右上・右下・左下の順）を角ごとのロングハンドから解く。
     * 横の半径の % は箱の幅、縦の半径の % は箱の高さが基準。
     *
     * 何が壊れていたか: 以前は描画が {@code border-radius} の文字列だけを読んでいたので、
     * {@code border-top-left-radius} が無視され、{@code border-radius} の後に 1 角だけ上書きすることもできなかった。
     *
     * LIMITATION: 角は円弧で描くので、楕円の角（{@code 10px / 20px}・長方形の箱の {@code 50%}）は
     * 横と縦の半径の小さい方の円で近似する。README の "Not supported"。
     */
    public static float[] cornerRadii(ComputedStyle style, float w, float h, float rootW, float rootH) {
        float[] out = new float[4];
        String shorthand = null;
        for (int i = 0; i < 4; i++) {
            String value = style.get(CORNER_PROPERTIES[i]);
            if (value == null) {
                // 展開を通っていない算出スタイル（テストや埋め込み側が直に組んだもの）
                if (shorthand == null) {
                    shorthand = style.getOrDefault(CssProperty.BORDER_RADIUS, "0");
                }
                value = ShorthandExpanders.borderRadius(shorthand).getOrDefault(CORNER_PROPERTIES[i], "0");
            }
            List<String> parts = ShorthandExpanders.splitTopLevelTokens(value);
            if (parts.isEmpty()) continue;
            float rx = parseDim(parts.get(0), w, rootW, rootH, style.fontSizePx(), style.rootFontSizePx());
            float ry = parts.size() > 1
                    ? parseDim(parts.get(1), h, rootW, rootH, style.fontSizePx(), style.rootFontSizePx())
                    : parseDim(parts.get(0), h, rootW, rootH, style.fontSizePx(), style.rootFontSizePx());
            out[i] = Math.max(0, Math.min(rx, ry));
        }
        return out;
    }

    // ==========================================
    // 数式（CSS Values 4 §10）
    // ==========================================

    /** 量の次元。{@code PERCENT} は割合のまま扱う文脈（{@code %} を長さにしない）でだけ現れる。 */
    public enum Dimension { NUMBER, LENGTH, PERCENT, ANGLE, TIME, RESOLUTION }

    /**
     * 数式の値。{@code value} の単位は、長さが px、角度が度、時間が秒、解像度が dppx、数と割合はそのまま。
     */
    public record Quantity(double value, Dimension dimension) {

        /** 長さの文脈で使う px。数は px 扱い（従来の挙動）で、他の次元は長さにならない。 */
        double toLengthPixels() {
            return dimension == Dimension.LENGTH || dimension == Dimension.NUMBER ? value : Double.NaN;
        }
    }

    /** 数式の文字列を評価する。式でない、型が合わない、読めないときは null。 */
    public static Quantity evaluateMath(String text, Metrics metrics) {
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(text == null ? "" : text));
        if (values.size() != 1 || !(values.getFirst() instanceof CssSyntax.Function f)) return null;
        return MathExpression.evaluate(f, metrics);
    }

    /**
     * 名前（{@code r} / {@code h} など）を数として読める数式を評価する。{@code %} は長さにせず割合（{@code PERCENT}）のまま返す。
     * 色の相対構文の成分に使う。式でない・型が合わないときは null。
     */
    static Quantity evaluateMath(CssSyntax.Function function, Map<String, Double> keywords) {
        Metrics typed = new Metrics(Float.NaN, 0, 0, 16, 16, 0, 0, Float.NaN, Float.NaN);
        return MathExpression.evaluate(function, typed, keywords);
    }

    /** 長さの単位か（{@code px}・{@code em}・{@code vw}・{@code cqw}・{@code mm} など）。 */
    public static boolean isLengthUnit(String unit) {
        return !Float.isNaN(lengthUnitInPixels(unit.toLowerCase(Locale.ROOT), Metrics.of(0, 0, 0, 16, 16)));
    }

    /** 角度の値を度にする。角度の単位でなければ null。 */
    static Double angleDegrees(double n, String unit) {
        return switch (unit.toLowerCase(Locale.ROOT)) {
            case "deg" -> n;
            case "grad" -> n * 0.9;
            case "rad" -> Math.toDegrees(n);
            case "turn" -> n * 360.0;
            default -> null;
        };
    }

    /** {@code calc()} などの数式の関数名か。 */
    public static boolean isMathFunction(String name) {
        return MathExpression.isMathFunction(name.toLowerCase(Locale.ROOT));
    }

    /**
     * {@code calc()} / {@code min()} / {@code max()} / {@code clamp()} と、Level 4 の
     * {@code round()} / {@code mod()} / {@code rem()} / 三角関数 / {@code pow()} / {@code sqrt()} / {@code hypot()} /
     * {@code log()} / {@code exp()} / {@code abs()} / {@code sign()} の評価器。
     *
     * 引数は {@code <calc-sum>}（{@code + - * /} と括弧、入れ子の関数、定数 {@code pi} / {@code e} /
     * {@code infinity} / {@code NaN}）。{@code +} と {@code -} は前後に空白が要る（トークンの規則そのもの）。
     * 0 除算は IEEE の無限大・NaN のまま返し、呼び出し側（{@link #parseDim}）が丸める。
     */
    private static final class MathExpression {

        private final Metrics metrics;
        /** 式の中で数として読む名前（相対色の {@code r} {@code g} {@code b} {@code h} など）。 */
        private final Map<String, Double> keywords;

        private MathExpression(Metrics metrics, Map<String, Double> keywords) {
            this.metrics = metrics;
            this.keywords = keywords;
        }

        static Quantity evaluate(CssSyntax.Function function, Metrics metrics) {
            return new MathExpression(metrics, Map.of()).function(function);
        }

        static Quantity evaluate(CssSyntax.Function function, Metrics metrics, Map<String, Double> keywords) {
            return new MathExpression(metrics, keywords).function(function);
        }

        static boolean isMathFunction(String name) {
            return switch (name) {
                case "calc", "min", "max", "clamp", "round", "mod", "rem", "sin", "cos", "tan", "asin", "acos",
                     "atan", "atan2", "pow", "sqrt", "hypot", "log", "exp", "abs", "sign", "-webkit-calc",
                     "-moz-calc" -> true;
                default -> false;
            };
        }

        // ---- 関数

        private Quantity function(CssSyntax.Function f) {
            String name = f.name().toLowerCase(Locale.ROOT);
            if (!isMathFunction(name)) return null;
            List<List<ComponentValue>> args = CssSyntax.split(f.args(), Type.COMMA);
            return switch (name) {
                case "calc", "-webkit-calc", "-moz-calc" -> args.size() == 1 ? sum(args.getFirst()) : null;
                case "min", "max" -> minMax(name, args);
                case "clamp" -> clamp(args);
                case "round" -> round(args);
                case "mod", "rem" -> modRem(name, args);
                case "sin", "cos", "tan" -> trig(name, args);
                case "asin", "acos", "atan" -> inverseTrig(name, args);
                case "atan2" -> atan2(args);
                case "pow" -> pow(args);
                case "sqrt" -> sqrt(args);
                case "hypot" -> hypot(args);
                case "log" -> log(args);
                case "exp" -> exp(args);
                case "abs" -> unary(args, q -> new Quantity(Math.abs(q.value()), q.dimension()));
                case "sign" -> unary(args, q -> new Quantity(Math.signum(q.value()), Dimension.NUMBER));
                default -> null;
            };
        }

        private List<Quantity> evaluateAll(List<List<ComponentValue>> args) {
            List<Quantity> out = new ArrayList<>();
            for (List<ComponentValue> arg : args) {
                Quantity q = sum(arg);
                if (q == null) return null;
                out.add(q);
            }
            return out;
        }

        private static boolean sameDimension(List<Quantity> quantities) {
            for (Quantity q : quantities) {
                if (q.dimension() != quantities.getFirst().dimension()) return false;
            }
            return true;
        }

        private Quantity minMax(String name, List<List<ComponentValue>> args) {
            List<Quantity> q = evaluateAll(args);
            if (q == null || q.isEmpty() || !sameDimension(q)) return null;
            double result = q.getFirst().value();
            for (Quantity next : q) {
                if (Double.isNaN(next.value())) return new Quantity(Double.NaN, q.getFirst().dimension());
                result = name.equals("min") ? Math.min(result, next.value()) : Math.max(result, next.value());
            }
            return new Quantity(result, q.getFirst().dimension());
        }

        /** {@code clamp(MIN, VAL, MAX)} は {@code max(MIN, min(VAL, MAX))}。{@code none} は片側を外す。 */
        private Quantity clamp(List<List<ComponentValue>> args) {
            if (args.size() != 3) return null;
            Quantity low = isNone(args.get(0)) ? null : sum(args.get(0));
            Quantity value = sum(args.get(1));
            Quantity high = isNone(args.get(2)) ? null : sum(args.get(2));
            if (value == null || (low == null && !isNone(args.get(0))) || (high == null && !isNone(args.get(2)))) {
                return null;
            }
            if ((low != null && low.dimension() != value.dimension())
                    || (high != null && high.dimension() != value.dimension())) {
                return null;
            }
            double result = value.value();
            if (high != null) result = Math.min(result, high.value());
            if (low != null) result = Math.max(low.value(), result);
            return new Quantity(result, value.dimension());
        }

        private static boolean isNone(List<ComponentValue> arg) {
            List<ComponentValue> t = CssSyntax.trim(arg);
            return t.size() == 1 && CssSyntax.tokenOf(t.getFirst()) != null
                    && CssSyntax.tokenOf(t.getFirst()).isIdent("none");
        }

        /** {@code round(<strategy>?, A, B?)}。B は A が数のときだけ省ける（1）。 */
        private Quantity round(List<List<ComponentValue>> args) {
            String strategy = "nearest";
            List<List<ComponentValue>> rest = args;
            if (!args.isEmpty() && args.getFirst().size() == 1 && CssSyntax.tokenOf(args.getFirst().getFirst()) != null
                    && CssSyntax.tokenOf(args.getFirst().getFirst()).is(Type.IDENT)) {
                String word = CssSyntax.tokenOf(args.getFirst().getFirst()).value().toLowerCase(Locale.ROOT);
                if (word.equals("nearest") || word.equals("up") || word.equals("down") || word.equals("to-zero")) {
                    strategy = word;
                    rest = args.subList(1, args.size());
                }
            }
            List<Quantity> q = evaluateAll(rest);
            if (q == null || q.isEmpty() || q.size() > 2) return null;
            Quantity a = q.getFirst();
            Quantity b = q.size() == 2 ? q.get(1) : new Quantity(1, Dimension.NUMBER);
            if (q.size() == 1 && a.dimension() != Dimension.NUMBER) return null;
            if (a.dimension() != b.dimension()) return null;
            return new Quantity(roundTo(strategy, a.value(), b.value()), a.dimension());
        }

        private static double roundTo(String strategy, double a, double step) {
            if (Double.isNaN(a) || Double.isNaN(step) || step == 0 || Double.isInfinite(a) && Double.isInfinite(step)) {
                return Double.NaN;
            }
            if (Double.isInfinite(step)) {
                // 無限大の刻みへの丸め（Values 4 §10.3）。丸め先は 0 か ±無限大
                return switch (strategy) {
                    case "up" -> a > 0 ? Double.POSITIVE_INFINITY : a == 0 ? a : 0.0;
                    case "down" -> a < 0 ? Double.NEGATIVE_INFINITY : a == 0 ? a : 0.0;
                    default -> a == 0 ? a : 0.0;
                };
            }
            if (Double.isInfinite(a)) return a;
            double s = Math.abs(step);
            double q = a / s;
            double rounded = switch (strategy) {
                case "up" -> Math.ceil(q);
                case "down" -> Math.floor(q);
                case "to-zero" -> q < 0 ? Math.ceil(q) : Math.floor(q);
                // nearest は中間値を正の無限大の側へ丸める（Math.round と同じ）
                default -> Math.floor(q + 0.5);
            };
            return rounded * s;
        }

        /** {@code mod()} は割る数と同じ符号、{@code rem()} は割られる数と同じ符号の余り。 */
        private Quantity modRem(String name, List<List<ComponentValue>> args) {
            List<Quantity> q = evaluateAll(args);
            if (q == null || q.size() != 2 || !sameDimension(q)) return null;
            double a = q.get(0).value();
            double b = q.get(1).value();
            double result;
            if (b == 0 || Double.isInfinite(a) || Double.isNaN(a) || Double.isNaN(b)) {
                result = Double.NaN;
            } else if (Double.isInfinite(b)) {
                boolean sameSign = (a < 0) == (b < 0) || a == 0;
                result = name.equals("rem") || sameSign ? a : Double.NaN;
            } else if (name.equals("rem")) {
                result = a % b;
            } else {
                result = a - b * Math.floor(a / b);
            }
            return new Quantity(result, q.get(0).dimension());
        }

        private Quantity trig(String name, List<List<ComponentValue>> args) {
            return unary(args, q -> {
                double radians;
                if (q.dimension() == Dimension.ANGLE) radians = Math.toRadians(q.value());
                else if (q.dimension() == Dimension.NUMBER) radians = q.value();
                else return null;
                double r = switch (name) {
                    case "sin" -> Math.sin(radians);
                    case "cos" -> Math.cos(radians);
                    default -> Math.tan(radians);
                };
                return new Quantity(r, Dimension.NUMBER);
            });
        }

        private Quantity inverseTrig(String name, List<List<ComponentValue>> args) {
            return unary(args, q -> {
                if (q.dimension() != Dimension.NUMBER) return null;
                double radians = switch (name) {
                    case "asin" -> Math.asin(q.value());
                    case "acos" -> Math.acos(q.value());
                    default -> Math.atan(q.value());
                };
                return new Quantity(Math.toDegrees(radians), Dimension.ANGLE);
            });
        }

        private Quantity atan2(List<List<ComponentValue>> args) {
            List<Quantity> q = evaluateAll(args);
            if (q == null || q.size() != 2 || !sameDimension(q) || q.getFirst().dimension() == Dimension.PERCENT) {
                return null;
            }
            return new Quantity(Math.toDegrees(Math.atan2(q.get(0).value(), q.get(1).value())), Dimension.ANGLE);
        }

        private Quantity pow(List<List<ComponentValue>> args) {
            List<Quantity> q = evaluateAll(args);
            if (q == null || q.size() != 2 || q.get(0).dimension() != Dimension.NUMBER
                    || q.get(1).dimension() != Dimension.NUMBER) {
                return null;
            }
            return new Quantity(Math.pow(q.get(0).value(), q.get(1).value()), Dimension.NUMBER);
        }

        private Quantity sqrt(List<List<ComponentValue>> args) {
            return unary(args, q -> q.dimension() == Dimension.NUMBER
                    ? new Quantity(Math.sqrt(q.value()), Dimension.NUMBER) : null);
        }

        private Quantity exp(List<List<ComponentValue>> args) {
            return unary(args, q -> q.dimension() == Dimension.NUMBER
                    ? new Quantity(Math.exp(q.value()), Dimension.NUMBER) : null);
        }

        private Quantity hypot(List<List<ComponentValue>> args) {
            List<Quantity> q = evaluateAll(args);
            if (q == null || q.isEmpty() || !sameDimension(q)) return null;
            double sum = 0;
            for (Quantity next : q) sum += next.value() * next.value();
            return new Quantity(Math.sqrt(sum), q.getFirst().dimension());
        }

        private Quantity log(List<List<ComponentValue>> args) {
            List<Quantity> q = evaluateAll(args);
            if (q == null || q.isEmpty() || q.size() > 2) return null;
            for (Quantity next : q) {
                if (next.dimension() != Dimension.NUMBER) return null;
            }
            double value = q.getFirst().value();
            double result = q.size() == 2 ? Math.log(value) / Math.log(q.get(1).value()) : Math.log(value);
            return new Quantity(result, Dimension.NUMBER);
        }

        private interface Unary {
            Quantity apply(Quantity q);
        }

        private Quantity unary(List<List<ComponentValue>> args, Unary op) {
            if (args.size() != 1) return null;
            Quantity q = sum(args.getFirst());
            return q == null ? null : op.apply(q);
        }

        // ---- 式（和・積・項）

        /** {@code <calc-sum>}。{@code +} / {@code -} は空白で挟まれた DELIM トークン。 */
        private Quantity sum(List<ComponentValue> values) {
            List<ComponentValue> v = CssSyntax.trim(values);
            if (v.isEmpty()) return null;
            int[] cursor = {0};
            Quantity result = product(v, cursor);
            while (result != null) {
                skipWhitespace(v, cursor);
                if (cursor[0] >= v.size()) return result;
                Token op = CssSyntax.tokenOf(v.get(cursor[0]));
                if (op == null || !(op.isDelim('+') || op.isDelim('-'))) return null;
                // 演算子の前後には空白が要る（「10px+5px」は 1 つの数値トークンの並びで、式ではない）
                if (cursor[0] == 0 || !CssSyntax.isWhitespace(v.get(cursor[0] - 1))
                        || cursor[0] + 1 >= v.size() || !CssSyntax.isWhitespace(v.get(cursor[0] + 1))) {
                    return null;
                }
                cursor[0]++;
                Quantity right = product(v, cursor);
                if (right == null) return null;
                result = add(result, right, op.isDelim('-'));
            }
            return null;
        }

        private Quantity product(List<ComponentValue> v, int[] cursor) {
            skipWhitespace(v, cursor);
            Quantity result = term(v, cursor);
            while (result != null) {
                skipWhitespace(v, cursor);
                if (cursor[0] >= v.size()) return result;
                Token op = CssSyntax.tokenOf(v.get(cursor[0]));
                if (op == null || !(op.isDelim('*') || op.isDelim('/'))) return result;
                cursor[0]++;
                skipWhitespace(v, cursor);
                Quantity right = term(v, cursor);
                if (right == null) return null;
                result = op.isDelim('*') ? multiply(result, right) : divide(result, right);
            }
            return null;
        }

        private Quantity term(List<ComponentValue> v, int[] cursor) {
            if (cursor[0] >= v.size()) return null;
            ComponentValue c = v.get(cursor[0]++);
            if (c instanceof CssSyntax.Block b && b.open() == Type.LEFT_PAREN) return sum(b.body());
            if (c instanceof CssSyntax.Function f) return function(f);
            Token t = CssSyntax.tokenOf(c);
            if (t == null) return null;
            return switch (t.type()) {
                case NUMBER -> new Quantity(t.number(), Dimension.NUMBER);
                case PERCENTAGE -> Float.isNaN(metrics.percentBase())
                        ? new Quantity(t.number(), Dimension.PERCENT)
                        : new Quantity(metrics.percentBase() * t.number() / 100.0, Dimension.LENGTH);
                case DIMENSION -> dimension(t);
                case IDENT -> identifier(t.value().toLowerCase(Locale.ROOT));
                default -> null;
            };
        }

        private Quantity identifier(String name) {
            Double bound = keywords.get(name);
            return bound != null ? new Quantity(bound, Dimension.NUMBER) : constant(name);
        }

        private static Quantity constant(String name) {
            return switch (name) {
                case "pi" -> new Quantity(Math.PI, Dimension.NUMBER);
                case "e" -> new Quantity(Math.E, Dimension.NUMBER);
                case "infinity" -> new Quantity(Double.POSITIVE_INFINITY, Dimension.NUMBER);
                case "-infinity" -> new Quantity(Double.NEGATIVE_INFINITY, Dimension.NUMBER);
                case "nan" -> new Quantity(Double.NaN, Dimension.NUMBER);
                default -> null;
            };
        }

        private Quantity dimension(Token t) {
            String unit = t.unit().toLowerCase(Locale.ROOT);
            double n = t.number();
            switch (unit) {
                case "deg": return new Quantity(n, Dimension.ANGLE);
                case "grad": return new Quantity(n * 0.9, Dimension.ANGLE);
                case "rad": return new Quantity(Math.toDegrees(n), Dimension.ANGLE);
                case "turn": return new Quantity(n * 360.0, Dimension.ANGLE);
                case "s": return new Quantity(n, Dimension.TIME);
                case "ms": return new Quantity(n / 1000.0, Dimension.TIME);
                case "dppx", "x": return new Quantity(n, Dimension.RESOLUTION);
                case "dpi": return new Quantity(n / 96.0, Dimension.RESOLUTION);
                case "dpcm": return new Quantity(n * 2.54 / 96.0, Dimension.RESOLUTION);
                default:
                    float perUnit = lengthUnitInPixels(unit, metrics);
                    return Float.isNaN(perUnit) ? null : new Quantity(n * perUnit, Dimension.LENGTH);
            }
        }

        private static void skipWhitespace(List<ComponentValue> v, int[] cursor) {
            while (cursor[0] < v.size() && CssSyntax.isWhitespace(v.get(cursor[0]))) cursor[0]++;
        }

        // ---- 型付きの四則

        private static Quantity add(Quantity a, Quantity b, boolean subtract) {
            if (a.dimension() != b.dimension()) return null;
            return new Quantity(subtract ? a.value() - b.value() : a.value() + b.value(), a.dimension());
        }

        private static Quantity multiply(Quantity a, Quantity b) {
            if (a.dimension() == Dimension.NUMBER) return new Quantity(a.value() * b.value(), b.dimension());
            if (b.dimension() == Dimension.NUMBER) return new Quantity(a.value() * b.value(), a.dimension());
            return null;
        }

        private static Quantity divide(Quantity a, Quantity b) {
            if (b.dimension() == Dimension.NUMBER) return new Quantity(a.value() / b.value(), a.dimension());
            // 同じ次元どうしの商は数（calc(100px / 10px) = 10）
            if (a.dimension() == b.dimension()) return new Quantity(a.value() / b.value(), Dimension.NUMBER);
            return null;
        }
    }
}