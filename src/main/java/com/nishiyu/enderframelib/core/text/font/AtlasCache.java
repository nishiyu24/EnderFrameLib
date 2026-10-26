package com.nishiyu.enderframelib.core.text.font;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.ToLongFunction;

/**
 * 字形の置き場（GPU のテクスチャを持つ）を、使われた順に覚えて、使われなくなったものから捨てる入れ物。
 *
 * <ul>
 *   <li>{@code protectNanos} より最近に使われた置き場は捨てない。まだ描画バッファに積まれた字が、
 *       そのテクスチャを指しているかもしれないため（フレームの途中でテクスチャを解放すると字が 1 フレーム消える）。</li>
 *   <li>個数が {@code softLimit} を超えたら、保護の外にある古いものから捨てる。全部が保護の中なら超えたまま許す。</li>
 *   <li>{@code expireNanos} を超えて使われないものは、個数によらず捨てる（{@code scale} のアニメーションが残した
 *       サイズ違いの置き場を VRAM に溜めないため）。</li>
 * </ul>
 * 時計は差し替えられる（テストが時間を進める）。スレッドセーフ。
 */
public final class AtlasCache<K, V> {

    private static final class Entry<V> {
        final V value;
        long lastUsed;

        Entry(V value, long lastUsed) {
            this.value = value;
            this.lastUsed = lastUsed;
        }
    }

    private final Map<K, Entry<V>> entries = new LinkedHashMap<>(32, 0.75f, true);
    private final int softLimit;
    private final long protectNanos;
    private final long expireNanos;
    private final LongSupplier clock;
    private final Consumer<V> release;
    private long lastSweep;

    public AtlasCache(int softLimit, long protectNanos, long expireNanos, LongSupplier clock, Consumer<V> release) {
        this.softLimit = softLimit;
        this.protectNanos = protectNanos;
        this.expireNanos = expireNanos;
        this.clock = clock;
        this.release = release;
        this.lastSweep = clock.getAsLong();
    }

    /** {@code key} の置き場。無ければ {@code factory} で作る。 */
    public synchronized V get(K key, Function<K, V> factory) {
        long now = clock.getAsLong();
        Entry<V> e = entries.get(key);
        if (e != null) {
            e.lastUsed = now;
            return e.value;
        }
        V value = factory.apply(key);
        entries.put(key, new Entry<>(value, now));
        sweep(now);
        return value;
    }

    /** 期限切れの置き場を捨てる（描画のたびに呼んでよい。掃除は {@code protectNanos} ごとに 1 回だけ）。 */
    public synchronized void sweepIfDue() {
        long now = clock.getAsLong();
        if (now - lastSweep >= protectNanos) sweep(now);
    }

    private void sweep(long now) {
        lastSweep = now;
        Iterator<Map.Entry<K, Entry<V>>> it = entries.entrySet().iterator();
        // アクセス順なので先頭が最も古い。保護の外にある間だけ見ればよい
        while (it.hasNext()) {
            Entry<V> e = it.next().getValue();
            long idle = now - e.lastUsed;
            if (idle < protectNanos) break;
            if (idle >= expireNanos || entries.size() > softLimit) {
                it.remove();
                release.accept(e.value);
            } else {
                break;
            }
        }
    }

    /** 全ての置き場の {@code sizer} の合計（VRAM の使用量の集計用）。 */
    public synchronized long sum(ToLongFunction<V> sizer) {
        long total = 0;
        for (Entry<V> e : entries.values()) total += sizer.applyAsLong(e.value);
        return total;
    }

    /** 保護の外（{@code protectNanos} より前に使った）にある置き場を、古い順に全て捨てる。VRAM の上限を超えたときに使う。 */
    public synchronized int evictUnprotected() {
        long now = clock.getAsLong();
        int removed = 0;
        Iterator<Map.Entry<K, Entry<V>>> it = entries.entrySet().iterator();
        while (it.hasNext()) {
            Entry<V> e = it.next().getValue();
            if (now - e.lastUsed < protectNanos) break;
            it.remove();
            release.accept(e.value);
            removed++;
        }
        return removed;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void clear() {
        for (Entry<V> e : entries.values()) release.accept(e.value);
        entries.clear();
    }
}
