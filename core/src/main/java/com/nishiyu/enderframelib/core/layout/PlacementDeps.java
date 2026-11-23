package com.nishiyu.enderframelib.core.layout;

import java.util.Arrays;

/**
 * K2: 配置の鍵のうち、箱自身の直接入力（原点・親の外寸・自己スクロール・マージン）以外の外部依存のスナップショット。
 *
 * <p>通常の純粋な箱では生成せず（{@code null}）、部分木に外向きの依存（子孫のスクロール・外の包含ブロック・
 * ビューポート・アンカー・float・sticky・表セルの親割り当て寸法）がある箱だけ保持する。再配置時に値が前回と一致すれば
 * 同じインスタンスを再利用し、追加の確保を出さない。</p>
 */
final class PlacementDeps {
    static final byte REF_CB = 0;
    static final byte REF_ANCHOR = 1;
    static final byte REF_STICKY = 2;

    static final int ABSORBABLE_MASK =
            ReadKind.PLACE_CONTAINING_BLOCK.stageBit()
            | ReadKind.PLACE_ANCHOR.stageBit()
            | ReadKind.PLACE_STICKY.stageBit();

    static final int EXTERNAL_MASK =
            ReadKind.PLACE_DESCENDANT_SCROLL.stageBit()
            | ReadKind.PLACE_VIEWPORT.stageBit()
            | ReadKind.PLACE_FLOAT.stageBit()
            | ABSORBABLE_MASK;

    static final int SHIFT_BLOCKERS =
            ReadKind.PLACE_CONTAINING_BLOCK.stageBit()
            | ReadKind.PLACE_ANCHOR.stageBit()
            | ReadKind.PLACE_STICKY.stageBit();

    static int readKindBit(byte refKind) {
        return switch (refKind) {
            case REF_CB -> ReadKind.PLACE_CONTAINING_BLOCK.stageBit();
            case REF_ANCHOR -> ReadKind.PLACE_ANCHOR.stageBit();
            case REF_STICKY -> ReadKind.PLACE_STICKY.stageBit();
            default -> 0;
        };
    }

    final int scrollVersion;
    final float viewportW, viewportH;
    final float ownW, ownH, ownContentOffsetY;
    final float staticX, staticY, staticAreaW, staticAreaH;
    final float parentContentL, parentContentT, parentContentR, parentContentB;
    final byte[] refKinds;
    final LayoutBox[] refReaders;
    final LayoutBox[] refTargets;
    final float[] refCoords; // 6 floats per ref: x, y, w, h, scrollX, scrollY

    private PlacementDeps(int scrollVersion, float viewportW, float viewportH,
                          float ownW, float ownH, float ownContentOffsetY,
                          float staticX, float staticY, float staticAreaW, float staticAreaH,
                          float parentContentL, float parentContentT, float parentContentR, float parentContentB,
                          byte[] refKinds, LayoutBox[] refReaders, LayoutBox[] refTargets, float[] refCoords) {
        this.scrollVersion = scrollVersion;
        this.viewportW = viewportW;
        this.viewportH = viewportH;
        this.ownW = ownW;
        this.ownH = ownH;
        this.ownContentOffsetY = ownContentOffsetY;
        this.staticX = staticX;
        this.staticY = staticY;
        this.staticAreaW = staticAreaW;
        this.staticAreaH = staticAreaH;
        this.parentContentL = parentContentL;
        this.parentContentT = parentContentT;
        this.parentContentR = parentContentR;
        this.parentContentB = parentContentB;
        this.refKinds = refKinds;
        this.refReaders = refReaders;
        this.refTargets = refTargets;
        this.refCoords = refCoords;
    }

    static boolean needsDeps(LayoutBox box, int mask) {
        return (mask & EXTERNAL_MASK) != 0
                || box.isAbsolute
                || box.isSticky
                || (box.display != null && box.display.inner() == LayoutBox.Display.Inner.TABLE_CELL);
    }

    static PlacementDeps captureOrReuse(PlacementDeps prev, LayoutBox box, int mask, LayoutContext ctx,
                                        MeasureReads.ThreadState st, int refStart, int refEnd) {
        if (!needsDeps(box, mask)) return null;
        int sv = (mask & ReadKind.PLACE_DESCENDANT_SCROLL.stageBit()) != 0 ? LayoutReads.scrollVersion() : 0;
        float vw = (mask & ReadKind.PLACE_VIEWPORT.stageBit()) != 0 ? LayoutReads.peekViewportW(ctx) : 0f;
        float vh = (mask & ReadKind.PLACE_VIEWPORT.stageBit()) != 0 ? LayoutReads.peekViewportH(ctx) : 0f;
        boolean tableCell = box.display != null && box.display.inner() == LayoutBox.Display.Inner.TABLE_CELL;
        float ow = tableCell ? box.w : 0f;
        float oh = tableCell ? box.h : 0f;
        float ocy = tableCell ? box.contentOffsetY : 0f;
        float sx = box.isAbsolute ? box.staticX : 0f;
        float sy = box.isAbsolute ? box.staticY : 0f;
        float saw = box.isAbsolute ? box.staticAreaW : 0f;
        float sah = box.isAbsolute ? box.staticAreaH : 0f;
        float pcl = box.isSticky ? LayoutReads.peekParentContentLeft(box) : 0f;
        float pct = box.isSticky ? LayoutReads.peekParentContentTop(box) : 0f;
        float pcr = box.isSticky ? LayoutReads.peekParentContentRight(box) : 0f;
        float pcb = box.isSticky ? LayoutReads.peekParentContentBottom(box) : 0f;

        int refCount = refEnd - refStart;
        if (prev != null && prev.matchesCaptured(sv, vw, vh, ow, oh, ocy, sx, sy, saw, sah,
                pcl, pct, pcr, pcb, st, refStart, refCount)) {
            return prev;
        }
        byte[] kinds = null;
        LayoutBox[] readers = null;
        LayoutBox[] targets = null;
        float[] coords = null;
        if (refCount > 0) {
            kinds = new byte[refCount];
            readers = new LayoutBox[refCount];
            targets = new LayoutBox[refCount];
            coords = new float[refCount * 6];
            for (int i = 0; i < refCount; i++) {
                int idx = refStart + i;
                byte k = st.placeRefKind[idx];
                kinds[i] = k;
                readers[i] = st.placeRefReader[idx];
                LayoutBox t = st.placeRefTarget[idx];
                targets[i] = t;
                int b = i * 6;
                if (k == REF_ANCHOR && t != null) {
                    coords[b] = LayoutReads.peekAnchorX(t);
                    coords[b + 1] = LayoutReads.peekAnchorY(t);
                    coords[b + 2] = LayoutReads.peekAnchorW(t);
                    coords[b + 3] = LayoutReads.peekAnchorH(t);
                } else {
                    coords[b] = st.placeRefX[idx];
                    coords[b + 1] = st.placeRefY[idx];
                    coords[b + 2] = st.placeRefW[idx];
                    coords[b + 3] = st.placeRefH[idx];
                    coords[b + 4] = st.placeRefScrollX[idx];
                    coords[b + 5] = st.placeRefScrollY[idx];
                }
            }
        }
        return new PlacementDeps(sv, vw, vh, ow, oh, ocy, sx, sy, saw, sah, pcl, pct, pcr, pcb,
                kinds, readers, targets, coords);
    }

    private boolean matchesCaptured(int sv, float vw, float vh, float ow, float oh, float ocy,
                                    float sx, float sy, float saw, float sah,
                                    float pcl, float pct, float pcr, float pcb,
                                    MeasureReads.ThreadState st, int refStart, int refCount) {
        if (scrollVersion != sv || vw != viewportW || vh != viewportH
                || ow != ownW || oh != ownH || ocy != ownContentOffsetY
                || bitsNe(staticX, sx) || bitsNe(staticY, sy) || bitsNe(staticAreaW, saw) || bitsNe(staticAreaH, sah)
                || pcl != parentContentL || pct != parentContentT || pcr != parentContentR || pcb != parentContentB) {
            return false;
        }
        int prevLen = refKinds == null ? 0 : refKinds.length;
        if (prevLen != refCount) return false;
        for (int i = 0; i < refCount; i++) {
            int idx = refStart + i;
            byte k = st.placeRefKind[idx];
            if (refKinds[i] != k || refReaders[i] != st.placeRefReader[idx] || refTargets[i] != st.placeRefTarget[idx]) {
                return false;
            }
            int b = i * 6;
            LayoutBox t = refTargets[i];
            if (k == REF_ANCHOR && t != null) {
                if (refCoords[b] != LayoutReads.peekAnchorX(t)
                        || refCoords[b + 1] != LayoutReads.peekAnchorY(t)
                        || refCoords[b + 2] != LayoutReads.peekAnchorW(t)
                        || refCoords[b + 3] != LayoutReads.peekAnchorH(t)) {
                    return false;
                }
            } else {
                if (refCoords[b] != st.placeRefX[idx] || refCoords[b + 1] != st.placeRefY[idx]
                        || refCoords[b + 2] != st.placeRefW[idx] || refCoords[b + 3] != st.placeRefH[idx]
                        || refCoords[b + 4] != st.placeRefScrollX[idx] || refCoords[b + 5] != st.placeRefScrollY[idx]) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean matches(LayoutBox box, int mask, LayoutContext ctx) {
        if ((mask & ReadKind.PLACE_DESCENDANT_SCROLL.stageBit()) != 0
                && scrollVersion != LayoutReads.scrollVersion()) {
            return false;
        }
        if ((mask & ReadKind.PLACE_VIEWPORT.stageBit()) != 0
                && (viewportW != LayoutReads.peekViewportW(ctx) || viewportH != LayoutReads.peekViewportH(ctx))) {
            return false;
        }
        if ((mask & ReadKind.PLACE_FLOAT.stageBit()) != 0 && box.floatSpace != null) {
            return false;
        }
        if (box.display != null && box.display.inner() == LayoutBox.Display.Inner.TABLE_CELL
                && (ownW != box.w || ownH != box.h || ownContentOffsetY != box.contentOffsetY)) {
            return false;
        }
        if (box.isAbsolute && (bitsNe(staticX, box.staticX) || bitsNe(staticY, box.staticY)
                || bitsNe(staticAreaW, box.staticAreaW) || bitsNe(staticAreaH, box.staticAreaH))) {
            return false;
        }
        if (box.isSticky && (parentContentL != LayoutReads.peekParentContentLeft(box)
                || parentContentT != LayoutReads.peekParentContentTop(box)
                || parentContentR != LayoutReads.peekParentContentRight(box)
                || parentContentB != LayoutReads.peekParentContentBottom(box))) {
            return false;
        }
        if (refKinds != null) {
            for (int i = 0; i < refKinds.length; i++) {
                if (!refMatches(i, ctx)) return false;
            }
        }
        return true;
    }

    private boolean refMatches(int i, LayoutContext ctx) {
        byte k = refKinds[i];
        LayoutBox reader = refReaders[i];
        LayoutBox target = refTargets[i];
        int b = i * 6;
        return switch (k) {
            case REF_CB -> LayoutReads.peekContainingBlockMatches(
                    reader, target, ctx,
                    refCoords[b], refCoords[b + 1], refCoords[b + 2], refCoords[b + 3],
                    refCoords[b + 4], refCoords[b + 5]);
            case REF_ANCHOR -> target != null
                    && refCoords[b] == LayoutReads.peekAnchorX(target)
                    && refCoords[b + 1] == LayoutReads.peekAnchorY(target)
                    && refCoords[b + 2] == LayoutReads.peekAnchorW(target)
                    && refCoords[b + 3] == LayoutReads.peekAnchorH(target);
            case REF_STICKY -> LayoutReads.peekStickyMatches(
                    reader, target, ctx,
                    refCoords[b], refCoords[b + 1], refCoords[b + 2], refCoords[b + 3],
                    refCoords[b + 4], refCoords[b + 5]);
            default -> false;
        };
    }

    private static boolean bitsNe(float a, float b) {
        return Float.floatToIntBits(a) != Float.floatToIntBits(b);
    }
}
