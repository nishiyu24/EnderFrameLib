package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;
import com.nishiyu.enderframelib.core.text.font.LoadedFace;
import com.nishiyu.enderframelib.core.text.font.SystemFonts;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * カラー絵文字（COLR v0 / CPAL）。絵文字の字形はレイヤーごとの色で下から重ねて描く。
 * 実際のカラー書体（Segoe UI Emoji / Noto Color Emoji / Apple Color Emoji）が無い環境では飛ばす。
 */
public class ColorEmojiTest {

    private static final int W = 160, H = 100;

    private static SystemFonts.Face colorFace() {
        SystemFonts system = SystemFonts.system();
        for (String name : new String[]{"Segoe UI Emoji", "Noto Color Emoji", "Apple Color Emoji"}) {
            if (!system.hasFamily(name)) continue;
            for (SystemFonts.Face f : system.family(name)) {
                LoadedFace lf = LoadedFace.open(f);
                if (lf != null && lf.colorLayers(0x1F600) != null) return f;
            }
        }
        Assumptions.abort("COLR 形式のカラー絵文字書体が無い環境");
        return null;
    }

    @Test
    public void colorGlyphHasSeveralLayersWithDistinctColors() {
        LoadedFace face = LoadedFace.open(colorFace());
        LoadedFace.ColorLayers layers = face.colorLayers(0x1F600);
        assertNotNull(layers);
        assertTrue(layers.count() >= 2, "顔の絵文字は複数のレイヤー: " + layers.count());
        Set<Integer> colors = new HashSet<>();
        for (int i = 0; i < layers.count(); i++) {
            if (!layers.foreground()[i]) colors.add(layers.argb()[i]);
        }
        assertTrue(colors.size() >= 2, "レイヤーは複数の色を持つ: " + colors);
        // レイヤーの字形は文字に結び付かないが、字形番号で描ける
        boolean anyInk = false;
        for (int g : layers.glyphs()) {
            GlyphBitmap bmp = face.rasterizeGlyphIndex(g, 40f, 0);
            if (!bmp.isEmpty()) anyInk = true;
        }
        assertTrue(anyInk, "レイヤーの字形が描ける");
    }

    @Test
    public void plainLettersAreNotColorGlyphs() {
        LoadedFace face = LoadedFace.open(colorFace());
        assertNull(face.colorLayers('A'));
        assertNull(face.colorLayers(0x20));
    }

    @Test
    public void emojiIsPaintedInColorNotInTextColor() {
        SystemFonts.Face face = colorFace();
        String family = face.familyNames().iterator().next();
        int[] px = shot("<span style=\"font-family:'" + family + "';font-size:60px;color:#000\">😀</span>");
        int chromatic = 0, ink = 0;
        for (int p : px) {
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (r < 250 || g < 250 || b < 250) ink++;
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 60) chromatic++;
        }
        assertTrue(ink > 200, "絵文字が描かれている: " + ink);
        assertTrue(chromatic > 200, "黒の文字色でも絵文字は彩色される: " + chromatic);
    }

    @Test
    public void textColorStillAppliesToPlainText() {
        int[] px = shot("<span style=\"font-size:60px;color:#000\">A</span>");
        int chromatic = 0, ink = 0;
        for (int p : px) {
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            if (r < 250) ink++;
            if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 8) chromatic++;
        }
        assertTrue(ink > 50, "文字が描かれている: " + ink);
        assertEquals(0, chromatic, "通常の文字はグレースケール");
    }

    private static int[] shot(String body) {
        String html = "<html><body style='margin:0;background:#fff'><div style='position:absolute;left:10px;top:10px'>"
                + body + "</div></body></html>";
        StandaloneView view = new StandaloneView(html, W, H);
        try (SoftwareRenderBackend backend = new SoftwareRenderBackend(W, H, 1f)) {
            view.frame(1000, backend, true);
            return backend.pixmap().pixels().clone();
        } finally {
            view.close();
        }
    }
}
