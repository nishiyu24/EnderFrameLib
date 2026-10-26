package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.paint.model.ImageFitMode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** srcset / sizes（HTML §4.8.4.3）・image-set()（CSS Images 4）の候補選びと object-fit / object-position。 */
class ImageSourcesTest {

    @Test
    void densityDescriptorsPickSmallestAtLeastTheDeviceRatioElseLargest() {
        String set = "a.png, b.png 2x, c.png 3x";
        assertEquals("a.png", ImageSources.selectSrcset(set, null, null, 800, 600, 1).url());
        assertEquals("b.png", ImageSources.selectSrcset(set, null, null, 800, 600, 1.5).url());
        assertEquals("b.png", ImageSources.selectSrcset(set, null, null, 800, 600, 2).url());
        assertEquals("c.png", ImageSources.selectSrcset(set, null, null, 800, 600, 2.01).url());
        assertEquals("c.png", ImageSources.selectSrcset(set, null, null, 800, 600, 9).url());
        assertEquals(3, ImageSources.selectSrcset(set, null, null, 800, 600, 9).density());
    }

    @Test
    void widthDescriptorsDivideBySourceSize() {
        String set = "s.png 320w, m.png 640w, l.png 1280w";
        // sizes 無しは 100vw = 640px
        assertEquals("m.png", ImageSources.selectSrcset(set, null, null, 640, 480, 1).url());
        assertEquals("l.png", ImageSources.selectSrcset(set, null, null, 640, 480, 2).url());
        assertEquals(2, ImageSources.selectSrcset(set, null, null, 640, 480, 2).density());
        // sizes の最初に合う条件
        String sizes = "(max-width: 400px) 320px, (min-width: 401px) 50vw, 100vw";
        assertEquals("s.png", ImageSources.selectSrcset(set, sizes, null, 400, 300, 1).url());
        assertEquals("m.png", ImageSources.selectSrcset(set, sizes, null, 1280, 300, 1).url());
    }

    @Test
    void sizesSupportCalcAndMediaCombinations() {
        assertEquals(500, ImageSources.sourceSize("calc(100vw - 100px)", 600, 400), 1e-9);
        assertEquals(200, ImageSources.sourceSize("(min-width: 500px) and (max-width: 800px) 200px, 50px", 600, 400), 1e-9);
        assertEquals(50, ImageSources.sourceSize("(min-width: 500px) and (max-width: 800px) 200px, 50px", 900, 400), 1e-9);
        assertEquals(600, ImageSources.sourceSize("bogus", 600, 400), 1e-9);
    }

    @Test
    void invalidSrcsetFallsBackToSrc() {
        assertEquals("fallback.png", ImageSources.selectSrcset("", null, "fallback.png", 100, 100, 1).url());
        assertEquals("fallback.png", ImageSources.selectSrcset("x.png 0x, y.png -1x", null, "fallback.png", 100, 100, 1).url());
        assertNull(ImageSources.selectSrcset("", null, "", 100, 100, 1));
    }

    @Test
    void urlsWithCommasKeepTheirDescriptor() {
        ImageChoice s = ImageSources.selectSrcset("data:image/png;base64,AAA= 2x, b.png 1x", null, null, 100, 100, 2);
        assertEquals("data:image/png;base64,AAA=", s.url());
    }

    @Test
    void forImgReadsAttributes() {
        assertEquals("p.png", ImageSources.forImg(Map.of("src", "p.png"), 100, 100).url());
        assertNull(ImageSources.forImg(Map.of(), 100, 100));
    }

    @Test
    void imageSetPicksByResolution() {
        String v = "image-set(url(a.png) 1x, url(\"b.png\") 2x, 'c.png' 192dpi)";
        assertEquals("a.png", ImageSources.selectImageSet(v, 1).url());
        assertEquals("b.png", ImageSources.selectImageSet(v, 1.5).url());
        // 192dpi = 2dppx。同じ密度は先のもの
        assertEquals("b.png", ImageSources.selectImageSet(v, 2).url());
        assertEquals(2, ImageSources.selectImageSet(v, 2).density());
        assertEquals("a.png", ImageSources.selectImageSet("-webkit-image-set(url(a.png) 1x)", 3).url());
        assertNull(ImageSources.selectImageSet("url(a.png)", 1));
        assertTrue(ImageSources.isImageSet(" IMAGE-SET(url(a.png))"));
    }

    // ------------------------------------------------------------------ ObjectFit

    @Test
    void objectFitSizes() {
        assertArrayEquals(new float[]{100, 50}, ObjectFit.objectSize(ImageFitMode.FILL, 20, 10, 100, 50), 1e-4f);
        assertArrayEquals(new float[]{100, 50}, ObjectFit.objectSize(ImageFitMode.CONTAIN, 20, 10, 100, 100), 1e-4f);
        assertArrayEquals(new float[]{200, 100}, ObjectFit.objectSize(ImageFitMode.COVER, 20, 10, 100, 100), 1e-4f);
        assertArrayEquals(new float[]{20, 10}, ObjectFit.objectSize(ImageFitMode.NONE, 20, 10, 100, 100), 1e-4f);
        assertArrayEquals(new float[]{20, 10}, ObjectFit.objectSize(ImageFitMode.SCALE_DOWN, 20, 10, 100, 100), 1e-4f);
        assertArrayEquals(new float[]{10, 5}, ObjectFit.objectSize(ImageFitMode.SCALE_DOWN, 20, 10, 10, 10), 1e-4f);
    }

    @Test
    void objectPositionDefaultsToCenterAndSupportsKeywordsLengthsAndPercents() {
        ObjectFit.Placement c = ObjectFit.place(ImageFitMode.NONE, null, 20, 10, 100, 100, 16);
        assertEquals(40, c.x(), 1e-4);
        assertEquals(45, c.y(), 1e-4);
        ObjectFit.Placement lt = ObjectFit.place(ImageFitMode.NONE, "left top", 20, 10, 100, 100, 16);
        assertEquals(0, lt.x(), 1e-4);
        assertEquals(0, lt.y(), 1e-4);
        ObjectFit.Placement rb = ObjectFit.place(ImageFitMode.NONE, "bottom right", 20, 10, 100, 100, 16);
        assertEquals(80, rb.x(), 1e-4);
        assertEquals(90, rb.y(), 1e-4);
        ObjectFit.Placement mixed = ObjectFit.place(ImageFitMode.NONE, "25% 10px", 20, 10, 100, 100, 16);
        assertEquals(20, mixed.x(), 1e-4);
        assertEquals(10, mixed.y(), 1e-4);
        ObjectFit.Placement offset = ObjectFit.place(ImageFitMode.NONE, "right 5px bottom 3px", 20, 10, 100, 100, 16);
        assertEquals(75, offset.x(), 1e-4);
        assertEquals(87, offset.y(), 1e-4);
        ObjectFit.Placement cover = ObjectFit.place(ImageFitMode.COVER, "left top", 20, 10, 100, 100, 16);
        assertEquals(0, cover.x(), 1e-4);
        assertEquals(200, cover.w(), 1e-4);
        ObjectFit.Placement bogus = ObjectFit.place(ImageFitMode.NONE, "wat", 20, 10, 100, 100, 16);
        assertEquals(40, bogus.x(), 1e-4);
    }
}
