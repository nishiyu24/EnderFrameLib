package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** S7 ②: {@code -webkit-text-stroke-width} の解釈は L3（{@link TextStroke}）にあり、L5（paint）は文字列を読まない。 */
class TextStrokeTest {

    @Test
    void keywordsMapToThinMediumThick() {
        assertEquals(1f, TextStroke.widthPx("thin", 16f, 16f));
        assertEquals(3f, TextStroke.widthPx(" MEDIUM ", 16f, 16f));
        assertEquals(5f, TextStroke.widthPx("thick", 16f, 16f));
    }

    @Test
    void lengthsResolveAgainstElementAndRootFontSize() {
        assertEquals(2f, TextStroke.widthPx("2px", 10f, 20f));
        assertEquals(2f, TextStroke.widthPx("2", 10f, 20f));
        assertEquals(20f, TextStroke.widthPx("2em", 10f, 20f));
        assertEquals(40f, TextStroke.widthPx("2rem", 10f, 20f));
    }

    @Test
    void absentInvalidOrNonPositiveIsZero() {
        assertEquals(0f, TextStroke.widthPx(null, 16f, 16f));
        assertEquals(0f, TextStroke.widthPx("abc", 16f, 16f));
        assertEquals(0f, TextStroke.widthPx("0px", 16f, 16f));
        assertEquals(0f, TextStroke.widthPx("-3px", 16f, 16f));
        assertEquals(0f, TextStroke.widthPx("NaN", 16f, 16f));
        assertEquals(0f, TextStroke.widthPx("Infinity", 16f, 16f));
    }

    @Test
    void paintLayerDoesNotParseStrokeWidthItself() throws Exception {
        Path root = Path.of("core/src/main/java");
        if (!Files.isDirectory(root)) root = Path.of("src/main/java");
        Path paint = root.resolve("com/nishiyu/enderframelib/core/paint");
        try (Stream<Path> files = Files.walk(paint)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(f);
                assertFalse(src.contains("WEBKIT_TEXT_STROKE_WIDTH"), f + " が text-stroke-width の文字列を直接読んでいる");
            }
        }
    }
}
