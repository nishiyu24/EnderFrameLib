package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import java.util.function.Function;

/**
 * 呼び出し側の画像の出所（{@code src} → 画素）を先に引き、無ければ基底の {@link ImageProvider} へ回す。
 * 9-slice の切り出しは基底の規則に任せ、元の画像の取得にもこの差し替えが効く。
 */
public final class OverrideImages implements ImageProvider {

    /** 差し替え側の結果（事前乗算でない ARGB）。 */
    public record Raw(int width, int height, int[] argb, float cssWidth, float cssHeight) {}

    private final ImageProvider base;
    private final Function<String, Raw> override;

    public OverrideImages(ImageProvider base, Function<String, Raw> override) {
        this.base = java.util.Objects.requireNonNull(base, "base");
        this.override = java.util.Objects.requireNonNull(override, "override");
    }

    private Image custom(String src) {
        if (src == null || src.isEmpty()) return null;
        Raw r = override.apply(src);
        if (r == null) return null;
        Pixmap pm = new Pixmap(r.width(), r.height());
        int[] px = pm.pixels();
        for (int i = 0; i < px.length; i++) px[i] = Pixmap.premultiply(r.argb()[i]);
        return new Image(pm, r.cssWidth(), r.cssHeight());
    }

    @Override
    public Image load(String src, int boxW, int boxH) {
        Image c = custom(src);
        return c != null ? c : base.load(src, boxW, boxH);
    }

    @Override
    public Image load(DisplayItem item, int boxW, int boxH) {
        if (item.type() == ElementType.CANVAS) return base.load(item, boxW, boxH);
        String src = item.text();
        if (src != null && CropSrc.isCrop(src)) return ImageProvider.super.load(item, boxW, boxH);
        if (item.type() == ElementType.IMAGE) {
            Image c = custom(src);
            if (c != null) return c;
        }
        return base.load(item, boxW, boxH);
    }

    /** 基底が HTTP 取得つきなら、その取得の進み具合を見るために返す。 */
    public ImageProvider.Web web() {
        return base instanceof ImageProvider.Web w ? w : null;
    }

    /** 基底が閉じられるもの（{@link ImageProvider.Web}）なら閉じる。 */
    public void close() {
        if (base instanceof ImageProvider.Web w) w.close();
    }
}
