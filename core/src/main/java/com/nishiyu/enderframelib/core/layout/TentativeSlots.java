package com.nishiyu.enderframelib.core.layout;

import com.nishiyu.enderframelib.core.layout.model.ConstraintSpace;

import java.util.Arrays;

/**
 * 仮測定（{@link Tentative.Kind#TENTATIVE}）および確定葉（{@link Tentative.Kind#DEFINITE}）の
 * 複数枠と仮適用中の一時退避状態（{@link MeasureMemo} から切り出した補助表現）。
 */
final class TentativeSlots {
    private static final int MAX_TENTATIVES = 4;
    private static final int MAX_TOTAL_TENTATIVES = MAX_TENTATIVES + 2;

    Tentative[] entries;
    ConstraintSpace stashedSpace;
    LayoutContext stashedCtx;
    boolean stashed;
    private float stW, stH, stMT, stMR, stMB, stML;
    private float stPT, stPR, stPB, stPL, stBT, stBR, stBB, stBL;
    private float stLegendSpill, stContentBlockExtent, stOwnMarginTop, stOwnMarginBottom;
    private LayoutBox.SizingMode stSizingMode;

    TentativeSlots copy() {
        TentativeSlots c = new TentativeSlots();
        c.entries = entries == null ? null : entries.clone();
        c.stashedSpace = stashedSpace;
        c.stashedCtx = stashedCtx;
        c.stashed = stashed;
        c.stW = stW; c.stH = stH; c.stMT = stMT; c.stMR = stMR; c.stMB = stMB; c.stML = stML;
        c.stPT = stPT; c.stPR = stPR; c.stPB = stPB; c.stPL = stPL;
        c.stBT = stBT; c.stBR = stBR; c.stBB = stBB; c.stBL = stBL;
        c.stLegendSpill = stLegendSpill; c.stContentBlockExtent = stContentBlockExtent;
        c.stOwnMarginTop = stOwnMarginTop; c.stOwnMarginBottom = stOwnMarginBottom; c.stSizingMode = stSizingMode;
        return c;
    }

    void captureStash(LayoutBox b) {
        stW = b.w; stH = b.h; stMT = b.mT; stMR = b.mR; stMB = b.mB; stML = b.mL;
        stPT = b.pT; stPR = b.pR; stPB = b.pB; stPL = b.pL;
        stBT = b.bT; stBR = b.bR; stBB = b.bB; stBL = b.bL;
        stLegendSpill = b.legendSpill; stContentBlockExtent = b.contentBlockExtent;
        stOwnMarginTop = b.ownMarginTop; stOwnMarginBottom = b.ownMarginBottom; stSizingMode = b.sizingMode;
    }

    void applyStash(LayoutBox b) {
        b.w = stW; b.h = stH; b.mT = stMT; b.mR = stMR; b.mB = stMB; b.mL = stML;
        b.pT = stPT; b.pR = stPR; b.pB = stPB; b.pL = stPL;
        b.bT = stBT; b.bR = stBR; b.bB = stBB; b.bL = stBL;
        b.legendSpill = stLegendSpill; b.contentBlockExtent = stContentBlockExtent;
        b.ownMarginTop = stOwnMarginTop; b.ownMarginBottom = stOwnMarginBottom; b.sizingMode = stSizingMode;
    }

    int obtainSlotIndex(Tentative.Kind kind, ConstraintSpace space, int maxForKind) {
        if (entries == null) {
            entries = new Tentative[MAX_TENTATIVES];
            return 0;
        }
        int firstNull = -1, countForKind = 0, oldestOfKind = -1;
        for (int i = 0; i < entries.length; i++) {
            Tentative old = entries[i];
            if (old == null) {
                if (firstNull < 0) firstNull = i;
                continue;
            }
            if (old.kind() == kind) {
                if (old.matchesSpaceForEviction(space)) return i;
                if (oldestOfKind < 0) oldestOfKind = i;
                countForKind++;
            }
        }
        if (countForKind < maxForKind) {
            if (firstNull >= 0) return firstNull;
            int nextLen = Math.min(MAX_TOTAL_TENTATIVES, entries.length + 2);
            entries = Arrays.copyOf(entries, nextLen);
            return entries.length - 2;
        }
        int lastNonNull = oldestOfKind;
        for (int i = oldestOfKind + 1; i < entries.length && entries[i] != null; i++) {
            entries[i - 1] = entries[i];
            lastNonNull = i;
        }
        entries[lastNonNull] = null;
        return lastNonNull;
    }
}
