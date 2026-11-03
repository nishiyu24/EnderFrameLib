package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.FilterOp;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 大きな σ のぼかし（3 回のボックスぼかしの近似）が、ガウスの結果に近く、画面全体でも遅くならないこと。 */
public class LayerFxBlurTest {
    private static final double[] IDENTITY = {1, 0, 0, 1};

    private static long alphaSum(Pixmap pm, int[] r) {
        long sum = 0;
        for (int y = r[1]; y < r[3]; y++) for (int x = r[0]; x < r[2]; x++) sum += pm.pixels()[y * pm.width() + x] >>> 24;
        return sum;
    }

    @Test
    public void largeBlurPreservesMassAndSpreadsSymmetrically() {
        Pixmap pm = new Pixmap(400, 300);
        for (int y = 140; y < 160; y++) for (int x = 190; x < 210; x++) pm.pixels()[y * 400 + x] = 0xFFFFFFFF;
        long before = alphaSum(pm, new int[]{0, 0, 400, 300});
        int[] region = LayerFx.filter(pm, new int[]{190, 140, 210, 160}, List.of(FilterOp.blur(20f)), IDENTITY);
        long after = alphaSum(pm, region);
        assertEquals(before, after, before * 0.02, "質量が保たれる");
        int c = pm.pixels()[150 * 400 + 200] >>> 24;
        int l = pm.pixels()[150 * 400 + 170] >>> 24, rt = pm.pixels()[150 * 400 + 230] >>> 24;
        assertTrue(c > l && l > 0, "中心が最も濃く、周りへ広がる");
        assertTrue(Math.abs(l - rt) <= 3, "左右対称: " + l + " vs " + rt);
        assertTrue(region[0] < 190 - 20 && region[2] > 210 + 20, "広がった領域を返す");
    }

    @Test
    public void fullScreenLargeBlurIsFast() {
        Pixmap pm = new Pixmap(1280, 800);
        java.util.Arrays.fill(pm.pixels(), 0xFF3366FF);
        long t0 = System.nanoTime();
        LayerFx.filter(pm, new int[]{0, 0, 1280, 800}, List.of(FilterOp.blur(90f)), IDENTITY);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertTrue(ms < 2000, "画面全体の blur(90px) が " + ms + "ms");
    }
}
