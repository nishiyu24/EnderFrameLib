package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

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

}
