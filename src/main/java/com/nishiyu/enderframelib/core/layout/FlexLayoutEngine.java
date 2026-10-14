package com.nishiyu.enderframelib.core.layout;

import java.util.ArrayList;
import java.util.List;

public class FlexLayoutEngine implements LayoutEngine {
    @Override
    public void computeSize(LayoutBox box, int tentativeInnerW, int parentH, boolean isParentFlexRow, boolean isParentFlexColumn, String parentAlignItems, boolean isParentShrink, int rootW, int rootH) {
        boolean isFlexRow = box.style.getOrDefault("flex-direction", "row").equals("row");
        boolean isFlexColumn = box.style.getOrDefault("flex-direction", "row").equals("column");
        String alignItems = box.style.getOrDefault("align-items", "stretch");

        int maxChildW = 0, sumChildW = 0, activeChildren = 0;
        for (LayoutBox child : box.children) {
            if (child.isAbsolute || child.style.getOrDefault("display", "block").equals("none")) continue;
            child.computeSize(tentativeInnerW, parentH, isFlexRow, isFlexColumn, alignItems, box.w == -1, rootW, rootH);
            maxChildW = Math.max(maxChildW, child.w + child.mL + child.mR);
            sumChildW += child.w + child.mL + child.mR;
            activeChildren++;
        }

        if (box.w == -1) {
            if (isFlexRow) box.w = sumChildW + (activeChildren > 1 ? box.gap * (activeChildren - 1) : 0) + box.horizontalDecoration();
            else box.w = maxChildW + box.horizontalDecoration();
        }
    }

    @Override
    public void computeHeightAndFinalizeChildren(LayoutBox box, int parentH, boolean isParentFlexRow, boolean isParentFlexColumn, int rootW, int rootH) {
        boolean isFlexRow = box.style.getOrDefault("flex-direction", "row").equals("row");
        boolean isFlexColumn = box.style.getOrDefault("flex-direction", "row").equals("column");
        String flexWrap = box.style.getOrDefault("flex-wrap", "nowrap");

        int actualInnerW = Math.max(0, box.w - box.horizontalDecoration());

        if (!box.style.containsKey("height") || box.style.get("height").equals("auto")) {
            int contentH = 0;
            if (isFlexRow && flexWrap.equals("wrap")) {
                int currentLineW = 0, currentLineH = 0;
                for (LayoutBox child : box.getFlowChildren()) {
                    int childOuterW = child.w + child.mL + child.mR, childOuterH = child.h + child.mT + child.mB;
                    if (currentLineW + childOuterW > actualInnerW && currentLineW > 0) {
                        contentH += currentLineH + box.gap;
                        currentLineW = 0;
                        currentLineH = 0;
                    }
                    currentLineW += childOuterW + box.gap;
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
                if (box.getFlowChildren().size() > 1) contentH += box.gap * (box.getFlowChildren().size() - 1);
            }
            box.h = Math.max(box.h, contentH + box.verticalDecoration());
        }

        int actualInnerH = Math.max(0, box.h - box.verticalDecoration());
        String align = box.style.getOrDefault("align-items", "stretch");

        if (align.equals("stretch") && flexWrap.equals("nowrap")) {
            for (LayoutBox child : box.getFlowChildren()) {
                boolean stretched = false;
                String oldProp = null;
                if (isFlexRow && !child.style.containsKey("height")) {
                    int targetH = actualInnerH - child.mT - child.mB;
                    if (child.h < targetH) {
                        child.h = Math.max(0, targetH);
                        stretched = true;
                        oldProp = child.style.get("height");
                        int forcedH = child.h - (child.style.getOrDefault("box-sizing", "border-box").equals("content-box") ? child.verticalDecoration() : 0);
                        child.style.put("height", Math.max(0, forcedH) + "px");
                    }
                } else if (isFlexColumn && !child.style.containsKey("width")) {
                    int targetW = actualInnerW - child.mL - child.mR;
                    if (child.w < targetW) {
                        child.w = Math.max(0, targetW);
                        stretched = true;
                        oldProp = child.style.get("width");
                        int forcedW = child.w - (child.style.getOrDefault("box-sizing", "border-box").equals("content-box") ? child.horizontalDecoration() : 0);
                        child.style.put("width", Math.max(0, forcedW) + "px");
                    }
                }
                if (stretched) {
                    child.computeSize(child.w, child.h, false, false, "stretch", false, rootW, rootH);
                    if (isFlexRow) { if (oldProp != null) child.style.put("height", oldProp); else child.style.remove("height"); }
                    else { if (oldProp != null) child.style.put("width", oldProp); else child.style.remove("width"); }
                }
            }
        }

        float totalGrowW = 0, totalGrowH = 0;
        int usedW = 0, usedH = 0;
        List<LayoutBox> flowChildren = box.getFlowChildren();
        for (LayoutBox child : flowChildren) {
            if (isFlexRow) {
                usedW += child.w + child.mL + child.mR;
                if (child.style.containsKey("flex-grow")) totalGrowW += Float.parseFloat(child.style.get("flex-grow"));
            } else if (isFlexColumn) {
                usedH += child.h + child.mT + child.mB;
                if (child.style.containsKey("flex-grow")) totalGrowH += Float.parseFloat(child.style.get("flex-grow"));
            }
        }
        if (flowChildren.size() > 1) {
            if (isFlexRow) usedW += box.gap * (flowChildren.size() - 1);
            if (isFlexColumn) usedH += box.gap * (flowChildren.size() - 1);
        }

        if (isFlexRow && flexWrap.equals("nowrap") && totalGrowW > 0 && actualInnerW > usedW) {
            int extraW = Math.max(0, actualInnerW - usedW);
            for (LayoutBox child : flowChildren) {
                if (child.style.containsKey("flex-grow")) {
                    float grow = Float.parseFloat(child.style.get("flex-grow"));
                    child.w += (int) (extraW * (grow / totalGrowW));
                    String oldW = child.style.get("width");
                    int forcedW = child.w - (child.style.getOrDefault("box-sizing", "border-box").equals("content-box") ? child.horizontalDecoration() : 0);
                    child.style.put("width", Math.max(0, forcedW) + "px");
                    child.computeSize(child.w, child.h, false, false, "stretch", false, rootW, rootH);
                    if (oldW != null) child.style.put("width", oldW); else child.style.remove("width");
                }
            }
        } else if (isFlexRow && flexWrap.equals("nowrap") && actualInnerW < usedW) {
            String overflowX = box.style.getOrDefault("overflow-x", box.style.getOrDefault("overflow", "visible"));
            if (!overflowX.equals("scroll") && !overflowX.equals("auto")) {
                int deficitW = usedW - actualInnerW;
                boolean[] frozen = new boolean[flowChildren.size()];
                int loopCount = 0;
                while (deficitW > 0 && loopCount < 10) {
                    float currentTotalShrinkW = 0;
                    for (int i = 0; i < flowChildren.size(); i++) {
                        if (!frozen[i]) currentTotalShrinkW += Float.parseFloat(flowChildren.get(i).style.getOrDefault("flex-shrink", "1")) * flowChildren.get(i).w;
                    }
                    if (currentTotalShrinkW <= 0) break;

                    int remainingDeficit = 0;
                    for (int i = 0; i < flowChildren.size(); i++) {
                        if (frozen[i]) continue;
                        LayoutBox child = flowChildren.get(i);
                        float shrink = Float.parseFloat(child.style.getOrDefault("flex-shrink", "1"));
                        if (shrink > 0) {
                            int shrinkAmount = (int) (deficitW * ((shrink * child.w) / currentTotalShrinkW));
                            int minW = child.getMinContentWidth();
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
                    String oldW = child.style.get("width");
                    int forcedW = child.w - (child.style.getOrDefault("box-sizing", "border-box").equals("content-box") ? child.horizontalDecoration() : 0);
                    child.style.put("width", Math.max(0, forcedW) + "px");
                    child.computeSize(child.w, -1, false, false, "stretch", false, rootW, rootH);
                    if (oldW != null) child.style.put("width", oldW); else child.style.remove("width");
                }
            }
        } else if (isFlexColumn && flexWrap.equals("nowrap") && totalGrowH > 0 && actualInnerH > usedH) {
            int extraH = Math.max(0, actualInnerH - usedH);
            for (LayoutBox child : flowChildren) {
                if (child.style.containsKey("flex-grow")) {
                    float grow = Float.parseFloat(child.style.get("flex-grow"));
                    child.h += (int) (extraH * (grow / totalGrowH));
                    String oldH = child.style.get("height");
                    int forcedH = child.h - (child.style.getOrDefault("box-sizing", "border-box").equals("content-box") ? child.verticalDecoration() : 0);
                    child.style.put("height", Math.max(0, forcedH) + "px");
                    child.computeSize(child.w, child.h, false, false, "stretch", false, rootW, rootH);
                    if (oldH != null) child.style.put("height", oldH); else child.style.remove("height");
                }
            }
        } else if (isFlexColumn && flexWrap.equals("nowrap") && actualInnerH < usedH) {
            String overflowY = box.style.getOrDefault("overflow-y", box.style.getOrDefault("overflow", "visible"));
            if (!overflowY.equals("scroll") && !overflowY.equals("auto")) {
                int deficitH = usedH - actualInnerH;
                boolean[] frozen = new boolean[flowChildren.size()];
                int loopCount = 0;
                while (deficitH > 0 && loopCount < 10) {
                    float currentTotalShrinkH = 0;
                    for (int i = 0; i < flowChildren.size(); i++) {
                        if (!frozen[i]) currentTotalShrinkH += Float.parseFloat(flowChildren.get(i).style.getOrDefault("flex-shrink", "1")) * flowChildren.get(i).h;
                    }
                    if (currentTotalShrinkH <= 0) break;

                    int remainingDeficit = 0;
                    for (int i = 0; i < flowChildren.size(); i++) {
                        if (frozen[i]) continue;
                        LayoutBox child = flowChildren.get(i);
                        float shrink = Float.parseFloat(child.style.getOrDefault("flex-shrink", "1"));
                        if (shrink > 0) {
                            int shrinkAmount = (int) (deficitH * ((shrink * child.h) / currentTotalShrinkH));
                            int minH = 24;
                            if (child.h - shrinkAmount <= minH) {
                                remainingDeficit += shrinkAmount - (child.h - minH);
                                child.h = minH;
                                frozen[i] = true;
                            } else {
                                child.h -= shrinkAmount;
                            }
                        }
                    }
                    deficitH = remainingDeficit;
                    loopCount++;
                }
                for (LayoutBox child : flowChildren) {
                    String oldH = child.style.get("height");
                    int forcedH = child.h - (child.style.getOrDefault("box-sizing", "border-box").equals("content-box") ? child.verticalDecoration() : 0);
                    child.style.put("height", Math.max(0, forcedH) + "px");
                    child.computeSize(child.w, child.h, false, false, "stretch", false, rootW, rootH);
                    if (oldH != null) child.style.put("height", oldH); else child.style.remove("height");
                }
            }
        }

        for (LayoutBox child : box.children) {
            if (child.isAbsolute && !child.style.getOrDefault("display", "block").equals("none")) {
                child.computeSize(actualInnerW, actualInnerH, false, false, "stretch", false, rootW, rootH);
            }
        }
    }

    @Override
    public void layoutChildren(LayoutBox box, int innerX, int innerY, int rootW, int rootH) {
        String flexDir = box.style.getOrDefault("flex-direction", "row");
        String flexWrap = box.style.getOrDefault("flex-wrap", "nowrap");
        String justify = box.style.getOrDefault("justify-content", "flex-start");
        String align = box.style.getOrDefault("align-items", "stretch");
        List<LayoutBox> flowChildren = box.getFlowChildren();

        if (flexDir.equals("row") && flexWrap.equals("wrap")) {
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
                currentLineW += childW + box.gap;
            }
            if (!currentLine.isEmpty()) lines.add(currentLine);

            int lineY = innerY;
            for (List<LayoutBox> line : lines) {
                int lineH = 0, lineW = 0;
                for (LayoutBox child : line) {
                    lineH = Math.max(lineH, child.h + child.mT + child.mB);
                    lineW += child.w + child.mL + child.mR;
                }
                int activeCount = line.size(), dynamicGap = box.gap, startX = innerX;
                int totalLineContentW = lineW + box.gap * Math.max(0, activeCount - 1);
                float containerInnerW = Math.max(totalLineContentW, (box.w - box.horizontalDecoration()));

                if (justify.equals("space-between") && activeCount > 1)
                    dynamicGap = (int) (Math.max(0, containerInnerW - lineW) / (activeCount - 1));
                else if (justify.equals("center"))
                    startX = (int) (startX + Math.max(0, containerInnerW - totalLineContentW) / 2);
                else if (justify.equals("flex-end"))
                    startX = (int) (startX + Math.max(0, containerInnerW - totalLineContentW));

                for (LayoutBox child : line) {
                    int itemY = lineY;
                    if (align.equals("center")) itemY += Math.max(0, lineH - (child.h + child.mT + child.mB)) / 2;
                    else if (align.equals("flex-end")) itemY += Math.max(0, lineH - (child.h + child.mT + child.mB));
                    else if (align.equals("stretch") && !child.style.containsKey("height"))
                        child.h = Math.max(child.h, lineH - child.mT - child.mB);
                    child.layout(startX, itemY, child.w, child.h, rootW, rootH);
                    startX += child.w + child.mL + child.mR + dynamicGap;
                }
                lineY += lineH + box.gap;
            }
        } else {
            boolean isRow = flexDir.equals("row");
            int mainSpace = isRow ? (box.w - box.horizontalDecoration()) : (box.h - box.verticalDecoration());
            int mainTotalSize = 0, activeCount = flowChildren.size();
            for (LayoutBox child : flowChildren)
                mainTotalSize += isRow ? (child.w + child.mL + child.mR) : (child.h + child.mT + child.mB);
            int currentMain = isRow ? innerX : innerY, dynamicGap = box.gap;

            int totalMainSpaceWithGap = mainTotalSize + box.gap * Math.max(0, activeCount - 1);
            int containerMainSpace = Math.max(totalMainSpaceWithGap, mainSpace);

            if (justify.equals("space-between") && activeCount > 1)
                dynamicGap = Math.max(0, containerMainSpace - mainTotalSize) / (activeCount - 1);
            else if (justify.equals("center"))
                currentMain += Math.max(0, containerMainSpace - totalMainSpaceWithGap) / 2;
            else if (justify.equals("flex-end"))
                currentMain += Math.max(0, containerMainSpace - totalMainSpaceWithGap);

            for (LayoutBox child : flowChildren) {
                int crossSpace = isRow ? (box.h - box.verticalDecoration()) : (box.w - box.horizontalDecoration());
                int crossStart = isRow ? innerY : innerX;
                int childCrossSize = isRow ? (child.h + child.mT + child.mB) : (child.w + child.mL + child.mR);

                switch (align) {
                    case "center" -> crossStart += Math.max(0, crossSpace - childCrossSize) / 2;
                    case "flex-end" -> crossStart += Math.max(0, crossSpace - childCrossSize);
                    case "stretch" -> {
                        if (isRow && !child.style.containsKey("height"))
                            child.h = Math.max(child.h, crossSpace - child.mT - child.mB);
                        else if (!isRow && !child.style.containsKey("width"))
                            child.w = Math.max(child.w, crossSpace - child.mL - child.mR);
                    }
                }
                int childX = isRow ? currentMain : crossStart, childY = isRow ? crossStart : currentMain;
                child.layout(childX, childY, child.w, child.h, rootW, rootH);
                currentMain += (isRow ? child.w + child.mL + child.mR : child.h + child.mT + child.mB) + dynamicGap;
            }
        }
    }
}