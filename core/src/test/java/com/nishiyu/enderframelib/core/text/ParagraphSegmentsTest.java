package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontStyle;
import com.nishiyu.enderframelib.core.text.model.WordBreak;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 段落の下ごしらえ（{@link ParagraphSegments}）の値が、インライン整形・min-content が以前に断片ごとに測っていた値と
 * ビット単位で一致すること。同じ段落は同じインスタンスを返す（測り直さない）。
 */
class ParagraphSegmentsTest {

    private static final String[] PIECES = {"hello", "world", " ", "  ", "\t", "　", "日本語の文章", "、", "。", "-", "two-viewers",
            "A", "V", "AV", "To", "ff", "  trailing  ", "x y", "", " "};

    private static String randomText(Random r) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0, n = 1 + r.nextInt(14); i < n; i++) sb.append(PIECES[r.nextInt(PIECES.length)]);
        return sb.toString();
    }

    @Test
    void preparedValuesEqualWhatTheInlineFormatterMeasuredPerSegment() {
        FontFace face = FontFace.FALLBACK;
        Random r = new Random(77);
        for (int i = 0; i < 1500; i++) {
            String text = randomText(r);
            FontStyle font = new FontStyle(8 + r.nextInt(24), r.nextBoolean(), r.nextBoolean(), r.nextInt(3), 400, r.nextBoolean());
            WordBreak wb = WordBreak.values()[r.nextInt(WordBreak.values().length)];
            ParagraphSegments p = TextShaper.segmented(text, face, font, wb);
            List<String> segments = TextShaper.segments(text, wb);
            String at = "case " + i + " '" + text + "'";

            assertEquals(segments.size(), p.segs().length, at);
            String previous = null;
            for (int s = 0; s < segments.size(); s++) {
                String seg = segments.get(s);
                assertEquals(seg, p.segs()[s], at);
                if (seg.isEmpty()) continue;
                assertEquals(Float.floatToIntBits(face.advance(seg, font)), Float.floatToIntBits(p.fullW(s)), at + " full " + s);
                assertEquals(Float.floatToIntBits(face.advance(seg.stripTrailing(), font)),
                        Float.floatToIntBits(p.strippedTrailingW(s)), at + " stripTrailing " + s);
                String word = seg.strip();
                assertEquals(Float.floatToIntBits(word.isEmpty() ? 0f : face.advance(word, font)),
                        Float.floatToIntBits(p.strippedW(s)), at + " strip " + s);
                if (previous != null) {
                    assertEquals(Float.floatToIntBits(TextShaper.kerningBetween(face, font, previous, seg)),
                            Float.floatToIntBits(p.joinW(s)), at + " join " + s);
                }
                previous = seg;
            }
        }
    }

    @Test
    void theSameParagraphIsPreparedOnce() {
        FontFace face = FontFace.FALLBACK;
        FontStyle font = new FontStyle(16, false, false, 0, 400, true);
        ParagraphSegments a = TextShaper.segmented("some text to break up", face, font, WordBreak.NORMAL);
        ParagraphSegments b = TextShaper.segmented(new String("some text to break up"), face, font, WordBreak.NORMAL);
        assertSame(a, b);
        assertEquals(0, TextShaper.segmented("", face, font, WordBreak.NORMAL).segs().length);
    }
}
