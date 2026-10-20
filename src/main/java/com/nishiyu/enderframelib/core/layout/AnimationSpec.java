package com.nishiyu.enderframelib.core.layout;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSS {@code animation} と {@code @keyframes} から解決済みのアニメーション定義。
 *
 * <p>{@code animation-duration}, {@code animation-delay}, {@code animation-iteration-count},
 * {@code animation-direction}, {@code animation-fill-mode}, {@code animation-timing-function}
 * および {@code @keyframes} で実際に宣言されたプロパティのチャネルマスクを保持する。
 */
public record AnimationSpec(
        int durationMs,
        int delayMs,
        float iterationCount,
        Direction direction,
        FillMode fillMode,
        String easing,
        int animatedChannels,
        List<Keyframe> frames
) {
    /** 平行移動・拡大・回転（transform）のチャネルビット。 */
    public static final int CH_TRANSFORM = 1 << 0;
    /** 不透明度（opacity）のチャネルビット。 */
    public static final int CH_OPACITY   = 1 << 1;
    /** 文字色（color）のチャネルビット。 */
    public static final int CH_COLOR     = 1 << 2;
    /** 背景色（background-color）のチャネルビット。 */
    public static final int CH_BG_COLOR  = 1 << 3;

    /** アニメーションの再生方向（{@code animation-direction}）。 */
    public enum Direction {
        NORMAL,
        REVERSE,
        ALTERNATE,
        ALTERNATE_REVERSE;

        public static Direction fromCss(String value) {
            if (value == null || value.isBlank()) return NORMAL;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "reverse" -> REVERSE;
                case "alternate" -> ALTERNATE;
                case "alternate-reverse" -> ALTERNATE_REVERSE;
                default -> NORMAL;
            };
        }
    }

    /** アニメーション前後の値保持モード（{@code animation-fill-mode}）。 */
    public enum FillMode {
        NONE,
        FORWARDS,
        BACKWARDS,
        BOTH;

        public static FillMode fromCss(String value) {
            if (value == null || value.isBlank()) return NONE;
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "forwards" -> FORWARDS;
                case "backwards" -> BACKWARDS;
                case "both" -> BOTH;
                default -> NONE;
            };
        }
    }

    /** アニメーション無し。 */
    public static final AnimationSpec NONE = new AnimationSpec(
            1, 0, 1.0f, Direction.NORMAL, FillMode.NONE, "ease", 0, List.of());

    public AnimationSpec {
        durationMs = Math.max(1, durationMs);
        if (iterationCount < 0f) iterationCount = 1.0f;
        if (direction == null) direction = Direction.NORMAL;
        if (fillMode == null) fillMode = FillMode.NONE;
        if (easing == null || easing.isBlank()) easing = "ease";
        frames = frames != null ? List.copyOf(frames) : List.of();
    }

    /** 後方互換用の短縮コンストラクタ。 */
    public AnimationSpec(int durationMs, boolean infinite, List<Keyframe> frames) {
        this(
                durationMs,
                0,
                infinite ? Float.POSITIVE_INFINITY : 1.0f,
                Direction.NORMAL,
                FillMode.NONE,
                "ease",
                inferChannels(frames),
                frames
        );
    }

    private static int inferChannels(List<Keyframe> frames) {
        if (frames == null || frames.isEmpty()) return 0;
        int mask = 0;
        for (Keyframe kf : frames) {
            mask |= kf.definedChannels();
        }
        return mask != 0 ? mask : (CH_TRANSFORM | CH_OPACITY);
    }

    public boolean infinite() {
        return Float.isInfinite(iterationCount);
    }

    public boolean isEmpty() {
        return frames.isEmpty() || animatedChannels == 0 || iterationCount <= 0f;
    }

    public boolean animates(int channelBit) {
        return (animatedChannels & channelBit) != 0;
    }

    /**
     * チャネルマスクを絞ったコピーを返す（例: 背景要素には背景色のみを渡すなど）。
     */
    public AnimationSpec withChannels(int channelMask) {
        int filtered = this.animatedChannels & channelMask;
        if (filtered == 0 || isEmpty()) return NONE;
        if (filtered == this.animatedChannels) return this;
        return new AnimationSpec(durationMs, delayMs, iterationCount, direction, fillMode, easing, filtered, frames);
    }

    /**
     * 1 つのキーフレーム。
     *
     * @param pct             進行度（0〜100）
     * @param tx              X 平行移動
     * @param ty              Y 平行移動
     * @param sx              X 拡大率
     * @param sy              Y 拡大率
     * @param rot             回転角（度）
     * @param op              不透明度
     * @param color           文字色（ARGB）
     * @param bgColor         背景色（ARGB）
     * @param definedChannels このキーフレームで宣言されたチャネルのビットマスク
     */
    public record Keyframe(
            float pct,
            float tx,
            float ty,
            float sx,
            float sy,
            float rot,
            float op,
            int color,
            int bgColor,
            int definedChannels
    ) {
        public Keyframe(float pct, float tx, float ty, float sx, float sy, float rot, float op) {
            this(pct, tx, ty, sx, sy, rot, op, 0, 0, CH_TRANSFORM | CH_OPACITY);
        }
    }

    /**
     * CSS のイージング関数（{@code transition-timing-function} / {@code animation-timing-function}）の評価器。
     *
     * <p>{@code cubic-bezier(x1, y1, x2, y2)} の任意制御点と標準プリセット
     * （{@code ease}, {@code linear}, {@code ease-in}, {@code ease-out}, {@code ease-in-out}）をサポートし、
     * CSS 仕様に従い既定値を {@code ease}（{@code cubic-bezier(0.25, 0.1, 0.25, 1.0)}）とする。
     */
    public static final class EasingFunction {

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
}
