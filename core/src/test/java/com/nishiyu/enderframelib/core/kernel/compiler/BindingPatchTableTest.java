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

    @Test
    void overlayPatchesStyleStructsWithoutTouchingOthers() {
        HtmlDocument d = doc();
        HtmlNode bar = d.getElementById("bar");
        HtmlNode fade = d.getElementById("fade");
        BindingPatchTable t = new BindingPatchTable(bar.arena());
        t.compile(d);
        var compiler = new com.nishiyu.enderframelib.core.kernel.style.StyleStructs.Compiler(
                new com.nishiyu.enderframelib.core.kernel.memory.AtomTable());
        var base = compiler.compile(new com.nishiyu.enderframelib.core.css.ComputedStyle(
                java.util.Map.of("width", "10px", "opacity", "1"), 16f, 16f), null);
        assertSame(base, t.overlay(base, bar.nodeId()), "未設定のパッチは何も変えない");
        t.setFloat("hp_pct", 40f);
        t.setFloat("alpha", 0.5f);
        var patched = t.overlay(base, bar.nodeId());
        assertEquals(com.nishiyu.enderframelib.core.kernel.style.PackedCssValue.U_PERCENT,
                com.nishiyu.enderframelib.core.kernel.style.PackedCssValue.unit(patched.box.width()));
        assertEquals(80f, patched.widthPx(200, 16, 0, 0));
        assertEquals(1f, patched.paint.opacity(), "bar に opacity のパッチは無い");
        assertSame(base.surround, patched.surround);
        assertEquals(0.5f, t.overlay(base, fade.nodeId()).paint.opacity());
        assertSame(patched.box, t.overlay(base, d.getElementById("two").nodeId()).box, "同じ値の構造体は畳まれる");
    }

    @Test
    void reactiveBinderMirrorsNumericValuesIntoTheTable() {
        HtmlDocument d = doc();
        var binder = new com.nishiyu.enderframelib.core.dom.Bindings.ReactiveDomBinder();
        java.util.Map<String, String> values = new java.util.HashMap<>();
        values.put("hp_pct", "30");
        values.put("alpha", "0.75");
        values.put("fs", "12");
        values.put("grow", "2");
        values.put("a", "5");
        binder.sync(d, values, java.util.Map.of(), java.util.Set.of(com.nishiyu.enderframelib.core.dom.Bindings.TemplateDeps.ALWAYS));
        var table = binder.patchTable();
        assertNotNull(table);
        assertEquals(30f, table.get(BindingPatchTable.F_WIDTH_PCT, d.getElementById("bar").nodeId()));
        assertEquals(0.75f, table.get(BindingPatchTable.F_OPACITY, d.getElementById("fade").nodeId()));
        assertEquals(12f, table.get(BindingPatchTable.F_FONT_SIZE_PX, d.getElementById("size").nodeId()));
        // 従来の属性経路も同じ値になっている（両者が食い違わない）
        assertTrue(d.getElementById("bar").attrs.get("style").contains("width: 30%"));

        values.put("hp_pct", "55");
        binder.sync(d, values, java.util.Map.of(), java.util.Set.of("hp_pct"));
        assertEquals(55f, table.get(BindingPatchTable.F_WIDTH_PCT, d.getElementById("two").nodeId()));
        assertTrue(d.getElementById("bar").attrs.get("style").contains("width: 55%"));
    }

    @Test
    void nodesAddedLaterAreCompiledOnTheNextSync() {
        HtmlDocument d = doc();
        var binder = new com.nishiyu.enderframelib.core.dom.Bindings.ReactiveDomBinder();
        java.util.Map<String, String> values = new java.util.HashMap<>();
        values.put("hp_pct", "30");
        values.put("late", "12");
        var all = java.util.Set.of(com.nishiyu.enderframelib.core.dom.Bindings.TemplateDeps.ALWAYS);
        binder.sync(d, values, java.util.Map.of(), all);
        assertFalse(binder.patchTable().hasKey("late"));

        HtmlDocument scratch = (HtmlDocument) HtmlParser.parseDocument("<div id=\"row\" style=\"width: {{late}}px\"></div>");
        HtmlNode row = scratch.getElementById("row");
        d.getElementById("hud").appendChild(row);
        binder.sync(d, values, java.util.Map.of(), all);
        assertTrue(binder.patchTable().hasKey("late"), "追加された節も登録される");
        assertEquals(12f, binder.patchTable().get(BindingPatchTable.F_WIDTH_PX, row.nodeId()));

        int patches = binder.patchTable().patchCount();
        binder.sync(d, values, java.util.Map.of(), all);
        assertEquals(patches, binder.patchTable().patchCount(), "同じ節を二重に登録しない");
    }
}
