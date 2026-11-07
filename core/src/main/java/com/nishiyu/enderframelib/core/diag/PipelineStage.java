package com.nishiyu.enderframelib.core.diag;

/** {@link StageProfiler} が時間を分けて記録する、描画パイプラインの段。 */
public enum PipelineStage {
    /** 再スタイルと箱の木の生成/再利用（{@code buildLayoutTree}）。 */
    STYLE_BUILD("style+build"),
    /** 匿名ボックスの整形（{@code BoxTreeBuilder}）。 */
    BOX_TREE("box-tree"),
    /** 局所の再スタイル（{@code restyleLayoutTree}。ホバー・class のトグルなど）。 */
    RESTYLE_LOCAL("restyle-local"),
    /** 差分/全体のレイアウトのうち measure（{@code LAYOUT_PASSES} 回）。 */
    LAYOUT_MEASURE("layout.measure"),
    /** 同じく配置（位置決め）。 */
    LAYOUT_PLACE("layout.place"),
    /** measure を飛ばした位置決めだけ（スクロール・ドラッグ）。 */
    REPOSITION("reposition"),
    /** ディスプレイリスト生成。 */
    DISPLAY_LIST("display-list"),
    /** 描画バックエンド（{@code ProfilingRenderBackend} 越しの CPU 側の所要）。 */
    PAINT("paint(cpu)"),
    /** GPU 側の所要（タイマークエリ。数フレーム遅れて届く）。 */
    GPU("gpu"),
    /** ゲーム内: 面 1 枚の描画全体（コア経路・従来経路のどちらも含む。{@code SurfaceRenderer.paint}）。 */
    SURFACE("surface"),
    /** ゲーム内: コア GPU バックエンドへの命令発行（{@code CoreGpuSurface} の {@code backend.render}）。 */
    SURFACE_CORE_RENDER("surface.core-render"),
    /** ゲーム内: コアの結果テクスチャを板として貼る処理。 */
    SURFACE_CORE_BLIT("surface.core-blit"),
    /** ゲーム内: 変化もアニメも無く、コアの描画を飛ばして前のテクスチャを貼っただけのフレーム（回数が意味を持つ）。 */
    SURFACE_CORE_SKIPPED("surface.core-skipped"),
    /** ゲーム内: 従来の経路（{@code PaintRequest.render}）で描いた面。 */
    SURFACE_LEGACY("surface.legacy");

    private final String label;

    PipelineStage(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
