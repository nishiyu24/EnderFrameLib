package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

/**
 * グラデーションの 4 隅の色（左上・右上・右下・左下）。描画側は双線形に補間する。
 * 停止点や角度は {@code BoxDecorations.gradientStrips} が帯と隅の色に直してから渡す。
 */
public record GradientSpec(int topLeft, int topRight, int bottomRight, int bottomLeft) {
    /** 辺の向き（{@code right / left / top / bottom}）の 2 色のグラデーション。 */
    public static GradientSpec directional(String direction, int start, int end) {
        return switch (direction != null ? direction : "bottom") {
            case "right" -> new GradientSpec(start, end, end, start);
            case "left" -> new GradientSpec(end, start, start, end);
            case "top" -> new GradientSpec(end, end, start, start);
            default -> new GradientSpec(start, start, end, end);
        };
    }
}
