package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * サブグリッド（CSS Grid 2 §9: {@code grid-template-columns / -rows: subgrid}）。
 * サブグリッドの子は、親グリッドのトラック・線・gap を共有する。
 */
@Disabled("G2 subgrid が新経路に無い。計画書 §3.2「新経路の穴（M2c-3 の移植で見えた）」")
public class SubgridTest {

    private static final int VIEWPORT_W = 600;
    private static final int VIEWPORT_H = 400;

    @Test
    public void testColumnsSubgridSharesParentTracks() {
        // 親は 100 / 200 / 100。2 列目から 2 本にまたがるサブグリッドの子は 200 / 100 に並ぶ
        PaintView root = layout("""
                <div style="display: grid; width: 400px; grid-template-columns: 100px 200px 100px;">
                  <div id="s" style="display: grid; grid-column: 2 / span 2; grid-template-columns: subgrid;">
                    <div id="a" style="height: 10px;"></div>
                    <div id="b" style="height: 10px;"></div>
                  </div>
                </div>
                """);
        assertEquals(300, find(root, "s").w(), 0.01f);
        assertEquals(200, find(root, "a").w(), 0.01f);
        assertEquals(100, find(root, "b").w(), 0.01f);
        assertEquals(200, find(root, "b").x() - find(root, "a").x(), 0.01f);
        assertEquals(100, find(root, "a").x() - find(root, "s").x() + 100, 0.01f); // サブグリッドの左端 = 2 本目の線
    }

    @Test
    public void testSubgridItemsContributeToParentIntrinsicTracks() {
        // 親の列は auto。サブグリッド内の幅 80px / 50px の子が、それぞれ親の列の寸法を決める
        PaintView root = layout("""
                <div style="display: grid; width: 400px; justify-content: start; grid-template-columns: auto auto;">
                  <div id="s" style="display: grid; grid-column: 1 / span 2; grid-template-columns: subgrid;">
                    <div id="a" style="width: 80px; height: 10px;"></div>
                    <div id="b" style="width: 50px; height: 10px;"></div>
                  </div>
                  <div id="c" style="height: 10px;"></div>
                  <div id="d" style="height: 10px;"></div>
                </div>
                """);
        // 親の列 = 80 / 50（stretch なし: justify-content: start でも auto は残りを分けるので下限だけ確認）
        assertTrue(find(root, "c").w() >= 80 - 0.01f, "親の 1 列目はサブグリッドの子の幅以上: " + find(root, "c").w());
        assertTrue(find(root, "d").w() >= 50 - 0.01f);
        // サブグリッドの子と、親の次の行の項目は同じ列に揃う
        assertEquals(find(root, "c").x(), find(root, "a").x(), 0.01f);
        assertEquals(find(root, "d").x(), find(root, "b").x(), 0.01f);
        assertEquals(find(root, "c").w(), find(root, "a").w(), 0.01f);
    }

    @Test
    public void testRowsSubgridAlignsAcrossSiblings() {
        // 各カードは 3 行にまたがるサブグリッド。見出し・本文・フッタの高さが、カードをまたいで揃う
        PaintView root = layout("""
                <div style="display: grid; width: 300px; grid-template-columns: 1fr 1fr; align-items: start;">
                  <div id="c1" style="display: grid; grid-row: span 3; grid-template-rows: subgrid;">
                    <div id="h1" style="height: 20px;"></div>
                    <div id="b1" style="height: 80px;"></div>
                    <div id="f1" style="height: 10px;"></div>
                  </div>
                  <div id="c2" style="display: grid; grid-row: span 3; grid-template-rows: subgrid;">
                    <div id="h2" style="height: 40px;"></div>
                    <div id="b2" style="height: 30px;"></div>
                    <div id="f2" style="height: 10px;"></div>
                  </div>
                </div>
                """);
        // 行 = max(20,40) / max(80,30) / 10
        assertEquals(find(root, "h1").y(), find(root, "h2").y(), 0.01f);
        assertEquals(find(root, "b1").y(), find(root, "b2").y(), 0.01f);
        assertEquals(find(root, "f1").y(), find(root, "f2").y(), 0.01f);
        assertEquals(40, find(root, "b1").y() - find(root, "h1").y(), 0.01f);
        assertEquals(80, find(root, "f1").y() - find(root, "b1").y(), 0.01f);
        assertEquals(130, find(root, "c1").h(), 0.01f);
        assertEquals(130, find(root, "c2").h(), 0.01f);
    }

    @Test
    public void testSubgridGapDiffersFromParent() {
        // 親 gap 20px・サブグリッド gap 0 でも、線の位置は親と同じ（隣り合うトラックが半分ずつ受け持つ）
        PaintView root = layout("""
                <div style="display: grid; width: 220px; column-gap: 20px; grid-template-columns: 100px 100px;">
                  <div id="s" style="display: grid; grid-column: 1 / span 2; column-gap: 0; grid-template-columns: subgrid;">
                    <div id="a" style="height: 10px;"></div>
                    <div id="b" style="height: 10px;"></div>
                  </div>
                </div>
                """);
        assertEquals(110, find(root, "a").w(), 0.01f);
        assertEquals(110, find(root, "b").w(), 0.01f);
        assertEquals(110, find(root, "b").x() - find(root, "a").x(), 0.01f);
    }

    @Test
    public void testNormalGapInheritsParentGap() {
        PaintView root = layout("""
                <div style="display: grid; width: 220px; column-gap: 20px; grid-template-columns: 100px 100px;">
                  <div id="s" style="display: grid; grid-column: 1 / span 2; grid-template-columns: subgrid;">
                    <div id="a" style="height: 10px;"></div>
                    <div id="b" style="height: 10px;"></div>
                  </div>
                </div>
                """);
        assertEquals(100, find(root, "a").w(), 0.01f);
        assertEquals(120, find(root, "b").x() - find(root, "a").x(), 0.01f);
    }

    @Test
    public void testSubgridPaddingActsAsEdgeMargin() {
        // サブグリッドの padding は端の項目の外側余白として働く。線の位置（= 隣の項目との揃え）は親のまま
        PaintView root = layout("""
                <div style="display: grid; width: 300px; grid-template-columns: 100px 200px;">
                  <div id="s" style="display: grid; grid-column: 1 / span 2; grid-template-columns: subgrid; padding: 0 10px;">
                    <div id="a" style="height: 10px;"></div>
                    <div id="b" style="height: 10px;"></div>
                  </div>
                </div>
                """);
        assertEquals(300, find(root, "s").w(), 0.01f);
        // 端の項目は padding ぶん内側へ寄り、幅はその分だけ狭い
        assertEquals(90, find(root, "a").w(), 0.01f);
        assertEquals(190, find(root, "b").w(), 0.01f);
        assertEquals(10, find(root, "a").x() - find(root, "s").x(), 0.01f);
        assertEquals(100, find(root, "b").x() - find(root, "s").x(), 0.01f);
    }

    @Test
    public void testSubgridPaddingContributesToParentTrack() {
        // 端の子の寄与にサブグリッドの padding が足される（親の auto 列が広がる）
        PaintView root = layout("""
                <div style="display: grid; justify-content: start; grid-template-columns: auto auto;">
                  <div id="s" style="display: grid; grid-column: 1 / span 2; grid-template-columns: subgrid; padding: 0 10px;">
                    <div id="a" style="width: 50px; height: 10px;"></div>
                    <div id="b" style="width: 30px; height: 10px;"></div>
                  </div>
                  <div id="c" style="height: 10px;"></div>
                </div>
                """);
        assertEquals(60, find(root, "c").w(), 0.01f);
        assertEquals(100, find(root, "s").w(), 0.01f);
    }

    @Test
    public void testLineNamesAreInheritedFromParent() {
        PaintView root = layout("""
                <div style="display: grid; width: 300px; grid-template-columns: [a] 100px [b] 200px [c];">
                  <div id="s" style="display: grid; grid-column: a / c; grid-template-columns: subgrid;">
                    <div id="x" style="grid-column: b / c; height: 10px;"></div>
                  </div>
                </div>
                """);
        assertEquals(200, find(root, "x").w(), 0.01f);
        assertEquals(100, find(root, "x").x() - find(root, "s").x(), 0.01f);
    }

    @Test
    public void testOwnLineNamesOnSubgrid() {
        PaintView root = layout("""
                <div style="display: grid; width: 300px; grid-template-columns: 100px 200px;">
                  <div id="s" style="display: grid; grid-column: 1 / span 2; grid-template-columns: subgrid [l] [m] [r];">
                    <div id="x" style="grid-column: m / r; height: 10px;"></div>
                  </div>
                </div>
                """);
        assertEquals(200, find(root, "x").w(), 0.01f);
    }

    @Test
    public void testSubgridOutsideGridBehavesAsNone() {
        // グリッドの項目でなければ subgrid は none と同じ（1 本の暗黙の列）
        PaintView root = layout("""
                <div style="display: block; width: 300px;">
                  <div id="s" style="display: grid; grid-template-columns: subgrid;">
                    <div id="a" style="height: 10px;"></div>
                  </div>
                </div>
                """);
        assertEquals(300, find(root, "a").w(), 0.01f);
    }

    @Test
    public void testItemsBeyondSubgridAreClampedToLastTrack() {
        PaintView root = layout("""
                <div style="display: grid; width: 300px; grid-template-columns: 100px 200px;">
                  <div id="s" style="display: grid; grid-column: 1 / span 2; grid-template-columns: subgrid;">
                    <div id="a" style="height: 10px;"></div>
                    <div id="b" style="height: 10px;"></div>
                    <div id="c" style="grid-column: 5; height: 10px;"></div>
                  </div>
                </div>
                """);
        // 暗黙の列は作られず、はみ出した c は最後のトラック（200px）に入る
        assertEquals(200, find(root, "c").w(), 0.01f);
        assertEquals(100, find(root, "c").x() - find(root, "s").x(), 0.01f);
    }

    @Test
    public void testBothAxesAndNesting() {
        // 2 段の入れ子: 孫も親の列を共有する
        PaintView root = layout("""
                <div style="display: grid; width: 300px; grid-template-columns: 100px 200px;">
                  <div id="s" style="display: grid; grid-column: 1 / span 2; grid-template-columns: subgrid;">
                    <div id="t" style="display: grid; grid-column: 2; grid-template-columns: subgrid;">
                      <div id="g" style="height: 10px;"></div>
                    </div>
                    <div id="a" style="height: 10px;"></div>
                  </div>
                </div>
                """);
        assertEquals(200, find(root, "t").w(), 0.01f);
        assertEquals(200, find(root, "g").w(), 0.01f);
        assertEquals(100, find(root, "g").x() - find(root, "s").x(), 0.01f);
    }

    @Test
    public void testRelayoutAfterWidthChangeKeepsAlignment() {
        // 幅だけが違う再レイアウトでも、測定の再利用が古い列の寸法を残さない
        String html = """
                <div style="display: grid; width: %dpx; grid-template-columns: 1fr 1fr;">
                  <div id="s" style="display: grid; grid-column: 1 / span 2; grid-template-columns: subgrid;">
                    <div id="a" style="height: 10px;"></div>
                    <div id="b" style="height: 10px;"></div>
                  </div>
                </div>
                """;
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<style>body { margin: 0; }</style>" + html.formatted(200), "", VIEWPORT_W, VIEWPORT_H);
        PaintView first = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        assertEquals(100, find(first, "a").w(), 0.01f);
        Document doc2 = parser.parseDocument("<style>body { margin: 0; }</style>" + html.formatted(400), "", VIEWPORT_W, VIEWPORT_H);
        PaintView second = parser.layoutDocument(doc2, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        assertEquals(200, find(second, "a").w(), 0.01f);
        assertEquals(200, find(second, "b").w(), 0.01f);
    }

    private static PaintView layout(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument("<style>body { margin: 0; }</style>" + html, "", VIEWPORT_W, VIEWPORT_H);
        return parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
    }

    private static PaintView find(PaintView box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (PaintView child : TestBoxes.elements(box)) {
            PaintView hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
