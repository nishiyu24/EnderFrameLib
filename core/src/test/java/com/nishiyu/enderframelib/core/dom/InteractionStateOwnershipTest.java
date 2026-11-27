package com.nishiyu.enderframelib.core.dom;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.dom.node.NodeIds;
import com.nishiyu.enderframelib.core.paint.tree.PaintBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

/**
 * S7 ③: {@link InteractionState} が {@link HtmlNode} に持ち主欄や状態欄を書かず、
 * 同一文書・同一ノードを複数ビューで共有しても状態所有権が衝突しないことを検証する。
 */
class InteractionStateOwnershipTest {

    @Test
    void twoViewsSharingSameDocumentKeepIndependentPseudoStatesAndComputedStyles() {
        String html = "<html><head><style>.btn{color:#111}.btn:hover{color:#e11}.btn:active{color:#11e}</style></head>"
                + "<body><button id='b1' class='btn'>one</button><button id='b2' class='btn'>two</button></body></html>";
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", 400, 200);
        HtmlNode b1 = doc.root.getElementById("b1");
        HtmlNode b2 = doc.root.getElementById("b2");

        InteractionState viewA = new InteractionState();
        InteractionState viewB = new InteractionState();

        PaintBox rootA = compiler.layoutDocument(doc, 400, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, viewA);
        PaintBox rootB = compiler.layoutDocument(doc, 400, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, viewB);

        viewA.setHoveredNode(b1);
        viewB.setActiveNode(b2);
        compiler.layoutDocument(doc, 400, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, viewA);
        compiler.layoutDocument(doc, 400, 200, Map.of(), new ScrollState(), FontResolver.FALLBACK, viewB);

        assertTrue(flag(viewA, b1, StateFlags::isHovered));
        assertFalse(flag(viewA, b1, StateFlags::isActive));
        assertFalse(flag(viewB, b1, StateFlags::isHovered));
        assertFalse(flag(viewB, b1, StateFlags::isActive));

        assertFalse(flag(viewA, b2, StateFlags::isActive));
        assertTrue(flag(viewB, b2, StateFlags::isActive));
        assertNotEquals(viewA.computedStyleOf(b1).get("color"), viewB.computedStyleOf(b1).get("color"));
        assertNotEquals(viewA.computedStyleOf(b2).get("color"), viewB.computedStyleOf(b2).get("color"));
    }

    @Test
    void virtualNodesKeepIndependentStatesAcrossViewsWithoutArenaAllocation() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument("<div id='host'></div>");
        NodeIds arena = doc.nodeIds();
        int liveBefore = arena.liveCount();
        HtmlNode virtual = new HtmlNode("span");
        virtual.parent = doc.getElementById("host");
        assertTrue(virtual.isVirtualNode());

        InteractionState viewA = new InteractionState();
        InteractionState viewB = new InteractionState();
        viewA.setHovered(virtual, true);
        viewB.setActive(virtual, true);

        assertTrue(viewA.get(virtual).isHovered());
        assertFalse(viewA.get(virtual).isActive());
        assertFalse(viewB.get(virtual).isHovered());
        assertTrue(viewB.get(virtual).isActive());
        assertEquals(liveBefore, arena.liveCount());

        viewA.clear();
        assertNull(viewA.get(virtual));
        assertTrue(viewB.get(virtual).isActive());
    }

    @Test
    void movingNodesAndRecyclingDynamicIdsPreserveAndIsolateMultipleViews() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument("<div id='a'><span id='s'>hi</span></div><ul id='u'></ul>");
        HtmlNode s = doc.getElementById("s");
        HtmlNode u = doc.getElementById("u");
        InteractionState viewA = new InteractionState();
        InteractionState viewB = new InteractionState();

        viewA.setHovered(s, true);
        viewB.setFocused(s, true, true);
        var flagsA = viewA.get(s);
        var flagsB = viewB.get(s);

        // 静的ノードの同一文書内移動で両方の状態実体が保たれる
        u.appendChild(s);
        assertSame(flagsA, viewA.get(s));
        assertSame(flagsB, viewB.get(s));
        assertTrue(viewA.get(s).isHovered());
        assertTrue(viewB.get(s).isFocused());

        // 動的ノードの同一文書内移動でも保たれ、削除後の ID 再利用では両ビューとも無効になる
        HtmlElement dyn = new HtmlElement("li");
        u.appendChild(dyn);
        viewA.setHovered(dyn, true);
        viewB.setChecked(dyn, true);
        var dynFlagsA = viewA.get(dyn);
        var dynFlagsB = viewB.get(dyn);
        int dynId = dyn.nodeId();

        doc.getElementById("a").appendChild(dyn);
        assertSame(dynFlagsA, viewA.get(dyn));
        assertSame(dynFlagsB, viewB.get(dyn));

        doc.getElementById("a").removeChild(dyn);
        HtmlElement recycled = new HtmlElement("li");
        u.appendChild(recycled);
        assertEquals(dynId, recycled.nodeId());
        assertNull(viewA.get(recycled));
        assertNull(viewB.get(recycled));
        assertEquals(0, viewA.stateBitsOf(recycled) & ElementState.INTERACTIVE_MASK);
        assertEquals(0, viewB.stateBitsOf(recycled) & ElementState.INTERACTIVE_MASK);

        // 切り離されたノードに付けた状態は、文書接続時に新アリーナへ引き継がれる
        HtmlElement detached = new HtmlElement("div");
        viewA.setHovered(detached, true);
        viewB.setActive(detached, true);
        var detA = viewA.get(detached);
        var detB = viewB.get(detached);
        u.appendChild(detached);
        assertSame(detA, viewA.get(detached));
        assertSame(detB, viewB.get(detached));
        assertTrue(viewA.get(detached).isHovered());
        assertTrue(viewB.get(detached).isActive());
    }

    @Test
    void concurrentReadsAndWritesOnSharedDocumentDoNotCollide() throws Exception {
        StringBuilder sb = new StringBuilder("<div>");
        for (int i = 0; i < 64; i++) sb.append("<span id='n").append(i).append("'>x</span>");
        sb.append("</div>");
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument(sb.toString());
        List<HtmlNode> nodes = new ArrayList<>(64);
        for (int i = 0; i < 64; i++) nodes.add(doc.getElementById("n" + i));

        InteractionState viewA = new InteractionState();
        InteractionState viewB = new InteractionState();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < 4; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    InteractionState st = (thread % 2 == 0) ? viewA : viewB;
                    for (int round = 0; round < 20; round++) {
                        for (int i = 0; i < nodes.size(); i++) {
                            HtmlNode n = nodes.get(i);
                            var flags = st.getOrCreate(n);
                            assertNotNull(flags);
                            assertSame(flags, st.get(n));
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void virtualNodeDescendantsKeepPerViewSlotsWithoutEvictingDocumentNumbers() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument("<div id='host'></div>");
        HtmlNode host = doc.getElementById("host");
        NodeIds arena = doc.nodeIds();
        int liveBefore = arena.liveCount();

        InteractionState viewA = new InteractionState();
        InteractionState viewB = new InteractionState();
        viewA.setHovered(host, true);
        var hostFlagsA = viewA.get(host);

        List<HtmlNode> texts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            HtmlNode pseudo = new HtmlNode("span");
            pseudo.isPseudoNode = true;
            pseudo.parent = host;
            HtmlNode txt = new HtmlNode("#text");
            txt.parent = pseudo;
            pseudo.children.add(txt);
            assertTrue(txt.isVirtualNode());

            viewA.setHovered(txt, true);
            viewB.setActive(txt, true);
            assertFalse(txt.hasNodeId());
            texts.add(txt);
        }

        assertEquals(liveBefore, arena.liveCount());
        assertSame(hostFlagsA, viewA.get(host), "多数（>=MAX_TABS）の仮想節子孫で文書 Tab が追い出されない");
        for (HtmlNode txt : texts) {
            assertTrue(viewA.get(txt).isHovered());
            assertFalse(viewA.get(txt).isActive());
            assertTrue(viewB.get(txt).isActive());
            assertFalse(viewB.get(txt).isHovered());
            for (HtmlNode cur = txt; cur != null; cur = cur.parent) {
                var f = viewA.get(cur);
                if (f != null) f.clearStyleDirtyFlag();
            }
        }
        assertFalse(viewA.hasDirtyNodes());
        viewA.markAllDirty();
        for (HtmlNode txt : texts) assertTrue(viewA.get(txt).isStyleDirty());
    }

    @Test
    void htmlNodeNoLongerDeclaresInteractionOwnerOrFlagsFields() {
        for (Field f : HtmlNode.class.getDeclaredFields()) {
            assertNotEquals("interactionOwner", f.getName());
            assertNotEquals("interactionFlags", f.getName());
        }
    }

    /** 状態の印（状態を持たない節は印なし。経路を 1 本にした M2c-3 以降、組み立ては触れていない節の状態を作らない）。 */
    private static boolean flag(InteractionState view, HtmlNode node, java.util.function.Predicate<StateFlags> bit) {
        StateFlags f = view.get(node);
        return f != null && bit.test(f);
    }
}
