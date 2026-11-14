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

    @Test
    void bandSliceQueriesMatchReferenceWithoutCallingDiff() {
        Random r = new Random(99);
        List<ComputedStyle> pool = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String[] c : CHOICES) {
                int k = r.nextInt(4);
                if (k > 0) m.put(c[0], c[k]);
            }
            if (r.nextBoolean()) m.put("position", r.nextBoolean() ? "relative" : "absolute");
            if (r.nextInt(3) == 0) m.put("left", (r.nextInt(3) * 10) + "px");
            if (r.nextInt(3) == 0) m.put("top", (r.nextInt(3) * 10) + "px");
            if (r.nextInt(4) == 0) m.put("white-space", r.nextBoolean() ? "normal" : "pre");
            if (r.nextInt(5) == 0) m.put("--custom", "v" + r.nextInt(3));
            pool.add(new ComputedStyle(m, r.nextBoolean() ? 16f : 14f, 16f));
        }

        for (int i = 0; i < 2000; i++) {
            ComputedStyle a = pool.get(r.nextInt(pool.size()));
            ComputedStyle b = pool.get(r.nextInt(pool.size()));

            // 直接 diff 参照
            boolean[] refLayout = {Float.compare(a.fontSizePx(), b.fontSizePx()) != 0
                    || Float.compare(a.rootFontSizePx(), b.rootFontSizePx()) != 0};
            boolean[] refStruct = {false};
            int[] refProp = {refLayout[0] ? 2 : 0};
            int[] refAxes = {0};
            ComputedStyle.diff(a, b, (name, paintOnly, inherited) -> {
                if (!paintOnly) refLayout[0] = true;
                if (inherited) refProp[0] = 2;
                else if (refProp[0] == 0) refProp[0] = 1;
                switch (name) {
                    case CssProperty.DISPLAY, CssProperty.POSITION, CssProperty.FLOAT,
                         CssProperty.WHITE_SPACE, CssProperty.CONTENT, CssProperty.CONTENT_VISIBILITY,
                         CssProperty.LIST_STYLE_TYPE, CssProperty.LIST_STYLE_POSITION, CssProperty.LIST_STYLE_IMAGE,
                         CssProperty.COUNTER_RESET, CssProperty.COUNTER_INCREMENT, CssProperty.COUNTER_SET -> refStruct[0] = true;
                }
                if (!paintOnly) {
                    if (CssProperty.LEFT.equals(name) || CssProperty.RIGHT.equals(name)) {
                        if (refAxes[0] >= 0) refAxes[0] |= 1;
                    } else if (CssProperty.TOP.equals(name) || CssProperty.BOTTOM.equals(name)) {
                        if (refAxes[0] >= 0) refAxes[0] |= 2;
                    } else {
                        refAxes[0] = -1;
                    }
                }
            });
            assertEquals(refLayout[0], a.hasLayoutAffectingDiffFrom(b), "layout diff #" + i);
            assertEquals(refStruct[0], a.hasStructureAffectingDiff(b), "struct diff #" + i);
            assertEquals(refProp[0], b.classifyPropagationFrom(a), "propagation #" + i);
            assertEquals(refAxes[0], a.classifyPositionOnlyDiff(b), "position axes #" + i);
        }

        // Band 3 (color) のみの変化は >256 組でも diffMemoMisses を増やさず O(1) で判定できる
        long missCheck = ComputedStyle.diffMemoMisses();
        for (int i = 0; i < 400; i++) {
            ComputedStyle a = new ComputedStyle(Map.of("display", "block", "width", (i + 10) + "px", "color", "#111"));
            ComputedStyle b = new ComputedStyle(Map.of("display", "block", "width", (i + 10) + "px", "color", "#222"));
            assertFalse(a.hasLayoutAffectingDiffFrom(b));
            assertEquals(2, b.classifyPropagationFrom(a));
        }
        assertEquals(missCheck, ComputedStyle.diffMemoMisses(), "専用判定は diff()/DIFF_MEMO を使わない");
    }

    @Test
    void derivedAnonymousAndMarkerMatchInheritedMapCopies() {
        Map<String, String> ownerDecl = new LinkedHashMap<>();
        ownerDecl.put("display", "flex");
        ownerDecl.put("width", "120px");
        ownerDecl.put("margin-left", "8px");
        ownerDecl.put("color", "crimson");
        ownerDecl.put("font-size", "20px");
        ownerDecl.put("text-transform", "uppercase");
        ownerDecl.put("--accent", "#09f");
        ComputedStyle owner = new ComputedStyle(ownerDecl, 20f, 16f);

        ComputedStyle anon = owner.derivedAnonymous("block");
        assertSame(anon, owner.derivedAnonymous("block"), "同一所有者からはメモを返す");
        assertEquals("block", anon.get("display"));
        assertNull(anon.get("width"));
        assertNull(anon.get("margin-left"));
        assertEquals("crimson", anon.get("color"));
        assertEquals("uppercase", anon.get("text-transform"));
        assertEquals("#09f", anon.get("--accent"));
        assertEquals(20f, anon.fontSizePx());
        assertEquals(16f, anon.rootFontSizePx());

        ComputedStyle defaultMarker = owner.derivedMarker(null);
        assertSame(defaultMarker, owner.derivedMarker(null));
        assertEquals("inline-block", defaultMarker.get("display"));
        assertEquals("pre", defaultMarker.get("white-space"));
        assertEquals("none", defaultMarker.get("text-transform"));
        assertEquals("crimson", defaultMarker.get("color"));
        assertNull(defaultMarker.get("width"));

        ComputedStyle declared = new ComputedStyle(
                Map.of("color", "teal", "content", "'*'", "display", "block", "white-space", "normal"), 18f, 16f);
        ComputedStyle marker = owner.derivedMarker(declared);
        assertSame(marker, owner.derivedMarker(declared));
        assertNull(marker.get("content"));
        assertEquals("inline-block", marker.get("display"));
        assertEquals("pre", marker.get("white-space"));
        assertEquals("none", marker.get("text-transform"));
        assertEquals("teal", marker.get("color"));
        assertEquals(18f, marker.fontSizePx());
    }
}
