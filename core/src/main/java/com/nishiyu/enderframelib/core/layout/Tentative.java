package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.Shortcut;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.IntrinsicSizeResolver;
import com.nishiyu.enderframelib.core.layout.model.Viewport;
import com.nishiyu.enderframelib.core.text.model.FontResolver;

/**
 * 仮の測定（上書き寸法なし）・確定の測定（上書き寸法あり）の結果の産物と、その入力。
 *
 * <p>A7 で不変の {@code record} にし、出力 {@link MeasureOutputs} との間のエイリアスや
 * 事後の書き換えを型で防ぐ。</p>
 */
record Tentative(
        Kind kind,
        ConstraintSpace space,
        Viewport viewport,
        FontResolver fonts,
        IntrinsicSizeResolver intrinsics,
        int epoch,
        MeasureOutputs outputs,
        boolean percentFree,
        boolean shrinkFree,
        boolean outsideFree,
        boolean widthFree,
        float fitNeed,
        boolean fillFit,
        float overrideW,
        float overrideH
) {
    enum Kind { TENTATIVE, DEFINITE }

    /**
     * 同じ制約か。上書き寸法は必ず一致させる。高さの % の基準が結果に効かない箱（{@link #percentFree}）や
     * 親の shrink 指示に依らない箱（{@link #shrinkFree}）はそれらだけが違っても同じとみなし、確定幅の箱（{@link #widthFree}）や
     * 内容で寸法が決まる箱（{@link #fitNeed}）は使える幅の違いも許す。
     */
    boolean matches(ConstraintSpace s) {
        if (!MeasureAnalysis.sameFixedSizes(space, s)) return false;
        return space.equals(s)
                || MeasureAnalysis.sameIgnoringPercentAndShrink(space, s, percentFree, shrinkFree)
                || widthMatches(s)
                || fitMatches(s);
    }

    boolean widthMatches(ConstraintSpace s) {
        return Shortcut.WIDTH_INDEPENDENT_REUSE.on() && widthFree
                && MeasureAnalysis.sameBlockPercentAndParent(space, s, percentFree, shrinkFree);
    }

    boolean fitMatches(ConstraintSpace s) {
        return Shortcut.FIT_REUSE.on() && fitNeed != Float.POSITIVE_INFINITY
                && s.availableInlineSize() >= fitNeed && space.availableInlineSize() >= fitNeed
                && MeasureAnalysis.sameBlockPercentAndParent(space, s, percentFree, shrinkFree);
    }

    /** ビューポートが同じか。外を読まない箱（{@link #outsideFree}）や内容で寸法が決まる箱は、幅の違いを許す。 */
    boolean sameViewport(Viewport v) {
        return viewport.equals(v)
                || (((Shortcut.FIT_REUSE.on() && fitNeed != Float.POSITIVE_INFINITY)
                        || (Shortcut.VIEWPORT_WIDTH_FREE_REUSE.on() && outsideFree))
                        && viewport.rootH() == v.rootH() && viewport.devicePixelRatio() == v.devicePixelRatio());
    }

    boolean matchesExactRecord(Kind kind, ConstraintSpace space, Viewport viewport, FontResolver fonts,
                               IntrinsicSizeResolver intrinsics, int epoch, LayoutBox box, boolean percentFree,
                               boolean shrinkFree, boolean outsideFree, boolean widthFree, float fitNeed,
                               boolean fillFit, float overrideW, float overrideH) {
        return this.kind == kind
                && this.epoch == epoch
                && this.percentFree == percentFree
                && this.shrinkFree == shrinkFree
                && this.outsideFree == outsideFree
                && this.widthFree == widthFree
                && this.fillFit == fillFit
                && Float.floatToIntBits(this.fitNeed) == Float.floatToIntBits(fitNeed)
                && MeasureAnalysis.sameLength(this.overrideW, overrideW)
                && MeasureAnalysis.sameLength(this.overrideH, overrideH)
                && this.fonts == fonts
                && this.intrinsics == intrinsics
                && this.space.equals(space)
                && this.viewport.equals(viewport)
                && this.outputs.matchesAll(box);
    }
}
