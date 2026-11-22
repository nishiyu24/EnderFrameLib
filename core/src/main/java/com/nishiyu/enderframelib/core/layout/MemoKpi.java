package com.nishiyu.enderframelib.core.layout;

import java.util.concurrent.atomic.LongAdder;

/**
 * 記憶（メモ化）の効き具合の KPI 用カウンタ（診断用。C 系の最終目標〔増幅率〕を測る。計画書 §2 の「記憶の効き具合」）。
 * 呼び出しと計算の個数だけを数え、挙動には一切影響しない。各段が持つ既存のカウンタ（測定の計算・配置の skip/shift・断片の hit/miss）と
 * 合わせて {@code FineProfileMain} の {@code MEMO} 行に出る。累計（プロセス全体）なので、比べるときはフレーム数で割る。
 */
public final class MemoKpi {
    private MemoKpi() { }

    /** 測定の記憶の入口（{@code MeasureMemo.measureCore}）の呼び出し。呼び出し − 計算 ＝ 使い回し。 */
    public static final LongAdder MEASURE_CALLS = new LongAdder();
    /** 配置の使い回し判定の入口（{@code PlacementMemo.tryReuseOrShift}）の呼び出し。呼び出し − skip − shift ＝ 配置し直し。 */
    public static final LongAdder PLACE_CALLS = new LongAdder();
    /** 再スタイルで訪問した箱・値を解き直した箱・算出値が実際に変わった箱。 */
    public static final LongAdder RESTYLE_VISITED = new LongAdder(), RESTYLE_RECOMPUTED = new LongAdder(), RESTYLE_CHANGED = new LongAdder();
}
