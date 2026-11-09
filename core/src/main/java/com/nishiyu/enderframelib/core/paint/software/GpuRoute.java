package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;

/**
 * 1 フレームの間だけ、図形・層の GPU 試行をどの {@link GpuSink} へ向けるか。
 * 根では GPU 版から渡された {@code root}、CPU の層の中では {@code null}（CPU が描く）。
 * バックエンドは {@code GpuSink} を保持せず、この経路だけを持つ（試行は {@link GpuAttempt} / {@link GpuLayerAttempt}）。
 */
final class GpuRoute {
    private GpuSink root;
    private GpuSink current;

    /** フレームの始まり。{@code gpu} が {@code null} なら全部 CPU。 */
    void begin(GpuSink gpu) {
        root = gpu;
        current = gpu;
    }

    /** フレームの終わり。 */
    void end() {
        root = null;
        current = null;
    }

    /** いま試行を向ける先。CPU の層の中では {@code null}。 */
    GpuSink current() {
        return current;
    }

    /** 根の先（CPU の層を閉じて GPU の背景へ合成するときに使う）。 */
    GpuSink root() {
        return root;
    }

    /** CPU の層を開いた。中は CPU が描く。 */
    void enterCpuLayer() {
        current = null;
    }

    /** CPU の層を閉じた。外にまだ CPU の層があれば CPU のまま、なければ根の先へ戻る。 */
    void leaveCpuLayer(boolean stillInsideCpuLayer) {
        if (!stillInsideCpuLayer) current = root;
    }
}
