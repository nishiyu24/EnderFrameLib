package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.BoxDecorations.Rect;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CSS のグラデーション（CSS Images 3 / 4）: {@code linear-gradient()} / {@code radial-gradient()} /
 * {@code conic-gradient()} と {@code repeating-*}、色の補間のヒント、{@code in <色空間>}（色の補間空間）。
 *
 * 値は {@link CssSyntax} のトークンとして読み、描画側が扱える形（シェーダーの区間 {@link ShaderGradient}）に直す。
 * Minecraft に依存しないので単体テストで形を確かめられる。
 *
 * 何が壊れていたか: 以前は {@code linear-gradient()} だけを文字列の分割で読み、{@code radial-gradient()} /
 * {@code conic-gradient()} / {@code repeating-*} は読めず（背景が描かれない）、色の補間のヒント
 * （色の間に書く位置だけの項）は読み飛ばし、補間は常に乗算済みアルファの sRGB だった。
 */
public final class Gradients {

    private Gradients() {}

    /**
     * 色の停止点 1 つ。位置は未解決の文字列（無ければ null）。2 つ目の位置は {@code red 10% 20%} の形のとき。
     * {@code hint} は色の補間のヒント（{@code color} は使わず、{@code position} が 2 つの停止点の間の中間点）。
     */
    public record ColorStop(int color, String position, String position2, boolean hint) {
        public ColorStop(int color, String position, String position2) {
            this(color, position, position2, false);
        }
    }

    /** グラデーションの共通の持ち物。 */
    public sealed interface Gradient permits LinearGradient, RadialGradient, ConicGradient {
        List<ColorStop> stops();

        /** {@code repeating-*} か。 */
        boolean repeating();

        /** {@code in <space>} で指定した補間空間（{@code "oklch longer hue"} など）。書かれていなければ null（sRGB）。 */
        String colorSpace();
    }

    /**
     * 解いた {@code linear-gradient()}。{@code angle} は度（0 = 上へ、90 = 右へ。時計回り）。
     * {@code to <角>} は箱の縦横比で角度が決まるので {@code cornerX} / {@code cornerY}（−1 / 1、辺なら 0）で持つ。
     */
    public record LinearGradient(double angle, int cornerX, int cornerY, List<ColorStop> stops,
                                 boolean repeating, String colorSpace) implements Gradient {

        public LinearGradient(double angle, int cornerX, int cornerY, List<ColorStop> stops) {
            this(angle, cornerX, cornerY, stops, false, null);
        }

        /** 箱の大きさで決まる角度（度）。 */
        public double angleFor(float w, float h) {
            if (cornerX == 0 || cornerY == 0) return angle;
            // 50% の線が残りの 2 つの角を結ぶ向き（§3.1.1）
            return Math.toDegrees(Math.atan2(cornerX * h, -cornerY * w));
        }
    }

    /**
     * 解いた {@code radial-gradient()}。{@code circle} は円（そうでなければ楕円）。大きさは
     * {@code sizeKeyword}（{@code closest-side} など。明示した長さのときは null）か、{@code radiusX} / {@code radiusY}
     * （未解決の長さ。円は {@code radiusX} だけ）。中心は {@code centerX} / {@code centerY}（未解決の位置の語の並び）。
     */
    public record RadialGradient(boolean circle, String sizeKeyword, String radiusX, String radiusY,
                                 List<String> center, List<ColorStop> stops, boolean repeating,
                                 String colorSpace) implements Gradient {}

    /** 解いた {@code conic-gradient()}。{@code from} は開始角（度）、{@code center} は中心の位置の語の並び。 */
    public record ConicGradient(double from, List<String> center, List<ColorStop> stops, boolean repeating,
                                String colorSpace) implements Gradient {}

    // ==========================================
    // 読む
    // ==========================================

    /** グラデーションの関数（{@code *-gradient()}）を読む。読めなければ null。 */
    public static Gradient parse(String image) {
        if (image == null) return null;
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(image));
        if (values.size() != 1 || !(values.getFirst() instanceof CssSyntax.Function f)) return null;
        String name = f.name().toLowerCase(Locale.ROOT);
        boolean repeating = name.startsWith("repeating-");
        String kind = repeating ? name.substring("repeating-".length()) : name;
        List<List<ComponentValue>> args = CssSyntax.split(f.args(), Type.COMMA);
        if (args.isEmpty()) return null;
        return switch (kind) {
            case "linear-gradient" -> parseLinear(args, repeating);
            case "radial-gradient" -> parseRadial(args, repeating);
            case "conic-gradient" -> parseConic(args, repeating);
            default -> null;
        };
    }

    /**
     * {@code linear-gradient()}（{@code repeating-} 付きを含む）を読む。向きは {@code <angle>}（deg / rad / turn / grad）か
     * {@code to <side-or-corner>}、省略すれば下向き（180deg）。読めなければ（線形でなければ）null。
     */
    public static LinearGradient parseLinear(String image) {
        return parse(image) instanceof LinearGradient linear ? linear : null;
    }

    private static LinearGradient parseLinear(List<List<ComponentValue>> args, boolean repeating) {
        double angle = 180;
        int cornerX = 0, cornerY = 0;
        String space = null;
        int first = 0;
        List<List<ComponentValue>> head = CssSyntax.splitOnWhitespace(args.getFirst());
        if (!head.isEmpty() && !isColorStop(args.getFirst(), false)) {
            first = 1;
            int i = 0;
            Token lead = tokenOfWord(head.get(0));
            if (lead != null && lead.isIdent("to")) {
                int sx = 0, sy = 0;
                for (i = 1; i < head.size(); i++) {
                    Token side = tokenOfWord(head.get(i));
                    if (side == null || side.type() != Type.IDENT) return null;
                    String word = side.value().toLowerCase(Locale.ROOT);
                    if (word.equals("in")) break;
                    switch (word) {
                        case "left" -> sx = -1;
                        case "right" -> sx = 1;
                        case "top" -> sy = -1;
                        case "bottom" -> sy = 1;
                        default -> { return null; }
                    }
                }
                if (sx == 0 && sy == 0) return null;
                if (sx != 0 && sy != 0) {
                    cornerX = sx;
                    cornerY = sy;
                } else {
                    angle = sx > 0 ? 90 : sx < 0 ? 270 : sy < 0 ? 0 : 180;
                }
            } else if (lead != null && lead.type() != Type.IDENT) {
                Double a = angleOf(lead);
                if (a == null) return null;
                angle = a;
                i = 1;
            }
            if (i < head.size()) {
                space = interpolationSpace(head, i);
                if (space == null) return null;
            }
        }
        List<ColorStop> stops = parseStops(args.subList(first, args.size()), false);
        return stops == null ? null : new LinearGradient(angle, cornerX, cornerY, stops, repeating, space);
    }

    private static RadialGradient parseRadial(List<List<ComponentValue>> args, boolean repeating) {
        boolean circle = false;
        boolean shapeGiven = false;
        String sizeKeyword = "farthest-corner";
        String radiusX = null, radiusY = null;
        List<String> center = List.of("center");
        String space = null;
        int first = 0;

        List<List<ComponentValue>> head = CssSyntax.splitOnWhitespace(args.getFirst());
        if (!head.isEmpty() && !isColorStop(args.getFirst(), false)) {
            first = 1;
            List<String> lengths = new ArrayList<>();
            for (int i = 0; i < head.size(); i++) {
                Token t = tokenOfWord(head.get(i));
                String word = t != null && t.type() == Type.IDENT ? t.value().toLowerCase(Locale.ROOT) : null;
                if ("in".equals(word)) {
                    space = interpolationSpace(head, i);
                    if (space == null) return null;
                    break;
                } else if ("at".equals(word)) {
                    center = new ArrayList<>();
                    for (i = i + 1; i < head.size(); i++) {
                        Token p = tokenOfWord(head.get(i));
                        if (p != null && p.isIdent("in")) {
                            space = interpolationSpace(head, i);
                            if (space == null) return null;
                            break;
                        }
                        center.add(CssSyntax.serialize(head.get(i)));
                    }
                    if (center.isEmpty()) return null;
                    break;
                } else if ("circle".equals(word)) {
                    circle = true;
                    shapeGiven = true;
                } else if ("ellipse".equals(word)) {
                    circle = false;
                    shapeGiven = true;
                } else if (word != null && (word.equals("closest-side") || word.equals("closest-corner")
                        || word.equals("farthest-side") || word.equals("farthest-corner"))) {
                    sizeKeyword = word;
                } else if (word == null && t != null || head.get(i).getFirst() instanceof CssSyntax.Function) {
                    lengths.add(CssSyntax.serialize(head.get(i)));
                } else {
                    return null;
                }
            }
            if (!lengths.isEmpty()) {
                // 明示した大きさ: 円は長さ 1 つ、楕円は 2 つ（円で 2 つ・楕円で 1 つは無効）
                if (lengths.size() > 2 || (circle && lengths.size() != 1) || (!circle && shapeGiven && lengths.size() != 2)) {
                    return null;
                }
                if (!shapeGiven) circle = lengths.size() == 1;
                if (!circle && lengths.size() != 2) return null;
                sizeKeyword = null;
                radiusX = lengths.get(0);
                radiusY = lengths.size() > 1 ? lengths.get(1) : null;
            } else if (shapeGiven && circle && sizeKeyword == null) {
                return null;
            }
        }
        List<ColorStop> stops = parseStops(args.subList(first, args.size()), false);
        return stops == null ? null : new RadialGradient(circle, sizeKeyword, radiusX, radiusY, center, stops, repeating, space);
    }

    private static ConicGradient parseConic(List<List<ComponentValue>> args, boolean repeating) {
        double from = 0;
        List<String> center = List.of("center");
        String space = null;
        int first = 0;
        List<List<ComponentValue>> head = CssSyntax.splitOnWhitespace(args.getFirst());
        if (!head.isEmpty() && !isColorStop(args.getFirst(), true)) {
            first = 1;
            for (int i = 0; i < head.size(); i++) {
                Token t = tokenOfWord(head.get(i));
                String word = t != null && t.type() == Type.IDENT ? t.value().toLowerCase(Locale.ROOT) : null;
                if ("from".equals(word)) {
                    if (i + 1 >= head.size()) return null;
                    Double a = angleOf(tokenOfWord(head.get(++i)));
                    if (a == null) return null;
                    from = a;
                } else if ("at".equals(word)) {
                    center = new ArrayList<>();
                    for (i = i + 1; i < head.size(); i++) {
                        Token p = tokenOfWord(head.get(i));
                        if (p != null && p.isIdent("in")) {
                            space = interpolationSpace(head, i);
                            if (space == null) return null;
                            break;
                        }
                        center.add(CssSyntax.serialize(head.get(i)));
                    }
                    if (center.isEmpty()) return null;
                    break;
                } else if ("in".equals(word)) {
                    space = interpolationSpace(head, i);
                    if (space == null) return null;
                    break;
                } else {
                    return null;
                }
            }
        }
        List<ColorStop> stops = parseStops(args.subList(first, args.size()), true);
        return stops == null ? null : new ConicGradient(from, center, stops, repeating, space);
    }

    /** {@code head[from]} が {@code in} の後ろの補間空間（{@code oklch longer hue}）。読めなければ null。 */
    private static String interpolationSpace(List<List<ComponentValue>> head, int inIndex) {
        Token in = tokenOfWord(head.get(inIndex));
        if (in == null || !in.isIdent("in") || inIndex + 1 >= head.size()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = inIndex + 1; i < head.size(); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(CssSyntax.serialize(head.get(i)));
        }
        String text = sb.toString();
        // 空間の名前が読めるか（読めなければグラデーションごと無効）
        try {
            ColorParser.mixer(text).mix(0xFF000000, 0xFFFFFFFF, 0.5);
        } catch (RuntimeException e) {
            return null;
        }
        return text;
    }

    private static Token tokenOfWord(List<ComponentValue> word) {
        return word.size() == 1 ? CssSyntax.tokenOf(word.getFirst()) : null;
    }

    /** 角度（{@code 45deg} / {@code 0.5turn} / {@code 0}）を度にする。角度でなければ null。 */
    private static Double angleOf(Token t) {
        if (t == null) return null;
        if (t.type() == Type.NUMBER && t.number() == 0) return 0.0;
        if (t.type() != Type.DIMENSION) return null;
        return switch (t.unit().toLowerCase(Locale.ROOT)) {
            case "deg" -> t.number();
            case "grad" -> t.number() * 0.9;
            case "rad" -> Math.toDegrees(t.number());
            case "turn" -> t.number() * 360;
            default -> null;
        };
    }

    // ---- 色の停止点

    /** 引数が色の停止点（色を含む）か、向きなどの設定か。 */
    private static boolean isColorStop(List<ComponentValue> arg, boolean angular) {
        for (List<ComponentValue> word : CssSyntax.splitOnWhitespace(arg)) {
            if (word.size() == 1 && ColorParser.isColor(CssSyntax.serialize(word))) return true;
        }
        return false;
    }

    /**
     * 色の停止点のリスト。1 つの引数は {@code <color> [<position> [<position>]]} か、補間のヒント（位置だけ）。
     * 色が 2 つ未満・ヒントが両端や連続にある・読めない語があれば null。
     */
    private static List<ColorStop> parseStops(List<List<ComponentValue>> args, boolean angular) {
        List<ColorStop> stops = new ArrayList<>();
        boolean previousWasHint = true; // 先頭のヒントは無効
        int colors = 0;
        for (List<ComponentValue> arg : args) {
            List<List<ComponentValue>> words = CssSyntax.splitOnWhitespace(arg);
            if (words.isEmpty()) return null;
            Integer color = null;
            List<String> positions = new ArrayList<>(2);
            for (List<ComponentValue> word : words) {
                String text = CssSyntax.serialize(word);
                if (isPosition(word, angular)) {
                    positions.add(text);
                } else if (color == null && word.size() == 1 && ColorParser.isColor(text)) {
                    color = ColorParser.parseColor(text);
                } else {
                    return null;
                }
            }
            if (color == null) {
                // 位置だけの項は色の間のヒント
                if (positions.size() != 1 || previousWasHint) return null;
                stops.add(new ColorStop(0, positions.getFirst(), null, true));
                previousWasHint = true;
                continue;
            }
            if (positions.size() > 2) return null;
            stops.add(new ColorStop(color, positions.isEmpty() ? null : positions.get(0),
                    positions.size() > 1 ? positions.get(1) : null));
            previousWasHint = false;
            colors++;
        }
        if (colors < 2 || previousWasHint) return null; // 末尾のヒントも無効
        return stops;
    }

    private static boolean isPosition(List<ComponentValue> word, boolean angular) {
        if (word.size() != 1) return false;
        ComponentValue v = word.getFirst();
        if (v instanceof CssSyntax.Function f) return LengthResolver.isMathFunction(f.name());
        Token t = CssSyntax.tokenOf(v);
        if (t == null) return false;
        return switch (t.type()) {
            case PERCENTAGE -> true;
            case NUMBER -> t.number() == 0;
            case DIMENSION -> angular ? angleOf(t) != null : angleOf(t) == null && LengthResolver.isLengthUnit(t.unit());
            default -> false;
        };
    }

    // ==========================================
    // 停止点の位置の解決と、線上の色
    // ==========================================

    /** 位置を解いた停止点。{@code hints[k]} は停止点 k と k+1 の間の補間のヒントの位置（無ければ NaN）。 */
    record ResolvedStops(float[] positions, int[] colors, float[] hints) {}

    /**
     * 停止点の位置を、グラデーションの線上の割合に解く（§3.5.1）。位置の無い最初と最後は 0 と 1、
     * 前の停止点より手前の位置は前の位置に揃え、位置の無い中間は前後の位置の間に均等に並べる。
     * 位置が 2 つある停止点は同じ色の停止点 2 つに分ける。ヒントは前後の停止点の間に収める。
     *
     * @param length  位置の長さが割合 1 に当たる px（角度のグラデーションでは使わない）
     * @param angular 位置が角度（conic）か。角度は 360° が割合 1
     */
    static ResolvedStops resolveStops(List<ColorStop> stops, float length, boolean angular, float rootW, float rootH,
                                      float fontSize, float rootFontSize) {
        List<Integer> colors = new ArrayList<>();
        List<Float> raw = new ArrayList<>();
        List<Float> hintAfter = new ArrayList<>();
        for (ColorStop stop : stops) {
            if (stop.hint()) {
                hintAfter.set(hintAfter.size() - 1, stopPosition(stop.position(), length, angular, rootW, rootH, fontSize, rootFontSize));
                continue;
            }
            colors.add(stop.color());
            raw.add(stop.position() == null ? null : stopPosition(stop.position(), length, angular, rootW, rootH, fontSize, rootFontSize));
            hintAfter.add(Float.NaN);
            if (stop.position2() != null) {
                colors.add(stop.color());
                raw.add(stopPosition(stop.position2(), length, angular, rootW, rootH, fontSize, rootFontSize));
                // 1 つの停止点から作った 2 つの間にヒントは入らない（ヒントは元の停止点の後ろ）
                Float hint = hintAfter.get(hintAfter.size() - 1);
                hintAfter.set(hintAfter.size() - 1, Float.NaN);
                hintAfter.add(hint);
            }
        }
        int n = raw.size();
        Float[] p = raw.toArray(new Float[0]);
        if (p[0] == null) p[0] = 0f;
        if (p[n - 1] == null) p[n - 1] = 1f;
        float max = p[0];
        for (int i = 1; i < n; i++) {
            if (p[i] != null) {
                p[i] = Math.max(p[i], max);
                max = p[i];
            }
        }
        float[] positions = new float[n];
        int i = 0;
        while (i < n) {
            if (p[i] != null) {
                positions[i] = p[i];
                i++;
                continue;
            }
            int j = i;
            while (p[j] == null) j++;
            float from = positions[i - 1], to = p[j];
            for (int m = i; m < j; m++) positions[m] = from + (to - from) * (m - i + 1) / (j - i + 1);
            i = j;
        }
        int[] colorArray = new int[n];
        for (int k = 0; k < n; k++) colorArray[k] = colors.get(k);
        float[] hints = new float[Math.max(0, n - 1)];
        for (int k = 0; k < hints.length; k++) {
            float h = hintAfter.get(k);
            hints[k] = Float.isNaN(h) ? Float.NaN : Math.clamp(h, positions[k], positions[k + 1]);
        }
        return new ResolvedStops(positions, colorArray, hints);
    }

    private static float stopPosition(String pos, float length, boolean angular, float rootW, float rootH,
                                      float fontSize, float rootFontSize) {
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(pos));
        Token t = values.size() == 1 ? CssSyntax.tokenOf(values.getFirst()) : null;
        if (t != null && t.type() == Type.PERCENTAGE) return (float) t.number() / 100f;
        if (angular) {
            Double degrees = angleOf(t);
            if (degrees != null) return (float) (degrees / 360.0);
            // 数式の角度は評価できないので 0
            LengthResolver.Quantity q = LengthResolver.evaluateMath(pos, LengthResolver.Metrics.of(1, rootW, rootH, fontSize, rootFontSize));
            return q != null && q.dimension() == LengthResolver.Dimension.ANGLE ? (float) (q.value() / 360.0)
                    : q != null && q.dimension() == LengthResolver.Dimension.PERCENT ? (float) (q.value() / 100.0) : 0f;
        }
        if (length <= 0) return 0;
        return LengthResolver.parseDim(pos.trim(), length, rootW, rootH, fontSize, rootFontSize) / length;
    }

    /**
     * 線上の位置 {@code t} の色。停止点の間は補間し（{@code space} が null なら乗算済みアルファの sRGB、
     * そうでなければその色空間。§3.5.3）、補間のヒントは中間点を動かす。{@code repeating} なら最初と最後の
     * 停止点の間を繰り返す。
     */
    static int colorAt(ResolvedStops stops, float t, boolean repeating, ColorParser.ColorMixer mixer) {
        float[] positions = stops.positions();
        int[] colors = stops.colors();
        int n = positions.length;
        if (repeating) {
            float span = positions[n - 1] - positions[0];
            if (span > 1e-6f) {
                t = positions[0] + (float) (((t - positions[0]) % span + span) % span);
            } else {
                return colors[n - 1];
            }
        }
        if (t <= positions[0]) return colors[0];
        if (t >= positions[n - 1]) return colors[n - 1];
        for (int i = 0; i + 1 < n; i++) {
            float a = positions[i], b = positions[i + 1];
            if (t > b) continue;
            // 同じ位置に並んだ停止点は後ろの色へ急に切り替わる
            if (b - a <= 1e-6f) return colors[i + 1];
            double f = (t - a) / (b - a);
            float hint = stops.hints()[i];
            if (!Float.isNaN(hint) && hint > a && hint < b) {
                // 中間点を hint に動かす: f' = f^(ln 0.5 / ln h)（h は区間内での hint の位置）
                double h = (hint - a) / (b - a);
                f = Math.pow(f, Math.log(0.5) / Math.log(h));
            }
            return mixer == null ? lerpPremultiplied(colors[i], colors[i + 1], (float) f)
                    : mixer.mix(colors[i], colors[i + 1], f);
        }
        return colors[n - 1];
    }

    static int lerpPremultiplied(int c1, int c2, float f) {
        float a1 = (c1 >>> 24) / 255f, a2 = (c2 >>> 24) / 255f;
        float a = a1 + (a2 - a1) * f;
        if (a <= 0) return 0;
        int[] shifts = {16, 8, 0};
        int out = Math.round(a * 255) << 24;
        for (int s : shifts) {
            float v1 = ((c1 >>> s) & 0xFF) * a1, v2 = ((c2 >>> s) & 0xFF) * a2;
            out |= Math.clamp(Math.round((v1 + (v2 - v1) * f) / a), 0, 255) << s;
        }
        return out;
    }

    // ==========================================
    // シェーダーの記述に直す
    // ==========================================

    /** 補間空間の指定があるとき、停止点の間をこの数に刻んで sRGB の補間で近似する。 */
    private static final int COLOR_SPACE_SUBDIVISIONS = 32;
    /** 区間の数の上限（停止点が多いときは色空間の細分を粗くして収める）。 */
    private static final int MAX_SHADER_SEGMENTS = 512;

    /**
     * グラデーション（{@code linear-} / {@code radial-} / {@code conic-}、{@code repeating-} を含む）を、
     * シェーダーが画素ごとに計算して描くための記述にする。帯や格子に刻まないので、継ぎ目も段もない。
     *
     * <p>停止点の間（区間）ごとに 1 つ返す。区間は同じ矩形を重ねて描き、各区間は自分の t（0〜1）の範囲の外を捨てる
     * （{@link ShaderGradient#LOWER_CLOSED} / {@link ShaderGradient#UPPER_CLOSED}）ので、画素ごとにちょうど 1 区間が塗る。
     * 最初の区間の手前と最後の区間の先は端の色が伸びる。幅 0 の区間（同じ位置の停止点。色の切り替わり）は描かず、
     * その手前・先の端の色は単色の区間で塗る。繰り返しはシェーダーが位置を周期に畳む。
     * 補間のヒントは区間の t の指数で表す。補間空間の指定（{@code in oklab} など）は、区間を細かく刻んで、
     * その色空間での色を sRGB の補間でつなぐ近似にする。
     *
     * <p>空のときは描くものがない（タイルの大きさが 0、線の長さが 0）。形式は {@link ShaderGradient}。
     */
    public static List<ShaderGradient> shaderGradients(Gradient g, Rect tile, float rootW, float rootH, float fontSize, float rootFontSize) {
        if (tile.w() <= 0 || tile.h() <= 0) return List.of();
        ColorParser.ColorMixer mixer = g.colorSpace() == null ? null : ColorParser.mixer(g.colorSpace());
        return switch (g) {
            case LinearGradient linear -> {
                double rad = Math.toRadians(linear.angleFor(tile.w(), tile.h()));
                double sin = Math.sin(rad), cos = Math.cos(rad);
                if (Math.abs(sin) < 1e-9) sin = 0;
                if (Math.abs(cos) < 1e-9) cos = 0;
                double length = Math.abs(tile.w() * sin) + Math.abs(tile.h() * cos);
                if (length <= 0) yield List.<ShaderGradient>of();
                ResolvedStops stops = resolveStops(g.stops(), (float) length, false, rootW, rootH, fontSize, rootFontSize);
                double cx = tile.x() + tile.w() / 2.0, cy = tile.y() + tile.h() / 2.0;
                float dx = (float) (sin / length), dy = (float) (-cos / length);
                float off = (float) ((-cx * sin + cy * cos) / length + 0.5);
                yield segments(ShaderGradient.LINEAR, dx, dy, off, 0f, stops, linear.repeating(), mixer);
            }
            case RadialGradient radial -> {
                float[] center = resolveCenter(radial.center(), tile, rootW, rootH, fontSize, rootFontSize);
                float[] radii = radialRadii(radial, tile, center[0], center[1], rootW, rootH, fontSize, rootFontSize);
                float rx = Math.max(radii[0], 1e-3f), ry = Math.max(radii[1], 1e-3f);
                ResolvedStops stops = resolveStops(g.stops(), rx, false, rootW, rootH, fontSize, rootFontSize);
                yield segments(ShaderGradient.RADIAL, center[0], center[1], 1f / rx, 1f / ry, stops, radial.repeating(), mixer);
            }
            case ConicGradient conic -> {
                float[] center = resolveCenter(conic.center(), tile, rootW, rootH, fontSize, rootFontSize);
                ResolvedStops stops = resolveStops(g.stops(), 1, true, rootW, rootH, fontSize, rootFontSize);
                float from = (float) (conic.from() / 360.0);
                yield segments(ShaderGradient.CONIC, center[0], center[1], from, 0f, stops, conic.repeating(), mixer);
            }
        };
    }

    /** 区間 1 つ分。{@code p0} は始まりの位置、{@code span} は幅（どちらも停止点の尺度）、{@code hint} は t の指数。 */
    private record Piece(float p0, float span, int color0, int color1, float hint) {}

    private static List<ShaderGradient> segments(int kind, float g0, float g1, float g2, float g3, ResolvedStops stops,
                                                 boolean repeating, ColorParser.ColorMixer mixer) {
        float[] pos = stops.positions();
        int[] colors = stops.colors();
        int n = pos.length;
        List<Piece> real = new ArrayList<>();
        int intervals = 0;
        for (int i = 0; i + 1 < n; i++) {
            if (pos[i + 1] - pos[i] > 1e-6f) intervals++;
        }
        int sub = mixer == null ? 1 : Math.clamp(MAX_SHADER_SEGMENTS / Math.max(1, intervals), 1, COLOR_SPACE_SUBDIVISIONS);
        int firstReal = -1, lastReal = -1;
        for (int i = 0; i + 1 < n; i++) {
            float a = pos[i], b = pos[i + 1], span = b - a;
            if (span <= 1e-6f) continue;
            if (firstReal < 0) firstReal = i;
            lastReal = i;
            if (mixer != null) {
                // 色空間での色を刻みの端で求め、刻みの中は sRGB でつなぐ（ヒントも色の側に入っている）
                for (int s = 0; s < sub; s++) {
                    float pa = a + span * s / sub, pb = a + span * (s + 1) / sub;
                    real.add(new Piece(pa, pb - pa, colorAt(stops, pa, false, mixer), colorAt(stops, pb, false, mixer), 1f));
                }
            } else {
                real.add(new Piece(a, span, colors[i], colors[i + 1], hintExponent(stops.hints()[i], a, b)));
            }
        }

        List<ShaderGradient> out = new ArrayList<>();
        float period = pos[n - 1] - pos[0];
        if (repeating && period > 1e-6f) {
            // 周期の中の区間は両側を閉じる（周期の外は畳まれて入ってこない）
            int both = ShaderGradient.LOWER_CLOSED | ShaderGradient.UPPER_CLOSED;
            for (Piece p : real) out.add(make(kind, g0, g1, g2, g3, p, pos[0], period, both));
            return out;
        }
        if (repeating) {
            // 周期が 0 の繰り返しは最後の色の単色（§3.5.2）
            out.add(make(kind, g0, g1, g2, g3, new Piece(pos[n - 1], 1f, colors[n - 1], colors[n - 1], 1f), 0f, 0f, 0));
            return out;
        }
        boolean padBefore = firstReal != 0;
        boolean padAfter = lastReal != n - 2;
        if (padBefore) {
            // 最初の区間より手前（幅 0 の区間のさらに手前）は最初の停止点の色。その停止点までを単色で塗る
            float edge = firstReal >= 0 ? pos[firstReal] : pos[0];
            out.add(make(kind, g0, g1, g2, g3, new Piece(edge - 1f, 1f, colors[0], colors[0], 1f), 0f, 0f, ShaderGradient.UPPER_CLOSED));
        }
        for (int k = 0; k < real.size(); k++) {
            int bounds = (k > 0 || padBefore ? ShaderGradient.LOWER_CLOSED : 0)
                    | (k < real.size() - 1 || padAfter ? ShaderGradient.UPPER_CLOSED : 0);
            out.add(make(kind, g0, g1, g2, g3, real.get(k), 0f, 0f, bounds));
        }
        if (padAfter) {
            float edge = lastReal >= 0 ? pos[lastReal + 1] : pos[n - 1];
            out.add(make(kind, g0, g1, g2, g3, new Piece(edge, 1f, colors[n - 1], colors[n - 1], 1f), 0f, 0f, ShaderGradient.LOWER_CLOSED));
        }
        return out;
    }

    private static ShaderGradient make(int kind, float g0, float g1, float g2, float g3, Piece p,
                                       float repeatOrigin, float repeatPeriod, int bounds) {
        return ShaderGradient.of(kind, g0, g1, g2, g3, p.p0(), 1f / p.span(), p.hint(), repeatOrigin, repeatPeriod,
                p.color0(), p.color1(), bounds);
    }

    /** 区間 [a, b] の補間のヒントを t の指数にする（中間点を hint に動かす: t' = t^(ln 0.5 / ln h)）。ヒントがなければ 1。 */
    private static float hintExponent(float hint, float a, float b) {
        if (Float.isNaN(hint) || hint <= a || hint >= b) return 1f;
        double h = (hint - a) / (b - a);
        return (float) (Math.log(0.5) / Math.log(h));
    }

    /** 終点の半径（横・縦）。円は同じ値（§3.2.3）。 */
    static float[] radialRadii(RadialGradient g, Rect tile, float cx, float cy, float rootW, float rootH,
                                       float fontSize, float rootFontSize) {
        if (g.sizeKeyword() == null) {
            float rx = LengthResolver.parseDim(g.radiusX(), tile.w(), rootW, rootH, fontSize, rootFontSize);
            if (g.circle()) return new float[]{rx, rx};
            float ry = LengthResolver.parseDim(g.radiusY(), tile.h(), rootW, rootH, fontSize, rootFontSize);
            return new float[]{rx, ry};
        }
        float left = cx - tile.x(), right = tile.x() + tile.w() - cx;
        float top = cy - tile.y(), bottom = tile.y() + tile.h() - cy;
        float sideX = g.sizeKeyword().startsWith("closest") ? Math.min(Math.abs(left), Math.abs(right)) : Math.max(Math.abs(left), Math.abs(right));
        float sideY = g.sizeKeyword().startsWith("closest") ? Math.min(Math.abs(top), Math.abs(bottom)) : Math.max(Math.abs(top), Math.abs(bottom));
        if (g.sizeKeyword().endsWith("side")) {
            if (!g.circle()) return new float[]{sideX, sideY};
            // 円: 4 辺までの距離の最小（closest-side）または最大（farthest-side）
            float nearest = Math.min(Math.min(Math.abs(left), Math.abs(right)), Math.min(Math.abs(top), Math.abs(bottom)));
            float farthest = Math.max(Math.max(Math.abs(left), Math.abs(right)), Math.max(Math.abs(top), Math.abs(bottom)));
            float r = g.sizeKeyword().startsWith("closest") ? nearest : farthest;
            return new float[]{r, r};
        }
        // 角: 最も近い（遠い）角までの距離
        float[] dxs = {Math.abs(left), Math.abs(right)};
        float[] dys = {Math.abs(top), Math.abs(bottom)};
        boolean closest = g.sizeKeyword().startsWith("closest");
        float bestDx = 0, bestDy = 0, best = closest ? Float.MAX_VALUE : -1;
        for (float dx : dxs) {
            for (float dy : dys) {
                float d = dx * dx + dy * dy;
                if (closest ? d < best : d > best) {
                    best = d;
                    bestDx = dx;
                    bestDy = dy;
                }
            }
        }
        if (g.circle()) {
            float r = (float) Math.sqrt(best);
            return new float[]{r, r};
        }
        // 楕円: side の楕円と同じ縦横比で、その角を通る大きさにする
        float rx0 = Math.max(sideX, 1e-3f), ry0 = Math.max(sideY, 1e-3f);
        float scale = (float) Math.sqrt((bestDx / rx0) * (bestDx / rx0) + (bestDy / ry0) * (bestDy / ry0));
        return new float[]{rx0 * scale, ry0 * scale};
    }

    // ---- 中心

    /**
     * {@code at <position>}（放射・円錐の中心）をタイル内の座標にする。1〜2 値（{@code left} / {@code center} /
     * {@code top 20%} など）と、辺のキーワードにずれを添えた 3〜4 値の構文を読む。% はタイルの幅・高さに対する割合。
     */
    static float[] resolveCenter(List<String> words, Rect tile, float rootW, float rootH, float fontSize, float rootFontSize) {
        List<String> tokens = new ArrayList<>();
        for (String w : words) tokens.add(w.toLowerCase(Locale.ROOT));
        float x = tile.x() + tile.w() / 2, y = tile.y() + tile.h() / 2;
        if (tokens.isEmpty()) return new float[]{x, y};

        if (tokens.size() <= 2) {
            Float px = null, py = null;
            for (int i = 0; i < tokens.size(); i++) {
                String t = tokens.get(i);
                switch (t) {
                    case "left" -> px = 0f;
                    case "right" -> px = tile.w();
                    case "top" -> py = 0f;
                    case "bottom" -> py = tile.h();
                    case "center" -> {
                        if (i == 0 && tokens.size() == 2 && isVerticalWord(tokens.get(1))) px = tile.w() / 2;
                        else if (i == 0) {
                            px = tile.w() / 2;
                            if (tokens.size() == 1) py = tile.h() / 2;
                        } else if (px == null) {
                            px = tile.w() / 2;
                        } else {
                            py = tile.h() / 2;
                        }
                    }
                    default -> {
                        float v = LengthResolver.parseDim(t, i == 0 ? tile.w() : tile.h(), rootW, rootH, fontSize, rootFontSize);
                        if (i == 0) px = v;
                        else py = v;
                    }
                }
            }
            return new float[]{tile.x() + (px != null ? px : tile.w() / 2), tile.y() + (py != null ? py : tile.h() / 2)};
        }

        // 3〜4 値: 辺のキーワードの後ろにずれ
        Float fromLeft = null, fromTop = null;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            boolean horizontal = t.equals("left") || t.equals("right");
            boolean vertical = t.equals("top") || t.equals("bottom");
            if (!horizontal && !vertical) continue;
            float offset = 0;
            if (i + 1 < tokens.size() && !isEdgeWord(tokens.get(i + 1))) {
                offset = LengthResolver.parseDim(tokens.get(i + 1), horizontal ? tile.w() : tile.h(), rootW, rootH,
                        fontSize, rootFontSize);
            }
            if (horizontal) fromLeft = t.equals("left") ? offset : tile.w() - offset;
            else fromTop = t.equals("top") ? offset : tile.h() - offset;
        }
        return new float[]{tile.x() + (fromLeft != null ? fromLeft : tile.w() / 2),
                tile.y() + (fromTop != null ? fromTop : tile.h() / 2)};
    }

    private static boolean isVerticalWord(String t) {
        return t.equals("top") || t.equals("bottom");
    }

    private static boolean isEdgeWord(String t) {
        return t.equals("left") || t.equals("right") || t.equals("top") || t.equals("bottom") || t.equals("center");
    }
}
