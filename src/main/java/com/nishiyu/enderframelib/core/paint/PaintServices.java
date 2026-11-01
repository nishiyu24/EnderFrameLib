package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.function.Function;

/**
 * ディスプレイリスト生成が外の世界（リソース・フォント）に頼る点の差し込み口。
 * 既定は純 Java で完結する実装（{@code data:} の SVG とシステムフォント）で、
 * Minecraft 側は起動時に {@link #setSvgSource} / {@link #setFontResolver} で差し替える。
 */
public final class PaintServices {

    private static final Function<String, SvgImage> DEFAULT_SVG = src -> {
        if (src == null || src.isEmpty()) return null;
        return SvgImage.isSvgDataUri(src) ? SvgImage.cachedFromDataUri(src) : null;
    };

    private static volatile Function<String, SvgImage> svgSource = DEFAULT_SVG;
    private static volatile FontResolver fontResolver = null;

    private static volatile IntrinsicSizeResolver intrinsicSizes = null;

    private PaintServices() {}

    /** 画像の固有サイズ {@code [w, h]}。分からなければ null。 */
    public static int[] intrinsicSize(String url) {
        IntrinsicSizeResolver r = intrinsicSizes;
        return r == null || url == null ? null : r.getSize(url);
    }

    public static void setIntrinsicSizeResolver(IntrinsicSizeResolver resolver) {
        intrinsicSizes = resolver;
    }

    /** 画像としての SVG（{@code <img src>}・{@code url()}）を引く。読み込み中・失敗は null。 */
    public static SvgImage svg(String src) {
        return svgSource.apply(src);
    }

    public static void setSvgSource(Function<String, SvgImage> source) {
        svgSource = source == null ? DEFAULT_SVG : source;
    }

    /** SVG 内の文字を描くときの書体解決。未設定ならシステムフォント。 */
    public static FontResolver fontResolver() {
        FontResolver r = fontResolver;
        return r != null ? r : SystemFonts.system();
    }

    public static void setFontResolver(FontResolver resolver) {
        fontResolver = resolver;
    }
}
