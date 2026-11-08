package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 文字・画像の供給元を独立した持ち物にし、描画器どうしで共有できること。 */
class PaintResourcesTest {

    @Test
    void nullResetsToDefaultsAndSettersChain() {
        PaintResources r = new PaintResources();
        ImageProvider std = r.images();
        FontResolver sysFonts = r.fonts();
        assertNotNull(std);
        assertNotNull(sysFonts);
        ImageProvider custom = new ImageProvider() {
            @Override public Image load(String src, int w, int h) { return null; }
        };
        assertSame(r, r.images(custom));
        assertSame(custom, r.images());
        r.images(null);
        assertNotNull(r.images());
        assertNotSame(custom, r.images());
        FontResolver f = family -> null;
        r.fonts(f);
        assertSame(f, r.fonts());
        r.fonts(null);
        assertNotSame(f, r.fonts());
    }

    @Test
    void sharedResourcesReachTheRendererWithoutForwarding() {
        Pixmap pm = new Pixmap(4, 4);
        for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) pm.setStraight(x, y, 0xFF336699);
        PaintResources shared = new PaintResources();
        // 2 つの描画器が同じ供給元を共有する。供給元に 1 回書けば、どちらの描画にも効く
        SoftwareRenderBackend a = new SoftwareRenderBackend(60, 40, 1f, "res-a", shared);
        SoftwareRenderBackend b = new SoftwareRenderBackend(60, 40, 1f, "res-b", shared);
        List<DisplayItem> items = SoftwareRendering.displayList(
                "<html><body style='margin:0'><canvas style='width:40px;height:30px'></canvas></body></html>", 60, 40);
        a.render(items, 1000L);
        assertEquals(0, a.pixmap().get(20, 15) >>> 24, "供給元が無ければ canvas は空");
        shared.images(new ImageProvider() {
            @Override public Image load(String src, int w, int h) { return null; }
            @Override public Image load(DisplayItem it, int w, int h) {
                return it.type() == ElementType.CANVAS ? new Image(pm, 4, 4) : null;
            }
        });
        a.render(items, 1000L);
        b.render(items, 1000L);
        assertEquals(0xFF336699, a.pixmap().get(20, 15));
        assertEquals(0xFF336699, b.pixmap().get(20, 15));
    }

    @Test
    void ownResourcesAreIndependentAndNullIsRejected() {
        SoftwareRenderBackend a = new SoftwareRenderBackend(8, 8);
        SoftwareRenderBackend b = new SoftwareRenderBackend(8, 8);
        a.images(new ImageProvider() {
            @Override public Image load(String src, int w, int h) { return null; }
        });
        assertThrows(NullPointerException.class, () -> new SoftwareRenderBackend(8, 8, 1f, "x", null));
        assertNotNull(b.sessionId());
    }
}
