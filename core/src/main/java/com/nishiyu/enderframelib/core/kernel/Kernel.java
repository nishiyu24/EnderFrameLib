package com.nishiyu.enderframelib.core.kernel;

import java.util.concurrent.atomic.LongAdder;

/**
 * 増分エンジンの計器の唯一の入口（設計書 {@code DESIGN_INCREMENTAL_KERNEL.md} §2 K4・計画書 K0 ②。旧 {@code MemoKpi}）。
 *
 * <p>計数は段の出入口のここだけ。{@link #STATS}（{@code enderframe.kernel.stats}。{@code costGate}・{@code profile} の子 JVM だけが立てる）は
 * {@code static final} なので、既定（オフ）では JIT が分岐ごと消す。1 箱ごとの {@code LongAdder}・独自のカウンタを段の中へ足さない
 * （2,000 箱の文書で {@code hover} を約 8% 遅くした。§6-180。{@code IncrementalStateRatchetTest} が数える）。
 * 累計（プロセス全体）なので、比べるときはフレーム数で割る。挙動には一切影響しない。</p>
 */
public final class Kernel {
    private Kernel() { }

    /** 計器を動かすか。既定オフ。 */
    public static final boolean STATS = EngineFlags.KERNEL_STATS;

    /** 計器。 */
    public static final Stats stats = new Stats();

    /** 記憶の効き具合の計数（計画書 §2「記憶の効き具合」）。呼び出し − 計算 ＝ 使い回し。 */
    public static final class Stats {
        private final LongAdder measureCalls = new LongAdder();
        private final LongAdder placeCalls = new LongAdder();
        private final LongAdder restyleVisited = new LongAdder();
        private final LongAdder restyleRecomputed = new LongAdder();
        private final LongAdder restyleChanged = new LongAdder();

        private Stats() { }

        /** 測定の記憶の入口（{@code MeasureMemo.measureCore}）の呼び出し。 */
        public void measureCall() { if (STATS) measureCalls.increment(); }

        /** 配置の使い回し判定の入口（{@code PlacementMemo.tryReuseOrShift}）の呼び出し。呼び出し − skip − shift ＝ 配置し直し。 */
        public void placeCall() { if (STATS) placeCalls.increment(); }

        /** 再スタイルで訪問した箱。 */
        public void restyleVisit() { if (STATS) restyleVisited.increment(); }

        /** 再スタイルで値を解き直した箱。 */
        public void restyleRecompute() { if (STATS) restyleRecomputed.increment(); }

        /** 再スタイルで算出値が実際に変わった箱。 */
        public void restyleChange() { if (STATS) restyleChanged.increment(); }

        public long measureCalls() { return measureCalls.sum(); }

        public long placeCalls() { return placeCalls.sum(); }

        public long restyleVisited() { return restyleVisited.sum(); }

        public long restyleRecomputed() { return restyleRecomputed.sum(); }

        public long restyleChanged() { return restyleChanged.sum(); }
    }
}
