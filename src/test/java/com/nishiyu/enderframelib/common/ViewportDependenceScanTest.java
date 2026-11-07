package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Matcher を作らない走査が、正規表現と同じ判定をすること。 */
class ViewportDependenceScanTest {

    private static final Pattern REGEX = Pattern.compile(
            "\\d(?:[sld]?v(?:w|h|i|b|min|max)|cq(?:w|h|i|b|min|max))(?![a-z])|var\\(|env\\(", Pattern.CASE_INSENSITIVE);

    @Test
    void knownValues() {
        for (String yes : new String[]{"50vw", "10VH", "3svh", "2dvmin", "1lvmax", "4cqw", "var(--x)", "calc(100vh - 2px)",
                "VAR(--a)", "env(safe-area-inset-top)", "1vi", "7vb"}) {
            assertTrue(RenderPipeline.dependsOnViewportValue(yes), yes);
        }
        for (String no : new String[]{"10px", "red", "5vwx", "2vminx", "vw", "10 vw", "1em", "3cqx", "rgb(1,2,3)", ""}) {
            assertFalse(RenderPipeline.dependsOnViewportValue(no), no);
        }
    }

    @Test
    void matchesRegexOnRandomStrings() {
        String[] atoms = {"1", "20", "v", "w", "h", "min", "max", "i", "b", "s", "l", "d", "cq", "var(", "env(", "(", ")",
                " ", "px", "x", "V", "W", "-", ".", "a", "calc", "VAR", "Env"};
        Random r = new Random(99);
        for (int n = 0; n < 300_000; n++) {
            StringBuilder sb = new StringBuilder();
            for (int k = r.nextInt(7); k >= 0; k--) sb.append(atoms[r.nextInt(atoms.length)]);
            String s = sb.toString();
            assertEquals(REGEX.matcher(s).find(), RenderPipeline.dependsOnViewportValue(s), "'" + s + "'");
        }
    }
}
