package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Milestone 2: カスケード開始時の継承コピーを、Map.Entry の走査から String[] の直接走査へ替えても、中身も並びも同じになる。 */
class InheritCopyTest {

    private static Map<String, String> oldWay(Map<String, String> parent) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : parent.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("--")) {
                out.put(key, e.getValue());
                continue;
            }
            PropertyRegistry.Property p = PropertyRegistry.get(key);
            if (p != null && p.inherited() && !p.isShorthand()) out.put(key, e.getValue());
        }
        return out;
    }

    @Test
    void arrayWalkMatchesEntryWalkOnRandomStyles() {
        List<String> names = new ArrayList<>();
        for (PropertyRegistry.Property p : PropertyRegistry.all()) names.add(p.name());
        String[] extras = {"--a", "--brand-color", "--x1", "unregistered-prop", "-webkit-made-up"};
        Random rnd = new Random(20261112);
        for (int round = 0; round < 2000; round++) {
            Map<String, String> src = new LinkedHashMap<>();
            int n = rnd.nextInt(40);
            for (int i = 0; i < n; i++) src.put(names.get(rnd.nextInt(names.size())), "v" + rnd.nextInt(5));
            for (String x : extras) if (rnd.nextBoolean()) src.put(x, "e" + rnd.nextInt(5));
            ComputedStyle parent = new ComputedStyle(src);

            Map<String, String> fast = new LinkedHashMap<>();
            assertTrue(ComputedStyle.copyInheritable(parent.asMap(), fast));
            Map<String, String> old = oldWay(parent.asMap());

            assertEquals(old, fast, "内容");
            assertEquals(new ArrayList<>(old.keySet()), new ArrayList<>(fast.keySet()), "並び");
        }
    }

    @Test
    void plainMapParentFallsBackToEntryWalk() {
        Map<String, String> plain = new LinkedHashMap<>();
        plain.put("color", "red");
        plain.put("width", "10px");
        plain.put("--v", "1");
        Map<String, String> target = new LinkedHashMap<>();
        assertFalse(ComputedStyle.copyInheritable(plain, target));
        assertTrue(target.isEmpty(), "対象外のときは何も書かない");

        StyleResolver.inheritInto(plain, target);
        assertEquals(oldWay(plain), target);
        assertEquals("red", target.get("color"));
        assertFalse(target.containsKey("width"), "非継承は写さない");
    }

    @Test
    void inheritIntoMatchesOnComputedParent() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("color", "blue");
        src.put("font-size", "20px");
        src.put("width", "5px");
        src.put("--k", "v");
        ComputedStyle parent = new ComputedStyle(src);
        Map<String, String> target = new LinkedHashMap<>();
        StyleResolver.inheritInto(parent.asMap(), target);
        assertEquals(oldWay(parent.asMap()), target);
        assertEquals("blue", target.get("color"));
        assertEquals("v", target.get("--k"));
        assertFalse(target.containsKey("width"));
    }
}
