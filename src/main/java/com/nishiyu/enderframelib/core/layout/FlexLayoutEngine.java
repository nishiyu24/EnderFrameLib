package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.css.CssProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * flex レイアウト。
 *
 * LIMITATION(制約): {@code order}、{@code row-reverse} / {@code column-reverse} /
 * {@code wrap-reverse}、{@code align-content}、auto margin、{@code align-items: baseline} を
 * 読まない。{@code width} を持つアイテムは {@code flex-shrink} で縮まず、伸長は
 * {@code max-width} で止まらない。{@code min-width} / {@code min-height: auto} は
 * 仕様の自動最小サイズになっていない。W4 で直す。FIDELITY.md の
 * 「W4 — 整形コンテキスト」と README の "Not supported" に対応。
 */
public class FlexLayoutEngine extends AbstractLayoutEngine {

    @Override
    public void measure(LayoutBox box, float tentativeInnerW, float parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        String dir = box.style.getOrDefault(CssProperty.FLEX_DIRECTION, "row");
        boolean isFlexRow = dir.startsWith("row");
        boolean isFlexColumn = dir.startsWith("column");
        String alignItems = box.style.getOrDefault(CssProperty.ALIGN_ITEMS, "stretch");

        float maxChildW = 0, sumChildW = 0, activeChildren = 0;
        for (LayoutBox child : box.children) {
            if (child.isAbsolute || isDisplayNone(child.style)) continue;
            String childAlign = child.style.getOrDefault(CssProperty.ALIGN_SELF, alignItems);

            FormattingContext childCtx = new FormattingContext(dir, childAlign, box.sizingMode == SizingMode.SHRINK_TO_FIT);
            child.measure(tentativeInnerW, parentH, childCtx, ctx);

            if (isFlexRow && child.style.containsKey(CssProperty.FLEX_BASIS) && !"auto".equalsIgnoreCase(child.style.get(CssProperty.FLEX_BASIS).trim())) {
                child.w = resolveFlexBasis(child, true, tentativeInnerW, ctx);
                child.sizingMode = SizingMode.DEFINITE;
            }

            maxChildW = Math.max(maxChildW, child.w + child.mL + child.mR);
            sumChildW += child.w + child.mL + child.mR;
            activeChildren++;
        }

        if (box.sizingMode == SizingMode.SHRINK_TO_FIT) {
            if (isFlexRow) box.w = sumChildW + (activeChildren > 1 ? box.columnGap * (activeChildren - 1) : 0) + box.horizontalDecoration();
            else box.w = maxChildW + box.horizontalDecoration();
        }
    }

    private float resolveFlexBasis(LayoutBox child, boolean isRow, float availableSize, LayoutContext ctx) {
        String basisStr = child.style.get(CssProperty.FLEX_BASIS);
        if (basisStr == null || "auto".equalsIgnoreCase(basisStr.trim())) {
            return isRow ? child.w : child.h;
        }
        float rootW = ctx.viewport().rootW();
        float rootH = ctx.viewport().rootH();
        float basis = child.style.length(CssProperty.FLEX_BASIS, availableSize, rootW, rootH);
        boolean isBorderBox = "border-box".equals(child.style.get(CssProperty.BOX_SIZING));
        if (!isBorderBox) {
            basis += isRow ? child.horizontalDecoration() : child.verticalDecoration();
        }
        return Math.max(0, basis);
    }


    @Override
    public void resolveFlexibleHeights(LayoutBox box, float parentH, FormattingContext fmtCtx, LayoutContext ctx) {
        String dir = box.style.getOrDefault(CssProperty.FLEX_DIRECTION, "row");
        boolean isFlexRow = dir.startsWith("row");
        boolean isFlexColumn = dir.startsWith("column");
        String flexWrap = box.style.getOrDefault(CssProperty.FLEX_WRAP, "nowrap");

        float actualInnerW = Math.max(0, box.w - box.horizontalDecoration());

        if (!box.style.containsKey(CssProperty.HEIGHT) || "auto".equals(box.style.getOrDefault(CssProperty.HEIGHT, "auto"))) {
            float contentH = 0;
            if (isFlexRow && "wrap".equals(flexWrap)) {
                float currentLineW = 0, currentLineH = 0;
                for (LayoutBox child : box.getFlowChildren()) {
                    float childOuterW = child.w + child.mL + child.mR, childOuterH = child.h + child.mT + child.mB;
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

        float actualInnerH = Math.max(0, box.h - box.verticalDecoration());
        String align = box.style.getOrDefault(CssProperty.ALIGN_ITEMS, "stretch");

        // A-5バグ修正: キャッシュの可変マップを直接書き換えず、LayoutBox のフィールドと SizingMode を更新して再計算を促す
        if ("nowrap".equals(flexWrap)) {
            for (LayoutBox child : box.getFlowChildren()) {
                String effAlign = child.style.getOrDefault(CssProperty.ALIGN_SELF, align);
                if (!"stretch".equals(effAlign)) continue;

                if (isFlexRow && !child.style.containsKey(CssProperty.HEIGHT)) {
                    float targetH = actualInnerH - child.mT - child.mB;
                    if (child.h < targetH) {
                        child.h = Math.max(0, targetH);
                        child.resolveFlexibleHeights(parentH, new FormattingContext(dir, effAlign, false), ctx);
                    }
                } else if (isFlexColumn && !child.style.containsKey(CssProperty.WIDTH)) {
                    float targetW = actualInnerW - child.mL - child.mR;
                    if (child.w < targetW) {
                        child.w = Math.max(0, targetW);
                        child.sizingMode = SizingMode.DEFINITE;
                        child.resolveFlexibleHeights(parentH, new FormattingContext(dir, effAlign, false), ctx);
                    }
                }
            }
        }

        float totalGrowW = 0, totalGrowH = 0;
        float usedW = 0, usedH = 0;
        List<LayoutBox> flowChildren = box.getFlowChildren();
        for (LayoutBox child : flowChildren) {
            if (isFlexRow) {
                if (child.style.containsKey(CssProperty.FLEX_BASIS) && !"auto".equalsIgnoreCase(child.style.get(CssProperty.FLEX_BASIS).trim())) {
                    child.w = resolveFlexBasis(child, true, actualInnerW, ctx);
                    child.sizingMode = SizingMode.DEFINITE;
                }
                usedW += child.w + child.mL + child.mR;
                totalGrowW += child.style.number(CssProperty.FLEX_GROW, 0f);
            } else if (isFlexColumn) {
                if (child.style.containsKey(CssProperty.FLEX_BASIS) && !"auto".equalsIgnoreCase(child.style.get(CssProperty.FLEX_BASIS).trim())) {
                    child.h = resolveFlexBasis(child, false, actualInnerH, ctx);
                }
                usedH += child.h + child.mT + child.mB;
                totalGrowH += child.style.number(CssProperty.FLEX_GROW, 0f);
            }
        }

        if (flowChildren.size() > 1) {
            if (isFlexRow) usedW += box.columnGap * (flowChildren.size() - 1);
            if (isFlexColumn) usedH += box.rowGap * (flowChildren.size() - 1);
        }

        if (isFlexRow && "nowrap".equals(flexWrap) && totalGrowW > 0 && actualInnerW > usedW) {
            float extraW = Math.max(0, actualInnerW - usedW);
            for (LayoutBox child : flowChildren) {
                float grow = child.style.number(CssProperty.FLEX_GROW, 0f);
                if (grow > 0) {
                    child.w += (extraW * (grow / totalGrowW));
                    child.sizingMode = SizingMode.DEFINITE;
                    child.resolveFlexibleHeights(parentH, new FormattingContext(dir, align, false), ctx);
                }
            }
        } else if (isFlexRow && "nowrap".equals(flexWrap) && actualInnerW < usedW) {
            String overflowX = box.style.getOrDefault(CssProperty.OVERFLOW_X, box.style.getOrDefault(CssProperty.OVERFLOW, "visible"));
            if (!"scroll".equals(overflowX) && !"auto".equals(overflowX)) {
                float deficitW = usedW - actualInnerW;
                boolean[] frozen = new boolean[flowChildren.size()];
                int loopCount = 0;
                while (deficitW > 0 && loopCount < 10) {
                    float currentTotalShrinkW = 0;
                    for (int i = 0; i < flowChildren.size(); i++) {
                        if (!frozen[i]) currentTotalShrinkW += flowChildren.get(i).style.number(CssProperty.FLEX_SHRINK, 1f) * flowChildren.get(i).w;
                    }
                    if (currentTotalShrinkW <= 0) break;

                    float remainingDeficit = 0;
                    for (int i = 0; i < flowChildren.size(); i++) {
                        if (frozen[i]) continue;
                        LayoutBox child = flowChildren.get(i);
                        float shrink = child.style.number(CssProperty.FLEX_SHRINK, 1f);
                        if (shrink > 0) {
                            float shrinkAmount = (deficitW * ((shrink * child.w) / currentTotalShrinkW));
                            float minW = child.getMinContentWidth(ctx);
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
            float extraH = Math.max(0, actualInnerH - usedH);
            for (LayoutBox child : flowChildren) {
                float grow = child.style.number(CssProperty.FLEX_GROW, 0f);
                if (grow > 0) {
                    child.h += (extraH * (grow / totalGrowH));
                    child.resolveFlexibleHeights(parentH, new FormattingContext(dir, align, false), ctx);
                }
            }
        }

        measureAbsoluteChildren(box, ctx);
    }

    @Override
    public void arrangeChildren(LayoutBox box, float innerX, float innerY, LayoutContext ctx) {
        String flexDir = box.style.getOrDefault(CssProperty.FLEX_DIRECTION, "row");
        String flexWrap = box.style.getOrDefault(CssProperty.FLEX_WRAP, "nowrap");
        String justify = box.style.getOrDefault(CssProperty.JUSTIFY_CONTENT, "flex-start");
        String align = box.style.getOrDefault(CssProperty.ALIGN_ITEMS, "stretch");
        List<LayoutBox> flowChildren = box.getFlowChildren();

        if (flexDir.startsWith("row") && "wrap".equals(flexWrap)) {
            List<List<LayoutBox>> lines = new ArrayList<>();
            List<LayoutBox> currentLine = new ArrayList<>();
            float currentLineW = 0;
            for (LayoutBox child : flowChildren) {
                float childW = child.w + child.mL + child.mR;
                if (currentLineW + childW > (box.w - box.horizontalDecoration()) && !currentLine.isEmpty()) {
                    lines.add(currentLine);
                    currentLine = new ArrayList<>();
                    currentLineW = 0;
                }
                currentLine.add(child);
                currentLineW += childW + box.columnGap;
            }
            if (!currentLine.isEmpty()) lines.add(currentLine);

            float lineY = innerY;
            for (List<LayoutBox> line : lines) {
                float lineH = 0, lineW = 0;
                for (LayoutBox child : line) {
                    lineH = Math.max(lineH, child.h + child.mT + child.mB);
                    lineW += child.w + child.mL + child.mR;
                }
                MainAxisSpacing spacing = distributeMainAxis(justify, innerX,
                        box.w - box.horizontalDecoration(), lineW, box.columnGap, line.size());
                float startX = spacing.start();
                float dynamicGap = spacing.gap();

                for (LayoutBox child : line) {
                    String effAlign = child.style.getOrDefault(CssProperty.ALIGN_SELF, align);
                    float itemY = lineY;
                    if ("center".equals(effAlign)) itemY += Math.max(0, lineH - (child.h + child.mT + child.mB)) / 2;
                    else if ("flex-end".equals(effAlign) || "end".equals(effAlign)) itemY += Math.max(0, lineH - (child.h + child.mT + child.mB));
                    else if ("stretch".equals(effAlign) && !child.style.containsKey(CssProperty.HEIGHT))
                        child.h = Math.max(child.h, lineH - child.mT - child.mB);
                    child.layout(startX, itemY, child.w, child.h, ctx);
                    startX += child.w + child.mL + child.mR + dynamicGap;
                }
                lineY += lineH + box.rowGap;
            }
        } else if (flexDir.startsWith("column") && "wrap".equals(flexWrap)) {
            float maxColH = Math.max(1, box.h - box.verticalDecoration());
            float colX = innerX, curY = innerY, colMaxW = 0;
            for (LayoutBox child : flowChildren) {
                float childOuterW = child.w + child.mL + child.mR;
                float childOuterH = child.h + child.mT + child.mB;
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
            float mainSpace = isRow ? (box.w - box.horizontalDecoration()) : (box.h - box.verticalDecoration());
            float mainTotalSize = 0;
            int activeCount = flowChildren.size();
            for (LayoutBox child : flowChildren)
                mainTotalSize += isRow ? (child.w + child.mL + child.mR) : (child.h + child.mT + child.mB);
            float mainGap = isRow ? box.columnGap : box.rowGap;
            MainAxisSpacing spacing = distributeMainAxis(justify, isRow ? innerX : innerY,
                    mainSpace, mainTotalSize, mainGap, activeCount);
            float currentMain = spacing.start();
            float dynamicGap = spacing.gap();

            for (LayoutBox child : flowChildren) {
                String effAlign = child.style.getOrDefault(CssProperty.ALIGN_SELF, align);
                float crossSpace = isRow ? (box.h - box.verticalDecoration()) : (box.w - box.horizontalDecoration());
                float crossStart = isRow ? innerY : innerX;
                float childCrossSize = isRow ? (child.h + child.mT + child.mB) : (child.w + child.mL + child.mR);

                switch (effAlign) {
                    case "center" -> crossStart += Math.max(0, crossSpace - childCrossSize) / 2;
                    case "flex-end", "end" -> crossStart += Math.max(0, crossSpace - childCrossSize);
                    case "stretch" -> {
                        if (isRow && !child.style.containsKey(CssProperty.HEIGHT))
                            child.h = Math.max(child.h, crossSpace - child.mT - child.mB);
                        else if (!isRow && !child.style.containsKey(CssProperty.WIDTH))
                            child.w = Math.max(child.w, crossSpace - child.mL - child.mR);
                    }
                }
                float childX = isRow ? currentMain : crossStart, childY = isRow ? crossStart : currentMain;
                child.layout(childX, childY, child.w, child.h, ctx);
                currentMain += (isRow ? child.w + child.mL + child.mR : child.h + child.mT + child.mB) + dynamicGap;
            }
        }
    }
}