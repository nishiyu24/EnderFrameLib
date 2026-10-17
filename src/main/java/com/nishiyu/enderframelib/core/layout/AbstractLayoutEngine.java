package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;

public abstract class AbstractLayoutEngine implements LayoutEngine {

    public static final float DEFAULT_LINE_HEIGHT = 1.375f;

    protected boolean isDisplayNone(ComputedStyle style) {
        return "none".equals(style.getOrDefault("display", "block"));
    }

    protected float parseLineHeight(ComputedStyle style) {
        String lhStr = style.get("line-height");
        if (lhStr == null) return DEFAULT_LINE_HEIGHT;
        try {
            if (lhStr.endsWith("px")) {
                return Float.parseFloat(lhStr.replace("px", "")) / 16.0f;
            }
            return Float.parseFloat(lhStr);
        } catch (NumberFormatException e) {
            return DEFAULT_LINE_HEIGHT;
        }
    }

    protected void layoutAbsoluteChildren(LayoutBox box, LayoutContext ctx) {
        int scrollX = ctx.scrollState().getScrollX(box.node.getId());
        int scrollY = ctx.scrollState().getScrollY(box.node.getId());

        for (LayoutBox child : box.children) {
            if (isDisplayNone(child.style) || !child.isAbsolute) continue;
            child.layout(box.x - scrollX, box.y - scrollY, box.w, box.h, ctx);
        }
    }

    protected boolean isBold(ComputedStyle style) {
        String fw = style.getOrDefault("font-weight", "");
        return fw.matches("(?i)bold|bolder|700|800|900");
    }
}