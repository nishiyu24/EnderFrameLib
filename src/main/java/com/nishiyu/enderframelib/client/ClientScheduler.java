package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.common.UiTimers;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * クライアント側のアニメーションフレーム（requestAnimationFrame 相当）およびタイマー
 * （setTimeout / setInterval 相当）の入口。登録簿の実体はコアの {@link UiTimers} で、各セッションが持つ。
 * ここはセッション台帳（{@link ClientWebManager}）を引いて、描画フレーム・tick のたびに全セッション分を回す。
 */
public final class ClientScheduler {

    private ClientScheduler() {}

    private static UiTimers timersOf(String sessionId) {
        return ClientWebManager.getOrCreateSession(sessionId != null ? sessionId : "").timers();
    }

    public static int requestAnimationFrame(String sessionId, Consumer<Double> callback) {
        if (callback == null) return -1;
        return timersOf(sessionId).requestAnimationFrame(callback);
    }

    public static boolean cancelAnimationFrame(int id) {
        for (SessionContext session : ClientWebManager.sessions()) {
            if (session.timers().cancelAnimationFrame(id)) return true;
        }
        return false;
    }

    public static int setTimeout(String sessionId, Runnable runnable, long delayMs) {
        if (runnable == null) return -1;
        return timersOf(sessionId).setTimeout(runnable, delayMs);
    }

    public static boolean clearTimeout(int id) {
        for (SessionContext session : ClientWebManager.sessions()) {
            if (session.timers().clearTimeout(id)) return true;
        }
        return false;
    }

    public static int setInterval(String sessionId, Runnable runnable, long intervalMs) {
        if (runnable == null) return -1;
        return timersOf(sessionId).setInterval(runnable, intervalMs);
    }

    public static boolean clearInterval(int id) {
        for (SessionContext session : ClientWebManager.sessions()) {
            if (session.timers().clearInterval(id)) return true;
        }
        return false;
    }

    /** 全登録をクリアする（クライアント切断・再起動時）。 */
    public static void clearAll() {
        for (SessionContext session : ClientWebManager.sessions()) {
            session.timers().clear();
        }
    }

    /** 描画フレームごとに呼び出される（requestAnimationFrame のディスパッチ）。 */
    public static void onRenderFrame(double timestampMs) {
        List<UiTimers> all = new ArrayList<>();
        for (SessionContext session : ClientWebManager.sessions()) all.add(session.timers());
        UiTimers.runAnimationFrames(all, timestampMs);
    }

    /** クライアント tick ごとに呼び出される（タイマー判定）。 */
    public static void onClientTick() {
        for (SessionContext session : ClientWebManager.sessions()) {
            session.timers().runDueTimers();
        }
    }
}
