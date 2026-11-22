package com.nishiyu.enderframelib.core.dom;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * アリーナ番号を持たない仮想の節（疑似要素・匿名ボックス等）の同一性弱参照表。
 * キーの {@link HtmlNode} が回収されると {@link ReferenceQueue} 経由で項目を取り除く。
 */
final class VirtualNodeSlots<T> {

    private static final class Entry<T> extends WeakReference<HtmlNode> {
        final int hash;
        T value;
        Entry<T> next;

        Entry(HtmlNode key, int hash, T value, ReferenceQueue<HtmlNode> q, Entry<T> next) {
            super(key, q);
            this.hash = hash;
            this.value = value;
            this.next = next;
        }
    }

    private final ReferenceQueue<HtmlNode> queue = new ReferenceQueue<>();
    private Entry<T>[] buckets;
    private int size;

    @SuppressWarnings("unchecked")
    VirtualNodeSlots() {
        this.buckets = (Entry<T>[]) new Entry[16];
    }

    private static int hash(HtmlNode key) {
        int h = System.identityHashCode(key);
        return h ^ (h >>> 16);
    }

    @SuppressWarnings("unchecked")
    private void expunge() {
        Reference<? extends HtmlNode> ref;
        while ((ref = queue.poll()) != null) {
            Entry<T> dead = (Entry<T>) ref;
            int idx = dead.hash & (buckets.length - 1);
            Entry<T> prev = null;
            for (Entry<T> cur = buckets[idx]; cur != null; prev = cur, cur = cur.next) {
                if (cur == dead) {
                    if (prev == null) buckets[idx] = cur.next;
                    else prev.next = cur.next;
                    cur.value = null;
                    size--;
                    break;
                }
            }
        }
    }

    synchronized T get(HtmlNode key) {
        if (key == null) return null;
        expunge();
        int h = hash(key);
        int idx = h & (buckets.length - 1);
        for (Entry<T> cur = buckets[idx]; cur != null; cur = cur.next) {
            if (cur.hash == h && cur.get() == key) return cur.value;
        }
        return null;
    }

    synchronized void put(HtmlNode key, T value) {
        if (key == null) return;
        expunge();
        int h = hash(key);
        int idx = h & (buckets.length - 1);
        for (Entry<T> cur = buckets[idx]; cur != null; cur = cur.next) {
            if (cur.hash == h && cur.get() == key) {
                cur.value = value;
                return;
            }
        }
        buckets[idx] = new Entry<>(key, h, value, queue, buckets[idx]);
        if (++size > (buckets.length * 3) / 4) resize();
    }

    @SuppressWarnings("unchecked")
    private void resize() {
        Entry<T>[] old = buckets;
        Entry<T>[] next = (Entry<T>[]) new Entry[old.length << 1];
        int mask = next.length - 1;
        int kept = 0;
        for (Entry<T> head : old) {
            for (Entry<T> cur = head; cur != null; ) {
                Entry<T> nxt = cur.next;
                if (cur.get() != null) {
                    int idx = cur.hash & mask;
                    cur.next = next[idx];
                    next[idx] = cur;
                    kept++;
                } else {
                    cur.value = null;
                    cur.next = null;
                }
                cur = nxt;
            }
        }
        buckets = next;
        size = kept;
    }

    void forEach(BiConsumer<HtmlNode, T> visitor) {
        List<HtmlNode> keys;
        List<T> vals;
        synchronized (this) {
            expunge();
            if (size == 0) return;
            keys = new ArrayList<>(size);
            vals = new ArrayList<>(size);
            for (Entry<T> head : buckets) {
                for (Entry<T> cur = head; cur != null; cur = cur.next) {
                    HtmlNode k = cur.get();
                    T v = cur.value;
                    if (k != null && v != null) {
                        keys.add(k);
                        vals.add(v);
                    }
                }
            }
        }
        for (int i = 0, n = keys.size(); i < n; i++) {
            visitor.accept(keys.get(i), vals.get(i));
        }
    }

    synchronized void clear() {
        Arrays.fill(buckets, null);
        size = 0;
        while (queue.poll() != null) {
            // バケットを全て破棄済みのため参照キューのみ空にする
        }
    }
}
