package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** B9 M3b: 前の項目の属性と同じ値を入れる限り属性の表を作らず、違えば従来どおりの表になる。 */
class ItemPropsAttrsReuseTest {

    private static Map<String, String> prev() {
        Map<String, String> m = new HashMap<>();
        m.put("onclick", "go");
        m.put("data-x", "1");
        return m;
    }

    @Test
    void sameValuesReturnThePreviousMapWithoutBuildingOne() {
        Map<String, String> prev = prev();
        ItemProps p = new ItemProps();
        p.reuseAttrs(prev);
        p.put("onclick", "go");
        p.put("data-x", "1");
        assertTrue(p.containsKey("onclick"));
        assertEquals("1", p.get("data-x"));
        assertNull(p.get("data-y"));
        assertSame(prev, p.attrs());
    }

    @Test
    void anyDifferenceBuildsAnEqualToWhatWasPut() {
        Map<String, String> prev = prev();
        for (String[][] puts : new String[][][]{
                {{"onclick", "go"}},                                  // 一部だけ（前より少ない）
                {{"onclick", "go"}, {"data-x", "2"}},                 // 値が違う
                {{"onclick", "go"}, {"data-x", "1"}, {"extra", "e"}}, // 前に無い鍵が増える
                {{"other", "o"}},                                     // 全部違う
        }) {
            ItemProps p = new ItemProps();
            p.reuseAttrs(prev);
            Map<String, String> expected = new HashMap<>();
            for (String[] kv : puts) {
                p.put(kv[0], kv[1]);
                expected.put(kv[0], kv[1]);
            }
            assertEquals(expected, p.attrs(), java.util.Arrays.deepToString(puts));
            for (var e : expected.entrySet()) {
                assertTrue(p.containsKey(e.getKey()));
                assertEquals(e.getValue(), p.get(e.getKey()));
            }
        }
        assertEquals(prev(), prev, "前の表は書き換えない");
    }

    @Test
    void noCandidateOrReusedPropsBehaveAsBefore() {
        ItemProps p = new ItemProps();
        p.put("a", "1");
        assertEquals(Map.of("a", "1"), p.attrs());
        p.reuseAttrs(prev());
        p.reset();
        p.put("onclick", "go");
        assertEquals(Map.of("onclick", "go"), p.attrs(), "reset は候補を捨てる（前の表を返さない）");
        assertEquals(Map.of(), new ItemProps().attrs());
    }

    @Test
    void copyAndPutAfterPendingKeepEverything() {
        Map<String, String> prev = prev();
        ItemProps p = new ItemProps();
        p.reuseAttrs(prev);
        p.put("onclick", "go");
        ItemProps c = p.copy();
        assertEquals(Map.of("onclick", "go"), c.attrs());
        p.put("data-x", "9"); // 候補と違う値 → 表を作り、先に入れた分も残る
        assertEquals(Map.of("onclick", "go", "data-x", "9"), p.attrs());
        ItemProps q = new ItemProps();
        q.reuseAttrs(prev);
        q.put("onclick", "go");
        ItemProps w = new ItemProps();
        w.copyFrom(q);
        assertEquals(Map.of("onclick", "go"), w.attrs());
    }

    @Test
    void putBaseAttrsAfterPendingMerges() {
        ItemProps p = new ItemProps();
        p.reuseAttrs(prev());
        p.put("onclick", "go");
        p.putBaseAttrs(Map.of("data-z", "z"));
        assertEquals(Map.of("onclick", "go", "data-z", "z"), p.attrs());
    }

    @Test
    void baseAttrsAreComparedWithThePreviousMapToo() {
        Map<String, String> prev = new HashMap<>();
        prev.put("font", "serif");
        prev.put("onclick", "go");
        // 基底の表（書体）＋ put（onclick）が前の表と同じ → 前の表そのもの
        ItemProps p = new ItemProps();
        p.reuseAttrs(prev);
        p.putBaseAttrs(Map.of("font", "serif"));
        p.put("onclick", "go");
        assertTrue(p.containsKey("font") && p.containsKey("onclick"));
        assertEquals("serif", p.get("font"));
        assertSame(prev, p.attrs());
        // 基底の値が違う → 作る（前の表を返さない）
        ItemProps q = new ItemProps();
        q.reuseAttrs(prev);
        q.putBaseAttrs(Map.of("font", "mono"));
        q.put("onclick", "go");
        assertEquals(Map.of("font", "mono", "onclick", "go"), q.attrs());
        // 基底に前に無い鍵がある → 数が合わず作る
        ItemProps r = new ItemProps();
        r.reuseAttrs(prev);
        r.putBaseAttrs(Map.of("font", "serif", "extra", "x"));
        r.put("onclick", "go");
        assertEquals(Map.of("font", "serif", "extra", "x", "onclick", "go"), r.attrs());
        // put が基底の鍵を上書きする
        ItemProps t = new ItemProps();
        t.reuseAttrs(prev);
        t.putBaseAttrs(Map.of("font", "mono", "onclick", "no"));
        t.put("font", "serif");
        t.put("onclick", "go");
        assertSame(prev, t.attrs());
    }
}
