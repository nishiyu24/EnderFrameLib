package com.nishiyu.enderframelib.core.paint.gpu;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像（キー）→ GPU テクスチャ ID の LRU キャッシュ。描画 API を持たない純粋な管理表。
 * キーは同一性（{@code ==}）で比較する。満杯のとき、最も長く使われていない 1 件だけを追い出す
 * （全破棄しない）。
 */
public final class ImageTextureCache<K> {

    /** 1 件分。{@code version} はテクスチャへ転送済みの画素の版。 */
    public static final class Entry {
        public final int texture;
        public int version;
        private long lastUse;

        Entry(int texture, int version) {
            this.texture = texture;
            this.version = version;
        }
    }

    private final int limit;
    private final Map<K, Entry> map = new IdentityHashMap<>();
    private long clock;

    public ImageTextureCache(int limit) {
        if (limit < 1) throw new IllegalArgumentException("limit");
        this.limit = limit;
    }

    public int size() {
        return map.size();
    }

    public boolean isFull() {
        return map.size() >= limit;
    }

    /** 見つかれば使用済みとして印を付けて返す。無ければ {@code null}。 */
    public Entry get(K key) {
        Entry e = map.get(key);
        if (e != null) e.lastUse = ++clock;
        return e;
    }

    public Entry put(K key, int texture, int version) {
        Entry e = new Entry(texture, version);
        e.lastUse = ++clock;
        map.put(key, e);
        return e;
    }

    /** 最も長く使われていない 1 件を取り除いて返す（空なら {@code null}）。 */
    public Entry evictOldest() {
        K oldestKey = null;
        Entry oldest = null;
        for (Map.Entry<K, Entry> me : map.entrySet()) {
            if (oldest == null || me.getValue().lastUse < oldest.lastUse) {
                oldest = me.getValue();
                oldestKey = me.getKey();
            }
        }
        if (oldestKey != null) map.remove(oldestKey);
        return oldest;
    }

    /** すべて取り除き、持っていたテクスチャ ID を返す（破棄用）。 */
    public List<Integer> clear() {
        List<Integer> ids = new ArrayList<>(map.size());
        for (Entry e : map.values()) ids.add(e.texture);
        map.clear();
        return ids;
    }
}
