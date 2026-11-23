package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.ChangeLedger;
import com.nishiyu.enderframelib.core.dom.node.ChangeLedger.Kind;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * K5b L0（変更台帳。設計書 §3.6）: ①台帳の単体〔追記・凍結・世代・祖先を辿る {@code touches}・あふれ〕②<b>観測</b>: 既存の入口
 * 〔{@code pendingTextNodes}・{@code pendingChildParents}・{@code pendingAttrNodes}・再スタイルの汚れ印〕に載る変更は、
 * 乱数の操作列のどの時点でも全て台帳に載っている〔取りこぼし 0〕。台帳は観測だけで、挙動は変えない。
 */
public class ChangeLedgerTest {
    /** root(div) > [a(section) > b(p)], c(span)。{@code out} に b・c・a を返す。 */
    private static HtmlElement tree(HtmlElement[] out) {
        HtmlElement root = new HtmlElement("div"), a = new HtmlElement("section"), b = new HtmlElement("p"), c = new HtmlElement("span");
        root.appendChild(a);
        a.appendChild(b);
        root.appendChild(c);
        out[0] = b;
        out[1] = c;
        out[2] = a;
        return root;
    }

    @Test
    void appendFreezeAndTouchesWalkAncestorsOnly() {
        HtmlElement[] n = new HtmlElement[3];
        HtmlElement root = tree(n);
        HtmlElement b = n[0], c = n[1], a = n[2];
        ChangeLedger l = new ChangeLedger();
        assertEquals(0L, l.freeze());
        assertEquals(0L, l.epoch(), "何も無い静止フレームは世代を進めない");
        l.append(b, Kind.TEXT);
        l.append(b, Kind.TEXT); // 同じ節の同じ変更が続くだけ
        assertEquals(1, l.pendingCount());
        assertTrue(l.pendingHas(b, Kind.TEXT));
        assertFalse(l.pendingHas(b, Kind.ATTR));
        assertEquals(1L, l.freeze());
        assertEquals(0, l.pendingCount());
        assertTrue(l.touches(b) && l.touches(a) && l.touches(root), "自分と祖先は触れられている");
        assertFalse(l.touches(c), "兄弟の部分木は触れられていない");
        assertEquals(2L, l.freeze(), "凍結した世代が残っているので次の凍結で世代が進み、中身は空になる");
        assertFalse(l.touches(b));
        assertEquals(2L, l.freeze(), "空から空は進めない");
    }

    @Test
    void nodelessAndOverflowTouchEverything() {
        HtmlElement[] n = new HtmlElement[3];
        tree(n);
        ChangeLedger l = new ChangeLedger();
        l.append(null, Kind.ENV);
        l.freeze();
        assertTrue(l.touches(n[1]), "節を持たない変更は全体に効く");
        l.freeze();
        for (int i = 0; i < ChangeLedger.MAX_ENTRIES + 5; i++) l.append(new HtmlElement("i"), Kind.STYLE);
        assertEquals(Integer.MAX_VALUE, l.pendingCount(), "あふれたら個々を捨てて全体");
        l.freeze();
        assertTrue(l.touches(n[1]));
        assertEquals(Integer.MAX_VALUE, l.frozenCount());
    }

    private static final String HTML = "<style>.hot{color:red} .row:hover{background:#eef} .big{font-size:20px}</style>"
            + "<div id=list><div class=row id=r0>row 0</div><div class=row id=r1>row 1 <b>x</b></div><div class=row id=r2>row 2</div>"
            + "<div class=row id=r3>row 3</div><div class=row id=r4>row 4</div></div><progress id=pg value=3 max=10></progress><p id=tail>tail</p>";

    private static void walk(HtmlNode n, List<HtmlNode> out) {
        out.add(n);
        for (HtmlNode c : n.children) walk(c, out);
    }

    /** 台帳がこの節自身か、祖先の子の増減で覆っているか（新しく付いた部分木の節は、親の {@code CHILDREN} が覆う）。 */
    private static boolean covered(ChangeLedger l, HtmlNode node) {
        for (HtmlNode cur = node; cur != null; cur = cur.parent) {
            if (l.pendingTouches(cur) && (cur == node || l.pendingHas(cur, Kind.CHILDREN))) return true;
        }
        return false;
    }

    @Test
    void everyExistingInletIsCoveredByTheLedgerAtEveryStep() {
        for (long seed = 1; seed <= 12; seed++) {
            Random rnd = new Random(seed);
            try (StandaloneView v = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK); var be = new MutationScenario.NoDraw()) {
                ResizeSettle.freezeClock(v); // 止めた時計: リサイズの間引きが実時間に依らない（遅い実行でだけ落ちた）
                UiView ui = v.ui();
                ChangeLedger l = ui.interactionState().changeLedger();
                long now = 1000;
                v.frame(now, be, true);
                // 既存の入口は、フレームで取り除かれず残ることがある〔<style> の変更で全体の再レイアウトに入る経路など〕。残りかすは今の変更ではない
                java.util.Set<HtmlNode> leftover = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                java.util.Set<HtmlNode> leftoverText = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                java.util.Set<HtmlNode> leftoverAttr = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (int step = 0; step < 40; step++) {
                    List<HtmlNode> all = new ArrayList<>();
                    walk(v.root(), all);
                    HtmlNode t = all.get(rnd.nextInt(all.size()));
                    String what;
                    switch (rnd.nextInt(8)) {
                        case 0 -> { if (t instanceof HtmlElement e) e.setAttribute("class", rnd.nextBoolean() ? "row hot" : "row"); what = "class"; }
                        case 1 -> { if (t instanceof HtmlElement e) e.setAttribute("style", "padding-left:" + rnd.nextInt(20) + "px"); what = "style"; }
                        case 2 -> { if (t instanceof HtmlText x) x.setData("t" + rnd.nextInt(1000)); what = "text"; }
                        case 3 -> {
                            if (t instanceof HtmlElement e) {
                                HtmlElement add = (HtmlElement) v.root().ownerDocument.createElement("div");
                                add.setAttribute("class", "row");
                                add.setTextContent("n" + step);
                                e.appendChild(add);
                            }
                            what = "append";
                        }
                        case 4 -> { if (!t.children.isEmpty() && t != v.root()) t.removeChild(t.children.get(rnd.nextInt(t.children.size()))); what = "remove"; }
                        case 5 -> { v.mouseMove(rnd.nextInt(320), rnd.nextInt(240), be.sessionId()); what = "hover"; }
                        case 6 -> { HtmlNode pg = v.root().getNodeById("pg"); if (pg instanceof HtmlElement e) e.setAttribute("value", String.valueOf(rnd.nextInt(10))); what = "attr"; }
                        default -> { v.resize(300 + rnd.nextInt(40), 240); what = "resize"; }
                    }
                    // 既存の入口に載ったものは、フレームを回す前に全て台帳に載っている
                    String where = "seed " + seed + " step " + step + " " + what;
                    for (HtmlNode n : ui.pendingTextNodes) assertTrue(l.pendingHas(n, Kind.TEXT) || leftoverText.contains(n), where + ": 文字の節");
                    for (HtmlNode n : ui.localPatches.pendingChildParents) assertTrue(l.pendingHas(n, Kind.CHILDREN) || leftover.contains(n), where + ": 子の増減の親 " + n.tag + "#" + n.getId() + " pending=" + java.util.Arrays.toString(l.pendingKinds()) + " count=" + l.pendingCount());
                    for (HtmlNode n : ui.pendingAttrNodes) assertTrue(l.pendingHas(n, Kind.ATTR) || leftoverAttr.contains(n), where + ": 属性の節");
                    List<HtmlNode> nodes = new ArrayList<>();
                    walk(v.root(), nodes);
                    for (HtmlNode n : nodes) {
                        var f = ui.interactionState().get(n);
                        if (f != null && f.isStyleDirty()) assertTrue(covered(l, n), where + ": 汚れた節 " + n.tag + "#" + n.getId());
                    }
                    now += 16;
                    v.frame(now, be, true);
                    assertEquals(0, l.pendingCount(), where + ": フレームの始めに凍結される");
                    leftover.clear();
                    leftover.addAll(ui.localPatches.pendingChildParents);
                    leftoverText.clear();
                    leftoverText.addAll(ui.pendingTextNodes);
                    leftoverAttr.clear();
                    leftoverAttr.addAll(ui.pendingAttrNodes);
                }
            }
        }
    }

    @Test
    void hoverAndResizeReachTheLedger() {
        try (StandaloneView v = new StandaloneView(HTML, 320, 240, FontResolver.FALLBACK); var be = new MutationScenario.NoDraw()) {
            ChangeLedger l = v.ui().interactionState().changeLedger();
            v.frame(1000, be, true);
            assertEquals(0, l.pendingCount());
            v.mouseMove(10, 5, be.sessionId());
            assertTrue(l.pendingCount() > 0, "ホバーで台帳に載る（対話状態の入口）");
            v.frame(1016, be, true);
            v.resize(300, 240);
            assertEquals(Integer.MAX_VALUE, l.pendingCount(), "リサイズは全体に効く（ENV）");
        }
    }
}
