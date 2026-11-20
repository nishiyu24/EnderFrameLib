package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.CropSrc;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * {@link CropSrc}（9-slice の区画を指す {@code src}）の切り出しを、元の画像から作る。切り出した {@link Pixmap} は元の画素ごとに
 * 使い回す（GPU のテクスチャが同一性で引けるように）。
 */
final class CropImages {
    private CropImages() {}

    private static final Map<Pixmap, Map<String, ImageProvider.Image>> CACHE = new WeakHashMap<>();

    /**
     * {@code cropSrc} の切り出しを、元の画像 {@code source} から作る。範囲が画像の外・空なら null。
     * 同じ元画素・同じ範囲なら同じインスタンスを返す。
     */
    static synchronized ImageProvider.Image crop(String cropSrc, ImageProvider.Image source) {
        if (source == null) return null;
        int[] r = CropSrc.rect(cropSrc);
        Pixmap pm = source.pixmap();
        if (r == null || r[2] <= 0 || r[3] <= 0 || r[0] < 0 || r[1] < 0 || r[0] + r[2] > pm.width() || r[1] + r[3] > pm.height()) return null;
        Map<String, ImageProvider.Image> per = CACHE.computeIfAbsent(pm, k -> new java.util.HashMap<>());
        String key = r[0] + "," + r[1] + "," + r[2] + "," + r[3];
        ImageProvider.Image hit = per.get(key);
        if (hit != null) return hit;
        Pixmap out = new Pixmap(r[2], r[3]);
        int[] src = pm.pixels(), dst = out.pixels();
        for (int y = 0; y < r[3]; y++) System.arraycopy(src, (r[1] + y) * pm.width() + r[0], dst, y * r[2], r[2]);
        // CSS px の固有の大きさは、元の画素と CSS px の比のまま
        float kx = source.cssW() > 0 ? source.cssW() / pm.width() : 1f, ky = source.cssH() > 0 ? source.cssH() / pm.height() : 1f;
        ImageProvider.Image img = new ImageProvider.Image(out, r[2] * kx, r[3] * ky);
        per.put(key, img);
        return img;
    }
}
