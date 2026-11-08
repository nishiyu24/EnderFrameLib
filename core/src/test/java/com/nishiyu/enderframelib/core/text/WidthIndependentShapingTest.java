package com.nishiyu.enderframelib.core.text;

import com.nishiyu.enderframelib.core.text.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** 幅を変えて何度も整形しても、断片の分割と語ごとの測定は 1 回だけで、行の結果は幅ごとに正しい。 */
public class WidthIndependentShapingTest {

    private static final FontFace FACE = FontFace.FALLBACK;
    private static final FontStyle FONT = new FontStyle(16.0f, false, false, 0.0f);
    private static final TextStyle STYLE = new TextStyle(FONT, WhiteSpace.NORMAL, WordBreak.NORMAL, false, false, 20f);

    @Test
    public void resizingReusesTheParagraphPreparation() {
        String text = "unique-" + System.nanoTime() + " the quick brown fox jumps over the lazy dog again and again";
        int before = TextShaper.segmentedBuilds();
        for (int w = 40; w <= 600; w += 7) TextShaper.shape(text, FACE, STYLE, w);
        assertEquals(1, TextShaper.segmentedBuilds() - before, "幅を 80 通り変えても下ごしらえは 1 回");
    }

    @Test
    public void linesAreValidAtEveryWidth() {
        Random rnd = new Random(7);
        String[] words = {"alpha", "be", "gamma-delta", "x", "longerwordhere", "ij", "and", "ok."};
        for (int t = 0; t < 40; t++) {
            StringBuilder sb = new StringBuilder("w" + System.nanoTime() + "_" + t);
            for (int i = 0, n = 3 + rnd.nextInt(20); i < n; i++) sb.append(' ').append(words[rnd.nextInt(words.length)]);
            String text = sb.toString();
            float full = FACE.advance(text, FONT);
            List<ShapedLine> one = TextShaper.shape(text, FACE, STYLE, full + 1);
            assertEquals(1, one.size(), "全文が入る幅なら 1 行");
            assertEquals(text, one.get(0).text());
            for (int w = 10; w < full; w += 13) {
                List<ShapedLine> lines = TextShaper.shape(text, FACE, STYLE, w);
                StringBuilder joined = new StringBuilder();
                for (ShapedLine l : lines) {
                    joined.append(l.text());
                    boolean singleWord = l.text().indexOf(' ') < 0;
                    assertTrue(singleWord || l.width() <= w + TextShaper.WRAP_TOLERANCE + 0.01f,
                            "複数語の行は幅に収まる: " + l.text() + " w=" + w);
                }
                assertEquals(text.replace(" ", ""), joined.toString().replace(" ", ""), "行をつなぐと元の文（空白以外）");
                // 同じ幅の 2 回目は同じ結果
                assertEquals(lines, TextShaper.shape(text, FACE, STYLE, w));
            }
        }
    }
}
