package com.nishiyu.enderframelib.core.paint;

import java.util.Locale;

/**
 * {@code mix-blend-mode} の合成（Compositing and Blending 1 §10）。
 *
 * <p>層の合成シェーダー（{@code LayerCompositor}）の式を Java で持つ。シェーダーの {@code blendMode}
 * は {@link #ordinal()} と同じ番号。テストと参照描画（AWT）が使う。色は 0〜1 の sRGB（アルファで割ったストレートな値）。
 */
public enum BlendMode {
    // 順序はシェーダーの番号と同じ（変えるときはシェーダーも）
    NORMAL, MULTIPLY, SCREEN, OVERLAY, DARKEN, LIGHTEN, COLOR_DODGE, COLOR_BURN, HARD_LIGHT, SOFT_LIGHT,
    DIFFERENCE, EXCLUSION, HUE, SATURATION, COLOR, LUMINOSITY, PLUS_LIGHTER;

    public String cssName() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** CSS のキーワードから。知らない値・{@code null} は {@link #NORMAL}。 */
    public static BlendMode fromCss(String keyword) {
        if (keyword == null) return NORMAL;
        String k = keyword.trim().toLowerCase(Locale.ROOT);
        for (BlendMode m : values()) {
            if (m.cssName().equals(k)) return m;
        }
        return NORMAL;
    }

    /**
     * 合成の式。{@code cb} は下（背景）の色、{@code ab} は下のアルファ、{@code cs} は上（この層）の色、{@code as} は上のアルファ。
     * 結果は {@code {r, g, b, a}}（色はアルファを掛けたプリマルチプライド）。
     */
    public static float[] composite(BlendMode mode, float[] cb, float ab, float[] cs, float as) {
        float[] out = new float[4];
        out[3] = as + ab * (1f - as);
        if (mode == PLUS_LIGHTER) {
            for (int i = 0; i < 3; i++) out[i] = Math.min(1f, cs[i] * as + cb[i] * ab);
            out[3] = Math.min(1f, as + ab);
            return out;
        }
        float[] b = blend(mode, cb, cs);
        for (int i = 0; i < 3; i++) {
            out[i] = as * (1f - ab) * cs[i] + as * ab * b[i] + (1f - as) * ab * cb[i];
        }
        return out;
    }

    /** 混ぜた色 B(cb, cs)（アルファを含まない）。 */
    public static float[] blend(BlendMode mode, float[] cb, float[] cs) {
        float[] out = new float[3];
        switch (mode) {
            case HUE -> {
                return setLum(setSat(cs, sat(cb)), lum(cb));
            }
            case SATURATION -> {
                return setLum(setSat(cb, sat(cs)), lum(cb));
            }
            case COLOR -> {
                return setLum(cs, lum(cb));
            }
            case LUMINOSITY -> {
                return setLum(cb, lum(cs));
            }
            default -> {
                for (int i = 0; i < 3; i++) out[i] = separable(mode, cb[i], cs[i]);
                return out;
            }
        }
    }

    static float separable(BlendMode mode, float cb, float cs) {
        return switch (mode) {
            case MULTIPLY -> cb * cs;
            case SCREEN -> cb + cs - cb * cs;
            case OVERLAY -> hardLight(cs, cb);
            case DARKEN -> Math.min(cb, cs);
            case LIGHTEN -> Math.max(cb, cs);
            case COLOR_DODGE -> cb == 0f ? 0f : cs >= 1f ? 1f : Math.min(1f, cb / (1f - cs));
            case COLOR_BURN -> cb >= 1f ? 1f : cs <= 0f ? 0f : 1f - Math.min(1f, (1f - cb) / cs);
            case HARD_LIGHT -> hardLight(cb, cs);
            case SOFT_LIGHT -> softLight(cb, cs);
            case DIFFERENCE -> Math.abs(cb - cs);
            case EXCLUSION -> cb + cs - 2f * cb * cs;
            default -> cs; // NORMAL
        };
    }

    private static float hardLight(float cb, float cs) {
        return cs <= 0.5f ? cb * (2f * cs) : cb + (2f * cs - 1f) - cb * (2f * cs - 1f);
    }

    private static float softLight(float cb, float cs) {
        if (cs <= 0.5f) return cb - (1f - 2f * cs) * cb * (1f - cb);
        float d = cb <= 0.25f ? ((16f * cb - 12f) * cb + 4f) * cb : (float) Math.sqrt(cb);
        return cb + (2f * cs - 1f) * (d - cb);
    }

    // ---- 色相・彩度・輝度（非分離）----

    static float lum(float[] c) {
        return 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2];
    }

    static float sat(float[] c) {
        return Math.max(c[0], Math.max(c[1], c[2])) - Math.min(c[0], Math.min(c[1], c[2]));
    }

    private static float[] clipColor(float[] c) {
        float l = lum(c);
        float n = Math.min(c[0], Math.min(c[1], c[2]));
        float x = Math.max(c[0], Math.max(c[1], c[2]));
        float[] out = c.clone();
        if (n < 0f) for (int i = 0; i < 3; i++) out[i] = l + (out[i] - l) * l / (l - n);
        if (x > 1f) for (int i = 0; i < 3; i++) out[i] = l + (out[i] - l) * (1f - l) / (x - l);
        return out;
    }

    static float[] setLum(float[] c, float l) {
        float d = l - lum(c);
        return clipColor(new float[]{c[0] + d, c[1] + d, c[2] + d});
    }

    static float[] setSat(float[] c, float s) {
        int max = 0, min = 0;
        for (int i = 1; i < 3; i++) {
            if (c[i] > c[max]) max = i;
            if (c[i] < c[min]) min = i;
        }
        if (max == min) return new float[]{0f, 0f, 0f};
        int mid = 3 - max - min;
        float[] out = new float[3];
        float range = c[max] - c[min];
        out[mid] = (c[mid] - c[min]) * s / range;
        out[max] = s;
        out[min] = 0f;
        return out;
    }
}
