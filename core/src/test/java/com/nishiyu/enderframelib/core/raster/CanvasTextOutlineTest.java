package com.nishiyu.enderframelib.core.raster;

import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Canvas のテキスト: 回転・せん断・反転でも字形が変換される（字形の輪郭を変換して塗る）、{@code strokeText} が輪郭を縁取る、
 * {@code measureText} の墨の範囲、{@code direction}、キャンバス同士の {@code drawImage}。実際の書体が要る。
 */
public class CanvasTextOutlineTest {

    private static CanvasRenderingContext2D ctx(int w, int h) {
        SystemFonts system = SystemFonts.system();
        FontFace face = system.resolve("sans-serif");
        Assumptions.assumeTrue(face instanceof OutlineFontFace, "輪郭の書体が無い環境");
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(w, h);
        c.fonts(system);
        c.font("40px sans-serif");
        return c;
    }

    /** 不透明度のある画素の外接矩形 {@code [x0, y0, x1, y1]}（x1・y1 は含まない）。無ければ null。 */
    private static int[] ink(CanvasRenderingContext2D c) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
        for (int y = 0; y < c.height(); y++) {
            for (int x = 0; x < c.width(); x++) {
                if ((c.bitmap().getStraight(x, y) >>> 24) > 64) {
                    x0 = Math.min(x0, x);
                    y0 = Math.min(y0, y);
                    x1 = Math.max(x1, x + 1);
                    y1 = Math.max(y1, y + 1);
                }
            }
        }
        return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
    }

    private static int inkCount(CanvasRenderingContext2D c) {
        int n = 0;
        for (int y = 0; y < c.height(); y++) {
            for (int x = 0; x < c.width(); x++) if ((c.bitmap().getStraight(x, y) >>> 24) > 128) n++;
        }
        return n;
    }

    @Test
    public void rotatedTextTurnsTheGlyphs() {
        CanvasRenderingContext2D flat = ctx(200, 200);
        flat.fillText("HHHH", 20, 100);
        int[] a = ink(flat);
        assertNotNull(a);
        assertTrue(a[2] - a[0] > (a[3] - a[1]) * 2, "水平の文字は横長: " + java.util.Arrays.toString(a));

        CanvasRenderingContext2D turned = ctx(200, 200);
        turned.translate(100, 20);
        turned.rotate(Math.PI / 2);
        turned.fillText("HHHH", 0, 0);
        int[] b = ink(turned);
        assertNotNull(b, "回転した文字が描かれる");
        assertTrue(b[3] - b[1] > (b[2] - b[0]) * 2, "90 度回すと縦長: " + java.util.Arrays.toString(b));
        assertEquals(a[2] - a[0], b[3] - b[1], 3);
    }

    @Test
    public void skewAndFlipTransformTheText() {
        CanvasRenderingContext2D plain = ctx(200, 120);
        plain.fillText("HH", 20, 80);
        int[] p = ink(plain);

        CanvasRenderingContext2D skewed = ctx(200, 120);
        skewed.transform(1, 0, -0.6, 1, 0, 0); // x' = x - 0.6y（上へいくほど右へ流れる）
        skewed.fillText("HH", 70, 80);
        int[] s = ink(skewed);
        assertNotNull(s);
        assertTrue(s[2] - s[0] > p[2] - p[0] + 10, "せん断で幅が広がる: " + (s[2] - s[0]) + " vs " + (p[2] - p[0]));

        CanvasRenderingContext2D flipped = ctx(200, 120);
        flipped.translate(0, 120);
        flipped.scale(1, -1);
        flipped.fillText("H", 20, 40); // 反転後の y = 40 は画面の y = 80
        int[] f = ink(flipped);
        assertNotNull(f, "反転した文字が描かれる");
        assertTrue(f[1] >= 78, "基線より下に描かれる: " + java.util.Arrays.toString(f));
    }

    @Test
    public void nonUniformScaleStretchesTheText() {
        CanvasRenderingContext2D plain = ctx(300, 120);
        plain.fillText("H", 10, 80);
        CanvasRenderingContext2D wide = ctx(300, 120);
        wide.scale(3, 1);
        wide.fillText("H", 10 / 3.0, 80);
        int[] p = ink(plain), w = ink(wide);
        assertEquals((p[2] - p[0]) * 3.0, w[2] - w[0], 4.0);
        assertEquals(p[3] - p[1], w[3] - w[1], 2.0, "高さは変わらない");
    }

    @Test
    public void strokeTextOutlinesTheGlyphInsteadOfFillingIt() {
        CanvasRenderingContext2D filled = ctx(160, 140);
        filled.font("bold 120px sans-serif");
        filled.fillText("I", 40, 120);
        CanvasRenderingContext2D stroked = ctx(160, 140);
        stroked.font("bold 120px sans-serif");
        stroked.lineWidth(2);
        stroked.strokeText("I", 40, 120);
        int[] box = ink(filled);
        assertNotNull(box);
        int cx = (box[0] + box[2]) / 2, cy = (box[1] + box[3]) / 2;
        assertTrue((filled.bitmap().getStraight(cx, cy) >>> 24) > 200, "塗りは中が埋まる");
        assertEquals(0, stroked.bitmap().getStraight(cx, cy) >>> 24, "縁取りは中が空");
        assertNotNull(ink(stroked), "縁取りが描かれる");
        assertTrue(inkCount(stroked) < inkCount(filled) / 2, "縁取りの画素は塗りより少ない");
    }

    @Test
    public void strokeTextHonoursLineWidth() {
        CanvasRenderingContext2D thin = ctx(160, 140), thick = ctx(160, 140);
        thin.font("bold 100px sans-serif");
        thick.font("bold 100px sans-serif");
        thin.lineWidth(1);
        thick.lineWidth(6);
        thin.strokeText("O", 30, 110);
        thick.strokeText("O", 30, 110);
        assertTrue(inkCount(thick) > inkCount(thin) * 3, "太い線は画素が多い");
    }

    @Test
    public void measureTextReportsInkBounds() {
        CanvasRenderingContext2D c = ctx(10, 10);
        c.font("100px sans-serif");
        TextMetrics h = c.measureText("H");
        TextMetrics g = c.measureText("g");
        assertTrue(h.actualBoundingBoxAscent() > 50 && h.actualBoundingBoxAscent() < h.fontBoundingBoxAscent() + 1,
                "H の高さ（キャップハイト）: " + h.actualBoundingBoxAscent());
        assertTrue(h.actualBoundingBoxDescent() < 3, "H は基線の下へ出ない: " + h.actualBoundingBoxDescent());
        assertTrue(g.actualBoundingBoxDescent() > 10, "g は基線の下へ出る: " + g.actualBoundingBoxDescent());
        assertTrue(h.actualBoundingBoxRight() > 0 && h.actualBoundingBoxRight() <= h.width() + 1);
        assertTrue(h.fontBoundingBoxAscent() > 0 && h.fontBoundingBoxDescent() > 0);
        assertEquals(0, h.alphabeticBaseline(), 1e-9, "既定の textBaseline は alphabetic");
        assertTrue(h.hangingBaseline() > 0 && h.ideographicBaseline() < 0);

        c.textBaseline("top");
        TextMetrics top = c.measureText("H");
        assertTrue(top.alphabeticBaseline() < -50, "top のとき欧文の基線は原点の下: " + top.alphabeticBaseline());
        assertTrue(top.actualBoundingBoxAscent() < 5, "top のとき文字は原点より上へ出ない: " + top.actualBoundingBoxAscent());
    }

    @Test
    public void inkBoundsMatchWhatIsDrawn() {
        CanvasRenderingContext2D c = ctx(200, 160);
        c.font("80px sans-serif");
        TextMetrics m = c.measureText("Hx");
        c.fillText("Hx", 30, 100);
        int[] box = ink(c);
        assertEquals(100 - m.actualBoundingBoxAscent(), box[1], 2.0, "墨の上端");
        assertEquals(100 + m.actualBoundingBoxDescent(), box[3], 2.0, "墨の下端");
        assertEquals(30 - m.actualBoundingBoxLeft(), box[0], 2.0, "墨の左端");
        assertEquals(30 + m.actualBoundingBoxRight(), box[2], 2.0, "墨の右端");
    }

    @Test
    public void directionSwapsStartAndEnd() {
        CanvasRenderingContext2D c = ctx(10, 10);
        c.textAlign("start");
        TextMetrics ltr = c.measureText("HHH");
        assertEquals(0, ltr.actualBoundingBoxLeft(), 8.0);
        c.direction("rtl");
        assertEquals("rtl", c.direction());
        TextMetrics rtl = c.measureText("HHH");
        assertEquals(rtl.width(), rtl.actualBoundingBoxLeft(), 8.0, "rtl の start は右揃え");
        c.textAlign("end");
        TextMetrics rtlEnd = c.measureText("HHH");
        assertEquals(0, rtlEnd.actualBoundingBoxLeft(), 8.0, "rtl の end は左揃え");
        c.direction("bogus");
        assertEquals("rtl", c.direction(), "不正な値は無視");
    }

    @Test
    public void gettersRoundTripAndSurviveSaveRestore() {
        CanvasRenderingContext2D c = ctx(10, 10);
        assertEquals("start", c.textAlign());
        assertEquals("alphabetic", c.textBaseline());
        assertEquals("ltr", c.direction());
        assertEquals("low", c.imageSmoothingQuality());
        c.save();
        c.textAlign("center");
        c.textBaseline("middle");
        c.direction("rtl");
        c.imageSmoothingQuality("high");
        assertEquals("center", c.textAlign());
        assertEquals("high", c.imageSmoothingQuality());
        c.restore();
        assertEquals("start", c.textAlign());
        assertEquals("alphabetic", c.textBaseline());
        assertEquals("ltr", c.direction());
        assertEquals("low", c.imageSmoothingQuality());
    }

    @Test
    public void drawImageFromAnotherCanvasScalesAndCrops() {
        CanvasRenderingContext2D src = new CanvasRenderingContext2D(10, 10);
        src.fillStyle("#ff0000");
        src.fillRect(0, 0, 5, 10);
        src.fillStyle("#0000ff");
        src.fillRect(5, 0, 5, 10);
        CanvasRenderingContext2D dst = new CanvasRenderingContext2D(40, 20);
        dst.drawImage(src, 0, 0, 20, 20);
        assertEquals(0xFFFF0000, dst.bitmap().getStraight(5, 10));
        assertEquals(0xFF0000FF, dst.bitmap().getStraight(15, 10));
        CanvasRenderingContext2D cropped = new CanvasRenderingContext2D(20, 10);
        cropped.drawImage(src, 5, 0, 5, 10, 0, 0, 20, 10); // 右半分（青）だけを引き伸ばす
        assertEquals(0xFF0000FF, cropped.bitmap().getStraight(2, 5));
        assertEquals(0xFF0000FF, cropped.bitmap().getStraight(17, 5));
        ImageData like = dst.createImageData(dst.getImageData(0, 0, 7, 3));
        assertEquals(7, like.width());
        assertEquals(3, like.height());
    }

    @Test
    public void glyphOutlineIsInDesignUnitsYUp() {
        SystemFonts system = SystemFonts.system();
        FontFace face = system.resolve("sans-serif");
        Assumptions.assumeTrue(face instanceof OutlineFontFace);
        OutlineFontFace outline = (OutlineFontFace) face;
        FontStyle style = new FontStyle(40f, false, false, 0, 400, true);
        LoadedFace lf = outline.glyphFace('H', style).face();
        Path2D h = lf.glyphOutline(lf.glyphId('H'));
        assertFalse(h.isEmpty());
        double[] b = h.bounds(Matrix2D.IDENTITY);
        double upem = lf.unitsPerEm();
        assertTrue(b[3] > 0.5 * upem && b[3] < 1.2 * upem, "上端は正（y 上向き）: " + b[3] / upem + " em");
        assertTrue(b[1] > -0.1 * upem && b[1] < 0.1 * upem, "下端は基線付近");
        assertTrue(lf.glyphOutline(lf.glyphId(' ')).isEmpty(), "空白は空のパス");
        assertSame(h, lf.glyphOutline(lf.glyphId('H')), "キャッシュされる");
    }
}
