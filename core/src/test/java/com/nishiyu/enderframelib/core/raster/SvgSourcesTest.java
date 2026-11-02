package com.nishiyu.enderframelib.core.raster;

import com.nishiyu.enderframelib.core.raster.svg.SvgImage;
import com.nishiyu.enderframelib.core.raster.svg.SvgSources;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SvgSourcesTest {

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"20\" height=\"10\"><rect width=\"20\" height=\"10\"/></svg>";

    @AfterEach void reset() { SvgSources.setResourceReader(null); SvgSources.setHttpSource(null); }

    @Test
    @DisplayName("リソースは読み手から 1 回だけ読んで覚え、固有サイズを返す")
    void resourceIsReadOnceAndCached() {
        AtomicInteger reads = new AtomicInteger();
        SvgSources.setResourceReader((n, max) -> { reads.incrementAndGet(); return SVG.getBytes(StandardCharsets.UTF_8); });
        assertArrayEquals(new int[]{20, 10}, SvgSources.intrinsicSize("ns:icons/a.svg"));
        assertNotNull(SvgSources.get("ns:icons/a.svg"));
        assertEquals(1, reads.get());
    }

    @Test
    @DisplayName("読めない・壊れた資産は null で、再読み込みもしない")
    void missingAndBrokenAreNull() {
        AtomicInteger reads = new AtomicInteger();
        SvgSources.setResourceReader((n, max) -> {
            reads.incrementAndGet();
            if (n.equals("ns:bad.svg")) throw new IOException("boom");
            return null;
        });
        assertNull(SvgSources.get("ns:none.svg"));
        assertNull(SvgSources.get("ns:none.svg"));
        assertNull(SvgSources.get("ns:bad.svg"));
        assertEquals(2, reads.get());
        assertNull(SvgSources.intrinsicSize("ns:none.svg"));
    }

    @Test
    @DisplayName("http(s) は差し込んだ取得元へ、空は null")
    void httpGoesToHttpSource() {
        SvgImage img = SvgImage.parse(SVG);
        SvgSources.setHttpSource(u -> u.equals("https://e.test/a.svg") ? img : null);
        assertSame(img, SvgSources.get("https://e.test/a.svg"));
        assertNull(SvgSources.get("http://e.test/other.svg"));
        assertNull(SvgSources.get(""));
        assertNull(SvgSources.get(null));
    }
}
