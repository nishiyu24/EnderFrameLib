package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** M1c: カスケード開始時の {@link StyleProperties#inheritInto} が、旧継承規則（継承ロングハンドと {@code --*}）と同じ内容を写す。 */
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
    void typedInheritMatchesEntryWalkOnRandomStyles() {
        String[] extras = {"--a", "--brand-color", "--x1", "unregistered-prop"};
        Random rnd = new Random(20261112);
        for (int round = 0; round < 500; round++) {
            Map<String, String> src = new LinkedHashMap<>();
            if (rnd.nextBoolean()) src.put("color", "#112233");
            if (rnd.nextBoolean()) src.put("font-size", (12 + rnd.nextInt(10)) + "px");
            if (rnd.nextBoolean()) src.put("font-weight", rnd.nextBoolean() ? "bold" : "700");
            if (rnd.nextBoolean()) src.put("white-space", "pre");
            if (rnd.nextBoolean()) src.put("width", "50px");
            for (String x : extras) if (rnd.nextBoolean()) src.put(x, "e" + rnd.nextInt(5));
            ComputedStyle parent = ComputedStyle.of(src, 16f, 16f);

            StyleTable target = new StyleTable();
            StyleProperties.inheritInto(parent.typed(), target);
            Map<String, String> old = oldWay(parent.asMap());
            assertEquals(old, new LinkedHashMap<>(target), "内容 round=" + round);
        }
    }

    @Test
    void plainMapParentFallsBackToEntryWalk() {
        Map<String, String> plain = new LinkedHashMap<>();
        plain.put("color", "red");
        plain.put("width", "10px");
        plain.put("--v", "1");
        Map<String, String> target = new LinkedHashMap<>();
        StyleResolver.inheritInto(plain, target);
        assertEquals(oldWay(plain), target);
        assertEquals("red", target.get("color"));
        assertFalse(target.containsKey("width"), "非継承は写さない");
    }

    @Test
    void inheritIntoMatchesOnComputedParent() {
        Map<String, String> src = new LinkedHashMap<>();
        src.put("color", "#0000ff");
        src.put("font-size", "20px");
        src.put("width", "5px");
        src.put("--k", "v");
        ComputedStyle parent = ComputedStyle.of(src, 16f, 16f);
        StyleTable target = new StyleTable();
        StyleProperties.inheritInto(parent.typed(), target);
        assertEquals(oldWay(parent.asMap()), new LinkedHashMap<>(target));
        assertEquals("#0000ff", target.get("color"));
        assertEquals("v", target.get("--k"));
        assertFalse(target.containsKey("width"));
    }
}
