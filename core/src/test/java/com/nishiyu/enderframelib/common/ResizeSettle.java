package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.RenderBackend;

/**
 * テスト用: {@link StandaloneView#resize} の反映を待つ。リサイズは実時間で間引かれる（{@code UiView.resizeDue}: 直前のレイアウトの所要時間の
 * 2 倍が過ぎるまで次のフレームへ持ち越す）ので、{@code resize} の直後に結果を比べると、直前のレイアウトが重く測られた回だけ古い幅が残って落ちる
 * （全件の並列実行でだけ時々落ちた {@code MeasureReadSetTest} の原因。計画書 §6-178）。反映されるまで描いてから比べる。
 */
public final class ResizeSettle {
    private ResizeSettle() { }

    private static boolean applied(StandaloneView v) {
        return v.ui().rootW() == v.width() && v.ui().rootH() == v.height();
    }

    /** 反映されるまで無効化処理を回す（描かないテスト用）。 */
    public static void flushUntilApplied(StandaloneView v) {
        for (int spin = 0; spin < 2000 && !applied(v); spin++) {
            v.ui().flushInvalidation();
            sleep1();
        }
        v.ui().flushInvalidation();
    }

    /** 同じ時刻のフレームを、反映されるまで回す（項目を読むテスト用。仮想時刻は進めない）。 */
    public static void frameUntilApplied(StandaloneView v, RenderBackend backend, long nowMs) {
        for (int spin = 0; spin < 2000 && !applied(v); spin++) {
            v.frame(nowMs, backend, true);
            sleep1();
        }
    }

    private static void sleep1() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
