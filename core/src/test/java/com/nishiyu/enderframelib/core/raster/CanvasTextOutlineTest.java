package com.nishiyu.enderframelib.core.raster;

import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.font.WebFonts;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

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

    @Test
    public void fillTextRendersSystemColorEmojiInColorEvenWithBlackFill() {
        SystemFonts system = SystemFonts.system();
        String emojiFamily = null;
        for (String name : new String[]{"Segoe UI Emoji", "Noto Color Emoji", "Apple Color Emoji"}) {
            if (!system.hasFamily(name)) continue;
            for (SystemFonts.Face f : system.family(name)) {
                LoadedFace lf = LoadedFace.open(f);
                if (lf != null && (lf.colorBitmap(0x1F600, 48f) != null || lf.colorLayers(0x1F600) != null)) {
                    emojiFamily = name;
                    break;
                }
            }
            if (emojiFamily != null) break;
        }
        Assumptions.assumeTrue(emojiFamily != null, "カラー絵文字書体が無い環境");

        CanvasRenderingContext2D flat = new CanvasRenderingContext2D(120, 100);
        flat.fonts(system);
        flat.font("64px '" + emojiFamily + "'");
        flat.fillStyle("#000000");
        flat.fillText("\uD83D\uDE00", 10, 75);
        assertTrue(chromaticPixels(flat) > 100, "黒 fillStyle でもカラー絵文字が彩色される");

        CanvasRenderingContext2D rotated = new CanvasRenderingContext2D(120, 100);
        rotated.fonts(system);
        rotated.font("56px '" + emojiFamily + "'");
        rotated.fillStyle("#000000");
        rotated.translate(60, 50);
        rotated.rotate(0.4);
        rotated.fillText("\uD83D\uDE00", -25, 20);
        assertTrue(chromaticPixels(rotated) > 80, "回転してもカラー絵文字が彩色される");
    }

    @Test
    public void fillTextRendersColrV0PaletteAndForegroundLayers() throws IOException {
        byte[] sfnt = buildColrV0TestFont(0x1F600);
        assertTrue(WebFonts.register("test-canvas-colrv0", sfnt, 400, false));
        try {
            CanvasRenderingContext2D c = new CanvasRenderingContext2D(120, 100);
            c.fonts(SystemFonts.system());
            c.font("60px test-canvas-colrv0");
            c.fillStyle("#e02020");
            c.fillText("\uD83D\uDE00", 10, 75);

            int greenHits = 0, redHits = 0;
            for (int y = 0; y < c.height(); y++) {
                for (int x = 0; x < c.width(); x++) {
                    int p = c.bitmap().getStraight(x, y);
                    if ((p >>> 24) < 128) continue;
                    int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                    if (g > 150 && r < 80 && b < 80) greenHits++;
                    if (r > 180 && g < 60 && b < 60) redHits++;
                }
            }
            assertTrue(greenHits > 50, "COLR v0 の固定パレット色（緑）が描かれる: " + greenHits);
            assertTrue(redHits > 50, "COLR v0 の 0xFFFF が fillStyle（赤）で描かれる: " + redHits);
        } finally {
            WebFonts.clear();
        }
    }

    private static int chromaticPixels(CanvasRenderingContext2D c) {
        int n = 0;
        for (int y = 0; y < c.height(); y++) {
            for (int x = 0; x < c.width(); x++) {
                int p = c.bitmap().getStraight(x, y);
                if ((p >>> 24) < 64) continue;
                int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 50) n++;
            }
        }
        return n;
    }

    /** gid=1（左半分, パレット0=緑）+ gid=2（右半分, パレット0xFFFF=fillStyle）の COLR v0 フォント。 */
    private static byte[] buildColrV0TestFont(int cp) throws IOException {
        Map<String, byte[]> t = new TreeMap<>();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        DataOutputStream hd = new DataOutputStream(head);
        hd.writeInt(0x00010000); hd.writeInt(0x00010000); hd.writeInt(0);
        hd.writeInt(0x5F0F3CF5); hd.writeShort(0); hd.writeShort(1000);
        hd.writeLong(0); hd.writeLong(0);
        hd.writeShort(0); hd.writeShort(0); hd.writeShort(1000); hd.writeShort(1000);
        hd.writeShort(0); hd.writeShort(8); hd.writeShort(2); hd.writeShort(0); hd.writeShort(0);
        t.put("head", head.toByteArray());

        ByteArrayOutputStream hhea = new ByteArrayOutputStream();
        DataOutputStream hh = new DataOutputStream(hhea);
        hh.writeInt(0x00010000); hh.writeShort(800); hh.writeShort(-200); hh.writeShort(0);
        hh.writeShort(1024); hh.writeShort(0); hh.writeShort(0); hh.writeShort(1024);
        hh.writeShort(1); hh.writeShort(0); hh.writeShort(0);
        for (int i = 0; i < 4; i++) hh.writeShort(0);
        hh.writeShort(0); hh.writeShort(3);
        t.put("hhea", hhea.toByteArray());

        ByteArrayOutputStream maxp = new ByteArrayOutputStream();
        DataOutputStream mp = new DataOutputStream(maxp);
        mp.writeInt(0x00010000); mp.writeShort(3);
        mp.writeShort(4); mp.writeShort(1);
        for (int i = 2; i < 13; i++) mp.writeShort(0);
        t.put("maxp", maxp.toByteArray());

        ByteArrayOutputStream os2 = new ByteArrayOutputStream();
        DataOutputStream o2 = new DataOutputStream(os2);
        o2.writeShort(4); o2.writeShort(512); o2.writeShort(400); o2.writeShort(5);
        for (int i = 0; i < 12; i++) o2.writeShort(0);
        o2.write(new byte[10]);
        for (int i = 0; i < 4; i++) o2.writeInt(0);
        o2.writeBytes("NONE");
        o2.writeShort(0x0040); o2.writeShort(0xFFFF); o2.writeShort(0xFFFF);
        o2.writeShort(800); o2.writeShort(-200); o2.writeShort(0);
        o2.writeShort(800); o2.writeShort(200);
        o2.writeInt(1); o2.writeInt(0);
        o2.writeShort(500); o2.writeShort(700); o2.writeShort(0); o2.writeShort(0); o2.writeShort(0);
        t.put("OS/2", os2.toByteArray());

        t.put("hmtx", new byte[]{2, 0, 0, 0, 3, (byte) 0xE8, 0, 50, 3, (byte) 0xE8, 1, (byte) 0xF4});

        ByteArrayOutputStream cmap = new ByteArrayOutputStream();
        DataOutputStream cm = new DataOutputStream(cmap);
        cm.writeShort(0); cm.writeShort(1);
        cm.writeShort(3); cm.writeShort(10); cm.writeInt(12);
        cm.writeShort(12); cm.writeShort(0); cm.writeInt(28); cm.writeInt(0); cm.writeInt(1);
        cm.writeInt(cp); cm.writeInt(cp); cm.writeInt(1);
        t.put("cmap", cmap.toByteArray());

        ByteArrayOutputStream glyf = new ByteArrayOutputStream();
        DataOutputStream gl = new DataOutputStream(glyf);
        writeRectGlyph(gl, 50, 0, 450, 700);  // gid 1: 左半分
        writeRectGlyph(gl, 500, 0, 900, 700); // gid 2: 右半分
        t.put("glyf", glyf.toByteArray());
        // numGlyphs = 3 -> loca has 4 entries (uint16 word offsets: 0, 0, 17, 34)
        t.put("loca", new byte[]{0, 0, 0, 0, 0, 17, 0, 34});

        ByteArrayOutputStream post = new ByteArrayOutputStream();
        DataOutputStream ps = new DataOutputStream(post);
        ps.writeInt(0x00030000); ps.writeInt(0); ps.writeShort(0); ps.writeShort(0);
        for (int i = 0; i < 5; i++) ps.writeInt(0);
        t.put("post", post.toByteArray());

        byte[] nm = "test-canvas-colrv0".getBytes(StandardCharsets.UTF_16BE);
        ByteArrayOutputStream name = new ByteArrayOutputStream();
        DataOutputStream na = new DataOutputStream(name);
        na.writeShort(0); na.writeShort(1); na.writeShort(18);
        na.writeShort(3); na.writeShort(1); na.writeShort(0x0409); na.writeShort(1); na.writeShort(nm.length); na.writeShort(0);
        na.write(nm);
        t.put("name", name.toByteArray());

        // CPAL v0: 1 palette, 1 entry (BGRA = 0x20, 0xD0, 0x20, 0xFF -> 緑)
        ByteArrayOutputStream cpal = new ByteArrayOutputStream();
        DataOutputStream cpOut = new DataOutputStream(cpal);
        cpOut.writeShort(0); cpOut.writeShort(1); cpOut.writeShort(1); cpOut.writeShort(1);
        cpOut.writeInt(14); cpOut.writeShort(0);
        cpOut.writeByte(0x20); cpOut.writeByte(0xD0); cpOut.writeByte(0x20); cpOut.writeByte(0xFF);
        t.put("CPAL", cpal.toByteArray());

        // COLR v0: gid 1 -> layer 0 (gid 1, pal 0) + layer 1 (gid 2, pal 0xFFFF)
        ByteArrayOutputStream colr = new ByteArrayOutputStream();
        DataOutputStream co = new DataOutputStream(colr);
        co.writeShort(0); co.writeShort(1); co.writeInt(14); co.writeInt(20); co.writeShort(2);
        co.writeShort(1); co.writeShort(0); co.writeShort(2);
        co.writeShort(1); co.writeShort(0);
        co.writeShort(2); co.writeShort(0xFFFF);
        t.put("COLR", colr.toByteArray());

        int n = t.size(), dirEnd = 12 + n * 16;
        int[] offsets = new int[n], lengths = new int[n];
        byte[][] datas = new byte[n][];
        String[] tags = t.keySet().toArray(new String[0]);
        int pos = dirEnd;
        for (int i = 0; i < n; i++) {
            byte[] d = t.get(tags[i]);
            lengths[i] = d.length;
            datas[i] = java.util.Arrays.copyOf(d, (d.length + 3) & ~3);
            offsets[i] = pos;
            pos += datas[i].length;
        }
        ByteArrayOutputStream sfnt = new ByteArrayOutputStream(pos);
        DataOutputStream out = new DataOutputStream(sfnt);
        out.writeInt(0x00010000); out.writeShort(n);
        int sr = Integer.highestOneBit(n) * 16;
        out.writeShort(sr); out.writeShort(Integer.numberOfTrailingZeros(sr / 16)); out.writeShort(n * 16 - sr);
        for (int i = 0; i < n; i++) {
            int sum = 0;
            byte[] d = datas[i];
            for (int j = 0; j < d.length; j += 4) {
                sum += ((d[j] & 0xFF) << 24) | ((d[j + 1] & 0xFF) << 16) | ((d[j + 2] & 0xFF) << 8) | (d[j + 3] & 0xFF);
            }
            out.writeBytes(tags[i]); out.writeInt(sum); out.writeInt(offsets[i]); out.writeInt(lengths[i]);
        }
        for (byte[] d : datas) out.write(d);
        return sfnt.toByteArray();
    }

    private static void writeRectGlyph(DataOutputStream gl, int x0, int y0, int x1, int y1) throws IOException {
        gl.writeShort(1); gl.writeShort(x0); gl.writeShort(y0); gl.writeShort(x1); gl.writeShort(y1);
        gl.writeShort(3); gl.writeShort(0);
        gl.write(new byte[]{1, 1, 1, 1});
        gl.writeShort(x0); gl.writeShort(0); gl.writeShort(x1 - x0); gl.writeShort(0);
        gl.writeShort(y0); gl.writeShort(y1 - y0); gl.writeShort(0); gl.writeShort(y0 - y1);
    }
}
