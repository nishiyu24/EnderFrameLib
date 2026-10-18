package com.nishiyu.enderframelib.core.layout;

public interface LayoutEngine {
    void measure(LayoutBox box, float tentativeInnerW, float parentH, FormattingContext fmtCtx, LayoutContext ctx);
    void resolveFlexibleHeights(LayoutBox box, float parentH, FormattingContext fmtCtx, LayoutContext ctx);
    void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx);
}