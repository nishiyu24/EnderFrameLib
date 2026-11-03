package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.AlphaMask;

/** {@link ImageProvider} が引いた画像から、ヒットテスト用の 1 ビットの不透明マスクを作る {@link AlphaMask.Provider}。 */
public final class AlphaMasks {
    private AlphaMasks() {}

    public static AlphaMask.Provider from(ImageProvider images) {
        return (item, threshold) -> {
            ImageProvider.Image img = images.load(item, 0, 0);
            if (img == null || img.pixmap().width() <= 0 || img.pixmap().height() <= 0) return null;
            return new AlphaMask.Source(AlphaMask.of(img.pixmap(), threshold), img.cssW(), img.cssH());
        };
    }
}
