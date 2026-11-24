package com.nishiyu.enderframelib.wpt;

import com.nishiyu.enderframelib.common.SoftwareRendering;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2b-0 の一時アダプタ（{@code FragmentProjection}）の確認: 新経路と旧経路で幾何が一致する文書（M2a の {@code LegacyParityTest}）では、
 * 射影した {@code LayoutBox} の木を同じ {@code DisplayListBuilder} で描いた画素も旧経路と一致すること。
 */
class ProjectionPixelParityTest {

    @Test
    void blocksTextAndFlexRenderLikeLegacy() {
        String[] docs = {
            "<html><body><ul style='list-style:none;padding:0'><li style='padding:4px;border-bottom:1px solid #ccc'>Row item one with text</li>"
                    + "<li style='padding:4px;border-bottom:1px solid #ccc;background:#eef'>Row item two</li></ul></body></html>",
            "<html><body style='margin:0'><div style='width:200px;margin:0 auto;padding:3px;border:2px solid #900;background:#ffe'>centered box</div>"
                    + "<p style='width:120px;color:#036'>alpha beta gamma delta epsilon zeta eta theta</p></body></html>",
            "<html><body style='margin:0'><div style='display:flex;width:500px;background:#eee'><div style='flex-grow:1;background:#fcc'>a</div>"
                    + "<div style='flex-grow:2;padding:4px;background:#cfc'>bb</div><div style='width:100px;background:#ccf'>ccc</div></div></body></html>",
            // M2b-1: 混在の流れの行に置いた原子（inline-block）は、その行の位置に描く（中に文字を持つ原子を、射影が親の原点へ戻していた）
            "<html><body style='margin:0'><p style='margin:0;height:30px;background:#eee'>para</p>"
                    + "<div style='display:inline-block;width:40px;height:20px;background:#c00'>ab</div>"
                    + "<div style='display:inline-block;width:30px;height:10px;background:#00c'>c</div></body></html>",
        };
        for (String html : docs) {
            Pixmap legacy = legacy(html), v3 = WptV3BaselineTest.render(html);
            int diff = 0;
            for (int y = 0; y < WptReftestRatchetTest.H; y++) {
                for (int x = 0; x < WptReftestRatchetTest.W; x++) if (legacy.get(x, y) != v3.get(x, y)) diff++;
            }
            assertTrue(diff == 0, diff + " 画素が旧経路と違う: " + html);
        }
    }

    private static Pixmap legacy(String html) {
        SoftwareRenderBackend b = new SoftwareRenderBackend(WptReftestRatchetTest.W, WptReftestRatchetTest.H, 1f);
        b.render(SoftwareRendering.displayList(html, WptReftestRatchetTest.W, WptReftestRatchetTest.H, WptReftestRatchetTest.FONTS), WptReftestRatchetTest.T0);
        return b.pixmap();
    }
}
