package com.nishiyu.enderframelib.core.dom;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeIds;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.node.SelectorIndex;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.kernel.memory.AtomTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import org.junit.jupiter.api.Test;

/**
 * 節の同一性（鍵は番号から導き、文字列 id を属性へ振らない）・子の並びの位置・セレクタの索引・擬似状態。
 * M5-1 で番号の空間から木の形と Bloom の写しを除いたので、位置と索引は子の並びと属性からその場で保つ形を確かめる。
 */
class NodeIdentityTest {

    private static final String PAGE = "<html><head><title>t</title></head><body>"
            + "<div id=\"a\" class=\"card big\"><span>x</span> text <b id=\"bold\">y</b></div>"
            + "<ul><li>1</li><li class=\"on\">2</li></ul><p>tail</p></body></html>";

    private static HtmlDocument doc(String html) {
        return (HtmlDocument) HtmlParser.parseDocument(html);
    }

    private static void walk(HtmlNode n, List<HtmlNode> out) {
        out.add(n);
        for (HtmlNode c : n.children) walk(c, out);
    }

    private static List<HtmlNode> all(HtmlNode root) {
        List<HtmlNode> out = new ArrayList<>();
        walk(root, out);
        return out;
    }

    @Test
    void parserNoLongerWritesSyntheticIdsIntoAttributes() {
        HtmlDocument d = doc(PAGE);
        int explicit = 0;
        for (HtmlNode n : all(d)) {
            String id = n.attrs.get("id");
            if (id != null) {
                assertFalse(id.startsWith("gen_") || id.startsWith("txt_"), "合成 id が属性に残っている: " + id);
                if (!id.isEmpty() && n != d) explicit++; // 文書の根（html_root）は索引に載せない
            }
        }
        // 索引に載るのは作者が書いた id だけ
        assertEquals(explicit, d.indexedNodeCount(), "id 索引の件数 = 明示 id を持つ節の数");
        assertEquals(2, explicit, "a, bold");
    }

    @Test
    void keyIsExplicitIdOrDerivedFromArenaNumber() {
        HtmlDocument d = doc(PAGE);
        HtmlNode a = d.getElementById("a");
        assertEquals("a", a.key());
        assertEquals("a", a.getId());

        HtmlNode span = a.children.get(0);
        assertEquals("", span.getId(), "DOM の id は作者の値だけ");
        assertEquals("~" + span.nodeId(), span.key());
        HtmlNode text = a.children.get(1);
        assertEquals(NodeType.TEXT, text.nodeType);
        assertEquals("~t" + text.nodeId(), text.key());

        assertSame(span, d.getNodeById(span.key()), "派生鍵は番号から節へ引ける");
        assertSame(text, d.getNodeById(text.key()));
        assertSame(a, d.getNodeById("a"));
        assertNull(d.getElementById("zzz"));
        assertNull(d.getNodeById("~99999"));
        assertNull(d.getNodeById("~12x"));
    }

    @Test
    void numberingIsPreOrderAndIdenticalAcrossParses() {
        HtmlDocument d1 = doc(PAGE), d2 = doc(PAGE);
        List<HtmlNode> n1 = all(d1), n2 = all(d2);
        assertEquals(n1.size(), n2.size());
        for (int i = 0; i < n1.size(); i++) {
            assertEquals(i, n1.get(i).nodeId(), "行きがけ順の番号");
            assertEquals(n1.get(i).key(), n2.get(i).key(), "同じ文書なら鍵が揃う（サーバーとクライアントの対応付け）");
        }
        NodeIds arena = d1.nodeIds();
        assertEquals(n1.size(), arena.staticCount());
        // 行きがけ順なら部分木は連続区間: 部分木の最大番号 + 1 = id + 部分木の大きさ
        for (HtmlNode n : n1) {
            int max = all(n).stream().mapToInt(HtmlNode::nodeId).max().getAsInt();
            assertEquals(n.nodeId() + all(n).size(), max + 1);
        }
    }

    @Test
    void siblingIndexAndSelectorIndexMirrorTheTree() {
        HtmlDocument d = doc(PAGE);
        assertTreeIndexesMatch(d);
    }

    /** 子の並びの位置と、セレクタの索引（自分の Bloom は属性どおり・子孫の Bloom は子孫の和を含む）が木と一致する。 */
    static void assertTreeIndexesMatch(HtmlNode root) {
        AtomTable atoms = AtomTable.shared();
        for (HtmlNode n : all(root)) {
            for (int i = 0; i < n.children.size(); i++) {
                HtmlNode c = n.children.get(i);
                assertEquals(i, c.indexInParent(), "indexInParent of child " + i + " of " + n.tag);
                assertSame(n, c.parent);
                assertSame(i == 0 ? null : n.children.get(i - 1), c.getPreviousSibling());
                assertSame(i == n.children.size() - 1 ? null : n.children.get(i + 1), c.getNextSibling());
            }
            long expected = AtomTable.bloomBits(atoms.lookup(n.tag)) | AtomTable.bloomBits(atoms.lookup(n.getId()));
            List<Integer> classAtoms = new ArrayList<>();
            for (String c : n.getClasses()) {
                int atom = atoms.lookup(c);
                assertNotEquals(0, atom, "class の語は atom になっている: " + c);
                classAtoms.add(atom);
                expected |= AtomTable.bloomBits(atom);
            }
            assertArrayEquals(classAtoms.stream().mapToInt(Integer::intValue).toArray(), SelectorIndex.classAtoms(n),
                    "class の atom は属性どおり: " + n.tag);
            assertEquals(expected, SelectorIndex.selfBloom(n), "自分の Bloom は属性どおり: " + n.tag);
            long union = 0L;
            for (HtmlNode x : all(n)) union |= SelectorIndex.selfBloom(x);
            assertTrue(AtomTable.mayContain(SelectorIndex.subtreeBloom(n), union), "子孫の Bloom は子孫の和を含む: " + n.tag);
        }
    }

    @Test
    void mutationsKeepIndexesAndNeverLeakNumbers() {
        HtmlDocument d = doc(PAGE);
        HtmlNode body = d.getElementById("a").parent;
        Random random = new Random(7);
        for (int step = 0; step < 400; step++) {
            List<HtmlNode> nodes = all(d);
            HtmlNode target = nodes.get(random.nextInt(nodes.size()));
            if (target.nodeType == NodeType.TEXT || target == d) continue;
            try {
            switch (random.nextInt(9)) {
                case 0 -> target.appendChild(new HtmlElement(random.nextBoolean() ? "div" : "span"));
                case 1 -> {
                    if (!target.children.isEmpty() && target != d) {
                        HtmlNode kid = target.children.get(random.nextInt(target.children.size()));
                        target.removeChild(kid);
                    }
                }
                case 2 -> { // 木の中での付け替え（同じ文書の別の親へ）
                    HtmlNode mover = nodes.get(random.nextInt(nodes.size()));
                    if (mover != d && mover != target && !mover.contains(target) && mover.parent != null) {
                        target.appendChild(mover);
                    }
                }
                case 3 -> target.setTextContent("t" + step);
                case 4 -> target.setAttribute("class", "m" + random.nextInt(5) + " k" + step);
                case 5 -> { // 子の並びを直接触る経路（パーサ・バインディング。親は呼び出し側が先に結ぶ）: まとめて足す・条件で外す
                    if (random.nextBoolean()) {
                        HtmlElement em = new HtmlElement("em"), u = new HtmlElement("u");
                        em.parent = target;
                        u.parent = target;
                        target.children.addAll(random.nextInt(target.children.size() + 1), List.of(em, u));
                    } else {
                        target.children.removeIf(c -> c.nodeType == NodeType.ELEMENT && random.nextInt(3) == 0);
                    }
                }
                case 6 -> {
                    if (!target.children.isEmpty()) {
                        HtmlElement s = new HtmlElement("s");
                        s.parent = target;
                        HtmlNode old = target.children.set(random.nextInt(target.children.size()), s);
                        old.parent = null;
                    }
                }
                case 7 -> target.setInnerHTML("<b class=\"in" + step + "\">x</b><i>y</i>");
                default -> {
                    if (!target.children.isEmpty()) {
                        HtmlNode ref = target.children.get(random.nextInt(target.children.size()));
                        target.insertBefore(new HtmlElement("i"), ref);
                    }
                }
            }
            } catch (RuntimeException ignoredIllegalHierarchyMove) {
                // DOM が拒否した操作（循環など）。木は変わっていないので、そのまま検証へ進む
            }
            assertTreeIndexesMatch(d);
            NodeIds a = d.nodeIds();
            int treeSize = all(d).size();
            // 静的領域から外れた節は欠番として予約されるので、生きている番号 = 木にいる節の数
            assertEquals(treeSize, a.liveCount(), "step " + step + ": 生きている番号と木の節の数が一致する（漏れなし）");
        }
        assertNotNull(body);
    }

    @Test
    void staticNodeKeepsItsKeyWhenMovedAndDynamicNumbersAreRecycled() {
        HtmlDocument d = doc(PAGE);
        HtmlNode a = d.getElementById("a");
        HtmlNode span = a.children.get(0);
        String spanKey = span.key();
        int staticCount = d.nodeIds().staticCount();

        HtmlNode ul = d.querySelector("ul");
        ul.appendChild(span); // 移動
        assertEquals(spanKey, span.key(), "静的な節は移動しても鍵が変わらない");
        assertSame(span, d.getNodeById(spanKey));

        HtmlElement fresh = new HtmlElement("div");
        ul.appendChild(fresh);
        int freshId = fresh.nodeId();
        assertTrue(freshId >= staticCount, "新しい節は動的領域");
        ul.removeChild(fresh);
        HtmlElement next = new HtmlElement("div");
        ul.appendChild(next);
        assertEquals(freshId, next.nodeId(), "動的な番号は解放後に再利用される");
        assertNull(d.getNodeById("~9999"), "存在しない番号は引けない");
    }

    @Test
    void detachedFragmentsGetTheirOwnNumberingOnDemand() {
        HtmlNode frag = HtmlParser.parseFragment("<p>a<b>b</b></p><i>c</i>", "div");
        List<HtmlNode> nodes = all(frag);
        for (int i = 0; i < nodes.size(); i++) assertEquals(i, nodes.get(i).nodeId());
        HtmlNode p = frag.children.get(0);
        assertSame(p, frag.getNodeById(p.key()));
        assertNotSame(nodes.get(0).nodeIds(), doc(PAGE).nodeIds());
        assertTreeIndexesMatch(frag);
    }

    @Test
    void virtualNodesDoNotOccupyDocumentSlots() {
        HtmlDocument d = doc(PAGE);
        NodeIds a = d.nodeIds();
        int live = a.liveCount();
        HtmlNode owner = d.getElementById("a");
        HtmlNode pseudo = new HtmlNode("span"); // 描画が作る仮想の節: parent は持つが子の並びには載らない
        pseudo.parent = owner;
        pseudo.setId("a_before");
        assertTrue(pseudo.isVirtualNode());

        InteractionState state = new InteractionState();
        state.setHovered(pseudo, true);
        assertTrue(state.get(pseudo).isHovered());
        assertEquals(live, a.liveCount(), "仮想の節の状態で番号を消費しない");
        state.markAllDirty(); // 仮想の節も列挙に含まれる
        assertTrue(state.get(pseudo).isStyleDirty());
    }

    @Test
    void classAndTagAreHashedIntoTheSelectorIndex() {
        HtmlDocument d = doc(PAGE);
        HtmlNode a = d.getElementById("a");
        AtomTable atoms = AtomTable.shared();

        int card = atoms.lookup("card"), big = atoms.lookup("big");
        assertNotEquals(0, card);
        assertArrayEquals(new int[]{card, big}, SelectorIndex.classAtoms(a));
        long self = SelectorIndex.selfBloom(a);
        assertTrue(AtomTable.mayContain(self, AtomTable.bloomBits(card)));
        assertTrue(AtomTable.mayContain(self, AtomTable.bloomBits(atoms.lookup("div"))));
        assertTrue(AtomTable.mayContain(self, AtomTable.bloomBits(atoms.lookup("a"))), "id も入る");

        // 子孫の class は祖先の子孫 Bloom に入る（:has() の棄却用）
        HtmlNode li = d.querySelector("li.on");
        long on = AtomTable.bloomBits(atoms.lookup("on"));
        assertTrue(AtomTable.mayContain(SelectorIndex.subtreeBloom(li.parent), on));
        assertTrue(AtomTable.mayContain(SelectorIndex.subtreeBloom(d), on));

        // class を書き換えたら作り直される
        a.attrs.put("class", "zzz-m1-new");
        int fresh = atoms.lookup("zzz-m1-new");
        assertArrayEquals(new int[]{fresh}, SelectorIndex.classAtoms(a));
        assertTrue(AtomTable.mayContain(SelectorIndex.selfBloom(a), AtomTable.bloomBits(fresh)));
        assertTrue(AtomTable.mayContain(SelectorIndex.subtreeBloom(d), AtomTable.bloomBits(fresh)), "祖先へ広がる");
    }

    /** 番号の無い節（仮想の節・切り離された木）も索引を持つ（以前は番号を振った節だけが Bloom を持っていた）。 */
    @Test
    void selectorIndexDoesNotNeedNumbers() {
        HtmlElement detached = new HtmlElement("section");
        detached.setAttribute("class", "zzz-m5-detached");
        HtmlElement kid = new HtmlElement("p");
        kid.setAttribute("id", "zzz-m5-kid");
        detached.appendChild(kid);
        assertFalse(detached.hasNodeId(), "番号は問われるまで振らない");
        AtomTable atoms = AtomTable.shared();
        assertTrue(detached.hasClass("zzz-m5-detached"));
        assertTrue(AtomTable.mayContain(SelectorIndex.subtreeBloom(detached), AtomTable.bloomBits(atoms.lookup("zzz-m5-kid"))));
        assertFalse(detached.hasNodeId(), "索引を読んでも番号を振らない");

        HtmlNode pseudo = new HtmlNode("span");
        pseudo.parent = detached; // 子の並びに載らない仮想の節
        pseudo.setAttribute("class", "zzz-m5-virtual");
        assertTrue(pseudo.hasClass("zzz-m5-virtual"));
        assertFalse(AtomTable.mayContain(SelectorIndex.subtreeBloom(detached), AtomTable.bloomBits(atoms.lookup("zzz-m5-virtual"))),
                "仮想の節は親の子孫 Bloom に混ざらない");
        assertEquals(-1, pseudo.indexInParent());
    }

    @Test
    void interactionStateKeepsPseudoStateInOneStoreOnly() {
        HtmlDocument d = doc(PAGE);
        HtmlNode b = d.getElementById("bold");
        InteractionState state = new InteractionState();

        assertTrue(state.setHovered(b, true));
        assertFalse(state.setHovered(b, true), "同じ値の再設定は変化なし");
        assertTrue((state.stateBitsOf(b) & ElementState.HOVER) != 0);
        assertTrue(state.setActive(b, true));
        assertTrue((state.stateBitsOf(b) & ElementState.ACTIVE) != 0);
        state.setHovered(b, false);
        assertEquals(0, state.stateBitsOf(b) & ElementState.HOVER);
        assertTrue((state.stateBitsOf(b) & ElementState.ACTIVE) != 0, "別のビットは残る");

        state.setChecked(b, true);
        state.setDisabled(b, true);
        int both = ElementState.CHECKED | ElementState.DISABLED;
        assertEquals(both, state.stateBitsOf(b) & both);

        // アリーナには対話状態を写さない（二重書き込みの撤去）。汚れの印は StateFlags だけが持つ
        assertTrue(state.get(b).isStyleDirty(), "作りたては汚れ");
        state.clearStyleDirty(b);
        assertFalse(state.get(b).isStyleDirty());
        state.markDirty(b);
        assertTrue(state.get(b).isStyleDirty());
    }

    @Test
    void dirtyMarksLiveInOneIntAndStayIndependent() {
        HtmlDocument d = doc("<div id='p'><span id='c'></span></div>");
        HtmlNode p = d.getElementById("p");
        HtmlNode c = d.getElementById("c");
        InteractionState state = new InteractionState();
        StateFlags f = state.getOrCreate(p);
        int all = StateFlags.DIRTY_STYLE | StateFlags.DIRTY_SUBTREE
                | StateFlags.DIRTY_BELOW | StateFlags.DIRTY_UNSCOPED;
        assertEquals(all, f.dirtyBits(), "作りたては安全側に 4 ビットすべて");
        assertTrue(state.hasDirtyNodes());

        f.clearSubtreeDirty();
        assertEquals(all & ~StateFlags.DIRTY_SUBTREE, f.dirtyBits(), "他のビットは動かない");
        assertTrue(f.isStyleDirty() && f.dirtyBelow() && f.unscoped() && !f.subtreeDirty());
        f.markSubtreeDirty();
        assertEquals(all, f.dirtyBits());

        // style ビットを下ろす（subtree / unscoped も下りる）。below は別に下ろす
        state.clearStyleDirty(p);
        assertFalse(f.isStyleDirty() || f.subtreeDirty() || f.unscoped());
        assertTrue(f.dirtyBelow(), "below は clearStyleDirty では動かない");
        f.clearDirtyBelow();
        assertEquals(0, f.dirtyBits());
        state.getOrCreate(c); // 子の印も作りたて（汚れ）なので、先に作って下ろす
        state.clearStyleDirty(c);
        assertFalse(state.hasDirtyNodes(), "style ビットが全部下りたら汚れた節は無い");

        // 子を汚すと、子は style/subtree/unscoped、親は below だけが立つ
        state.markDirty(c);
        StateFlags pf = state.get(p);
        assertTrue(state.get(c).isStyleDirty() && state.get(c).subtreeDirty() && state.get(c).unscoped());
        assertTrue(pf.dirtyBelow());
        assertFalse(pf.isStyleDirty());
        assertFalse(pf.subtreeDirty());
        assertTrue(state.hasDirtyNodes());
        state.clearStyleDirty(c);
        assertFalse(state.get(c).isStyleDirty());
        assertTrue(state.hasDirtyNodes(), "祖先の印は markDirty が作りたて（汚れ）で作るので残る");
    }

    @Test
    void triStateOverridesAttributeDerivedBitsAndNullRestoresThem() {
        HtmlDocument d = doc("<input id='i' checked disabled>");
        HtmlNode i = d.getElementById("i");
        InteractionState state = new InteractionState();
        int both = ElementState.CHECKED | ElementState.DISABLED;
        assertEquals(both, state.stateBitsOf(i), "属性から決まる値");
        state.setChecked(i, false);
        assertEquals(ElementState.DISABLED, state.stateBitsOf(i) & both, "偽の指定は属性由来の checked を打ち消す");
        state.setDisabled(i, true);
        state.setChecked(i, null);
        assertEquals(both, state.stateBitsOf(i) & both, "null で属性由来に戻る");
        assertFalse(state.setChecked(i, null), "未指定→未指定は変化なし");
        state.setOpen(i, true);
        state.setIndeterminate(i, true);
        assertEquals(ElementState.OPEN | ElementState.INDETERMINATE,
                state.stateBitsOf(i) & (ElementState.OPEN | ElementState.INDETERMINATE));
        assertFalse(state.setOpen(i, true));
        assertTrue(state.setOpen(i, false));
    }

    @Test
    void focusBitsKeepFocusWithinSemantics() {
        HtmlDocument d = doc(PAGE);
        HtmlNode b = d.getElementById("bold");
        InteractionState state = new InteractionState();
        assertTrue(state.setFocusedNode(b, true));
        int bits = state.stateBitsOf(b);
        assertTrue((bits & ElementState.FOCUS) != 0);
        assertTrue((bits & ElementState.FOCUS_VISIBLE) != 0);
        assertTrue((bits & ElementState.FOCUS_WITHIN) != 0);
        assertTrue((state.stateBitsOf(b.parent) & ElementState.FOCUS_WITHIN) != 0, "祖先は focus-within");
        assertEquals(0, state.stateBitsOf(b.parent) & ElementState.FOCUS);
        assertTrue(state.setFocusedNode(null, false));
        assertEquals(0, state.stateBitsOf(b) & (ElementState.FOCUS | ElementState.FOCUS_VISIBLE | ElementState.FOCUS_WITHIN));
        assertEquals(0, state.stateBitsOf(b.parent) & ElementState.FOCUS_WITHIN);
    }

    @Test
    void interactionStateSurvivesMovesAndDiesWithRemovedNodes() {
        HtmlDocument d = doc(PAGE);
        HtmlNode b = d.getElementById("bold");
        InteractionState state = new InteractionState();
        state.setHovered(b, true);
        StateFlags flags = state.get(b);

        d.querySelector("ul").appendChild(b); // 移動（取り外し→取り付け）
        assertSame(flags, state.get(b), "移動しても状態は節に付いてくる");
        assertTrue(state.get(b).isHovered());

        HtmlElement temp = new HtmlElement("em");
        d.querySelector("ul").appendChild(temp);
        state.setHovered(temp, true);
        int tempId = temp.nodeId();
        d.querySelector("ul").removeChild(temp);
        HtmlElement other = new HtmlElement("em");
        d.querySelector("ul").appendChild(other);
        assertEquals(tempId, other.nodeId(), "番号は再利用される");
        assertNull(state.get(other), "再利用された番号に前の持ち主の状態は見えない");
        assertEquals(0, state.stateBitsOf(other) & ElementState.INTERACTIVE_MASK);
    }

    @Test
    void clearForgetsAllStatesAndDirtyAccountingFollowsNodes() {
        HtmlDocument d = doc(PAGE);
        InteractionState state = new InteractionState();
        for (HtmlNode n : all(d)) state.getOrCreate(n);
        assertTrue(state.hasDirtyNodes());
        for (HtmlNode n : all(d)) state.clearStyleDirty(n);
        assertFalse(state.hasDirtyNodes());
        state.markAllDirty();
        assertTrue(state.hasDirtyNodes());
        state.clear();
        assertNull(state.get(d.getElementById("a")));
        assertFalse(state.hasDirtyNodes());
    }
}
