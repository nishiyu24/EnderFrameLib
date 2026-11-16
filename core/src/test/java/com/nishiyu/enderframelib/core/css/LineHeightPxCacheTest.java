package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** B9: line-height の宣言の解釈結果を使い回しても、値は毎回読み直したときと同じ。 */
class LineHeightPxCacheTest {
    private static float px(String declared, float font) {
        return ValueComputer.lineHeightPx(declared, font, 16f, 800, 600);
    }

    @Test
    void repeatedCallsGiveSameValuesForEveryKindOfDeclaration() {
        for (int round = 0; round < 3; round++) { // 2 回目以降は使い回した結果
            assertEquals(1.5f * 20f, px("1.5", 20f), 1e-4);
            assertEquals(30f, px("30px", 20f), 1e-4);
            assertEquals(2f * 16f, px("2rem", 20f), 1e-4);
            assertEquals(20f * LengthResolver.NORMAL_LINE_HEIGHT_EM, px("normal", 20f), 1e-4);
            assertEquals(20f * LengthResolver.NORMAL_LINE_HEIGHT_EM, px(null, 20f), 1e-4);
            assertEquals(20f * LengthResolver.NORMAL_LINE_HEIGHT_EM, px("bogus value", 20f), 1e-4, "読めない値は normal");
            assertEquals(20f * LengthResolver.NORMAL_LINE_HEIGHT_EM, px("1 2", 20f), 1e-4, "単一の字句でない値は normal");
        }
    }

    @Test
    void sameDeclarationDependsOnFontSizeNotOnTheCache() {
        assertEquals(15f, px("1.5", 10f), 1e-4);
        assertEquals(30f, px("1.5", 20f), 1e-4);
    }
}
