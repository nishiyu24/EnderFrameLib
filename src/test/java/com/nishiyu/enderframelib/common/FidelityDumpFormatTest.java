package com.nishiyu.enderframelib.common;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 忠実度ダンプの列形式のテスト。
 *
 * <p>計算値は {@code rgb(10, 20, 30)} や {@code matrix(1, 0, 0, 1, 0, 0)} のように
 * 空白を含む。空白を列区切りにすると値が途中で切れ、比較が「ブラウザ matrix(1,」対
 * 「エンジン matrix(1, 0, 0, 1, 0, 0)」のような誤った差になる（R0 で実際に踏んだ）。
 */
public class FidelityDumpFormatTest {

    @Test
    public void testRoundTripKeepsValuesWithSpaces() {
        String line = "a 1 2 3 4 0 sw=3 sh=4 cw=3 ch=4 display=block "
                + "color=\"rgb(10, 20, 30)\" transform=\"matrix(1, 0, 0, 1, 0, 0)\"";
        Map<String, FidelityDumper.Snapshot> parsed = FidelityDumper.parseDump(line);

        FidelityDumper.Snapshot snapshot = parsed.get("a");
        assertEquals("rgb(10, 20, 30)", snapshot.computedValue("color"));
        assertEquals("matrix(1, 0, 0, 1, 0, 0)", snapshot.computedValue("transform"));

        String formatted = FidelityDumper.format(parsed);
        FidelityDumper.Snapshot again = FidelityDumper.parseDump(formatted).get("a");
        assertEquals(snapshot.computed(), again.computed());
    }

    @Test
    public void testQuoteAndEscape() {
        assertEquals("\"rgb(10, 20, 30)\"", FidelityDumper.quoteValue("rgb(10, 20, 30)"));
        assertEquals("block", FidelityDumper.quoteValue("block"));
        assertEquals("\"a\\\"b\"", FidelityDumper.quoteValue("a\"b"));
        assertEquals("\"a\\\\b\"", FidelityDumper.quoteValue("a\\b"));

        List<String> fields = FidelityDumper.splitFields("k=\"a\\\"b\" plain");
        assertEquals(List.of("k=a\"b", "plain"), fields);
    }

    @Test
    public void testUnclosedQuoteIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> FidelityDumper.splitFields("color=\"rgb(10, 20, 30)"));
    }
}
