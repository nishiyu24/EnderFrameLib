package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * ディスプレイリスト生成が外の世界（リソース・フォント）に頼る点の読み口。
 * 値は面ごとの文脈（{@code EngineContext.current().host()}。{@code HostServices} がこの {@link Source} を実装する）が持ち、
 * ここには状態を持たない（A13。以前は static の差し込み口 4 個）。既定は純 Java で完結する実装（{@code data:} の SVG とシステムフォント）。
 * 差し替えは {@code EngineContext#updateHost} の {@code withSvgSource} / {@code withFontResolver} /
 * {@code withIntrinsicSizeResolver} / {@code withPixelSizeFallback}。
 */
public final class PaintServices {

    /** 文脈が差し込む値（各値は null なら既定）。L5 が L6 の型を知らずに済むよう、読み口だけをここで宣言する。 */
    public interface Source {
        Function<String, SvgImage> svgSource();

        FontResolver fontResolver();

        IntrinsicSizeResolver intrinsicSizeResolver();

        IntrinsicSizeResolver pixelSizeFallback();
    }

    private static final Function<String, SvgImage> DEFAULT_SVG = src -> {
        if (src == null || src.isEmpty()) return null;
        return SvgImage.isSvgDataUri(src) ? SvgImage.cachedFromDataUri(src) : null;
    };

    private static final Source NONE = new Source() {
        @Override public Function<String, SvgImage> svgSource() { return null; }
        @Override public FontResolver fontResolver() { return null; }
        @Override public IntrinsicSizeResolver intrinsicSizeResolver() { return null; }
        @Override public IntrinsicSizeResolver pixelSizeFallback() { return null; }
    };

    private static volatile Supplier<Source> source = () -> NONE;

    private PaintServices() {}

    /** 「このスレッドの文脈の値」を引く口を結ぶ（{@code EngineContext} が 1 回だけ呼ぶ。層の逆転）。 */
    public static void bindSource(Supplier<Source> contextSource) {
        source = contextSource == null ? () -> NONE : contextSource;
    }

    /** 画像の固有サイズ {@code [w, h]}。分からなければ null。 */
    public static int[] intrinsicSize(String url) {
        IntrinsicSizeResolver r = source.get().intrinsicSizeResolver();
        return r == null || url == null ? null : r.getSize(url);
    }

    /**
     * 画像の画素数 {@code [w, h]}（{@code border-image} の切り出しに要る）。ホストの {@link #intrinsicSize} があればそれ、
     * 無ければ文脈の {@code pixelSizeFallback}。どちらも無い・分からなければ null。
     * 背景画像の寸法決めには使わない（{@link #intrinsicSize} の挙動を変えないため）。
     */
    public static int[] imagePixelSize(String url) {
        int[] s = intrinsicSize(url);
        if (s != null) return s;
        IntrinsicSizeResolver f = source.get().pixelSizeFallback();
        return f == null || url == null ? null : f.getSize(url);
    }

    /** 画像としての SVG（{@code <img src>}・{@code url()}）を引く。読み込み中・失敗は null。 */
    public static SvgImage svg(String src) {
        Function<String, SvgImage> f = source.get().svgSource();
        return (f != null ? f : DEFAULT_SVG).apply(src);
    }

    /** SVG 内の文字を描くときの書体解決。未設定ならシステムフォント。 */
    public static FontResolver fontResolver() {
        FontResolver r = source.get().fontResolver();
        return r != null ? r : SystemFonts.system();
    }
}
