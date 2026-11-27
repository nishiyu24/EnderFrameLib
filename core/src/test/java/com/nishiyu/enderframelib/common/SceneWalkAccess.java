package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.Painter;
import com.nishiyu.enderframelib.core.paint.SceneWalker;
import com.nishiyu.enderframelib.core.paint.model.PaintOffsets;
import com.nishiyu.enderframelib.core.raster.Matrix2D;

/** 他のパッケージのテスト（{@code SceneWalkerShadowTest}）が、ビューの直近のシーンを走査器でたどる口（M3b-4b-2）。 */
public final class SceneWalkAccess {
    private SceneWalkAccess() { }

    /** ビュー {@code v} の直近のシーンを {@code walker} でたどる（{@code V3Frame#walk}）。 */
    public static void walk(StandaloneView v, SceneWalker walker, PaintOffsets offsets, long nowMs, Matrix2D frame, Painter painter) {
        v.ui().v3.walk(walker, offsets, nowMs, frame, painter);
    }
}
