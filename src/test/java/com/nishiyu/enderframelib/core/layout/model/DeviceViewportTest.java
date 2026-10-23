package com.nishiyu.enderframelib.core.layout.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * ビューポート（CSS px）= 実ピクセル ÷ デバイスピクセル比（ブラウザの innerWidth と同じ）。
 *
 * 何が壊れていたか: 以前は 16:9 の画面なら常に 854×480 で、それを画面いっぱいに引き伸ばしていた。
 * 4K でも 854 CSS px しか入らず、大きな画面で一度に表示できる量が増えなかった。
 */
public class DeviceViewportTest {

    @Test
    void fullHdAtOsScale100IsBrowserSized() {
        var vp = LayoutMetrics.DeviceViewport.of(1920, 1080, LayoutMetrics.DeviceViewport.devicePixelRatio(1.0f, 100));
        assertEquals(1920, vp.cssWidth());
        assertEquals(1080, vp.cssHeight());
        assertEquals(1.0f, vp.devicePixelRatio());
    }

    @Test
    void fourKAtOsScale150() {
        var vp = LayoutMetrics.DeviceViewport.of(3840, 2160, LayoutMetrics.DeviceViewport.devicePixelRatio(1.5f, 100));
        assertEquals(2560, vp.cssWidth());
        assertEquals(1440, vp.cssHeight());
    }

    @Test
    void zoomBehavesLikeBrowserZoom() {
        float dpr = LayoutMetrics.DeviceViewport.devicePixelRatio(1.25f, 200);
        assertEquals(2.5f, dpr, 1e-6);
        var vp = LayoutMetrics.DeviceViewport.of(2560, 1440, dpr);
        assertEquals(1024, vp.cssWidth());
        assertEquals(576, vp.cssHeight());
    }

    @Test
    void largerWindowsShowMoreCssPixels() {
        float dpr = LayoutMetrics.DeviceViewport.devicePixelRatio(1.0f, 100);
        int small = LayoutMetrics.DeviceViewport.of(1280, 720, dpr).cssWidth();
        int large = LayoutMetrics.DeviceViewport.of(2560, 1440, dpr).cssWidth();
        assertEquals(2 * small, large);
    }

    @Test
    void invalidScaleFallsBackSafely() {
        assertEquals(1.0f, LayoutMetrics.DeviceViewport.devicePixelRatio(Float.NaN, 100));
        assertEquals(1.0f, LayoutMetrics.DeviceViewport.devicePixelRatio(0f, 100));
        var vp = LayoutMetrics.DeviceViewport.of(800, 600, 0f);
        assertEquals(LayoutMetrics.DeviceViewport.MIN_DEVICE_PIXEL_RATIO, vp.devicePixelRatio());
        assertEquals(3200, vp.cssWidth());
    }
}
