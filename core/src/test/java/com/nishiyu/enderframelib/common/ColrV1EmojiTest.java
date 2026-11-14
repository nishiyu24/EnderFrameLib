package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
                if (lf != null && lf.colorBitmap(SMILE, 48f) != null) return f;
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
        LoadedFace.ColorBitmap b = LoadedFace.open(face()).colorBitmap(SMILE, 96f);
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
        LoadedFace.ColorBitmap heart = face.colorBitmap(0x2764, 96f);
        assertNotNull(heart);
        double[] red = meanColor(heart.pixmap());
        assertTrue(red[0] > 200 && red[1] < 110 && red[2] < 150, "ハートは赤系: " + java.util.Arrays.toString(red));
    }

    @Test
    public void extentMatchesTheV0Layers() {
        LoadedFace face = LoadedFace.open(face());
        float size = 80f;
        LoadedFace.ColorBitmap v1 = face.colorBitmap(SMILE, size);
        LoadedFace.ColorLayers layers = face.colorLayers(SMILE);
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
}
