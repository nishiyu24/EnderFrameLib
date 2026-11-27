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

    /**
     * ビュー {@code v} の直近のフレームの描き直す範囲（M3b-4b-3b）: {@code damage} に直近のシーンと今のスクロール量を渡してシーンの違いを求め、
     * 走査器でたどって補間・外の事情で変わる項目の範囲を足す。
     */
    public static com.nishiyu.enderframelib.core.paint.SceneDamage.Rects damage(StandaloneView v, com.nishiyu.enderframelib.core.paint.SceneDamage damage,
                                                                              SceneWalker walker, long nowMs, Painter painter) {
        com.nishiyu.enderframelib.core.paint.SceneDamage.Rects r = damage.next(v.ui().v3.scene(), v.ui().dynamicState());
        v.ui().v3.walk(walker, PaintOffsets.EMPTY, nowMs, Matrix2D.IDENTITY, painter, r);
        return r;
    }
}
