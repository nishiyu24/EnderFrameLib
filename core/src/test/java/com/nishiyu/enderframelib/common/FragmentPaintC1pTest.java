package com.nishiyu.enderframelib.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nishiyu.enderframelib.core.dom.node.HtmlElement;
import com.nishiyu.enderframelib.core.dom.node.HtmlNode;
import com.nishiyu.enderframelib.core.dom.node.HtmlText;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * C1p 検証テスト: 祖先の項目再利用・レイヤー錨の記憶・多兄弟の部分木スキップ（DirtyKids）・窓付き構造変更・部分木単位のビューポート感度。
 */
class FragmentPaintC1pTest {

    private static String itemSig(DisplayItem i) {
        return i.id() + "|" + i.baseId() + "|" + i.type() + "|" + i.x() + "," + i.y() + "," + i.width() + "," + i.height()
                + "|" + i.text() + "|" + i.color() + "|" + i.bgColor() + "|" + i.radius() + "|" + i.opacity()
                + "|" + i.translateX() + "," + i.translateY() + "," + i.scaleX() + "," + i.scaleY() + "," + i.rotate()
                + "|" + i.transitionDuration() + "|" + i.events()
                + "|" + (i.hints() != null && i.hints().layer() != null ? "L:" + i.hints().layer().span() : "")
                + "|" + (i.animDef() == null ? "" : i.animDef().frames().size());
    }

    private static void assertMatchesFresh(StandaloneView view, int w, int h, String step) {
        UiView ui = view.ui();
        ui.flushInvalidation();
        List<DisplayItem> actual = ui.renderCache();
        DisplayListBuilder fresh = new DisplayListBuilder(w, h, ui.document().sheet.keyframes, ui.scrollState());
        List<DisplayItem> expected = fresh.render(ui.layoutRoot(), false);
        assertEquals(expected.size(), actual.size(), step + ": 表示項目数");
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(itemSig(expected.get(i)), itemSig(actual.get(i)), step + ": 項目 " + i);
        }
    }

    private static LayoutBox firstBoxByTag(LayoutBox b, String tag) {
        if (tag.equals(b.node.tag) && b.w() > 0 && b.h() > 0) return b;
        for (LayoutBox c : b.children) {
            LayoutBox found = firstBoxByTag(c, tag);
            if (found != null) return found;
        }
        return null;
    }

    private static DisplayItem findAnchorWithLayer(List<DisplayItem> items, String baseId) {
        for (DisplayItem it : items) {
            if (baseId.equals(it.baseId()) && it.hints() != null && it.hints().layer() != null) return it;
        }
        return null;
    }

    private static HtmlNode firstTextNode(HtmlNode n) {
        for (HtmlNode c : n.children) if (c instanceof HtmlText) return c;
        return null;
    }

    @Test
    void showcaseHoverReusesAncestorOwnAndMatchesFresh() throws IOException {
        Path p = Path.of("src/main/resources/data/enderframelib/enderframe_ui/showcase.html");
        if (!Files.exists(p)) p = Path.of("core", p.toString());
        String html = Files.readString(p, StandardCharsets.UTF_8);
        try (StandaloneView v = new StandaloneView(html, 800, 600, FontResolver.FALLBACK)) {
            assertMatchesFresh(v, 800, 600, "showcase 初回");
            LayoutBox btn = firstBoxByTag(v.ui().layoutRoot(), "button");
            assertNotNull(btn, "showcase に button がある");
            double hx = btn.x() + btn.w() * 0.5;
            double hy = btn.y() + btn.h() * 0.5;

            long ownBefore = DisplayListBuilder.fragmentOwnReused();
            long hitsBefore = DisplayListBuilder.fragmentMemoHits();
            v.mouseMove(hx, hy, "standalone");
            assertMatchesFresh(v, 800, 600, "showcase hover");
            v.mouseMove(-100, -100, "standalone");
            assertMatchesFresh(v, 800, 600, "showcase leave");
            v.mouseMove(hx, hy, "standalone");
            assertMatchesFresh(v, 800, 600, "showcase re-hover");

            assertTrue(DisplayListBuilder.fragmentOwnReused() > ownBefore, "ホバー時に祖先の own 項目を再利用する");
            assertTrue(DisplayListBuilder.fragmentMemoHits() > hitsBefore, "変更のない部分木を再利用する");
        }
    }

    @Test
    void layeredAnchorReusedAcrossSiblingEditsAndUpdatesSpanOnInsideChange() {
        String html = """
                <style>
                  body { margin: 0; font-size: 14px }
                  #layer { filter: opacity(0.8); padding: 4px; background: #cde }
                  .item { height: 20px; background: #eee }
                </style>
                <div id="layer"><div id="inside" class="item">one</div></div>
                <div id="sibling" class="item">sib</div>
                """;
        try (StandaloneView v = new StandaloneView(html, 320, 240, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            DisplayItem a1 = findAnchorWithLayer(v.ui().renderCache(), "layer");
            assertNotNull(a1);
            int span1 = a1.hints().layer().span();

            ((HtmlText) firstTextNode(v.root().getNodeById("sibling"))).setData("sib-updated");
            assertMatchesFresh(v, 320, 240, "層外の兄弟更新");
            DisplayItem a2 = findAnchorWithLayer(v.ui().renderCache(), "layer");
            assertSame(a1, a2, "レイヤーの中身が不変なら同じ錨の項目実体を使い回す");
            assertEquals(span1, a2.hints().layer().span());

            HtmlElement extra = (HtmlElement) v.root().ownerDocument.createElement("div");
            extra.setAttribute("id", "inside2");
            extra.setAttribute("class", "item");
            extra.setTextContent("two");
            v.root().getNodeById("layer").appendChild(extra);
            assertMatchesFresh(v, 320, 240, "層内に子を追加");
            DisplayItem a3 = findAnchorWithLayer(v.ui().renderCache(), "layer");
            assertNotNull(a3);
            assertNotEquals(span1, a3.hints().layer().span(), "層内の項目数変化で span が更新される");
        }
    }

    @Test
    void manySiblingsDirtyKidsSkipAndFallbacksMatchFresh() {
        StringBuilder sb = new StringBuilder();
        sb.append("<style>body{margin:0;font-size:14px}.row{height:18px;background:#eef}.row:hover,.hot{background:#fdd}.off{display:none}.abs{position:absolute;top:0;left:0;width:40px;height:18px;background:#0f0}</style><ul id='list'>");
        for (int i = 0; i < 120; i++) sb.append("<li id='r").append(i).append("' class='row'>item ").append(i).append("</li>");
        sb.append("</ul>");

        try (StandaloneView v = new StandaloneView(sb.toString(), 400, 600, FontResolver.FALLBACK)) {
            assertMatchesFresh(v, 400, 600, "初回 120 行");

            // 1 子の文字更新で未変更兄弟をスキップ（ヒット数が >= 100 増える）
            long hits0 = DisplayListBuilder.fragmentMemoHits();
            ((HtmlText) firstTextNode(v.root().getNodeById("r5"))).setData("item 5 edited");
            assertMatchesFresh(v, 400, 600, "1 子テキスト更新");
            assertTrue(DisplayListBuilder.fragmentMemoHits() - hits0 >= 100, "120 兄弟のうち未変更兄弟をスキップ");

            // 1 子 hover / style 更新
            LayoutBox r10Box = findBoxById(v.ui().layoutRoot(), "r10");
            assertNotNull(r10Box);
            v.mouseMove(r10Box.x() + 10, r10Box.y() + 5, "standalone");
            assertMatchesFresh(v, 400, 600, "1 子 hover");

            // 2 子の同時更新
            ((HtmlText) firstTextNode(v.root().getNodeById("r20"))).setData("item 20 two");
            ((HtmlElement) v.root().getNodeById("r80")).setAttribute("class", "row hot");
            assertMatchesFresh(v, 400, 600, "2 子同時更新");

            // 8 件超の汚れによる全走査フォールバック
            for (int k = 0; k < 12; k++) ((HtmlElement) v.root().getNodeById("r" + (k * 3))).setAttribute("class", "row hot");
            assertMatchesFresh(v, 400, 600, ">8 子溢れフォールバック");

            // style/class 以外の属性変更（安全弁 treeNonStyleAttrVersion）
            ((HtmlElement) v.root().getNodeById("r30")).setAttribute("title", "tooltip-30");
            ((HtmlElement) v.root().getNodeById("r31")).setAttribute("data-tag", "v1");
            assertMatchesFresh(v, 400, 600, "非スタイル属性変更");

            // 別の DisplayListBuilder による挟み込み描画後に主ビューを継続更新
            DisplayListBuilder other = new DisplayListBuilder(400, 600, v.ui().document().sheet.keyframes, v.ui().scrollState());
            other.render(v.ui().layoutRoot(), false);
            ((HtmlText) firstTextNode(v.root().getNodeById("r45"))).setData("after other builder");
            assertMatchesFresh(v, 400, 600, "他ビルダー挟み込み後の更新");

            // display: none トグル（nullFlip 経路）
            ((HtmlElement) v.root().getNodeById("r40")).setAttribute("class", "row off");
            assertMatchesFresh(v, 400, 600, "display:none 化");
            ((HtmlElement) v.root().getNodeById("r40")).setAttribute("class", "row");
            assertMatchesFresh(v, 400, 600, "display:none 解除");

            // position: absolute トグル
            ((HtmlElement) v.root().getNodeById("r50")).setAttribute("class", "row abs");
            assertMatchesFresh(v, 400, 600, "position:absolute 化");
            ((HtmlElement) v.root().getNodeById("r50")).setAttribute("class", "row");
            assertMatchesFresh(v, 400, 600, "position:absolute 解除");

            // 64 フレームの記憶スイープを越える 75 回連続更新
            for (int f = 0; f < 75; f++) {
                ((HtmlText) firstTextNode(v.root().getNodeById("r0"))).setData("frame " + f);
                v.ui().flushInvalidation();
            }
            assertMatchesFresh(v, 400, 600, "GC スイープ後 (75f)");
            long hitsAfterSweep = DisplayListBuilder.fragmentMemoHits();
            ((HtmlText) firstTextNode(v.root().getNodeById("r1"))).setData("after sweep");
            assertMatchesFresh(v, 400, 600, "GC スイープ後もスキップ継続");
            assertTrue(DisplayListBuilder.fragmentMemoHits() - hitsAfterSweep >= 100);
        }
    }

    @Test
    void windowedListInsertRemoveAndScrollMatchFresh() {
        StringBuilder sb = new StringBuilder();
        sb.append("<style>body{margin:0;font-size:14px}#big{height:200px;overflow-y:auto}.row{height:20px;background:#eef}</style><ul id='big'>");
        for (int i = 0; i < 600; i++) sb.append("<li id='w").append(i).append("' class='row'>row ").append(i).append("</li>");
        sb.append("</ul>");

        try (StandaloneView v = new StandaloneView(sb.toString(), 400, 300, FontResolver.FALLBACK)) {
            v.ui().flushInvalidation();
            assertTrue(v.ui().paintBuilder().isWindowed(), "600 項目で窓付き描画が有効");
            assertMatchesFresh(v, 400, 300, "窓付き初回");

            HtmlNode big = v.root().getNodeById("big");
            HtmlNode mid = v.root().getNodeById("w300");
            HtmlElement ins = (HtmlElement) v.root().ownerDocument.createElement("li");
            ins.setAttribute("id", "inserted");
            ins.setAttribute("class", "row");
            ins.setTextContent("inserted row");
            big.insertBefore(ins, mid);
            assertMatchesFresh(v, 400, 300, "窓付き中央挿入");

            big.removeChild(ins);
            assertMatchesFresh(v, 400, 300, "窓付き中央削除");

            HtmlElement head = (HtmlElement) v.root().ownerDocument.createElement("li");
            head.setAttribute("id", "headIns");
            head.setAttribute("class", "row");
            head.setTextContent("head");
            big.insertBefore(head, big.children.get(0));
            assertMatchesFresh(v, 400, 300, "窓付き先頭挿入");

            v.mouseMove(50, 50, "standalone");
            v.scroll(0, -5, "standalone");
            assertMatchesFresh(v, 400, 300, "窓付きスクロール下");
            v.scroll(0, 3, "standalone");
            assertMatchesFresh(v, 400, 300, "窓付きスクロール上");
        }
    }

    @Test
    void viewportResizeRetainsFixedMemosAndRebuildsViewportSensitiveSubtrees() {
        String html = """
                <style>
                  body { margin: 0; font-size: 14px }
                  #fixedCard { width: 200px; padding: 6px; border: 1px solid #444; background: #eee }
                  #flexWrap { display: flex; }
                  #flexGrow { flex: 1; background: #dfe; padding: 4px }
                  #vwBox { width: 20vw; height: 24px; background: #fed }
                  #bgVw { width: 100px; height: 20px; background-image: linear-gradient(red, blue); background-size: 10vw 10px }
                  #anim { width: 80px; height: 20px; background: #cdf; animation: pulse 2s infinite }
                  @keyframes pulse { from { opacity: 0.4 } to { opacity: 1.0 } }
                </style>
                <div id="fixedCard"><div id="innerFixed">fixed inner text</div></div>
                <div id="flexWrap"><div id="flexGrow">grow</div></div>
                <div id="vwBox">vw</div>
                <div id="bgVw">bgvw</div>
                <div id="anim">anim</div>
                """;
        try (StandaloneView v = new StandaloneView(html, 800, 600, FontResolver.FALLBACK)) {
            assertMatchesFresh(v, 800, 600, "resize 800 初回");
            int[] widths = {799, 600, 800};
            for (int w : widths) {
                long hits0 = DisplayListBuilder.fragmentMemoHits();
                v.ui().recomputeLayout(w, 600);
                assertMatchesFresh(v, w, 600, "resize w=" + w);
                assertTrue(DisplayListBuilder.fragmentMemoHits() > hits0, "非依存な固定箱で記憶ヒットが生じる w=" + w);
                DisplayItem vwRect = firstRectOf(v.ui().renderCache(), "vwBox");
                assertNotNull(vwRect);
                assertEquals(Math.round(w * 0.2f), vwRect.width(), "vw の幅が新ビューポートに追従 w=" + w);
            }
        }
    }

    @Test
    void flexToBlockParentSwitchReclassifiesUnmovedChildKind() {
        // Case A: small flex -> block container where #d1 keeps identical geometry and style,
        // yet its Fragment.Kind flips between ATOMIC (INLINES layer) and BLOCK (BLOCKS layer),
        // and #d3 (z-index: 2) flips between STACKING and non-STACKING.
        String html = """
                <style>
                  body { margin: 0; font-size: 14px }
                  #wrap { width: 200px }
                  .f { display: flex; flex-direction: column }
                  .item { width: 80px; height: 20px; background: #eee }
                  .zi { z-index: 2; background: #cde }
                </style>
                <div id="wrap" class="f">
                  <div id="d1" class="item">one</div>
                  <div id="d2" class="item">two</div>
                  <div id="d3" class="item zi">three</div>
                </div>
                """;
        try (StandaloneView v = new StandaloneView(html, 320, 240, FontResolver.FALLBACK)) {
            assertMatchesFresh(v, 320, 240, "flex 初回");
            HtmlElement wrap = (HtmlElement) v.root().getNodeById("wrap");
            wrap.setAttribute("class", "");
            assertMatchesFresh(v, 320, 240, "flex→block 切替");
            wrap.setAttribute("class", "f");
            assertMatchesFresh(v, 320, 240, "block→flex 再切替");
        }

        // Case B: >= 64 siblings container so Pillar 2 must not skip children on flex <-> block switch.
        StringBuilder big = new StringBuilder();
        big.append("""
                <style>
                  body { margin: 0; font-size: 14px }
                  #bigWrap { width: 200px }
                  .f { display: flex; flex-direction: column }
                  .c { width: 100px; height: 10px; background: #eef }
                  .c:hover { background: #fdd }
                </style>
                <div id="bigWrap" class="f">
                """);
        for (int i = 0; i < 80; i++) {
            big.append("<div id='b").append(i).append("' class='c'>row ").append(i).append("</div>");
        }
        big.append("</div>");
        try (StandaloneView v = new StandaloneView(big.toString(), 400, 900, FontResolver.FALLBACK)) {
            assertMatchesFresh(v, 400, 900, "多兄弟 flex 初回");
            HtmlElement bigWrap = (HtmlElement) v.root().getNodeById("bigWrap");
            bigWrap.setAttribute("class", "");
            assertMatchesFresh(v, 400, 900, "多兄弟 flex→block 切替");
            LayoutBox b5 = findBoxById(v.ui().layoutRoot(), "b5");
            assertNotNull(b5);
            v.mouseMove(b5.x() + 10, b5.y() + 5, "standalone");
            assertMatchesFresh(v, 400, 900, "多兄弟 block 状態での hover");
            bigWrap.setAttribute("class", "f");
            assertMatchesFresh(v, 400, 900, "多兄弟 block→flex 切替");
        }
    }

    private static DisplayItem firstRectOf(List<DisplayItem> items, String baseId) {
        for (DisplayItem it : items) {
            if (baseId.equals(it.baseId()) && it.width() > 0 && it.height() > 0) return it;
        }
        return null;
    }

    private static LayoutBox findBoxById(LayoutBox b, String id) {
        if (b.node != null && id.equals(b.node.getId())) return b;
        for (LayoutBox c : b.children) {
            LayoutBox found = findBoxById(c, id);
            if (found != null) return found;
        }
        return null;
    }
}
