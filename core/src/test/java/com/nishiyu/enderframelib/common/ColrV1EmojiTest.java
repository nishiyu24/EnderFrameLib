package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.font.ColorBitmap;
import com.nishiyu.enderframelib.core.text.font.ColorLayers;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import com.nishiyu.enderframelib.core.text.font.WebFonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COLR v1（グラデーション・変形・合成を持つカラー絵文字）。ペイントグラフを描いた色付きのビットマップとして置く。
 * COLR v1 を持つ絵文字書体（Windows 11 の Segoe UI Emoji など）が無い環境では飛ばす。
 */
public class ColrV1EmojiTest {

    private static final int SMILE = 0x1F600;

    private static SystemFonts.Face face() {
        SystemFonts system = SystemFonts.system();
        for (String name : new String[]{"Segoe UI Emoji", "Noto Color Emoji", "Apple Color Emoji"}) {
            if (!system.hasFamily(name)) continue;
            for (SystemFonts.Face f : system.family(name)) {
                LoadedFace lf = LoadedFace.open(f);
                // COLR の書体だけ（v0 のレイヤーを持つ。Segoe UI Emoji は v0 と v1 の両方）。ビットマップ（CBDT）版の Noto Color Emoji
                // も色付きのビットマップを返すが、v0 との比較・大きさの上限（v1 だけ）の前提が無いので外す（CI の Ubuntu で赤になった）
                if (lf != null && lf.colorLayers(SMILE) != null && lf.colorBitmap(SMILE, 48f) != null) return f;
            }
        }
        Assumptions.abort("COLR v1 を持つ絵文字書体が無い環境");
        return null;
    }

    private static int distinctOpaqueColors(Pixmap pm) {
        Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < pm.height(); y++) {
            for (int x = 0; x < pm.width(); x++) {
                int c = pm.get(x, y);
                if ((c >>> 24) == 0xFF) colors.add(c);
            }
        }
        return colors.size();
    }

    @Test
    public void glyphIsRenderedWithGradients() {
        ColorBitmap b = LoadedFace.open(face()).colorBitmap(SMILE, 96f);
        assertNotNull(b);
        assertTrue(b.pixmap().width() > 40 && b.pixmap().height() > 40,
                "96px の絵文字は十分な大きさ: " + b.pixmap().width() + "x" + b.pixmap().height());
        int colors = distinctOpaqueColors(b.pixmap());
        assertTrue(colors > 30, "グラデーションで多くの色が出る（v0 の単色レイヤーなら数色）: " + colors);
        assertTrue(b.top() > 0, "上端は原点より上");
    }

    /** 不透明な画素の平均色 {@code [r, g, b]}。 */
    private static double[] meanColor(Pixmap pm) {
        double r = 0, g = 0, b = 0;
        int n = 0;
        for (int y = 0; y < pm.height(); y++) {
            for (int x = 0; x < pm.width(); x++) {
                int c = pm.get(x, y);
                if ((c >>> 24) != 0xFF) continue;
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
                n++;
            }
        }
        return new double[]{r / n, g / n, b / n};
    }

    @Test
    public void coloursAreTheDesignedOnesNotAScaleError() {
        // グラデーションの座標・半径は 16.16 固定小数点で返る。換算を誤ると顔が茶色・ハートが紫になる
        LoadedFace face = LoadedFace.open(face());
        double[] smile = meanColor(face.colorBitmap(SMILE, 96f).pixmap());
        assertTrue(smile[0] > 200 && smile[1] > 120 && smile[2] < 140, "笑顔は黄色系: " + java.util.Arrays.toString(smile));
        ColorBitmap heart = face.colorBitmap(0x2764, 96f);
        assertNotNull(heart);
        double[] red = meanColor(heart.pixmap());
        assertTrue(red[0] > 200 && red[1] < 110 && red[2] < 150, "ハートは赤系: " + java.util.Arrays.toString(red));
    }

    @Test
    public void extentMatchesTheV0Layers() {
        LoadedFace face = LoadedFace.open(face());
        float size = 80f;
        ColorBitmap v1 = face.colorBitmap(SMILE, size);
        ColorLayers layers = face.colorLayers(SMILE);
        assertNotNull(layers);
        int minL = Integer.MAX_VALUE, maxR = Integer.MIN_VALUE, minT = Integer.MAX_VALUE, maxB = Integer.MIN_VALUE;
        for (int g : layers.glyphs()) {
            GlyphBitmap bmp = face.rasterizeGlyphIndex(g, size, 0);
            if (bmp.isEmpty()) continue;
            minL = Math.min(minL, bmp.left());
            maxR = Math.max(maxR, bmp.left() + bmp.width());
            minT = Math.min(minT, -bmp.top());
            maxB = Math.max(maxB, -bmp.top() + bmp.height());
        }
        int w0 = maxR - minL, h0 = maxB - minT;
        assertEquals(w0, v1.pixmap().width(), w0 * 0.2, "幅が v0 の合成とほぼ同じ");
        assertEquals(h0, v1.pixmap().height(), h0 * 0.2, "高さが v0 の合成とほぼ同じ");
        assertEquals(minL, v1.left(), 0.1 * size, "左端の位置");
        assertEquals(-minT, v1.top(), 0.1 * size, "上端の位置");
    }

    @Test
    public void bitmapIsCachedAndPlainLettersHaveNone() {
        LoadedFace face = LoadedFace.open(face());
        assertSame(face.colorBitmap(SMILE, 64f), face.colorBitmap(SMILE, 64f));
        assertNotSame(face.colorBitmap(SMILE, 64f), face.colorBitmap(SMILE, 65f), "大きさごとに別");
        assertNull(face.colorBitmap('A', 64f));
        assertNull(face.colorBitmap(SMILE, 2000f), "大きすぎるときは描かない（v0 へ戻る）");
    }

    @Test
    public void pageRenderingShowsTheGradientEmoji() {
        String family = face().familyNames().iterator().next();
        String html = "<html><body style='margin:0;background:#fff'><div style='position:absolute;left:10px;top:10px'>"
                + "<span style=\"font-family:'" + family + "';font-size:70px;color:#000\">😀</span></div></body></html>";
        StandaloneView view = new StandaloneView(html, 160, 100);
        int[] px;
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(160, 100, 1f)) {
            view.frame(1000, backend, true);
            px = backend.pixmap().pixels().clone();
        } finally {
            view.close();
        }
        Set<Integer> colors = new HashSet<>();
        for (int p : px) if ((p >>> 24) == 0xFF && (p & 0xFFFFFF) != 0xFFFFFF) colors.add(p);
        assertTrue(colors.size() > 30, "ページに描いた絵文字にグラデーションの階調がある: " + colors.size());
    }

    @Test
    public void foregroundColor0xFFFFFollowsCssColorAndCachesPerColor() throws IOException {
        byte[] sfnt = buildColrV1ForegroundFont(SMILE);
        assertTrue(WebFonts.register("test-colrv1-fg", sfnt, 400, false));
        try {
            LoadedFace lf = LoadedFace.open(new SystemFonts.Face(
                    null, 0, Set.of("test-colrv1-fg"), "TestColrFg", 400, false, 5, new SystemFonts.MemoryFont(sfnt)));
            assertNotNull(lf);
            try {
                int gid = lf.glyphId(SMILE);
                assertEquals(1, gid, "SMILE -> gid 1");
                assertFalse(lf.glyphOutline(gid).isEmpty(), "gid 1 has non-empty outline");
                ColorBitmap red = lf.colorBitmap(SMILE, 40f, 0xFFE02030);
                ColorBitmap blue = lf.colorBitmap(SMILE, 40f, 0xFF1060F0);
                assertNotNull(red);
                assertNotNull(blue);
                assertSame(red, lf.colorBitmap(SMILE, 40f, 0xFFE02030), "同じ前景色はキャッシュを再利用");
                assertNotSame(red, blue, "異なる前景色ごとに別ビットマップをキャッシュ");
                double[] redMean = meanColor(red.pixmap());
                double[] blueMean = meanColor(blue.pixmap());
                assertTrue(redMean[0] > 180 && redMean[2] < 80, "赤系: " + java.util.Arrays.toString(redMean));
                assertTrue(blueMean[2] > 180 && blueMean[0] < 80, "青系: " + java.util.Arrays.toString(blueMean));
            } finally {
                lf.release();
            }

            int[] redPage = renderSnippet("<span style=\"font-family:'test-colrv1-fg';font-size:48px;color:#e02030\">😀</span>");
            int[] bluePage = renderSnippet("<span style=\"font-family:'test-colrv1-fg';font-size:48px;color:#1060f0\">😀</span>");
            assertHasDominantPixel(redPage, true, "ページ描画で赤前景色が反映される");
            assertHasDominantPixel(bluePage, false, "ページ描画で青前景色が反映される");
        } finally {
            WebFonts.clear();
        }
    }

    private static int[] renderSnippet(String body) {
        String html = "<html><body style='margin:0;background:#fff'><div style='position:absolute;left:10px;top:10px'>"
                + body + "</div></body></html>";
        StandaloneView view = new StandaloneView(html, 120, 80);
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(120, 80, 1f)) {
            view.frame(1000, backend, true);
            return backend.pixmap().pixels().clone();
        } finally {
            view.close();
        }
    }

    private static void assertHasDominantPixel(int[] px, boolean expectRed, String msg) {
        int hits = 0;
        for (int p : px) {
            int r = (p >> 16) & 0xFF, b = p & 0xFF;
            if (expectRed ? (r > 180 && b < 80) : (b > 180 && r < 80)) hits++;
        }
        assertTrue(hits > 50, msg + " (hits=" + hits + ")");
    }

    /** 単純な四角形の輪郭（gid=1）を COLR v1 PaintSolid(0xFFFF) で塗る最小 SFNT を作る。 */
    private static byte[] buildColrV1ForegroundFont(int cp) throws IOException {
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
        hh.writeShort(0); hh.writeShort(2);
        t.put("hhea", hhea.toByteArray());

        ByteArrayOutputStream maxp = new ByteArrayOutputStream();
        DataOutputStream mp = new DataOutputStream(maxp);
        mp.writeInt(0x00010000); mp.writeShort(2);
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

        t.put("hmtx", new byte[]{2, 0, 0, 0, 3, (byte) 0xE8, 0, 100});

        ByteArrayOutputStream cmap = new ByteArrayOutputStream();
        DataOutputStream cm = new DataOutputStream(cmap);
        cm.writeShort(0); cm.writeShort(1);
        cm.writeShort(3); cm.writeShort(10); cm.writeInt(12);
        cm.writeShort(12); cm.writeShort(0); cm.writeInt(28); cm.writeInt(0); cm.writeInt(1);
        cm.writeInt(cp); cm.writeInt(cp); cm.writeInt(1);
        t.put("cmap", cmap.toByteArray());

        ByteArrayOutputStream glyf = new ByteArrayOutputStream();
        DataOutputStream gl = new DataOutputStream(glyf);
        gl.writeShort(1); gl.writeShort(100); gl.writeShort(0); gl.writeShort(800); gl.writeShort(700);
        gl.writeShort(3); gl.writeShort(0);
        gl.write(new byte[]{1, 1, 1, 1});
        gl.writeShort(100); gl.writeShort(0); gl.writeShort(700); gl.writeShort(0);
        gl.writeShort(0); gl.writeShort(700); gl.writeShort(0); gl.writeShort(-700);
        t.put("glyf", glyf.toByteArray());
        t.put("loca", new byte[]{0, 0, 0, 0, 0, (byte) (glyf.size() / 2)});

        ByteArrayOutputStream post = new ByteArrayOutputStream();
        DataOutputStream ps = new DataOutputStream(post);
        ps.writeInt(0x00030000); ps.writeInt(0); ps.writeShort(0); ps.writeShort(0);
        for (int i = 0; i < 5; i++) ps.writeInt(0);
        t.put("post", post.toByteArray());

        byte[] nm = "TestColrFg".getBytes(StandardCharsets.UTF_16BE);
        ByteArrayOutputStream name = new ByteArrayOutputStream();
        DataOutputStream na = new DataOutputStream(name);
        na.writeShort(0); na.writeShort(1); na.writeShort(18);
        na.writeShort(3); na.writeShort(1); na.writeShort(0x0409); na.writeShort(1); na.writeShort(nm.length); na.writeShort(0);
        na.write(nm);
        t.put("name", name.toByteArray());

        // CPAL v0: 1 entry (黒)
        ByteArrayOutputStream cpal = new ByteArrayOutputStream();
        DataOutputStream cpOut = new DataOutputStream(cpal);
        cpOut.writeShort(0); cpOut.writeShort(1); cpOut.writeShort(1); cpOut.writeShort(1);
        cpOut.writeInt(14); cpOut.writeShort(0);
        cpOut.writeInt(0x000000FF);
        t.put("CPAL", cpal.toByteArray());

        // COLR v1: gid=1 -> PaintGlyph -> PaintSolid(0xFFFF, alpha=1.0)
        ByteArrayOutputStream colr = new ByteArrayOutputStream();
        DataOutputStream co = new DataOutputStream(colr);
        co.writeShort(1); co.writeShort(0); co.writeInt(0); co.writeShort(0); co.writeInt(0);
        co.writeInt(34); co.writeInt(0); co.writeInt(0); co.writeInt(0); co.writeInt(0); // header total = 34
        co.writeInt(1); co.writeShort(1); co.writeInt(10); // BaseGlyphList (10 bytes -> PaintGlyph at 44)
        co.writeByte(10); co.writeByte(0); co.writeByte(0); co.writeByte(6); co.writeShort(1); // PaintGlyph (6 bytes -> PaintSolid at 50)
        co.writeByte(2); co.writeShort(0xFFFF); co.writeShort(0x4000); // PaintSolid(foreground, 1.0)
        t.put("COLR", colr.toByteArray());

        int n = t.size(), dirEnd = 12 + n * 16;
        int[] offsets = new int[n], lengths = new int[n];
        byte[][] datas = new byte[n][];
        String[] tags = t.keySet().toArray(new String[0]);
        int pos = dirEnd;
        for (int i = 0; i < n; i++) {
            datas[i] = t.get(tags[i]);
            offsets[i] = pos;
            lengths[i] = datas[i].length;
            pos += (lengths[i] + 3) & ~3;
        }
        ByteArrayOutputStream sfnt = new ByteArrayOutputStream(pos);
        DataOutputStream out = new DataOutputStream(sfnt);
        out.writeInt(0x00010000); out.writeShort(n);
        int sr = Integer.highestOneBit(n) * 16;
        out.writeShort(sr); out.writeShort(Integer.numberOfTrailingZeros(sr / 16)); out.writeShort(n * 16 - sr);
        for (int i = 0; i < n; i++) {
            int padLen = (lengths[i] + 3) & ~3;
            byte[] pad = java.util.Arrays.copyOf(datas[i], padLen);
            datas[i] = pad;
            int sum = 0;
            for (int j = 0; j < padLen; j += 4) {
                sum += ((pad[j] & 0xFF) << 24) | ((pad[j + 1] & 0xFF) << 16) | ((pad[j + 2] & 0xFF) << 8) | (pad[j + 3] & 0xFF);
            }
            out.writeBytes(tags[i]);
            out.writeInt(sum);
            out.writeInt(offsets[i]);
            out.writeInt(lengths[i]);
        }
        for (int i = 0; i < n; i++) {
            out.write(datas[i]);
        }
        return sfnt.toByteArray();
    }
}
