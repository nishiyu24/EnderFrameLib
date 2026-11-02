package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.css.CssSyntax;
import com.nishiyu.enderframelib.core.css.CssSyntax.ComponentValue;
import com.nishiyu.enderframelib.core.css.CssSyntax.Token;
import com.nishiyu.enderframelib.core.css.CssSyntax.Type;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * CSS の {@code filter}（Filter Effects 1）を、レイヤーの合成が順に適用できる操作の列に解く。
 *
 * <p>色を変える関数（{@code brightness} / {@code contrast} / {@code grayscale} / {@code hue-rotate} / {@code invert} /
 * {@code saturate} / {@code sepia}）はどれも sRGB の色（アルファで割った値）への 3x4 の線形変換なので、
 * 隣り合うものは 1 つの行列に畳める（{@link #optimize}）。{@code opacity} はアルファへの倍率、{@code blur} と
 * {@code drop-shadow} は周りの画素を使うので別の操作として残る。{@code url()} は読めない（その値は無効で、フィルターなし）。
 *
 * <p>シェーダーは {@link #matrix} をそのまま uniform に渡す。ここの {@link #applyColor} は同じ変換の CPU 版で、
 * テストと参照描画（AWT）が使う。
 *
 * @param matrix {@code COLOR_MATRIX} の 3x4（行 R・G・B が {@code r g b offset} の 12 値）
 * @param amount {@code OPACITY} の倍率と {@code BLUR} の標準偏差（px）。{@code DROP_SHADOW} ではぼかしの標準偏差
 * @param dx     {@code DROP_SHADOW} の横のずれ
 * @param dy     同じく縦のずれ
 * @param color  {@code DROP_SHADOW} の色（ARGB）
 */
public record FilterOp(Kind kind, float[] matrix, float amount, float dx, float dy, int color) {

    public enum Kind { COLOR_MATRIX, OPACITY, BLUR, DROP_SHADOW }

    public static FilterOp color(float[] m) {
        return new FilterOp(Kind.COLOR_MATRIX, m, 0f, 0f, 0f, 0);
    }

    public static FilterOp opacity(float a) {
        return new FilterOp(Kind.OPACITY, null, a, 0f, 0f, 0);
    }

    public static FilterOp blur(float sigma) {
        return new FilterOp(Kind.BLUR, null, sigma, 0f, 0f, 0);
    }

    public static FilterOp dropShadow(float dx, float dy, float sigma, int color) {
        return new FilterOp(Kind.DROP_SHADOW, null, sigma, dx, dy, color);
    }

    /** 長さを px にする文脈（{@code blur()} と {@code drop-shadow()} の長さ）。 */
    public record Context(float rootW, float rootH, float fontSize, float rootFontSize, int currentColor) {}

    private static final float[] IDENTITY = {1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0};

    /** 値を操作の列にする。{@code none}・空・読めない値は空（フィルターなし）。 */
    public static List<FilterOp> parse(String value, Context ctx) {
        List<FilterOp> out = new ArrayList<>();
        if (value == null) return out;
        String v = value.trim();
        if (v.isEmpty() || v.equalsIgnoreCase("none")) return out;
        for (ComponentValue cv : CssSyntax.parseComponentValues(v)) {
            if (CssSyntax.isWhitespace(cv)) continue;
            if (!(cv instanceof CssSyntax.Function fn)) return List.of();
            FilterOp op = function(fn, ctx);
            if (op == null) return List.of();
            out.add(op);
        }
        return optimize(out);
    }

    private static FilterOp function(CssSyntax.Function fn, Context ctx) {
        String name = fn.name().toLowerCase(Locale.ROOT);
        List<ComponentValue> args = new ArrayList<>();
        for (ComponentValue a : fn.args()) if (!CssSyntax.isWhitespace(a)) args.add(a);
        switch (name) {
            case "blur" -> {
                float sigma = args.isEmpty() ? 0f : length(args.get(0), ctx);
                return Float.isNaN(sigma) || sigma < 0 ? null : FilterOp.blur(sigma);
            }
            case "brightness" -> {
                float b = amount(args, 1f, false);
                return Float.isNaN(b) ? null : FilterOp.color(new float[]{b, 0, 0, 0, 0, b, 0, 0, 0, 0, b, 0});
            }
            case "contrast" -> {
                float c = amount(args, 1f, false);
                if (Float.isNaN(c)) return null;
                float o = 0.5f - 0.5f * c;
                return FilterOp.color(new float[]{c, 0, 0, o, 0, c, 0, o, 0, 0, c, o});
            }
            case "grayscale" -> {
                float a = amount(args, 1f, true);
                return Float.isNaN(a) ? null : FilterOp.color(grayscale(a));
            }
            case "sepia" -> {
                float a = amount(args, 1f, true);
                return Float.isNaN(a) ? null : FilterOp.color(sepia(a));
            }
            case "saturate" -> {
                float s = amount(args, 1f, false);
                return Float.isNaN(s) ? null : FilterOp.color(saturate(s));
            }
            case "invert" -> {
                float a = amount(args, 1f, true);
                if (Float.isNaN(a)) return null;
                float k = 1f - 2f * a;
                return FilterOp.color(new float[]{k, 0, 0, a, 0, k, 0, a, 0, 0, k, a});
            }
            case "opacity" -> {
                float a = amount(args, 1f, true);
                return Float.isNaN(a) ? null : FilterOp.opacity(a);
            }
            case "hue-rotate" -> {
                double rad = args.isEmpty() ? 0 : angle(args.get(0));
                return Double.isNaN(rad) ? null : FilterOp.color(hueRotate(rad));
            }
            case "drop-shadow" -> {
                return dropShadow(args, ctx);
            }
            default -> {
                return null;
            }
        }
    }

    /** {@code drop-shadow(<offset-x> <offset-y> [<blur>] [<color>])}。色は前後どちらにも書ける。 */
    private static FilterOp dropShadow(List<ComponentValue> args, Context ctx) {
        List<Float> lengths = new ArrayList<>();
        int color = ctx.currentColor();
        for (ComponentValue a : args) {
            Token t = CssSyntax.tokenOf(a);
            boolean isLength = t != null && (t.type() == Type.NUMBER || t.type() == Type.DIMENSION)
                    || a instanceof CssSyntax.Function f && isMath(f);
            if (isLength) {
                float len = length(a, ctx);
                if (Float.isNaN(len)) return null;
                lengths.add(len);
            } else {
                String text = CssSyntax.serialize(a).trim();
                if (!ColorParser.isColor(text)) return null;
                color = ColorParser.parseColor(text);
            }
        }
        if (lengths.size() < 2 || lengths.size() > 3) return null;
        float blur = lengths.size() > 2 ? lengths.get(2) : 0f;
        if (blur < 0) return null;
        // drop-shadow() のぼかしは box-shadow と違い標準偏差そのもの
        return FilterOp.dropShadow(lengths.get(0), lengths.get(1), blur, color);
    }

    private static boolean isMath(CssSyntax.Function f) {
        String n = f.name().toLowerCase(Locale.ROOT);
        return n.equals("calc") || n.equals("min") || n.equals("max") || n.equals("clamp");
    }

    // ---- 行列 ----

    static float[] grayscale(float a) {
        float s = 1f - a;
        return new float[]{
                0.2126f + 0.7874f * s, 0.7152f - 0.7152f * s, 0.0722f - 0.0722f * s, 0,
                0.2126f - 0.2126f * s, 0.7152f + 0.2848f * s, 0.0722f - 0.0722f * s, 0,
                0.2126f - 0.2126f * s, 0.7152f - 0.7152f * s, 0.0722f + 0.9278f * s, 0};
    }

    static float[] sepia(float a) {
        float s = 1f - a;
        return new float[]{
                0.393f + 0.607f * s, 0.769f - 0.769f * s, 0.189f - 0.189f * s, 0,
                0.349f - 0.349f * s, 0.686f + 0.314f * s, 0.168f - 0.168f * s, 0,
                0.272f - 0.272f * s, 0.534f - 0.534f * s, 0.131f + 0.869f * s, 0};
    }

    static float[] saturate(float s) {
        return new float[]{
                0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0,
                0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0,
                0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0};
    }

    static float[] hueRotate(double rad) {
        float c = (float) Math.cos(rad), s = (float) Math.sin(rad);
        return new float[]{
                0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0,
                0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0,
                0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0};
    }

    /** {@code second ∘ first}（先に {@code first}、次に {@code second} を色へ掛ける）の 3x4 行列。 */
    static float[] multiply(float[] second, float[] first) {
        float[] out = new float[12];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                out[row * 4 + col] = second[row * 4] * first[col] + second[row * 4 + 1] * first[4 + col] + second[row * 4 + 2] * first[8 + col];
            }
            out[row * 4 + 3] = second[row * 4] * first[3] + second[row * 4 + 1] * first[7] + second[row * 4 + 2] * first[11] + second[row * 4 + 3];
        }
        return out;
    }

    /** 隣り合う色の行列を 1 つに畳む。 */
    public static List<FilterOp> optimize(List<FilterOp> ops) {
        List<FilterOp> out = new ArrayList<>();
        for (FilterOp op : ops) {
            if (op.kind() == Kind.COLOR_MATRIX && !out.isEmpty() && out.getLast().kind() == Kind.COLOR_MATRIX) {
                out.set(out.size() - 1, FilterOp.color(multiply(op.matrix(), out.getLast().matrix())));
            } else {
                out.add(op);
            }
        }
        return out;
    }

    /** 色の行列を、アルファで割った（ストレートな）sRGB の色 {@code rgb[3]} に掛ける。結果は 0〜1 に切る。 */
    public static float[] applyColor(float[] m, float[] rgb) {
        float[] out = new float[3];
        for (int row = 0; row < 3; row++) {
            float v = m[row * 4] * rgb[0] + m[row * 4 + 1] * rgb[1] + m[row * 4 + 2] * rgb[2] + m[row * 4 + 3];
            out[row] = Math.max(0f, Math.min(1f, v));
        }
        return out;
    }

    /** 恒等行列か。 */
    public static boolean isIdentity(float[] m) {
        for (int i = 0; i < 12; i++) if (Math.abs(m[i] - IDENTITY[i]) > 1e-6f) return false;
        return true;
    }

    // ---- 引数 ----

    /** 数か割合（% は 100 で割る）。省略は {@code def}、読めない・負（{@code clamp01} なら 1 を越える値は 1）は NaN。 */
    private static float amount(List<ComponentValue> args, float def, boolean clamp01) {
        if (args.isEmpty()) return def;
        Token t = CssSyntax.tokenOf(args.get(0));
        if (t == null) return Float.NaN;
        float v;
        if (t.type() == Type.NUMBER) v = (float) t.number();
        else if (t.type() == Type.PERCENTAGE) v = (float) (t.number() / 100.0);
        else return Float.NaN;
        if (v < 0) return Float.NaN;
        return clamp01 ? Math.min(v, 1f) : v;
    }

    private static float length(ComponentValue cv, Context ctx) {
        Token t = CssSyntax.tokenOf(cv);
        if (t != null && t.type() == Type.NUMBER && t.number() != 0) return Float.NaN; // 単位のない数は 0 だけ（長さ）
        if (t != null && t.type() != Type.NUMBER && t.type() != Type.DIMENSION) return Float.NaN;
        String text = CssSyntax.serialize(cv).trim();
        return com.nishiyu.enderframelib.core.css.LengthResolver.parseDim(text, 0f, ctx.rootW(), ctx.rootH(), ctx.fontSize(), ctx.rootFontSize());
    }

    /** 角度（ラジアン）。単位なしの 0 は 0。読めなければ NaN。 */
    private static double angle(ComponentValue cv) {
        Token t = CssSyntax.tokenOf(cv);
        if (t == null) return Double.NaN;
        if (t.type() == Type.NUMBER) return t.number() == 0 ? 0 : Double.NaN;
        if (t.type() != Type.DIMENSION) return Double.NaN;
        return switch (t.unit().toLowerCase(Locale.ROOT)) {
            case "deg" -> Math.toRadians(t.number());
            case "rad" -> t.number();
            case "grad" -> t.number() * Math.PI / 200.0;
            case "turn" -> t.number() * 2 * Math.PI;
            default -> Double.NaN;
        };
    }
}
