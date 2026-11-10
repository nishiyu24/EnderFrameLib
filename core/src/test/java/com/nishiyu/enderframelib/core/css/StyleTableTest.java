package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Milestone 2: カスケード・算出の作業表（String[] 裏打ちの Map）が、LinkedHashMap と同じ Map として振る舞い、算出スタイルへ変換できる。 */
class StyleTableTest {
    private static List<String> names() {
        List<String> n = new ArrayList<>();
        for (PropertyRegistry.Property p : PropertyRegistry.all()) n.add(p.name());
        n.add("--a");
        n.add("--b");
        n.add("--c");
        n.add("made-up-prop");
        return n;
    }

    @Test
    void behavesLikeAMapUnderRandomOperations() {
        List<String> names = names();
        Random rnd = new Random(20261114);
        for (int round = 0; round < 300; round++) {
            StyleTable t = new StyleTable();
            Map<String, String> model = new HashMap<>();
            for (int i = 0; i < 200; i++) {
                String k = names.get(rnd.nextInt(names.size()));
                switch (rnd.nextInt(4)) {
                    case 0, 1 -> assertEquals(model.put(k, "v" + i), t.put(k, "v" + i));
                    case 2 -> assertEquals(model.remove(k), t.remove(k));
                    default -> {
                        assertEquals(model.get(k), t.get(k));
                        assertEquals(model.containsKey(k), t.containsKey(k));
                    }
                }
                assertEquals(model.size(), t.size());
            }
            assertEquals(model, new HashMap<>(t));
            assertEquals(model, t);
            // 走査: 登録順 → 添字なしの宣言。件数は size と一致
            int seen = 0;
            for (Map.Entry<String, String> e : t.entrySet()) {
                assertEquals(model.get(e.getKey()), e.getValue());
                seen++;
            }
            assertEquals(t.size(), seen);
        }
    }

    @Test
    void iteratorRemoveAndRemoveIfKeepSizeConsistent() {
        StyleTable t = new StyleTable();
        t.put("color", "red");
        t.put("width", "1px");
        t.put("--x", "1");
        t.put("--y", "2");
        t.put("made-up-prop", "z");
        t.keySet().removeIf(k -> !k.startsWith("--") && !"color".equals(k));
        assertEquals(Map.of("color", "red", "--x", "1", "--y", "2"), new HashMap<>(t));
        assertEquals(3, t.size());
        Iterator<Map.Entry<String, String>> it = t.entrySet().iterator();
        while (it.hasNext()) {
            it.next();
            it.remove();
        }
        assertTrue(t.isEmpty());
        assertEquals(0, t.size());
    }

    @Test
    void nullValueMeansNoDeclaration() {
        StyleTable t = new StyleTable();
        t.put("color", "red");
        t.put("color", null);
        t.put("--v", "1");
        t.put("--v", null);
        assertTrue(t.isEmpty());
        assertFalse(t.containsKey("color"));
    }

    @Test
    void copyIsIndependent() {
        StyleTable a = new StyleTable();
        a.put("color", "red");
        a.put("--v", "1");
        StyleTable b = StyleTable.copyOf(a);
        b.put("color", "blue");
        b.put("--v", "2");
        b.put("width", "3px");
        assertEquals("red", a.get("color"));
        assertEquals("1", a.get("--v"));
        assertNull(a.get("width"));
        assertEquals(2, a.size());
        assertEquals(3, b.size());
        StyleTable c = StyleTable.copyOf(new LinkedHashMap<>(Map.of("color", "x")));
        assertEquals("x", c.get("color"));
    }

    @Test
    void putIfAbsentByIdOnlyFillsAnEmptySlot() {
        StyleTable t = new StyleTable();
        int color = ComputedStyle.idOf("color");
        t.putIfAbsentById(color, "red");
        t.putIfAbsentById(color, "blue");
        t.putIfAbsentById(ComputedStyle.idOf("width"), null);
        assertEquals("red", t.get("color"));
        assertEquals(1, t.size());
        t.remove("color");
        t.putIfAbsentById(color, "blue");
        assertEquals("blue", t.get("color"));
        assertEquals(1, t.size());
    }

    @Test
    void computedStyleFromTableEqualsOneFromAPlainMap() {
        Map<String, String> plain = new LinkedHashMap<>();
        plain.put("color", "red");
        plain.put("font-size", "20px");
        plain.put("--v", "1");
        plain.put("made-up-prop", "m");
        ComputedStyle viaMap = new ComputedStyle(plain, 20f, 16f);
        ComputedStyle viaTable = ComputedStyle.ofOwned(StyleTable.copyOf(plain), 16f, 16f);
        assertTrue(ComputedStyle.sameDeclarations(viaMap, viaTable));
        assertEquals(viaMap.fontSizePx(), viaTable.fontSizePx());
        assertEquals("1", viaTable.customProperties().get("--v"));
        assertEquals(viaMap.asMap(), viaTable.asMap());
    }
}
