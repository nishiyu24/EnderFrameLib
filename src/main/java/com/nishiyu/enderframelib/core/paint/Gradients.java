package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.BoxDecorations.Rect;
import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;
import com.nishiyu.enderframelib.core.css.LengthResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CSS のグラデーション（CSS Images 3 / 4）: {@code linear-gradient()} / {@code radial-gradient()} /
 * {@code conic-gradient()} と {@code repeating-*}、色の補間のヒント、{@code in <色空間>}（色の補間空間）。
 *
 * 値は {@link CssSyntax} のトークンとして読み、描画側が扱える形（4 隅の色を持つ矩形 {@link GradientStrip}）に直す。
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

    /** グラデーションを塗る矩形 1 枚と 4 隅の色（左上・右上・右下・左下）。描画側は 4 隅の色を双線形に補間する。 */
    public record GradientStrip(float x, float y, float w, float h, int topLeft, int topRight, int bottomRight, int bottomLeft) {}

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
    // 矩形に直す
    // ==========================================

    /** 斜めのグラデーションを刻む帯の上限。 */
    static final int MAX_GRADIENT_STRIPS = 24;

    /** 放射・円錐・複雑な斜めのグラデーションを刻むときの矩形の上限。 */
    static final int MAX_MESH_CELLS = 512;

    /** 点の色を返す関数。 */
    @FunctionalInterface
    private interface ColorField {
        int at(float x, float y);
    }

    /**
     * タイルをグラデーションの矩形に分ける。
     *
     * 縦横のグラデーションは停止点の位置（繰り返しなら各周期の停止点、ヒントや補間空間があれば区間の細分）で帯を切るので、
     * 帯の中は 2 色の補間そのもの。斜めで停止点が両端の 2 つだけなら色は座標の 1 次式なので、1 枚の双線形補間で正確に描ける。
     * それ以外（斜めで中間の停止点があるとき・放射・円錐）は点の色の関数を、色の変化が大きいところを細かくする
     * 四分木で刻み、矩形の隅の間は線形に補間する。
     *
     * LIMITATION(制約): 放射・円錐・斜めの多段のグラデーションは、矩形（最大 {@value #MAX_MESH_CELLS} 枚）の隅の色を
     * 線形に補間する近似で、色の急な境目は矩形の細かさまでしか鋭くならない。README の "Not supported" に対応。
     */
    public static List<GradientStrip> strips(Gradient g, Rect tile, float rootW, float rootH,
                                             float fontSize, float rootFontSize) {
        return strips(g, tile, tile, rootW, rootH, fontSize, rootFontSize);
    }

    /**
     * {@link #strips(Gradient, Rect, float, float, float, float)} のうち、{@code visible}（描画領域）に掛かる矩形だけを返す。
     * 繰り返しで敷いたタイルのほとんどは領域の外に出るので、そこは刻まない。色の計算にはタイル全体を使う。
     */
    public static List<GradientStrip> strips(Gradient g, Rect tile, Rect visible, float rootW, float rootH,
                                             float fontSize, float rootFontSize) {
        if (tile.w() <= 0 || tile.h() <= 0) return new ArrayList<>();
        ColorParser.ColorMixer mixer = g.colorSpace() == null ? null : ColorParser.mixer(g.colorSpace());
        return switch (g) {
            case LinearGradient linear -> linearStrips(linear, tile, visible, rootW, rootH, fontSize, rootFontSize, mixer);
            case RadialGradient radial -> radialStrips(radial, tile, visible, rootW, rootH, fontSize, rootFontSize, mixer);
            case ConicGradient conic -> conicStrips(conic, tile, visible, rootW, rootH, fontSize, rootFontSize, mixer);
        };
    }

    /**
     * 2 色だけの {@code linear-gradient()} / {@code radial-gradient()}（繰り返しなし・ヒントなし・補間空間の指定なし）を、
     * シェーダーが画素ごとに計算して 1 枚で描くための記述にする。帯や格子に刻まないので、継ぎ目も段もない。
     * 対象外（停止点が 3 つ以上・繰り返し・円錐など）は null で、従来どおり {@link #strips} で刻む。
     *
     * 形式（カンマ区切り）: 線形 {@code L,dx,dy,offset,色0,色1}（t = dx*x + dy*y + offset）、
     * 放射 {@code R,cx,cy,1/rx,1/ry,p0,1/(p1-p0),色0,色1}（t = (|((x-cx)/rx, (y-cy)/ry)| - p0) / (p1 - p0)）。
     * どちらも t は 0〜1 に切り詰め、色はプリマルチプライドで補間する（{@link #lerpPremultiplied} と同じ）。
     */
    public static String shaderGradient(Gradient g, Rect tile, float rootW, float rootH, float fontSize, float rootFontSize) {
        if (tile.w() <= 0 || tile.h() <= 0 || g.repeating() || g.colorSpace() != null) return null;
        if (g instanceof LinearGradient linear) {
            double rad = Math.toRadians(linear.angleFor(tile.w(), tile.h()));
            double sin = Math.sin(rad), cos = Math.cos(rad);
            if (Math.abs(sin) < 1e-9) sin = 0;
            if (Math.abs(cos) < 1e-9) cos = 0;
            double length = Math.abs(tile.w() * sin) + Math.abs(tile.h() * cos);
            if (length <= 0) return null;
            ResolvedStops stops = resolveStops(g.stops(), (float) length, false, rootW, rootH, fontSize, rootFontSize);
            if (stops.positions().length != 2 || hasHint(stops)) return null;
            double p0 = stops.positions()[0], span = stops.positions()[1] - p0;
            if (span <= 1e-6) return null;
            double cx = tile.x() + tile.w() / 2.0, cy = tile.y() + tile.h() / 2.0;
            double k = 1.0 / (length * span);
            double dx = sin * k, dy = -cos * k;
            double off = (-cx * sin + cy * cos) * k + (0.5 - p0) / span;
            return "L," + (float) dx + "," + (float) dy + "," + (float) off + "," + stops.colors()[0] + "," + stops.colors()[1];
        }
        if (g instanceof RadialGradient radial) {
            float[] center = resolveCenter(radial.center(), tile, rootW, rootH, fontSize, rootFontSize);
            float[] radii = radialRadii(radial, tile, center[0], center[1], rootW, rootH, fontSize, rootFontSize);
            float rx = Math.max(radii[0], 1e-3f), ry = Math.max(radii[1], 1e-3f);
            ResolvedStops stops = resolveStops(g.stops(), rx, false, rootW, rootH, fontSize, rootFontSize);
            if (stops.positions().length != 2 || hasHint(stops)) return null;
            float p0 = stops.positions()[0], span = stops.positions()[1] - p0;
            if (span <= 1e-6f) return null;
            return "R," + center[0] + "," + center[1] + "," + (1f / rx) + "," + (1f / ry) + "," + p0 + "," + (1f / span)
                    + "," + stops.colors()[0] + "," + stops.colors()[1];
        }
        return null;
    }

    private static List<GradientStrip> linearStrips(LinearGradient g, Rect tile, Rect visible, float rootW, float rootH,
                                                    float fontSize, float rootFontSize, ColorParser.ColorMixer mixer) {
        List<GradientStrip> out = new ArrayList<>();
        double rad = Math.toRadians(g.angleFor(tile.w(), tile.h()));
        double sin = Math.sin(rad), cos = Math.cos(rad);
        if (Math.abs(sin) < 1e-9) sin = 0;
        if (Math.abs(cos) < 1e-9) cos = 0;
        double length = Math.abs(tile.w() * sin) + Math.abs(tile.h() * cos);
        if (length <= 0) return out;
        ResolvedStops stops = resolveStops(g.stops(), (float) length, false, rootW, rootH, fontSize, rootFontSize);
        float[] positions = stops.positions();
        boolean repeating = g.repeating();
        double centerX = tile.x() + tile.w() / 2.0, centerY = tile.y() + tile.h() / 2.0;
        double dirSin = sin, dirCos = cos;
        ColorField field = (px, py) -> colorAt(stops, (float) (((px - centerX) * dirSin - (py - centerY) * dirCos) / length + 0.5),
                repeating, mixer);

        boolean horizontal = cos == 0, vertical = sin == 0;
        boolean axisAligned = horizontal || vertical;
        boolean plainTwoStops = !repeating && !hasHint(stops) && mixer == null && positions.length == 2
                && positions[0] == 0 && positions[1] == 1;
        // 斜めで色が座標の 1 次式にならないもの（中間の停止点・繰り返し・ヒント・補間空間）は格子で近似する
        if (!axisAligned && !plainTwoStops) return mesh(field, tile, visible);

        List<Float> cuts = new ArrayList<>();
        cuts.add(0f);
        if (axisAligned) {
            // 停止点の位置で切る。グラデーションの線の向きが逆なら位置も反転する
            boolean reversed = horizontal ? sin < 0 : cos > 0;
            for (float p : lineCuts(stops, repeating, mixer != null || hasHint(stops))) {
                if (p > 0 && p < 1) cuts.add(reversed ? 1 - p : p);
            }
        }
        cuts.add(1f);
        cuts.sort(Float::compare);

        // 縦のグラデーション（上下に色が変わる）は横長の帯、それ以外は縦長の帯
        boolean rows = vertical || (!horizontal && Math.abs(cos) > Math.abs(sin));
        for (int i = 0; i + 1 < cuts.size() && out.size() < MAX_MESH_CELLS; i++) {
            float a = cuts.get(i), b = cuts.get(i + 1);
            if (b - a <= 1e-6f) continue;
            float x0, y0, x1, y1;
            if (rows) {
                x0 = tile.x(); x1 = tile.x() + tile.w();
                y0 = tile.y() + tile.h() * a; y1 = tile.y() + tile.h() * b;
            } else {
                y0 = tile.y(); y1 = tile.y() + tile.h();
                x0 = tile.x() + tile.w() * a; x1 = tile.x() + tile.w() * b;
            }
            if (!intersects(x0, y0, x1, y1, visible)) continue;
            out.add(new GradientStrip(x0, y0, x1 - x0, y1 - y0, field.at(x0, y0), field.at(x1, y0), field.at(x1, y1), field.at(x0, y1)));
        }
        return out;
    }

    private static boolean intersects(float x0, float y0, float x1, float y1, Rect area) {
        return x0 < area.x() + area.w() && x1 > area.x() && y0 < area.y() + area.h() && y1 > area.y();
    }

    private static boolean hasHint(ResolvedStops stops) {
        for (float h : stops.hints()) {
            if (!Float.isNaN(h)) return true;
        }
        return false;
    }

    /**
     * 線上（0〜1）で色を区切る位置。停止点の位置のほか、繰り返しなら各周期の停止点、ヒントや補間空間があれば
     * 区間を細かく刻んだ位置。上限を超える分は間引く。
     */
    private static List<Float> lineCuts(ResolvedStops stops, boolean repeating, boolean curved) {
        float[] positions = stops.positions();
        List<Float> cuts = new ArrayList<>();
        float first = positions[0], last = positions[positions.length - 1];
        float span = last - first;
        int periods = 1;
        float start = 0;
        if (repeating && span > 1e-4f) {
            // 0〜1 を覆う周期の範囲（負の側にもはみ出す）
            int before = (int) Math.ceil(first / span);
            start = first - before * span;
            periods = (int) Math.ceil((1 - start) / span) + 1;
            periods = Math.min(periods, MAX_MESH_CELLS / Math.max(1, positions.length));
        }
        int sub = curved ? 8 : 1;
        for (int period = 0; period < periods; period++) {
            float offset = repeating && span > 1e-4f ? start + period * span - first : 0;
            for (int i = 0; i < positions.length; i++) {
                cuts.add(positions[i] + offset);
                if (i + 1 < positions.length && sub > 1) {
                    for (int s = 1; s < sub; s++) {
                        cuts.add(positions[i] + offset + (positions[i + 1] - positions[i]) * s / sub);
                    }
                }
            }
        }
        return cuts;
    }

    // ---- 放射

    private static List<GradientStrip> radialStrips(RadialGradient g, Rect tile, Rect visible, float rootW, float rootH,
                                                    float fontSize, float rootFontSize, ColorParser.ColorMixer mixer) {
        float[] center = resolveCenter(g.center(), tile, rootW, rootH, fontSize, rootFontSize);
        float cx = center[0], cy = center[1];
        float[] radii = radialRadii(g, tile, cx, cy, rootW, rootH, fontSize, rootFontSize);
        float rx = Math.max(radii[0], 1e-3f), ry = Math.max(radii[1], 1e-3f);
        ResolvedStops stops = resolveStops(g.stops(), rx, false, rootW, rootH, fontSize, rootFontSize);
        boolean repeating = g.repeating();
        ColorField field = (px, py) -> {
            float dx = (px - cx) / rx, dy = (py - cy) / ry;
            return colorAt(stops, (float) Math.sqrt(dx * dx + dy * dy), repeating, mixer);
        };
        return mesh(field, tile, visible);
    }

    /** 終点の半径（横・縦）。円は同じ値（§3.2.3）。 */
    private static float[] radialRadii(RadialGradient g, Rect tile, float cx, float cy, float rootW, float rootH,
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

    // ---- 円錐

    private static List<GradientStrip> conicStrips(ConicGradient g, Rect tile, Rect visible, float rootW, float rootH,
                                                   float fontSize, float rootFontSize, ColorParser.ColorMixer mixer) {
        float[] center = resolveCenter(g.center(), tile, rootW, rootH, fontSize, rootFontSize);
        float cx = center[0], cy = center[1];
        ResolvedStops stops = resolveStops(g.stops(), 1, true, rootW, rootH, fontSize, rootFontSize);
        boolean repeating = g.repeating();
        double from = g.from();
        ColorField field = (px, py) -> {
            // 真上から時計回りの角度
            double deg = Math.toDegrees(Math.atan2(px - cx, -(py - cy)));
            double t = (((deg - from) % 360) + 360) % 360 / 360.0;
            return colorAt(stops, (float) t, repeating, mixer);
        };
        return mesh(field, tile, visible);
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

    // ---- 四分木

    /**
     * タイルを、色の変化が大きいところほど細かい矩形に刻む。最初に粗い格子（最大 8 × 8）に分け、各矩形の中心の色が
     * 隅の色の平均から外れていれば 4 つに割る（上限 {@value #MAX_MESH_CELLS} 枚、最小 1px）。
     */
    private static List<GradientStrip> mesh(ColorField field, Rect tile, Rect visible) {
        List<GradientStrip> out = new ArrayList<>();
        int nx = Math.clamp((int) Math.ceil(tile.w() / 32f), 1, 8);
        int ny = Math.clamp((int) Math.ceil(tile.h() / 32f), 1, 8);
        int[] budget = {MAX_MESH_CELLS - nx * ny};
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                float x0 = tile.x() + tile.w() * i / nx, x1 = tile.x() + tile.w() * (i + 1) / nx;
                float y0 = tile.y() + tile.h() * j / ny, y1 = tile.y() + tile.h() * (j + 1) / ny;
                subdivide(field, x0, y0, x1, y1, visible, 0, budget, out);
            }
        }
        return out;
    }

    private static final int MAX_SUBDIVISION_DEPTH = 6;
    /** 中心の色が隅の平均から外れていても許す大きさ（チャンネルごとの最大差。0〜255）。 */
    private static final int SUBDIVISION_TOLERANCE = 6;

    private static void subdivide(ColorField field, float x0, float y0, float x1, float y1, Rect visible, int depth,
                                  int[] budget, List<GradientStrip> out) {
        if (!intersects(x0, y0, x1, y1, visible)) return;
        int c00 = field.at(x0, y0), c10 = field.at(x1, y0), c11 = field.at(x1, y1), c01 = field.at(x0, y1);
        boolean small = (x1 - x0) <= 1.5f && (y1 - y0) <= 1.5f;
        if (depth < MAX_SUBDIVISION_DEPTH && !small && budget[0] >= 3) {
            int mid = field.at((x0 + x1) / 2, (y0 + y1) / 2);
            int average = averageOf(c00, c10, c11, c01);
            if (maxChannelDifference(mid, average) > SUBDIVISION_TOLERANCE) {
                budget[0] -= 3;
                float mx = (x0 + x1) / 2, my = (y0 + y1) / 2;
                subdivide(field, x0, y0, mx, my, visible, depth + 1, budget, out);
                subdivide(field, mx, y0, x1, my, visible, depth + 1, budget, out);
                subdivide(field, x0, my, mx, y1, visible, depth + 1, budget, out);
                subdivide(field, mx, my, x1, y1, visible, depth + 1, budget, out);
                return;
            }
        }
        out.add(new GradientStrip(x0, y0, x1 - x0, y1 - y0, c00, c10, c11, c01));
    }

    private static int averageOf(int a, int b, int c, int d) {
        int out = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int sum = ((a >>> shift) & 0xFF) + ((b >>> shift) & 0xFF) + ((c >>> shift) & 0xFF) + ((d >>> shift) & 0xFF);
            out |= (sum / 4) << shift;
        }
        return out;
    }

    private static int maxChannelDifference(int a, int b) {
        int max = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            max = Math.max(max, Math.abs(((a >>> shift) & 0xFF) - ((b >>> shift) & 0xFF)));
        }
        return max;
    }
}
