package com.nishiyu.enderframelib.core.raster;

import java.util.Locale;

/**
 * 合成演算（Canvas 2D の {@code globalCompositeOperation}）。入出力は事前乗算済みの ARGB。
 * Porter-Duff の 11 種と、W3C Compositing の分離可能なブレンド 11 種。
 * 非分離のブレンド（hue / saturation / color / luminosity）は source-over として扱う。
 */
public enum CompositeOp {
    SOURCE_OVER("source-over"),
    SOURCE_IN("source-in"),
    SOURCE_OUT("source-out"),
    SOURCE_ATOP("source-atop"),
    DESTINATION_OVER("destination-over"),
    DESTINATION_IN("destination-in"),
    DESTINATION_OUT("destination-out"),
    DESTINATION_ATOP("destination-atop"),
    LIGHTER("lighter"),
    COPY("copy"),
    XOR("xor"),
    MULTIPLY("multiply"),
    SCREEN("screen"),
    OVERLAY("overlay"),
    DARKEN("darken"),
    LIGHTEN("lighten"),
    COLOR_DODGE("color-dodge"),
    COLOR_BURN("color-burn"),
    HARD_LIGHT("hard-light"),
    SOFT_LIGHT("soft-light"),
    DIFFERENCE("difference"),
    EXCLUSION("exclusion");

    private final String cssName;

    CompositeOp(String cssName) {
        this.cssName = cssName;
    }

    public String cssName() {
        return cssName;
    }

    /** 知らない名前は {@code null}（Canvas は無視する）。 */
    public static CompositeOp parse(String name) {
        if (name == null) return null;
        String n = name.trim().toLowerCase(Locale.ROOT);
        for (CompositeOp op : values()) if (op.cssName.equals(n)) return op;
        return switch (n) {
            case "hue", "saturation", "color", "luminosity" -> SOURCE_OVER;
            default -> null;
        };
    }

    /** 図形の外（被覆率 0 の画素）でも結果が変わる演算か（クリップの内側の全体に効く）。 */
    public boolean affectsOutsideShape() {
        return this == SOURCE_IN || this == SOURCE_OUT || this == COPY || this == DESTINATION_IN || this == DESTINATION_ATOP;
    }

    private boolean isBlend() {
        return ordinal() >= MULTIPLY.ordinal();
    }

    /** {@code src} を {@code dst} へ合成した事前乗算済みの ARGB。 */
    public int apply(int src, int dst) {
        switch (this) {
            case SOURCE_OVER -> {
                int sa = src >>> 24;
                if (sa == 255) return src;
                if (sa == 0) return dst;
                int inv = 255 - sa;
                return (clamp((src >>> 24) + Pixmap.div255((dst >>> 24) * inv)) << 24)
                        | (clamp(((src >> 16) & 0xFF) + Pixmap.div255(((dst >> 16) & 0xFF) * inv)) << 16)
                        | (clamp(((src >> 8) & 0xFF) + Pixmap.div255(((dst >> 8) & 0xFF) * inv)) << 8)
                        | clamp((src & 0xFF) + Pixmap.div255((dst & 0xFF) * inv));
            }
            default -> {
                return general(src, dst);
            }
        }
    }

    private int general(int src, int dst) {
        float sa = (src >>> 24) / 255f;
        float da = (dst >>> 24) / 255f;
        float sr = ((src >> 16) & 0xFF) / 255f;
        float sg = ((src >> 8) & 0xFF) / 255f;
        float sb = (src & 0xFF) / 255f;
        float dr = ((dst >> 16) & 0xFF) / 255f;
        float dg = ((dst >> 8) & 0xFF) / 255f;
        float db = (dst & 0xFF) / 255f;
        float fa;
        float fb;
        if (isBlend()) {
            float ra = sa + da - sa * da;
            float rr = blendChannel(sr, dr, sa, da);
            float rg = blendChannel(sg, dg, sa, da);
            float rb = blendChannel(sb, db, sa, da);
            return pack(ra, rr, rg, rb);
        }
        switch (this) {
            case SOURCE_IN -> {
                fa = da;
                fb = 0;
            }
            case SOURCE_OUT -> {
                fa = 1 - da;
                fb = 0;
            }
            case SOURCE_ATOP -> {
                fa = da;
                fb = 1 - sa;
            }
            case DESTINATION_OVER -> {
                fa = 1 - da;
                fb = 1;
            }
            case DESTINATION_IN -> {
                fa = 0;
                fb = sa;
            }
            case DESTINATION_OUT -> {
                fa = 0;
                fb = 1 - sa;
            }
            case DESTINATION_ATOP -> {
                fa = 1 - da;
                fb = sa;
            }
            case LIGHTER -> {
                fa = 1;
                fb = 1;
            }
            case COPY -> {
                fa = 1;
                fb = 0;
            }
            case XOR -> {
                fa = 1 - da;
                fb = 1 - sa;
            }
            default -> {
                fa = 1;
                fb = 1 - sa;
            }
        }
        return pack(sa * fa + da * fb, sr * fa + dr * fb, sg * fa + dg * fb, sb * fa + db * fb);
    }

    /** 事前乗算の 1 成分（src, dst は事前乗算値）のブレンド結果（事前乗算値）。 */
    private float blendChannel(float s, float d, float sa, float da) {
        float cs = sa > 0 ? Math.min(1, s / sa) : 0;
        float cb = da > 0 ? Math.min(1, d / da) : 0;
        float b = switch (this) {
            case MULTIPLY -> cs * cb;
            case SCREEN -> cb + cs - cb * cs;
            case OVERLAY -> hardLight(cb, cs);
            case DARKEN -> Math.min(cb, cs);
            case LIGHTEN -> Math.max(cb, cs);
            case COLOR_DODGE -> cb == 0 ? 0 : (cs >= 1 ? 1 : Math.min(1, cb / (1 - cs)));
            case COLOR_BURN -> cb >= 1 ? 1 : (cs <= 0 ? 0 : 1 - Math.min(1, (1 - cb) / cs));
            case HARD_LIGHT -> hardLight(cs, cb);
            case SOFT_LIGHT -> softLight(cb, cs);
            case DIFFERENCE -> Math.abs(cb - cs);
            case EXCLUSION -> cb + cs - 2 * cb * cs;
            default -> cs;
        };
        return s * (1 - da) + d * (1 - sa) + sa * da * b;
    }

    private static float hardLight(float cs, float cb) {
        return cs <= 0.5f ? cb * 2 * cs : cb + (2 * cs - 1) - cb * (2 * cs - 1);
    }

    private static float softLight(float cb, float cs) {
        if (cs <= 0.5f) return cb - (1 - 2 * cs) * cb * (1 - cb);
        float dd = cb <= 0.25f ? ((16 * cb - 12) * cb + 4) * cb : (float) Math.sqrt(cb);
        return cb + (2 * cs - 1) * (dd - cb);
    }

    private static int pack(float a, float r, float g, float b) {
        int ia = clamp(Math.round(a * 255f));
        int ir = Math.min(ia, clamp(Math.round(r * 255f)));
        int ig = Math.min(ia, clamp(Math.round(g * 255f)));
        int ib = Math.min(ia, clamp(Math.round(b * 255f)));
        return (ia << 24) | (ir << 16) | (ig << 8) | ib;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
