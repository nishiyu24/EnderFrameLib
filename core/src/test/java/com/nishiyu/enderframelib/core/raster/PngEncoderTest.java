package com.nishiyu.enderframelib.core.raster;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 独自の PNG エンコーダを、独立した復号器（ImageIO）で検証する。 */
class PngEncoderTest {

    @Test
    void roundTripsStraightArgbIncludingAlpha() throws Exception {
        int w = 37, h = 23; // 2 の冪でない寸法
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            int a = (i * 7) & 0xFF;
            argb[i] = (a << 24) | ((i * 31) & 0xFF) << 16 | ((i * 17) & 0xFF) << 8 | ((i * 5) & 0xFF);
        }
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(PngEncoder.encode(w, h, argb)));
        assertEquals(w, back.getWidth());
        assertEquals(h, back.getHeight());
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) assertEquals(argb[y * w + x], back.getRGB(x, y), x + "," + y);
    }

    @Test
    void encodesAOnePixelImage() throws Exception {
        BufferedImage back = ImageIO.read(new ByteArrayInputStream(PngEncoder.encode(1, 1, new int[]{0x80FF8040})));
        assertEquals(0x80FF8040, back.getRGB(0, 0));
    }
}
