package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.EngineContext;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P6（全木走査の削減）の正当性・等価性テスト。
 * ReusableTable の再利用と arena nodeId 再利用耐性、markRootRestyleChanges / clearAllSubtreeMarks の枝刈り、
 * resetPlacementsFast + fragmentsStale 遅延破棄、BoxTreeBuilder の linkParents 撤去と subtreeHasGenerated 枝刈りを検証する。
 */
public class P6IncrementalTraversalTest {

    @AfterEach
    void restoreGlobals() {
        EngineContext.global().incrementalLayout = true;
        EngineContext.global().inPlaceRestyleRelayout = true;
    }

    private static List<String> sigs(StandaloneView v) {
        v.ui().flushInvalidation();
        List<String> out = new ArrayList<>();
        for (DisplayItem item : v.ui().renderCache()) {
            out.add(MutationScenario.sig(item));
        }
        return out;
    }

    private static void assertAllParentsLinked(LayoutBox box, LayoutBox expectedParent) {
        assertSame(expectedParent, box.parent, () -> "parent link mismatch at node " + box.node);
        for (int i = 0; i < box.children.size(); i++) {
            assertAllParentsLinked(box.children.get(i), box);
        }
    }

    private static void assertNoStaleFragments(LayoutBox box) {
        assertFalse(box.fragmentsStale, () -> "fragmentsStale left set on " + box.node);
        for (int i = 0; i < box.children.size(); i++) {
            assertNoStaleFragments(box.children.get(i));
        }
    }

    @Test
    void incrementalBuildMatchesFreshWithPseudoTableDisplayNoneAndDynamicNodes() {
        String html = """
                <style>
                  body { margin: 0; font-size: 13px; }
                  .p::before { content: "["; color: #0a0; }
                  .p::after { content: "]"; color: #00a; }
                  .hide { display: none; }
                  .tbl { display: table; border: 1px solid #444; margin: 4px; }
                  .row { display: table-row; }
                  .cell { display: table-cell; padding: 3px; border: 1px solid #888; }
                  .contents { display: contents; }
                </style>
                <div id="host">
                  <p id="p1" class="p">alpha <span id="inline1">beta</span></p>
                  <ul id="ul1"><li id="li1">item one</li><li id="li2">item two</li></ul>
                  <div id="vis" class="hide"><span id="inner">hidden text that wraps when visible</span></div>
                  <div id="boxtbl" class="tbl"><div id="tr1" class="row"><div id="tc1" class="cell">cell</div></div></div>
                  <div id="cnt" class="contents"><span id="cchild">in contents</span></div>
                </div>
                """;
        StandaloneView inc = new StandaloneView(html, 300, 260, FontResolver.FALLBACK);
        StandaloneView fresh = new StandaloneView(html, 300, 260, FontResolver.FALLBACK);
        try {
            EngineContext.global().incrementalLayout = false;
            List<String> exp0 = sigs(fresh);
            EngineContext.global().incrementalLayout = true;
            assertEquals(exp0, sigs(inc));
            assertAllParentsLinked(inc.ui().layoutRoot(), null);

            // 1. display: none <-> block 切り替え + 動的要素の追加・削除・再作成（DocumentArena nodeId 再利用）
            runBoth(inc, fresh, v -> {
                v.root().getNodeById("vis").setAttribute("class", "");
                HtmlNode host = v.root().getNodeById("host");
                HtmlElement dyn = (HtmlElement) v.root().ownerDocument.createElement("div");
                dyn.setAttribute("id", "dyn1");
                dyn.setTextContent("first");
                host.appendChild(dyn);
            });

            // 2. 動的テキスト差し替え（nodeId フリーリスト再利用を含む）＋ ::before/::after 更新
            runBoth(inc, fresh, v -> {
                HtmlElement dyn = (HtmlElement) v.root().getNodeById("dyn1");
                dyn.setTextContent("replaced longer text");
                v.root().getNodeById("p1").setAttribute("style", "color: #f00; padding-left: 8px;");
            });

            // 3. table <-> table-row <-> block 遷移と匿名ブロック再混在
            runBoth(inc, fresh, v -> {
                v.root().getNodeById("boxtbl").setAttribute("class", "row");
                v.root().getNodeById("vis").setAttribute("class", "hide");
                v.root().getNodeById("host").removeChild(v.root().getNodeById("dyn1"));
                HtmlElement dyn2 = (HtmlElement) v.root().ownerDocument.createElement("span");
                dyn2.setAttribute("id", "dyn2");
                dyn2.setTextContent("inline-after-remove");
                v.root().getNodeById("host").appendChild(dyn2);
            });

            // 4. 再び表示 + リビルドで ReusableTable による再利用が効いていること
            runBoth(inc, fresh, v -> {
                v.root().getNodeById("boxtbl").setAttribute("class", "");
                v.root().getNodeById("vis").setAttribute("class", "");
                v.root().getNodeById("inline1").setAttribute("style", "font-size: 16px;");
            });
            assertFalse(RenderPipeline.lastBuildStats().contains("reused=0 "),
                    () -> "ReusableTable により既存箱が再利用されること: " + RenderPipeline.lastBuildStats());
            assertAllParentsLinked(inc.ui().layoutRoot(), null);
            assertNoStaleFragments(inc.ui().layoutRoot());
        } finally {
            inc.close();
            fresh.close();
        }
    }

    @Test
    void relayoutRootOnlyUsesRootPrunedMarkingAndMatchesFullMarking() {
        String html = """
                <style>
                  body { margin: 0; font-size: 12px; }
                  .wrap { padding: 4px; }
                  .card { border: 1px solid #666; margin-bottom: 4px; }
                  .hidden { display: none; }
                </style>
                <div class="wrap">
                  <div class="card" id="c1">word1 word2 word3 word4 word5 word6 word7</div>
                  <div class="hidden" id="h1"><div>never visible</div></div>
                  <div class="card" id="c2"><span>nested a</span> <b id="b2">nested b</b></div>
                </div>
                """;
        StandaloneView inc = new StandaloneView(html, 400, 300, FontResolver.FALLBACK);
        StandaloneView fresh = new StandaloneView(html, 400, 300, FontResolver.FALLBACK);
        try {
            EngineContext.global().incrementalLayout = false;
            List<String> exp0 = sigs(fresh);
            EngineContext.global().incrementalLayout = true;
            assertEquals(exp0, sigs(inc));

            long skipsBefore = RenderPipeline.resizeRebuildSkips();
            int[][] sizes = {{220, 300}, {140, 260}, {360, 220}};
            for (int[] sz : sizes) {
                EngineContext.global().incrementalLayout = false;
                fresh.ui().recomputeLayout(sz[0], sz[1]);
                List<String> exp = fresh.ui().renderCache().stream().map(MutationScenario::sig).toList();

                EngineContext.global().incrementalLayout = true;
                inc.ui().recomputeLayout(sz[0], sz[1]);
                List<String> act = inc.ui().renderCache().stream().map(MutationScenario::sig).toList();

                assertEquals(exp, act, "resize " + sz[0] + "x" + sz[1]);
                assertNoStaleFragments(inc.ui().layoutRoot());
            }
            assertTrue(RenderPipeline.resizeRebuildSkips() > skipsBefore, "relayoutRootOnly が効くこと");

            // restyleLayoutTree が inPlaceRestyleRelayout=false で recomputeLayout(relayoutRootOnly) へ落ちても一致すること
            EngineContext.global().inPlaceRestyleRelayout = false;
            runBoth(inc, fresh, v -> v.root().getNodeById("b2").setAttribute("style", "display:inline-block;width:90px"));
        } finally {
            inc.close();
            fresh.close();
        }
    }

    @Test
    void resetPlacementsFastClearsSkippedAndHiddenFragments() {
        String html = """
                <style>body { margin: 0; font-size: 13px; } .gone { display: none; }</style>
                <div id="keep">visible line that wraps cleanly at narrow width</div>
                <div id="toggle">line one line two line three line four</div>
                """;
        StandaloneView view = new StandaloneView(html, 150, 200, FontResolver.FALLBACK);
        StandaloneView fresh = new StandaloneView(html, 150, 200, FontResolver.FALLBACK);
        try {
            EngineContext.global().incrementalLayout = false;
            sigs(fresh);
            EngineContext.global().incrementalLayout = true;
            sigs(view);
            LayoutBox root = view.ui().layoutRoot();
            HtmlNode toggleTextNode = view.root().getNodeById("toggle").children.getFirst();
            LayoutBox textBox = root.findByNode(toggleTextNode);
            assertNotNull(textBox);
            assertFalse(textBox.inlineFragments.isEmpty());

            // resetPlacementsFast はまず fragmentsStale 印だけを付け、unvisited の箱は finishResetPlacements で回収する
            List<LayoutBox> stale = new ArrayList<>();
            LayoutBox.resetPlacementsFast(root, -1, stale);
            assertTrue(textBox.fragmentsStale, "resetPlacementsFast 直後は fragmentsStale が立つこと");
            assertFalse(textBox.inlineFragments.isEmpty(), "前払いの discardFragments を省いていること");
            LayoutBox.finishResetPlacements(stale);
            assertFalse(textBox.fragmentsStale);
            assertTrue(textBox.inlineFragments.isEmpty(), "layout が触れなかった箱の断片が finishResetPlacements で回収されること");
            assertTrue(stale.isEmpty());

            // 再配置（resize / display:none 往復）でも stale 断片を残さず fresh と一致すること
            runBoth(view, fresh, v -> v.root().getNodeById("toggle").setAttribute("class", "gone"));
            runBoth(view, fresh, v -> v.root().getNodeById("toggle").setAttribute("class", ""));
            assertNoStaleFragments(view.ui().layoutRoot());
        } finally {
            view.close();
            fresh.close();
        }
    }

    @Test
    void boxTreeBuilderPrunesGeneratedCollectionAndPreservesAnonymousAndChildPatch() {
        String html = """
                <style>
                  body { margin: 0; font-size: 13px; }
                  .lst { margin: 0; padding-left: 20px; }
                  .flat { display: block; }
                </style>
                <div id="mixed">inline prefix <div id="blk">inner block</div> inline suffix</div>
                <ul class="lst" id="list"><li id="a">A</li></ul>
                <div class="flat" id="patchParent"><div id="c1">one</div></div>
                """;
        StandaloneView inc = new StandaloneView(html, 300, 240, FontResolver.FALLBACK);
        StandaloneView fresh = new StandaloneView(html, 300, 240, FontResolver.FALLBACK);
        try {
            EngineContext.global().incrementalLayout = false;
            List<String> exp0 = sigs(fresh);
            EngineContext.global().incrementalLayout = true;
            assertEquals(exp0, sigs(inc));

            // 匿名ブロックを含まない flat 親へ混合ブロックを含む子を tryChildPatch で差し込んだ後、全体再構築しても一致する
            runBoth(inc, fresh, v -> {
                HtmlElement item = (HtmlElement) v.root().ownerDocument.createElement("div");
                item.setAttribute("id", "c2");
                HtmlElement inner = (HtmlElement) v.root().ownerDocument.createElement("div");
                inner.setAttribute("id", "c2b");
                inner.setTextContent("block");
                item.appendChild(v.root().ownerDocument.createTextNode("prefix "));
                item.appendChild(inner);
                v.root().getNodeById("patchParent").appendChild(item);
            });
            runBoth(inc, fresh, v -> {
                HtmlElement li = (HtmlElement) v.root().ownerDocument.createElement("li");
                li.setAttribute("id", "b");
                li.setTextContent("B");
                v.root().getNodeById("list").appendChild(li);
                v.root().getNodeById("blk").setAttribute("style", "padding: 6px;");
            });
            assertAllParentsLinked(inc.ui().layoutRoot(), null);
        } finally {
            inc.close();
            fresh.close();
        }
    }

    private static void runBoth(StandaloneView inc, StandaloneView fresh, java.util.function.Consumer<StandaloneView> mutate) {
        EngineContext.global().incrementalLayout = false;
        mutate.accept(fresh);
        List<String> exp = sigs(fresh);
        EngineContext.global().incrementalLayout = true;
        mutate.accept(inc);
        List<String> act = sigs(inc);
        assertEquals(exp, act);
    }
}
