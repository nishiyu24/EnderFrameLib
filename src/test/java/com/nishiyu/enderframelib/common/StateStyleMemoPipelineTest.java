package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Milestone 2: :hover の往復が状態表（StateStyleMemo）を通り、結果が従来経路と一致すること。 */
class StateStyleMemoPipelineTest {

    private static final String PAGE = """
            <style>
              .btn { background-color: #111111; width: 40px; }
              .btn:hover { background-color: #222222; }
              .btn.alt { background-color: #444444; }
              .btn.alt:hover { background-color: #555555; }
              .wide:hover { width: 80px; }
            </style>
            <button id="b" class="btn">Hi</button>
            <button id="w" class="btn wide">Wide</button>
            """;

    private static LayoutBox find(LayoutBox box, String id) {
        if (id.equals(box.node != null ? box.node.getId() : null)) return box;
        for (LayoutBox c : box.elementChildren) {
            LayoutBox r = find(c, id);
            if (r != null) return r;
        }
        return null;
    }

    private static String bg(LayoutBox root, String id) {
        return find(root, id).style.get(CssProperty.BACKGROUND_COLOR);
    }

    @Test
    void hoverRoundTripsGoThroughTableAndStayCorrect() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(PAGE, "", 320, 200);
        InteractionState state = new InteractionState();
        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        HtmlNode b = doc.root.getElementById("b");
        assertEquals("#111111", bg(root, "b"));

        for (int i = 0; i < 6; i++) {
            state.setHoveredNode(b);
            parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
            assertEquals("#222222", bg(root, "b"), "hover " + i);
            state.setHoveredNode(null);
            parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
            assertEquals("#111111", bg(root, "b"), "unhover " + i);
        }
        var memo = state.getOrCreate(b).stateMemo;
        assertNotNull(memo, "ボタンは状態表を持つ");
        assertTrue(memo.hits >= 8, "往復の大半は表を引く: hits=" + memo.hits + " builds=" + memo.builds);
        assertTrue(memo.builds <= 2, "入力が変わらない間は作り直さない: " + memo.builds);
    }

    @Test
    void attributeChangeInvalidatesTheTable() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(PAGE, "", 320, 200);
        InteractionState state = new InteractionState();
        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        HtmlNode b = doc.root.getElementById("b");
        for (int i = 0; i < 3; i++) {
            state.setHoveredNode(b);
            parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
            state.setHoveredNode(null);
            parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        }
        var memo = state.getOrCreate(b).stateMemo;
        int builds = memo.builds;

        b.setAttribute("class", "btn alt");
        state.markDirty(b);
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals("#444444", bg(root, "b"), "古い表の結果を返さない");
        state.setHoveredNode(b);
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals("#555555", bg(root, "b"));
        state.setHoveredNode(null);
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals("#444444", bg(root, "b"));
        assertTrue(state.getOrCreate(b).stateMemo.builds > builds || state.getOrCreate(b).stateMemo != memo);
    }

    @Test
    void layoutChangingHoverStillRelayouts() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(PAGE, "", 320, 200);
        InteractionState state = new InteractionState();
        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        HtmlNode w = doc.root.getElementById("w");
        for (int i = 0; i < 4; i++) {
            state.setHoveredNode(w);
            RenderPipeline.RestyleResult r = parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
            assertTrue(r.layoutRequired(), "width が変わる hover は再レイアウトを要求する (" + i + ")");
            assertEquals("80px", find(root, "w").style.get(CssProperty.WIDTH));
            state.setHoveredNode(null);
            r = parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
            assertTrue(r.layoutRequired());
            assertEquals("40px", find(root, "w").style.get(CssProperty.WIDTH));
        }
    }

    @Test
    void ancestorStateRulesDisableTheTable() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>.p:hover .c { color: #00ff00; } .c:hover { color: #0000ff; }</style>
                <div class="p" id="p"><span class="c" id="c">x</span></div>
                """, "", 320, 200);
        assertFalse(doc.sheet.stateOnSubjectOnly(), "祖先の状態を読む規則があると表は使えない");
        InteractionState state = new InteractionState();
        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        state.setHoveredNode(doc.root.getElementById("p"));
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals("#00ff00", find(root, "c").style.get(CssProperty.COLOR));
        var cm = state.getOrCreate(doc.root.getElementById("c")).stateMemo;
        assertTrue(cm == null || !cm.hasTable(), "祖先の状態を読む節は表を持たない");
    }

    @Test
    void hasSelectorsNeverServeStaleTables() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>.box { background-color: #111111; } .box:hover { background-color: #222222; }
                       .box:has(.flag) { background-color: #aa00aa; }</style>
                <div class="box" id="bx"><span id="k">x</span></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();
        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        HtmlNode bx = doc.root.getElementById("bx");
        for (int i = 0; i < 3; i++) {
            state.setHoveredNode(bx);
            parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
            state.setHoveredNode(null);
            parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        }
        HtmlNode k = doc.root.getElementById("k");
        k.setAttribute("class", "flag");
        state.markDirty(k);
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals("#aa00aa", bg(root, "bx"), "子孫の class で :has が当たる");
        state.setHoveredNode(bx);
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        state.setHoveredNode(null);
        parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
        assertEquals("#aa00aa", bg(root, "bx"));
    }

    @Test
    void childOfHoveredParentFollowsInheritedChangesWithBoundedFrames() {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("""
                <style>.p { color: #111111; } .p:hover { color: #999999; }</style>
                <div class="p" id="p"><span id="c">text</span></div>
                """, "", 320, 200);
        InteractionState state = new InteractionState();
        LayoutBox root = parser.buildLayoutTree(doc.root, new HashMap<>(), doc.sheet, Map.of(), false, state);
        HtmlNode p = doc.root.getElementById("p");
        for (int i = 0; i < 8; i++) {
            state.setHoveredNode(p);
            parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
            assertEquals("#999999", find(root, "c").style.get(CssProperty.COLOR), "hover " + i);
            state.setHoveredNode(null);
            parser.restyleLayoutTree(root, doc, 320, 200, Map.of(), state);
            assertEquals("#111111", find(root, "c").style.get(CssProperty.COLOR), "unhover " + i);
        }
        var memo = state.getOrCreate(doc.root.getElementById("c")).stateMemo;
        assertNotNull(memo);
        assertTrue(memo.builds <= 4, "子の枠は往復の両側だけ: builds=" + memo.builds + " hits=" + memo.hits);
        assertTrue(memo.hits >= 10, "往復の大半は枠を引く: hits=" + memo.hits);
    }
}
