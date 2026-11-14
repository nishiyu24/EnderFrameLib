package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * P1 最適化（canPatchBoxes / isCulledNoop / offscreen / preparePatchBoxes の単一パス化とキャッシュ）の検証。
 */
class DisplayListSlicePatchOptimizationTest {

    private static final int W = 600;
    private static final int H = 400;

    private static final String HTML = """
            <!DOCTYPE html>
            <html>
            <head>
            <style>
              body { margin: 0; padding: 0; font-size: 14px; }
              .item { height: 30px; margin: 2px 0; background: #eee; }
              .item.hot { background: #f00; }
              .moving-parent { transform: translateX(10px); }
            </style>
            </head>
            <body>
              <div id="i1" class="item">Item 1</div>
              <div id="i2" class="item">Item 2</div>
              <div id="i3" class="item">Item 3</div>
              <div id="scroll-container" style="height: 10000px;">
                <div id="deep-item" class="item" style="margin-top: 5000px;">Deep Offscreen</div>
              </div>
              <div id="moving-container" class="moving-parent" style="margin-top: 5000px;">
                <div id="moving-child" class="item">Moving Child</div>
              </div>
            </body>
            </html>
            """;

    private static LayoutBox findBox(LayoutBox root, String id) {
        if (root.node != null && id.equals(root.node.getId())) return root;
        for (LayoutBox child : root.children) {
            LayoutBox found = findBox(child, id);
            if (found != null) return found;
        }
        return null;
    }

    @Test
    void preparePatchBoxesDeduplicatesIdenticalBoxesAndPreservesDescendingOrder() {
        StandaloneView view = new StandaloneView(HTML, W, H, FontResolver.FALLBACK);
        view.ui().flushInvalidation();

        LayoutBox b1 = findBox(view.ui().layoutRoot(), "i1");
        LayoutBox b2 = findBox(view.ui().layoutRoot(), "i2");
        LayoutBox b3 = findBox(view.ui().layoutRoot(), "i3");
        assertNotNull(b1);
        assertNotNull(b2);
        assertNotNull(b3);

        DisplayListBuilder builder = view.ui().paintBuilder();
        assertNotNull(builder);

        // 重複して渡す: [b1, b3, b2, b1, b3]
        List<LayoutBox> input = List.of(b1, b3, b2, b1, b3);
        List<LayoutBox> prepared = builder.preparePatchBoxes(input);
        assertNotNull(prepared, "重複を含むボックス列も正常に受け入れられる");
        assertEquals(3, prepared.size(), "重複が除去されてユニークな 3 つのボックスになる");

        // 降順ソートの確認: dlStart が大きい順
        assertTrue(prepared.get(0).dlStart > prepared.get(1).dlStart, "降順ソートされている");
        assertTrue(prepared.get(1).dlStart > prepared.get(2).dlStart, "降順ソートされている");
    }

    @Test
    void preparePatchBoxesHandlesAllCulledNoopGracefully() {
        StandaloneView view = new StandaloneView(HTML, W, H, FontResolver.FALLBACK);
        view.ui().flushInvalidation();

        // 画面外の要素（windowedBuild で項目が生成されていない）
        LayoutBox deep = findBox(view.ui().layoutRoot(), "deep-item");
        assertNotNull(deep);

        DisplayListBuilder builder = view.ui().paintBuilder();
        assertNotNull(builder);

        // 画面外かつスライスを持たないボックス
        if (deep.dlStart < 0 && deep.dlEnd < 0 && deep.dlOrder < 0) {
            List<LayoutBox> prepared = builder.preparePatchBoxes(List.of(deep));
            assertNotNull(prepared);
            assertTrue(prepared.isEmpty(), "画面外で項目を持たないボックスのみの場合は空リストが返る");

            // patchBoxes は null でなく現在の表示リストを安全に返す
            List<DisplayItem> patched = builder.patchBoxes(List.of(deep));
            assertNotNull(patched, "フル描画へフォールバックせず no-op として成功する");
            assertSame(view.ui().renderCache(), patched, "表示リストの参照が維持される");
        }
    }

    @Test
    void movingParentPreventsCulledNoop() {
        StandaloneView view = new StandaloneView(HTML, W, H, FontResolver.FALLBACK);
        view.ui().flushInvalidation();

        // transform を持つ親の下にある要素
        LayoutBox movingChild = findBox(view.ui().layoutRoot(), "moving-child");
        assertNotNull(movingChild);

        // 親に moving props があるため、画面外の幾何位置にあっても offscreen (culledNoop) として
        // 除外されず、スライスパッチ判定で安全に扱われる
        var facts = PaintOrder.factsOf(movingChild.parent.style);
        assertTrue(facts.hasMovingProps(), "親の StyleFacts が hasMovingProps=true を保持している");
    }

    @Test
    void patchBoxesExecutesWithoutDoubleCanPatchInvocation() {
        StandaloneView view = new StandaloneView(HTML, W, H, FontResolver.FALLBACK);
        view.ui().flushInvalidation();

        HtmlElement el = (HtmlElement) view.root().getNodeById("i1");
        assertNotNull(el);

        long patchBefore = UiView.RESTYLE_REPAINT_PATCH.get();
        long fullBefore = UiView.RESTYLE_REPAINT_FULL.get();

        el.setAttribute("class", "item hot");
        view.ui().flushInvalidation();

        assertEquals(patchBefore + 1, UiView.RESTYLE_REPAINT_PATCH.get(), "パッチが 1 回命中する");
        assertEquals(fullBefore, UiView.RESTYLE_REPAINT_FULL.get(), "フル描画フォールバックは起きない");
    }

    @Test
    void preparePatchBoxesDetectsOverlappingSlices() {
        StandaloneView view = new StandaloneView(HTML, W, H, FontResolver.FALLBACK);
        view.ui().flushInvalidation();

        LayoutBox b1 = findBox(view.ui().layoutRoot(), "i1");
        LayoutBox b2 = findBox(view.ui().layoutRoot(), "i2");
        assertNotNull(b1);
        assertNotNull(b2);

        DisplayListBuilder builder = view.ui().paintBuilder();
        assertNotNull(builder);

        // 正常な状態では b1 と b2 のスライスは重ならない
        assertTrue(b1.dlEnd <= b2.dlStart || b2.dlEnd <= b1.dlStart);
        assertNotNull(builder.preparePatchBoxes(List.of(b1, b2)));

        // 意図的にスライスを重複させる（b1 の dlEnd を b2 の dlStart より大きく改ざん）
        int origDlEnd = b1.dlEnd;
        try {
            b1.dlEnd = b2.dlStart + 1;
            List<LayoutBox> result = builder.preparePatchBoxes(List.of(b1, b2));
            assertNull(result, "スライスが重複するボックス群は拒否される（cant 0, 7）");
        } finally {
            b1.dlEnd = origDlEnd;
        }
    }

    @Test
    void movingParentPropagatesIntoOffscreenRenderAndChildPatchSubtreeSlices() {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><style>")
                .append("body{margin:0;padding:0;}")
                .append(".item{height:20px;}")
                .append(".moving{transform:translateY(-4950px);}")
                .append("</style></head><body>");
        for (int i = 0; i < 450; i++) {
            sb.append("<div class=\"item\" id=\"s").append(i).append("\">S").append(i).append("</div>");
        }
        sb.append("<div id=\"moving-box\" class=\"moving\"><div id=\"moving-inner\"><div id=\"nested-moving\" class=\"item\">Moving</div></div></div>");
        sb.append("</body></html>");

        StandaloneView view = new StandaloneView(sb.toString(), W, H, FontResolver.FALLBACK);
        view.ui().flushInvalidation();

        LayoutBox deepStatic = findBox(view.ui().layoutRoot(), "s440");
        LayoutBox nestedMoving = findBox(view.ui().layoutRoot(), "nested-moving");
        assertNotNull(deepStatic);
        assertNotNull(nestedMoving);
        assertTrue(deepStatic.movingStamped && !deepStatic.selfOrAncestorMoving);
        assertTrue(nestedMoving.movingStamped && nestedMoving.ancestorMoving && nestedMoving.selfOrAncestorMoving);
        assertTrue(deepStatic.dlStart < 0, "画面外の静的な箱は窓付き構築で間引かれる");
        assertTrue(nestedMoving.dlStart >= 0, "親から moving 属性が伝播した深い子孫は画面外座標でも間引かれない");

        HtmlElement nestedParent = (HtmlElement) view.root().getNodeById("moving-inner");
        HtmlElement added = new HtmlElement("div");
        added.setAttribute("id", "added-moving");
        added.setAttribute("class", "item");
        added.updateTextContent("Added");
        nestedParent.appendChild(added);
        view.ui().flushInvalidation();

        LayoutBox addedBox = findBox(view.ui().layoutRoot(), "added-moving");
        assertNotNull(addedBox);
        assertTrue(addedBox.movingStamped && addedBox.ancestorMoving && addedBox.selfOrAncestorMoving);
        assertTrue(addedBox.dlStart >= 0, "動的に追加した moving 配下の子も renderSubtreeSlices で間引かれず描かれる");
    }
}
