package com.nishiyu.enderframelib.client.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * レンジの溝の枠（半透明）を溝・値の色に重ねた色が、Chrome を撮って読んだ画素と一致する（±1）。
 * 描画は重ねた色を先に合成して不透明で塗るので、この合成が Chrome と同じでなければならない。
 */
public class RangeTrackColourTest {

    private static void assertColour(int expected, int actual) {
        for (int shift = 0; shift <= 16; shift += 8) {
            int e = (expected >>> shift) & 0xFF, a = (actual >>> shift) & 0xFF;
            assertEquals(e, a, 1, String.format("#%06x と #%06x", expected & 0xFFFFFF, actual & 0xFFFFFF));
        }
        assertEquals(0xFF, actual >>> 24, "不透明");
    }

    @Test
    void borderOverTrackAndValueMatchesChromePixels() {
        assertColour(0xFFB2B2B2, DisplayItemDrawer.over(0x80767676, 0xFFEFEFEF));
        assertColour(0xFF3B75BA, DisplayItemDrawer.over(0x80767676, 0xFF0075FF));
        assertColour(0xFF858585, DisplayItemDrawer.over(0x80CFCFCF, 0xFF3B3B3B));
    }
}
