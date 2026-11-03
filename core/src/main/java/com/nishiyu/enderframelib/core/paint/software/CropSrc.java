package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.raster.Pixmap;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * 画像の一部（9-slice の区画）を指す {@code src}: {@code crop:<x>,<y>,<w>,<h>|<元の src>}（画素）。
 * 表示項目は文字列の {@code src} しか持てないので、切り出しもここへ載せる（どのバックエンドでも同じに解決できる）。
 * 切り出した {@link Pixmap} は元の画素ごとに使い回す（GPU のテクスチャが同一性で引けるように）。
 */
public final class CropSrc {
    public static final String PREFIX = "crop:";

    private CropSrc() {}

    public static String of(int x, int y, int w, int h, String src) {
        return PREFIX + x + "," + y + "," + w + "," + h + "|" + src;
    }

    public static boolean isCrop(String src) {
        return src != null && src.startsWith(PREFIX);
    }

    /** 切り出し元の {@code src}。{@link #isCrop} でなければ null。 */
    public static String inner(String src) {
        if (!isCrop(src)) return null;
        int bar = src.indexOf('|');
        return bar < 0 ? null : src.substring(bar + 1);
    }

    private static int[] rect(String src) {
        int bar = src.indexOf('|');
        if (bar < 0) return null;
        String[] p = src.substring(PREFIX.length(), bar).split(",");
        if (p.length != 4) return null;
        try {
            return new int[]{Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final Map<Pixmap, Map<String, ImageProvider.Image>> CACHE = new WeakHashMap<>();

    /**
     * {@code cropSrc} の切り出しを、元の画像 {@code source} から作る。範囲が画像の外・空なら null。
     * 同じ元画素・同じ範囲なら同じインスタンスを返す。
     */
    public static synchronized ImageProvider.Image crop(String cropSrc, ImageProvider.Image source) {
        if (source == null) return null;
        int[] r = rect(cropSrc);
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
