package com.nishiyu.enderframelib.client.renderer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code accent-color} の状態ごとの濃淡が Chrome の実測と一致する。Chrome をリモート操作してマウスを乗せた状態・押した状態を撮り、
 * 選択済みのチェックボックスの塗り・ラジオの点・レンジのつまみ（どれも同じ色）を読んだ値。
 * 明るい配色（白と見分けられる色）はマウスを乗せると暗く・押すと明るく、暗い配色は逆。
 */
public class AccentShadeTest {

    private static final int HOVER = 0xFF005CC8, ACTIVE = 0xFF3793FF, IDLE = 0xFF0075FF;

    /** {色, マウスを乗せたとき, 押したとき, 暗い配色か}（チェックボックス・ラジオの配色） */
    private static final Object[][] CHROME = {
            {0xFFFF0000, 0xFFC70000, 0xFFFF3838, false},
            {0xFF00FF00, 0xFF38FF38, 0xFF00C700, true},
            {0xFF0000FF, 0xFF0000C7, 0xFF3838FF, false},
            {0xFFFF00FF, 0xFFC700C7, 0xFFFF38FF, false},
            {0xFFFFFF00, 0xFFFFFF38, 0xFFC7C700, true},
            {0xFF808080, 0xFF646464, 0xFF9C9C9C, false},
            {0xFF004080, 0xFF002448, 0xFF005CB8, false},
            {0xFFFF8000, 0xFFFF9C38, 0xFFC76400, true},
            {0xFF00C0FF, 0xFF38CEFF, 0xFF0096C7, true},
            {0xFFCCCCCC, 0xFFE8E8E8, 0xFFB0B0B0, true},
            {0xFF123456, 0xFF081828, 0xFF1C5084, false},
            {0xFFFFFFFF, 0xFFFFFFFF, 0xFFE3E3E3, true},
    };

    @Test
    void hoverAndActiveShadesMatchChrome() {
        for (Object[] row : CHROME) {
            int accent = (int) row[0];
            boolean dark = (boolean) row[3];
            String name = String.format("#%06x", accent & 0xFFFFFF);
            assertEquals(hex(accent), hex(DisplayListBuilder.shadeAccent(accent, IDLE, dark)), name + " 通常");
            assertEquals(hex((int) row[1]), hex(DisplayListBuilder.shadeAccent(accent, HOVER, dark)), name + " マウスを乗せたとき");
            assertEquals(hex((int) row[2]), hex(DisplayListBuilder.shadeAccent(accent, ACTIVE, dark)), name + " 押したとき");
        }
    }

    /** レンジは溝（#efefef）と比べるので、マゼンタは暗い配色になり向きが逆（Chrome の実測: 乗せると #ff38ff・押すと #c700c7）。 */
    @Test
    void magentaRangeShadesTheOtherWay() {
        assertEquals(hex(0xFFFF38FF), hex(DisplayListBuilder.shadeAccent(0xFFFF00FF, HOVER, true)));
        assertEquals(hex(0xFFC700C7), hex(DisplayListBuilder.shadeAccent(0xFFFF00FF, ACTIVE, true)));
    }

    private static String hex(int argb) {
        return String.format("#%08x", argb);
    }
}
