package com.nishiyu.enderframelib.core.layout.model;

import com.nishiyu.enderframelib.core.layout.*;
import com.nishiyu.enderframelib.core.layout.model.*;

/**
 * @param devicePixelRatio 1 CSS px あたりの実ピクセル（{@code srcset} / {@code image-set()} の候補選びの基準）。
 *                         Surface ごとに違うので、文書ごとに持つ
 */
public record Viewport(int rootW, int rootH, float devicePixelRatio) {
    public Viewport {
        if (!(devicePixelRatio > 0) || !Float.isFinite(devicePixelRatio)) devicePixelRatio = 1f;
    }

    public Viewport(int rootW, int rootH) {
        this(rootW, rootH, 1f);
    }
}
