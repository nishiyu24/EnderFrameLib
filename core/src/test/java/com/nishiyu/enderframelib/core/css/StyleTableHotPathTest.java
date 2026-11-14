package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 再スタイルの熱い経路の最適化（ビューポート依存の判定を値ごとに覚える／{@code --*} の表を書き込み時にだけ写す）が、
 * 素朴な実装と同じ答えを返すこと。
 */
class StyleTableHotPathTest {

    private static final String[] VALUES = {
            "10px", "red", "10vw", "5vh", "2vmin", "3VMAX", "100dvh", "1svw", "7lvh", "4cqw", "4cqi", "8cqmax",
            "var(--x)", "VAR(--y, 1px)", "env(safe-area-inset-top)", "calc(100% - 20px)", "calc(100vh - 2px)",
            "10vwx", "10v", "vw", "1 2 3", "rgb(1,2,3)", "10em", "50%", "auto", "inherit", "1cq", "9vi", "9vb",
    };

    private static boolean naive(Map<String, String> raw) {
        for (String v : raw.values()) {
            if (v != null && StyleResolver.valueDependsOnViewport(v)) return true;
        }
        return false;
    }

    @Test
    void knownValuesAreClassifiedAsBefore() {
        for (String v : new String[]{"10vw", "5vh", "2vmin", "3VMAX", "100dvh", "4cqw", "var(--x)", "env(x)",
                "calc(100vh - 2px)", "9vi", "9vb"}) {
            assertTrue(StyleResolver.valueDependsOnViewport(v), v);
        }
        for (String v : new String[]{"10px", "red", "10vwx", "10v", "vw", "calc(100% - 20px)", "50%", "1cq"}) {
            assertFalse(StyleResolver.valueDependsOnViewport(v), v);
        }
    }

    @Test
    void tableAnswerEqualsTheMapScanUnderRandomContents() {
        List<String> names = new ArrayList<>();
        for (PropertyRegistry.Property p : PropertyRegistry.all()) names.add(p.name());
        names.add("--a");
        names.add("--b");
        Random rnd = new Random(20261110);
        for (int round = 0; round < 2000; round++) {
            StyleTable t = new StyleTable();
            int n = rnd.nextInt(8);
            for (int i = 0; i < n; i++) {
                t.put(names.get(rnd.nextInt(names.size())), VALUES[rnd.nextInt(VALUES.length)]);
            }
            if (rnd.nextInt(4) == 0) t.remove(names.get(rnd.nextInt(names.size())));
            assertEquals(naive(t), StyleResolver.rawDependsOnViewport(t), "round " + round + " " + t);
            // 書き換えたあとも古い答えを返さない
            t.put(names.get(rnd.nextInt(names.size())), VALUES[rnd.nextInt(VALUES.length)]);
            assertEquals(naive(t), StyleResolver.rawDependsOnViewport(t), "after write " + round);
        }
        // 普通のマップでも同じ
        Map<String, String> plain = new HashMap<>();
        plain.put("width", "10vw");
        assertTrue(StyleResolver.rawDependsOnViewport(plain));
        plain.put("width", "10px");
        assertFalse(StyleResolver.rawDependsOnViewport(plain));
    }

    @Test
    void copiesShareExtrasButNeverSeeEachOthersWrites() {
        Random rnd = new Random(7);
        for (int round = 0; round < 300; round++) {
            StyleTable base = new StyleTable();
            Map<String, String> baseModel = new HashMap<>();
            for (int i = 0; i < 4; i++) {
                base.put("--k" + i, "v" + i);
                baseModel.put("--k" + i, "v" + i);
            }
            List<StyleTable> tables = new ArrayList<>(List.of(base));
            List<Map<String, String>> models = new ArrayList<>(List.of(baseModel));
            for (int step = 0; step < 60; step++) {
                int i = rnd.nextInt(tables.size());
                StyleTable t = tables.get(i);
                Map<String, String> m = models.get(i);
                String k = "--k" + rnd.nextInt(6);
                switch (rnd.nextInt(5)) {
                    case 0 -> {
                        tables.add(StyleTable.copyOf(t));
                        models.add(new HashMap<>(m));
                    }
                    case 1 -> assertEquals(m.put(k, "w" + step), t.put(k, "w" + step));
                    case 2 -> assertEquals(m.remove(k), t.remove(k));
                    case 3 -> {
                        // 走査しながら 1 つ消す（共有中でも、走査も他の表も壊さない）
                        Iterator<Map.Entry<String, String>> it = t.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<String, String> e = it.next();
                            if (e.getKey().startsWith("--") && rnd.nextBoolean()) {
                                m.remove(e.getKey());
                                it.remove();
                                break;
                            }
                        }
                    }
                    default -> {
                        if (rnd.nextInt(8) == 0) {
                            t.clear();
                            m.clear();
                        }
                    }
                }
                for (int j = 0; j < tables.size(); j++) {
                    Map<String, String> actual = new HashMap<>();
                    for (Map.Entry<String, String> e : tables.get(j).entrySet()) actual.put(e.getKey(), e.getValue());
                    assertEquals(models.get(j), actual, "round " + round + " step " + step + " table " + j);
                    assertEquals(models.get(j).size(), tables.get(j).size());
                }
            }
        }
    }

    @Test
    void inheritedExtrasAndComputedValuesKeepViewportFlagExact() {
        StyleTable parentSrc = new StyleTable();
        parentSrc.put("--plain", "12px");
        parentSrc.put("--vp", "5vw");
        parentSrc.put("color", "red");
        parentSrc.put("width", "10vw");
        ComputedStyle parentWithVpCustom = new ComputedStyle(parentSrc, 16f, 16f);

        StyleTable childA = new StyleTable();
        assertTrue(ComputedStyle.copyInheritable(parentWithVpCustom.asMap(), childA));
        assertTrue(StyleResolver.rawDependsOnViewport(childA), "親の --vp を共有して引き継ぐので true");
        childA.put("--vp", "8px");
        assertFalse(StyleResolver.rawDependsOnViewport(childA), "--vp を上書きして消したあとは false");
        childA.put("margin-left", "2vh");
        assertTrue(StyleResolver.rawDependsOnViewport(childA));
        childA.put("margin-left", "4px");
        assertFalse(StyleResolver.rawDependsOnViewport(childA));
        childA.put("padding-top", "3vmin");
        assertTrue(StyleResolver.rawDependsOnViewport(childA));
        childA.remove("padding-top");
        assertFalse(StyleResolver.rawDependsOnViewport(childA));
        childA.put("height", "5vh");
        childA.clear();
        assertFalse(StyleResolver.rawDependsOnViewport(childA));

        StyleTable parentNoVp = new StyleTable();
        parentNoVp.put("--a", "10px");
        parentNoVp.put("color", "blue");
        ComputedStyle computedNoVp = new ComputedStyle(parentNoVp, 16f, 16f);
        StyleTable childB = new StyleTable();
        assertTrue(ComputedStyle.copyInheritable(computedNoVp.asMap(), childB));
        assertFalse(StyleResolver.rawDependsOnViewport(childB));
        StyleTable copied = StyleTable.copyOf(computedNoVp.asMap());
        assertFalse(StyleResolver.rawDependsOnViewport(copied));
        copied.put("--b", "10dvh");
        assertTrue(StyleResolver.rawDependsOnViewport(copied));
        copied.remove("--b");
        assertFalse(StyleResolver.rawDependsOnViewport(copied));
    }
}
