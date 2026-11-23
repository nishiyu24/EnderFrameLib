package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.kernel.Clock;
import com.nishiyu.enderframelib.core.paint.RenderBackend;

/**
 * テスト用: {@link StandaloneView#resize} の反映を待たずに確かめる。リサイズは時計で間引かれる（{@code UiView.resizeDue}: 直前のレイアウトの所要時間の
 * 2 倍が過ぎるまで次のフレームへ持ち越す）ので、実時計のままだと、直前のレイアウトが重く測られた回だけ古い幅が残って落ちる
 * （全件の並列実行でだけ時々落ちた {@code MeasureReadSetTest} の原因。計画書 §6-178）。
 *
 * <p>K0 ①: ビューに止まった時計（{@link Clock.Manual}）を注入する。所要時間は常に 0 と測られ、間引きは起きない。
 * したがって反映は 1 フレームで済み、実時間を待つ {@code Thread.sleep} は要らない（反映されなければ間引きではなく不具合なので失敗にする）。</p>
 */
public final class ResizeSettle {
    private ResizeSettle() { }

    private static boolean applied(StandaloneView v) {
        return v.ui().rootW() == v.width() && v.ui().rootH() == v.height();
    }

    /** 止まった時計を注入する（既に手動の時計なら触らない）。間引きを無効にして、resize が次のフレームで必ず反映されるようにする。 */
    public static void freezeClock(StandaloneView v) {
        if (!(v.ui().clock instanceof Clock.Manual)) v.clock(new Clock.Manual());
    }

    /** 反映されるまで無効化処理を回す（描かないテスト用）。 */
    public static void flushUntilApplied(StandaloneView v) {
        freezeClock(v);
        v.ui().flushInvalidation();
        if (!applied(v)) v.ui().flushInvalidation();
        if (!applied(v)) throw new AssertionError("resize が反映されない（間引きは止めてあるので不具合）: " + v.ui().rootW() + "x" + v.ui().rootH()
                + " ≠ " + v.width() + "x" + v.height());
    }

    /** 同じ時刻のフレームで反映させる（項目を読むテスト用。仮想時刻は進めない）。 */
    public static void frameUntilApplied(StandaloneView v, RenderBackend backend, long nowMs) {
        freezeClock(v);
        v.frame(nowMs, backend, true);
        if (!applied(v)) v.frame(nowMs, backend, true);
        if (!applied(v)) throw new AssertionError("resize が反映されない（間引きは止めてあるので不具合）: " + v.ui().rootW() + "x" + v.ui().rootH()
                + " ≠ " + v.width() + "x" + v.height());
    }
}
