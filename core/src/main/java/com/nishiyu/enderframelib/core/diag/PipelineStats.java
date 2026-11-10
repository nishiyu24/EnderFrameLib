package com.nishiyu.enderframelib.core.diag;

/**
 * 1 回のレイアウト構築の診断用の途中経過と直近の結果。スレッドごとに 1 つ持たれ（{@code RenderPipeline#stats()}）、
 * 複数のビュー・スレッドが同時にレイアウトしても数え合わない。
 */
public final class PipelineStats {
    /** 直近の構築で作った箱の数・スタイルを算出し直した数・前回の箱を使い回した数（調べる用）。 */
    public String lastBuildStats = "";
    /** 直近の局所再レイアウトが経路だけの印付けで済んだか。 */
    public boolean lastRelayoutWasLocal;
    /** {@code trackAllocations} のとき、スタイル解決の段ごとの割り当て（バイト）。 */
    public long allocResolve, allocCompute, allocAnimated;
    public int boxes, restyled, reused;
    public String phases = "";
    public long passMeasureMs, passLayoutMs, passMeasured;
}
