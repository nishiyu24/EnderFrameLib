package com.nishiyu.enderframelib.core.layout;

public interface LayoutEngine {
    void measure(LayoutBox box, int tentativeInnerW, int parentH, FormattingContext fmtCtx, LayoutContext ctx);
    void resolveFlexibleHeights(LayoutBox box, int parentH, FormattingContext fmtCtx, LayoutContext ctx);
    void arrangeChildren(LayoutBox box, int innerX, int innerY, LayoutContext ctx);
}