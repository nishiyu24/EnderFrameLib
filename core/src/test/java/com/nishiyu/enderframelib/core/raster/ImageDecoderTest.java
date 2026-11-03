package com.nishiyu.enderframelib.core.raster;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** {@link ImageDecoder} の差し替え口: AWT を使わない復号器でも {@link GifAnimation} が合成できること。 */
class ImageDecoderTest {

    @Test
    void gifCompositionWorksWithACustomDecoder() {
        int red = 0xFFFF0000, blue = 0xFF0000FF;
        ImageDecoder fake = new ImageDecoder() {
            public Bitmap decode(byte[] b) { return null; }
            public GifStream decodeGif(byte[] b, int maxFrames, long maxPixels) {
                return new GifStream(2, 1, List.of(
                        new GifFrame(0, 0, 2, 1, new int[]{red, red}, 0, 0),
                        new GifFrame(1, 0, 1, 1, new int[]{blue}, 50, 0)));
            }
        };
        GifAnimation a = GifAnimation.decode("GIF89a....".getBytes(), fake);
        assertNotNull(a);
        assertEquals(2, a.frameCount());
        assertEquals(2, a.width());
        // 2 枚目は右の画素だけ青、左は前の赤が残る。0ms の遅延は 100ms に補正される
        int[] f1 = a.frame(1).pixels();
        assertNotEquals(a.frame(0).pixels()[1], f1[1]);
        assertEquals(a.frame(0).pixels()[0], f1[0]);
        assertEquals(150, a.durationMs());
    }

    @Test
    void decoderCanBeReplacedAndRestored() {
        ImageDecoder std = ImageDecoder.standard();
        ImageDecoder none = new ImageDecoder() {
            public Bitmap decode(byte[] b) { return null; }
            public GifStream decodeGif(byte[] b, int m, long p) { return null; }
        };
        try {
            ImageDecoder.use(none);
            assertSame(none, ImageDecoder.standard());
        } finally {
            ImageDecoder.use(std);
        }
        assertSame(std, ImageDecoder.standard());
    }
}
