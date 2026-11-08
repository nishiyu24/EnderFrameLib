package com.nishiyu.enderframelib.core.dom;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.core.dom.node.HtmlDocument;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.NodeType;
import com.nishiyu.enderframelib.core.dom.parser.HtmlParser;
import com.nishiyu.enderframelib.core.kernel.memory.AtomTable;
import com.nishiyu.enderframelib.core.kernel.memory.DocumentArena;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import com.nishiyu.enderframelib.core.css.model.ElementState;
import org.junit.jupiter.api.Test;

/** Milestone 1: 節の同一性（鍵）・トポロジー・擬似状態はアリーナの列に載り、文字列 id を属性へ振らない。 */
class NodeArenaIdentityTest {

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
        DocumentArena arena = d1.arena();
        assertEquals(n1.size(), arena.staticCount());
        // 行きがけ順なら部分木は連続区間: 部分木の最大番号 + 1 = id + 部分木の大きさ
        for (HtmlNode n : n1) {
            int max = all(n).stream().mapToInt(HtmlNode::nodeId).max().getAsInt();
            assertEquals(n.nodeId() + all(n).size(), max + 1);
        }
    }

    @Test
    void topologyColumnsMirrorTheTree() {
        HtmlDocument d = doc(PAGE);
        assertTopologyMatches(d);
    }

    private static void assertTopologyMatches(HtmlNode root) {
        DocumentArena a = root.arena();
        for (HtmlNode n : all(root)) {
            int id = n.nodeId();
            assertSame(n, a.owner(id));
            int parentId = n == root ? DocumentArena.NONE : n.parent.nodeId();
            assertEquals(parentId, a.parent(id), "parent of " + n.tag);
            assertEquals(n.children.isEmpty() ? DocumentArena.NONE : n.children.get(0).nodeId(), a.firstChild(id));
            assertEquals(n.children.isEmpty() ? DocumentArena.NONE : n.children.get(n.children.size() - 1).nodeId(), a.lastChild(id));
            for (int i = 0; i < n.children.size(); i++) {
                int c = n.children.get(i).nodeId();
                assertEquals(i == 0 ? DocumentArena.NONE : n.children.get(i - 1).nodeId(), a.prevSibling(c));
                assertEquals(i == n.children.size() - 1 ? DocumentArena.NONE : n.children.get(i + 1).nodeId(), a.nextSibling(c));
            }
        }
    }

    @Test
    void mutationsKeepTopologyAndNeverLeakArenaSlots() {
        HtmlDocument d = doc(PAGE);
        HtmlNode body = d.getElementById("a").parent;
        Random random = new Random(7);
        for (int step = 0; step < 400; step++) {
            List<HtmlNode> nodes = all(d);
            HtmlNode target = nodes.get(random.nextInt(nodes.size()));
            if (target.nodeType == NodeType.TEXT || target == d) continue;
            try {
            switch (random.nextInt(5)) {
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
            assertTopologyMatches(d);
            DocumentArena a = d.arena();
            int treeSize = all(d).size();
            // 静的領域から外れた節は欠番として予約されるので、生きている番号 = 木にいる節の数
            assertEquals(treeSize, a.liveCount(), "step " + step + ": アリーナの生存数と木の節の数が一致する（漏れなし）");
        }
        assertNotNull(body);
    }

    @Test
    void staticNodeKeepsItsKeyWhenMovedAndDynamicNumbersAreRecycled() {
        HtmlDocument d = doc(PAGE);
        HtmlNode a = d.getElementById("a");
        HtmlNode span = a.children.get(0);
        String spanKey = span.key();
        int staticCount = d.arena().staticCount();

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
        assertNotSame(nodes.get(0).arena(), doc(PAGE).arena());
    }

    @Test
    void virtualNodesDoNotOccupyDocumentSlots() {
        HtmlDocument d = doc(PAGE);
        DocumentArena a = d.arena();
        int live = a.liveCount();
        HtmlNode owner = d.getElementById("a");
        HtmlNode pseudo = new HtmlNode("span"); // 描画が作る仮想の節: parent は持つが子の並びには載らない
        pseudo.parent = owner;
        pseudo.setId("a_before");
        assertTrue(pseudo.isVirtualNode());

        InteractionState state = new InteractionState();
        state.setHovered(pseudo, true);
        assertTrue(state.get(pseudo).isHovered());
        assertEquals(live, a.liveCount(), "仮想の節の状態でアリーナの番号を消費しない");
        state.markAllDirty(); // 仮想の節も列挙に含まれる
        assertTrue(state.get(pseudo).isStyleDirty());
    }

    @Test
    void classAndTagAreHashedIntoBloomColumns() {
        HtmlDocument d = doc(PAGE);
        HtmlNode a = d.getElementById("a");
        DocumentArena arena = d.arena();
        AtomTable atoms = AtomTable.shared();

        int card = atoms.lookup("card"), big = atoms.lookup("big");
        assertNotEquals(0, card);
        assertArrayEquals(new int[]{card, big}, a.classAtoms());
        long self = arena.selfBloom(a.nodeId());
        assertTrue(AtomTable.mayContain(self, AtomTable.bloomBits(card)));
        assertTrue(AtomTable.mayContain(self, AtomTable.bloomBits(atoms.lookup("div"))));
        assertEquals(atoms.lookup("a"), arena.idAtom(a.nodeId()));
        assertEquals(atoms.lookup("div"), DocumentArena.tagAtomOf(arena.tagAndType(a.nodeId())));

        // 子孫の class は祖先の subtreeBloom に入る（:has() の棄却用）
        HtmlNode li = d.querySelector("li.on");
        long on = AtomTable.bloomBits(atoms.lookup("on"));
        assertTrue(AtomTable.mayContain(arena.subtreeBloom(li.parent.nodeId()), on));
        assertTrue(AtomTable.mayContain(arena.subtreeBloom(d.nodeId()), on));

        // class を書き換えたら 1 回だけ作り直される
        a.attrs.put("class", "zzz-m1-new");
        int fresh = atoms.lookup("zzz-m1-new");
        assertArrayEquals(new int[]{fresh}, a.classAtoms());
        assertTrue(AtomTable.mayContain(arena.selfBloom(a.nodeId()), AtomTable.bloomBits(fresh)));
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
        InteractionState.StateFlags f = state.getOrCreate(p);
        int all = InteractionState.StateFlags.DIRTY_STYLE | InteractionState.StateFlags.DIRTY_SUBTREE
                | InteractionState.StateFlags.DIRTY_BELOW | InteractionState.StateFlags.DIRTY_UNSCOPED;
        assertEquals(all, f.dirtyBits(), "作りたては安全側に 4 ビットすべて");
        assertTrue(state.hasDirtyNodes());

        f.clearSubtreeDirty();
        assertEquals(all & ~InteractionState.StateFlags.DIRTY_SUBTREE, f.dirtyBits(), "他のビットは動かない");
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
        InteractionState.StateFlags pf = state.get(p);
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
        InteractionState.StateFlags flags = state.get(b);

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
