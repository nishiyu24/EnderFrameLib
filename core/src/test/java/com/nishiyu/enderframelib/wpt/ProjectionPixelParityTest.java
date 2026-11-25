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
            // M2b-1: flex の項目になった文字の節と、blockify したインライン要素（span）は項目の位置に描く（射影が容器の原点へ戻していた）
            "<html><body style='margin:0'><div style='display:flex;justify-content:space-between;align-items:flex-end;height:40px;width:300px;background:#eee'>"
                    + "x<span style='background:#fc0'>span</span>z</div></body></html>",
            // M2b-1: インライン要素の行ごとの箱（背景・枠線・角丸。行をまたぐ箱は端を切る）と、中身の無い枠線つきの箱
            "<html><body style='margin:0'><p style='width:160px;margin:0;line-height:22px'>aa <span style='padding:0 4px;border:2px solid #090;"
                    + "background:#cfc;border-radius:3px'>bb cc dd ee ff gg</span> hh <span style='border-left:12px solid #00c'></span>ii</p></body></html>",
            // M2b-1: リストの外側・内側のマーカー（番号・記号）と閉じた／開いた details の summary の ▸ / ▾
            "<html><body style='margin:0'><ul><li>disc</li><li style='list-style-type:square'>square</li></ul>"
                    + "<ol start=3><li>three</li><li value=9>nine</li><li style='list-style-position:inside'>inside</li></ol>"
                    + "<details><summary>closed</summary><p>hidden</p></details><details open><summary>open</summary><p>shown</p></details></body></html>",
            // M2b-1: スクロールコンテナのつまみの大きさ（到達範囲 ScrollableOverflow）
            "<html><body style='margin:0'><div style='overflow:auto;width:200px;height:80px;border:1px solid #888'>"
                    + "<p style='margin:4px'>one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen</p>"
                    + "<div style='width:320px;height:40px;background:#cde'></div></div></body></html>",
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
