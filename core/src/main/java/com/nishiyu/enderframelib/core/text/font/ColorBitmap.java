package com.nishiyu.enderframelib.core.text.font;

import com.nishiyu.enderframelib.core.raster.Pixmap;

/**
 * COLR v1 またはカラービットマップ（{@code CBDT}/{@code sbix}）の絵文字を描いたビットマップ（事前乗算 ARGB）。
 * {@code left} は原点から左端、{@code top} は原点から上端の論理画素数（{@link GlyphBitmap} と同じ向き）、
 * {@code width}/{@code height} は描画先の大きさ。
 */
public record ColorBitmap(Pixmap pixmap, float left, float top, float width, float height) {
    public ColorBitmap(Pixmap pixmap, int left, int top) {
        this(pixmap, left, top, pixmap.width(), pixmap.height());
    }
}
