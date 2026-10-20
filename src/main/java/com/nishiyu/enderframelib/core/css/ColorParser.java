package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.css.model.*;

import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * CSS の色（CSS Color 4 / 5）を ARGB に解く。
 *
 * 読める形: 16 進・名前付き色・{@code rgb()} / {@code hsl()} / {@code hwb()} / {@code lab()} / {@code lch()} /
 * {@code oklab()} / {@code oklch()} / {@code color()}・{@code color-mix()}・相対色（{@code rgb(from red r g b)}）。
 * 成分は {@code none}（欠けた成分）と {@code calc()} を書ける。値は {@link CssSyntax} のトークンとして読み、
 * 正規表現は使わない。
 *
 * sRGB の色域の外にある色（{@code oklch(0.7 0.4 150)} など）は、CSS Color 4 §13.2 の
 * OKLCH の彩度を詰める方法で色域に写す。{@code rgb()} / {@code hsl()} / {@code hwb()} と
 * {@code color(srgb …)} の範囲外の値は、仕様どおり切り詰める。
 *
 * 何が壊れていたか:
 * <ul>
 *   <li>{@code #RRGGBBAA} を 16 進の整数のまま ARGB として返していたので、{@code #ff000080}（半透明の赤）が
 *       {@code 0xFF000080}（不透明の暗い青）になっていた。{@code #RGBA} は白になっていた。</li>
 *   <li>名前付き色が Minecraft のチャットの色だった（{@code red} = {@code #FF5555}、{@code gray} = {@code #AAAAAA}）。
 *       CSS の名前付き色は CSS の値（{@code red} = {@code #ff0000}、{@code gray} = {@code #808080}）。
 *       Minecraft の色が欲しいときは {@code --mc-red} などの変数を使う（README）。</li>
 *   <li>知らない値を不透明な白にしていた。いまは {@link #isColor} で判定でき、カスケードは無効な色の宣言を捨てる。</li>
 * </ul>
 */
public class ColorParser {

    /** 解けない値。{@link #parseColor} はこのとき透明を返す。 */
    private static final long INVALID = Long.MIN_VALUE;

    /** グラデーションから色を拾えなかったときの代わりの色。 */
    private static final long GRADIENT_FALLBACK = 0xFF555555L;

    public static int parseColor(String c) {
        long parsed = parse(c);
        return parsed == INVALID ? 0x00000000 : (int) parsed;
    }

    /** CSS の色として解けるか（{@code currentcolor} を含む。グラデーションは含まない）。 */
    public static boolean isColor(String c) {
        if (c == null) return false;
        String v = c.trim().toLowerCase(Locale.ROOT);
        if (v.equals("currentcolor")) return true;
        if (v.contains("gradient(")) return false;
        return parse(v) != INVALID;
    }

    /** ARGB を CSS の色の文字列にする。不透明なら {@code #rrggbb}、そうでなければ {@code rgba()}。 */
    public static String format(int argb) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (a == 0xFF) return String.format(Locale.ROOT, "#%02x%02x%02x", r, g, b);
        return String.format(Locale.ROOT, "rgba(%d, %d, %d, %s)", r, g, b, trimNumber(a / 255.0));
    }

    private static String trimNumber(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * 2 色を補間空間 {@code space}（{@code "oklab"}・{@code "srgb"}・{@code "lch longer hue"} など、
     * {@code color-mix(in …)} の {@code in} の後ろの書き方）で混ぜる。{@code t} は 0 で {@code from}、1 で {@code to}。
     * 空間が読めなければ {@code oklab}。グラデーションの {@code in <space>} とトランジションが使う。
     */
    public static int interpolate(int from, int to, double t, String space) {
        return mixer(space).mix(from, to, t);
    }

    /** 2 色（ARGB）を補間空間で混ぜる関数。空間の読み取りを 1 回で済ませたいとき（グラデーションの各点）に使う。 */
    @FunctionalInterface
    public interface ColorMixer {
        int mix(int from, int to, double t);
    }

    /** {@link #interpolate} と同じ補間空間の書き方から、混ぜる関数を作る。読めなければ {@code oklab}。 */
    public static ColorMixer mixer(String space) {
        Interpolation parsed = Interpolation.parse(space == null ? "oklab" : space);
        Interpolation interpolation = parsed != null ? parsed : Interpolation.parse("oklab");
        return (from, to, t) -> mix(Color.fromArgb(from), Color.fromArgb(to), 1 - t, t, interpolation, 1.0).toArgb();
    }

    private static long parse(String c) {
        if (c == null) return INVALID;
        List<ComponentValue> values = CssSyntax.trim(CssSyntax.parseComponentValues(c));
        if (values.isEmpty()) return INVALID;
        if (values.size() > 1) {
            // グラデーションの外側の余分な文字は許さない
            return INVALID;
        }
        return parseValue(values.getFirst());
    }

    private static long parseValue(ComponentValue value) {
        if (value instanceof CssSyntax.Function f) {
            String name = f.name().toLowerCase(Locale.ROOT);
            if (name.endsWith("-gradient")) return firstGradientColor(f);
            Color color = parseFunction(f);
            return color == null ? INVALID : color.toArgb() & 0xFFFFFFFFL;
        }
        Token t = CssSyntax.tokenOf(value);
        if (t == null) return INVALID;
        if (t.type() == Type.HASH) return parseHex(t.value());
        if (t.type() == Type.IDENT) {
            String name = t.value().toLowerCase(Locale.ROOT);
            if (name.equals("transparent")) return 0x00000000L;
            Integer rgb = NAMED_COLORS.get(name);
            return rgb == null ? INVALID : 0xFF000000L | rgb;
        }
        return INVALID;
    }

    /** グラデーションの最初の色。背景色の型のプロパティに画像が書かれたときの代わりに使う。 */
    private static long firstGradientColor(CssSyntax.Function gradient) {
        for (List<ComponentValue> arg : CssSyntax.split(gradient.args(), Type.COMMA)) {
            for (List<ComponentValue> part : CssSyntax.splitOnWhitespace(arg)) {
                if (part.size() != 1) continue;
                long parsed = parseValue(part.getFirst());
                if (parsed != INVALID) return parsed;
            }
        }
        return GRADIENT_FALLBACK;
    }

    /** {@code RGB} / {@code RGBA} / {@code RRGGBB} / {@code RRGGBBAA}（CSS Color 4 §5.2）。 */
    private static long parseHex(String hex) {
        for (int i = 0; i < hex.length(); i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) return INVALID;
        }
        int r, g, b, a = 0xFF;
        switch (hex.length()) {
            case 3, 4 -> {
                r = Character.digit(hex.charAt(0), 16) * 0x11;
                g = Character.digit(hex.charAt(1), 16) * 0x11;
                b = Character.digit(hex.charAt(2), 16) * 0x11;
                if (hex.length() == 4) a = Character.digit(hex.charAt(3), 16) * 0x11;
            }
            case 6, 8 -> {
                r = Integer.parseInt(hex.substring(0, 2), 16);
                g = Integer.parseInt(hex.substring(2, 4), 16);
                b = Integer.parseInt(hex.substring(4, 6), 16);
                if (hex.length() == 8) a = Integer.parseInt(hex.substring(6, 8), 16);
            }
            default -> {
                return INVALID;
            }
        }
        return argb(a, r, g, b);
    }

    private static long argb(int a, int r, int g, int b) {
        return ((long) a << 24 | r << 16 | g << 8 | b) & 0xFFFFFFFFL;
    }

    // ==========================================
    // 色の値
    // ==========================================

    /** 色空間。極座標の空間（hsl / hwb / lch / oklch）は成分 0〜2 のどれかが色相（度）。 */
    private enum Space {
        SRGB("srgb"), SRGB_LINEAR("srgb-linear"), DISPLAY_P3("display-p3"), A98_RGB("a98-rgb"),
        PROPHOTO_RGB("prophoto-rgb"), REC2020("rec2020"), XYZ_D50("xyz-d50"), XYZ_D65("xyz-d65"),
        LAB("lab"), LCH("lch"), OKLAB("oklab"), OKLCH("oklch"), HSL("hsl"), HWB("hwb");

        final String id;

        Space(String id) {
            this.id = id;
        }

        static Space of(String name) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.equals("xyz")) return XYZ_D65;
            for (Space s : values()) {
                if (s.id.equals(n)) return s;
            }
            return null;
        }

        /** 色相の成分の番号。極座標でなければ -1。 */
        int hueIndex() {
            return switch (this) {
                case HSL, HWB -> 0;
                case LCH, OKLCH -> 2;
                default -> -1;
            };
        }

        /** sRGB の色域に収まるはずの空間（範囲外の値は切り詰める）。 */
        boolean isSrgbBounded() {
            return this == SRGB || this == SRGB_LINEAR || this == HSL || this == HWB;
        }
    }

    /**
     * 色 1 つ。{@code c} は成分 3 つ（NaN は {@code none}＝欠けた成分）、{@code alpha} は 0〜1（NaN は欠け）。
     * 成分の尺度は空間ごとに CSS の書き方に合わせる: sRGB 系と XYZ は 0〜1、hsl / hwb は色相が度で残りが 0〜100、
     * lab / lch は L が 0〜100、oklab / oklch は L が 0〜1。
     */
    private record Color(Space space, double c0, double c1, double c2, double alpha) {

        static Color fromArgb(int argb) {
            return new Color(Space.SRGB, ((argb >> 16) & 0xFF) / 255.0, ((argb >> 8) & 0xFF) / 255.0,
                    (argb & 0xFF) / 255.0, (argb >>> 24) / 255.0);
        }

        double component(int i) {
            return i == 0 ? c0 : i == 1 ? c1 : c2;
        }

        double[] components() {
            return new double[]{c0, c1, c2};
        }

        /** 欠けた成分を 0 にした色。変換の前に使う。 */
        Color withoutMissing() {
            return new Color(space, zero(c0), zero(c1), zero(c2), Double.isNaN(alpha) ? 0 : alpha);
        }

        int toArgb() {
            double[] rgb = ColorSpaces.toDeviceSrgb(this);
            double a = Double.isNaN(alpha) ? 0 : Math.clamp(alpha, 0.0, 1.0);
            return (clampByte(a * 255.0) << 24) | (clampByte(rgb[0] * 255.0) << 16)
                    | (clampByte(rgb[1] * 255.0) << 8) | clampByte(rgb[2] * 255.0);
        }
    }

    private static double zero(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static int clampByte(double v) {
        return (int) Math.clamp(Math.round(v), 0, 255);
    }

    // ==========================================
    // 関数（rgb / hsl / hwb / lab / lch / oklab / oklch / color / color-mix）
    // ==========================================

    private static Color parseFunction(CssSyntax.Function f) {
        return switch (f.name().toLowerCase(Locale.ROOT)) {
            case "rgb", "rgba" -> parseRgb(f);
            case "hsl", "hsla" -> parsePolarOrLab(f, Space.HSL);
            case "hwb" -> parsePolarOrLab(f, Space.HWB);
            case "lab" -> parsePolarOrLab(f, Space.LAB);
            case "lch" -> parsePolarOrLab(f, Space.LCH);
            case "oklab" -> parsePolarOrLab(f, Space.OKLAB);
            case "oklch" -> parsePolarOrLab(f, Space.OKLCH);
            case "color" -> parseColorFunction(f);
            case "color-mix" -> parseColorMix(f);
            case "light-dark" -> parseLightDark(f);
            default -> null;
        };
    }

    /** {@code light-dark(<light>, <dark>)}。このエンジンは明るい配色（color-scheme: light）なので前者を選ぶ。 */
    private static Color parseLightDark(CssSyntax.Function f) {
        List<List<ComponentValue>> args = CssSyntax.split(f.args(), Type.COMMA);
        if (args.size() != 2) return null;
        Color light = parseColorArgument(args.get(0));
        Color dark = parseColorArgument(args.get(1));
        return light == null || dark == null ? null : light;
    }

    /** 引数の列（空白区切り）を 1 つの色として読む。 */
    private static Color parseColorArgument(List<ComponentValue> arg) {
        List<ComponentValue> v = CssSyntax.trim(arg);
        if (v.size() != 1) return null;
        long parsed = parseValue(v.getFirst());
        if (parsed != INVALID) {
            // 16 進・名前付き・関数のいずれも ARGB を経由しない: 関数は色空間を保ったまま返したい
            if (v.getFirst() instanceof CssSyntax.Function f && !f.name().toLowerCase(Locale.ROOT).endsWith("-gradient")) {
                return parseFunction(f);
            }
            return Color.fromArgb((int) parsed);
        }
        return null;
    }

    /** 成分の 1 つの書き方。 */
    private enum Channel {
        /** 数または割合。{@code percentOf} は 100% に当たる値。 */
        VALUE,
        /** 色相（数＝度、または角度）。 */
        HUE,
        /** アルファ（数 0〜1 または割合）。 */
        ALPHA
    }

    /** {@code (<chan> <chan> <chan> [/ <alpha>])} を解く（相対色の {@code from} も読む）。 */
    private static final class ChannelParser {
        final Map<String, Double> keywords = new HashMap<>();

        /** 空白区切り（旧式はカンマ）の成分の列から、成分 3 つとアルファを取り出す。 */
        List<List<ComponentValue>> split(List<ComponentValue> body, boolean legacyAllowed) {
            List<List<ComponentValue>> result = new ArrayList<>();
            boolean hasComma = false;
            for (ComponentValue v : body) {
                if (CssSyntax.isToken(v, Type.COMMA)) hasComma = true;
            }
            if (hasComma) {
                if (!legacyAllowed) return null;
                for (List<ComponentValue> part : CssSyntax.split(body, Type.COMMA)) result.add(part);
                if (result.size() != 3 && result.size() != 4) return null;
                while (result.size() < 4) result.add(null);
                return result;
            }
            List<ComponentValue> main = body;
            List<ComponentValue> alpha = null;
            for (int i = 0; i < body.size(); i++) {
                Token t = CssSyntax.tokenOf(body.get(i));
                if (t != null && t.isDelim('/')) {
                    main = body.subList(0, i);
                    alpha = CssSyntax.trim(body.subList(i + 1, body.size()));
                    if (alpha.isEmpty()) return null;
                    break;
                }
            }
            List<List<ComponentValue>> channels = CssSyntax.splitOnWhitespace(main);
            // 空白で分けた 1 成分は 1 つの値（calc(a + b) のような関数は 1 つのコンポーネント値）
            if (channels.size() != 3) return null;
            result.addAll(channels);
            result.add(alpha);
            return result;
        }

        /**
         * 成分を読む。{@code none} は NaN、読めなければ null。
         *
         * @param percentOf 割合 100% に当たる値（色相とアルファでは使わない）
         */
        Double read(List<ComponentValue> arg, Channel kind, double percentOf) {
            if (arg == null) return kind == Channel.ALPHA ? 1.0 : null;
            List<ComponentValue> v = CssSyntax.trim(arg);
            if (v.size() != 1) return null;
            ComponentValue only = v.getFirst();
            if (only instanceof CssSyntax.Function f) {
                LengthResolver.Quantity q = LengthResolver.evaluateMath(f, keywords);
                return q == null ? null : fromQuantity(q, kind, percentOf);
            }
            Token t = CssSyntax.tokenOf(only);
            if (t == null) return null;
            switch (t.type()) {
                case NUMBER:
                    return kind == Channel.HUE ? t.number() : t.number();
                case PERCENTAGE:
                    if (kind == Channel.HUE) return null;
                    return kind == Channel.ALPHA ? t.number() / 100.0 : t.number() / 100.0 * percentOf;
                case DIMENSION:
                    if (kind != Channel.HUE) return null;
                    return LengthResolver.angleDegrees(t.number(), t.unit());
                case IDENT:
                    String name = t.value().toLowerCase(Locale.ROOT);
                    if (name.equals("none")) return Double.NaN;
                    return keywords.get(name);
                default:
                    return null;
            }
        }

        private static Double fromQuantity(LengthResolver.Quantity q, Channel kind, double percentOf) {
            return switch (q.dimension()) {
                case NUMBER -> q.value();
                case PERCENT -> kind == Channel.ALPHA ? q.value() / 100.0
                        : kind == Channel.HUE ? null : q.value() / 100.0 * percentOf;
                case ANGLE -> kind == Channel.HUE ? q.value() : null;
                default -> null;
            };
        }
    }

    /** {@code rgb()} / {@code rgba()}。旧式（カンマ）は 3 成分とも数、または 3 成分とも割合。 */
    private static Color parseRgb(CssSyntax.Function f) {
        ChannelParser parser = new ChannelParser();
        List<ComponentValue> body = prepareBody(f, parser, Space.SRGB);
        if (body == null) return null;
        boolean legacy = body.stream().anyMatch(v -> CssSyntax.isToken(v, Type.COMMA));
        List<List<ComponentValue>> parts = parser.split(body, true);
        if (parts == null) return null;

        if (legacy) {
            boolean percents = isPercentage(parts.get(0));
            for (int i = 0; i < 3; i++) {
                if (isPercentage(parts.get(i)) != percents || isNone(parts.get(i))) return null;
            }
        }
        Double r = parser.read(parts.get(0), Channel.VALUE, 255);
        Double g = parser.read(parts.get(1), Channel.VALUE, 255);
        Double b = parser.read(parts.get(2), Channel.VALUE, 255);
        Double a = parser.read(parts.get(3), Channel.ALPHA, 1);
        if (r == null || g == null || b == null || a == null) return null;
        return new Color(Space.SRGB, r / 255.0, g / 255.0, b / 255.0, a);
    }

    private static boolean isPercentage(List<ComponentValue> arg) {
        List<ComponentValue> v = CssSyntax.trim(arg);
        return v.size() == 1 && CssSyntax.isToken(v.getFirst(), Type.PERCENTAGE);
    }

    private static boolean isNone(List<ComponentValue> arg) {
        List<ComponentValue> v = CssSyntax.trim(arg);
        return v.size() == 1 && CssSyntax.tokenOf(v.getFirst()) != null && CssSyntax.tokenOf(v.getFirst()).isIdent("none");
    }

    /**
     * hsl / hwb / lab / lch / oklab / oklch。hsl の旧式（カンマ）は {@code s} と {@code l} が割合。
     * 内部の成分は {@link Color} の尺度（hsl・hwb の s / l / w / b は 0〜100）。
     */
    private static Color parsePolarOrLab(CssSyntax.Function f, Space space) {
        ChannelParser parser = new ChannelParser();
        List<ComponentValue> body = prepareBody(f, parser, space);
        if (body == null) return null;
        boolean legacyAllowed = space == Space.HSL;
        List<List<ComponentValue>> parts = parser.split(body, legacyAllowed);
        if (parts == null) return null;
        boolean legacy = legacyAllowed && body.stream().anyMatch(v -> CssSyntax.isToken(v, Type.COMMA));
        if (legacy && (!isPercentage(parts.get(1)) || !isPercentage(parts.get(2)))) return null;

        Double c0;
        Double c1;
        Double c2;
        switch (space) {
            case HSL, HWB -> {
                c0 = parser.read(parts.get(0), Channel.HUE, 0);
                c1 = parser.read(parts.get(1), Channel.VALUE, 100);
                c2 = parser.read(parts.get(2), Channel.VALUE, 100);
            }
            case LAB -> {
                c0 = parser.read(parts.get(0), Channel.VALUE, 100);
                c1 = parser.read(parts.get(1), Channel.VALUE, 125);
                c2 = parser.read(parts.get(2), Channel.VALUE, 125);
            }
            case LCH -> {
                c0 = parser.read(parts.get(0), Channel.VALUE, 100);
                c1 = parser.read(parts.get(1), Channel.VALUE, 150);
                c2 = parser.read(parts.get(2), Channel.HUE, 0);
            }
            case OKLAB -> {
                c0 = parser.read(parts.get(0), Channel.VALUE, 1);
                c1 = parser.read(parts.get(1), Channel.VALUE, 0.4);
                c2 = parser.read(parts.get(2), Channel.VALUE, 0.4);
            }
            default -> { // OKLCH
                c0 = parser.read(parts.get(0), Channel.VALUE, 1);
                c1 = parser.read(parts.get(1), Channel.VALUE, 0.4);
                c2 = parser.read(parts.get(2), Channel.HUE, 0);
            }
        }
        Double a = parser.read(parts.get(3), Channel.ALPHA, 1);
        if (c0 == null || c1 == null || c2 == null || a == null) return null;
        return new Color(space, c0, c1, c2, a);
    }

    /** {@code color(<space> c1 c2 c3 [/ a])}。成分は数か割合（100% = 1）。 */
    private static Color parseColorFunction(CssSyntax.Function f) {
        ChannelParser parser = new ChannelParser();
        List<ComponentValue> body = new ArrayList<>(CssSyntax.trim(f.args()));
        Color origin = null;
        if (!body.isEmpty() && CssSyntax.tokenOf(body.getFirst()) != null && CssSyntax.tokenOf(body.getFirst()).isIdent("from")) {
            int[] used = {1};
            origin = readOrigin(body, used);
            if (origin == null) return null;
            body = new ArrayList<>(body.subList(used[0], body.size()));
        }
        body = new ArrayList<>(CssSyntax.trim(body));
        if (body.isEmpty() || !(CssSyntax.tokenOf(body.getFirst()) != null && CssSyntax.tokenOf(body.getFirst()).is(Type.IDENT))) {
            return null;
        }
        String name = CssSyntax.tokenOf(body.getFirst()).value();
        Space space = Space.of(name);
        if (space == null || space.hueIndex() >= 0 || space == Space.LAB || space == Space.OKLAB) return null;
        List<ComponentValue> rest = body.subList(1, body.size());
        if (origin != null) bindKeywords(parser, origin, space, true);

        List<List<ComponentValue>> parts = parser.split(rest, false);
        if (parts == null) return null;
        Double c0 = parser.read(parts.get(0), Channel.VALUE, 1);
        Double c1 = parser.read(parts.get(1), Channel.VALUE, 1);
        Double c2 = parser.read(parts.get(2), Channel.VALUE, 1);
        Double a = parser.read(parts.get(3), Channel.ALPHA, 1);
        if (c0 == null || c1 == null || c2 == null || a == null) return null;
        return new Color(space, c0, c1, c2, a);
    }

    /**
     * 相対色なら {@code from <color>} を読み、残りを返して成分の名前（{@code r} / {@code h} など）を
     * パーサに束ねる。相対色でなければ引数の全体を返す。構文が壊れていれば null。
     */
    private static List<ComponentValue> prepareBody(CssSyntax.Function f, ChannelParser parser, Space space) {
        List<ComponentValue> body = new ArrayList<>(CssSyntax.trim(f.args()));
        if (!body.isEmpty() && CssSyntax.tokenOf(body.getFirst()) != null
                && CssSyntax.tokenOf(body.getFirst()).isIdent("from")) {
            int[] used = {1};
            Color origin = readOrigin(body, used);
            if (origin == null) return null;
            bindKeywords(parser, origin, space, false);
            return new ArrayList<>(body.subList(used[0], body.size()));
        }
        return body;
    }

    /** {@code from} の次にある色（1 つのコンポーネント値）を読む。{@code used[0]} は読み終えた位置。 */
    private static Color readOrigin(List<ComponentValue> body, int[] used) {
        int i = used[0];
        while (i < body.size() && CssSyntax.isWhitespace(body.get(i))) i++;
        if (i >= body.size()) return null;
        ComponentValue v = body.get(i);
        Color color;
        if (v instanceof CssSyntax.Function fn) {
            color = parseFunction(fn);
        } else {
            long parsed = parseValue(v);
            color = parsed == INVALID ? null : Color.fromArgb((int) parsed);
        }
        used[0] = i + 1;
        return color;
    }

    /** 相対色の成分の名前に、元の色をその関数の空間へ変換した値を束ねる。 */
    private static void bindKeywords(ChannelParser parser, Color origin, Space target, boolean colorFunction) {
        Color c = ColorSpaces.convert(origin.withoutMissing(), target);
        String[] names = switch (target) {
            case HSL -> new String[]{"h", "s", "l"};
            case HWB -> new String[]{"h", "w", "b"};
            case LAB, OKLAB -> new String[]{"l", "a", "b"};
            case LCH, OKLCH -> new String[]{"l", "c", "h"};
            case XYZ_D50, XYZ_D65 -> new String[]{"x", "y", "z"};
            default -> new String[]{"r", "g", "b"};
        };
        double scale = target == Space.SRGB && !colorFunction ? 255.0 : 1.0;
        for (int i = 0; i < 3; i++) {
            parser.keywords.put(names[i], c.component(i) * (i < 3 && target == Space.SRGB && !colorFunction ? scale : 1.0));
        }
        parser.keywords.put("alpha", Double.isNaN(origin.alpha()) ? 0 : origin.alpha());
    }

    // ---- color-mix()

    /** 補間の空間と、極座標の空間での色相の補間方法。 */
    private record Interpolation(Space space, String hueMethod) {

        /** {@code oklch longer hue} のような {@code in} の後ろの書き方を読む。読めなければ null。 */
        static Interpolation parse(String text) {
            List<List<ComponentValue>> words = CssSyntax.splitOnWhitespace(CssSyntax.parseComponentValues(text));
            if (words.isEmpty()) return null;
            Token first = CssSyntax.tokenOf(words.getFirst().getFirst());
            if (first == null || first.type() != Type.IDENT) return null;
            Space space = Space.of(first.value());
            if (space == null) return null;
            String method = "shorter";
            if (words.size() > 1) {
                if (space.hueIndex() < 0 || words.size() != 3) return null;
                Token m = CssSyntax.tokenOf(words.get(1).getFirst());
                Token hue = CssSyntax.tokenOf(words.get(2).getFirst());
                if (m == null || hue == null || !hue.isIdent("hue")) return null;
                method = m.value().toLowerCase(Locale.ROOT);
                if (!List.of("shorter", "longer", "increasing", "decreasing").contains(method)) return null;
            }
            return new Interpolation(space, method);
        }
    }

    /** {@code color-mix(in <space> [<hue> hue]?, <color> <pct>?, <color> <pct>?)}。 */
    private static Color parseColorMix(CssSyntax.Function f) {
        List<List<ComponentValue>> args = CssSyntax.split(f.args(), Type.COMMA);
        if (args.size() != 3) return null;
        List<List<ComponentValue>> head = CssSyntax.splitOnWhitespace(args.get(0));
        if (head.size() < 2 || CssSyntax.tokenOf(head.getFirst().getFirst()) == null
                || !CssSyntax.tokenOf(head.getFirst().getFirst()).isIdent("in")) {
            return null;
        }
        StringBuilder spec = new StringBuilder();
        for (int i = 1; i < head.size(); i++) spec.append(CssSyntax.serialize(head.get(i))).append(' ');
        Interpolation interpolation = Interpolation.parse(spec.toString().trim());
        if (interpolation == null) return null;

        Color[] colors = new Color[2];
        double[] percents = {Double.NaN, Double.NaN};
        for (int i = 0; i < 2; i++) {
            List<List<ComponentValue>> parts = CssSyntax.splitOnWhitespace(args.get(i + 1));
            List<ComponentValue> colorPart = null;
            for (List<ComponentValue> part : parts) {
                if (part.size() == 1 && CssSyntax.isToken(part.getFirst(), Type.PERCENTAGE)) {
                    if (!Double.isNaN(percents[i])) return null;
                    percents[i] = CssSyntax.tokenOf(part.getFirst()).number();
                } else if (colorPart == null) {
                    colorPart = part;
                } else {
                    return null;
                }
            }
            if (colorPart == null) return null;
            colors[i] = parseColorArgument(colorPart);
            if (colors[i] == null || percents[i] < 0 || percents[i] > 100) return null;
        }

        double p1 = percents[0];
        double p2 = percents[1];
        if (Double.isNaN(p1) && Double.isNaN(p2)) {
            p1 = 50;
            p2 = 50;
        } else if (Double.isNaN(p1)) {
            p1 = 100 - p2;
        } else if (Double.isNaN(p2)) {
            p2 = 100 - p1;
        }
        double sum = p1 + p2;
        if (sum <= 0) return null;
        // 合計が 100% を超えれば正規化し、満たなければ残りを透明として足す（アルファを掛ける）
        double alphaMultiplier = sum < 100 ? sum / 100.0 : 1.0;
        return mix(colors[0], colors[1], p1 / sum, p2 / sum, interpolation, alphaMultiplier);
    }

    /**
     * 2 色を補間空間で混ぜる（CSS Color 4 §12）。アルファを掛けた値（premultiplied）で補間し、
     * 欠けた成分は相手の値を使い、色相は {@code hueMethod} に従って寄せる。
     *
     * @param w1 色 1 の重み、{@param w2} は色 2 の重み（合計 1）
     */
    private static Color mix(Color a, Color b, double w1, double w2, Interpolation interpolation, double alphaMultiplier) {
        Space space = interpolation.space();
        Color ca = ColorSpaces.convertKeepingMissing(a, space);
        Color cb = ColorSpaces.convertKeepingMissing(b, space);

        double alphaA = ca.alpha();
        double alphaB = cb.alpha();
        double alpha;
        if (Double.isNaN(alphaA) && Double.isNaN(alphaB)) alpha = Double.NaN;
        else {
            double aa = Double.isNaN(alphaA) ? alphaB : alphaA;
            double ab = Double.isNaN(alphaB) ? alphaA : alphaB;
            alpha = aa * w1 + ab * w2;
            alphaA = aa;
            alphaB = ab;
        }

        double[] out = new double[3];
        int hueIndex = space.hueIndex();
        double hueA = hueIndex >= 0 ? ca.component(hueIndex) : Double.NaN;
        double hueB = hueIndex >= 0 ? cb.component(hueIndex) : Double.NaN;
        double[] hues = hueIndex >= 0 ? fixHues(hueA, hueB, interpolation.hueMethod()) : null;

        double premultiplyA = Double.isNaN(alphaA) ? 1 : alphaA;
        double premultiplyB = Double.isNaN(alphaB) ? 1 : alphaB;
        for (int i = 0; i < 3; i++) {
            double va = ca.component(i);
            double vb = cb.component(i);
            if (i == hueIndex) {
                out[i] = hues[0] * w1 + hues[1] * w2;
                if (Double.isNaN(va) && Double.isNaN(vb)) out[i] = Double.NaN;
                else if (Double.isNaN(va)) out[i] = vb;
                else if (Double.isNaN(vb)) out[i] = va;
                continue;
            }
            if (Double.isNaN(va) && Double.isNaN(vb)) {
                out[i] = Double.NaN;
            } else if (Double.isNaN(va)) {
                out[i] = vb;
            } else if (Double.isNaN(vb)) {
                out[i] = va;
            } else {
                double mixed = va * premultiplyA * w1 + vb * premultiplyB * w2;
                double a2 = Double.isNaN(alpha) ? 1 : alpha;
                out[i] = a2 == 0 ? mixed : mixed / a2;
            }
        }
        double resultAlpha = Double.isNaN(alpha) ? Double.NaN : alpha * alphaMultiplier;
        return new Color(space, out[0], out[1], out[2], resultAlpha);
    }

    /** 色相 2 つを、補間方法に応じて 360° 以内に寄せる（CSS Color 4 §12.4）。欠けた色相はそのまま返す。 */
    private static double[] fixHues(double h1, double h2, String method) {
        if (Double.isNaN(h1) || Double.isNaN(h2)) return new double[]{zero(h1), zero(h2)};
        h1 = ((h1 % 360) + 360) % 360;
        h2 = ((h2 % 360) + 360) % 360;
        double delta = h2 - h1;
        switch (method) {
            case "shorter" -> {
                if (delta > 180) h1 += 360;
                else if (delta < -180) h2 += 360;
            }
            case "longer" -> {
                if (delta > 0 && delta < 180) h1 += 360;
                else if (delta > -180 && delta <= 0) h2 += 360;
            }
            case "increasing" -> {
                if (delta < 0) h2 += 360;
            }
            case "decreasing" -> {
                if (delta > 0) h1 += 360;
            }
            default -> {
            }
        }
        return new double[]{h1, h2};
    }

    // ==========================================
    // 色空間の変換と色域の写像
    // ==========================================

    /** 色空間どうしの変換（行列は CSS Color 4 の color-conversion のもの）。 */
    private static final class ColorSpaces {

        private ColorSpaces() {}

        private static final double[][] LIN_SRGB_TO_XYZ = {
                {0.41239079926595934, 0.357584339383878, 0.1804807884018343},
                {0.21263900587151027, 0.715168678767756, 0.07219231536073371},
                {0.01933081871559182, 0.11919477979462598, 0.9505321522496607}};
        private static final double[][] XYZ_TO_LIN_SRGB = {
                {3.2409699419045226, -1.537383177570094, -0.4986107602930034},
                {-0.9692436362808796, 1.8759675015077202, 0.04155505740717559},
                {0.05563007969699366, -0.20397695888897652, 1.0569715142428786}};
        private static final double[][] LIN_P3_TO_XYZ = {
                {0.4865709486482162, 0.26566769316909306, 0.1982172852343625},
                {0.2289745640697488, 0.6917385218365064, 0.079286914093745},
                {0.0, 0.04511338185890264, 1.043944368900976}};
        private static final double[][] XYZ_TO_LIN_P3 = {
                {2.493496911941425, -0.9313836179191239, -0.40271078445071684},
                {-0.8294889695615747, 1.7626640603183463, 0.023624685841943577},
                {0.03584583024378447, -0.07617238926804182, 0.9568845240076872}};
        private static final double[][] LIN_A98_TO_XYZ = {
                {0.5766690429101305, 0.1855582379065463, 0.1882286462349947},
                {0.29734497525053605, 0.6273635662554661, 0.0752914584939978},
                {0.02703136138641234, 0.07068885253582723, 0.9913375368376388}};
        private static final double[][] XYZ_TO_LIN_A98 = {
                {2.0415879038107465, -0.5650069742788596, -0.34473135077832956},
                {-0.9692436362808795, 1.8759675015077202, 0.04155505740717557},
                {0.013444280632031142, -0.11836239223101838, 1.0151749943912054}};
        private static final double[][] LIN_PROPHOTO_TO_XYZ_D50 = {
                {0.7977666449006423, 0.13518129740053308, 0.0313477341283922},
                {0.2880748288194013, 0.711835234241873, 0.00008993693872564},
                {0.0, 0.0, 0.8251046025104602}};
        private static final double[][] XYZ_D50_TO_LIN_PROPHOTO = {
                {1.3457989731028281, -0.25558010007997534, -0.05110628506753401},
                {-0.5446224939028347, 1.5082327413132781, 0.02053603239147973},
                {0.0, 0.0, 1.2119675456389454}};
        private static final double[][] LIN_REC2020_TO_XYZ = {
                {0.6369580483012914, 0.14461690358620832, 0.1688809751641721},
                {0.2627002120112671, 0.6779980715188708, 0.05930171646986196},
                {0.0, 0.028072693049087428, 1.060985057710791}};
        private static final double[][] XYZ_TO_LIN_REC2020 = {
                {1.7166511879712674, -0.35567078377639233, -0.25336628137365974},
                {-0.6666843518324892, 1.6164812366349395, 0.01576854581391113},
                {0.017639857445310783, -0.042770613257808524, 0.9421031212354738}};
        private static final double[][] D50_TO_D65 = {
                {0.955473421488075, -0.02309845494876471, 0.06325924320057072},
                {-0.0283697093338637, 1.0099953980813041, 0.021041441191917323},
                {0.012314014864481998, -0.020507649298898964, 1.330365926242124}};
        private static final double[][] D65_TO_D50 = {
                {1.0479297925449969, 0.022946870601609652, -0.05019226628920524},
                {0.02962780877005599, 0.9904344267538799, -0.017073799063418826},
                {-0.009243040646204504, 0.015055191490298152, 0.7518742814281371}};
        private static final double[][] XYZ_TO_LMS = {
                {0.8190224379967030, 0.3619062600528904, -0.1288737815209879},
                {0.0329836539323885, 0.9292868615863434, 0.0361446663506424},
                {0.0481771893596242, 0.2642395317527308, 0.6335478284694309}};
        private static final double[][] LMS_TO_XYZ = {
                {1.2268798758459243, -0.5578149944602171, 0.2813910456659647},
                {-0.0405757452148008, 1.1122868032803170, -0.0716766787716327},
                {-0.0763729366746601, -0.4214933324022432, 1.5869240198367816}};
        private static final double[][] LMS_TO_OKLAB = {
                {0.2104542683093140, 0.7936177747023054, -0.0040720430116193},
                {1.9779985324311684, -2.4285922420485799, 0.4505937096174110},
                {0.0259040424655478, 0.7827717124575296, -0.8086757549230774}};
        private static final double[][] OKLAB_TO_LMS = {
                {1.0, 0.3963377773761749, 0.2158037573099136},
                {1.0, -0.1055613458156586, -0.0638541728258133},
                {1.0, -0.0894841775298119, -1.2914855480194092}};

        /** D50 の白（CIE Lab の基準）。 */
        private static final double[] D50_WHITE = {0.3457 / 0.3585, 1.0, (1.0 - 0.3457 - 0.3585) / 0.3585};
        private static final double LAB_EPSILON = 216.0 / 24389.0;
        private static final double LAB_KAPPA = 24389.0 / 27.0;

        static double[] multiply(double[][] m, double[] v) {
            return new double[]{
                    m[0][0] * v[0] + m[0][1] * v[1] + m[0][2] * v[2],
                    m[1][0] * v[0] + m[1][1] * v[1] + m[1][2] * v[2],
                    m[2][0] * v[0] + m[2][1] * v[1] + m[2][2] * v[2]};
        }

        // ---- 伝達関数

        static double srgbToLinear(double v) {
            double a = Math.abs(v);
            return a <= 0.04045 ? v / 12.92 : Math.signum(v) * Math.pow((a + 0.055) / 1.055, 2.4);
        }

        static double linearToSrgb(double v) {
            double a = Math.abs(v);
            return a > 0.0031308 ? Math.signum(v) * (1.055 * Math.pow(a, 1 / 2.4) - 0.055) : 12.92 * v;
        }

        static double a98ToLinear(double v) {
            return Math.signum(v) * Math.pow(Math.abs(v), 563.0 / 256.0);
        }

        static double linearToA98(double v) {
            return Math.signum(v) * Math.pow(Math.abs(v), 256.0 / 563.0);
        }

        static double prophotoToLinear(double v) {
            double a = Math.abs(v);
            return a <= 16.0 / 512.0 ? v / 16.0 : Math.signum(v) * Math.pow(a, 1.8);
        }

        static double linearToProphoto(double v) {
            double a = Math.abs(v);
            return a >= 1.0 / 512.0 ? Math.signum(v) * Math.pow(a, 1.0 / 1.8) : 16.0 * v;
        }

        private static final double REC2020_ALPHA = 1.09929682680944;
        private static final double REC2020_BETA = 0.018053968510807;

        static double rec2020ToLinear(double v) {
            double a = Math.abs(v);
            return a < REC2020_BETA * 4.5 ? v / 4.5
                    : Math.signum(v) * Math.pow((a + REC2020_ALPHA - 1) / REC2020_ALPHA, 1 / 0.45);
        }

        static double linearToRec2020(double v) {
            double a = Math.abs(v);
            return a >= REC2020_BETA ? Math.signum(v) * (REC2020_ALPHA * Math.pow(a, 0.45) - (REC2020_ALPHA - 1)) : 4.5 * v;
        }

        // ---- XYZ（D65）への往復

        /** 色を XYZ（D65）にする。欠けた成分は 0 として扱う。 */
        static double[] toXyzD65(Color c) {
            double[] v = {zero(c.c0()), zero(c.c1()), zero(c.c2())};
            switch (c.space()) {
                case SRGB:
                    return multiply(LIN_SRGB_TO_XYZ, new double[]{srgbToLinear(v[0]), srgbToLinear(v[1]), srgbToLinear(v[2])});
                case SRGB_LINEAR:
                    return multiply(LIN_SRGB_TO_XYZ, v);
                case DISPLAY_P3:
                    return multiply(LIN_P3_TO_XYZ, new double[]{srgbToLinear(v[0]), srgbToLinear(v[1]), srgbToLinear(v[2])});
                case A98_RGB:
                    return multiply(LIN_A98_TO_XYZ, new double[]{a98ToLinear(v[0]), a98ToLinear(v[1]), a98ToLinear(v[2])});
                case PROPHOTO_RGB:
                    return multiply(D50_TO_D65, multiply(LIN_PROPHOTO_TO_XYZ_D50,
                            new double[]{prophotoToLinear(v[0]), prophotoToLinear(v[1]), prophotoToLinear(v[2])}));
                case REC2020:
                    return multiply(LIN_REC2020_TO_XYZ, new double[]{rec2020ToLinear(v[0]), rec2020ToLinear(v[1]), rec2020ToLinear(v[2])});
                case XYZ_D65:
                    return v;
                case XYZ_D50:
                    return multiply(D50_TO_D65, v);
                case LAB:
                    return multiply(D50_TO_D65, labToXyzD50(v));
                case LCH:
                    return multiply(D50_TO_D65, labToXyzD50(lchToLab(v)));
                case OKLAB:
                    return oklabToXyz(v);
                case OKLCH:
                    return oklabToXyz(lchToLab(v));
                case HSL:
                    return multiply(LIN_SRGB_TO_XYZ, linearFromSrgb(hslToSrgb(v)));
                case HWB:
                    return multiply(LIN_SRGB_TO_XYZ, linearFromSrgb(hwbToSrgb(v)));
                default:
                    throw new IllegalStateException(c.space().id);
            }
        }

        private static double[] linearFromSrgb(double[] rgb) {
            return new double[]{srgbToLinear(rgb[0]), srgbToLinear(rgb[1]), srgbToLinear(rgb[2])};
        }

        /** XYZ（D65）を空間 {@code target} の成分にする。 */
        static double[] fromXyzD65(double[] xyz, Space target) {
            switch (target) {
                case SRGB: {
                    double[] l = multiply(XYZ_TO_LIN_SRGB, xyz);
                    return new double[]{linearToSrgb(l[0]), linearToSrgb(l[1]), linearToSrgb(l[2])};
                }
                case SRGB_LINEAR:
                    return multiply(XYZ_TO_LIN_SRGB, xyz);
                case DISPLAY_P3: {
                    double[] l = multiply(XYZ_TO_LIN_P3, xyz);
                    return new double[]{linearToSrgb(l[0]), linearToSrgb(l[1]), linearToSrgb(l[2])};
                }
                case A98_RGB: {
                    double[] l = multiply(XYZ_TO_LIN_A98, xyz);
                    return new double[]{linearToA98(l[0]), linearToA98(l[1]), linearToA98(l[2])};
                }
                case PROPHOTO_RGB: {
                    double[] l = multiply(XYZ_D50_TO_LIN_PROPHOTO, multiply(D65_TO_D50, xyz));
                    return new double[]{linearToProphoto(l[0]), linearToProphoto(l[1]), linearToProphoto(l[2])};
                }
                case REC2020: {
                    double[] l = multiply(XYZ_TO_LIN_REC2020, xyz);
                    return new double[]{linearToRec2020(l[0]), linearToRec2020(l[1]), linearToRec2020(l[2])};
                }
                case XYZ_D65:
                    return xyz;
                case XYZ_D50:
                    return multiply(D65_TO_D50, xyz);
                case LAB:
                    return xyzD50ToLab(multiply(D65_TO_D50, xyz));
                case LCH:
                    return labToLch(xyzD50ToLab(multiply(D65_TO_D50, xyz)), 0.0015);
                case OKLAB:
                    return xyzToOklab(xyz);
                case OKLCH:
                    return labToLch(xyzToOklab(xyz), 0.000004);
                case HSL:
                    return srgbToHsl(fromXyzD65(xyz, Space.SRGB));
                case HWB:
                    return srgbToHwb(fromXyzD65(xyz, Space.SRGB));
                default:
                    throw new IllegalStateException(target.id);
            }
        }

        static Color convert(Color c, Space target) {
            if (c.space() == target) return c;
            double[] out = fromXyzD65(toXyzD65(c), target);
            return new Color(target, out[0], out[1], out[2], c.alpha());
        }

        /**
         * 補間用の変換。同じ空間なら欠けた成分を保ち、そうでなければ欠けた成分を 0 として変換したうえで、
         * 極座標の色相が意味を持たない（彩度がほぼ 0 の）ときは欠けた色相にする（powerless）。
         */
        static Color convertKeepingMissing(Color c, Space target) {
            if (c.space() == target) return c;
            double[] out = fromXyzD65(toXyzD65(c), target);
            Color converted = new Color(target, out[0], out[1], out[2], c.alpha());
            return markPowerlessHue(converted);
        }

        private static Color markPowerlessHue(Color c) {
            return switch (c.space()) {
                case HSL -> Math.abs(c.c1()) < 1e-6 ? new Color(c.space(), Double.NaN, c.c1(), c.c2(), c.alpha()) : c;
                case HWB -> c.c1() + c.c2() >= 100 - 1e-6 ? new Color(c.space(), Double.NaN, c.c1(), c.c2(), c.alpha()) : c;
                case LCH, OKLCH -> Double.isNaN(c.c2()) ? new Color(c.space(), c.c0(), c.c1(), Double.NaN, c.alpha()) : c;
                default -> c;
            };
        }

        // ---- Lab / LCH / OKLab

        private static double[] labToXyzD50(double[] lab) {
            double fy = (lab[0] + 16) / 116;
            double fx = lab[1] / 500 + fy;
            double fz = fy - lab[2] / 200;
            double x = fx * fx * fx > LAB_EPSILON ? fx * fx * fx : (116 * fx - 16) / LAB_KAPPA;
            double y = lab[0] > LAB_KAPPA * LAB_EPSILON ? Math.pow((lab[0] + 16) / 116, 3) : lab[0] / LAB_KAPPA;
            double z = fz * fz * fz > LAB_EPSILON ? fz * fz * fz : (116 * fz - 16) / LAB_KAPPA;
            return new double[]{x * D50_WHITE[0], y * D50_WHITE[1], z * D50_WHITE[2]};
        }

        private static double[] xyzD50ToLab(double[] xyz) {
            double fx = labF(xyz[0] / D50_WHITE[0]);
            double fy = labF(xyz[1] / D50_WHITE[1]);
            double fz = labF(xyz[2] / D50_WHITE[2]);
            return new double[]{116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz)};
        }

        private static double labF(double t) {
            return t > LAB_EPSILON ? Math.cbrt(t) : (LAB_KAPPA * t + 16) / 116;
        }

        private static double[] oklabToXyz(double[] lab) {
            double[] lms = multiply(OKLAB_TO_LMS, lab);
            return multiply(LMS_TO_XYZ, new double[]{lms[0] * lms[0] * lms[0], lms[1] * lms[1] * lms[1], lms[2] * lms[2] * lms[2]});
        }

        private static double[] xyzToOklab(double[] xyz) {
            double[] lms = multiply(XYZ_TO_LMS, xyz);
            return multiply(LMS_TO_OKLAB, new double[]{Math.cbrt(lms[0]), Math.cbrt(lms[1]), Math.cbrt(lms[2])});
        }

        private static double[] lchToLab(double[] lch) {
            double h = Math.toRadians(lch[2]);
            return new double[]{lch[0], lch[1] * Math.cos(h), lch[1] * Math.sin(h)};
        }

        /** 彩度が {@code achromatic} 未満なら色相は意味を持たないので NaN にする。 */
        private static double[] labToLch(double[] lab, double achromatic) {
            double chroma = Math.hypot(lab[1], lab[2]);
            double hue = chroma < achromatic ? Double.NaN : ((Math.toDegrees(Math.atan2(lab[2], lab[1])) % 360) + 360) % 360;
            return new double[]{lab[0], chroma, hue};
        }

        // ---- HSL / HWB

        /** hsl（色相は度、s / l は 0〜100）を sRGB（0〜1）にする。 */
        private static double[] hslToSrgb(double[] hsl) {
            double h = ((hsl[0] % 360) + 360) % 360;
            double s = hsl[1] / 100;
            double l = hsl[2] / 100;
            double a = s * Math.min(l, 1 - l);
            return new double[]{hslChannel(0, h, l, a), hslChannel(8, h, l, a), hslChannel(4, h, l, a)};
        }

        private static double hslChannel(int n, double h, double l, double a) {
            double k = (n + h / 30) % 12;
            return l - a * Math.max(-1, Math.min(Math.min(k - 3, 9 - k), 1));
        }

        private static double[] srgbToHsl(double[] rgb) {
            double max = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
            double min = Math.min(rgb[0], Math.min(rgb[1], rgb[2]));
            double l = (min + max) / 2;
            double d = max - min;
            double h = Double.NaN;
            double s = 0;
            if (d != 0) {
                s = l == 0 || l == 1 ? 0 : (max - l) / Math.min(l, 1 - l);
                if (max == rgb[0]) h = (rgb[1] - rgb[2]) / d + (rgb[1] < rgb[2] ? 6 : 0);
                else if (max == rgb[1]) h = (rgb[2] - rgb[0]) / d + 2;
                else h = (rgb[0] - rgb[1]) / d + 4;
                h *= 60;
            }
            if (s < 0) {
                h += 180;
                s = -s;
            }
            if (!Double.isNaN(h) && h >= 360) h -= 360;
            return new double[]{h, s * 100, l * 100};
        }

        private static double[] hwbToSrgb(double[] hwb) {
            double w = hwb[1] / 100;
            double b = hwb[2] / 100;
            if (w + b >= 1) {
                double gray = w / (w + b);
                return new double[]{gray, gray, gray};
            }
            double[] rgb = hslToSrgb(new double[]{hwb[0], 100, 50});
            for (int i = 0; i < 3; i++) rgb[i] = rgb[i] * (1 - w - b) + w;
            return rgb;
        }

        private static double[] srgbToHwb(double[] rgb) {
            double[] hsl = srgbToHsl(rgb);
            double w = Math.min(rgb[0], Math.min(rgb[1], rgb[2]));
            double b = 1 - Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
            return new double[]{hsl[0], w * 100, b * 100};
        }

        // ---- 色域の写像

        private static final double JND = 0.02;
        private static final double GAMUT_EPSILON = 0.0001;

        /**
         * 表示に使う sRGB（0〜1、ガンマ済み）。sRGB の範囲に収まる空間の値は切り詰め、
         * それ以外（lab / lch / oklab / oklch / 広色域 / XYZ）の範囲外は OKLCH の彩度を詰めて写す。
         */
        static double[] toDeviceSrgb(Color c) {
            Color clean = c.withoutMissing();
            if (clean.space().isSrgbBounded()) {
                double[] rgb = clean.space() == Space.SRGB ? clean.components()
                        : fromXyzD65(toXyzD65(clean), Space.SRGB);
                return clip(rgb);
            }
            double[] linear = multiply(XYZ_TO_LIN_SRGB, toXyzD65(clean));
            if (inGamut(linear)) return clip(new double[]{linearToSrgb(linear[0]), linearToSrgb(linear[1]), linearToSrgb(linear[2])});
            return clip(gamutMap(toXyzD65(clean)));
        }

        private static boolean inGamut(double[] linear) {
            double e = 0.000075;
            for (double v : linear) {
                if (v < -e || v > 1 + e) return false;
            }
            return true;
        }

        private static double[] clip(double[] rgb) {
            return new double[]{Math.clamp(rgb[0], 0.0, 1.0), Math.clamp(rgb[1], 0.0, 1.0), Math.clamp(rgb[2], 0.0, 1.0)};
        }

        /** CSS Color 4 §13.2 の写像。XYZ（D65）から sRGB（ガンマ済み）へ。 */
        private static double[] gamutMap(double[] xyz) {
            double[] oklch = fromXyzD65(xyz, Space.OKLCH);
            double lightness = oklch[0];
            double chroma = zero(oklch[1]);
            double hue = zero(oklch[2]);
            if (lightness >= 1) return new double[]{1, 1, 1};
            if (lightness <= 0) return new double[]{0, 0, 0};

            double[] current = srgbOf(lightness, chroma, hue);
            if (inGamut(multiply(XYZ_TO_LIN_SRGB, toXyzD65(new Color(Space.SRGB, current[0], current[1], current[2], 1)))) ) {
                return current;
            }
            double[] clipped = clip(current);
            if (deltaEOK(lightness, chroma, hue, clipped) < JND) return clipped;

            double min = 0;
            double max = chroma;
            boolean minInGamut = true;
            while (max - min > GAMUT_EPSILON) {
                double mid = (min + max) / 2;
                double[] candidate = srgbOf(lightness, mid, hue);
                boolean inside = isSrgbInGamut(candidate);
                if (minInGamut && inside) {
                    min = mid;
                    continue;
                }
                clipped = clip(candidate);
                double e = deltaEOK(lightness, mid, hue, clipped);
                if (e < JND) {
                    if (JND - e < GAMUT_EPSILON) return clipped;
                    minInGamut = false;
                    min = mid;
                } else {
                    max = mid;
                }
            }
            return clipped;
        }

        private static double[] srgbOf(double l, double c, double h) {
            double[] xyz = oklabToXyz(lchToLab(new double[]{l, c, h}));
            return fromXyzD65(xyz, Space.SRGB);
        }

        private static boolean isSrgbInGamut(double[] srgb) {
            double e = 0.0001;
            for (double v : srgb) {
                if (v < -e || v > 1 + e) return false;
            }
            return true;
        }

        private static double deltaEOK(double l, double c, double h, double[] srgb) {
            double[] lab1 = lchToLab(new double[]{l, c, h});
            double[] lab2 = xyzToOklab(multiply(LIN_SRGB_TO_XYZ, linearFromSrgb(srgb)));
            return Math.sqrt(Math.pow(lab1[0] - lab2[0], 2) + Math.pow(lab1[1] - lab2[1], 2) + Math.pow(lab1[2] - lab2[2], 2));
        }
    }

    /** CSS Color 4 §6.1 の名前付き色（148 色）。 */
    private static final Map<String, Integer> NAMED_COLORS = new HashMap<>();

    static {
        Object[] table = {
                "aliceblue", 0xF0F8FF, "antiquewhite", 0xFAEBD7, "aqua", 0x00FFFF, "aquamarine", 0x7FFFD4,
                "azure", 0xF0FFFF, "beige", 0xF5F5DC, "bisque", 0xFFE4C4, "black", 0x000000,
                "blanchedalmond", 0xFFEBCD, "blue", 0x0000FF, "blueviolet", 0x8A2BE2, "brown", 0xA52A2A,
                "burlywood", 0xDEB887, "cadetblue", 0x5F9EA0, "chartreuse", 0x7FFF00, "chocolate", 0xD2691E,
                "coral", 0xFF7F50, "cornflowerblue", 0x6495ED, "cornsilk", 0xFFF8DC, "crimson", 0xDC143C,
                "cyan", 0x00FFFF, "darkblue", 0x00008B, "darkcyan", 0x008B8B, "darkgoldenrod", 0xB8860B,
                "darkgray", 0xA9A9A9, "darkgreen", 0x006400, "darkgrey", 0xA9A9A9, "darkkhaki", 0xBDB76B,
                "darkmagenta", 0x8B008B, "darkolivegreen", 0x556B2F, "darkorange", 0xFF8C00, "darkorchid", 0x9932CC,
                "darkred", 0x8B0000, "darksalmon", 0xE9967A, "darkseagreen", 0x8FBC8F, "darkslateblue", 0x483D8B,
                "darkslategray", 0x2F4F4F, "darkslategrey", 0x2F4F4F, "darkturquoise", 0x00CED1, "darkviolet", 0x9400D3,
                "deeppink", 0xFF1493, "deepskyblue", 0x00BFFF, "dimgray", 0x696969, "dimgrey", 0x696969,
                "dodgerblue", 0x1E90FF, "firebrick", 0xB22222, "floralwhite", 0xFFFAF0, "forestgreen", 0x228B22,
                "fuchsia", 0xFF00FF, "gainsboro", 0xDCDCDC, "ghostwhite", 0xF8F8FF, "gold", 0xFFD700,
                "goldenrod", 0xDAA520, "gray", 0x808080, "green", 0x008000, "greenyellow", 0xADFF2F,
                "grey", 0x808080, "honeydew", 0xF0FFF0, "hotpink", 0xFF69B4, "indianred", 0xCD5C5C,
                "indigo", 0x4B0082, "ivory", 0xFFFFF0, "khaki", 0xF0E68C, "lavender", 0xE6E6FA,
                "lavenderblush", 0xFFF0F5, "lawngreen", 0x7CFC00, "lemonchiffon", 0xFFFACD, "lightblue", 0xADD8E6,
                "lightcoral", 0xF08080, "lightcyan", 0xE0FFFF, "lightgoldenrodyellow", 0xFAFAD2, "lightgray", 0xD3D3D3,
                "lightgreen", 0x90EE90, "lightgrey", 0xD3D3D3, "lightpink", 0xFFB6C1, "lightsalmon", 0xFFA07A,
                "lightseagreen", 0x20B2AA, "lightskyblue", 0x87CEFA, "lightslategray", 0x778899, "lightslategrey", 0x778899,
                "lightsteelblue", 0xB0C4DE, "lightyellow", 0xFFFFE0, "lime", 0x00FF00, "limegreen", 0x32CD32,
                "linen", 0xFAF0E6, "magenta", 0xFF00FF, "maroon", 0x800000, "mediumaquamarine", 0x66CDAA,
                "mediumblue", 0x0000CD, "mediumorchid", 0xBA55D3, "mediumpurple", 0x9370DB, "mediumseagreen", 0x3CB371,
                "mediumslateblue", 0x7B68EE, "mediumspringgreen", 0x00FA9A, "mediumturquoise", 0x48D1CC,
                "mediumvioletred", 0xC71585, "midnightblue", 0x191970, "mintcream", 0xF5FFFA, "mistyrose", 0xFFE4E1,
                "moccasin", 0xFFE4B5, "navajowhite", 0xFFDEAD, "navy", 0x000080, "oldlace", 0xFDF5E6,
                "olive", 0x808000, "olivedrab", 0x6B8E23, "orange", 0xFFA500, "orangered", 0xFF4500,
                "orchid", 0xDA70D6, "palegoldenrod", 0xEEE8AA, "palegreen", 0x98FB98, "paleturquoise", 0xAFEEEE,
                "palevioletred", 0xDB7093, "papayawhip", 0xFFEFD5, "peachpuff", 0xFFDAB9, "peru", 0xCD853F,
                "pink", 0xFFC0CB, "plum", 0xDDA0DD, "powderblue", 0xB0E0E6, "purple", 0x800080,
                "rebeccapurple", 0x663399, "red", 0xFF0000, "rosybrown", 0xBC8F8F, "royalblue", 0x4169E1,
                "saddlebrown", 0x8B4513, "salmon", 0xFA8072, "sandybrown", 0xF4A460, "seagreen", 0x2E8B57,
                "seashell", 0xFFF5EE, "sienna", 0xA0522D, "silver", 0xC0C0C0, "skyblue", 0x87CEEB,
                "slateblue", 0x6A5ACD, "slategray", 0x708090, "slategrey", 0x708090, "snow", 0xFFFAFA,
                "springgreen", 0x00FF7F, "steelblue", 0x4682B4, "tan", 0xD2B48C, "teal", 0x008080,
                "thistle", 0xD8BFD8, "tomato", 0xFF6347, "turquoise", 0x40E0D0, "violet", 0xEE82EE,
                "wheat", 0xF5DEB3, "white", 0xFFFFFF, "whitesmoke", 0xF5F5F5, "yellow", 0xFFFF00,
                "yellowgreen", 0x9ACD32,
        };
        for (int i = 0; i < table.length; i += 2) {
            NAMED_COLORS.put((String) table[i], (Integer) table[i + 1]);
        }
    }

    /** 名前付き色の数（テスト用）。 */
    static int namedColorCount() {
        return NAMED_COLORS.size();
    }
}
