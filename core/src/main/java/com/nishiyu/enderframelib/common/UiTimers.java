package com.nishiyu.enderframelib.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 1 つの {@link UiView} が持つ {@code requestAnimationFrame} / {@code setTimeout} / {@code setInterval} の登録簿。
 * ID はプロセス全体で一意なので、{@code cancel*} は所有者が分からなくても登録簿ごとに試せる。
 * 文書（セッション）を閉じると {@link #clear()} で全登録が消える。
 */
public final class UiTimers {

    private static final Logger LOGGER = LoggerFactory.getLogger(UiTimers.class);
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(1);

    public static final class RafCallback {
        public final int id;
        public final String sessionId;
        public final Consumer<Double> callback;

        public RafCallback(int id, String sessionId, Consumer<Double> callback) {
            this.id = id;
            this.sessionId = sessionId;
            this.callback = callback;
        }
    }

    public static final class TimerTask {
        public final int id;
        public final String sessionId;
        public final Runnable runnable;
        public final long intervalMs;
        public final boolean recurring;
        public long nextExecutionTime;

        public TimerTask(int id, String sessionId, Runnable runnable, long delayMs, long intervalMs, boolean recurring) {
            this.id = id;
            this.sessionId = sessionId;
            this.runnable = runnable;
            this.intervalMs = intervalMs;
            this.recurring = recurring;
            this.nextExecutionTime = System.currentTimeMillis() + delayMs;
        }
    }

    private final String sessionId;
    private final Map<Integer, RafCallback> rafCallbacks = new ConcurrentHashMap<>();
    private final Map<Integer, TimerTask> timers = new ConcurrentHashMap<>();

    public UiTimers(String sessionId) {
        this.sessionId = sessionId != null ? sessionId : "";
    }

    public Map<Integer, RafCallback> rafCallbacks() {
        return rafCallbacks;
    }

    public Map<Integer, TimerTask> timers() {
        return timers;
    }

    public boolean isEmpty() {
        return rafCallbacks.isEmpty() && timers.isEmpty();
    }

    /** 次の描画フレームの直前に 1 回だけ呼ばれるコールバックを登録する。 */
    public int requestAnimationFrame(Consumer<Double> callback) {
        if (callback == null) return -1;
        int id = ID_GENERATOR.getAndIncrement();
        rafCallbacks.put(id, new RafCallback(id, sessionId, callback));
        return id;
    }

    public boolean cancelAnimationFrame(int id) {
        return rafCallbacks.remove(id) != null;
    }

    public int setTimeout(Runnable runnable, long delayMs) {
        if (runnable == null) return -1;
        int id = ID_GENERATOR.getAndIncrement();
        timers.put(id, new TimerTask(id, sessionId, runnable, Math.max(0, delayMs), 0, false));
        return id;
    }

    public boolean clearTimeout(int id) {
        return timers.remove(id) != null;
    }

    public int setInterval(Runnable runnable, long intervalMs) {
        if (runnable == null) return -1;
        int id = ID_GENERATOR.getAndIncrement();
        long interval = Math.max(1, intervalMs);
        timers.put(id, new TimerTask(id, sessionId, runnable, interval, interval, true));
        return id;
    }

    public boolean clearInterval(int id) {
        return timers.remove(id) != null;
    }

    public void clear() {
        rafCallbacks.clear();
        timers.clear();
    }

    /** 溜まっている animation frame コールバックを取り出して空にする。 */
    public List<RafCallback> drainAnimationFrames() {
        if (rafCallbacks.isEmpty()) return List.of();
        List<RafCallback> batch = new ArrayList<>(rafCallbacks.values());
        rafCallbacks.clear();
        return batch;
    }

    /** 溜まっている animation frame コールバックを {@code timestampMs} で呼ぶ。 */
    public void runAnimationFrames(double timestampMs) {
        runBatch(drainAnimationFrames(), timestampMs);
    }

    /** 複数の登録簿の animation frame をまとめて 1 バッチにして呼ぶ（全登録簿を先に空にする）。 */
    public static void runAnimationFrames(Iterable<UiTimers> all, double timestampMs) {
        List<RafCallback> batch = new ArrayList<>();
        for (UiTimers t : all) batch.addAll(t.drainAnimationFrames());
        if (!batch.isEmpty()) runBatch(batch, timestampMs);
    }

    private static void runBatch(List<RafCallback> batch, double timestampMs) {
        for (RafCallback raf : batch) {
            try {
                raf.callback.accept(timestampMs);
            } catch (Throwable t) {
                LOGGER.error("requestAnimationFrame callback failed for session: {}", raf.sessionId, t);
            }
        }
    }

    /** 時刻が来たタイマーを実行する（{@code System.currentTimeMillis()} 基準）。 */
    public void runDueTimers() {
        runDueTimers(System::currentTimeMillis);
    }

    public void runDueTimers(LongSupplier clock) {
        if (timers.isEmpty()) return;
        long now = clock.getAsLong();
        for (TimerTask task : new ArrayList<>(timers.values())) {
            if (now < task.nextExecutionTime) continue;
            if (!task.recurring) {
                timers.remove(task.id);
            } else {
                task.nextExecutionTime = now + task.intervalMs;
            }
            try {
                task.runnable.run();
            } catch (Throwable t) {
                LOGGER.error("Timer callback failed for session: {}", task.sessionId, t);
            }
        }
    }
}
