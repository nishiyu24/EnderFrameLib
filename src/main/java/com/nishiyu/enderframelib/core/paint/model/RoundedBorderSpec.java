package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

/** 角丸の枠線の辺ごとの太さと色（上・右・下・左）。外側の半径は {@link RenderMeta#borderRadii()}。 */
public record RoundedBorderSpec(float widthTop, float widthRight, float widthBottom, float widthLeft,
                                int colorTop, int colorRight, int colorBottom, int colorLeft) {
    public float[] widths() {
        return new float[]{widthTop, widthRight, widthBottom, widthLeft};
    }

    public int color(int side) {
        return switch (side) {
            case 0 -> colorTop;
            case 1 -> colorRight;
            case 2 -> colorBottom;
            default -> colorLeft;
        };
    }
}
