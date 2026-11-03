package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.paint.PaintServices;

/**
 * 標準の {@link ImageProvider} で画像を引いて画素数を答える {@link IntrinsicSizeResolver}。ホスト（Minecraft クライアント等）が
 * 自分の解決を入れていない単体の描画で、{@code border-image} のように画素数が要る描画が画像の大きさを知るための既定。
 */
public final class ImageSizes {
    private ImageSizes() {}

    public static final IntrinsicSizeResolver STANDARD = src -> {
        com.nishiyu.enderframelib.core.paint.model.TextureRef t = com.nishiyu.enderframelib.core.paint.model.TextureRef.parse(src);
        if (t != null) return new int[]{Math.round(t.regionWidth()), Math.round(t.regionHeight())};
        ImageProvider.Image img = ImageProvider.standard().load(src, 0, 0);
        if (img == null || img.pixmap().width() <= 0 || img.pixmap().height() <= 0) return null;
        return new int[]{img.pixmap().width(), img.pixmap().height()};
    };

    /** {@code border-image} 用の画素数の既定として {@link #STANDARD} を入れる（ホストの固有サイズ解決があればそちらが先）。 */
    public static void installDefault() {
        PaintServices.setPixelSizeFallback(STANDARD);
    }
}
