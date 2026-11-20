package com.nishiyu.enderframelib.core.text.font;

import java.util.ServiceLoader;

/** {@link ColrV1Renderer} の実装の置き場（{@link ServiceLoader} で見つけた最初のもの。無ければ null）。 */
final class ColrV1Renderers {
    private ColrV1Renderers() {}

    private static final ColrV1Renderer RENDERER = load();

    private static ColrV1Renderer load() {
        for (ColrV1Renderer r : ServiceLoader.load(ColrV1Renderer.class, ColrV1Renderer.class.getClassLoader())) return r;
        return null;
    }

    /** 実装があれば描く（{@link ColrV1Renderer#render}）。実装が無いときは null。 */
    static ColrV1Renderer.Result render(ColrV1Renderer.Face owner, org.lwjgl.util.freetype.FT_Face face, int glyphIndex, float pixelSize, int foregroundArgb) {
        return RENDERER == null ? null : RENDERER.render(owner, face, glyphIndex, pixelSize, foregroundArgb);
    }
}
