package com.nishiyu.enderframelib.core.css;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** ComputedStyle の添字配列＋カスタムプロパティ用マップという内部表現が、マップ API と同じに振る舞うこと。 */
class ComputedStyleStorageTest {

    private static Map<String, String> sample() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("color", "red");
        m.put("display", "block");
        m.put("--accent", "blue");
        m.put("x-unknown-prop", "1");
        return m;
    }

    @Test
    void readsRegisteredCustomAndUnknownProperties() {
        ComputedStyle s = new ComputedStyle(sample());
        assertEquals("red", s.get("color"));
        assertEquals("block", s.get("display"));
        assertEquals("blue", s.get("--accent"));
        assertEquals("1", s.get("x-unknown-prop"));
        assertNull(s.get("width"));
        assertTrue(s.containsKey("color"));
        assertFalse(s.containsKey("width"));
        assertEquals("d", s.getOrDefault("width", "d"));
        assertEquals(4, s.asMap().size());
        assertEquals(Map.of("--accent", "blue"), s.customProperties());
    }

    @Test
    void viewEqualsAndIteratesLikeAMap() {
        Map<String, String> src = sample();
        ComputedStyle a = new ComputedStyle(src);
        ComputedStyle b = new ComputedStyle(new LinkedHashMap<>(src));
        assertEquals(a.asMap(), b.asMap());
        assertEquals(a.asMap().hashCode(), b.asMap().hashCode());
        assertEquals(src, a.asMap());
        assertEquals(a.asMap(), src);
        assertEquals(src.keySet(), a.asMap().keySet());

        Map<String, String> other = sample();
        other.put("color", "green");
        assertNotEquals(a.asMap(), new ComputedStyle(other).asMap());
        other.remove("color");
        assertNotEquals(a.asMap(), new ComputedStyle(other).asMap());
    }

    @Test
    void copyFromAnotherStyleIsIndependent() {
        ComputedStyle a = new ComputedStyle(sample());
        ComputedStyle copy = new ComputedStyle(a.asMap(), 20f, 16f);
        assertEquals(a.asMap(), copy.asMap());
        assertEquals(20f, copy.fontSizePx());
        Map<String, String> edited = new LinkedHashMap<>(copy.asMap());
        edited.put("color", "black");
        assertEquals("red", copy.get("color"));
        assertEquals("black", new ComputedStyle(edited).get("color"));
    }

    @Test
    void viewIsReadOnly() {
        ComputedStyle s = new ComputedStyle(sample());
        assertThrows(UnsupportedOperationException.class, () -> s.asMap().put("color", "x"));
        assertThrows(UnsupportedOperationException.class, () -> s.asMap().remove("color"));
    }
}
