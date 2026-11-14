package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.BlendMode;
import com.nishiyu.enderframelib.core.paint.PaintSceneDriver.Group;
import com.nishiyu.enderframelib.core.paint.RetainPlan;
import com.nishiyu.enderframelib.core.paint.gpu.GpuScrollLayer;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;

import java.util.List;

/**
 * 層（グループ・マスク・スクロール保持層）の「GPU に持たせられるか」の問い合わせ窓口。図形の試行を集めた
 * {@link GpuAttempt} の層版で、{@link SoftwareRenderBackend} は {@link GpuSink} を直接呼ばず、ここを通す。
 * 受け取られた（true）なら層の画素は GPU が持ち、CPU は層を確保しない。{@code sink} が null なら常に false。
 */
final class GpuLayerAttempt {
    private GpuLayerAttempt() {}

    /** マスクの層を GPU で開けるか。 */
    static boolean beginMask(GpuSink sink) {
        return sink != null && sink.beginMask();
    }

    /** グループの層を GPU で開けるか（フィルター・クリップ・backdrop・混合モード・マスク有無を伝える）。 */
    static boolean beginGroup(GpuSink sink, Group g) {
        if (sink == null) return false;
        LayerSpec spec = g.spec();
        GpuSink.GroupExtras extras = spec != null && (spec.clip() != null || spec.hasBackdrop())
                ? new GpuSink.GroupExtras(g.pose(), spec.clip(), spec.hasBackdrop() ? spec.backdrop() : List.of(),
                spec.hasBackdrop() ? spec.backdropShape() : null) : null;
        return sink.beginGroup(g.opacity(), spec != null ? spec.filters() : List.of(), g.map(),
                spec != null && spec.masked(), spec != null && spec.maskLuminance(),
                spec != null ? spec.blend() : BlendMode.NORMAL, extras);
    }

    /** GPU が持つ層（マスクならマスク）を閉じる。 */
    static void end(GpuSink sink, boolean mask) {
        if (mask) sink.endMask();
        else sink.endGroup();
    }

    /** スクロール保持層の方針。{@code sink} がなければ NONE。 */
    static RetainPlan planScroll(GpuSink sink, GpuScrollLayer info) {
        return sink == null ? RetainPlan.NONE : sink.planScrollLayer(info);
    }

    /** 境界矩形だけでスクロール保持層の方針を問い合わせる（対応していなければ null）。 */
    static RetainPlan planScrollBounds(GpuSink sink, String key, Object token, com.nishiyu.enderframelib.core.raster.Matrix2D pose,
                                       int x0, int y0, int x1, int y1) {
        return sink == null ? RetainPlan.NONE : sink.planScrollLayerBounds(key, token, pose, x0, y0, x1, y1);
    }

    /** 保持済みのスクロール層を重ねる。 */
    static void drawScroll(GpuSink sink, GpuScrollLayer l) {
        if (l != null && sink != null) sink.drawScrollLayer(l);
    }

    /** 保持層への記録を始める。始めたら true。 */
    static boolean beginScroll(GpuSink sink, GpuScrollLayer l) {
        if (l == null || sink == null) return false;
        sink.beginScrollLayer(l);
        return true;
    }

    /** 保持層への記録を終える。保持できなければ false（層は捨てられる）。{@code l} か {@code sink} がなければ true。 */
    static boolean endScroll(GpuSink sink, GpuScrollLayer l) {
        return l == null || sink == null || sink.endScrollLayer(l);
    }
}
