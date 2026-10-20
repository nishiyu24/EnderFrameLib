package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.EnderFrameLib;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * クライアント側のアニメーションフレーム（requestAnimationFrame 相当）および
 * タイマー（setTimeout / setInterval 相当）を管理するスケジューラ。
 *
 * <p>登録はセッションの {@link SessionContext} が所有し、文書（セッション）が閉じると
 * 該当セッションの登録が全て自動解除され、リークを防止する。</p>
 */
public final class ClientScheduler {

    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(1);

    static final class RafCallback {
        final int id;
        final String sessionId;
        final Consumer<Double> callback;

        RafCallback(int id, String sessionId, Consumer<Double> callback) {
            this.id = id;
            this.sessionId = sessionId;
            this.callback = callback;
        }
    }

    static final class TimerTask {
        final int id;
        final String sessionId;
        final Runnable runnable;
        final long intervalMs;
        final boolean recurring;
        long nextExecutionTime;

        TimerTask(int id, String sessionId, Runnable runnable, long delayMs, long intervalMs, boolean recurring) {
            this.id = id;
            this.sessionId = sessionId;
            this.runnable = runnable;
            this.intervalMs = intervalMs;
            this.recurring = recurring;
            this.nextExecutionTime = System.currentTimeMillis() + delayMs;
        }
    }

    private ClientScheduler() {}

    /**
     * 次の描画フレームの直前に実行されるコールバックを登録する（requestAnimationFrame 相当）。
     * 1回実行されると自動的に解除される。
     */
    public static int requestAnimationFrame(String sessionId, Consumer<Double> callback) {
        if (callback == null) return -1;
        int id = ID_GENERATOR.getAndIncrement();
        String safeSessionId = sessionId != null ? sessionId : "";
        ClientWebManager.getOrCreateSession(safeSessionId)
                .rafCallbacks().put(id, new RafCallback(id, safeSessionId, callback));
        return id;
    }

    /**
     * 登録済みの requestAnimationFrame コールバックをキャンセルする。
     */
    public static boolean cancelAnimationFrame(int id) {
        return removeCallback(id) != null;
    }

    private static RafCallback removeCallback(int id) {
        for (SessionContext session : ClientWebManager.sessions()) {
            RafCallback removed = session.rafCallbacks().remove(id);
            if (removed != null) return removed;
        }
        return null;
    }

    private static TimerTask removeTimer(int id) {
        for (SessionContext session : ClientWebManager.sessions()) {
            TimerTask removed = session.timers().remove(id);
            if (removed != null) return removed;
        }
        return null;
    }

    /**
     * 指定ミリ秒後に1回実行されるタスクを登録する（setTimeout 相当）。
     */
    public static int setTimeout(String sessionId, Runnable runnable, long delayMs) {
        if (runnable == null) return -1;
        int id = ID_GENERATOR.getAndIncrement();
        String safeSessionId = sessionId != null ? sessionId : "";
        ClientWebManager.getOrCreateSession(safeSessionId)
                .timers().put(id, new TimerTask(id, safeSessionId, runnable, Math.max(0, delayMs), 0, false));
        return id;
    }

    /**
     * 登録済みの setTimeout タスクをキャンセルする。
     */
    public static boolean clearTimeout(int id) {
        return removeTimer(id) != null;
    }

    /**
     * 指定ミリ秒ごとに繰り返し実行されるタスクを登録する（setInterval 相当）。
     */
    public static int setInterval(String sessionId, Runnable runnable, long intervalMs) {
        if (runnable == null) return -1;
        int id = ID_GENERATOR.getAndIncrement();
        long interval = Math.max(1, intervalMs);
        String safeSessionId = sessionId != null ? sessionId : "";
        ClientWebManager.getOrCreateSession(safeSessionId)
                .timers().put(id, new TimerTask(id, safeSessionId, runnable, interval, interval, true));
        return id;
    }

    /**
     * 登録済みの setInterval タスクをキャンセルする。
     */
    public static boolean clearInterval(int id) {
        return removeTimer(id) != null;
    }

    /**
     * 全登録をクリアする（クライアント切断・再起動時）。
     */
    public static void clearAll() {
        for (SessionContext session : ClientWebManager.sessions()) {
            session.rafCallbacks().clear();
            session.timers().clear();
        }
    }

    /**
     * 描画フレームごとに呼び出される（requestAnimationFrame のディスパッチ）。
     */
    public static void onRenderFrame(double timestampMs) {
        List<RafCallback> currentBatch = new ArrayList<>();
        for (SessionContext session : ClientWebManager.sessions()) {
            if (session.rafCallbacks().isEmpty()) continue;
            currentBatch.addAll(session.rafCallbacks().values());
            session.rafCallbacks().clear();
        }
        if (currentBatch.isEmpty()) return;

        for (RafCallback raf : currentBatch) {
            try {
                raf.callback.accept(timestampMs);
            } catch (Throwable t) {
                EnderFrameLib.LOGGER.error("requestAnimationFrame callback failed for session: {}", raf.sessionId, t);
            }
        }
    }

    /**
     * クライアント tick ごとに呼び出される（タイマー判定）。
     */
    public static void onClientTick() {
        long now = System.currentTimeMillis();

        for (SessionContext session : ClientWebManager.sessions()) {
            Map<Integer, TimerTask> timers = session.timers();
            if (timers.isEmpty()) continue;

            List<TimerTask> tasks = new ArrayList<>(timers.values());
            for (TimerTask task : tasks) {
                if (now >= task.nextExecutionTime) {
                    if (!task.recurring) {
                        timers.remove(task.id);
                    } else {
                        task.nextExecutionTime = now + task.intervalMs;
                    }
                    try {
                        task.runnable.run();
                    } catch (Throwable t) {
                        EnderFrameLib.LOGGER.error("Timer callback failed for session: {}", task.sessionId, t);
                    }
                }
            }
        }
    }
}