package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

/**
 * 描画器が文字と画像を解決するのに使う供給元の持ち主。
 *
 * <p>以前は GPU 版が CPU 描画器の {@code fonts(..)} / {@code images(..)} を呼んで転送していた（供給元の持ち主が CPU 描画器）。
 * 図形ディスパッチを CPU ラスタから切り離す準備として、供給元を独立した 1 つの持ち物にし、GPU 版と保険の CPU 描画器が
 * 同じ実体を共有する。差し替えは持ち主に 1 回書けば、読み手の全員に見える。
 */
public final class PaintResources {
    private volatile FontResolver fonts = SystemFonts.system();
    private volatile ImageProvider images = ImageProvider.standard();

    public FontResolver fonts() {
        return fonts;
    }

    public ImageProvider images() {
        return images;
    }

    /** null なら既定（システムフォント）へ戻す。 */
    public PaintResources fonts(FontResolver resolver) {
        this.fonts = resolver == null ? SystemFonts.system() : resolver;
        return this;
    }

    /** null なら既定の供給元へ戻す。 */
    public PaintResources images(ImageProvider provider) {
        this.images = provider == null ? ImageProvider.standard() : provider;
        return this;
    }
}
