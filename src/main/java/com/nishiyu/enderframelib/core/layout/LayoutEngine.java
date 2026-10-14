package com.nishiyu.enderframelib.core.layout;

public interface LayoutEngine {
    void computeSize(LayoutBox box, int tentativeInnerW, int parentH, boolean isParentFlexRow, boolean isParentFlexColumn, String parentAlignItems, boolean isParentShrink, int rootW, int rootH);
    void computeHeightAndFinalizeChildren(LayoutBox box, int parentH, boolean isParentFlexRow, boolean isParentFlexColumn, int rootW, int rootH);
    void layoutChildren(LayoutBox box, int innerX, int innerY, int rootW, int rootH);
}