package com.nishiyu.enderframelib.core.raster;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ラスタライズした画像（インライン SVG・画像としての SVG）の置き場。表示項目の {@code raster:<key>} が指す先。
 *
 * 描画の項目は文字列しか持てないので、中身（{@link Pixmap}）はここに置き、キーで引く。
 * キーには内容と大きさが入るので、同じキーなら同じ画素（版は変わらない）。古いものから捨てる。
 */
public final class RasterImageStore {

    public static final String PREFIX = "raster:";
    private static final int MAX_ENTRIES = 96;

    /** 画素と版。版が変わったらテクスチャを上げ直す。 */
    public record Entry(Pixmap pixmap, long version) {}

    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private RasterImageStore() {}

    /** キーが無ければ {@code supplier} で作って置く。作れなければ null。 */
    public static synchronized Entry getOrCreate(String key, java.util.function.Supplier<Pixmap> supplier) {
        Entry e = ENTRIES.get(key);
        if (e != null) return e;
        Pixmap p = supplier.get();
        if (p == null) return null;
        e = new Entry(p, 1);
        ENTRIES.put(key, e);
        return e;
    }

    public static synchronized Entry get(String key) {
        return ENTRIES.get(key);
    }

    public static synchronized int size() {
        return ENTRIES.size();
    }

    public static synchronized void clear() {
        ENTRIES.clear();
    }

    /** 表示項目の {@code text} に入れる値。 */
    public static String src(String key) {
        return PREFIX + key;
    }

    public static boolean isRasterSrc(String src) {
        return src != null && src.startsWith(PREFIX);
    }

    public static String keyOf(String src) {
        return src.substring(PREFIX.length());
    }
}
