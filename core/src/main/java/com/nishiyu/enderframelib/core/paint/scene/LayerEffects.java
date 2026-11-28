package com.nishiyu.enderframelib.core.paint.scene;

import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.paint.model.ClipShape;
import com.nishiyu.enderframelib.core.paint.model.FilterOp;

import java.util.List;

/**
 * スタッキングコンテキストの合成効果（不透明度・フィルター・混合モード・マスク・{@code backdrop-filter}）。
 * 全て既定値なら {@link SceneLayer#effects} は {@code null} にする。
 */
public record LayerEffects(
        float opacity,
        boolean animatesOpacity,
        List<FilterOp> filters,
        BlendMode blendMode,
        boolean isolate,
        boolean masked,
        boolean luminanceMask,
        List<FilterOp> backdropFilters,
        ClipShape backdropShape
) {
    public LayerEffects {
        filters = filters == null || filters.isEmpty() ? List.of() : List.copyOf(filters);
        backdropFilters = backdropFilters == null || backdropFilters.isEmpty() ? List.of() : List.copyOf(backdropFilters);
        if (blendMode == null) blendMode = BlendMode.NORMAL;
    }

    public boolean hasBackdrop() {
        return !backdropFilters.isEmpty();
    }

    /**
     * フィルターが層の画素を外へ広げる幅（CSS px。ぼかしと影。無ければ 0）。描き直す範囲（{@code SceneDamage}）と層の包含矩形
     * （{@link SceneLayer#boundsMinX} ほか。描くフレームのカリング）が同じ規則で広げる。
     */
    public float filterReach() {
        float outset = 0f;
        for (FilterOp f : filters) {
            if (f.kind() == FilterOp.Kind.BLUR) outset += 3f * f.amount() + DrawOps.INK_PAD;
            else if (f.kind() == FilterOp.Kind.DROP_SHADOW) outset += Math.max(Math.abs(f.dx()), Math.abs(f.dy())) + 3f * f.amount() + DrawOps.INK_PAD;
        }
        return outset;
    }
}
