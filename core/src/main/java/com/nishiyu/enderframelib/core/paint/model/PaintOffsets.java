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
    private float[] dxs;
    private float[] dys;
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
        this.dxs = EMPTY_FLOATS;
        this.dys = EMPTY_FLOATS;
    }

    private PaintOffsets(String[] ids, long[] keys, float[] dxs, float[] dys, int size, int version, int keysVersion) {
        this.immutable = true;
        this.ids = ids;
        this.keys = keys;
        this.dxs = dxs;
        this.dys = dys;
        this.size = size;
        this.version = version;
        this.keysVersion = keysVersion;
    }

    /**
     * 要素 {@code id} の描画上の平行移動を {@code (dx, dy)} に設定する。実効値が変化したときだけ版を進めて {@code true}。
     * 新規追加で {@code (0, 0)} を入れた場合は鍵集合の版（{@link #keysVersion()}）だけを進め、値の版は進めない。
     */
    public synchronized boolean set(String id, float dx, float dy) {
        if (immutable) throw new UnsupportedOperationException("PaintOffsets is immutable");
        if (id == null || id.isEmpty()) return false;
        int n = size;
        for (int i = 0; i < n; i++) {
            if (ids[i] == id || ids[i].equals(id)) {
                if (Float.compare(dxs[i], dx) == 0 && Float.compare(dys[i], dy) == 0) return false;
                dxs[i] = dx;
                dys[i] = dy;
                version++;
                return true;
            }
        }
        if (n == ids.length) {
            int nextCap = n == 0 ? 8 : n * 2;
            ids = Arrays.copyOf(ids, nextCap);
            keys = Arrays.copyOf(keys, nextCap);
            dxs = Arrays.copyOf(dxs, nextCap);
            dys = Arrays.copyOf(dys, nextCap);
        }
        ids[n] = id;
        keys[n] = DisplayItem.keyOf(id);
        dxs[n] = dx;
        dys[n] = dy;
        size = n + 1;
        keysVersion++;
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
                boolean wasNonZero = dxs[i] != 0f || dys[i] != 0f;
                int last = n - 1;
                if (i != last) {
                    ids[i] = ids[last];
                    keys[i] = keys[last];
                    dxs[i] = dxs[last];
                    dys[i] = dys[last];
                }
                ids[last] = null;
                size = last;
                keysVersion++;
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
        for (int i = 0; i < n; i++) {
            if (dxs[i] != 0f || dys[i] != 0f) {
                anyNonZero = true;
                break;
            }
        }
        Arrays.fill(ids, 0, n, null);
        size = 0;
        keysVersion++;
        if (anyNonZero) version++;
    }

    public float dx(String id) {
        if (size == 0 || id == null) return 0f;
        synchronized (this) {
            for (int i = 0, n = size; i < n; i++) {
                if (ids[i] == id || ids[i].equals(id)) return dxs[i];
            }
            return 0f;
        }
    }

    public float dy(String id) {
        if (size == 0 || id == null) return 0f;
        synchronized (this) {
            for (int i = 0, n = size; i < n; i++) {
                if (ids[i] == id || ids[i].equals(id)) return dys[i];
            }
            return 0f;
        }
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
        if (size == 0 || el == null || el.type() != ElementType.NODE) return false;
        String id = el.id();
        if (id == null) return false;
        synchronized (this) {
            for (int i = 0, n = size; i < n; i++) {
                if (ids[i] == id || ids[i].equals(id)) return dxs[i] != 0f || dys[i] != 0f;
            }
            return false;
        }
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

    public synchronized String idAt(int i) { return ids[i]; }
    public synchronized long keyAt(int i) { return keys[i]; }
    public synchronized float dxAt(int i) { return dxs[i]; }
    public synchronized float dyAt(int i) { return dys[i]; }

    public synchronized void copyKeysInto(Set<String> dst) {
        for (int i = 0, n = size; i < n; i++) dst.add(ids[i]);
    }

    /** この時点の値を持つ不変の写し（フレームの記録・検証用）。 */
    public synchronized PaintOffsets snapshot() {
        int n = size;
        if (n == 0) return EMPTY;
        return new PaintOffsets(Arrays.copyOf(ids, n), Arrays.copyOf(keys, n), Arrays.copyOf(dxs, n), Arrays.copyOf(dys, n), n, version, keysVersion);
    }
}
