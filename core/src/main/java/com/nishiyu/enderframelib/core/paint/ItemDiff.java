package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.BlendMode;
import com.nishiyu.enderframelib.core.css.model.AnimationSpec;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.model.LayerSpec;
import com.nishiyu.enderframelib.core.paint.model.RenderMeta;

/**
 * 「前のフレームの項目」と「いまの項目」の比べ方を 1 か所に集めたもの（B9 の M1 / C6）。
 * {@link DisplayItem} は完全に不変であり、変化の判定は実体の同一性（{@code ==}）1 つに一本化している。
 * ここ以外で項目同士を {@code ==} / {@code !=} で比べてはならない（{@code ItemDiffRatchetTest}）。
 */
public final class ItemDiff {
    private ItemDiff() {}

    /** 前回から何も変わっていない項目か（項目は不変なので実体の同一性だけを見る）。 */
    public static boolean unchanged(DisplayItem prev, DisplayItem cur) {
        return prev == cur;
    }

    /** {@link #unchanged} の否定。 */
    public static boolean changed(DisplayItem prev, DisplayItem cur) {
        return prev != cur;
    }

    /** 層を要する効果が 1 つでもあるか。 */
    public static boolean hasFx(LayerSpec spec) {
        return spec != null && (!spec.filters().isEmpty() || spec.masked() || spec.blend() != BlendMode.NORMAL || spec.isolate()
                || spec.clip() != null || spec.hasBackdrop());
    }

}
