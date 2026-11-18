package com.nishiyu.enderframelib.core.paint.software;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.Test;

/**
 * T2b: 大きなぼかし（σ ≥ 12 はボックスぼかし 3 回の近似）も、描画先の外を端の画素として読む（ガウスの経路・GPU の clamp-to-edge と同じ）。
 * 以前は近似の経路だけが描画先の外を透明として読み、画面の端に接する {@code backdrop-filter: blur(16px)} の固定ヘッダの上端・左端に
 * 明るいかすみが出て、GPU と違った（voidwave。最大差 87）。
 */
class BoxBlurEdgeTest {

    private static int maxChannelDiff(int a, int b) {
        int m = 0;
        for (int s = 0; s < 32; s += 8) m = Math.max(m, Math.abs(((a >>> s) & 0xFF) - ((b >>> s) & 0xFF)));
        return m;
    }

    private static void assertEdgeLikeInterior(int blurPx) {
        String html = "<html><body style='margin:0;background:#05060f'>"
                + "<div style='position:absolute;left:0;top:0;width:300px;height:60px;backdrop-filter:blur(" + blurPx + "px)'></div>"
                + "<div style='height:400px'></div></body></html>";
        try (SoftwareRenderBackend sw = SoftwareRendering.renderHtml(html, 320, 200, 10_000)) {
            Pixmap pm = sw.pixmap();
            int interior = pm.get(150, 30);
            int corner = pm.get(0, 0), top = pm.get(150, 0), left = pm.get(0, 30);
            // 背景は一様なので、ぼかしても端と中は同じ色（透明を混ぜると端が薄くなる）
            assertTrue(maxChannelDiff(interior, corner) <= 2, "blur(" + blurPx + "px) の左上 " + Integer.toHexString(corner) + " ≠ 中 " + Integer.toHexString(interior));
            assertTrue(maxChannelDiff(interior, top) <= 2, "上端 " + Integer.toHexString(top) + " ≠ 中 " + Integer.toHexString(interior));
            assertTrue(maxChannelDiff(interior, left) <= 2, "左端 " + Integer.toHexString(left) + " ≠ 中 " + Integer.toHexString(interior));
        }
    }

    @Test
    void largeBlurBackdropAtTheScreenEdgeHasNoHalo() {
        assertEdgeLikeInterior(16); // ボックスぼかしの近似の経路
    }

    @Test
    void smallBlurBackdropAtTheScreenEdgeHasNoHalo() {
        assertEdgeLikeInterior(6); // ガウスの経路（以前から端の画素で読んでいた）
    }
}
