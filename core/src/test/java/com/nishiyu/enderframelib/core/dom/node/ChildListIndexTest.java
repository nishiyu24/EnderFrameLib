package com.nishiyu.enderframelib.core.dom.node;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.api.dom.Element;
import com.nishiyu.enderframelib.core.css.SelectorMatcher.SelectorEngine;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 子の並びが各子の位置（{@link HtmlNode#siblingIndex}）を<b>正確に</b>保つこと（M5-1）。
 *
 * <p>{@link HtmlNode#indexInParent} は位置が古くても並びを走査して正しい答えを返すので、照合の結果だけを見る試験では
 * 位置の付け直し忘れが見えない（O(1) が黙って O(n) に戻る）。ここでは欄そのものを、全ての変更の経路の後で確かめる。</p>
 */
class ChildListIndexTest {

    private static HtmlElement child(HtmlNode parent, String tag) {
        HtmlElement e = new HtmlElement(tag);
        e.parent = parent; // 子の並びを直接触る経路では、親は呼び出し側が結ぶ
        return e;
    }

    private static void assertExact(HtmlNode parent, List<HtmlNode> removed) {
        for (int i = 0; i < parent.children.size(); i++) {
            assertEquals(i, parent.children.get(i).siblingIndex, "子 " + i + " の位置");
        }
        for (HtmlNode r : removed) {
            if (!parent.children.contains(r)) assertEquals(-1, r.siblingIndex, "外れた子の位置は -1");
        }
    }

    @Test
    void everyMutationPathKeepsTheIndexExact() {
        HtmlElement p = new HtmlElement("div");
        List<HtmlNode> gone = new ArrayList<>();
        for (int i = 0; i < 5; i++) p.appendChild(new HtmlElement("i"));
        assertExact(p, gone);

        p.insertBefore(new HtmlElement("b"), p.children.get(0));                 // 先頭へ挿入
        assertExact(p, gone);
        p.children.add(3, child(p, "u"));                                         // 途中へ直接
        assertExact(p, gone);
        p.children.addAll(2, List.of(child(p, "s"), child(p, "s")));              // 途中へまとめて
        assertExact(p, gone);
        p.children.addAll(List.of(child(p, "q")));                                // 末尾へ 1 個
        assertExact(p, gone);
        gone.add(p.children.get(4));
        p.removeChild(p.children.get(4));                                         // 途中を外す
        assertExact(p, gone);
        gone.add(p.children.get(0));
        p.children.remove(p.children.get(0));                                     // 節で外す
        assertExact(p, gone);
        HtmlNode replaced = p.children.set(2, child(p, "em"));                    // 置き換え
        gone.add(replaced);
        assertExact(p, gone);
        p.children.removeIf(n -> n.tag.equals("s"));                              // 条件で外す
        assertExact(p, gone);
        List<HtmlNode> before = new ArrayList<>(p.children);
        p.children.subList(1, 3).clear();                                         // 範囲で外す
        gone.addAll(before.subList(1, 3));
        assertExact(p, gone);
        HtmlNode moved = p.children.get(0);
        p.appendChild(moved);                                                     // 同じ親の中で末尾へ移す
        assertExact(p, gone);
        gone.addAll(p.children);
        p.children.clear();
        assertExact(p, gone);
    }

    @Test
    void randomMutationsKeepTheIndexExact() {
        HtmlElement p = new HtmlElement("ul");
        List<HtmlNode> gone = new ArrayList<>();
        Random random = new Random(3);
        for (int step = 0; step < 2000; step++) {
            gone.clear(); // この手で外れた子だけを確かめる
            int n = p.children.size();
            switch (random.nextInt(6)) {
                case 0 -> p.insertBefore(new HtmlElement("li"), n == 0 ? null : p.children.get(random.nextInt(n)));
                case 1 -> p.appendChild(new HtmlElement("li"));
                case 2 -> { if (n > 0) { HtmlNode r = p.children.get(random.nextInt(n)); gone.add(r); p.removeChild(r); } }
                case 3 -> { if (n > 1) p.insertBefore(p.children.get(random.nextInt(n)), p.children.get(random.nextInt(n))); }
                case 4 -> p.children.add(random.nextInt(n + 1), child(p, "li"));
                default -> { if (n > 0) gone.add(p.children.set(random.nextInt(n), child(p, "li"))); }
            }
            assertExact(p, gone);
        }
    }

    /**
     * 子を新しい親へ結んで足してから古い並びを空にする順（パーサの adoption agency が furthest block の子を新しい書式要素へ
     * 移す手順）でも、移った子は新しい親の子のままでいる。以前は古い並びの {@code clear} が移った子の位置を -1 にし、
     * 子の並びに載っていない印（仮想の節）を付け、id 索引から外して番号を手放していた（id で引けず、{@code :has()} が落ちる）。
     */
    @Test
    void childrenMovedBeforeTheOldListIsClearedStayAttached() {
        HtmlDocument doc = (HtmlDocument) HtmlParser.parseDocument("<b><p><span id=\"z\" class=\"k\">1</span>2</b>3");
        HtmlNode z = doc.getElementById("z");
        assertNotNull(z, "移った節も id で引ける");
        assertEquals("b", z.parent.tag, "furthest block の子は新しい書式要素の子になる");
        assertEquals("p", z.parent.parent.tag);
        assertFalse(z.isVirtualNode(), "移った節は仮想の節にならない");
        assertTrue(z.hasNodeId(), "番号を持ったまま");
        assertEquals(0, z.siblingIndex);
        assertEquals(1, z.parent.children.get(1).siblingIndex);
        assertEquals(List.of(z.parent.parent), SelectorEngine.querySelectorAll(doc, "p:has(.k)"), "祖先の子孫 Bloom に入る");

        // 子の並びを直接触る呼び出しの同じ順
        HtmlDocument d2 = (HtmlDocument) HtmlParser.parseDocument("<div id=\"a\"><i id=\"x\"></i><i id=\"y\"></i></div><div id=\"b\"><u></u></div>");
        HtmlNode a = d2.getElementById("a"), b = d2.getElementById("b");
        List<HtmlNode> moving = new ArrayList<>(a.children);
        for (HtmlNode c : moving) c.parent = b;
        b.children.addAll(moving);
        a.children.clear();
        assertTrue(a.children.isEmpty());
        assertExact(b, List.of());
        for (HtmlNode c : moving) {
            assertSame(c, d2.getElementById(c.getId()), "移った節も id で引ける");
            assertFalse(c.isVirtualNode());
            assertTrue(c.hasNodeId());
        }
        assertEquals(2, SelectorEngine.querySelectorAll(d2, "#b > u ~ i").size());
    }

    /** 前後の要素の兄弟（{@code previousElementSibling} / {@code nextElementSibling}）は子の並びの位置から引き、文字の節を飛ばす。 */
    @Test
    void elementSiblingsFollowTheChildIndex() {
        HtmlElement p = new HtmlElement("div");
        Random random = new Random(7);
        for (int step = 0; step < 800; step++) {
            int n = p.children.size();
            switch (random.nextInt(5)) {
                case 0 -> p.insertBefore(new HtmlElement("i"), n == 0 ? null : p.children.get(random.nextInt(n)));
                case 1 -> p.insertBefore(new HtmlText("t"), n == 0 ? null : p.children.get(random.nextInt(n)));
                case 2 -> { if (n > 0) p.removeChild(p.children.get(random.nextInt(n))); }
                case 3 -> { if (n > 1) p.insertBefore(p.children.get(random.nextInt(n)), p.children.get(random.nextInt(n))); }
                default -> p.children.add(random.nextInt(n + 1), child(p, "b"));
            }
            for (int i = 0; i < p.children.size(); i++) {
                if (!(p.children.get(i) instanceof HtmlElement e)) continue;
                Element prev = null, next = null;
                for (int k = i - 1; k >= 0 && prev == null; k--) if (p.children.get(k) instanceof HtmlElement el) prev = el;
                for (int k = i + 1; k < p.children.size() && next == null; k++) if (p.children.get(k) instanceof HtmlElement el) next = el;
                assertSame(prev, e.getPreviousElementSibling(), "step " + step + " / " + i);
                assertSame(next, e.getNextElementSibling(), "step " + step + " / " + i);
            }
        }
        HtmlElement detached = new HtmlElement("i");
        assertNull(detached.getPreviousElementSibling());
        assertNull(detached.getNextElementSibling());
    }
}
