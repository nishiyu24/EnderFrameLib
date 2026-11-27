package com.nishiyu.enderframelib.core.paint.port;

/**
 * {@link GpuSink} のうち、スクロールの中身の移動量の丸めを扱う口（項目の出力の再生は M3b-4b-4a で削除）。既定はすべて「受け取らない」。
 */
public interface GpuReplaySink {

    /**
     * スクロールの中身の節の移動量をデバイス画素の整数倍へ丸めて描くか。保持したスクロール層をずらして使う描画先が true を返す
     * （小数の位置だと層の境で 1 画素ずれる）。丸めは項目のセルへ書く（{@code SceneWalker#setScrollSnapDpr}）。
     */
    default boolean snapsScrollOffsets() {
        return false;
    }
}
