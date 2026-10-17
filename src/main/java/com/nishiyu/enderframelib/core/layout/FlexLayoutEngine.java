package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.ComputedStyle;

import java.util.ArrayList;
import java.util.List;

public class FlexLayoutEngine extends AbstractLayoutEngine {

    private float getFloatProp(ComputedStyle style, String key, float defaultVal) {
        if (style.containsKey(key)) {
            try { return Float.parseFloat(style.get(key)); } catch (Exception ignored) {}
        }
        return defaultVal;
    }

    @Override
    public void measure(LayoutBox box, int tentativeInnerW, int parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        String dir = box.style.getOrDefault("flex-direction", "row");
        boolean isFlexRow = dir.startsWith("row");
        boolean isFlexColumn = dir.startsWith("column");
        String alignItems = box.style.getOrDefault("align-items", "stretch");

        int maxChildW = 0, sumChildW = 0, activeChildren = 0;
        for (LayoutBox child : box.children) {
            if (child.isAbsolute || isDisplayNone(child.style)) continue;
            String childAlign = child.style.getOrDefault("align-self", alignItems);

            FormattingContext childCtx = new FormattingContext(dir, childAlign, box.sizingMode == SizingMode.SHRINK_TO_FIT);
            child.measure(tentativeInnerW, parentH, childCtx, ctx);

            maxChildW = Math.max(maxChildW, child.w + child.mL + child.mR);
            sumChildW += child.w + child.mL + child.mR;
            activeChildren++;
        }

        if (box.sizingMode == SizingMode.SHRINK_TO_FIT) {
            if (isFlexRow) box.w = sumChildW + (activeChildren > 1 ? box.columnGap * (activeChildren - 1) : 0) + box.horizontalDecoration();
            else box.w = maxChildW + box.horizontalDecoration();
        }
    }

    @Override
    public void resolveFlexibleHeights(LayoutBox box, int parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        String dir = box.style.getOrDefault("flex-direction", "row");
        boolean isFlexRow = dir.startsWith("row");
        boolean isFlexColumn = dir.startsWith("column");
        String flexWrap = box.style.getOrDefault("flex-wrap", "nowrap");

        int actualInnerW = Math.max(0, box.w - box.horizontalDecoration());

        if (!box.style.containsKey("height") || "auto".equals(box.style.getOrDefault("height", "auto"))) {
            int contentH = 0;
            if (isFlexRow && "wrap".equals(flexWrap)) {
                int currentLineW = 0, currentLineH = 0;
                for (LayoutBox child : box.getFlowChildren()) {
                    int childOuterW = child.w + child.mL + child.mR, childOuterH = child.h + child.mT + child.mB;
                    if (currentLineW + childOuterW > actualInnerW && currentLineW > 0) {
                        contentH += currentLineH + box.rowGap;
                        currentLineW = 0;
                        currentLineH = 0;
                    }
                    currentLineW += childOuterW + box.columnGap;
                    currentLineH = Math.max(currentLineH, childOuterH);
                }
                contentH += currentLineH;
            } else if (isFlexRow) {
                for (LayoutBox child : box.getFlowChildren()) {
                    contentH = Math.max(contentH, child.h + child.mT + child.mB);
                }
            } else if (isFlexColumn) {
                for (LayoutBox child : box.getFlowChildren()) {
                    contentH += child.h + child.mT + child.mB;
                }
                if (box.getFlowChildren().size() > 1) contentH += box.rowGap * (box.getFlowChildren().size() - 1);
            }
            box.h = Math.max(box.h, contentH + box.verticalDecoration());
        }

        int actualInnerH = Math.max(0, box.h - box.verticalDecoration());
        String align = box.style.getOrDefault("align-items", "stretch");

        // A-5バグ修正: キャッシュの可変マップを直接書き換えず、LayoutBox のフィールドと SizingMode を更新して再計算を促す
        if ("nowrap".equals(flexWrap)) {
            for (LayoutBox child : box.getFlowChildren()) {
                String effAlign = child.style.getOrDefault("align-self", align);
                if (!"stretch".equals(effAlign)) continue;

                if (isFlexRow && !child.style.containsKey("height")) {
                    int targetH = actualInnerH - child.mT - child.mB;
                    if (child.h < targetH) {
                        child.h = Math.max(0, targetH);
                        child.resolveFlexibleHeights(parentH, new FormattingContext(dir, effAlign, false), ctx);
                    }
                } else if (isFlexColumn && !child.style.containsKey("width")) {
                    int targetW = actualInnerW - child.mL - child.mR;
                    if (child.w < targetW) {
                        child.w = Math.max(0, targetW);
                        child.sizingMode = SizingMode.DEFINITE;
                        child.resolveFlexibleHeights(parentH, new FormattingContext(dir, effAlign, false), ctx);
                    }
                }
            }
        }

        float totalGrowW = 0, totalGrowH = 0;
        int usedW = 0, usedH = 0;
        List<LayoutBox> flowChildren = box.getFlowChildren();
        for (LayoutBox child : flowChildren) {
            if (isFlexRow) {
                usedW += child.w + child.mL + child.mR;
                totalGrowW += getFloatProp(child.style, "flex-grow", 0f);
            } else if (isFlexColumn) {
                usedH += child.h + child.mT + child.mB;
                totalGrowH += getFloatProp(child.style, "flex-grow", 0f);
            }
        }
        if (flowChildren.size() > 1) {
            if (isFlexRow) usedW += box.columnGap * (flowChildren.size() - 1);
            if (isFlexColumn) usedH += box.rowGap * (flowChildren.size() - 1);
        }

        if (isFlexRow && "nowrap".equals(flexWrap) && totalGrowW > 0 && actualInnerW > usedW) {
            int extraW = Math.max(0, actualInnerW - usedW);
            for (LayoutBox child : flowChildren) {
                float grow = getFloatProp(child.style, "flex-grow", 0f);
                if (grow > 0) {
                    child.w += (int) (extraW * (grow / totalGrowW));
                    child.sizingMode = SizingMode.DEFINITE;
                    child.resolveFlexibleHeights(parentH, new FormattingContext(dir, align, false), ctx);
                }
            }
        } else if (isFlexRow && "nowrap".equals(flexWrap) && actualInnerW < usedW) {
            String overflowX = box.style.getOrDefault("overflow-x", box.style.getOrDefault("overflow", "visible"));
            if (!"scroll".equals(overflowX) && !"auto".equals(overflowX)) {
                int deficitW = usedW - actualInnerW;
                boolean[] frozen = new boolean[flowChildren.size()];
                int loopCount = 0;
                while (deficitW > 0 && loopCount < 10) {
                    float currentTotalShrinkW = 0;
                    for (int i = 0; i < flowChildren.size(); i++) {
                        if (!frozen[i]) currentTotalShrinkW += getFloatProp(flowChildren.get(i).style, "flex-shrink", 1f) * flowChildren.get(i).w;
                    }
                    if (currentTotalShrinkW <= 0) break;

                    int remainingDeficit = 0;
                    for (int i = 0; i < flowChildren.size(); i++) {
                        if (frozen[i]) continue;
                        LayoutBox child = flowChildren.get(i);
                        float shrink = getFloatProp(child.style, "flex-shrink", 1f);
                        if (shrink > 0) {
                            int shrinkAmount = (int) (deficitW * ((shrink * child.w) / currentTotalShrinkW));
                            int minW = child.getMinContentWidth(ctx);
                            if (child.w - shrinkAmount <= minW) {
                                remainingDeficit += shrinkAmount - (child.w - minW);
                                child.w = minW;
                                frozen[i] = true;
                            } else {
                                child.w -= shrinkAmount;
                            }
                        }
                    }
                    deficitW = remainingDeficit;
                    loopCount++;
                }
                for (LayoutBox child : flowChildren) {
                    child.sizingMode = SizingMode.DEFINITE;
                    child.resolveFlexibleHeights(parentH, new FormattingContext(dir, align, false), ctx);
                }
            }
        } else if (isFlexColumn && "nowrap".equals(flexWrap) && totalGrowH > 0 && actualInnerH > usedH) {
            int extraH = Math.max(0, actualInnerH - usedH);
            for (LayoutBox child : flowChildren) {
                float grow = getFloatProp(child.style, "flex-grow", 0f);
                if (grow > 0) {
                    child.h += (int) (extraH * (grow / totalGrowH));
                    child.resolveFlexibleHeights(parentH, new FormattingContext(dir, align, false), ctx);
                }
            }
        }

        for (LayoutBox child : box.children) {
            if (child.isAbsolute && !isDisplayNone(child.style)) {
                child.measure(actualInnerW, actualInnerH, new FormattingContext("block", "stretch", false), ctx);
            }
        }
    }

    @Override
    public void arrangeChildren(LayoutBox box, int innerX, int innerY, LayoutContext ctx) {
        String flexDir = box.style.getOrDefault("flex-direction", "row");
        String flexWrap = box.style.getOrDefault("flex-wrap", "nowrap");
        String justify = box.style.getOrDefault("justify-content", "flex-start");
        String align = box.style.getOrDefault("align-items", "stretch");
        List<LayoutBox> flowChildren = box.getFlowChildren();

        if (flexDir.startsWith("row") && "wrap".equals(flexWrap)) {
            List<List<LayoutBox>> lines = new ArrayList<>();
            List<LayoutBox> currentLine = new ArrayList<>();
            int currentLineW = 0;
            for (LayoutBox child : flowChildren) {
                int childW = child.w + child.mL + child.mR;
                if (currentLineW + childW > (box.w - box.horizontalDecoration()) && !currentLine.isEmpty()) {
                    lines.add(currentLine);
                    currentLine = new ArrayList<>();
                    currentLineW = 0;
                }
                currentLine.add(child);
                currentLineW += childW + box.columnGap;
            }
            if (!currentLine.isEmpty()) lines.add(currentLine);

            int lineY = innerY;
            for (List<LayoutBox> line : lines) {
                int lineH = 0, lineW = 0;
                for (LayoutBox child : line) {
                    lineH = Math.max(lineH, child.h + child.mT + child.mB);
                    lineW += child.w + child.mL + child.mR;
                }
                int activeCount = line.size(), dynamicGap = box.columnGap, startX = innerX;
                int totalLineContentW = lineW + box.columnGap * Math.max(0, activeCount - 1);
                float containerInnerW = Math.max(totalLineContentW, (box.w - box.horizontalDecoration()));

                if ("space-between".equals(justify) && activeCount > 1) {
                    dynamicGap = (int) (Math.max(0, containerInnerW - lineW) / (activeCount - 1));
                } else if ("space-around".equals(justify) && activeCount > 0) {
                    int space = (int) Math.max(0, containerInnerW - lineW);
                    dynamicGap = space / activeCount;
                    startX += dynamicGap / 2;
                } else if ("space-evenly".equals(justify) && activeCount > 0) {
                    int space = (int) Math.max(0, containerInnerW - lineW);
                    dynamicGap = space / (activeCount + 1);
                    startX += dynamicGap;
                } else if ("center".equals(justify)) {
                    startX = (int) (startX + Math.max(0, containerInnerW - totalLineContentW) / 2);
                } else if ("flex-end".equals(justify) || "end".equals(justify)) {
                    startX = (int) (startX + Math.max(0, containerInnerW - totalLineContentW));
                }

                for (LayoutBox child : line) {
                    String effAlign = child.style.getOrDefault("align-self", align);
                    int itemY = lineY;
                    if ("center".equals(effAlign)) itemY += Math.max(0, lineH - (child.h + child.mT + child.mB)) / 2;
                    else if ("flex-end".equals(effAlign) || "end".equals(effAlign)) itemY += Math.max(0, lineH - (child.h + child.mT + child.mB));
                    else if ("stretch".equals(effAlign) && !child.style.containsKey("height"))
                        child.h = Math.max(child.h, lineH - child.mT - child.mB);
                    child.layout(startX, itemY, child.w, child.h, ctx);
                    startX += child.w + child.mL + child.mR + dynamicGap;
                }
                lineY += lineH + box.rowGap;
            }
        } else if (flexDir.startsWith("column") && "wrap".equals(flexWrap)) {
            int maxColH = Math.max(1, box.h - box.verticalDecoration());
            int colX = innerX, curY = innerY, colMaxW = 0;
            for (LayoutBox child : flowChildren) {
                int childOuterW = child.w + child.mL + child.mR;
                int childOuterH = child.h + child.mT + child.mB;
                if (curY > innerY && (curY - innerY) + childOuterH > maxColH) {
                    colX += colMaxW + box.columnGap;
                    curY = innerY;
                    colMaxW = 0;
                }
                child.layout(colX, curY, child.w, child.h, ctx);
                curY += childOuterH + box.rowGap;
                colMaxW = Math.max(colMaxW, childOuterW);
            }
        } else {
            boolean isRow = flexDir.startsWith("row");
            int mainSpace = isRow ? (box.w - box.horizontalDecoration()) : (box.h - box.verticalDecoration());
            int mainTotalSize = 0, activeCount = flowChildren.size();
            for (LayoutBox child : flowChildren)
                mainTotalSize += isRow ? (child.w + child.mL + child.mR) : (child.h + child.mT + child.mB);
            int mainGap = isRow ? box.columnGap : box.rowGap;
            int currentMain = isRow ? innerX : innerY, dynamicGap = mainGap;

            int totalMainSpaceWithGap = mainTotalSize + mainGap * Math.max(0, activeCount - 1);
            int containerMainSpace = Math.max(totalMainSpaceWithGap, mainSpace);

            if ("space-between".equals(justify) && activeCount > 1) {
                dynamicGap = Math.max(0, containerMainSpace - mainTotalSize) / (activeCount - 1);
            } else if ("space-around".equals(justify) && activeCount > 0) {
                int space = Math.max(0, containerMainSpace - mainTotalSize);
                dynamicGap = space / activeCount;
                currentMain += dynamicGap / 2;
            } else if ("space-evenly".equals(justify) && activeCount > 0) {
                int space = Math.max(0, containerMainSpace - mainTotalSize);
                dynamicGap = space / (activeCount + 1);
                currentMain += dynamicGap;
            } else if ("center".equals(justify)) {
                currentMain += Math.max(0, containerMainSpace - totalMainSpaceWithGap) / 2;
            } else if ("flex-end".equals(justify) || "end".equals(justify)) {
                currentMain += Math.max(0, containerMainSpace - totalMainSpaceWithGap);
            }

            for (LayoutBox child : flowChildren) {
                String effAlign = child.style.getOrDefault("align-self", align);
                int crossSpace = isRow ? (box.h - box.verticalDecoration()) : (box.w - box.horizontalDecoration());
                int crossStart = isRow ? innerY : innerX;
                int childCrossSize = isRow ? (child.h + child.mT + child.mB) : (child.w + child.mL + child.mR);

                switch (effAlign) {
                    case "center" -> crossStart += Math.max(0, crossSpace - childCrossSize) / 2;
                    case "flex-end", "end" -> crossStart += Math.max(0, crossSpace - childCrossSize);
                    case "stretch" -> {
                        if (isRow && !child.style.containsKey("height"))
                            child.h = Math.max(child.h, crossSpace - child.mT - child.mB);
                        else if (!isRow && !child.style.containsKey("width"))
                            child.w = Math.max(child.w, crossSpace - child.mL - child.mR);
                    }
                }
                int childX = isRow ? currentMain : crossStart, childY = isRow ? crossStart : currentMain;
                child.layout(childX, childY, child.w, child.h, ctx);
                currentMain += (isRow ? child.w + child.mL + child.mR : child.h + child.mT + child.mB) + dynamicGap;
            }
        }
    }
}