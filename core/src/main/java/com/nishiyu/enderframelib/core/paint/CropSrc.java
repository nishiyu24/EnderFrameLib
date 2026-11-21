package com.nishiyu.enderframelib.core.paint;

/**
 * 画像の一部（9-slice の区画）を指す {@code src}: {@code crop:<x>,<y>,<w>,<h>|<元の src>}（画素）。
 * 表示項目は文字列の {@code src} しか持てないので、切り出しもここへ載せる（どのバックエンドでも同じに解決できる）。
 * 切り出した画素の作成と覚えは {@code paint.resource.CropImages}（画像を持つ側）。ここは文字列の符号化だけ。
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

    /** 切り出しの範囲 {@code {x, y, w, h}}（画素）。形が不正なら null。 */
    public static int[] rect(String src) {
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
}
