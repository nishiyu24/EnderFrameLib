package com.nishiyu.enderframelib.core.paint;

import static org.junit.jupiter.api.Assertions.*;

import com.nishiyu.enderframelib.common.StandaloneView;
import com.nishiyu.enderframelib.common.UiView;
import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 戦略 T2 / P0-4: Segmented DisplayList（DisplayItem スライス保持と局所差分差し替え）の検証。
 * 各 LayoutBox が自身の生成した [dlStart, dlEnd) スライスを保持し、
 * 局所変更（スタイル変更・テキスト更新・子の挿入削除）時に全ボックス走査をバイパスして
 * スライスを差分差し替え・シフトできること、およびその結果が毎回フル再構築時と
 * 100% 完全一致することを検証する。
 */
class DisplayListSlicePatchTest {

    private static final int W = 600;
    private static final int H = 800;

    @AfterEach
    void tearDown() {
        UiView.childPatchEnabled = true;
    }

    private static final String BASE_HTML = """
            <!DOCTYPE html>
            <html>
            <head>
            <style>
              body { margin: 10px; font-size: 14px; }
              .card { padding: 8px; margin: 4px 0; background: #f0f0f0; border: 1px solid #ccc; }
              .btn { display: block; padding: 4px 8px; background: #007acc; color: #fff; }
              .btn.hot { background: #e04040; }
              .list { margin: 4px 0; padding: 0; }
              .item { padding: 6px; margin: 2px 0; background: #e8e8e8; border-bottom: 1px solid #ddd; }
            </style>
            </head>
            <body>
              <div id="c1" class="card">
                <div id="btn1" class="btn">Action 1</div>
                <div id="btn2" class="btn">Action 2</div>
              </div>
              <div id="list" class="list">
                <div id="i0" class="item">Row Item 0</div>
                <div id="i1" class="item">Row Item 1</div>
                <div id="i2" class="item">Row Item 2</div>
                <div id="i3" class="item">Row Item 3</div>
                <div id="i4" class="item">Row Item 4</div>
              </div>
              <div id="c2" class="card">
                <div id="btn3" class="btn">Action 3</div>
              </div>
            </body>
            </html>
            """;

    private static void assertItemsEqual(List<DisplayItem> expected, List<DisplayItem> actual, String msg) {
        assertEquals(expected.size(), actual.size(), msg + " [item count mismatch]");
        for (int i = 0; i < expected.size(); i++) {
            DisplayItem exp = expected.get(i);
            DisplayItem act = actual.get(i);
            assertEquals(exp.key(), act.key(), msg + " [item " + i + " key mismatch]");
            assertEquals(exp.type(), act.type(), msg + " [item " + i + " type mismatch]");
            assertEquals(exp.x(), act.x(), 1e-2f, msg + " [item " + i + " x mismatch]");
            assertEquals(exp.y(), act.y(), 1e-2f, msg + " [item " + i + " y mismatch]");
            assertEquals(exp.width(), act.width(), 1e-2f, msg + " [item " + i + " width mismatch]");
            assertEquals(exp.height(), act.height(), 1e-2f, msg + " [item " + i + " height mismatch]");
            assertEquals(exp.color(), act.color(), msg + " [item " + i + " color mismatch]");
            assertEquals(exp.bgColor(), act.bgColor(), msg + " [item " + i + " bgColor mismatch]");
            assertEquals(exp.text(), act.text(), msg + " [item " + i + " text mismatch]");
        }
    }

    private static LayoutBox findBox(LayoutBox root, String id) {
        if (root.node != null && id.equals(root.node.getId())) return root;
        for (LayoutBox child : root.children) {
            LayoutBox found = findBox(child, id);
            if (found != null) return found;
        }
        return null;
    }

    private static List<DisplayItem> flush(StandaloneView v, boolean patch) {
        UiView.childPatchEnabled = patch;
        v.ui().flushInvalidation();
        return v.ui().renderCache();
    }

    @Test
    void slicesAreRecordedOnRender() {
        StandaloneView view = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        view.ui().flushInvalidation();
        List<DisplayItem> items = view.ui().renderCache();
        assertTrue(items.size() > 0, "初期表示項目が生成される");

        LayoutBox c1 = findBox(view.ui().layoutRoot(), "c1");
        assertNotNull(c1, "c1 ボックスが存在する");
        assertTrue(c1.dlStart >= 0, "c1.dlStart が有効な位置を指す");
        assertTrue(c1.dlEnd >= c1.dlStart, "c1.dlEnd >= c1.dlStart");
        assertTrue(c1.dlOrder >= 0, "c1.dlOrder が有効な順序を持つ");

        LayoutBox i2 = findBox(view.ui().layoutRoot(), "i2");
        assertNotNull(i2, "i2 ボックスが存在する");
        assertTrue(i2.dlStart >= c1.dlEnd, "描画順で後続のボックスは dlStart が大きい");
        assertTrue(i2.dlOrder > c1.dlOrder, "描画順で後続のボックスは dlOrder が大きい");
    }

    @Test
    void singleBoxStylePatchMatchesFullRender() {
        StandaloneView viewPatch = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        StandaloneView viewFull = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        flush(viewPatch, true);
        flush(viewFull, false);

        HtmlElement btn1Patch = (HtmlElement) viewPatch.root().getNodeById("btn1");
        HtmlElement btn1Full = (HtmlElement) viewFull.root().getNodeById("btn1");
        btn1Patch.setAttribute("class", "btn hot");
        btn1Full.setAttribute("class", "btn hot");

        List<DisplayItem> patchedItems = flush(viewPatch, true);
        List<DisplayItem> expectedItems = flush(viewFull, false);

        assertItemsEqual(expectedItems, patchedItems, "単一スタイル変更でパッチ結果がフル描画結果と 100% 一致");
    }

    @Test
    void multipleBoxesStylePatchMatchesFullRender() {
        StandaloneView viewPatch = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        StandaloneView viewFull = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        flush(viewPatch, true);
        flush(viewFull, false);

        for (String id : List.of("btn1", "btn2", "btn3")) {
            ((HtmlElement) viewPatch.root().getNodeById(id)).setAttribute("class", "btn hot");
            ((HtmlElement) viewFull.root().getNodeById(id)).setAttribute("class", "btn hot");
        }

        List<DisplayItem> patchedItems = flush(viewPatch, true);
        List<DisplayItem> expectedItems = flush(viewFull, false);

        assertItemsEqual(expectedItems, patchedItems, "複数スタイル変更でパッチ結果がフル描画結果と 100% 一致");
    }

    @Test
    void textPatchMatchesFullRender() {
        StandaloneView viewPatch = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        StandaloneView viewFull = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        flush(viewPatch, true);
        flush(viewFull, false);

        long hitsBefore = UiView.TEXT_PATCH_HITS.get();

        String updated = "Updated Row Item 2 Content";
        ((HtmlElement) viewPatch.root().getNodeById("i2")).updateTextContent(updated);
        ((HtmlElement) viewFull.root().getNodeById("i2")).updateTextContent(updated);

        List<DisplayItem> patchedItems = flush(viewPatch, true);
        List<DisplayItem> expectedItems = flush(viewFull, false);
        assertTrue(UiView.TEXT_PATCH_HITS.get() > hitsBefore, "TEXT_PATCH がヒットしている");

        assertItemsEqual(expectedItems, patchedItems, "テキスト更新でパッチ結果がフル描画結果と 100% 一致");
    }

    @Test
    void childInsertMiddleMatchesFullRender() {
        StandaloneView viewPatch = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        StandaloneView viewFull = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        flush(viewPatch, true);
        flush(viewFull, false);

        long hitsBefore = UiView.CHILD_PATCH_HITS.get();
        UiView.childPatchEnabled = true;

        for (StandaloneView v : List.of(viewPatch, viewFull)) {
            HtmlNode list = v.root().getNodeById("list");
            HtmlNode i3 = v.root().getNodeById("i3");
            HtmlElement newEl = new HtmlElement("div");
            newEl.setAttribute("class", "item");
            newEl.setAttribute("id", "i-new");
            newEl.updateTextContent("Inserted Row Item");
            list.insertBefore(newEl, i3);
        }

        List<DisplayItem> patchedItems = flush(viewPatch, true);
        List<DisplayItem> expectedItems = flush(viewFull, false);
        assertTrue(UiView.CHILD_PATCH_HITS.get() > hitsBefore, "CHILD_PATCH がヒットしている: " + UiView.lastChildPatchRejection);

        assertItemsEqual(expectedItems, patchedItems, "中間行挿入でパッチ結果がフル描画結果と 100% 一致");
    }

    @Test
    void childRemoveMiddleMatchesFullRender() {
        StandaloneView viewPatch = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        StandaloneView viewFull = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        flush(viewPatch, true);
        flush(viewFull, false);

        long hitsBefore = UiView.CHILD_PATCH_HITS.get();
        UiView.childPatchEnabled = true;

        for (StandaloneView v : List.of(viewPatch, viewFull)) {
            HtmlNode list = v.root().getNodeById("list");
            HtmlNode i2 = v.root().getNodeById("i2");
            list.removeChild(i2);
        }

        List<DisplayItem> patchedItems = flush(viewPatch, true);
        List<DisplayItem> expectedItems = flush(viewFull, false);
        assertTrue(UiView.CHILD_PATCH_HITS.get() > hitsBefore, "CHILD_PATCH がヒットしている: " + UiView.lastChildPatchRejection);

        assertItemsEqual(expectedItems, patchedItems, "中間行削除でパッチ結果がフル描画結果と 100% 一致");
    }

    @Test
    void childInsertAtStartAndEndMatchesFullRender() {
        // 先頭への挿入
        {
            StandaloneView viewPatch = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
            StandaloneView viewFull = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
            flush(viewPatch, true);
            flush(viewFull, false);

            long hitsBefore = UiView.CHILD_PATCH_HITS.get();
            UiView.childPatchEnabled = true;

            for (StandaloneView v : List.of(viewPatch, viewFull)) {
                HtmlNode list = v.root().getNodeById("list");
                HtmlNode i0 = v.root().getNodeById("i0");
                HtmlElement firstNew = new HtmlElement("div");
                firstNew.setAttribute("class", "item");
                firstNew.setAttribute("id", "i-first");
                firstNew.updateTextContent("First Row");
                list.insertBefore(firstNew, i0);
            }

            List<DisplayItem> patchedItems = flush(viewPatch, true);
            List<DisplayItem> expectedItems = flush(viewFull, false);
            assertTrue(UiView.CHILD_PATCH_HITS.get() > hitsBefore, "先頭行挿入で CHILD_PATCH がヒットしている");
            assertItemsEqual(expectedItems, patchedItems, "先頭行挿入でパッチ結果がフル描画結果と 100% 一致");
        }

        // 末尾への挿入
        {
            StandaloneView viewPatch = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
            StandaloneView viewFull = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
            flush(viewPatch, true);
            flush(viewFull, false);

            long hitsBefore = UiView.CHILD_PATCH_HITS.get();
            UiView.childPatchEnabled = true;

            for (StandaloneView v : List.of(viewPatch, viewFull)) {
                HtmlNode list = v.root().getNodeById("list");
                HtmlElement lastNew = new HtmlElement("div");
                lastNew.setAttribute("class", "item");
                lastNew.setAttribute("id", "i-last");
                lastNew.updateTextContent("Last Row");
                list.appendChild(lastNew);
            }

            List<DisplayItem> patchedItems = flush(viewPatch, true);
            List<DisplayItem> expectedItems = flush(viewFull, false);
            assertTrue(UiView.CHILD_PATCH_HITS.get() > hitsBefore, "末尾行挿入で CHILD_PATCH がヒットしている");
            assertItemsEqual(expectedItems, patchedItems, "末尾行挿入でパッチ結果がフル描画結果と 100% 一致");
        }
    }

    @Test
    void randomOperationsSequenceMatchesFullRender() {
        StandaloneView viewPatch = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        StandaloneView viewFull = new StandaloneView(BASE_HTML, W, H, FontResolver.FALLBACK);
        flush(viewPatch, true);
        flush(viewFull, false);

        long startChildHits = UiView.CHILD_PATCH_HITS.get();
        long startTextHits = UiView.TEXT_PATCH_HITS.get();

        Random rnd = new Random(1234);

        for (int step = 0; step < 40; step++) {
            UiView.childPatchEnabled = true;
            UiView.textPatchEnabled = true;

            int op = rnd.nextInt(3);
            if (op == 0) {
                // 行削除
                int targetIdx = 1 + rnd.nextInt(Math.max(1, viewPatch.root().getNodeById("list").children.size() - 2));
                for (StandaloneView v : List.of(viewPatch, viewFull)) {
                    HtmlNode list = v.root().getNodeById("list");
                    if (list.children.size() > 2 && targetIdx < list.children.size()) {
                        list.removeChild(list.children.get(targetIdx));
                    }
                }
            } else if (op == 1) {
                // 行追加
                int insertAfter = rnd.nextInt(viewPatch.root().getNodeById("list").children.size());
                String elId = "dyn-" + step;
                String elText = "Dynamic Item " + step;
                for (StandaloneView v : List.of(viewPatch, viewFull)) {
                    HtmlNode list = v.root().getNodeById("list");
                    if (list.children.size() < 15 && insertAfter < list.children.size()) {
                        HtmlNode prev = list.children.get(insertAfter);
                        HtmlElement newEl = new HtmlElement("div");
                        newEl.setAttribute("class", "item");
                        newEl.setAttribute("id", elId);
                        newEl.updateTextContent(elText);
                        list.insertBefore(newEl, (HtmlNode) prev.getNextSibling());
                    }
                }
            } else {
                // テキスト更新
                int targetIdx = rnd.nextInt(viewPatch.root().getNodeById("list").children.size());
                String text = "Mutated Text " + step + " [rand=" + rnd.nextInt(100) + "]";
                for (StandaloneView v : List.of(viewPatch, viewFull)) {
                    HtmlNode list = v.root().getNodeById("list");
                    if (targetIdx < list.children.size()) {
                        HtmlNode target = list.children.get(targetIdx);
                        if (target instanceof HtmlElement el) {
                            el.updateTextContent(text);
                        }
                    }
                }
            }

            List<DisplayItem> patched = flush(viewPatch, true);
            List<DisplayItem> expected = flush(viewFull, false);

            assertItemsEqual(expected, patched, "Step " + step + " でパッチ結果がフル描画結果と 100% 一致");
        }

        assertTrue(UiView.CHILD_PATCH_HITS.get() > startChildHits, "Child patch hits increased in random ops");
        assertTrue(UiView.TEXT_PATCH_HITS.get() > startTextHits, "Text patch hits increased in random ops");
    }
}
