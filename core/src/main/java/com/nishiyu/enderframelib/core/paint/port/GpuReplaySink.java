package com.nishiyu.enderframelib.core.paint.port;

/**
 * {@link GpuSink} のうち、項目の出力の記録・再生と差分パッチ（前フレームの出力の再利用）を扱う口。既定はすべて「受け取らない」。
 */
public interface GpuReplaySink {

    /**
     * 項目 {@code index} の出力が、直前のフレームと同じ位置・同じ入力で出ているなら、出力を再生して true を返す（呼び出し側はその項目を描かない）。
     * 呼び出し側は、入力（項目・解決結果・クリップ・不透明度）が前フレームと同じで、項目が止まっているときだけ呼ぶ。
     * {@code cpuPending} は、CPU が描いた画素が（描画順のために）重ねられないまま残っている可能性があること。
     */
    default boolean spanReplay(int index, boolean cpuPending) {
        return false;
    }

    /**
     * スクロールの中身の節の移動量をデバイス画素の整数倍へ丸めて描くか。保持したスクロール層をずらして使う描画先が true を返す
     * （小数の位置だと層の境で 1 画素ずれる）。丸めは項目のセルへ書く（{@code PaintSceneDriver#setScrollSnapDpr}）。
     */
    default boolean snapsScrollOffsets() {
        return false;
    }

    /** 項目 {@code index} の出力の記録を始める。記録できない状態（層の中など）なら false（{@link #spanEnd} は呼ばない）。 */
    default boolean spanBegin(int index) {
        return false;
    }

    /** 記録を閉じる。{@code ok} が false（その項目の描画に CPU 描画が混じった等）なら記録は捨てる。 */
    default void spanEnd(int index, boolean ok) {}

    /**
     * 前フレームと項目構成が同一で、変更箇所が少数のときに {@code RectStream} をそのまま引き継ぎ、
     * 差分項目だけをインプレース上書きするモードへ入る。使用できなければ false。
     */
    default boolean beginDiffPatch() {
        return false;
    }

    /** 項目 {@code index} がインプレース上書き可能な GPU スパンを持つなら true。 */
    default boolean canPatchSpan(int index) {
        return false;
    }

    /** 項目 {@code index} の既存スパンへの上書きを開始する。 */
    default boolean patchSpanBegin(int index) {
        return false;
    }

    /** 項目 {@code index} の既存スパン上書きを閉じる（余剰スロットは opcode=0 で無効化する）。溢れたら false。 */
    default boolean patchSpanEnd(int index, boolean ok) {
        return false;
    }

    /** インプレース上書きを中断して通常走査へ戻す。 */
    default void abortDiffPatch() {}

    /** インプレース上書きバッチを確定する。 */
    default void endDiffPatch() {}
}
