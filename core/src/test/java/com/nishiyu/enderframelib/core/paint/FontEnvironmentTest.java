package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 画素のゴールデンの書体の環境の指紋（{@link FontEnvironment}）。CI の機械と記録した機械で書体が違うときだけ画素の比較を飛ばす。 */
class FontEnvironmentTest {

    @Test
    void fingerprintIsStableWithinARun() {
        String a = FontEnvironment.fingerprint();
        assertFalse(a.isBlank());
        assertEquals(a, FontEnvironment.fingerprint());
    }

    @Test
    void comparesPixelsOnlyWhenTheRecordedEnvironmentMatches() {
        String now = FontEnvironment.fingerprint();
        assertTrue(FontEnvironment.pixelsComparable("# head\n" + FontEnvironment.HEADER + now + "\nx\t1\n", "t"), "同じ環境なら比べる");
        assertFalse(FontEnvironment.pixelsComparable(FontEnvironment.HEADER + "deadbeef-1\nx\t1\n", "t"), "違う環境なら比べない");
        assertTrue(FontEnvironment.pixelsComparable("x\t1\n", "t"), "指紋の無い古いファイルは従来どおり比べる");
        assertTrue(FontEnvironment.pixelsComparable(null, "t"));
        assertEquals("abc-3", FontEnvironment.recorded("# c\n" + FontEnvironment.HEADER + "abc-3\n"));
    }
}
