package com.nishiyu.enderframelib.core.diag;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 描画パイプラインの段ごとの所要時間（と、望めば割り当て量）を集める、切り替え式の計測器。
 *
 * <p><b>無効のときの費用</b>: {@link #begin} は volatile の読みが 1 回だけで 0 を返し、{@link #end} は {@code t0 == 0} を見て戻る
 * （時計も配列も触らない）。既定は無効で、{@code -Denderframe.profile=true} か {@link #enable} で有効にする。
 * 有効なときも画面の出力・描画結果には一切影響しない（時間を記録するだけ）。
 *
 * <p><b>使い方</b>:
 * <pre>{@code
 * long t0 = StageProfiler.begin(PipelineStage.DISPLAY_LIST);
 * try { ... } finally { StageProfiler.end(PipelineStage.DISPLAY_LIST, t0); }
 * }</pre>
 * 同じ段を同じスレッドで入れ子にはしない（割り当て量の始点を段ごとに 1 つしか覚えない）。違う段の入れ子はよい。
 *
 * <p>各段は直近 {@value #RING} 回の標本を輪にして持ち、{@link #snapshot} が中央値・95 パーセンタイル・最大を出す
 * （平均だけでは GC や JIT の外れ値が見えないため）。記録は段ごとの同期で、スレッドから呼んでよい。
 */
public final class StageProfiler {

    /** 1 段ぶんの集計。時間はミリ秒、{@code allocKb} は 1 回あたりの割り当て（計測していなければ -1）。 */
    public record StageStats(PipelineStage stage, long count, double totalMs, double meanMs, double p50Ms, double p95Ms,
                             double maxMs, double lastMs, double allocKb) { }

    private static final int RING = 1024;

    private static volatile boolean enabled = Boolean.getBoolean("enderframe.profile");
    private static volatile boolean trackAllocations = Boolean.getBoolean("enderframe.profile.alloc");

    private static final Stats[] STATS = new Stats[PipelineStage.values().length];
    /** 段ごとの「開始時の割り当て済みバイト」。段を入れ子にしない前提で、スレッドごとに 1 つ持つ。 */
    private static final ThreadLocal<long[]> ALLOC_START = ThreadLocal.withInitial(() -> new long[PipelineStage.values().length]);

    static {
        for (int i = 0; i < STATS.length; i++) STATS[i] = new Stats();
    }

    private StageProfiler() { }

    private static final class Stats {
        final long[] ring = new long[RING];
        long count;
        long totalNs;
        long maxNs;
        long lastNs;
        long allocBytes;
        long allocCount;

        synchronized void add(long ns, long alloc) {
            ring[(int) (count % RING)] = ns;
            count++;
            totalNs += ns;
            lastNs = ns;
            if (ns > maxNs) maxNs = ns;
            if (alloc >= 0) {
                allocBytes += alloc;
                allocCount++;
            }
        }

        synchronized void clear() {
            count = totalNs = maxNs = lastNs = allocBytes = allocCount = 0;
            Arrays.fill(ring, 0);
        }

        synchronized StageStats snapshot(PipelineStage stage) {
            int n = (int) Math.min(count, RING);
            if (n == 0) return new StageStats(stage, 0, 0, 0, 0, 0, 0, 0, -1);
            long[] sorted = Arrays.copyOf(ring, n);
            Arrays.sort(sorted);
            double mean = totalNs / (double) count;
            return new StageStats(stage, count, totalNs / 1e6, mean / 1e6,
                    sorted[(n - 1) / 2] / 1e6, sorted[(int) Math.min(n - 1, Math.ceil(n * 0.95) - 1)] / 1e6,
                    maxNs / 1e6, lastNs / 1e6, allocCount == 0 ? -1 : allocBytes / (double) allocCount / 1024.0);
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    /** 計測を有効/無効にする。無効にしても集計は残る（{@link #reset} で消す）。 */
    public static void enable(boolean on) {
        enabled = on;
    }

    /** true なら {@link #begin}/{@link #end} が呼び出しスレッドの割り当て量も記録する（有効なときだけ。やや費用がある）。 */
    public static void trackAllocations(boolean on) {
        trackAllocations = on;
    }

    /** 段の開始。無効なら 0 を返す（{@link #end} はそれを見て何もしない）。 */
    public static long begin(PipelineStage stage) {
        if (!enabled) return 0L;
        if (trackAllocations) ALLOC_START.get()[stage.ordinal()] = threadAllocatedBytes();
        long t = System.nanoTime();
        return t == 0L ? 1L : t; // 0 は「無効」の印なので、まず起きないが 0 を避ける
    }

    /** {@link #begin} の対。 */
    public static void end(PipelineStage stage, long t0) {
        if (t0 == 0L) return;
        long ns = System.nanoTime() - t0;
        long alloc = trackAllocations ? threadAllocatedBytes() - ALLOC_START.get()[stage.ordinal()] : -1;
        STATS[stage.ordinal()].add(ns, alloc);
    }

    /** 時間だけを直接記録する（すでに自前で測っている箇所用）。無効なら何もしない。 */
    public static void record(PipelineStage stage, long nanos) {
        if (!enabled) return;
        STATS[stage.ordinal()].add(Math.max(0, nanos), -1);
    }

    /** 全段の集計を消す。 */
    public static void reset() {
        for (Stats s : STATS) s.clear();
    }

    /** 標本のある段の集計（宣言順）。 */
    public static List<StageStats> snapshot() {
        List<StageStats> out = new ArrayList<>();
        for (PipelineStage s : PipelineStage.values()) {
            StageStats st = STATS[s.ordinal()].snapshot(s);
            if (st.count() > 0) out.add(st);
        }
        return out;
    }

    /** 人が読む表。 */
    public static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-16s %8s %9s %9s %9s %9s %9s %10s%n",
                "stage", "count", "mean ms", "p50 ms", "p95 ms", "max ms", "total ms", "alloc KB"));
        for (StageStats s : snapshot()) {
            sb.append(String.format(Locale.ROOT, "%-16s %8d %9.3f %9.3f %9.3f %9.3f %9.1f %10s%n",
                    s.stage().label(), s.count(), s.meanMs(), s.p50Ms(), s.p95Ms(), s.maxMs(), s.totalMs(),
                    s.allocKb() < 0 ? "-" : String.format(Locale.ROOT, "%.1f", s.allocKb())));
        }
        return sb.toString();
    }

    private static long threadAllocatedBytes() {
        if (ManagementFactory.getThreadMXBean() instanceof com.sun.management.ThreadMXBean t) {
            return t.getThreadAllocatedBytes(Thread.currentThread().getId());
        }
        return 0L;
    }
}
