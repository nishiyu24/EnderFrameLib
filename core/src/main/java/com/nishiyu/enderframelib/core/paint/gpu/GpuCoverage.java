package com.nishiyu.enderframelib.core.paint.gpu;

import java.util.concurrent.atomic.LongAdder;

/**
 * GPU 被覆率の計測（ENGINE_REARCHITECTURE_PLAN G0）。図形を種別ごとに「GPU が受け取った」か「断って CPU が描いた」かを数える。
 * 既定は無効（フラグ 1 つの読み取りだけで済む）。{@code FineProfileMain} とテストが有効にする。
 * 計数するのは GPU シンクが存在する経路だけ（{@code SOFTWARE} 単独の描画は数えない）。
 * 項目の再生（{@code spanReplay}）で過去の受理を再利用した図形はここを通らない（バックエンド側の再生数で補う）。
 */
public final class GpuCoverage {
    /** 試行の種別。 */
    public enum Kind {
        FILL, GRADIENT, OUTER_SHADOW, INSET_SHADOW, BORDER, DASHED_BORDER, CIRCLE_BORDER,
        GLYPH, GLYPH_STROKE, GLYPH_TRANSFORMED, IMAGE, EXTERNAL_TEXTURE
    }

    private static final Kind[] KINDS = Kind.values();
    private static final LongAdder[] ACCEPTED = adders();
    private static final LongAdder[] DECLINED = adders();
    private static volatile boolean enabled;

    private GpuCoverage() {}

    private static LongAdder[] adders() {
        LongAdder[] a = new LongAdder[KINDS.length];
        for (int i = 0; i < a.length; i++) a[i] = new LongAdder();
        return a;
    }

    public static void enable(boolean on) {
        enabled = on;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void reset() {
        for (int i = 0; i < KINDS.length; i++) {
            ACCEPTED[i].reset();
            DECLINED[i].reset();
        }
    }

    /** 試行の結果を記録して、そのまま返す（呼び出し側は {@code return note(kind, 試行)} と書ける）。 */
    public static boolean note(Kind kind, boolean accepted) {
        if (enabled) (accepted ? ACCEPTED : DECLINED)[kind.ordinal()].increment();
        return accepted;
    }

    public static long accepted(Kind kind) {
        return ACCEPTED[kind.ordinal()].sum();
    }

    public static long declined(Kind kind) {
        return DECLINED[kind.ordinal()].sum();
    }

    public static long totalAccepted() {
        long s = 0;
        for (LongAdder a : ACCEPTED) s += a.sum();
        return s;
    }

    public static long totalDeclined() {
        long s = 0;
        for (LongAdder a : DECLINED) s += a.sum();
        return s;
    }

    /**
     * 被覆率 = (受理 + {@code replayed}) ÷ (受理 + {@code replayed} + 断り)。{@code replayed} は項目の再生で再利用した図形数
     * （試行を通らないが GPU が描いた図形）。何も数えていなければ 1.0。
     */
    public static double coverage(long replayed) {
        long acc = totalAccepted() + Math.max(0, replayed), dec = totalDeclined();
        long all = acc + dec;
        return all == 0 ? 1.0 : acc / (double) all;
    }

    /** CPU 層へ落ちた図形の種別ヒストグラム（断りの多い順。0 件の種別は出さない）。 */
    public static String declinedHistogram() {
        StringBuilder sb = new StringBuilder();
        boolean[] used = new boolean[KINDS.length];
        for (int n = 0; n < KINDS.length; n++) {
            int best = -1;
            long bestV = 0;
            for (int i = 0; i < KINDS.length; i++) {
                long v = DECLINED[i].sum();
                if (!used[i] && v > bestV) {
                    best = i;
                    bestV = v;
                }
            }
            if (best < 0) break;
            used[best] = true;
            if (sb.length() > 0) sb.append(' ');
            sb.append(KINDS[best]).append('=').append(bestV);
        }
        return sb.length() == 0 ? "(none)" : sb.toString();
    }
}
