package com.nishiyu.enderframelib.client.renderer;

import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.paint.ImageSources;
import com.nishiyu.enderframelib.core.paint.ObjectFit;
import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import com.nishiyu.enderframelib.core.raster.RasterImageStore;
import com.nishiyu.enderframelib.core.raster.svg.SvgRenderer;

/**
 * SVG を描画項目の画像（{@code raster:<key>@<幅>x<高さ>}）にする。表示項目は文字列しか持てないので、
 * 画素は {@link RasterImageStore} に置き、項目にはキーと CSS px の大きさ（{@code object-fit} の縦横比の基準）を入れる。
 */
final class RasterPainting {

    /** ラスタライズの一辺の上限（px）。 */
    private static final int MAX_SIDE = 2048;

    private RasterPainting() {}

    /**
     * {@code svg} の木を、{@code fit} で決まる中身の大きさ（デバイスピクセル比を掛けた画素数）に描いて置く。
     * 同じ内容・大きさ・色はキャッシュされる。
     *
     * @param intrW 固有の幅（CSS px）。{@code object-fit} の縦横比の基準
     * @param color {@code currentColor} になる色（非乗算 ARGB）
     */
    static String svgSrc(HtmlNode svg, double intrW, double intrH, ImageFitMode fit, float boxW, float boxH, int color) {
        if (!(boxW > 0) || !(boxH > 0)) return "";
        float[] obj = ObjectFit.objectSize(fit, (float) intrW, (float) intrH, boxW, boxH);
        float dpr = Math.max(1f, Math.min(4f, ImageSources.devicePixelRatio()));
        double sw = obj[0] * dpr;
        double sh = obj[1] * dpr;
        double k = Math.min(1.0, MAX_SIDE / Math.max(1.0, Math.max(sw, sh)));
        int pw = (int) Math.max(1, Math.round(sw * k));
        int ph = (int) Math.max(1, Math.round(sh * k));
        String key = "svg:" + Long.toHexString(SvgRenderer.signature(svg)) + ":" + pw + "x" + ph + ":" + Integer.toHexString(color);
        RasterImageStore.Entry entry = RasterImageStore.getOrCreate(key,
                () -> SvgRenderer.render(svg, pw, ph, color, WebFontManager.McFontResolver.INSTANCE));
        if (entry == null) return "";
        return RasterImageStore.src(key) + "@" + Math.max(1, Math.round(obj[0])) + "x" + Math.max(1, Math.round(obj[1]));
    }

    /** {@code raster:<key>@<w>x<h>} の CSS px の大きさ。無ければ null。 */
    static int[] cssSizeOf(String src) {
        int at = src.lastIndexOf('@');
        int x = src.lastIndexOf('x');
        if (at < 0 || x < at) return null;
        try {
            return new int[]{Integer.parseInt(src.substring(at + 1, x)), Integer.parseInt(src.substring(x + 1))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code raster:<key>@...} のキー。 */
    static String keyOf(String src) {
        int at = src.lastIndexOf('@');
        return RasterImageStore.keyOf(at < 0 ? src : src.substring(0, at));
    }
}
