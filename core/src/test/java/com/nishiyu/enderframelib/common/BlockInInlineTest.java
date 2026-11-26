package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.paint.tree.PaintRun;
import com.nishiyu.enderframelib.core.paint.tree.PaintView;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * インラインの中のブロック（CSS 2.1 §9.2.1.1。計画書 §3.2 の G1）: インライン要素の中のブロック水準の箱は、インラインの内容をその前後で割り、
 * 持ち主のブロックコンテナの流れに兄弟として並ぶ。割れた前後は匿名のブロックの行になる。
 *
 * <p>期待値は「匿名のブロックを本物の div に置き換えた文書」と同じ位置（ブラウザの組み方と同じ）で固定する。</p>
 */
class BlockInInlineTest {
    private static final String BASE = "<style>body{margin:0}</style>";

    private static PaintView layout(String html) {
        return TestBoxes.layout(BASE + html, 320, 240);
    }

    private static PaintView find(PaintView root, String id) {
        PaintView b = TestBoxes.find(root, id);
        assertNotNull(b, id);
        return b;
    }

    private static void assertSameRect(PaintView expected, PaintView actual, String what) {
        assertEquals(expected.x(), actual.x(), 0.01f, what + " x");
        assertEquals(expected.y(), actual.y(), 0.01f, what + " y");
        assertEquals(expected.w(), actual.w(), 0.01f, what + " w");
        assertEquals(expected.h(), actual.h(), 0.01f, what + " h");
    }

    private static List<PaintRun> boxRuns(PaintView b) {
        return b.runs().stream().filter(r -> !r.isText()).map(r -> (PaintRun) r).toList();
    }

    @Test
    void blockSplitsTheInlineAndTakesTheFullWidthBetweenItsHalves() {
        PaintView root = layout("<div id=c>x <span id=s>a<div id=d>b</div>c</span> y</div>");
        PaintView ref = layout("<div id=c><div>x a</div><div id=d>b</div><div>c y</div></div>");
        assertSameRect(find(ref, "d"), find(root, "d"), "ブロックは匿名のブロックの間に並ぶ");
        assertSameRect(find(ref, "c"), find(root, "c"), "容器の高さは 3 行分");
        assertEquals(320f, find(root, "d").w(), 0.01f, "ブロックは容器の幅いっぱい");
    }

    @Test
    void splitInlineHasAStartEdgeOnlyBeforeAndAnEndEdgeOnlyAfterTheBlock() {
        PaintView root = layout("<div><span id=s style='border:2px solid #000;padding:0 4px'>a<div id=d>b</div>c</span></div>");
        PaintView d = find(root, "d");
        List<PaintRun> runs = boxRuns(find(root, "s"));
        assertEquals(2, runs.size(), "割れたインラインは前後の 2 つの断片");
        assertTrue(runs.get(0).startEdge() && !runs.get(0).endEdge(), "前の断片は開始端だけ");
        assertTrue(!runs.get(1).startEdge() && runs.get(1).endEdge(), "後ろの断片は終了端だけ");
        assertTrue(runs.get(0).y() + runs.get(0).h() <= d.y() + 2.01f, "前の断片はブロックの上");
        assertTrue(runs.get(1).y() >= d.y() + d.h() - 2.01f, "後ろの断片はブロックの下");
        // 開始端の padding・border は前の断片にだけ: 後ろの断片の文字は行の左端から
        PaintView c = TestBoxes.elements(find(root, "s")).getLast();
        assertEquals(0f, c.runs().getFirst().x(), 0.01f);
    }

    @Test
    void blocksInsideAnAtomicInlineDoNotSplitTheLine() {
        // inline-block・inline-flex は 1 つの塊: 中のブロックは自分の中で組む（持ち主の流れへ出さない）。後ろのブロックの兄弟で
        // 容器が混在の流れになる場合（WPT aspect-ratio-intrinsic-size-010・flexbox-paint-ordering-001 で見つけた）
        for (String display : new String[]{"inline-block", "inline-flex"}) {
            String atom = "<span id=ib style='display:" + display + "'>%s</span>";
            PaintView root = layout("<div id=c>x " + atom.formatted("<div id=d>b</div>") + " y<p id=p>after</p></div>");
            PaintView ref = layout("<div id=c>x " + atom.formatted("b") + " y<p id=p>after</p></div>");
            assertSameRect(find(ref, "ib"), find(root, "ib"), display + ": 原子");
            assertSameRect(find(ref, "p"), find(root, "p"), display + ": 後ろのブロック");
            assertEquals(find(root, "ib").x(), find(root, "d").x(), 0.01f, display + ": ブロックは原子の中");
        }
    }

    @Test
    void blockMarginsBehaveAsInTheBlockFlow() {
        PaintView root = layout("<div id=c><span>a<div id=d style='margin:10px 20px;padding:3px'>b</div>c</span></div>");
        PaintView ref = layout("<div id=c><div>a</div><div id=d style='margin:10px 20px;padding:3px'>b</div><div>c</div></div>");
        assertSameRect(find(ref, "d"), find(root, "d"), "左右の余白と上下の余白");
        assertSameRect(find(ref, "c"), find(root, "c"), "容器");
    }

    @Test
    void whitespaceAroundTheBlockMakesNoLinesAndMarginsCollapseThrough() {
        String body = "<span id=h>\n  <div id=d><h3 id=t>title</h3><p id=p>text</p></div>\n</span><h3 id=o>other</h3>";
        PaintView root = layout(body);
        PaintView ref = layout("<style>#h{display:block}</style>" + body);
        for (String id : new String[]{"d", "t", "p", "o"}) assertSameRect(find(ref, id), find(root, id), id);
        assertSameRect(find(root, "d"), find(root, "h"), "インラインの矩形は中のブロックを囲む");
    }

    @Test
    void contentOfTheBlockIsDrawnAndTheInlineBackgroundIsNotBehindIt() {
        String html = BASE + "<div><span style='background:#ff0000'>a<div id=d style='background:#0000ff'>b<i>q</i></div>c</span></div>";
        List<DisplayItem> items = TestBoxes.render(html, 320, 240);
        assertTrue(items.stream().anyMatch(i -> i.type() == ElementType.TEXT && "b".equals(i.text())), "ブロックの文字");
        assertTrue(items.stream().anyMatch(i -> i.type() == ElementType.TEXT && "q".equals(i.text())), "ブロックの中のインラインの文字");
        long red = items.stream().filter(i -> i.type() == ElementType.RECT && (i.bgColor() & 0xFFFFFF) == 0xFF0000).count();
        assertEquals(2, red, "インラインの背景は前後の断片の 2 つだけ");
        // 画素: ブロックの行は青で、その中に文字の墨がある。インラインの赤はブロックの行に無い
        PaintView d = find(TestBoxes.layout(html, 320, 240), "d");
        try (var cpu = new SoftwareRenderBackend(320, 240, 1f).fonts(FontResolver.FALLBACK)) {
            cpu.render(items, 10_000L);
            assertBlockRowsAreBlueWithInk(cpu.pixmap(), d);
        }
    }

    private static void assertBlockRowsAreBlueWithInk(com.nishiyu.enderframelib.core.raster.Pixmap px, PaintView d) {
        int y0 = (int) Math.ceil(d.y()) + 1, y1 = (int) Math.floor(d.y() + d.h()) - 1;
        boolean ink = false;
        for (int y = y0; y <= y1; y++) {
            assertEquals(0xFF0000FF, px.getStraight(300, y), "ブロックの背景 y=" + y);
            for (int x = 0; x < 320; x++) {
                int p = px.getStraight(x, y);
                assertTrue((p & 0xFFFFFF) != 0xFF0000, "ブロックの行にインラインの背景 x=" + x + " y=" + y);
                ink |= p != 0xFF0000FF;
            }
        }
        assertTrue(ink, "ブロックの文字が描かれる");
    }

    @Test
    void paintTreeKeepsTheBlockUnderItsInlineAncestorInDocumentOrder() {
        PaintView root = layout("<div><b id=o>x<i id=i>y<div id=d>z</div>w</i>v</b></div>");
        List<PaintView> kids = TestBoxes.elements(find(root, "i"));
        assertEquals(3, kids.size(), "文字 y・ブロック・文字 w");
        assertEquals("d", kids.get(1).node.getId());
        List<PaintRun> outer = boxRuns(find(root, "o")), inner = boxRuns(find(root, "i"));
        assertEquals(2, outer.size(), "外側のインラインも割れる");
        assertEquals(2, inner.size());
        assertFalse(outer.get(0).endEdge() || inner.get(0).endEdge());
        assertFalse(outer.get(1).startEdge() || inner.get(1).startEdge());
    }

    @Test
    void blockInsideAnInlineCountsInTheIntrinsicSizes() {
        PaintView min = layout("<div id=w style='width:min-content'><span>aa bb<div style='width:200px;height:4px'></div>cc</span></div>");
        assertEquals(200f, find(min, "w").w(), 0.01f, "min-content はブロックの幅を含む");
        PaintView max = layout("<div id=w style='float:left'><span>aaaa bbbb<div>c</div>dd</span></div>");
        PaintView ref = layout("<div id=w style='float:left'>aaaa bbbb</div>");
        assertEquals(find(ref, "w").w(), find(max, "w").w(), 0.01f, "max-content: ブロックの前後で行が割れる");
    }

    @Test
    void incrementalChangesMatchAFullRebuild() {
        String html = BASE + "<div id=c>x <span id=s>a<div id=d>b</div>c</span> y</div><p id=after>z</p>";
        IncrementalVsFullTest.assertIncrementalMatchesFull(html, v -> { },
                v -> v.root().ownerDocument.getElementById("d").setTextContent("changed text"));
        IncrementalVsFullTest.assertIncrementalMatchesFull(html, v -> { }, v -> {
            var doc = v.root().ownerDocument;
            var e = doc.createElement("div");
            e.setTextContent("second");
            doc.getElementById("s").appendChild(e);
        });
        IncrementalVsFullTest.assertIncrementalMatchesFull(html, v -> { }, v -> {
            var d = v.root().ownerDocument.getElementById("d");
            d.parent.removeChild(d);
        });
        IncrementalVsFullTest.assertIncrementalMatchesFull(html, v -> { },
                v -> v.root().ownerDocument.getElementById("d").setAttribute("style", "display:inline"));
        // 容器だけが組み直され、中のブロックの描画用の節は前回のまま使われる: 割れたインラインの矩形もそのブロックを囲む
        IncrementalVsFullTest.assertIncrementalMatchesFull(html, v -> { },
                v -> v.root().ownerDocument.getElementById("c").appendChild(v.root().ownerDocument.createTextNode(" more")));
    }
}
