package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.raster.Matrix2D;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Phase A: 画素を書く命令（{@link PixelOps}）と置き場（{@link OpPool}）を {@link SoftwareRenderBackend} から分離した後の等価性と復活防止。 */
class PixelOpsTest {

    private static final int W = 37, H = 29;

    private static Clip openClip() {
        return new Clip(List.of(), 0, 0, W, H, 0, 0, W, H);
    }

    @Test
    void paintOpWithoutFastRegionMatchesPerPixelReference() {
        Random r = new Random(7);
        for (int round = 0; round < 40; round++) {
            int[] px = new int[W * H], ref = new int[W * H];
            for (int i = 0; i < px.length; i++) ref[i] = px[i] = r.nextInt();
            int c = Pixmap_premul(r.nextInt() | 0x40000000);
            float opacity = 0.25f + r.nextFloat() * 0.75f;
            int x0 = r.nextInt(W - 2), y0 = r.nextInt(H - 2), x1 = x0 + 1 + r.nextInt(W - x0 - 1), y1 = y0 + 1 + r.nextInt(H - y0 - 1);
            Shader shader = (lx, ly) -> lx + ly > 20f ? c : 0;
            // 参照: 画素ごとに shader → 不透明度 → over（恒等姿勢では局所座標 = 画素の中心）
            for (int y = y0; y < y1; y++)
                for (int x = x0; x < x1; x++) {
                    int s = shader.shade(x + 0.5f, y + 0.5f);
                    if (s == 0) continue;
                    if (opacity < 1f) s = SoftShapes.scalePremul(s, opacity);
                    ref[y * W + x] = SoftShapes.over(s, ref[y * W + x]);
                }
            PixelOps.PaintOp op = new PixelOps.PaintOp();
            op.px = px; op.w = W; op.x0 = x0; op.y0 = y0; op.x1 = x1; op.y1 = y1;
            op.pose = Matrix2D.IDENTITY; op.inv = Matrix2D.IDENTITY; op.clip = openClip(); op.clipped = false;
            op.opacity = opacity; op.shader = shader; op.nr = 0; op.nRound = 0;
            op.run(x0, y0, x1, y1, new Scratch());
            assertArrayEquals(ref, px, "round " + round);
        }
    }

    @Test
    void spanColorMatchesThePerPixelPath() {
        assertEquals(0, PixelOps.spanColor(0x00ffffff, 1f), "透明は塗らない");
        int straight = 0x80ff8040;
        assertEquals(SoftShapes.scalePremul(Pixmap_premul(straight), 1f), PixelOps.spanColor(straight, 1f));
        assertEquals(SoftShapes.scalePremul(SoftShapes.scalePremul(Pixmap_premul(straight), 1f), 0.5f), PixelOps.spanColor(straight, 0.5f));
    }

    private static int Pixmap_premul(int argb) {
        return com.nishiyu.enderframelib.core.raster.Pixmap.premultiply(argb);
    }

    @Test
    void poolReusesOpsAndDropsTheirReferences() {
        OpPool pool = new OpPool();
        PixelOps.PaintOp p = pool.acquirePaint();
        p.px = new int[4]; p.clip = openClip(); p.shader = (a, b) -> 1; p.pose = Matrix2D.IDENTITY; p.inv = Matrix2D.IDENTITY;
        pool.release(p);
        assertNull(p.px);
        assertNull(p.clip);
        assertNull(p.shader);
        assertNull(p.pose);
        assertNull(p.inv);
        assertEquals(1, pool.free());
        assertSame(p, pool.acquirePaint(), "解放した命令は次の取得で再利用する");
        assertEquals(0, pool.free());

        PixelOps.GradientOp g = pool.acquireGradient();
        g.px = new int[4]; g.clip = openClip(); g.inv = Matrix2D.IDENTITY; g.rx = new float[4]; g.ry = new float[4];
        pool.release(g);
        assertNull(g.clip); assertNull(g.inv); assertNull(g.rx); assertNull(g.ry); assertNull(g.px);
        assertSame(g, pool.acquireGradient());

        PixelOps.GlyphOp y = pool.acquireGlyph();
        y.px = new int[4]; y.lut = new int[256]; y.clip = openClip();
        pool.release(y);
        assertNull(y.lut); assertNull(y.clip); assertNull(y.px);
        assertSame(y, pool.acquireGlyph());
        assertNotSame(pool.acquirePaint(), pool.acquirePaint(), "置き場が空なら新しい命令");
    }

    @Test
    void opBodiesStayOutOfTheBackend() throws Exception {
        Path src = Path.of("src/main/java/com/nishiyu/enderframelib/core/paint/software/SoftwareRenderBackend.java");
        if (!Files.exists(src)) src = Path.of("core").resolve(src);
        String t = Files.readString(src);
        for (String bad : new String[]{"class PaintOp", "class GradientOp", "class GlyphOp", "static void paintPixels(",
                "static void paintGradientPixels(", "static boolean roundRow(", "static int spanColor(", "ArrayDeque<"}) {
            assertFalse(t.contains(bad), bad + " は PixelOps / OpPool へ移した");
        }
    }
}
