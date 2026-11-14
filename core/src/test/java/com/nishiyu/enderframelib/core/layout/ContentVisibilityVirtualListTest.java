package com.nishiyu.enderframelib.core.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.Document;
import com.nishiyu.enderframelib.common.RenderPipeline;
import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiDocumentCompiler;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.CssProperty;
import com.nishiyu.enderframelib.core.css.CssPropertyIndex;
import com.nishiyu.enderframelib.core.dom.InteractionState;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code content-visibility: auto}、{@code contain-intrinsic-size}、および
 * {@link com.nishiyu.enderframelib.core.kernel.layout.FenwickVirtualList} による
 * 10 万行仮想化フローの統合検証。
 */
class ContentVisibilityVirtualListTest {

    private static final int VP_W = 800;
    private static final int VP_H = 600;

    @Test
    void containIntrinsicSizeExpansionAndUnits() {
        String html = """
                <html><head><style>
                  body { margin: 0; }
                  .pair { content-visibility: hidden; display: inline-block; contain-intrinsic-size: 120px 45px; }
                  .single { content-visibility: hidden; display: inline-block; contain-intrinsic-size: 80px; }
                  .logical { content-visibility: hidden; display: inline-block; contain-intrinsic-inline-size: 90px; contain-intrinsic-block-size: 30px; }
                  .pct { content-visibility: hidden; display: inline-block; contain-intrinsic-size: 50% 40px; }
                  .pctLong { content-visibility: hidden; display: inline-block; contain-intrinsic-width: 50%; contain-intrinsic-height: 40px; }
                </style></head><body>
                  <div id="pair" class="pair"><span>hidden child</span></div>
                  <div id="single" class="single"><span>hidden child</span></div>
                  <div id="logical" class="logical"><span>hidden child</span></div>
                  <div id="pct" class="pct"><span>hidden child</span></div>
                  <div id="pctLong" class="pctLong"><span>hidden child</span></div>
                </body></html>
                """;
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", VP_W, VP_H);
        LayoutBox root = compiler.layoutDocument(doc, VP_W, VP_H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());

        LayoutBox pair = root.findByNode(doc.root.getNodeById("pair"));
        assertNotNull(pair);
        assertEquals(120f, pair.w, 0.01f);
        assertEquals(45f, pair.h, 0.01f);
        assertTrue(pair.children.isEmpty(), "content-visibility: hidden は子の箱を作らない");

        LayoutBox single = root.findByNode(doc.root.getNodeById("single"));
        assertNotNull(single);
        assertEquals(80f, single.w, 0.01f);
        assertEquals(80f, single.h, 0.01f);

        LayoutBox logical = root.findByNode(doc.root.getNodeById("logical"));
        assertNotNull(logical);
        assertEquals(90f, logical.w, 0.01f);
        assertEquals(30f, logical.h, 0.01f);

        LayoutBox pct = root.findByNode(doc.root.getNodeById("pct"));
        assertNotNull(pct);
        assertEquals(0f, pct.w, 0.01f, "contain-intrinsic-size ショートハンドの % は宣言全体が無効");
        assertEquals(0f, pct.h, 0.01f);

        LayoutBox pctLong = root.findByNode(doc.root.getNodeById("pctLong"));
        assertNotNull(pctLong);
        assertEquals(0f, pctLong.w, 0.01f, "contain-intrinsic-width の % 値は無効（0px フォールバック）");
        assertEquals(40f, pctLong.h, 0.01f);
    }

    @Test
    void contentVisibilityAutoSkipsOffscreenMaterializesOnScrollAndRemembersSize() {
        String html = """
                <html><head><style>
                  body { margin: 0; }
                  #scroller { overflow-y: auto; width: 400px; height: 300px; }
                  .spacer { height: 1500px; }
                  .cv { content-visibility: auto; contain-intrinsic-size: auto 50px; }
                  .cv-inner { height: 140px; }
                  #footer { height: 40px; }
                </style></head><body>
                  <div id="scroller">
                    <div class="spacer">top</div>
                    <div id="far" class="cv"><div id="farChild" class="cv-inner">far content</div></div>
                    <div id="footer">footer</div>
                  </div>
                </body></html>
                """;
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", VP_W, VP_H);
        ScrollState scroll = new ScrollState();
        InteractionState state = new InteractionState();
        LayoutBox root = compiler.layoutDocument(doc, VP_W, VP_H, Map.of(), scroll, FontResolver.FALLBACK, state);

        HtmlNode scrollerNode = doc.root.getNodeById("scroller");
        HtmlNode farNode = doc.root.getNodeById("far");
        HtmlNode footerNode = doc.root.getNodeById("footer");
        LayoutBox far = root.findByNode(farNode);
        LayoutBox footer = root.findByNode(footerNode);
        assertNotNull(far);
        assertNotNull(footer);

        // 初期状態: y=1500 > 300 + 640 なので画面外としてスキップされ、contain-intrinsic-size の 50px になる
        assertTrue(far.contentAutoSkipped, "画面外の content-visibility: auto はスキップされる");
        assertTrue(far.childrenDeferred, "スキップ中の子の箱構築は遅延される");
        assertTrue(far.children.isEmpty());
        assertEquals(50f, far.h, 0.01f);
        assertEquals(1550f, footer.y, 0.01f);

        // スクロールして近傍（1500px 付近）へ入る -> 具現化・実寸測定（140px）・footer が 1640px へ押し下げられる
        scroll.setScroll(scrollerNode, 0f, 1250f);
        RenderPipeline.repositionLayout(root, doc, Map.of(), state, VP_W, VP_H, scroll, FontResolver.FALLBACK);

        assertFalse(far.contentAutoSkipped, "近傍へ入るとスキップが解除される");
        assertFalse(far.childrenDeferred, "近傍へ入ると子が具現化される");
        assertEquals(140f, far.h, 0.01f, "実寸 140px が測定される");
        assertNotNull(far.findByNode(doc.root.getNodeById("farChild")));
        // scroller の内上端 (y=0) から 1500 - 1250 + 140 = 390px に footer がある
        assertEquals(390f, footer.y, 0.01f);

        // 上端へ戻す（scroll=0）-> 画面外へ戻って子を解放するが、auto 50px が直前の 140px を記憶しているため footer は 1640px に保たれる
        scroll.setScroll(scrollerNode, 0f, 0f);
        RenderPipeline.repositionLayout(root, doc, Map.of(), state, VP_W, VP_H, scroll, FontResolver.FALLBACK);

        assertTrue(far.contentAutoSkipped, "再び画面外に出ると再スキップされる");
        assertTrue(far.childrenDeferred, "再スキップで子の箱が解放される");
        assertTrue(far.children.isEmpty());
        assertEquals(140f, far.h, 0.01f, "contain-intrinsic-size: auto により最後の表示高さ 140px が保持される");
        assertEquals(1640f, footer.y, 0.01f, "記憶済みサイズにより後続要素がジャンプしない");
    }

    @Test
    void focusWithinKeepsOffscreenContentVisibilityAutoRelevant() {
        String html = """
                <html><head><style>
                  body { margin: 0; }
                  .spacer { height: 2200px; }
                  .cv { content-visibility: auto; contain-intrinsic-size: 40px; }
                  .cv-body { display: block; height: 110px; box-sizing: border-box; }
                </style></head><body>
                  <div class="spacer">top</div>
                  <div id="section" class="cv"><input id="target" class="cv-body" type="text"></div>
                </body></html>
                """;
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", VP_W, VP_H);
        InteractionState state = new InteractionState();
        HtmlNode target = doc.root.getNodeById("target");
        state.setFocusedNode(target, true);

        LayoutBox root = compiler.layoutDocument(doc, VP_W, VP_H, Map.of(), new ScrollState(), FontResolver.FALLBACK, state);
        LayoutBox section = root.findByNode(doc.root.getNodeById("section"));
        assertNotNull(section);
        assertTrue(section.cvForceRelevant, "子孫がフォーカスを持つため強制関連になる");
        assertFalse(section.contentAutoSkipped, "フォーカス中の子孫を持つときは画面外でもスキップしない");
        assertFalse(section.childrenDeferred);
        assertEquals(110f, section.h, 0.01f);
        assertNotNull(section.findByNode(target));
    }

    @Test
    void paintOrderExcludesSkippedDescendants() {
        String html = """
                <html><head><style>
                  body { margin: 0; }
                  .spacer { height: 2500px; }
                  .cv { content-visibility: auto; contain-intrinsic-size: 60px; background: #eee; }
                  #skippedChild { height: 80px; background: #f00; }
                </style></head><body>
                  <div class="spacer">top</div>
                  <div id="sec" class="cv"><div id="skippedChild">hidden offscreen</div></div>
                </body></html>
                """;
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(html, "", VP_W, VP_H);
        LayoutBox root = compiler.layoutDocument(doc, VP_W, VP_H, Map.of(), new ScrollState(), FontResolver.FALLBACK, new InteractionState());

        List<LayoutBox> order = new DisplayListBuilder(VP_W, VP_H, Map.of(), new ScrollState()).paintOrder(root);
        HtmlNode childNode = doc.root.getNodeById("skippedChild");
        assertTrue(order.stream().noneMatch(b -> b.node == childNode),
                "スキップ中の content-visibility: auto の子孫は描画順に含まれない");
    }

    @Test
    void fenwickVirtualizes100kRowsWithExactScrollHeightAndBoundedWork() {
        final int rows = 100_000;
        final float intrinsicH = 32f;
        final float tallRowH = 80f;
        final int tallRowIndex = 50_000;

        String shell = """
                <html><head><style>
                  body { margin: 0; }
                  #list { overflow-y: auto; width: 320px; height: 400px; }
                  .row { content-visibility: auto; contain-intrinsic-size: auto 32px; height: auto; background: #fafafa; }
                  .cell { height: 32px; }
                  .tall-cell { height: 80px; }
                </style></head><body><div id="list"></div></body></html>
                """;
        UiDocumentCompiler compiler = new UiDocumentCompiler();
        Document doc = compiler.parseDocument(shell, "", VP_W, VP_H);
        HtmlNode listNode = doc.root.getNodeById("list");

        HtmlNode row0Node = null;
        HtmlNode row50kNode = null;
        HtmlNode row50k1Node = null;
        HtmlNode rowLastNode = null;
        for (int i = 0; i < rows; i++) {
            HtmlElement row = new HtmlElement("div");
            row.setAttribute("class", "row");
            HtmlElement cell = new HtmlElement("div");
            cell.setAttribute("class", i == tallRowIndex ? "tall-cell" : "cell");
            cell.appendChild(new HtmlText("Row " + i));
            row.appendChild(cell);
            listNode.appendChild(row);
            if (i == 0) row0Node = row;
            else if (i == tallRowIndex) row50kNode = row;
            else if (i == tallRowIndex + 1) row50k1Node = row;
            else if (i == rows - 1) rowLastNode = row;
        }

        ScrollState scroll = new ScrollState();
        InteractionState state = new InteractionState();
        long vfBefore = BlockLayoutEngine.virtualFlowsBuilt();
        LayoutBox root = compiler.layoutDocument(doc, VP_W, VP_H, Map.of(), scroll, FontResolver.FALLBACK, state);

        LayoutBox listBox = root.findByNode(listNode);
        assertNotNull(listBox);
        assertTrue(listBox.isVirtualizedFlow(), "10 万行リストは FenwickVirtualList ベースの仮想フローを使う");
        assertTrue(BlockLayoutEngine.virtualFlowsBuilt() > vfBefore);

        VirtualFlowState vf = listBox.virtualFlowState();
        assertNotNull(vf);
        assertEquals(rows, vf.rowCount());
        assertEquals(rows * intrinsicH, listBox.scrollHeight, 0.5f, "全体の scrollHeight が 3,200,000px に一致");
        assertTrue(vf.placedCount() > 0 && vf.placedCount() <= 45,
                "初期配置行数は O(K) に抑えられる（実際=" + vf.placedCount() + "）");

        LayoutBox row0 = listBox.children.get(0);
        LayoutBox row50k = listBox.children.get(tallRowIndex);
        LayoutBox row50k1 = listBox.children.get(tallRowIndex + 1);
        LayoutBox rowLast = listBox.children.get(rows - 1);
        assertFalse(row0.contentAutoSkipped, "先頭行は可視範囲内で具現化される");
        assertFalse(row0.childrenDeferred);
        assertTrue(row50k.contentAutoSkipped && row50k.childrenDeferred, "50,000 行目は初期状態でスキップ＋子遅延");
        assertTrue(rowLast.contentAutoSkipped && rowLast.childrenDeferred, "末尾行は初期状態でスキップ＋子遅延");

        // PaintOrder も O(K) 件しか触らない
        List<LayoutBox> paintInitial = new DisplayListBuilder(VP_W, VP_H, Map.of(), scroll).paintOrder(root);
        assertTrue(paintInitial.size() < 120,
                "10 万行あっても PaintOrder が返す箱は O(K)（実際=" + paintInitial.size() + "）");

        // 50,000 行目（y = 1,600,000）へ一気にジャンプ
        scroll.setScroll(listNode, 0f, tallRowIndex * intrinsicH);
        long t0 = System.nanoTime();
        RenderPipeline.repositionLayout(root, doc, Map.of(), state, VP_W, VP_H, scroll, FontResolver.FALLBACK);
        long scrollElapsedMs = (System.nanoTime() - t0) / 1_000_000L;

        assertFalse(row50k.contentAutoSkipped, "ジャンプ先の 50,000 行目が具現化される");
        assertFalse(row50k.childrenDeferred);
        assertEquals(tallRowH, row50k.h, 0.01f, "可変高さの 50,000 行目が実寸 80px に更新される");
        assertEquals(0f, row50k.y, 0.5f, "50,000 行目の画面上端がリスト上端に一致");
        assertEquals(tallRowH, row50k1.y, 0.5f, "後続 50,001 行目が Fenwick 木により 80px へ即時シフト");
        assertEquals(rows * intrinsicH + (tallRowH - intrinsicH), listBox.scrollHeight, 0.5f,
                "可変行の分だけ全体の scrollHeight も即座に 3,200,048px へ更新");
        assertTrue(row0.contentAutoSkipped && row0.childrenDeferred, "離れた先頭行は再スキップされ子の箱を解放する");
        assertTrue(row0.children.isEmpty());
        assertTrue(vf.placedCount() <= 65, "ジャンプ後の配置行数も O(K)（実際=" + vf.placedCount() + "）");
        assertTrue(scrollElapsedMs < 500L, "10 万行スクロールジャンプが O(log N + K) で高速完了する（経過=" + scrollElapsedMs + "ms）");

        // 末尾 99,999 行目へジャンプ
        scroll.setScroll(listNode, 0f, Math.max(0f, listBox.scrollHeight - listBox.h));
        RenderPipeline.repositionLayout(root, doc, Map.of(), state, VP_W, VP_H, scroll, FontResolver.FALLBACK);
        assertFalse(rowLast.contentAutoSkipped, "末尾 99,999 行目が具現化される");
        assertEquals(listBox.h, rowLast.y + rowLast.h, 1.0f, "末尾行の下端がスクロールポート下端に一致する");
    }

    @Test
    void standaloneViewHandles100kVirtualScrollEndToEnd() {
        final int rows = 100_000;
        StringBuilder sb = new StringBuilder(16384);
        sb.append("<style>body{margin:0}#list{overflow-y:auto;width:300px;height:320px}"
                + ".r{content-visibility:auto;contain-intrinsic-size:32px;height:32px}</style>"
                + "<div id=\"list\">");
        // 小規模 DOM シェルを作り、ノード追加で 10 万行を構築する方が文字列解析より高速
        sb.append("</div>");
        StandaloneView view = new StandaloneView(sb.toString(), 400, 400, FontResolver.FALLBACK);
        try {
            HtmlElement list = (HtmlElement) view.root().getNodeById("list");
            for (int i = 0; i < rows; i++) {
                HtmlElement row = new HtmlElement("div");
                row.setAttribute("id", "r" + i);
                row.setAttribute("class", "r");
                row.appendChild(new HtmlText("item " + i));
                list.appendChild(row);
            }
            view.ui().flushInvalidation();
            List<DisplayItem> initialItems = view.ui().renderCache();
            assertTrue(initialItems.size() < 200, "初期表示リストも O(K) 件のみ生成（実際=" + initialItems.size() + "）");
            assertNotNull(view.ui().hitTestIndex().getElementById("r0"));
            assertNull(view.ui().hitTestIndex().getElementById("r50000"));

            // 50,000 行目付近へ大ジャンプ（SCROLL_TRANSLATE_LIMIT 超過）
            list.scrollTo(0f, 50_000f * 32f);
            view.ui().flushInvalidation();
            List<DisplayItem> scrolledItems = view.ui().renderCache();
            assertTrue(scrolledItems.size() < 400, "ジャンプ後の表示リストも O(K) 件（実際=" + scrolledItems.size() + "）");
            assertNotNull(view.ui().hitTestIndex().getElementById("r50000"), "ジャンプ先の行が HitTest/描画へ現れる");
            assertNull(view.ui().hitTestIndex().getElementById("r0"), "画面外の先頭行は描画から外れる");
        } finally {
            view.close();
        }
    }
}
