package com.nishiyu.enderframelib.core.text;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.text.model.WhiteSpace;
import java.util.Random;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** {@link WhiteSpaceProcessing#normalize} は正規表現による元の実装と全入力で同じ結果を返す（Matcher を作らない版）。 */
class WhiteSpaceProcessingTest {

    private static final Pattern LINE_BREAKS = Pattern.compile("[\\r\\n]+");
    private static final Pattern BLANK_RUN = Pattern.compile("[ \\t]+");

    /** 比較の基準: 正規表現による元の実装（旧 {@code TextShaper.normalizeWithRegex}。M5-5 で本体からテストへ移した）。 */
    static String normalizeWithRegex(String text, WhiteSpace whiteSpace) {
        if (text == null || text.isEmpty()) return "";
        String result = text.replace("\r\n", "\n").replace('\r', '\n');
        if (!whiteSpace.preservesNewlines()) result = LINE_BREAKS.matcher(result).replaceAll(" ");
        if (whiteSpace.collapses()) {
            result = result.replace("\t", "    ");
            result = BLANK_RUN.matcher(result).replaceAll(" ");
        }
        return result;
    }

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
                assertEquals(normalizeWithRegex(s, ws), WhiteSpaceProcessing.normalize(s, ws),
                        ws + " の入力: " + s.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"));
            }
        }
    }

    @Test
    void unchangedStringsAreReturnedAsTheSameInstance() {
        String s = "hello world";
        assertSame(s, WhiteSpaceProcessing.normalize(s, WhiteSpace.NORMAL));
        assertEquals("", WhiteSpaceProcessing.normalize("", WhiteSpace.NORMAL));
        assertEquals("", WhiteSpaceProcessing.normalize(null, WhiteSpace.NORMAL));
    }

    @Test
    void allocatesLessThanTheRegexImplementation() {
        String text = "line one\n    line two\n\t\tline three  ";
        long fast = allocPerCall(() -> WhiteSpaceProcessing.normalize(text, WhiteSpace.NORMAL));
        long regex = allocPerCall(() -> normalizeWithRegex(text, WhiteSpace.NORMAL));
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
