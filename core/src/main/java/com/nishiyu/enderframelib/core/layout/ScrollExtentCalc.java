package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.dom.node.NodeType;

import java.util.List;

/**
 * 配置後の後処理（relative/sticky のずらし・fieldset legend 配置・スクロール範囲の集計）（A7）。
 */
final class ScrollExtentCalc {
    private ScrollExtentCalc() {}

    static void placeRenderedLegend(LayoutBox box, LayoutBox legend, LayoutContext ctx) {
        float contentW = Math.max(0, box.w - box.horizontalDecoration());
        boolean autoLeft = "auto".equals(legend.style.get(CssPropertyIndex.MARGIN_LEFT));
        boolean autoRight = "auto".equals(legend.style.get(CssPropertyIndex.MARGIN_RIGHT));
        float free = contentW - legend.w - (autoLeft ? 0 : legend.mL) - (autoRight ? 0 : legend.mR);
        if (autoLeft && autoRight) {
            legend.mL = Math.max(0, free / 2);
        } else if (autoLeft) {
            legend.mL = Math.max(0, free);
        }
        float top = Math.max(0, (box.bT - legend.h) / 2);
        legend.layout(box.bL + box.pL, top - legend.mT, contentW, Math.max(0, box.h - box.verticalDecoration()), ctx);
    }

    static void applyRelativePositioning(LayoutBox box, LayoutContext ctx) {
        if (!box.isRelative) return;
        applyRelativeWithOuter(box, 0f, 0f, ctx, false);
    }

    static void applyRelativeOnReuse(LayoutBox box, float cbW, float cbH, LayoutContext ctx) {
        if (!box.isRelative) return;
        applyRelativeWithOuter(box, cbW, cbH, ctx, true);
    }

    private static void applyRelativeWithOuter(LayoutBox box, float outerW, float outerH,
                                               LayoutContext ctx, boolean reuse) {
        ComputedStyle ax = box.axisStyle();
        boolean hasL = ax.containsKey(CssPropertyIndex.LEFT), hasR = !hasL && ax.containsKey(CssPropertyIndex.RIGHT);
        boolean hasT = ax.containsKey(CssPropertyIndex.TOP), hasB = !hasT && ax.containsKey(CssPropertyIndex.BOTTOM);
        if (!hasL && !hasR && !hasT && !hasB) return;

        float rootW = 0f, rootH = 0f;
        if (box.style.usesViewportUnits()) {
            rootW = reuse ? LayoutReads.peekViewportW(ctx) : LayoutReads.placeViewportW(ctx);
            rootH = reuse ? LayoutReads.peekViewportH(ctx) : LayoutReads.placeViewportH(ctx);
        }
        float offX = 0, offY = 0;
        if (hasL || hasR) {
            float cbW = reuse ? outerW : LayoutReads.parentOuterW();
            offX = hasL ? ax.length(CssPropertyIndex.LEFT, cbW, rootW, rootH)
                    : -ax.length(CssPropertyIndex.RIGHT, cbW, rootW, rootH);
        }
        if (hasT || hasB) {
            float cbH = reuse ? outerH : LayoutReads.parentOuterH();
            offY = hasT ? ax.length(CssPropertyIndex.TOP, cbH, rootW, rootH)
                    : -ax.length(CssPropertyIndex.BOTTOM, cbH, rootW, rootH);
        }

        if (offX != 0 || offY != 0) box.applyRelativeOffset(offX, offY);
    }

    static void applyStickyOffset(LayoutBox box, LayoutContext ctx) {
        applySticky(box, ctx, false);
    }

    static void applyStickyOnReuse(LayoutBox box, LayoutContext ctx) {
        applySticky(box, ctx, true);
    }

    private static void applySticky(LayoutBox box, LayoutContext ctx, boolean reuse) {
        if (box.parent == null) return;
        float rootW = reuse ? LayoutReads.peekViewportW(ctx) : LayoutReads.placeViewportW(ctx);
        float rootH = reuse ? LayoutReads.peekViewportH(ctx) : LayoutReads.placeViewportH(ctx);
        LayoutBox port = reuse ? LayoutReads.peekStickyScrollContainer(box) : LayoutReads.stickyScrollContainer(box);
        float portX = port != null ? (reuse ? port.x() + port.bL : LayoutReads.stickyPortX(port)) : 0f;
        float portY = port != null ? (reuse ? port.y() + port.bT : LayoutReads.stickyPortY(port)) : 0f;
        float portW = port != null ? (reuse ? Math.max(0, port.w - port.bL - port.bR) : LayoutReads.stickyPortW(port)) : rootW;
        float portH = port != null ? (reuse ? Math.max(0, port.h - port.bT - port.bB) : LayoutReads.stickyPortH(port)) : rootH;

        float cbTop = reuse ? LayoutReads.peekParentContentTop(box) : LayoutReads.parentContentTop(box);
        float cbBottom = reuse ? LayoutReads.peekParentContentBottom(box) : LayoutReads.parentContentBottom(box);
        float cbLeft = reuse ? LayoutReads.peekParentContentLeft(box) : LayoutReads.parentContentLeft(box);
        float cbRight = reuse ? LayoutReads.peekParentContentRight(box) : LayoutReads.parentContentRight(box);

        float bx = box.x();
        float by = box.y();
        ComputedStyle ax = box.axisStyle();
        float dx = 0, dy = 0;
        if (!BoxMeasurer.isAutoInset(ax, CssPropertyIndex.TOP)) {
            float limit = portY + ax.length(CssPropertyIndex.TOP, portH, rootW, rootH);
            if (by < limit) dy = Math.max(0, Math.min(limit - by, cbBottom - (by + box.h + box.mB)));
        } else if (!BoxMeasurer.isAutoInset(ax, CssPropertyIndex.BOTTOM)) {
            float limit = portY + portH - ax.length(CssPropertyIndex.BOTTOM, portH, rootW, rootH);
            if (by + box.h > limit) dy = -Math.max(0, Math.min(by + box.h - limit, by - box.mT - cbTop));
        }
        if (!BoxMeasurer.isAutoInset(ax, CssPropertyIndex.LEFT)) {
            float limit = portX + ax.length(CssPropertyIndex.LEFT, portW, rootW, rootH);
            if (bx < limit) dx = Math.max(0, Math.min(limit - bx, cbRight - (bx + box.w + box.mR)));
        } else if (!BoxMeasurer.isAutoInset(ax, CssPropertyIndex.RIGHT)) {
            float limit = portX + portW - ax.length(CssPropertyIndex.RIGHT, portW, rootW, rootH);
            if (bx + box.w > limit) dx = -Math.max(0, Math.min(bx + box.w - limit, bx - box.mL - cbLeft));
        }
        box.stickyDy = dy;
        box.stickyUnstuckY = box.y;
        if (dx != 0 || dy != 0) box.applyRelativeOffset(dx, dy);
        box.stickyPlacedY = box.y;
    }

    static float[] stickyScrollParams(LayoutBox box, LayoutBox port, float rootW, float rootH) {
        if (!box.isSticky || box.parent == null || port == null) return null;
        LayoutBox near = LayoutReads.peekStickyScrollContainer(box);
        if (near != port) return null;
        ComputedStyle ax = box.axisStyle();
        if (BoxMeasurer.isAutoInset(ax, CssPropertyIndex.TOP) || !BoxMeasurer.isAutoInset(ax, CssPropertyIndex.BOTTOM)
                || !BoxMeasurer.isAutoInset(ax, CssPropertyIndex.LEFT) || !BoxMeasurer.isAutoInset(ax, CssPropertyIndex.RIGHT)) {
            return null;
        }
        float portY = port.y() + port.bT;
        float portH = Math.max(0, port.h - port.bT - port.bB);
        float limit = portY + ax.length(CssPropertyIndex.TOP, portH, rootW, rootH);
        float cbBottom = LayoutReads.peekParentContentBottom(box);
        if (Float.floatToIntBits(box.y) != Float.floatToIntBits(box.stickyPlacedY)) return null;
        float unstuckAbsY = PlacementMemo.originParentY(box) + box.stickyUnstuckY;
        return new float[]{limit, cbBottom, unstuckAbsY, box.h, box.mB, box.parent != port ? 1f : 0f};
    }

    static void updateScrollExtent(LayoutBox box, float scrollX, float scrollY) {
        if (box.children.isEmpty() && box.engineState == null && box.inlineContentBottom <= Float.NEGATIVE_INFINITY) {
            box.descendantOverflowRight = box.w;
            box.descendantOverflowBottom = box.h;
            if (box.isCollapsedTable()) {
                box.scrollWidth = box.pL + box.bL;
                box.scrollHeight = box.pT + box.bT;
            } else {
                box.scrollWidth = Math.max(box.w, box.pL + box.bL + box.pR + box.bR);
                box.scrollHeight = Math.max(box.h, box.pT + box.bT + box.pB + box.bB);
            }
            return;
        }

        float extRight = box.pL + box.bL;
        float extBottom = box.pT + box.bT;
        float descRight = box.w;
        float descBottom = box.h;

        boolean marginEscapes = !box.establishesBfc && box.pB == 0 && box.bB == 0 && !hasDefiniteHeight(box);
        if (box.engineState instanceof VirtualFlowState vf) {
            float contentOriginY = box.pT + box.bT + box.contentOffsetY + box.legendSpill;
            float virtualBottom = contentOriginY + vf.contentExtentBottom(marginEscapes);
            if (virtualBottom > extBottom) extBottom = virtualBottom;
            if (virtualBottom > descBottom) descBottom = virtualBottom;
            for (int k = 0, m = vf.placedCount(); k < m; k++) {
                LayoutBox child = vf.placedChildAt(k);
                if (child == null || child.style.facts().displayNone || child.isAbsolute) continue;
                float r = child.x + child.w + child.mR + scrollX;
                float b = child.y + child.h + child.mB + scrollY;
                if (r > extRight) extRight = r;
                if (b > extBottom) extBottom = b;
                float cr = child.x + child.w + scrollX;
                float cb = child.y + child.h + scrollY;
                if (cr > descRight) descRight = cr;
                if (cb > descBottom) descBottom = cb;
                if (!clipsOverflow(child)) {
                    float ovR = child.x + child.descendantOverflowRight + scrollX;
                    float ovB = child.y + child.descendantOverflowBottom + scrollY;
                    if (ovR > descRight) descRight = ovR;
                    if (ovB > descBottom) descBottom = ovB;
                }
            }
        } else {
            LayoutBox lastInFlow = null;
            if (marginEscapes) {
                for (int i = box.children.size() - 1; i >= 0; i--) {
                    LayoutBox child = box.children.get(i);
                    if (!child.style.facts().displayNone && !child.isAbsolute && !child.display.isInlineLevel()) {
                        lastInFlow = child;
                        break;
                    }
                }
            }
            for (int i = 0, n = box.children.size(); i < n; i++) {
                LayoutBox child = box.children.get(i);
                if (child.style.facts().displayNone || child.isAbsolute) continue;
                if (child.node != null && child.node.nodeType == NodeType.TEXT
                        && !child.fragmentsStale && !child.inlineFragments.isEmpty()) {
                    List<LayoutBox.InlineFragment> frags = child.inlineFragments;
                    for (int j = 0, m = frags.size(); j < m; j++) {
                        LayoutBox.InlineFragment frag = frags.get(j);
                        float right = frag.x + frag.w + scrollX;
                        float bottom = Math.max(frag.y + frag.h, frag.y + frag.baseline + frag.descent) + scrollY;
                        if (right > extRight) extRight = right;
                        if (bottom > extBottom) extBottom = bottom;
                        if (right > descRight) descRight = right;
                        if (bottom > descBottom) descBottom = bottom;
                    }
                    continue;
                }
                if (child.isVerticalWrapper) {
                    int flow = box.isHorizontalReturnRoot
                            ? (box.orthogonalParentFlow & 0xFF)
                            : WritingModes.flowOf(box.style);
                    boolean tr = WritingModes.isTransposed(flow);
                    boolean parentCcw = WritingModes.isCounterClockwise(flow);
                    boolean ccw = box.isHorizontalReturnRoot ? (!tr && !parentCcw) : parentCcw;
                    float along = Math.max(child.w, child.descendantOverflowRight);
                    float across = Math.max(child.h, child.descendantOverflowBottom);
                    float r = (ccw || tr ? child.x + across : child.x) + scrollX;
                    float b = (ccw ? child.y : child.y + along) + scrollY;
                    if (r > extRight) extRight = r;
                    if (b > extBottom) extBottom = b;
                    if (r > descRight) descRight = r;
                    if (b > descBottom) descBottom = b;
                    continue;
                }
                if (child == lastInFlow) {
                    float r = child.x + child.w + child.mR + scrollX;
                    float b = child.y + child.h + scrollY;
                    if (r > extRight) extRight = r;
                    if (b > extBottom) extBottom = b;
                } else {
                    float r = child.x + child.w + child.mR + scrollX;
                    float b = child.y + child.h + child.mB + scrollY;
                    if (r > extRight) extRight = r;
                    if (b > extBottom) extBottom = b;
                }
                float cr = child.x + child.w + scrollX;
                float cb = child.y + child.h + scrollY;
                if (cr > descRight) descRight = cr;
                if (cb > descBottom) descBottom = cb;
                if (!clipsOverflow(child)) {
                    float ovR = child.x + child.descendantOverflowRight + scrollX;
                    float ovB = child.y + child.descendantOverflowBottom + scrollY;
                    if (ovR > descRight) descRight = ovR;
                    if (ovB > descBottom) descBottom = ovB;
                }
            }
        }
        if (box.engineState != null && box.engineState.contributesScrollExtent()) {
            LayoutBox.ScrollExtent extent = new LayoutBox.ScrollExtent(extRight, extBottom);
            box.engineState.contributeScrollExtent(extent, scrollX, scrollY);
            extRight = extent.right();
            extBottom = extent.bottom();
        }
        if (box.inlineContentBottom > Float.NEGATIVE_INFINITY) {
            float b = box.inlineContentBottom + scrollY;
            if (b > extBottom) extBottom = b;
        }

        box.descendantOverflowRight = descRight;
        box.descendantOverflowBottom = descBottom;
        if (box.isCollapsedTable()) {
            box.scrollWidth = extRight;
            box.scrollHeight = extBottom;
            return;
        }
        box.scrollWidth = Math.max(Math.max(box.w, extRight + box.pR + box.bR), box.descendantOverflowRight);
        box.scrollHeight = Math.max(Math.max(box.h, extBottom + box.pB + box.bB), box.descendantOverflowBottom);
    }

    private static boolean hasDefiniteHeight(LayoutBox box) {
        ComputedStyle ax = box.axisStyle();
        String height = ax.get(CssPropertyIndex.HEIGHT);
        if (height != null && !height.isBlank() && !"auto".equalsIgnoreCase(height.trim())) return true;
        String minHeight = ax.get(CssPropertyIndex.MIN_HEIGHT);
        return minHeight != null && !minHeight.isBlank() && !"0".equals(minHeight.trim()) && !"0px".equals(minHeight.trim())
                && !"auto".equalsIgnoreCase(minHeight.trim());
    }

    static boolean clipsOverflow(LayoutBox box) {
        ComputedStyle ax = box.axisStyle();
        return ax.overflowX().clips() || ax.overflowY().clips();
    }
}
