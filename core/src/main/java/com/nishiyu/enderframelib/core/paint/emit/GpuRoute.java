package com.nishiyu.enderframelib.core.paint.emit;

import com.nishiyu.enderframelib.core.paint.port.GpuSink;

/**
 * 1 フレームの間だけ、図形・層の GPU 試行をどの {@link GpuSink} へ向けるか。
 * 根では GPU 版から渡された {@code root}、CPU の層の中では {@code null}（CPU が描く）。
 * バックエンドは {@code GpuSink} を保持せず、この経路だけを持つ（試行は {@link GpuAttempt} / {@link GpuLayerAttempt}）。
 */
public final class GpuRoute {
    private GpuSink root;
    private GpuSink current;

    /** フレームの始まり。{@code gpu} が {@code null} なら全部 CPU。 */
    public void begin(GpuSink gpu) {
        root = gpu;
        current = gpu;
    }

    /** フレームの終わり。 */
    public void end() {
        root = null;
        current = null;
    }

    /** いま試行を向ける先。CPU の層の中では {@code null}。 */
    public GpuSink current() {
        return current;
    }

    /** 根の先（CPU の層を閉じて GPU の背景へ合成するときに使う）。 */
    public GpuSink root() {
        return root;
    }

    /** CPU の層を開いた。中は CPU が描く。 */
    public void enterCpuLayer() {
        current = null;
    }

    /** CPU の層を閉じた。外にまだ CPU の層があれば CPU のまま、なければ根の先へ戻る。 */
    public void leaveCpuLayer(boolean stillInsideCpuLayer) {
        if (!stillInsideCpuLayer) current = root;
    }

    // 項目単位の差分再生（B3）。バックエンドは GpuSink を直接呼ばず、ここを通す。

    /** 差分再生を始められるか（試行先があり、受け付けるとき）。 */
    public boolean beginDiffPatch() {
        return current != null && current.beginDiffPatch();
    }

    public void endDiffPatch() {
        if (current != null) current.endDiffPatch();
    }

    public void abortDiffPatch() {
        if (current != null) current.abortDiffPatch();
    }

    public boolean canPatchSpan(int index) {
        return current != null && current.canPatchSpan(index);
    }

    public boolean patchSpanBegin(int index) {
        return current != null && current.patchSpanBegin(index);
    }

    public boolean patchSpanEnd(int index, boolean ok) {
        return current != null && current.patchSpanEnd(index, ok);
    }
}
