package com.nishiyu.enderframelib.core.layout.fragment;

import com.nishiyu.enderframelib.core.box.BoxChildren;
import com.nishiyu.enderframelib.core.box.BoxNode;
import com.nishiyu.enderframelib.core.box.BoxTreeCompiler;
import com.nishiyu.enderframelib.core.dom.node.ChangeKind;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M2a 判定基準 5（再生成の局所性）と §3.3 A-1/A-2: {@link BoxTreeCompiler} は {@code ChangeLedger} の凍結世代だけを入口に、
 * 変わっていない部分木を同じ参照で返し、継承値が変わらなければ子孫の作り直しを打ち切る。
 */
class BoxTreeCompilerTest {
    /** 根・html・body・div#scroller・ul#list の 5 段。 */
    private static final int LIST_ANCESTORS = 5;

    @Test
    void quiescentFrameReturnsSameTreeAndFragment() {
        SpikeView v = new SpikeView(SpikeView.largeList(200), 800, 600);
        LayoutFragment f1 = v.frame();
        BoxNode r1 = v.boxRoot;
        LayoutFragment f2 = v.frame();
        assertSame(r1, v.boxRoot);
        assertSame(f1, f2);
        assertEquals(0, v.compiler.stats().rebuiltBoxes());
        assertEquals(0, v.compiler.stats().restyled());
        assertEquals(1, v.cacheStats().layoutHits);
        assertEquals(0, v.cacheStats().layoutComputes);
    }

    @Test
    void insertAndRemoveIn8000ChildListRebuildOnlyAncestorPathAndOneChunk() {
        SpikeView v = new SpikeView(SpikeView.largeList(8000), 800, 600);
        v.frame();
        BoxChildren before = list(v).children;
        assertEquals(250, before.chunkCount());
        for (String where : new String[]{"item-1", "item-4000", "item-8000", null}) {
            HtmlNode ul = v.byId("list");
            HtmlElement li = new HtmlElement("li");
            li.updateTextContent("inserted");
            ul.insertBefore(li, where == null ? null : v.byId(where));
            v.frame();
            BoxTreeCompiler.Stats st = v.compiler.stats();
            assertFalse(st.full());
            assertEquals(LIST_ANCESTORS + 2, st.rebuiltBoxes(), where + ": 祖先 5 ＋ 新しい li と文字 " + st);
            assertTrue(st.rebuiltChunks() <= 2, where + ": 作り直したチャンク " + st);
            assertTrue(sharedChunks(before, list(v).children) >= 249, where + ": 共有したチャンク");
            float hit = (float) v.cacheStats().chunkHits / (v.cacheStats().chunkHits + v.cacheStats().chunkComputes);
            // 一覧の 250 チャンクのうち変わったのは 1 個: 残り 249 は同じ (BoxChunk, 制約) で当たる（未変更チャンクのヒット率 100%）
            assertTrue(v.cacheStats().chunkHits >= 249 && hit >= 0.95f, where + ": " + v.cacheStats());

            before = list(v).children;
            ul.removeChild(li);
            v.frame();
            st = v.compiler.stats();
            assertEquals(LIST_ANCESTORS, st.rebuiltBoxes(), where + ": 削除は祖先 5 だけ " + st);
            assertTrue(st.rebuiltChunks() <= 2, where + ": " + st);
            assertTrue(sharedChunks(before, list(v).children) >= 249, where);
            before = list(v).children;
        }
    }

    @Test
    void textChangeRebuildsPathWithoutRestyle() {
        SpikeView v = new SpikeView(SpikeView.largeList(300), 800, 600);
        v.frame();
        ((HtmlText) v.byId("item-150").children.get(0)).setData("changed text");
        v.frame();
        assertEquals(LIST_ANCESTORS + 2, v.compiler.stats().rebuiltBoxes());
        assertEquals(0, v.compiler.stats().restyled(), "文字の変更はスタイルを解き直さない");
    }

    @Test
    void nonInheritedChangeStopsAtTheElement() {
        SpikeView v = new SpikeView(SpikeView.largeList(300), 800, 600);
        v.frame();
        BoxNode li = find(v.boxRoot, v.byId("item-150"));
        BoxNode text = li.children.get(0);
        ((HtmlElement) v.byId("item-150")).setAttribute("style", "padding:9px");
        v.frame();
        BoxNode li2 = find(v.boxRoot, v.byId("item-150"));
        assertNotSame(li, li2);
        assertSame(text, li2.children.get(0), "非継承の変更: 子は同じ参照（継承群が同じ参照のまま）");
        assertSame(li.style.inherited, li2.style.inherited);
        assertEquals(1, v.compiler.stats().restyled());
    }

    @Test
    void inheritedOverrideCutsPropagation() {
        SpikeView v = new SpikeView("<html><body><div id=a><div id=b style='color:red'><p id=p>x</p></div><p id=q>y</p></div></body></html>", 800, 600);
        v.frame();
        BoxNode p = find(v.boxRoot, v.byId("p")), q = find(v.boxRoot, v.byId("q")), b = find(v.boxRoot, v.byId("b"));
        ((HtmlElement) v.byId("a")).setAttribute("style", "color:blue");
        v.frame();
        BoxNode b2 = find(v.boxRoot, v.byId("b"));
        assertSame(b, b2, "b は color を上書きしているので、解き直しても前回と同値 → 同じ参照");
        assertSame(p, find(v.boxRoot, v.byId("p")), "b の下は作り直さない");
        assertNotSame(q, find(v.boxRoot, v.byId("q")), "q は新しい color を継承する");
    }

    @Test
    void inheritedChangeRestylesSubtree() {
        SpikeView v = new SpikeView(SpikeView.largeList(100), 800, 600);
        v.frame();
        ((HtmlElement) v.byId("list")).setAttribute("style", "font-size:20px");
        v.frame();
        assertTrue(v.compiler.stats().restyled() >= 200, "li と文字は全て解き直す: " + v.compiler.stats());
    }

    @Test
    void replacedNodeAtSamePositionGetsFreshBox() {
        // DOM の番号は削除で解放・再利用される。番号で照合すると、同じ位置に入った新しい節に古い箱を取り違える（§3.3 の知見）
        SpikeView v = new SpikeView(SpikeView.largeList(50), 800, 600);
        v.frame();
        HtmlNode ul = v.byId("list"), old = v.byId("item-5"), next = v.byId("item-6");
        ul.removeChild(old);
        HtmlElement fresh = new HtmlElement("li");
        fresh.updateTextContent("fresh");
        ul.insertBefore(fresh, next);
        v.frame();
        BoxNode b = find(v.boxRoot, fresh);
        assertNotNull(b);
        assertEquals("fresh", b.children.get(0).text);
        assertEquals(null, find(v.boxRoot, old));
    }

    @Test
    void nodelessChangeRebuildsWholeTree() {
        SpikeView v = new SpikeView(SpikeView.largeList(50), 800, 600);
        v.frame();
        v.ledger().append(null, ChangeKind.ENV);
        v.frame();
        assertTrue(v.compiler.stats().full());
        v.ledger().append(null, ChangeKind.SCROLL);
        v.frame();
        assertFalse(v.compiler.stats().full(), "スクロールは箱の木に効かない");
        assertEquals(0, v.compiler.stats().rebuiltBoxes());
    }

    private static BoxNode list(SpikeView v) {
        return find(v.boxRoot, v.byId("list"));
    }

    private static int sharedChunks(BoxChildren a, BoxChildren b) {
        Set<Object> s = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < a.chunkCount(); i++) s.add(a.chunk(i));
        int n = 0;
        for (int i = 0; i < b.chunkCount(); i++) if (s.contains(b.chunk(i))) n++;
        return n;
    }

    static BoxNode find(BoxNode b, HtmlNode node) {
        if (b.node == node) return b;
        for (int i = 0, n = b.children.size(); i < n; i++) {
            BoxNode r = find(b.children.get(i), node);
            if (r != null) return r;
        }
        return null;
    }
}
