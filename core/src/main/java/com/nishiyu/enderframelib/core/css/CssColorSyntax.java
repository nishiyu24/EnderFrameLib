package com.nishiyu.enderframelib.core.css;

import com.nishiyu.enderframelib.core.raster.ColorSyntax;

/** {@link ColorSyntax}（L2 の窓口）の実装。{@link ColorParser} へ委譲する。ServiceLoader が生成する。 */
public final class CssColorSyntax implements ColorSyntax {
    @Override public boolean isColor(String css) { return ColorParser.isColor(css); }

    @Override public int parseColor(String css) { return ColorParser.parseColor(css); }
}
