package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TileQueueTest {

    /** 順序に敏感な命令: 画素 = 画素 * 31 + id（同じ画素を触る命令の順序が違えば結果が変わる）。 */
    private static final class StampOp extends Op {
        int id;
        int runs;

        @Override
        void run(int ax0, int ay0, int ax1, int ay1, Scratch s) {
            for (int y = ay0; y < ay1; y++)
                for (int x = ax0; x < ax1; x++) px[y * w + x] = px[y * w + x] * 31 + id;
        }
    }

    private static StampOp stamp(int[] px, int w, int id, int x0, int y0, int x1, int y1) {
        StampOp o = new StampOp();
        o.px = px; o.w = w; o.id = id; o.x0 = x0; o.y0 = y0; o.x1 = x1; o.y1 = y1;
        return o;
    }

    private static void reference(int[] px, int w, List<int[]> specs) {
        for (int[] s : specs)
            for (int y = s[2]; y < s[4]; y++)
                for (int x = s[1]; x < s[3]; x++) px[y * w + x] = px[y * w + x] * 31 + s[0];
    }

    private static List<int[]> randomSpecs(int w, int h, int n, long seed) {
        Random r = new Random(seed);
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int x0 = r.nextInt(w - 1), y0 = r.nextInt(h - 1);
            out.add(new int[]{i + 1, x0, y0, x0 + 1 + r.nextInt(w - x0 - 1 + 1 > 1 ? w - x0 : 1), y0 + 1 + r.nextInt(h - y0)});
        }
        return out;
    }

    @Test
    void tiledParallelExecutionKeepsPerPixelCommandOrder() {
        int w = 700, h = 500; // 面積が並列化のしきい値を超え、タイルが複数にまたがる
        List<int[]> specs = randomSpecs(w, h, 60, 11);
        long area = 0;
        for (int[] s : specs) area += (long) (s[3] - s[1]) * (s[4] - s[2]);
        assertTrue(area > Rows.PARALLEL_MIN_PIXELS, "並列の経路を通す前提: " + area);

        int[] got = new int[w * h], want = new int[w * h];
        List<Op> recycled = new ArrayList<>();
        TileQueue q = new TileQueue(recycled::add);
        for (int[] s : specs) q.submit(stamp(got, w, s[0], s[1], s[2], s[3], s[4]), true);
        assertEquals(specs.size(), q.size());
        q.flush();
        reference(want, w, specs);
        assertArrayEquals(want, got);
        assertTrue(q.isEmpty());
        assertEquals(specs.size(), recycled.size(), "実行した命令はすべて手放される");
    }

    @Test
    void smallWorkRunsSequentiallyWithTheSameResult() {
        int w = 40, h = 30;
        List<int[]> specs = randomSpecs(w, h, 10, 3);
        int[] got = new int[w * h], want = new int[w * h];
        TileQueue q = new TileQueue(o -> {});
        for (int[] s : specs) q.submit(stamp(got, w, s[0], s[1], s[2], s[3], s[4]), true);
        q.flush();
        reference(want, w, specs);
        assertArrayEquals(want, got);
    }

    @Test
    void nonDeferredCommandFlushesEarlierOnesFirstAndRunsImmediately() {
        int w = 20, h = 20;
        int[] px = new int[w * h];
        List<Op> recycled = new ArrayList<>();
        TileQueue q = new TileQueue(recycled::add);
        q.submit(stamp(px, w, 1, 0, 0, 10, 10), true);
        assertFalse(q.isEmpty());
        q.submit(stamp(px, w, 2, 5, 5, 15, 15), false); // 順序を守るため、先に溜めた分が先に実行される
        assertTrue(q.isEmpty());
        assertEquals(2, recycled.size());
        assertEquals(1 * 31 + 2, px[7 * w + 7], "重なる画素は 1 → 2 の順");
        assertEquals(1, px[2 * w + 2]);
        assertEquals(2, px[12 * w + 12]);
    }

    @Test
    void switchingTargetFlushesWhatWasQueuedForTheOldTarget() {
        int w = 16, h = 16;
        int[] a = new int[w * h], b = new int[w * h];
        TileQueue q = new TileQueue(o -> {});
        q.submit(stamp(a, w, 1, 0, 0, 4, 4), true);
        q.submit(stamp(b, w, 2, 0, 0, 4, 4), true); // 描画先が変わる: a への分が先に書かれる
        assertEquals(1, a[0], "古い描画先の分は新しい描画先の命令を溜める前に実行済み");
        assertEquals(1, q.size());
        q.flush();
        assertEquals(2, b[0]);
    }

    @Test
    void discardDropsQueuedWorkWithoutRunningOrRecycling() {
        int[] px = new int[16 * 16];
        List<Op> recycled = new ArrayList<>();
        TileQueue q = new TileQueue(recycled::add);
        q.submit(stamp(px, 16, 1, 0, 0, 4, 4), true);
        q.discard();
        assertTrue(q.isEmpty());
        q.flush();
        assertEquals(0, px[0]);
        assertTrue(recycled.isEmpty());
    }

    @Test
    void backendKeepsNoTileLogic() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of(
                "core/src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java"));
        assertFalse(src.contains("flushPending"), "タイル実行は TileQueue に集約済み（§9.5.20）");
        assertFalse(src.contains("parallel()"));
        assertFalse(src.contains("abstract static class Op"));
    }
}
