package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DirtyRectTest {

    @Test
    void startsEmptyAndGrowsToTheBoundingBox() {
        DirtyRect d = new DirtyRect();
        assertTrue(d.isEmpty());
        assertNull(d.bounds());
        d.add(10, 20, 30, 40);
        assertArrayEquals(new int[]{10, 20, 30, 40}, d.bounds());
        d.add(5, 25, 12, 60);
        assertArrayEquals(new int[]{5, 20, 30, 60}, d.bounds());
        d.reset();
        assertTrue(d.isEmpty());
        assertNull(d.bounds());
    }

    @Test
    void zeroAreaRangesStayEmptyLikeBefore() {
        DirtyRect d = new DirtyRect();
        d.add(4, 4, 4, 9); // 幅 0
        assertTrue(d.isEmpty());
        assertNull(d.bounds());
    }

    @Test
    void saveAndRestoreRoundTripsEvenWhenEmpty() {
        DirtyRect d = new DirtyRect();
        int[] empty = d.toArray();
        d.add(1, 2, 3, 4);
        int[] saved = d.toArray();
        d.reset();
        d.add(50, 50, 60, 60);
        d.set(saved);
        assertArrayEquals(new int[]{1, 2, 3, 4}, d.bounds());
        d.set(empty);
        assertTrue(d.isEmpty());
        d.add(7, 7, 8, 8); // 空へ戻した後の追加が前の値に引きずられない
        assertArrayEquals(new int[]{7, 7, 8, 8}, d.bounds());
    }

    @Test
    void matchesAHandWrittenFourIntBoundingBoxOnRandomRanges() {
        Random rnd = new Random(7);
        for (int round = 0; round < 200; round++) {
            DirtyRect d = new DirtyRect();
            int mx0 = Integer.MAX_VALUE, my0 = Integer.MAX_VALUE, mx1 = Integer.MIN_VALUE, my1 = Integer.MIN_VALUE;
            for (int i = 0, n = 1 + rnd.nextInt(8); i < n; i++) {
                int x0 = rnd.nextInt(500), y0 = rnd.nextInt(500);
                int x1 = x0 + 1 + rnd.nextInt(100), y1 = y0 + 1 + rnd.nextInt(100);
                d.add(x0, y0, x1, y1);
                mx0 = Math.min(mx0, x0); my0 = Math.min(my0, y0);
                mx1 = Math.max(mx1, x1); my1 = Math.max(my1, y1);
            }
            assertArrayEquals(new int[]{mx0, my0, mx1, my1}, d.bounds());
        }
    }

    @Test
    void backendKeepsNoLooseDirtyScalars() throws Exception {
        String src = Files.readString(Path.of("core/src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java"));
        assertFalse(src.contains("dirtyX0"), "汚れ範囲は DirtyRect 1 つに集約済み（§9.5.18）");
        assertFalse(src.contains("dirtyY1"));
    }
}
