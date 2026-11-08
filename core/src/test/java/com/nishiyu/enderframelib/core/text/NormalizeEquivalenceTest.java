package com.nishiyu.enderframelib.core.text;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.text.model.WhiteSpace;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link TextShaper#normalize} は正規表現による元の実装と全入力で同じ結果を返す（Matcher を作らない版）。 */
class NormalizeEquivalenceTest {

    @Test
    void matchesTheRegexImplementationOnRandomStrings() {
        char[] alphabet = {' ', ' ', ' ', '\t', '\n', '\r', 'a', 'b', '日', '\u00a0'};
        Random rnd = new Random(11);
        for (int i = 0; i < 60_000; i++) {
            int len = rnd.nextInt(14);
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < len; k++) sb.append(alphabet[rnd.nextInt(alphabet.length)]);
            String s = sb.toString();
            for (WhiteSpace ws : WhiteSpace.values()) {
                assertEquals(TextShaper.normalizeWithRegex(s, ws), TextShaper.normalize(s, ws), ws + " の入力: " + s.replace("\n", "\n").replace("\r", "\r").replace("\t", "\t"));
            }
        }
    }

    @Test
    void unchangedStringsAreReturnedAsTheSameInstance() {
        String s = "hello world";
        assertSame(s, TextShaper.normalize(s, WhiteSpace.NORMAL));
        assertEquals("", TextShaper.normalize("", WhiteSpace.NORMAL));
        assertEquals("", TextShaper.normalize(null, WhiteSpace.NORMAL));
    }

    @Test
    void allocatesLessThanTheRegexImplementation() {
        String text = "line one\n    line two\n\t\tline three  ";
        long fast = allocPerCall(() -> TextShaper.normalize(text, WhiteSpace.NORMAL));
        long regex = allocPerCall(() -> TextShaper.normalizeWithRegex(text, WhiteSpace.NORMAL));
        System.out.println("NORMALIZE alloc/call fast=" + fast + " B regex=" + regex + " B");
        assertTrue(fast * 2 < regex, "確保が半分未満: " + fast + " vs " + regex);
    }

    private static long allocPerCall(Runnable r) {
        for (int i = 0; i < 20_000; i++) r.run();
        com.sun.management.ThreadMXBean mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long id = Thread.currentThread().threadId();
        long before = mx.getThreadAllocatedBytes(id);
        int n = 100_000;
        for (int i = 0; i < n; i++) r.run();
        return (mx.getThreadAllocatedBytes(id) - before) / n;
    }
}
