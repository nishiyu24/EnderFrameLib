package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.emit.ClipPart;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 同じ空間で別の部品をすっかり含む直角のクリップは除く（GPU が受け取れる部品数を超えて CPU 層へ落ちるのを防ぐ）。 */
class ClipPartMergeTest {
    private static final float[] SQ = new float[4];

    private static ClipPart part(Matrix2D inv, float x0, float y0, float x1, float y1, float r) {
        float[] rr = new float[]{r, r, r, r};
        return new ClipPart(inv, 1f, x0, y0, x1, y1, rr, rr, 0, 0, 100, 100, x0 + r, y0 + r, x1 - r, y1 - r, x0, y0, x1, y1);
    }

    private static final Matrix2D M1 = new Matrix2D(1, 0, 0, 1, -10, -10);
    private static final Matrix2D M2 = new Matrix2D(1, 0, 0, 1, -20, -20);

    @Test
    void squareClipThatContainsARoundedOneInTheSameSpaceIsDropped() {
        ClipPart square = part(M1, 0, 0, 50, 20, 0);
        ClipPart rounded = part(M1, 0, 0, 50, 20, 4);
        assertEquals(List.of(rounded), SoftwareRenderBackend.dropRedundantSquareParts(List.of(square, rounded)));
        assertEquals(List.of(rounded), SoftwareRenderBackend.dropRedundantSquareParts(List.of(rounded, square)));
    }

    @Test
    void largerSquareContainingASmallerSquareIsDropped() {
        ClipPart big = part(M1, 0, 0, 100, 100, 0);
        ClipPart small = part(M1, 10, 10, 50, 50, 0);
        assertEquals(List.of(small), SoftwareRenderBackend.dropRedundantSquareParts(List.of(big, small)));
    }

    @Test
    void identicalSquaresKeepExactlyOne() {
        ClipPart a = part(M1, 0, 0, 50, 20, 0);
        ClipPart b = part(M1, 0, 0, 50, 20, 0);
        assertEquals(1, SoftwareRenderBackend.dropRedundantSquareParts(List.of(a, b)).size());
    }

    @Test
    void differentSpacesRoundedAndNonContainedPartsAreKept() {
        ClipPart a = part(M1, 0, 0, 50, 20, 0);
        ClipPart otherSpace = part(M2, 0, 0, 50, 20, 4);
        assertEquals(2, SoftwareRenderBackend.dropRedundantSquareParts(List.of(a, otherSpace)).size(), "別の空間");
        ClipPart roundedBig = part(M1, 0, 0, 100, 100, 6);
        ClipPart squareSmall = part(M1, 10, 10, 50, 50, 0);
        assertEquals(2, SoftwareRenderBackend.dropRedundantSquareParts(List.of(roundedBig, squareSmall)).size(), "丸角は含む側でも除かない");
        ClipPart sideBySide = part(M1, 40, 0, 90, 20, 4);
        assertEquals(2, SoftwareRenderBackend.dropRedundantSquareParts(List.of(a, sideBySide)).size(), "含まれていない");
    }
}
