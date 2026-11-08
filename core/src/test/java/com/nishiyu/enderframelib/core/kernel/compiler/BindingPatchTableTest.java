package com.nishiyu.enderframelib.core.kernel.compiler;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.kernel.memory.DocumentArena;
import java.lang.management.ManagementFactory;
import org.junit.jupiter.api.Test;

class BindingPatchTableTest {

    private static final String PAGE = """
            <div id="hud"><div id="bar" class="bar" style="width: {{hp_pct}}%; height: 4px"></div>
            <div id="fade" style="opacity: {{alpha}}"></div>
            <div id="two" style="width: {{hp_pct}}%"></div>
            <div id="mixed" style="width: calc({{a}}% - 2px)"></div>
            <div id="size" style="font-size: {{fs}}px; flex-grow: {{grow}}"></div></div>
            """;

    private static HtmlDocument doc() {
        return (HtmlDocument) HtmlParser.parseDocument(PAGE);
    }

    @Test
    void compilesStyleBindingsIntoDirectPatches() {
        HtmlDocument d = doc();
        HtmlNode root = d;
        BindingPatchTable t = new BindingPatchTable(d.arena());
        int n = t.compile(root);
        assertEquals(5, n, "hp_pct x2 / alpha / fs / grow（calc() 混在は対象外）");
        assertTrue(t.hasKey("hp_pct"));
        assertTrue(t.hasKey("alpha"));
        assertFalse(t.hasKey("a"), "式の中の置換は従来経路");
    }

    @Test
    void setFloatWritesArenaColumnAndMarksDirty() {
        HtmlDocument d = doc();
        HtmlNode bar = d.getElementById("bar");
        HtmlNode two = d.getElementById("two");
        DocumentArena arena = bar.arena();
        BindingPatchTable t = new BindingPatchTable(arena);
        t.compile(d);
        arena.setDirty(bar.nodeId(), DocumentArena.DIRTY_STYLE | DocumentArena.DIRTY_LAYOUT, false);
        arena.setDirty(two.nodeId(), DocumentArena.DIRTY_STYLE | DocumentArena.DIRTY_LAYOUT, false);

        assertTrue(Float.isNaN(t.get(BindingPatchTable.F_WIDTH_PCT, bar.nodeId())));
        assertEquals(2, t.setFloat("hp_pct", 75f), "2 つの節が汚れる");
        assertEquals(75f, t.get(BindingPatchTable.F_WIDTH_PCT, bar.nodeId()));
        assertEquals(75f, t.get(BindingPatchTable.F_WIDTH_PCT, two.nodeId()));
        assertNotEquals(0, arena.dirtyBits(bar.nodeId()) & DocumentArena.DIRTY_LAYOUT);

        arena.setDirty(bar.nodeId(), DocumentArena.DIRTY_STYLE | DocumentArena.DIRTY_LAYOUT, false);
        assertEquals(0, t.setFloat("hp_pct", 75f), "同じ値なら何も汚さない");
        assertEquals(0, arena.dirtyBits(bar.nodeId()) & DocumentArena.DIRTY_LAYOUT);
        assertEquals(0, t.setFloat("unknown", 1f));
    }

    @Test
    void opacityIsPaintOnly() {
        HtmlDocument d = doc();
        HtmlNode fade = d.getElementById("fade");
        DocumentArena arena = fade.arena();
        BindingPatchTable t = new BindingPatchTable(arena);
        t.compile(d);
        arena.setDirty(fade.nodeId(), DocumentArena.DIRTY_STYLE | DocumentArena.DIRTY_LAYOUT | DocumentArena.DIRTY_PAINT, false);
        assertEquals(1, t.setFloat("alpha", 0.25f));
        assertEquals(0.25f, t.get(BindingPatchTable.F_OPACITY, fade.nodeId()));
        assertEquals(0, arena.dirtyBits(fade.nodeId()) & DocumentArena.DIRTY_LAYOUT);
        assertNotEquals(0, arena.dirtyBits(fade.nodeId()) & DocumentArena.DIRTY_PAINT);
    }

    @Test
    void registerGrowsColumnsForLateNodes() {
        DocumentArena arena = new DocumentArena(8);
        BindingPatchTable t = new BindingPatchTable(arena);
        t.register("k", 5000, BindingPatchTable.F_WIDTH_PX);
        assertEquals(0, t.setFloat("k", 3f) == 1 ? 0 : 1, "未使用の節番号でも書ける（汚れは立てない）");
        assertEquals(3f, t.get(BindingPatchTable.F_WIDTH_PX, 5000));
        assertThrows(IllegalArgumentException.class, () -> t.register("k", -1, 0));
        assertThrows(IllegalArgumentException.class, () -> t.register("k", 1, 99));
    }

    @Test
    void hotPathAllocatesNothing() {
        HtmlDocument d = doc();
        BindingPatchTable t = new BindingPatchTable(d.arena());
        t.compile(d);
        for (int i = 0; i < 200_000; i++) t.setFloat("hp_pct", i);
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(tid);
        int changed = 0;
        for (int i = 0; i < 100_000; i++) changed += t.setFloat("hp_pct", i);
        long used = mx.getThreadAllocatedBytes(tid) - before;
        assertTrue(changed > 0);
        assertTrue(used < 4096, "バインディングの直接書き込みがヒープを確保している: " + used + " B");
    }

    private static final java.util.Set<String> ALL =
            java.util.Set.of(com.nishiyu.enderframelib.core.dom.Bindings.TemplateDeps.ALWAYS);

    private static String inlineOf(HtmlNode n) {
        java.util.Map<String, String> m = new java.util.TreeMap<>();
        for (var decl : com.nishiyu.enderframelib.core.css.CssSyntax.parseDeclarationList(n.attrs.getOrDefault("style", ""))) {
            m.put(decl.name(), decl.valueText());
        }
        if (n.inlineOverlay != null) n.inlineOverlay.overlayInline(n, m);
        return m.toString();
    }

    @Test
    void numericBindingsGoToTheTableAndNeverRewriteTheStyleAttribute() {
        HtmlDocument d = doc();
        var binder = new com.nishiyu.enderframelib.core.dom.Bindings.ReactiveDomBinder();
        java.util.Map<String, String> values = new java.util.HashMap<>();
        values.put("hp_pct", "30");
        values.put("alpha", "0.75");
        values.put("fs", "12");
        values.put("grow", "2");
        values.put("a", "5");
        assertTrue(binder.sync(d, values, java.util.Map.of(), ALL));
        var table = binder.patchTable();
        assertNotNull(table);
        HtmlNode bar = d.getElementById("bar");
        assertEquals(30f, table.get(BindingPatchTable.F_WIDTH_PCT, bar.nodeId()));
        assertEquals(0.75f, table.get(BindingPatchTable.F_OPACITY, d.getElementById("fade").nodeId()));
        assertEquals(12f, table.get(BindingPatchTable.F_FONT_SIZE_PX, d.getElementById("size").nodeId()));

        // 属性には数値バインディングの宣言が残らない（文字列置換＋再パースの二重コストを払わない）
        assertEquals("height: 4px", bar.attrs.get("style").trim());
        assertEquals("", d.getElementById("two").attrs.get("style").trim());
        // 式の中の置換は従来の属性経路
        assertTrue(d.getElementById("mixed").attrs.get("style").contains("calc(5% - 2px)"));
        // スタイル解決が読む宣言（属性＋重ね書き）には値が揃っている
        assertEquals("{height=4px, width=30%}", inlineOf(bar));
        assertEquals("{opacity=0.75}", inlineOf(d.getElementById("fade")));
        assertEquals("{flex-grow=2, font-size=12px}", inlineOf(d.getElementById("size")));

        values.put("hp_pct", "55");
        assertTrue(binder.sync(d, values, java.util.Map.of(), java.util.Set.of("hp_pct")));
        assertEquals(55f, table.get(BindingPatchTable.F_WIDTH_PCT, d.getElementById("two").nodeId()));
        assertEquals("{height=4px, width=55%}", inlineOf(bar));
    }

    @Test
    void changedPatchedNodesAreReportedAndBumpTheAttributeVersion() {
        HtmlDocument d = doc();
        var binder = new com.nishiyu.enderframelib.core.dom.Bindings.ReactiveDomBinder();
        java.util.Map<String, String> values = new java.util.HashMap<>(java.util.Map.of("hp_pct", "30", "alpha", "1",
                "fs", "10", "grow", "1", "a", "1"));
        binder.sync(d, values, java.util.Map.of(), ALL);
        binder.drainChangedNodes();
        HtmlNode bar = d.getElementById("bar");
        int version = bar.attrVersion();

        values.put("hp_pct", "31");
        assertTrue(binder.sync(d, values, java.util.Map.of(), java.util.Set.of("hp_pct")));
        var changed = binder.drainChangedNodes();
        assertTrue(changed.contains(bar), "再スタイルの印を付ける対象に載る");
        assertTrue(changed.contains(d.getElementById("two")));
        assertFalse(changed.contains(d.getElementById("fade")), "無関係の節は載らない");
        assertTrue(bar.attrVersion() > version, "属性に依存するメモを捨てさせる");
        assertFalse(binder.drainLayoutTreeChanged(), "見た目だけの更新は箱の木を作り直さない");

        assertFalse(binder.sync(d, values, java.util.Map.of(), java.util.Set.of("hp_pct")), "同じ値なら何も変えない");
    }

    @Test
    void nonNumericValueFallsBackToTheAttributePathAndBack() {
        HtmlDocument d = doc();
        var binder = new com.nishiyu.enderframelib.core.dom.Bindings.ReactiveDomBinder();
        java.util.Map<String, String> values = new java.util.HashMap<>(java.util.Map.of("hp_pct", "30", "alpha", "1",
                "fs", "10", "grow", "1", "a", "1"));
        binder.sync(d, values, java.util.Map.of(), ALL);
        HtmlNode bar = d.getElementById("bar");

        values.put("hp_pct", "auto");
        binder.sync(d, values, java.util.Map.of(), java.util.Set.of("hp_pct"));
        assertTrue(Float.isNaN(binder.patchTable().get(BindingPatchTable.F_WIDTH_PCT, bar.nodeId())),
                "数値でなくなったら直接書き込みを取り消す");
        assertEquals("{height=4px, width=auto%}", inlineOf(bar), "従来の属性経路に戻る（値は利用側が弾く）");

        values.put("hp_pct", "40");
        binder.sync(d, values, java.util.Map.of(), java.util.Set.of("hp_pct"));
        assertEquals("{height=4px, width=40%}", inlineOf(bar));
        assertEquals("height: 4px", bar.attrs.get("style").trim());
    }

    @Test
    void duplicatedPropertyInOneStyleStaysOnTheAttributePath() {
        HtmlDocument d = (HtmlDocument) HtmlParser.parseDocument(
                "<div id=\"x\" style=\"width: {{k}}px; width: 5px\"></div>");
        var binder = new com.nishiyu.enderframelib.core.dom.Bindings.ReactiveDomBinder();
        binder.sync(d, new java.util.HashMap<>(java.util.Map.of("k", "9")), java.util.Map.of(), ALL);
        HtmlNode x = d.getElementById("x");
        assertNull(x.inlineOverlay, "後ろの width が勝つ宣言順を壊さない");
        assertTrue(x.attrs.get("style").contains("width: 9px"));
    }

    @Test
    void cssNumberFormattingNeverUsesExponentNotation() {
        assertEquals("30", BindingPatchTable.css(30f));
        assertEquals("0.75", BindingPatchTable.css(0.75f));
        assertEquals("-2", BindingPatchTable.css(-2f));
        assertFalse(BindingPatchTable.css(0.00001f).contains("E"));
        assertFalse(BindingPatchTable.css(1.0e10f + 0.5f).contains("E"));
    }

    @Test
    void directSetClearsWithNaNAndOnlyDirtiesOnChange() {
        HtmlDocument d = doc();
        HtmlNode bar = d.getElementById("bar");
        BindingPatchTable t = new BindingPatchTable(bar.arena());
        int id = bar.nodeId();
        assertTrue(t.set(id, BindingPatchTable.F_WIDTH_PCT, 10f));
        assertFalse(t.set(id, BindingPatchTable.F_WIDTH_PCT, 10f));
        assertTrue(t.set(id, BindingPatchTable.F_WIDTH_PCT, Float.NaN));
        assertTrue(Float.isNaN(t.get(BindingPatchTable.F_WIDTH_PCT, id)));
        assertFalse(t.set(id + 5000, BindingPatchTable.F_OPACITY, Float.NaN), "未確保の添字へ NaN は書かない");
        assertNull(BindingPatchTable.parsePatch("width: calc({{a}}% - 2px)"));
        assertNull(BindingPatchTable.parsePatch("opacity: {{a}} !important"));
        assertEquals("hp", BindingPatchTable.parsePatch(" Width : {{ hp }}% ").key());
    }
}
