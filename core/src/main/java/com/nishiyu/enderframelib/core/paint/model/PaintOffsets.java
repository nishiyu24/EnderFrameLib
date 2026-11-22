package com.nishiyu.enderframelib.core.paint.model;

import java.util.Arrays;
import java.util.Set;

/**
 * 描画の上だけの平行移動（スクロールの中身のずれ・つまみの位置・sticky の補正・ウィンドウのドラッグ）を
 * {@link DisplayItem} の外に持つ表（C6）。項目を完全に不変に保ったまま、毎フレーム変わる移動量だけをここに集める。
 *
 * <p>項目の整数鍵（{@link DisplayItem#key()}）は DOM ノードハンドル＋部品番号で付けられる一方、呼び出し側は文字列の
 * 要素 ID（{@code UiView.setPaintOffset(String, float, float)}）で指定するため、文字列 ID を鍵にし、キャッシュした
 * {@link DisplayItem#keyOf(String)} も並べて持つ。</p>
 */
public final class PaintOffsets {
    /** 空で不変の平行移動表（移動量が無い描画経路・互換オーバーロードの既定値）。 */
    public static final PaintOffsets EMPTY = new PaintOffsets(true);

    private static final String[] EMPTY_IDS = new String[0];
    private static final long[] EMPTY_KEYS = new long[0];
    private static final float[] EMPTY_FLOATS = new float[0];

    private final boolean immutable;
    private String[] ids;
    private long[] keys;
    private float[] coords;
    private PaintOffsets cachedSnapshot;
    private String[] snapIds;
    private long[] snapKeys;
    private int snapKeysVersion = -1;
    private volatile int size;
    private volatile int version;
    private volatile int keysVersion;

    public PaintOffsets() {
        this(false);
    }

    private PaintOffsets(boolean immutable) {
        this.immutable = immutable;
        this.ids = EMPTY_IDS;
        this.keys = EMPTY_KEYS;
        this.coords = EMPTY_FLOATS;
    }

    private PaintOffsets(String[] ids, long[] keys, float[] coords, int size, int version, int keysVersion) {
        this.immutable = true;
        this.ids = ids;
        this.keys = keys;
        this.coords = coords;
        this.version = version;
        this.keysVersion = keysVersion;
        this.size = size;
    }

    /** スナップショット等で凍結済みの不変表なら {@code true}。 */
    public boolean isImmutable() {
        return immutable;
    }

    /**
     * 要素 {@code id} の描画上の平行移動を {@code (dx, dy)} に設定する。実効値が変化したときだけ版を進めて {@code true}。
     * 新規追加で {@code (0, 0)} を入れた場合は鍵集合の版（{@link #keysVersion()}）だけを進め、値の版は進めない。
     */
    public synchronized boolean set(String id, float dx, float dy) {
        return put(id, dx, dy, false);
    }

    /** 未登録で {@code (0, 0)} のときは登録せず、既存項目の更新または非ゼロ値だけを記録する。 */
    public synchronized boolean setIfActive(String id, float dx, float dy) {
        return put(id, dx, dy, true);
    }

    private boolean put(String id, float dx, float dy, boolean skipZeroNew) {
        if (immutable) throw new UnsupportedOperationException("PaintOffsets is immutable");
        if (id == null || id.isEmpty()) return false;
        int n = size;
        for (int i = 0; i < n; i++) {
            if (ids[i] == id || ids[i].equals(id)) {
                int ci = i * 2;
                if (Float.compare(coords[ci], dx) == 0 && Float.compare(coords[ci + 1], dy) == 0) return false;
                coords[ci] = dx;
                coords[ci + 1] = dy;
                version++;
                cachedSnapshot = null;
                return true;
            }
        }
        if (skipZeroNew && dx == 0f && dy == 0f) return false;
        return append(n, id, dx, dy);
    }

    private boolean append(int n, String id, float dx, float dy) {
        if (n == ids.length) {
            int nextCap = n == 0 ? 8 : n * 2;
            ids = Arrays.copyOf(ids, nextCap);
            keys = Arrays.copyOf(keys, nextCap);
            coords = Arrays.copyOf(coords, nextCap * 2);
        }
        ids[n] = id;
        keys[n] = DisplayItem.keyOf(id);
        int ci = n * 2;
        coords[ci] = dx;
        coords[ci + 1] = dy;
        size = n + 1;
        keysVersion++;
        cachedSnapshot = null;
        if (dx != 0f || dy != 0f) {
            version++;
            return true;
        }
        return false;
    }

    /** 要素 {@code id} の平行移動を外す。非ゼロの移動量が外れたとき {@code true}。 */
    public synchronized boolean clear(String id) {
        if (immutable) throw new UnsupportedOperationException("PaintOffsets is immutable");
        if (id == null || size == 0) return false;
        int n = size;
        for (int i = 0; i < n; i++) {
            if (ids[i] == id || ids[i].equals(id)) {
                int ci = i * 2;
                boolean wasNonZero = coords[ci] != 0f || coords[ci + 1] != 0f;
                int last = n - 1;
                if (i != last) {
                    int clast = last * 2;
                    ids[i] = ids[last];
                    keys[i] = keys[last];
                    coords[ci] = coords[clast];
                    coords[ci + 1] = coords[clast + 1];
                }
                ids[last] = null;
                size = last;
                keysVersion++;
                cachedSnapshot = null;
                if (wasNonZero) version++;
                return wasNonZero;
            }
        }
        return false;
    }

    /** 全ての平行移動を消す（ビューを閉じるとき）。 */
    public synchronized void clearAll() {
        if (immutable) throw new UnsupportedOperationException("PaintOffsets is immutable");
        int n = size;
        if (n == 0) return;
        boolean anyNonZero = false;
        for (int i = 0, m = n * 2; i < m; i++) {
            if (coords[i] != 0f) {
                anyNonZero = true;
                break;
            }
        }
        Arrays.fill(ids, 0, n, null);
        size = 0;
        keysVersion++;
        cachedSnapshot = null;
        if (anyNonZero) version++;
    }

    public float dx(String id) {
        int n = size;
        if (n == 0 || id == null) return 0f;
        if (immutable) return lookupCoord(n, id, 0);
        synchronized (this) {
            return lookupCoord(size, id, 0);
        }
    }

    public float dy(String id) {
        int n = size;
        if (n == 0 || id == null) return 0f;
        if (immutable) return lookupCoord(n, id, 1);
        synchronized (this) {
            return lookupCoord(size, id, 1);
        }
    }

    private float lookupCoord(int n, String id, int axis) {
        for (int i = 0; i < n; i++) {
            if (ids[i] == id || ids[i].equals(id)) return coords[i * 2 + axis];
        }
        return 0f;
    }

    public float dx(DisplayItem el) {
        if (size == 0 || el == null || el.type() != ElementType.NODE) return 0f;
        return dx(el.id());
    }

    public float dy(DisplayItem el) {
        if (size == 0 || el == null || el.type() != ElementType.NODE) return 0f;
        return dy(el.id());
    }

    public boolean hasOffset(DisplayItem el) {
        int n = size;
        if (n == 0 || el == null || el.type() != ElementType.NODE) return false;
        String id = el.id();
        if (id == null) return false;
        if (immutable) return hasNonZero(n, id);
        synchronized (this) {
            return hasNonZero(size, id);
        }
    }

    private boolean hasNonZero(int n, String id) {
        for (int i = 0; i < n; i++) {
            if (ids[i] == id || ids[i].equals(id)) {
                int ci = i * 2;
                return coords[ci] != 0f || coords[ci + 1] != 0f;
            }
        }
        return false;
    }

    /** 項目自身の基準移動量にこの表の平行移動を足した実効 X 移動量。 */
    public float translateX(DisplayItem el) {
        float d = dx(el);
        return d == 0f ? el.translateX() : el.translateX() + d;
    }

    /** 項目自身の基準移動量にこの表の平行移動を足した実効 Y 移動量。 */
    public float translateY(DisplayItem el) {
        float d = dy(el);
        return d == 0f ? el.translateY() : el.translateY() + d;
    }

    public boolean isEmpty() { return size == 0; }
    public int size() { return size; }
    public int version() { return version; }
    public int keysVersion() { return keysVersion; }

    public String idAt(int i) {
        if (immutable) { int ignored = size; return ids[i]; }
        synchronized (this) { return ids[i]; }
    }

    public long keyAt(int i) {
        if (immutable) { int ignored = size; return keys[i]; }
        synchronized (this) { return keys[i]; }
    }

    public float dxAt(int i) {
        if (immutable) { int ignored = size; return coords[i * 2]; }
        synchronized (this) { return coords[i * 2]; }
    }

    public float dyAt(int i) {
        if (immutable) { int ignored = size; return coords[i * 2 + 1]; }
        synchronized (this) { return coords[i * 2 + 1]; }
    }

    public void copyKeysInto(Set<String> dst) {
        int n = size;
        if (immutable) {
            for (int i = 0; i < n; i++) dst.add(ids[i]);
            return;
        }
        synchronized (this) {
            for (int i = 0, sz = size; i < sz; i++) dst.add(ids[i]);
        }
    }

    /** この時点の値を持つ不変の写し（フレームの記録・検証・フレーム境界越え用）。 */
    public synchronized PaintOffsets snapshot() {
        if (immutable) return this;
        int n = size;
        if (n == 0) return EMPTY;
        if (cachedSnapshot != null) return cachedSnapshot;
        if (snapKeysVersion != keysVersion || snapIds == null || snapIds.length != n) {
            snapIds = Arrays.copyOf(ids, n);
            snapKeys = Arrays.copyOf(keys, n);
            snapKeysVersion = keysVersion;
        }
        return cachedSnapshot = new PaintOffsets(snapIds, snapKeys, Arrays.copyOf(coords, n * 2), n, version, keysVersion);
    }
}
