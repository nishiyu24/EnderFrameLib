package com.nishiyu.enderframelib.core.paint.model;

import java.util.List;

/**
 * {@code animation} の時間まわりの指定（名前・長さ・遅延・回数・方向・fill・イージング）と、
 * 経過時間から {@code @keyframes} の進行度への変換。読み取りは {@code core.css.AnimationParser}。
 *
 * @param name 適用する {@code @keyframes} の名前
 */
public record AnimationTiming(String name, int durationMs, int delayMs, float iterationCount,
                              AnimationSpec.Direction direction, AnimationSpec.FillMode fillMode, String easing) {

    /** {@code transition} の 1 組。{@code property} は小文字のプロパティ名、{@code all}、{@code none}。 */
    public record Transition(String property, int durationMs, int delayMs, String easing) {
        /** 開始までの待ちも含めて動くか。 */
        public boolean runs() {
            return durationMs > 0 || delayMs > 0;
        }
    }

    /** 方向と fill は CSS の語（{@code alternate}・{@code forwards} など）で渡す。読めなければ既定。 */
    public static AnimationTiming of(String name, int durationMs, int delayMs, float iterationCount,
                                     String direction, String fillMode, String easing) {
        return new AnimationTiming(name, Math.max(1, durationMs), delayMs, iterationCount,
                AnimationSpec.Direction.fromCss(direction), AnimationSpec.FillMode.fromCss(fillMode), easing);
    }

    /** {@code property} に効く組。同じプロパティを指す組が複数あれば最後のものが勝つ（CSS Transitions §2）。 */
    public static Transition transitionFor(List<Transition> transitions, String property) {
        for (int i = transitions.size() - 1; i >= 0; i--) {
            Transition t = transitions.get(i);
            if (t.property().equals(property) || t.property().equals("all")) return t;
        }
        return null;
    }

    /**
     * 経過時間（開始から）に対する {@code @keyframes} の進行度（0〜100）。
     * 遅延中で backwards が無い、または終了後で forwards が無いときは、適用外として {@link Float#NaN}。
     */
    public float progressPct(long elapsedMs) {
        return progressPct(durationMs, delayMs, iterationCount, direction, fillMode, elapsedMs);
    }

    public static float progressPct(int durationMs, int delayMs, float iterationCount, AnimationSpec.Direction direction,
                                    AnimationSpec.FillMode fillMode, long elapsedMs) {
        long activeElapsed = elapsedMs - delayMs;
        int dur = Math.max(1, durationMs);

        if (activeElapsed < 0) {
            if (fillMode == AnimationSpec.FillMode.BACKWARDS || fillMode == AnimationSpec.FillMode.BOTH) {
                boolean reverseFirst = direction == AnimationSpec.Direction.REVERSE
                        || direction == AnimationSpec.Direction.ALTERNATE_REVERSE;
                return reverseFirst ? 100f : 0f;
            }
            return Float.NaN;
        }

        boolean infinite = Float.isInfinite(iterationCount);
        double totalActive = infinite ? Double.POSITIVE_INFINITY : (double) dur * iterationCount;
        if (!infinite && activeElapsed >= totalActive) {
            if (fillMode == AnimationSpec.FillMode.FORWARDS || fillMode == AnimationSpec.FillMode.BOTH) {
                long finalIterIndex;
                float finalProgress;
                if (Math.floor(iterationCount) == iterationCount && iterationCount >= 1.0f) {
                    finalIterIndex = (long) iterationCount - 1L;
                    finalProgress = 1.0f;
                } else {
                    finalIterIndex = (long) Math.floor(iterationCount);
                    finalProgress = iterationCount - finalIterIndex;
                }
                boolean reverse = isIterationReversed(direction, finalIterIndex);
                return (reverse ? (1.0f - finalProgress) : finalProgress) * 100f;
            }
            return Float.NaN;
        }

        long iterIndex = activeElapsed / dur;
        float iterProgress = (float) (activeElapsed % dur) / dur;
        boolean reverse = isIterationReversed(direction, iterIndex);
        return (reverse ? (1.0f - iterProgress) : iterProgress) * 100f;
    }

    /** アニメーションが（遅延を含めて）まだ動くか。 */
    public boolean activeAt(long elapsedMs) {
        if (Float.isInfinite(iterationCount)) return true;
        return elapsedMs < delayMs + (double) durationMs * iterationCount;
    }

    private static boolean isIterationReversed(AnimationSpec.Direction dir, long iterIndex) {
        return switch (dir) {
            case NORMAL -> false;
            case REVERSE -> true;
            case ALTERNATE -> (iterIndex % 2L) == 1L;
            case ALTERNATE_REVERSE -> (iterIndex % 2L) == 0L;
        };
    }
}
