package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 空間ハッシュのヒットテストが、旧実装（全件の線形探索）と同じ結果になること。 */
class HitTestIndexTest {

    private static String page(String body) {
        return "<html><body style='margin:0'>" + body + "</body></html>";
    }

    /**
     * 重なり・z-index・静的な変形・疑似乱数の大きさの箱を並べた文書。アニメーションは、2 回の呼び出しの間（時計が進む）に
     * 値がほぼ動かないよう、遅くして途中（遅延の負の値）から始める（極端に長いと、基準の旧実装自身が時刻の浮動小数点の丸めで揺れる）。
     */
    private static String scatter(int n, long seed, boolean withAnimation) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int x = r.nextInt(560), y = r.nextInt(360), w = 8 + r.nextInt(90), h = 8 + r.nextInt(70);
            String extra = switch (r.nextInt(8)) {
                case 0 -> ";transform:rotate(" + (r.nextInt(80) - 40) + "deg)";
                case 1 -> ";transform:translate(" + (r.nextInt(20) - 10) + "px," + (r.nextInt(20) - 10) + "px)";
                case 2 -> ";transform:scale(" + (0.5 + r.nextInt(15) / 10.0) + ")";
                case 3 -> withAnimation ? ";animation:spin 100000s linear -25000s infinite" : "";
                case 4 -> withAnimation ? ";animation:slide 100000s linear -25000s infinite alternate" : "";
                default -> "";
            };
            sb.append("<div id=b").append(i).append(" onclick=\"x()\" style=\"position:absolute;left:").append(x).append("px;top:").append(y)
                    .append("px;width:").append(w).append("px;height:").append(h).append("px;z-index:").append(r.nextInt(6))
                    .append(";background:rgb(").append(r.nextInt(256)).append(",").append(r.nextInt(256)).append(",").append(r.nextInt(256))
                    .append(")").append(extra).append("\"></div>");
        }
        String css = withAnimation
                ? "<style>@keyframes spin{from{transform:rotate(0deg)}to{transform:rotate(360deg)}}"
                + "@keyframes slide{from{transform:translateX(0px)}to{transform:translateX(300px)}}</style>"
                : "";
        return css + sb;
    }

    private static void assertSameEverywhere(HitTestIndex idx, String session, long seed, int w, int h, int points) {
        Random r = new Random(seed);
        int hits = 0;
        for (int i = 0; i < points; i++) {
            // 整数・半整数・一般の小数・範囲外を混ぜる
            double x = switch (i % 4) {
                case 0 -> r.nextInt(w + 20) - 10;
                case 1 -> r.nextInt((w + 20) * 2) / 2.0 - 10;
                case 2 -> r.nextDouble() * (w + 20) - 10;
                default -> r.nextInt(w) + 0.5;
            };
            double y = switch ((i / 4) % 4) {
                case 0 -> r.nextInt(h + 20) - 10;
                case 1 -> r.nextInt((h + 20) * 2) / 2.0 - 10;
                case 2 -> r.nextDouble() * (h + 20) - 10;
                default -> r.nextInt(h) + 0.5;
            };
            UIHitResult a = idx.hitTest(session, x, y);
            UIHitResult b = idx.hitTestLinear(session, x, y);
            if (b == null) {
                assertNull(a, "線形が外れなのに当たった @" + x + "," + y);
                assertFalse(idx.isPointerOverUi(session, x, y));
            } else {
                hits++;
                assertNotNull(a, "線形が当たったのに外れた @" + x + "," + y + " 本来 " + b.element().id());
                assertEquals(b.element().id(), a.element().id(), "@" + x + "," + y);
                assertEquals(b.localX(), a.localX(), 1e-2, "@" + x + "," + y);
                assertEquals(b.localY(), a.localY(), 1e-2, "@" + x + "," + y);
                assertTrue(idx.isPointerOverUi(session, x, y));
            }
        }
        assertTrue(hits > points / 10, "当たりが少なすぎて検証にならない: " + hits);
    }

    @Test
    void staticAndTransformedScenesMatchTheLinearScan() {
        for (long seed : new long[]{1, 2, 3}) {
            StandaloneView v = new StandaloneView(page(scatter(300, seed, false)), 640, 420, FontResolver.FALLBACK);
            v.ui().flushInvalidation();
            HitTestIndex idx = v.ui().hitTestIndex();
            assertSameEverywhere(idx, v.ui().sessionId(), seed * 31, 640, 420, 6000);
        }
    }

    @Test
    void animatedElementsAreAlwaysCandidates() {
        StandaloneView v = new StandaloneView(page(scatter(200, 7, true)), 640, 420, FontResolver.FALLBACK);
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(640, 420, 1f)) {
            for (long t = 1000; t <= 3000; t += 1000) {
                v.frame(t, backend, true); // アニメーションの状態を作る・進める
                HitTestIndex idx = v.ui().hitTestIndex();
                assertSameEverywhere(idx, v.ui().sessionId(), 99 + t, 640, 420, 4000);
            }
        }
    }

    @Test
    void emptyAndOutOfRangeQueriesAreSafe() {
        StandaloneView empty = new StandaloneView(page(""), 100, 100, FontResolver.FALLBACK);
        empty.ui().flushInvalidation();
        HitTestIndex idx = empty.ui().hitTestIndex();
        assertNull(idx.hitTest("s", 5, 5));
        assertFalse(idx.isPointerOverUi("s", 5, 5));

        StandaloneView v = new StandaloneView(page("<div id=a onclick=\"x()\" style='width:50px;height:50px;background:#f00'></div>"), 100, 100,
                FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        HitTestIndex i2 = v.ui().hitTestIndex();
        assertNotNull(i2.hitTest(v.ui().sessionId(), 10, 10));
        for (double bad : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -1e9, 1e9, -0.0001}) {
            assertEquals(i2.hitTestLinear(v.ui().sessionId(), bad, 10) == null, i2.hitTest(v.ui().sessionId(), bad, 10) == null);
            assertEquals(i2.hitTestLinear(v.ui().sessionId(), 10, bad) == null, i2.hitTest(v.ui().sessionId(), 10, bad) == null);
        }
    }

    /** 大量の要素でも点の検索が線形の探索より桁違いに速い（候補が数個に絞られる）。 */
    @Test
    void manyElementsAreMuchFasterThanTheLinearScan() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < 60; y++) {
            for (int x = 0; x < 50; x++) {
                sb.append("<div id=c").append(y * 50 + x).append(" onclick=\"x()\" style=\"position:absolute;left:").append(x * 16)
                        .append("px;top:").append(y * 12).append("px;width:14px;height:10px;background:#36c\"></div>");
            }
        }
        StandaloneView v = new StandaloneView(page(sb.toString()), 800, 720, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        HitTestIndex idx = v.ui().hitTestIndex();
        String session = v.ui().sessionId();
        Random r = new Random(5);
        double[][] pts = new double[2000][2];
        for (double[] p : pts) {
            p[0] = r.nextDouble() * 800;
            p[1] = r.nextDouble() * 720;
        }
        // 暖機
        for (double[] p : pts) {
            idx.hitTest(session, p[0], p[1]);
            idx.hitTestLinear(session, p[0], p[1]);
        }
        long t0 = System.nanoTime();
        int a = 0;
        for (int rep = 0; rep < 3; rep++) for (double[] p : pts) if (idx.hitTest(session, p[0], p[1]) != null) a++;
        long grid = System.nanoTime() - t0;
        t0 = System.nanoTime();
        int b = 0;
        for (int rep = 0; rep < 3; rep++) for (double[] p : pts) if (idx.hitTestLinear(session, p[0], p[1]) != null) b++;
        long linear = System.nanoTime() - t0;
        assertEquals(b, a, "当たり数が違う");
        System.out.println("[hit-test benchmark] 3000 elements, 6000 queries: grid=" + grid / 1_000_000 + "ms linear=" + linear / 1_000_000 + "ms");
        assertTrue(grid * 3 < linear, "空間ハッシュが速くなっていない: grid=" + grid + "ns linear=" + linear + "ns");
    }

    // ------------------------------------------------------------------ 透過ヒットテスト

    /** 8×8 の PNG。左 4 列は不透明な赤、右 4 列は完全に透明。 */
    private static String halfTransparentPng() throws Exception {
        int[] px = new int[64];
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) px[y * 8 + x] = x < 4 ? 0xFFFF0000 : 0x00000000;
        return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(com.nishiyu.enderframelib.core.raster.PngEncoder.encode(8, 8, px));
    }

    private static String baseId(UIHitResult r) {
        return r == null ? null : r.element().baseId();
    }

    @Test
    void transparentImagePixelsClickThroughToTheElementBelow() throws Exception {
        String png = halfTransparentPng();
        String below = "<div id=under onclick=\"x()\" style='position:absolute;left:0;top:0;width:80px;height:80px;background:#00f'></div>";
        String img = "<img id=sprite src='" + png + "' %s style='position:absolute;left:0;top:0;width:80px;height:80px'>";

        // 属性なし: 画像の矩形全体が当たる（従来どおり）
        StandaloneView plain = new StandaloneView(page(below + String.format(img, "")), 100, 100, FontResolver.FALLBACK);
        plain.ui().flushInvalidation();
        assertEquals("sprite", baseId(plain.ui().hitTest(70, 40)));

        // data-hit-alpha: 不透明な左半分は画像、透明な右半分は下の要素へ通る
        StandaloneView alpha = new StandaloneView(page(below + String.format(img, "data-hit-alpha")), 100, 100, FontResolver.FALLBACK);
        alpha.ui().flushInvalidation();
        assertEquals("sprite", baseId(alpha.ui().hitTest(20, 40)));
        assertEquals("under", baseId(alpha.ui().hitTest(70, 40)), "透明な画素は下へ通る");
        assertEquals("sprite", baseId(alpha.ui().hitTestIndex().hitTestLinear(alpha.ui().sessionId(), 20, 40)), "旧経路も同じ");
        assertEquals("under", baseId(alpha.ui().hitTestIndex().hitTestLinear(alpha.ui().sessionId(), 70, 40)), "旧経路も同じ");
        assertTrue(alpha.isPointerOverUi(70, 40), "下の要素の上");
        assertFalse(alpha.isPointerOverUi(95, 95), "どれの上でもない");
    }

    @Test
    void thresholdAndObjectFitDecideWhichPixelsCount() throws Exception {
        // 半透明（alpha 100）の画素: 既定の閾値 128 では透明扱い、閾値 50 なら不透明
        int[] px = new int[16];
        java.util.Arrays.fill(px, 0x64640000); // a=100 (premultiplied-looking straight ARGB は PngEncoder が非乗算として書く)
        String png = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(com.nishiyu.enderframelib.core.raster.PngEncoder.encode(4, 4, px));
        String below = "<div id=under onclick=\"x()\" style='position:absolute;left:0;top:0;width:40px;height:40px;background:#00f'></div>";
        for (String[] c : new String[][]{{"data-hit-alpha", "under"}, {"data-hit-alpha=50", "sprite"}}) {
            StandaloneView v = new StandaloneView(page(below + "<img id=sprite src='" + png + "' " + c[0]
                    + " style='position:absolute;left:0;top:0;width:40px;height:40px'>"), 60, 60, FontResolver.FALLBACK);
            v.ui().flushInvalidation();
            assertEquals(c[1], baseId(v.ui().hitTest(20, 20)), c[0]);
        }
        // object-fit: contain で左右に余白ができる画像: 余白は透明扱い
        int[] opaque = new int[4 * 4];
        java.util.Arrays.fill(opaque, 0xFF00FF00);
        String full = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(com.nishiyu.enderframelib.core.raster.PngEncoder.encode(4, 4, opaque));
        StandaloneView v = new StandaloneView(page(below + "<img id=sprite src='" + full + "' data-hit-alpha "
                + "style='position:absolute;left:0;top:0;width:40px;height:20px;object-fit:contain'>"), 60, 60, FontResolver.FALLBACK);
        v.ui().flushInvalidation();
        assertEquals("sprite", baseId(v.ui().hitTest(20, 10)), "画像の中央");
        assertEquals("under", baseId(v.ui().hitTest(2, 10)), "contain の左の余白は通る");
    }

    @Test
    void alphaMaskIsOneBitPerPixelAndCached() {
        com.nishiyu.enderframelib.core.raster.Pixmap pm = new com.nishiyu.enderframelib.core.raster.Pixmap(256, 256);
        pm.pixels()[5 * 256 + 7] = 0xFF000000;
        AlphaMask m = AlphaMask.of(pm, 128);
        assertEquals(8192, m.byteSize(), "256×256 で 8KB");
        assertTrue(m.opaque(7, 5));
        assertFalse(m.opaque(8, 5));
        assertFalse(m.opaque(-1, 0));
        assertEquals(m, AlphaMask.of(pm, 128), "同じ画素・閾値は使い回す");
        pm.pixels()[5 * 256 + 8] = 0xFF000000;
        pm.markChanged();
        assertTrue(AlphaMask.of(pm, 128).opaque(8, 5), "画素が変わったら作り直す");
    }
}
