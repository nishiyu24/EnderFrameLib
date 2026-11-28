package com.nishiyu.enderframelib.core.text.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link FontFace#fitChars}（M5-5 で旧 {@code TextShaperTest} から移した。描画の省略の切り詰めが使う）。 */
class FontFaceFitCharsTest {

    @Test
    void fitCharsStopsAtCodePointBoundaries() {
        FontFace face = FontFace.FALLBACK;
        FontStyle font = new FontStyle(16.0f, false, false, 0.0f);
        String text = "ab😀cd"; // 絵文字はサロゲートペア
        int fit = face.fitChars(text, font, face.advance("ab😀", font));
        assertEquals("ab😀", text.substring(0, fit), "サロゲートペアの途中で切っている");
    }
}
