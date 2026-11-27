package com.nishiyu.enderframelib.core.paint.software;
import com.nishiyu.enderframelib.core.paint.resource.ImageProvider;

import com.nishiyu.enderframelib.core.paint.AlphaMask;
import com.nishiyu.enderframelib.core.paint.AlphaMaskProvider;

/** {@link ImageProvider} が引いた画像から、ヒットテスト用の 1 ビットの不透明マスクを作る {@link AlphaMaskProvider}。 */
public final class AlphaMasks {
    private AlphaMasks() {}

    public static AlphaMaskProvider from(ImageProvider images) {
        return (src, boxW, boxH, threshold) -> {
            if (src == null || src.isEmpty()) return null;
            ImageProvider.Image img = images.load(src, boxW, boxH);
            if (img == null || img.pixmap().width() <= 0 || img.pixmap().height() <= 0) return null;
            return new AlphaMask.Source(AlphaMask.of(img.pixmap(), threshold), img.cssW(), img.cssH());
        };
    }
}
