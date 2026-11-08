package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** SoftwareRenderBackend から切り出した、状態を持たない CPU ラスタの部品（Clip / ClipPart / Fast / Shader / Scratch）の単体検証。 */
class CpuRasterPartsTest {

    /** 恒等姿勢・円の角 {@code r} の矩形のクリップ片（{@code addClipPart} と同じ前提の値）。 */
    private static ClipPart part(float x0, float y0, float x1, float y1, float r) {
        float[] rx = {r, r, r, r};
        float reach = 0.5f + 1e-3f;
        float in = r + reach;
        return new ClipPart(Matrix2D.IDENTITY, 1f, x0, y0, x1, y1, rx, rx,
                (int) Math.floor(x0), (int) Math.floor(y0), (int) Math.ceil(x1), (int) Math.ceil(y1),
                x0 + in, y0 + in, x1 - in, y1 - in, x0 + in + 1e-3, y0 + in + 1e-3, x1 - in - 1e-3, y1 - in - 1e-3);
    }

    private static Clip clipOf(ClipPart... parts) {
        int x0 = 0, y0 = 0, x1 = 200, y1 = 200;
        double ix0 = Double.NEGATIVE_INFINITY, iy0 = ix0, ix1 = Double.POSITIVE_INFINITY, iy1 = ix1;
        for (ClipPart p : parts) {
            x0 = Math.max(x0, p.bx0()); y0 = Math.max(y0, p.by0());
            x1 = Math.min(x1, p.bx1()); y1 = Math.min(y1, p.by1());
            ix0 = Math.max(ix0, p.dix0()); iy0 = Math.max(iy0, p.diy0());
            ix1 = Math.min(ix1, p.dix1()); iy1 = Math.min(iy1, p.diy1());
        }
        return new Clip(List.of(parts), x0, y0, x1, y1, ix0, iy0, ix1, iy1);
    }

    @Test
    void emptyClipCoversEverythingAndBoundsDecideEmptiness() {
        Clip none = clipOf();
        assertEquals(1f, none.coverage(5.5, 7.5));
        assertFalse(none.empty());
        assertTrue(clipOf(part(10, 10, 20, 20, 0), part(30, 30, 40, 40, 0)).empty(), "交わらない 2 片は空");
    }

    @Test
    void clipCoverageMatchesTheRoundRectDistanceEverywhere() {
        ClipPart p = part(10, 20, 90, 70, 12);
        Clip c = clipOf(p);
        Random rnd = new Random(7);
        for (int i = 0; i < 20000; i++) {
            double dx = rnd.nextDouble() * 110, dy = rnd.nextDouble() * 90;
            float expect = SoftShapes.coverage(SoftShapes.sdRoundRect((float) dx, (float) dy, 10, 20, 90, 70, p.rx(), p.ry()), 1f);
            assertEquals(expect, c.coverage(dx, dy), 1e-6f, "(" + dx + "," + dy + ")");
            assertEquals(expect, p.coverage(dx, dy), 1e-6f);
        }
        assertEquals(0f, c.coverage(5, 5));
        assertEquals(1f, c.coverage(50, 45));
    }

    @Test
    void twoPartsMultiplyAndZeroShortCircuits() {
        Clip c = clipOf(part(0, 0, 100, 100, 0), part(50, 0, 150, 100, 0));
        assertEquals(1f, c.coverage(75.5, 50.5), 1e-6f);
        assertEquals(0f, c.coverage(25.5, 50.5));
        assertEquals(0f, c.coverage(125.5, 50.5));
    }

    @Test
    void fastRoundSpanOnlyPromisesFullCoverage() {
        float x0 = 10, y0 = 10, x1 = 110, y1 = 70;
        float[] r = {14, 14, 14, 14};
        float reach = 0.5f + 1e-3f;
        Fast f = new Fast();
        f.roundRect(x0, y0, x1, y1, r, r, reach, 0xFF112233);
        assertEquals(1, f.nRound);
        double[] span = new double[2];
        int hits = 0;
        for (double ly = 0; ly < 80; ly += 0.37) {
            if (!f.roundSpan(0, ly, span)) continue;
            hits++;
            for (double lx : new double[]{span[0] + 1e-3, (span[0] + span[1]) / 2, span[1] - 1e-3}) {
                float sd = SoftShapes.sdRoundRect((float) lx, (float) ly, x0, y0, x1, y1, r, r);
                assertEquals(1f, SoftShapes.coverage(sd, 1f), 1e-6f, "ly=" + ly + " lx=" + lx);
            }
        }
        assertTrue(hits > 100, "hits=" + hits);
    }

    @Test
    void fastFallsBackToAnInnerRectForNonCircularCornersAndCapsRegions() {
        Fast f = new Fast();
        f.roundRect(0, 0, 100, 50, new float[]{10, 10, 10, 10}, new float[]{5, 5, 5, 5}, 0.5f, 1);
        assertEquals(0, f.nRound);
        assertEquals(1, f.n);
        assertEquals(10.5f, f.rect[0], 1e-6f);
        assertEquals(5.5f, f.rect[1], 1e-6f);
        for (int i = 0; i < 20; i++) f.rect(0, 0, 1, 1, 2);
        assertEquals(Fast.MAX, f.n);
        f.rect(0, 0, 0, 0, 3);
        assertEquals(Fast.MAX, f.n, "空の矩形は足さない");
    }

    @Test
    void scratchSizedForEveryRegionAndMaxOf() {
        Scratch s = new Scratch();
        assertEquals(Fast.MAX + Fast.MAX_ROUND, s.a.length);
        assertEquals(2, s.out.length);
        assertEquals(0f, SoftShapes.maxOf(new float[]{-1, -2}));
        assertEquals(3f, SoftShapes.maxOf(new float[]{1, 3, 2}));
        Shader sh = (x, y) -> x > y ? 1 : 0;
        assertEquals(1, sh.shade(2, 1));
    }

    @Test
    void extractedTypesDoNotComeBackIntoTheBackend() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"record ClipPart(", "record Clip(", "class Fast ", "interface Shader ", "class Scratch ", "float maxOf("}) {
            assertFalse(t.contains(bad), bad + " は別ファイルへ移した");
        }
    }
}
