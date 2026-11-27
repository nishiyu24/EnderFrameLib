package com.nishiyu.enderframelib.core.css;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.css.Selector.SelectorToken;
import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.node.SelectorIndex;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.kernel.memory.AtomTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * セレクタの足切り（{@link SelectorIndex} の Bloom・class の atom）と前後の兄弟（子の並びの位置）を、DOM の変更のたびに
 * その場で保った文書の照合結果が、同じ木を作り直したもの（複製。索引は作りたて）の照合結果と常に同じになること
 * （直列化して読み直す形は、HTML の木構築が不正な入れ子を組み替えるので物差しにならない）。
 *
 * <p>M5-1 で {@code DocumentArena} の兄弟の列と Bloom の列（番号を持つ節だけ）を、子の並びの位置と節の欄
 * （番号の有無に依らない）に置き換えた。作り直した木の索引は作りたてで正確なので、増分の側の取りこぼし
 * （祖先へ広げ忘れた子孫の Bloom・付け直し忘れた位置）はここで食い違いになる。</p>
 */
class SelectorIndexOracleTest {

    private static final String PAGE = "<html><body><div id=\"a\" class=\"card big\"><span class=\"x\">1</span> t <b>2</b>"
            + "<i class=\"x\">3</i></div><ul><li>1</li><li class=\"on\">2</li><li>3</li><li class=\"on x\">4</li></ul>"
            + "<p>tail</p><section><div class=\"card\"><em class=\"x\">e</em></div></section></body></html>";

    private static final String[] SELECTORS = {
            "li + li", "li ~ li.on", "span + b", "span ~ i", ".x + .x", "li:nth-child(2n)", "li:nth-of-type(odd)",
            "div:has(> .x)", "div:has(.x)", "section:has(em.x)", "ul:has(li.on + li)", ":has(+ p)", "div.card .x",
            "ul > li.on", "#a > b", "*:has(.nonexistent-m1-class)", ".card.big", "li.on:not(.x)", "div:has(~ p)",
            "body :has(.zz)", "li:first-child + li", "b ~ *", ":has(> li.on)"
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

    /** 複製（番号を持たない切り離しの木。索引は作りたて）と同じ結果になる。 */
    private static void assertSameAsRebuilt(HtmlDocument doc, String label) {
        HtmlNode plain = new HtmlNode(NodeType.ROOT, "root"); // 断片の根は鍵を問われるまで番号を持たない
        for (HtmlNode child : doc.children) plain.appendChild(child.cloneNode(true));
        assertFalse(plain.hasNodeId(), "複製は番号を持たない");
        for (String s : SELECTORS) {
            assertEquals(matchedPaths(plain, s), matchedPaths(doc, s), label + " / " + s);
            assertFalse(plain.hasNodeId(), "照合は番号を振らない");
        }
    }

    @Test
    void parsedDocumentMatchesLikeTheRebuiltTree() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument(PAGE);
        assertTrue(doc.hasNodeId());
        assertSameAsRebuilt(doc, "parsed");
        assertFalse(matchedPaths(doc, "li + li").isEmpty());
        assertFalse(matchedPaths(doc, "div:has(> .x)").isEmpty());
    }

    @Test
    void randomMutationsKeepSiblingIndexesAndBloomsHonest() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument(PAGE);
        Random random = new Random(11);
        String[] classes = {"x", "on", "card", "big", "zz"};
        for (int step = 0; step < 300; step++) {
            List<HtmlNode> nodes = new ArrayList<>();
            paths(doc, "", nodes, new ArrayList<>());
            HtmlNode t = nodes.get(random.nextInt(nodes.size()));
            if (t == doc || t.nodeType != NodeType.ELEMENT) continue;
            int at = random.nextInt(t.children.size() + 1);
            try {
                switch (random.nextInt(10)) {
                    case 0 -> {
                        HtmlElement e = new HtmlElement(random.nextBoolean() ? "li" : "span");
                        e.attrs.put("class", classes[random.nextInt(classes.length)]);
                        t.insertBefore(e, at < t.children.size() ? t.children.get(at) : null);
                    }
                    case 1 -> { if (!t.children.isEmpty()) t.removeChild(t.children.get(random.nextInt(t.children.size()))); }
                    case 2 -> t.attrs.put("class", classes[random.nextInt(classes.length)] + " " + classes[random.nextInt(classes.length)]);
                    case 3 -> t.attrs.remove("class");
                    case 4 -> { // 同じ文書の中での移動（任意の位置へ）
                        HtmlNode m = nodes.get(random.nextInt(nodes.size()));
                        if (m != doc && m != t && !m.contains(t) && m.parent != null) {
                            t.insertBefore(m, at < t.children.size() ? t.children.get(at) : null);
                        }
                    }
                    case 5 -> { // パーサ・バインディングの経路: 子の並びへ直接まとめて足す（親は呼び出し側が結ぶ）
                        HtmlElement li = new HtmlElement("li"), em = new HtmlElement("em");
                        li.attrs.put("class", "on");
                        em.attrs.put("class", "x zz");
                        li.parent = t;
                        em.parent = t;
                        t.children.addAll(at, List.of(li, em));
                    }
                    case 6 -> t.children.removeIf(c -> c.nodeType == NodeType.ELEMENT && random.nextInt(3) == 0);
                    case 7 -> t.setInnerHTML("<li class=\"on\">n</li><em class=\"x\">m</em><li>o</li>");
                    case 8 -> t.attrs.put("id", random.nextBoolean() ? "a" : "z" + step);
                    default -> { if (!t.children.isEmpty()) t.insertBefore(new HtmlElement("b"), t.children.get(0)); }
                }
            } catch (RuntimeException rejectedByDom) {
                // DOM が拒否した操作は木を変えない
            }
            assertSameAsRebuilt(doc, "step " + step);
        }
    }

    /**
     * 子の並びへ直接まとめて足した（{@code addAll}。パーサ・バインディングの経路）子孫の class も、祖先の子孫 Bloom に入る。
     * 以前はこの経路だけ番号を振って兄弟を結び直すだけで祖先の子孫 Bloom へ広げず、{@code :has()} が当たりを棄却していた。
     */
    @Test
    void bulkAddedDescendantsReachHasSelectors() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument("<html><body><div id=\"p\"><i>0</i></div></body></html>");
        HtmlNode p = doc.getElementById("p");
        HtmlElement a = new HtmlElement("em"), b = new HtmlElement("em");
        a.attrs.put("class", "zzz-m5-bulk");
        a.parent = p;
        b.parent = p;
        p.children.addAll(List.of(a, b));
        assertEquals(List.of(p), SelectorEngine.querySelectorAll(doc, "div:has(.zzz-m5-bulk)"));
        assertEquals(List.of(p), SelectorEngine.querySelectorAll(doc, "div:has(> em.zzz-m5-bulk + em)"));
    }

    @Test
    void previousSiblingUsesTheChildIndexForNumberedAndUnnumberedTrees() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument(PAGE);
        HtmlNode li2 = doc.querySelector("li.on");
        assertTrue(li2.hasNodeId());
        assertEquals(1, li2.indexInParent());
        assertEquals(1, matchedPaths(doc, "li.on + li").size());
        HtmlNode frag = HtmlParser.parseFragment("<li>a</li><li class=\"on\">b</li>", "ul");
        assertFalse(frag.children.get(0).hasNodeId());
        assertEquals(1, frag.children.get(1).indexInParent(), "番号の無い木でも位置は引ける");
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
        assertTrue(AtomTable.mayContain(SelectorIndex.selfBloom(a), need));
        assertEquals(0L, new SelectorToken("*").requiredBloom());
    }
}
