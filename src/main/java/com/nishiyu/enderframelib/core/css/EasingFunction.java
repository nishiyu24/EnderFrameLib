package com.nishiyu.enderframelib.core.css;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSS のイージング関数（{@code transition-timing-function} / {@code animation-timing-function}）の評価器。
 *
 * <p>{@code cubic-bezier(x1, y1, x2, y2)} の任意制御点と標準プリセット
 * （{@code ease}, {@code linear}, {@code ease-in}, {@code ease-out}, {@code ease-in-out}）をサポートし、
 * CSS 仕様に従い既定値を {@code ease}（{@code cubic-bezier(0.25, 0.1, 0.25, 1.0)}）とする。
 */
public final class EasingFunction {

    private static final Pattern CUBIC_BEZIER_PATTERN = Pattern.compile(
            "cubic-bezier\\(\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*,\\s*(-?[0-9.]+)\\s*\\)",
            Pattern.CASE_INSENSITIVE
    );

    /** CSS 既定のイージング関数名。 */
    public static final String DEFAULT_EASING = "ease";

    private EasingFunction() {}

    /**
     * 進行度 {@code t}（0.0〜1.0）に対してイージング関数を適用した出力値を返す。
     */
    public static float apply(float t, String easingSpec) {
        if (t <= 0.0f) return 0.0f;
        if (t >= 1.0f) return 1.0f;

        String spec = (easingSpec == null || easingSpec.isBlank())
                ? DEFAULT_EASING
                : easingSpec.trim().toLowerCase(Locale.ROOT);

        return switch (spec) {
            case "linear" -> t;
            case "ease" -> cubicBezier(t, 0.25f, 0.1f, 0.25f, 1.0f);
            case "ease-in" -> cubicBezier(t, 0.42f, 0.0f, 1.0f, 1.0f);
            case "ease-out" -> cubicBezier(t, 0.0f, 0.0f, 0.58f, 1.0f);
            case "ease-in-out" -> cubicBezier(t, 0.42f, 0.0f, 0.58f, 1.0f);
            default -> {
                Matcher m = CUBIC_BEZIER_PATTERN.matcher(spec);
                if (m.find()) {
                    try {
                        float x1 = Float.parseFloat(m.group(1));
                        float y1 = Float.parseFloat(m.group(2));
                        float x2 = Float.parseFloat(m.group(3));
                        float y2 = Float.parseFloat(m.group(4));
                        yield cubicBezier(t, x1, y1, x2, y2);
                    } catch (NumberFormatException ignored) {
                    }
                }
                yield cubicBezier(t, 0.25f, 0.1f, 0.25f, 1.0f);
            }
        };
    }

    /**
     * 宣言文字列からイージング指定（{@code cubic-bezier(...)} またはキーワード）を抽出する。
     * 見つからなければ {@link #DEFAULT_EASING} を返す。
     */
    public static String extractFromShorthand(String shorthand) {
        if (shorthand == null || shorthand.isBlank()) return DEFAULT_EASING;
        Matcher m = CUBIC_BEZIER_PATTERN.matcher(shorthand);
        if (m.find()) {
            return m.group(0).replaceAll("\\s+", "");
        }
        String lower = shorthand.toLowerCase(Locale.ROOT);
        if (lower.contains("ease-in-out")) return "ease-in-out";
        if (lower.contains("ease-out")) return "ease-out";
        if (lower.contains("ease-in")) return "ease-in";
        if (lower.contains("linear")) return "linear";
        if (lower.contains("ease")) return "ease";
        return DEFAULT_EASING;
    }

    /**
     * 3 次ベジェ曲線 P0=(0,0), P1=(x1,y1), P2=(x2,y2), P3=(1,1) について、
     * X(u) = x となるパラメータ u を Newton-Raphson 法＋二分法で解き、Y(u) を返す。
     */
    public static float cubicBezier(float x, float x1, float y1, float x2, float y2) {
        if (x <= 0.0f) return 0.0f;
        if (x >= 1.0f) return 1.0f;
        if (x1 == y1 && x2 == y2) return x;

        float cx = 3.0f * x1;
        float bx = 3.0f * (x2 - x1) - cx;
        float ax = 1.0f - cx - bx;

        float cy = 3.0f * y1;
        float by = 3.0f * (y2 - y1) - cy;
        float ay = 1.0f - cy - by;

        // 1. Newton-Raphson 法で u を高速に求める
        float u = x;
        for (int i = 0; i < 8; i++) {
            float xEst = sampleCurve(ax, bx, cx, u) - x;
            if (Math.abs(xEst) < 1e-6f) {
                return sampleCurve(ay, by, cy, u);
            }
            float d = sampleCurveDerivative(ax, bx, cx, u);
            if (Math.abs(d) < 1e-6f) break;
            u -= xEst / d;
        }

        // 2. 導関数が 0 に近い領域では二分法にフォールバックして確実に収束させる
        float u0 = 0.0f;
        float u1 = 1.0f;
        u = Math.clamp(u, 0.0f, 1.0f);
        for (int i = 0; i < 12; i++) {
            float xEst = sampleCurve(ax, bx, cx, u);
            if (Math.abs(xEst - x) < 1e-6f) break;
            if (x > xEst) {
                u0 = u;
            } else {
                u1 = u;
            }
            u = (u0 + u1) * 0.5f;
        }

        return sampleCurve(ay, by, cy, u);
    }

    private static float sampleCurve(float a, float b, float c, float u) {
        return ((a * u + b) * u + c) * u;
    }

    private static float sampleCurveDerivative(float a, float b, float c, float u) {
        return (3.0f * a * u + 2.0f * b) * u + c;
    }
}
