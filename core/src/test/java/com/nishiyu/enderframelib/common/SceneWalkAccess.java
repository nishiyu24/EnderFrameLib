package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.Painter;
import com.nishiyu.enderframelib.core.paint.SceneDamage;
import com.nishiyu.enderframelib.core.paint.SceneWalker;
import com.nishiyu.enderframelib.core.raster.Matrix2D;

/**
 * 他のパッケージのテストが、ビューの直近の表示項目の列を、描画器と同じ入力（列・平行移動の表・列を組んだシーン）で走査器にたどらせる口
 * （M3b-4b-4a。描画の経路と同じ呼び出し）。
 */
public final class SceneWalkAccess {
    private SceneWalkAccess() { }

    /** ビュー {@code v} の直近の列を {@code walker} でたどる（描き直す範囲は求めない）。 */
    public static void walk(StandaloneView v, SceneWalker walker, long nowMs, Matrix2D frame, Painter painter) {
        UiView ui = v.ui();
        walker.run(prepared(ui.renderFrame(java.util.List.of(), null, nowMs)), frame, painter, null);
    }

    /**
     * ビュー {@code v} の直近のフレームの描き直す範囲（描画器と同じ求め方）: {@code damage} に直近のシーンと今のスクロール量を渡してシーンの違いを
     * 求め、走査器でたどって補間・外の事情で変わる項目・重ね描きの範囲を足す。
     */
    public static SceneDamage.Rects damage(StandaloneView v, SceneDamage damage, SceneWalker walker, long nowMs, Painter painter) {
        UiView ui = v.ui();
        SceneDamage.Rects r = damage.next(ui.scene(), ui.dynamicState());
        walker.run(prepared(ui.renderFrame(java.util.List.of(), null, nowMs)), Matrix2D.IDENTITY, painter, r);
        return r;
    }

    /** ビューのフレームを、描画器の準備と同じく列を組んだ値にする（範囲は求めない）。 */
    static com.nishiyu.enderframelib.core.paint.PreparedFrame prepared(com.nishiyu.enderframelib.core.paint.RenderFrame f) {
        return new com.nishiyu.enderframelib.core.paint.PreparedFrame(f, new com.nishiyu.enderframelib.core.paint.FrameComposer().compose(f), null, true, null);
    }
}
