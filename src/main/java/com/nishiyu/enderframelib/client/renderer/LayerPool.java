package com.nishiyu.enderframelib.client.renderer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * レイヤー（効果のある箱の描画先）の FBO のプール。GL を知らない純粋な管理だけを持ち、描画先の作り方と壊し方は
 * {@link Factory} が受け持つ（テストは偽の描画先で、貸し借り・予算・縮小を確かめる）。
 *
 * <ul>
 *   <li>同じ大きさの空きがあれば使い回す。無ければ、上限に達しているときだけ最も古い空きを壊して作り直す
 *       （大きさは箱の外接矩形ごとに違う。{@link com.nishiyu.enderframelib.core.paint.model.LayerBounds#bucket} で粗く丸めて使い回しやすくしている）。</li>
 *   <li>同時に持てる数の上限は {@code maxTargets}（{@link com.nishiyu.enderframelib.core.paint.model.VramBudget#maxLayers}）。
 *       上限に達していて空きが無ければ {@code null} を返す（呼び出し側は効果を諦めて直に描く）。</li>
 *   <li>効果が無いフレームが続けば確保を残さない（{@link #trim}）。FBO は効果があるときだけ確保するので、
 *       効果の無い文書は予算を一切使わない。</li>
 * </ul>
 */
final class LayerPool<T> {

    interface Factory<T> {
        T create(int width, int height);

        void destroy(T target);
    }

    private final class Entry {
        final T target;
        final int width;
        final int height;
        long freedAt;

        Entry(T target, int width, int height) {
            this.target = target;
            this.width = width;
            this.height = height;
        }
    }

    private final Factory<T> factory;
    private final IntSupplier maxTargets;
    private final List<Entry> free = new ArrayList<>();
    private final List<Entry> inUse = new ArrayList<>();

    LayerPool(Factory<T> factory, IntSupplier maxTargets) {
        this.factory = factory;
        this.maxTargets = maxTargets;
    }

    /** 大きさ {@code width x height} の描画先を借りる。上限に達していて空きが無ければ null。 */
    T acquire(int width, int height) {
        for (Iterator<Entry> it = free.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.width == width && e.height == height) {
                it.remove();
                inUse.add(e);
                return e.target;
            }
        }
        // 同じ大きさの空きが無いときだけ、上限まで古い空き（使われていない順）を壊して枠を空ける。
        // 大きさはレイヤーごとに違う（箱の外接矩形）ので、違う大きさの空きを全部壊すと毎回作り直しになる
        while (inUse.size() + free.size() >= maxTargets.getAsInt() && !free.isEmpty()) {
            Entry oldest = free.get(0);
            for (Entry e : free) if (e.freedAt < oldest.freedAt) oldest = e;
            free.remove(oldest);
            factory.destroy(oldest.target);
        }
        if (inUse.size() + free.size() >= maxTargets.getAsInt()) return null;
        T created = factory.create(width, height);
        inUse.add(new Entry(created, width, height));
        return created;
    }

    /** 借りた描画先を返す。 */
    void release(T target, long nowNanos) {
        for (Iterator<Entry> it = inUse.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.target == target) {
                it.remove();
                e.freedAt = nowNanos;
                free.add(e);
                return;
            }
        }
        throw new IllegalStateException("貸していない描画先が返された");
    }

    /** {@code idleNanos} 以上使われていない空きを壊す。借りているものには触らない。 */
    void trim(long nowNanos, long idleNanos) {
        for (Iterator<Entry> it = free.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (nowNanos - e.freedAt >= idleNanos) {
                it.remove();
                factory.destroy(e.target);
            }
        }
    }

    /** 空きを全部壊す（借りているものは返された後で次の {@link #trim} が壊す）。資源の再読み込みなど。 */
    void releaseFree() {
        for (Entry e : free) factory.destroy(e.target);
        free.clear();
    }

    int inUseCount() {
        return inUse.size();
    }

    int freeCount() {
        return free.size();
    }
}
