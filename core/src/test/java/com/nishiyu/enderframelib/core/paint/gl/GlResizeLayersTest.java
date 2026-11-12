package com.nishiyu.enderframelib.core.paint.gl;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@link LwjglGlRenderBackend#resize} の後に、グループ（opacity・clip）の層が新しい大きさで描かれるか。
 * 層の FBO は作成時の大きさのまま残ると、広げた分が白く抜ける（ウィンドウを広げたとき、カードが空白になった）。
 */
class GlResizeLayersTest {
    private static final long T0 = 10_000L;

    private static String page(int w, int h) {
        return "<html><body style='margin:0'>"
                + "<div style='width:" + w + "px;height:" + h + "px;opacity:.5;background:#0066cc'>"
                + "<div style='margin:10px;width:" + (w - 20) + "px;height:" + (h - 20) + "px;background:#ffcc00'><div style='width:" + (w - 20) + "px;height:" + (h - 20) + "px;background:#33aa33;opacity:.8'></div></div></div>"
                + "<div style='position:absolute;left:0;top:0;width:" + w + "px;height:" + h + "px;overflow:hidden;border-radius:6px'>"
                + "<div style='position:absolute;right:0;bottom:0;width:20px;height:20px;background:#cc3300'></div></div>"
                + "</body></html>";
    }

    @Test
    void groupsCoverTheWholeSurfaceAfterGrowing() {
        LwjglGlRenderBackend g;
        try {
            g = new LwjglGlRenderBackend(100, 80, 1f);
        } catch (Throwable t) {
            Assumptions.abort("OpenGL を使えない: " + t);
            return;
        }
        try (g) {
            SoftwareRendering.renderHtml(page(100, 80), 100, 80, T0, g);
            g.resize(300, 240);
            SoftwareRendering.renderHtml(page(300, 240), 300, 240, T0, g);
            Pixmap px = g.pixmap();
            var cpu = SoftwareRendering.renderHtml(page(300, 240), 300, 240, 1f, T0).pixmap();
            // 広げた側（旧い大きさの外）の角が、CPU 版と同じ色で描かれている
            int[][] probes = {{250, 200}, {299 - 5, 239 - 5}, {150, 230}, {290, 100}};
            for (int[] p : probes) {
                int a = px.get(p[0], p[1]), b = cpu.get(p[0], p[1]);
                for (int s = 0; s <= 24; s += 8) {
                    assertTrue(Math.abs(((a >>> s) & 0xFF) - ((b >>> s) & 0xFF)) <= 3,
                            "(" + p[0] + "," + p[1] + ") gpu=" + Integer.toHexString(a) + " cpu=" + Integer.toHexString(b));
                }
            }
        }
    }
}
