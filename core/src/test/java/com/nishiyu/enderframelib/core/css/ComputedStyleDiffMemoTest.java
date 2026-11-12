package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Q2: {@link ComputedStyle#diff} の (旧, 新) 組メモが、素朴な全枠走査と同じ結果を返し、同じ組では走査を繰り返さない。 */
class ComputedStyleDiffMemoTest {

    private static final String[][] CHOICES = {
            {"color", "red", "blue", "#112233"},
            {"background-color", "white", "black", "#eee"},
            {"font-size", "12px", "14px", "20px"},
            {"margin-top", "0", "4px", "1em"},
            {"display", "block", "inline", "flex"},
            {"opacity", "1", "0.5", "0"},
            {"width", "10px", "50%", "auto"},
    };

    private static ComputedStyle random(Random r) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String[] c : CHOICES) {
            int k = r.nextInt(4);
            if (k > 0) m.put(c[0], c[k]);
        }
        if (r.nextInt(5) == 0) m.put("--x", String.valueOf(r.nextInt(3)));
        return new ComputedStyle(m);
    }

    private static List<String> viaDiff(ComputedStyle a, ComputedStyle b) {
        List<String> out = new ArrayList<>();
        ComputedStyle.diff(a, b, (name, paintOnly, inherited) -> out.add(name + ":" + paintOnly + ":" + inherited));
        return out;
    }

    /** メモを使わない参照実装（プロパティ名の集合の差で求める）。 */
    private static List<String> names(ComputedStyle a, ComputedStyle b) {
        List<String> out = new ArrayList<>();
        ComputedStyle.diff(a, b, (name, p, i) -> out.add(name));
        return out;
    }

    @Test
    void memoizedDiffMatchesReferenceOnRandomPairs() {
        Random r = new Random(42);
        List<ComputedStyle> pool = new ArrayList<>();
        for (int i = 0; i < 40; i++) pool.add(random(r));
        for (int i = 0; i < 3000; i++) {
            ComputedStyle a = pool.get(r.nextInt(pool.size())), b = pool.get(r.nextInt(pool.size()));
            List<String> got = names(a, b);
            // 参照: 各プロパティを Map ビューで直接比べる
            List<String> expected = new ArrayList<>();
            Map<String, String> pa = a.asMap(), pb = b.asMap();
            for (String k : new java.util.TreeSet<>(union(pa.keySet(), pb.keySet()))) {
                if (!java.util.Objects.equals(pa.get(k), pb.get(k))) expected.add(k);
            }
            assertEquals(new java.util.TreeSet<>(expected), new java.util.TreeSet<>(got), "pair #" + i);
            if (a == b) assertTrue(got.isEmpty());
            // 2 回目（メモ命中）も同じ
            assertEquals(viaDiff(a, b), viaDiff(a, b));
        }
    }

    private static java.util.Set<String> union(java.util.Set<String> x, java.util.Set<String> y) {
        java.util.Set<String> s = new java.util.HashSet<>(x);
        s.addAll(y);
        return s;
    }

    @Test
    void samePairIsScannedOnce() {
        ComputedStyle a = new ComputedStyle(Map.of("color", "red", "display", "block"));
        ComputedStyle b = new ComputedStyle(Map.of("color", "blue", "display", "block"));
        long miss0 = ComputedStyle.diffMemoMisses(), hit0 = ComputedStyle.diffMemoHits();
        List<String> first = viaDiff(a, b);
        for (int i = 0; i < 50; i++) assertEquals(first, viaDiff(a, b));
        assertEquals(1, first.size());
        assertTrue(first.get(0).startsWith("color:"));
        assertEquals(1, ComputedStyle.diffMemoMisses() - miss0, "同じ組の全枠走査は 1 回だけ");
        assertEquals(50, ComputedStyle.diffMemoHits() - hit0);
        assertTrue(viaDiff(a, a).isEmpty());
    }

    @Test
    void differentPairsSharingASlotDoNotMixResults() {
        Random r = new Random(7);
        List<ComputedStyle> pool = new ArrayList<>();
        for (int i = 0; i < 600; i++) pool.add(random(r)); // 256 枠を十分に超える数の組で衝突させる
        List<List<String>> first = new ArrayList<>();
        for (int i = 0; i + 1 < pool.size(); i++) first.add(viaDiff(pool.get(i), pool.get(i + 1)));
        for (int i = 0; i + 1 < pool.size(); i++) assertEquals(first.get(i), viaDiff(pool.get(i), pool.get(i + 1)), "pair " + i);
    }
}
