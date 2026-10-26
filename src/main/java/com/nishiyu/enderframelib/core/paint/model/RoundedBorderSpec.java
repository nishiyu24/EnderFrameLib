package com.nishiyu.enderframelib.core.paint.model;

import com.nishiyu.enderframelib.core.paint.*;
import com.nishiyu.enderframelib.core.paint.model.*;

/**
 * 角丸の枠線の辺ごとの太さと色（上・右・下・左）。外側の半径は {@link RenderMeta#borderRadii()}。
 * {@code dashKind} は 4 辺が同じ線種・太さ・色のときの破線（1）・点線（2）で、角の円弧に沿って経路ごと割り付ける。実線は 0。
 */
public record RoundedBorderSpec(float widthTop, float widthRight, float widthBottom, float widthLeft,
                                int colorTop, int colorRight, int colorBottom, int colorLeft, int dashKind) {
    /** 実線。 */
    public RoundedBorderSpec(float widthTop, float widthRight, float widthBottom, float widthLeft,
                             int colorTop, int colorRight, int colorBottom, int colorLeft) {
        this(widthTop, widthRight, widthBottom, widthLeft, colorTop, colorRight, colorBottom, colorLeft, 0);
    }

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
