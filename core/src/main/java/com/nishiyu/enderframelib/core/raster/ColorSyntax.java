package com.nishiyu.enderframelib.core.raster;

import java.util.ServiceLoader;

/**
 * 2D ラスタ層（L2）が CSS の色文字列を解くための窓口。CSS の色解析（{@code core.css.ColorParser}、L3）を
 * L2 から直接参照しないための SPI で、実装は {@code core.css.CssColorSyntax}（{@code META-INF/services} で登録）。
 */
public interface ColorSyntax {
    /** CSS の色として解けるか（{@code currentcolor} を含む）。 */
    boolean isColor(String css);

    /** CSS の色を ARGB にする。解けなければ透明。 */
    int parseColor(String css);

    /** 登録された実装（なければ全て無効色の実装）。 */
    static ColorSyntax get() {
        return Holder.INSTANCE;
    }

    final class Holder {
        static final ColorSyntax INSTANCE = load();

        private Holder() {}

        private static ColorSyntax load() {
            for (ColorSyntax s : ServiceLoader.load(ColorSyntax.class, ColorSyntax.class.getClassLoader())) return s;
            return new ColorSyntax() {
                @Override public boolean isColor(String css) { return false; }
                @Override public int parseColor(String css) { return 0; }
            };
        }
    }
}
