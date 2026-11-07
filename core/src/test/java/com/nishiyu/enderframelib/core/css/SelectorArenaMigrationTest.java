package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.Selector.SelectorToken;
import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * SelectorMatcher のアリーナ移行: 兄弟リンクと Bloom で絞った結果が、アリーナを使わない素直な走査
 * （番号を持たない複製の木）と常に同じになること。
 */
class SelectorArenaMigrationTest {

    private static final String PAGE = "<html><body><div id=\"a\" class=\"card big\"><span class=\"x\">1</span> t <b>2</b>"
            + "<i class=\"x\">3</i></div><ul><li>1</li><li class=\"on\">2</li><li>3</li><li class=\"on x\">4</li></ul>"
            + "<p>tail</p><section><div class=\"card\"><em class=\"x\">e</em></div></section></body></html>";

    private static final String[] SELECTORS = {
            "li + li", "li ~ li.on", "span + b", "span ~ i", ".x + .x", "li:nth-child(2n)", "li:nth-of-type(odd)",
            "div:has(> .x)", "div:has(.x)", "section:has(em.x)", "ul:has(li.on + li)", ":has(+ p)", "div.card .x",
            "ul > li.on", "#a > b", "*:has(.nonexistent-m1-class)", ".card.big", "li.on:not(.x)", "div:has(~ p)"
    };

    private static void paths(HtmlNode n, String path, List<HtmlNode> nodes, List<String> out) {
        nodes.add(n);
        out.add(path);
        for (int i = 0; i < n.children.size(); i++) paths(n.children.get(i), path + "/" + i, nodes, out);
    }

    private static List<String> matchedPaths(HtmlNode root, String selector) {
        List<HtmlNode> nodes = new ArrayList<>();
        List<String> ps = new ArrayList<>();
        paths(root, "", nodes, ps);
        List<String> hits = new ArrayList<>();
        for (HtmlNode m : SelectorEngine.querySelectorAll(root, selector)) hits.add(ps.get(nodes.indexOf(m)));
        return hits;
    }

    /** 複製は番号を持たない切り離しの木なので、素直な走査経路になる。 */
    private static void assertSameAsPlainWalk(HtmlDocument doc, String label) {
        HtmlNode plain = new HtmlNode(NodeType.ROOT, "root"); // 断片の根は鍵を問われるまで番号を持たない
        for (HtmlNode child : doc.children) plain.appendChild(child.cloneNode(true));
        assertFalse(plain.hasNodeId(), "複製は番号を持たない");
        for (String s : SELECTORS) {
            assertEquals(matchedPaths(plain, s), matchedPaths(doc, s), label + " / " + s);
            assertFalse(plain.hasNodeId());
        }
    }

    @Test
    void parsedDocumentMatchesLikeThePlainWalk() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument(PAGE);
        assertTrue(doc.hasNodeId());
        assertSameAsPlainWalk(doc, "parsed");
        assertFalse(matchedPaths(doc, "li + li").isEmpty());
        assertFalse(matchedPaths(doc, "div:has(> .x)").isEmpty());
    }

    @Test
    void randomMutationsKeepSiblingLinksAndBloomsHonest() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument(PAGE);
        Random random = new Random(11);
        String[] classes = {"x", "on", "card", "big", "zz"};
        for (int step = 0; step < 150; step++) {
            List<HtmlNode> nodes = new ArrayList<>();
            paths(doc, "", nodes, new ArrayList<>());
            HtmlNode t = nodes.get(random.nextInt(nodes.size()));
            if (t == doc || t.nodeType != NodeType.ELEMENT) continue;
            try {
                switch (random.nextInt(6)) {
                    case 0 -> { HtmlElement e = new HtmlElement(random.nextBoolean() ? "li" : "span");
                        e.attrs.put("class", classes[random.nextInt(classes.length)]); t.appendChild(e); }
                    case 1 -> { if (!t.children.isEmpty()) t.removeChild(t.children.get(random.nextInt(t.children.size()))); }
                    case 2 -> t.attrs.put("class", classes[random.nextInt(classes.length)] + " " + classes[random.nextInt(classes.length)]);
                    case 3 -> t.attrs.remove("class");
                    case 4 -> { HtmlNode m = nodes.get(random.nextInt(nodes.size()));
                        if (m != doc && m != t && !m.contains(t) && m.parent != null) t.appendChild(m); }
                    default -> { if (!t.children.isEmpty()) t.insertBefore(new HtmlElement("b"), t.children.get(0)); }
                }
            } catch (RuntimeException rejectedByDom) {
                // DOM が拒否した操作は木を変えない
            }
            assertSameAsPlainWalk(doc, "step " + step);
        }
    }

    @Test
    void previousSiblingUsesArenaLinksButFallsBackForUnnumberedNodes() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument(PAGE);
        HtmlNode li2 = doc.querySelector("li.on");
        assertTrue(li2.hasNodeId());
        assertEquals(1, matchedPaths(doc, "li.on + li").size());
        HtmlNode frag = HtmlParser.parseFragment("<li>a</li><li class=\"on\">b</li>", "ul");
        assertFalse(frag.children.get(0).hasNodeId());
        assertEquals(1, SelectorEngine.querySelectorAll(frag, "li + li.on").size());
    }

    @Test
    void bloomRejectsAbsentNamesWithoutTouchingTheNode() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument(PAGE);
        SelectorToken unknown = new SelectorToken("div.never-seen-m1-class");
        assertEquals(SelectorToken.UNKNOWN_ATOM, unknown.requiredBloom());
        assertTrue(SelectorEngine.querySelectorAll(doc, "div.never-seen-m1-class").isEmpty());

        SelectorToken known = new SelectorToken("div.card");
        long need = known.requiredBloom();
        assertNotEquals(0L, need);
        HtmlNode a = doc.getElementById("a");
        assertTrue(com.nishiyu.enderframelib.core.kernel.memory.AtomTable.mayContain(
                a.arena().selfBloom(a.nodeId()), need));
        assertEquals(0L, new SelectorToken("*").requiredBloom());
    }
}
