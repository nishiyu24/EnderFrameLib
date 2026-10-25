package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;
import com.nishiyu.enderframelib.core.css.LengthResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code transform} と個別プロパティ（{@code translate} / {@code rotate} / {@code scale}）を、{@link CssSyntax} のトークン列から
 * 宣言順どおりに 2D のアフィン行列へ合成する（CSS Transforms 2 §6・§13）。
 *
 * <p>値は {@code [a c e; b d f]} で、点は {@code (x, y) → (a x + c y + e, b x + d y + f)}。{@code matrix(a, b, c, d, e, f)} と同じ並び。
 * 関数は左から右へ座標系に積むので、{@code translate(10px) scale(2)} の行列は {@code T · S}（点にはまず {@code scale} が掛かる）、
 * {@code scale(2) translate(10px)} は {@code S · T} で平行移動が 20px になる。
 *
 * <p>LIMITATION(制約): 描画項目は平行移動・回転・拡縮（{@link #toTrs}）しか運べないので、{@code skew*} と {@code matrix()} の
 * せん断は描かれない（行列の回転・拡縮・平行移動の成分だけが効く）。3D の関数（{@code translateZ}・{@code rotateX/Y}・{@code perspective}）の
 * 奥行きの成分も描かれない。README の "Not supported" に対応。
 */
public final class Transforms {
    private Transforms() {}

    /** 2D アフィン行列。 */
    public record Affine(double a, double b, double c, double d, double e, double f) {
        public static final Affine IDENTITY = new Affine(1, 0, 0, 1, 0, 0);

        /** {@code this · other}（{@code other} を先に点へ掛ける）。 */
        public Affine then(Affine other) {
            return new Affine(
                    a * other.a + c * other.b, b * other.a + d * other.b,
                    a * other.c + c * other.d, b * other.c + d * other.d,
                    a * other.e + c * other.f + e, b * other.e + d * other.f + f);
        }
    }

    /** 長さを px にするための文脈。{@code %} の基準は軸ごとに箱の幅・高さ。 */
    public record Context(float boxW, float boxH, float rootW, float rootH, float fontSize, float rootFontSize) {
        float length(List<ComponentValue> arg, float percentBase) {
            return LengthResolver.parseDim(CssSyntax.serialize(arg), percentBase, rootW, rootH, fontSize, rootFontSize);
        }
    }

    /**
     * {@code translate} → {@code rotate} → {@code scale} → {@code transform} の順に合成する（個別プロパティが先、CSS Transforms 2 §6）。
     * 値が null・空・{@code none} のものは飛ばす。
     */
    public static Affine compose(String translate, String rotate, String scale, String transform, Context ctx) {
        Affine m = Affine.IDENTITY;
        if (isSet(translate)) m = m.then(translateProperty(translate, ctx));
        if (isSet(rotate)) m = m.then(rotateProperty(rotate));
        if (isSet(scale)) m = m.then(scaleProperty(scale));
        if (isSet(transform)) m = m.then(parse(transform, ctx));
        return m;
    }

    private static boolean isSet(String value) {
        if (value == null) return false;
        String v = value.trim();
        return !v.isEmpty() && !v.equalsIgnoreCase("none");
    }

    /** {@code transform} の値（関数のリスト）を宣言順に合成する。知らない関数は飛ばす。 */
    public static Affine parse(String transform, Context ctx) {
        Affine m = Affine.IDENTITY;
        if (!isSet(transform)) return m;
        for (ComponentValue value : CssSyntax.parseComponentValues(transform)) {
            if (value instanceof CssSyntax.Function fn) m = m.then(function(fn, ctx));
        }
        return m;
    }

    private static Affine function(CssSyntax.Function fn, Context ctx) {
        List<List<ComponentValue>> args = arguments(fn);
        String name = fn.name().toLowerCase(Locale.ROOT);
        if (args.isEmpty()) return Affine.IDENTITY;
        switch (name) {
            case "translate", "translate3d" -> {
                float x = ctx.length(args.get(0), ctx.boxW());
                float y = args.size() > 1 ? ctx.length(args.get(1), ctx.boxH()) : 0f;
                return translation(x, y);
            }
            case "translatex" -> {
                return translation(ctx.length(args.get(0), ctx.boxW()), 0f);
            }
            case "translatey" -> {
                return translation(0f, ctx.length(args.get(0), ctx.boxH()));
            }
            case "scale" -> {
                double sx = number(args.get(0), 1);
                return scaling(sx, args.size() > 1 ? number(args.get(1), sx) : sx);
            }
            case "scale3d" -> {
                return scaling(number(args.get(0), 1), args.size() > 1 ? number(args.get(1), 1) : 1);
            }
            case "scalex" -> {
                return scaling(number(args.get(0), 1), 1);
            }
            case "scaley" -> {
                return scaling(1, number(args.get(0), 1));
            }
            case "rotate", "rotatez" -> {
                return rotation(angle(args.get(0)));
            }
            case "rotate3d" -> {
                // z 軸まわりだけが 2D に効く（x・y 軸まわりは奥行きの変化で描かれない）
                if (args.size() < 4 || number(args.get(0), 0) != 0 || number(args.get(1), 0) != 0) return Affine.IDENTITY;
                double z = number(args.get(2), 0);
                return z == 0 ? Affine.IDENTITY : rotation(angle(args.get(3)) * Math.signum(z));
            }
            case "skew" -> {
                double ax = angle(args.get(0));
                double ay = args.size() > 1 ? angle(args.get(1)) : 0;
                return new Affine(1, Math.tan(ay), Math.tan(ax), 1, 0, 0);
            }
            case "skewx" -> {
                return new Affine(1, 0, Math.tan(angle(args.get(0))), 1, 0, 0);
            }
            case "skewy" -> {
                return new Affine(1, Math.tan(angle(args.get(0))), 0, 1, 0, 0);
            }
            case "matrix" -> {
                if (args.size() != 6) return Affine.IDENTITY;
                return new Affine(number(args.get(0), 1), number(args.get(1), 0), number(args.get(2), 0),
                        number(args.get(3), 1), number(args.get(4), 0), number(args.get(5), 0));
            }
            case "matrix3d" -> {
                // 列優先の 4x4 の、x・y の面だけを取る（m11 m12 / m21 m22 / m41 m42）
                if (args.size() != 16) return Affine.IDENTITY;
                return new Affine(number(args.get(0), 1), number(args.get(1), 0), number(args.get(4), 0),
                        number(args.get(5), 1), number(args.get(12), 0), number(args.get(13), 0));
            }
            default -> {
                return Affine.IDENTITY;
            }
        }
    }

    private static Affine translateProperty(String value, Context ctx) {
        List<ComponentValue> parts = nonBlank(CssSyntax.parseComponentValues(value));
        if (parts.isEmpty()) return Affine.IDENTITY;
        float x = ctx.length(List.of(parts.get(0)), ctx.boxW());
        float y = parts.size() > 1 ? ctx.length(List.of(parts.get(1)), ctx.boxH()) : 0f;
        return translation(x, y);
    }

    private static Affine rotateProperty(String value) {
        List<ComponentValue> parts = nonBlank(CssSyntax.parseComponentValues(value));
        if (parts.isEmpty()) return Affine.IDENTITY;
        // 「軸 角度」の形は z 軸だけを 2D に効かせ、「角度」だけの形は z 軸まわり
        Token first = CssSyntax.tokenOf(parts.get(0));
        if (first != null && first.type() == Type.IDENT) {
            String axis = first.value().toLowerCase(Locale.ROOT);
            return axis.equals("z") && parts.size() > 1 ? rotation(angle(List.of(parts.get(1)))) : Affine.IDENTITY;
        }
        return rotation(angle(List.of(parts.get(parts.size() - 1))));
    }

    private static Affine scaleProperty(String value) {
        List<ComponentValue> parts = nonBlank(CssSyntax.parseComponentValues(value));
        if (parts.isEmpty()) return Affine.IDENTITY;
        double sx = number(List.of(parts.get(0)), 1);
        double sy = parts.size() > 1 ? number(List.of(parts.get(1)), sx) : sx;
        return scaling(sx, sy);
    }

    // ---- 行列の作り方 ----

    private static Affine translation(double x, double y) {
        return new Affine(1, 0, 0, 1, x, y);
    }

    private static Affine scaling(double sx, double sy) {
        return new Affine(sx, 0, 0, sy, 0, 0);
    }

    private static Affine rotation(double radians) {
        double cos = Math.cos(radians), sin = Math.sin(radians);
        // 90° の倍数での丸め誤差（cos(π/2) ≈ 6e-17）が、軸平行かの判定を崩さないようにする
        if (Math.abs(cos) < 1e-12) cos = 0;
        if (Math.abs(sin) < 1e-12) sin = 0;
        return new Affine(cos, sin, -sin, cos, 0, 0);
    }

    // ---- 引数 ----

    /** 関数の引数をカンマで区切り、前後の空白を除いて返す。 */
    private static List<List<ComponentValue>> arguments(CssSyntax.Function fn) {
        List<List<ComponentValue>> out = new ArrayList<>();
        List<ComponentValue> current = new ArrayList<>();
        for (ComponentValue v : fn.args()) {
            if (CssSyntax.isToken(v, Type.COMMA)) {
                out.add(CssSyntax.trim(current));
                current = new ArrayList<>();
            } else {
                current.add(v);
            }
        }
        List<ComponentValue> last = CssSyntax.trim(current);
        if (!last.isEmpty() || !out.isEmpty()) out.add(last);
        return out;
    }

    private static List<ComponentValue> nonBlank(List<ComponentValue> values) {
        List<ComponentValue> out = new ArrayList<>();
        for (ComponentValue v : values) {
            if (!CssSyntax.isWhitespace(v)) out.add(v);
        }
        return out;
    }

    private static double number(List<ComponentValue> arg, double fallback) {
        if (arg.size() != 1) return fallback;
        Token t = CssSyntax.tokenOf(arg.getFirst());
        if (t == null) return fallback;
        return switch (t.type()) {
            case NUMBER -> t.number();
            case PERCENTAGE -> t.number() / 100.0;
            default -> fallback;
        };
    }

    /** 角度をラジアンにする。単位なしの 0 は 0。知らない単位は 0。 */
    private static double angle(List<ComponentValue> arg) {
        if (arg.size() != 1) return 0;
        Token t = CssSyntax.tokenOf(arg.getFirst());
        if (t == null) return 0;
        // 単位のない数は度として読む（仕様では 0 だけが単位なしで書けるが、これまでの文書が度のつもりで書いている）
        if (t.type() == Type.NUMBER) return Math.toRadians(t.number());
        if (t.type() != Type.DIMENSION) return 0;
        return switch (t.unit().toLowerCase(Locale.ROOT)) {
            case "deg" -> Math.toRadians(t.number());
            case "rad" -> t.number();
            case "grad" -> t.number() * Math.PI / 200.0;
            case "turn" -> t.number() * 2 * Math.PI;
            default -> 0;
        };
    }

    // ---- 描画項目へ ----

    /**
     * 行列を、描画項目が運べる 平行移動・拡縮・回転 に分ける（{@code M = T · R · S}。せん断は捨てる）。
     *
     * @return {@code {translateX, translateY, scaleX, scaleY, rotate(度)}}
     */
    public static float[] toTrs(Affine m) {
        double sx, sy, rot;
        if (m.b() == 0 && m.c() == 0) {
            // 軸平行（負の拡大は反転のまま。回転 180° に化けさせない）
            sx = m.a();
            sy = m.d();
            rot = 0;
        } else {
            sx = Math.hypot(m.a(), m.b());
            rot = Math.toDegrees(Math.atan2(m.b(), m.a()));
            double det = m.a() * m.d() - m.b() * m.c();
            sy = sx == 0 ? Math.hypot(m.c(), m.d()) : det / sx;
        }
        return new float[]{(float) m.e(), (float) m.f(), (float) sx, (float) sy, (float) rot};
    }
}
