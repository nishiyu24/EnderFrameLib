package com.nishiyu.enderframelib.core.text;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UAX #9 による視覚順への並べ替え。 */
public class BidiTextTest {

    private static final String SHALOM = "שלום"; // שלום

    @Test
    void latinTextIsReturnedUntouched() {
        String text = "Hello, world";
        assertFalse(BidiText.hasRtl(text));
        assertSame(text, BidiText.toVisual(text, null));
        assertSame(text, BidiText.toVisual(text, true));
    }

    @Test
    void rtlRunIsReversedForLeftToRightDrawing() {
        assertTrue(BidiText.hasRtl(SHALOM));
        // 論理順 שלום は、左から描くとき ם ו ל ש の順
        assertEquals("םולש", BidiText.toVisual(SHALOM, null));
    }

    @Test
    void embeddedLatinKeepsItsDirectionInsideRtlText() {
        String logical = SHALOM + " abc " + SHALOM;
        String visual = BidiText.toVisual(logical, true);
        // 基底が右から左: 論理の最初の語が右端に来る。abc は左から右のまま
        String rev = new StringBuilder(SHALOM).reverse().toString();
        assertEquals(rev + " abc " + rev, visual);
    }

    @Test
    void baseDirectionDecidesWhereNeutralsGo() {
        String logical = "abc " + SHALOM;
        assertEquals("abc " + new StringBuilder(SHALOM).reverse(), BidiText.toVisual(logical, false));
        // 基底が右から左だと、論理の先頭の abc が右端になる
        assertEquals(new StringBuilder(SHALOM).reverse() + " abc", BidiText.toVisual(logical, true));
        // 向きを指定しない（最初の強い文字）: abc が先頭なので左から右
        assertEquals("abc " + new StringBuilder(SHALOM).reverse(), BidiText.toVisual(logical, null));
    }

    @Test
    void bracketsAreMirroredInsideRtlRuns() {
        String logical = "א(ב)"; // א(ב)
        assertEquals("(ב)א", BidiText.toVisual(logical, true));
    }

    @Test
    void digitsInsideRtlTextStayLeftToRight() {
        String logical = "א 123 ב";
        assertEquals("ב 123 א", BidiText.toVisual(logical, true));
    }
}
