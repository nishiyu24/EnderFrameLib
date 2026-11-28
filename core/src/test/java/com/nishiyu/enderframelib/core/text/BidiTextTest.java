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

    /**
     * M5-5: {@link BidiText#hasRtl} は文字列を作業用の配列（以前は {@code ThreadLocal}）へ写さずに字ごとに判定する。
     * 全ての字（未割り当て・代用対の片方だけを含む）で {@link java.text.Bidi#requiresBidi} と同じ答えになること。
     */
    @Test
    void hasRtlMatchesRequiresBidiForEveryCodePoint() {
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (int cp = 0; cp <= Character.MAX_CODE_POINT; cp++) {
            char[] one = Character.toChars(cp);
            String s = new String(one);
            boolean expected = java.text.Bidi.requiresBidi(one, 0, one.length);
            // 前後に判定を飛ばす字（ASCII・かな）を置いても同じ
            if (BidiText.hasRtl(s) != expected || BidiText.hasRtl("a" + s + "あ") != expected) {
                bad.add(Integer.toHexString(cp));
                if (bad.size() > 20) break;
            }
        }
        assertEquals(java.util.List.of(), bad, "requiresBidi と答えが違う字");
    }

    @Test
    void hasRtlMatchesRequiresBidiOnMixedStrings() {
        int[] pool = {'a', ' ', '1', 0x05D0, 0x0627, 0x0661, 0x2014, 0x201C, 0x202B, 0x202E, 0x2067, 0x200F, 0x200E,
                0xD800, 0xDC00, 0x1F600, 0x10900, 0x08A0, 0xFB1D, 0xFEFC, 0x3042, 0x05FF};
        java.util.Random rnd = new java.util.Random(9);
        for (int i = 0; i < 50_000; i++) {
            StringBuilder sb = new StringBuilder();
            int len = rnd.nextInt(8);
            for (int k = 0; k < len; k++) sb.appendCodePoint(pool[rnd.nextInt(pool.length)]);
            String s = sb.toString();
            char[] chars = s.toCharArray();
            assertEquals(java.text.Bidi.requiresBidi(chars, 0, chars.length), BidiText.hasRtl(s), () -> "入力: " + s.codePoints()
                    .mapToObj(Integer::toHexString).toList());
        }
    }
}
