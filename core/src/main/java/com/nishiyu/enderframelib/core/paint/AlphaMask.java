package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.raster.Pixmap;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 画像の「不透明か」を 1 画素 1 ビットで持つマスク（256×256 の画像で 8KB）。ヒットテストが、画像の透明な部分のクリックを
 * 下の要素へ通すのに使う。画素から一度だけ作り、GPU からの読み戻しはしない（描画 API に依存しない: L1）。
 */
public final class AlphaMask {
    private final int width, height;
    private final long[] bits;

    private AlphaMask(int width, int height, long[] bits) {
        this.width = width;
        this.height = height;
        this.bits = bits;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** 画素 {@code (x, y)} が不透明か（範囲外は false）。 */
    public boolean opaque(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) return false;
        int i = y * width + x;
        return (bits[i >> 6] & (1L << (i & 63))) != 0;
    }

    /** マスクの使うメモリ（バイト）。 */
    public int byteSize() {
        return bits.length * 8;
    }

    /** 事前乗算の画素から作る。{@code alpha >= threshold} の画素が不透明（0 なら常に全て不透明）。 */
    static AlphaMask build(Pixmap pm, int threshold) {
        int w = pm.width(), h = pm.height();
        long[] bits = new long[(int) (((long) w * h + 63) >> 6)];
        int[] px = pm.pixels();
        int t = Math.max(0, Math.min(255, threshold));
        for (int i = 0, n = w * h; i < n; i++) {
            if ((px[i] >>> 24) >= t) bits[i >> 6] |= 1L << (i & 63);
        }
        return new AlphaMask(w, h, bits);
    }

    private record Cached(int version, AlphaMask mask) {}

    private static final Map<Pixmap, Map<Integer, Cached>> CACHE = new WeakHashMap<>();

    /** {@code pm} の閾値ごとのマスク。画素の中身が変わった（{@link Pixmap#version()}）ときだけ作り直す。 */
    public static synchronized AlphaMask of(Pixmap pm, int threshold) {
        Map<Integer, Cached> per = CACHE.computeIfAbsent(pm, k -> new HashMap<>());
        Cached c = per.get(threshold);
        if (c != null && c.version == pm.version()) return c.mask;
        AlphaMask m = build(pm, threshold);
        per.put(threshold, new Cached(pm.version(), m));
        return m;
    }

    /**
     * 画像の項目の中の点（項目の左上が原点の局所座標）が、画像の不透明な画素の上か。{@code object-fit} / {@code object-position} で置かれた
     * 画像の範囲の外（余白）は透明扱い。{@code cssW / cssH} は画像の固有の大きさ（CSS px）。
     */
    public boolean hitsItem(DisplayItem el, double localX, double localY, float cssW, float cssH) {
        float iw = cssW > 0 ? cssW : width, ih = cssH > 0 ? cssH : height;
        String position = el.events() != null ? el.events().get("object-position") : null;
        float fontPx = el.meta() != null && el.meta().fontHeight() > 0 ? el.meta().fontHeight() : 16f;
        ImageFitMode fit = el.fitMode() != null ? el.fitMode() : ImageFitMode.FILL;
        ImagePlacement pl = ObjectFit.place(fit, position, iw, ih, el.width(), el.height(), fontPx);
        if (!(pl.w() > 0) || !(pl.h() > 0)) return false;
        double u = (localX - pl.x()) * width / pl.w(), v = (localY - pl.y()) * height / pl.h();
        if (u < 0 || v < 0 || u >= width || v >= height) return false;
        return opaque((int) u, (int) v);
    }

    /** ヒットテストが画像の項目から引くマスクと、画像の固有の大きさ。 */
    public record Source(AlphaMask mask, float cssW, float cssH) {}
}
