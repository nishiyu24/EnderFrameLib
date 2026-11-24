package com.nishiyu.enderframelib.core.layout.fragment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * M2b-1: fieldset の描画される legend（HTML §15.3.13。{@link FieldsetLegend}）。legend は流れに入らず、縮小して合わせた幅で
 * 上の枠線にまたがり（{@code top = max(0, (枠線 − 高さ) / 2)}）、中身は枠線からはみ出した分だけ下がる。fieldset の表示型
 * （ブロック・flex・グリッド）に依らない。期待値は固定の大きさの箱から手で求めた数。
 */
class FieldsetLegendTest {
    private static final float EPS = 0.02f;
    private static final String FS = "margin:0;padding:0;border:4px solid;width:200px;";
    private static final String LG = "padding:0;margin:0;width:60px;height:20px;display:block";

    private static float[] at(String body, String id) {
        float[] r = SpikeView.find(new SpikeView("<html><body style='margin:0'>" + body + "</body></html>", 800, 600).frame(), id);
        assertNotNull(r, id);
        return r;
    }

    @Test
    void legendStraddlesTheTopBorderAndPushesContentDown() {
        String html = "<fieldset id=f style='" + FS + "'><legend id=l style='" + LG + "'></legend><div id=c style='height:10px'></div></fieldset>";
        // top = max(0, (4 − 20) / 2) = 0、はみ出し = 0 + 20 − 4 = 16 → 中身は 4 + 16 = 20 から
        assertEquals(0, at(html, "l")[1], EPS);
        assertEquals(4, at(html, "l")[0], EPS);
        assertEquals(20, at(html, "c")[1], EPS);
        assertEquals(4 + 16 + 10 + 4, at(html, "f")[3], EPS);
    }

    @Test
    void autoMarginsCenterTheLegendAndAutoWidthShrinksToFit() {
        String html = "<fieldset id=f style='" + FS + "'><legend id=l style='padding:0;margin:0 auto;display:block'>"
                + "<div style='width:50px;height:8px'></div></legend></fieldset>";
        assertEquals(50, at(html, "l")[2], EPS);
        assertEquals(4 + (200 - 50) / 2f, at(html, "l")[0], EPS); // 内容の幅 200（content-box）の中央
    }

    @Test
    void flexAndGridFieldsetsKeepTheLegendOutOfTheItems() {
        for (String d : new String[]{"display:flex;flex-direction:column", "display:grid"}) {
            String html = "<fieldset id=f style='" + FS + d + "'><legend id=l style='" + LG + "'></legend>"
                    + "<div id=a style='height:10px'></div><div id=b style='height:10px'></div></fieldset>";
            assertEquals(0, at(html, "l")[1], EPS);
            assertEquals(20, at(html, "a")[1], EPS);
            assertEquals(30, at(html, "b")[1], EPS);
        }
    }
}
