package com.nishiyu.enderframelib.core.kernel.compiler;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssParser;
import com.nishiyu.enderframelib.core.css.StyleResolver;
import com.nishiyu.enderframelib.core.css.StyleSheet;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StyleStateAutomatonTest {

    private static HtmlDocument doc() {
        return (HtmlDocument) HtmlParser.parseDocument("""
                <div class="menu"><div class="item" id="in"></div></div>
                <div class="item" id="out"></div>
                <div class="parent" id="p"><div class="child" id="c"></div></div>
                <button id="b" class="btn">x</button>
                """);
    }

    private static HtmlNode byId(HtmlDocument d, String id) {
        return d.getElementById(id);
    }

    @Test
    void interactiveMaskCollectsOnlyMatchingStates() {
        HtmlDocument d = doc();
        StyleSheet sheet = CssParser.parse("""
                .menu .item:hover { color: #ff0000; }
                .parent:hover .child { color: #00ff00; }
                .btn:hover { color: #111111; }
                .btn:active { color: #222222; }
                .btn:focus { color: #333333; }
                .btn.never:hover { color: #444444; }
                """, 320);
        assertEquals(ElementState.HOVER, StyleStateAutomaton.interactiveMask(byId(d, "in"), sheet));
        assertEquals(0, StyleStateAutomaton.interactiveMask(byId(d, "out"), sheet));
        assertEquals(ElementState.HOVER, StyleStateAutomaton.interactiveMask(byId(d, "p"), sheet));
        assertEquals(0, StyleStateAutomaton.interactiveMask(byId(d, "c"), sheet));
        assertEquals(ElementState.HOVER | ElementState.ACTIVE | ElementState.FOCUS,
                StyleStateAutomaton.interactiveMask(byId(d, "b"), sheet));
        assertEquals(0, StyleStateAutomaton.interactiveMask(null, sheet));
        assertEquals(0, StyleStateAutomaton.interactiveMask(byId(d, "b"), null));
    }

    @Test
    void hasInteractiveRulesAgreesWithMask() {
        HtmlDocument d = doc();
        StyleSheet sheet = CssParser.parse(".btn:hover { color: red; } .item { color: blue; }", 320);
        for (String id : new String[]{"in", "out", "p", "c", "b"}) {
            HtmlNode n = byId(d, id);
            assertEquals(StyleStateAutomaton.interactiveMask(n, sheet) != 0, StyleResolver.hasInteractiveRules(n, sheet), id);
        }
    }

    @Test
    void interactiveRuleSubsetTracksSheetEdits() {
        StyleSheet sheet = CssParser.parse(".a { color: red; } .b:hover { color: blue; }", 320);
        assertEquals(1, sheet.interactiveRules().length);
        sheet.rules.addAll(CssParser.parse(".c:active { color: red; } .d:focus { color: red; }", 320).rules);
        assertEquals(3, sheet.interactiveRules().length, "規則が増えたら数え直す");
        assertSame(sheet.interactiveRules(), sheet.interactiveRules(), "変わらなければ使い回す");
    }

    @Test
    void compressPacksSelectedBits() {
        int mask = ElementState.HOVER | ElementState.FOCUS; // 1 と 4
        assertEquals(0, StyleStateAutomaton.compress(0, mask));
        assertEquals(1, StyleStateAutomaton.compress(ElementState.HOVER, mask));
        assertEquals(2, StyleStateAutomaton.compress(ElementState.FOCUS, mask));
        assertEquals(3, StyleStateAutomaton.compress(ElementState.HOVER | ElementState.FOCUS | ElementState.ACTIVE, mask));
    }

    private static ComputedStyle styleFor(int bits) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("width", "10px");
        m.put("background-color", (bits & ElementState.HOVER) != 0 ? "#222222" : "#111111");
        if ((bits & ElementState.ACTIVE) != 0) m.put("width", "20px");
        return new ComputedStyle(m, 16f, 16f);
    }

    @Test
    void stateTableResolvesLazilyAndClassifiesTransitions() {
        AtomicInteger calls = new AtomicInteger();
        var table = new StyleStateAutomaton.StateTable(ElementState.HOVER | ElementState.ACTIVE, bits -> {
            calls.incrementAndGet();
            return styleFor(bits);
        });
        assertEquals(4, table.size());
        assertEquals(0, calls.get(), "構築時には解かない");
        ComputedStyle base = table.style(0);
        assertEquals(1, calls.get());
        assertSame(base, table.style(0));
        assertSame(base, table.style(ElementState.FOCUS), "マスク外のビットは無視される");
        assertEquals(1, calls.get());
        assertEquals("#222222", table.style(ElementState.HOVER).get("background-color"));

        assertEquals(StyleStateAutomaton.PAINT_ONLY, table.changeKind(0, ElementState.HOVER), "背景色だけ");
        assertEquals(StyleStateAutomaton.LAYOUT, table.changeKind(0, ElementState.ACTIVE), "width が変わる");
        assertEquals(StyleStateAutomaton.NONE, table.changeKind(ElementState.HOVER, ElementState.HOVER));
        int before = calls.get();
        for (int i = 0; i < 100; i++) table.changeKind(0, ElementState.HOVER);
        assertEquals(before, calls.get(), "遷移の分類は覚える");
        assertEquals(3, table.resolvedCount());
    }

    @Test
    void stateTableRejectsEmptyMask() {
        assertThrows(IllegalArgumentException.class, () -> new StyleStateAutomaton.StateTable(0, b -> null));
    }

    @Test
    void hoverToggleThroughTableAllocatesNothing() {
        var table = new StyleStateAutomaton.StateTable(ElementState.HOVER, StyleStateAutomatonTest::styleFor);
        table.changeKind(0, ElementState.HOVER);
        table.changeKind(ElementState.HOVER, 0);
        ComputedStyle sink = null;
        for (int i = 0; i < 200_000; i++) {
            sink = table.style((i & 1) == 0 ? 0 : ElementState.HOVER);
            table.changeKind(0, ElementState.HOVER);
        }
        var mx = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long tid = Thread.currentThread().getId();
        long before = mx.getThreadAllocatedBytes(tid);
        int hits = 0;
        for (int i = 0; i < 100_000; i++) {
            sink = table.style((i & 1) == 0 ? 0 : ElementState.HOVER);
            hits += table.changeKind(0, ElementState.HOVER);
        }
        long used = mx.getThreadAllocatedBytes(tid) - before;
        assertNotNull(sink);
        assertTrue(hits > 0);
        assertTrue(used < 4096, ":hover の表引きがヒープを確保している: " + used + " B");
    }

    @Test
    void hoverLookupIsFarCheaperThanScanningRules() {
        // 規則が 500 本あっても、状態遷移は表引き（規則数に依存しない）
        StringBuilder css = new StringBuilder();
        for (int i = 0; i < 500; i++) css.append(".c").append(i).append(":hover{color:#").append(String.format("%06x", i)).append("}");
        css.append(".btn:hover{color:red}");
        StyleSheet sheet = CssParser.parse(css.toString(), 320);
        HtmlDocument d = doc();
        HtmlNode b = byId(d, "b");
        int mask = StyleStateAutomaton.interactiveMask(b, sheet);
        assertEquals(ElementState.HOVER, mask);
        var table = new StyleStateAutomaton.StateTable(mask, StyleStateAutomatonTest::styleFor);
        table.style(0);
        table.style(ElementState.HOVER);
        long t0 = System.nanoTime();
        int n = 1_000_000;
        int acc = 0;
        for (int i = 0; i < n; i++) acc += table.changeKind(0, (i & 1) == 0 ? ElementState.HOVER : 0);
        double nsPerOp = (System.nanoTime() - t0) / (double) n;
        System.out.println("[m2] :hover table lookup = " + nsPerOp + " ns/op (acc=" + acc + ")");
        assertTrue(nsPerOp < 5000, "表引きが遅すぎる: " + nsPerOp + " ns");
    }

    @Test
    void stateOnlyOnSubjectIsPerNode() {
        HtmlDocument d = doc();
        StyleSheet sheet = CssParser.parse("""
                .parent:hover .child { color: #00ff00; }
                .btn:hover { color: #111111; }
                .item:hover { color: #222222; }
                .menu .item:hover { color: #333333; }
                """, 320);
        assertFalse(sheet.stateOnSubjectOnly(), "シート全体では祖先の状態を読む規則がある");
        assertTrue(StyleStateAutomaton.stateOnlyOnSubject(byId(d, "b"), sheet), "無関係な規則は妨げない");
        assertTrue(StyleStateAutomaton.stateOnlyOnSubject(byId(d, "p"), sheet));
        assertFalse(StyleStateAutomaton.stateOnlyOnSubject(byId(d, "c"), sheet), ".parent:hover .child の対象");
        assertTrue(StyleStateAutomaton.stateOnlyOnSubject(byId(d, "in"), sheet), "左側に状態が無い");
        StyleSheet has = CssParser.parse("div:has(:hover) { color: red; }", 320);
        assertFalse(StyleStateAutomaton.stateOnlyOnSubject(byId(d, "p"), has), ":has は子孫の状態を読む");
        assertFalse(StyleStateAutomaton.stateOnlyOnSubject(null, sheet));
    }
}
