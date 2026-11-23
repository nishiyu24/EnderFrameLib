package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.kernel.layout.FenwickVirtualList;
import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;
import com.nishiyu.enderframelib.core.layout.model.FormattingContext;
import java.util.Arrays;
import java.util.List;

/**
 * {@link FenwickVirtualList} で 10 万行規模の縦ブロックフローを O(log N + K) で維持する状態。
 */
public final class VirtualFlowState implements LayoutEngine.LayoutEngineState {
    final FenwickVirtualList fenwick = new FenwickVirtualList(64);
    LayoutBox owner;
    float[] gapBefore = new float[0];
    float[] slotHeights = new float[0];
    float[] xPos = new float[0];
    ConstraintSpace childMeasureSpace;
    float innerW;
    float childPercentBase;
    float trailingMargin;
    float height;
    int placedStart = -1;
    int placedEnd = -1;
    int[] extraPlaced = new int[4];
    int extraPlacedCount = 0;
    int[] forceRelevantIndices = new int[4];
    int forceRelevantCount = 0;

    void prepare(int n, float innerW, float childPercentBase, LayoutBox owner, FormattingContext childFmt) {
        this.owner = owner;
        this.innerW = innerW;
        this.childPercentBase = childPercentBase;
        this.childMeasureSpace = ConstraintSpace.reuseOrCreate(this.childMeasureSpace, innerW, childPercentBase, childFmt);
        if (gapBefore.length < n) {
            gapBefore = new float[n];
            slotHeights = new float[n];
            xPos = new float[n];
        }
        this.placedStart = -1;
        this.placedEnd = -1;
        this.extraPlacedCount = 0;
        this.forceRelevantCount = 0;
    }

    void addForceRelevant(int idx) {
        if (forceRelevantCount == forceRelevantIndices.length) {
            forceRelevantIndices = Arrays.copyOf(forceRelevantIndices, forceRelevantIndices.length * 2);
        }
        forceRelevantIndices[forceRelevantCount++] = idx;
    }

    void addExtraPlaced(int idx) {
        if (extraPlacedCount == extraPlaced.length) {
            extraPlaced = Arrays.copyOf(extraPlaced, extraPlaced.length * 2);
        }
        extraPlaced[extraPlacedCount++] = idx;
    }

    boolean canResetSparse() {
        return true;
    }

    void resetPlacedChildren(long keepRun, boolean nextOffset, boolean nextBfcFloat, List<LayoutBox> staleFrags) {
        if (owner == null) return;
        List<LayoutBox> kids = owner.children;
        int n = kids.size();
        if (placedStart >= 0 && placedEnd >= placedStart) {
            int end = Math.min(placedEnd, n - 1);
            for (int i = placedStart; i <= end; i++) {
                PlacementMemo.resetPlacementChildFast(kids.get(i), keepRun, nextOffset, nextBfcFloat, staleFrags);
            }
        }
        for (int k = 0; k < extraPlacedCount; k++) {
            int idx = extraPlaced[k];
            if (idx >= 0 && idx < n && (idx < placedStart || idx > placedEnd)) {
                PlacementMemo.resetPlacementChildFast(kids.get(idx), keepRun, nextOffset, nextBfcFloat, staleFrags);
            }
        }
    }

    public float contentExtentBottom(boolean marginEscapes) {
        return marginEscapes ? fenwick.totalHeight() : (fenwick.totalHeight() + trailingMargin);
    }

    public int placedRangeStart() { return placedStart; }
    public int placedRangeEnd() { return placedEnd; }
    public int extraPlacedCount() { return extraPlacedCount; }
    public int extraPlacedAt(int k) { return extraPlaced[k]; }

    public int placedCount() {
        int range = (placedStart >= 0 && placedEnd >= placedStart) ? (placedEnd - placedStart + 1) : 0;
        return range + extraPlacedCount;
    }

    public LayoutBox placedChildAt(int k) {
        if (owner == null) return null;
        int range = (placedStart >= 0 && placedEnd >= placedStart) ? (placedEnd - placedStart + 1) : 0;
        int idx = k < range ? (placedStart + k) : extraPlaced[k - range];
        List<LayoutBox> kids = owner.children;
        return (idx >= 0 && idx < kids.size()) ? kids.get(idx) : null;
    }

    public float[] estimateChildPosition(LayoutBox parent, LayoutBox child) {
        int idx = child.virtualChildIndex;
        List<LayoutBox> kids = parent.children;
        if (idx < 0 || idx >= kids.size() || kids.get(idx) != child) return null;
        float px = parent.x() + parent.pL + parent.bL + xPos[idx] + child.mL;
        float py = parent.y() + parent.pT + parent.bT + parent.contentOffsetY + parent.legendSpill
                + fenwick.offsetOf(idx) + gapBefore[idx];
        return new float[]{px, py};
    }

    public int rowCount() { return fenwick.size(); }
    public float totalFlowHeight() { return fenwick.totalHeight(); }
}
