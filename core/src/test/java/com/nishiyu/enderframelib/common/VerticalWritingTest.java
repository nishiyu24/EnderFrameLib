package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.core.css.WritingModes;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.DisplayListBuilder;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.paint.software.SoftwareRenderBackend;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 縦書き（{@code writing-mode: vertical-rl | sideways-rl | sideways-lr}・{@code text-orientation}）。 */
public class VerticalWritingTest {

    private static final int W = 600, H = 400;
    private static final String CSS = "<style>body{margin:0;font:16px/20px monospace;background:#fff;color:#000} p{margin:0}</style>";

    private static final class Ctx {
        final UiDocumentCompiler compiler = new UiDocumentCompiler();
        final Document doc;

        Ctx(String html) {
            doc = compiler.parseDocument(CSS + html, "", W, H);
        }

        LayoutBox layout() {
            return compiler.layoutDocument(doc, W, H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        }

        List<DisplayItem> render(LayoutBox root) {
            return new DisplayListBuilder(W, H, doc.sheet.keyframes, new ScrollState()).render(root);
        }

        List<DisplayItem> render() {
            return render(layout());
        }
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (LayoutBox c : box.children) {
            LayoutBox h = find(c, id);
            if (h != null) return h;
        }
        return null;
    }

    private static List<DisplayItem> texts(List<DisplayItem> items) {
        List<DisplayItem> out = new ArrayList<>();
        for (DisplayItem it : items) if (it.type() == ElementType.TEXT) out.add(it);
        return out;
    }

    private static DisplayItem item(List<DisplayItem> items, String id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ 値の読み取り

    @Test
    public void writingModeValuesAreParsed() {
        assertEquals(WritingModes.HORIZONTAL, WritingModes.flowOf((String) null));
        assertEquals(WritingModes.HORIZONTAL, WritingModes.flowOf("horizontal-tb"));
        assertEquals(WritingModes.VERTICAL_RL, WritingModes.flowOf(" Vertical-RL "));
        assertEquals(WritingModes.VERTICAL_LR, WritingModes.flowOf("vertical-lr"));
        assertEquals(WritingModes.SIDEWAYS_RL, WritingModes.flowOf("sideways-rl"));
        assertEquals(WritingModes.SIDEWAYS_LR, WritingModes.flowOf("sideways-lr"));
        assertEquals(WritingModes.HORIZONTAL, WritingModes.flowOf("nonsense"));
        assertTrue(WritingModes.isCounterClockwise(WritingModes.SIDEWAYS_LR));
        assertFalse(WritingModes.isCounterClockwise(WritingModes.VERTICAL_RL));
    }

    @Test
    public void uprightClassification() {
        assertTrue(WritingModes.isUprightByDefault('日'));
        assertTrue(WritingModes.isUprightByDefault('あ'));
        assertTrue(WritingModes.isUprightByDefault('カ'));
        assertTrue(WritingModes.isUprightByDefault('Ａ'), "全角ラテン");
        assertTrue(WritingModes.isUprightByDefault(0x1F600), "絵文字");
        assertFalse(WritingModes.isUprightByDefault('a'));
        assertFalse(WritingModes.isUprightByDefault('1'));
        assertTrue(WritingModes.isUpright(WritingModes.Orientation.UPRIGHT, 'a'));
        assertFalse(WritingModes.isUpright(WritingModes.Orientation.SIDEWAYS, '日'));
    }

    // ------------------------------------------------------------------ レイアウト

    @Test
    public void verticalRootBecomesTallAndNarrow() {
        // 高さ 200px の縦書き。行の長さ 200px で折り返し、行（20px）を積んだ数が物理の幅になる
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;height:200px'>"
                + "aaaa ".repeat(40) + "</div>");
        LayoutBox v = find(c.layout(), "v");
        assertNotNull(v);
        assertTrue(v.isVerticalRoot);
        assertEquals(200, v.h, 0.01f, "物理の高さ = 行の長さ");
        assertTrue(v.w >= 40 && Math.abs(v.w % 20) < 0.01f, "物理の幅は行数 × 20px: " + v.w);
    }

    @Test
    public void moreTextMeansMoreLinesAndWiderBox() {
        Ctx a = new Ctx("<div id=v style='writing-mode:vertical-rl;height:200px'>" + "aaaa ".repeat(10) + "</div>");
        Ctx b = new Ctx("<div id=v style='writing-mode:vertical-rl;height:200px'>" + "aaaa ".repeat(40) + "</div>");
        assertTrue(find(b.layout(), "v").w > find(a.layout(), "v").w);
    }

    @Test
    public void shorterHeightWrapsMore() {
        Ctx tall = new Ctx("<div id=v style='writing-mode:vertical-rl;height:400px'>" + "aaaa ".repeat(30) + "</div>");
        Ctx shortBox = new Ctx("<div id=v style='writing-mode:vertical-rl;height:100px'>" + "aaaa ".repeat(30) + "</div>");
        assertTrue(find(shortBox.layout(), "v").w > find(tall.layout(), "v").w, "行の長さが短いほど行が増えて幅が増える");
    }

    @Test
    public void wrapperIsPlacedInsideRootContentBox() {
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;height:200px;padding:5px'>aaaa aaaa aaaa</div>");
        LayoutBox v = find(c.layout(), "v");
        LayoutBox wrapper = v.children.get(0);
        assertTrue(wrapper.isVerticalWrapper);
        // 回転後の見た目は根の内容ボックスに収まる: 箱は右へ h だけずらして置く（90° 回転は左へ h 張り出す）
        assertEquals(v.x + 5 + wrapper.h, wrapper.x, 0.01f);
        assertEquals(v.y + 5, wrapper.y, 0.01f);
        assertEquals(200, wrapper.w, 0.01f, "論理のインライン寸法 = 根の内容の高さ");
        assertEquals(wrapper.h + 10, v.w, 0.01f, "根の幅 = 論理のブロック寸法 + padding");
    }

    @Test
    public void explicitWidthPutsLinesAtTheRightEdge() {
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;height:100px;width:300px'>abc</div>");
        LayoutBox v = find(c.layout(), "v");
        LayoutBox wrapper = v.children.get(0);
        assertEquals(300, v.w, 0.01f);
        assertEquals(v.x + 300, wrapper.x, 0.01f, "最初の行は右端（block-start）");
    }

    @Test
    public void sidewaysLrRotatesTheOtherWay() {
        Ctx c = new Ctx("<div id=v style='writing-mode:sideways-lr;height:100px;padding:3px'>abc def ghi jkl mno</div>");
        LayoutBox root = c.layout();
        LayoutBox v = find(root, "v");
        LayoutBox wrapper = v.children.get(0);
        assertEquals(v.x + 3, wrapper.x, 0.01f, "行は左端から積む");
        assertEquals(v.y + 3 + wrapper.w, wrapper.y, 0.01f, "-90° 回転は上へ w だけ張り出す");
        DisplayItem n = item(c.render(root), wrapper.node.key());
        assertNotNull(n);
        assertEquals(-90f, n.rotate(), 0.001f);
    }

    @Test
    public void horizontalDocumentsAreUntouched() {
        Ctx c = new Ctx("<div id=h style='width:100px'>abc def</div>");
        LayoutBox root = c.layout();
        LayoutBox h = find(root, "h");
        assertFalse(h.isVerticalRoot);
        assertFalse(h.children.stream().anyMatch(b -> b.isVerticalWrapper));
        assertTrue(texts(c.render(root)).stream().allMatch(t -> t.rotate() == 0f));
    }

    @Test
    public void flexAndGridAreNotVerticalRoots() {
        Ctx c = new Ctx("<div id=f style='display:flex;writing-mode:vertical-rl;height:100px'>x</div>");
        assertFalse(find(c.layout(), "f").isVerticalRoot, "flex コンテナは対象外（LIMITATION）");
    }

    @Test
    public void nestedVerticalDoesNotRotateTwice() {
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;height:100px'><p id=p>abc def</p><p id=q>ghi</p></div>");
        LayoutBox root = c.layout();
        assertFalse(find(root, "p").isVerticalRoot, "子は祖先の縦書きの内側（回転は根の 1 回だけ）");
        long wrappers = c.render(root).stream().filter(i -> i.id().endsWith("_vw")).count();
        assertEquals(1, wrappers);
    }

    @Test
    public void blockChildrenStackAsColumns() {
        // 段落は論理の「下」へ積む＝物理の「左」へ並ぶ（右から左）
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;height:100px'><p id=a>aaa</p><p id=b>bbb</p></div>");
        LayoutBox root = c.layout();
        LayoutBox a = find(root, "a"), b = find(root, "b");
        assertEquals(20, b.y - a.y, 0.01f, "論理の座標では下へ積む（描画時に回転して左へ並ぶ）");
        assertEquals(40, find(root, "v").w, 0.01f);
    }

    // ------------------------------------------------------------------ 表示項目

    @Test
    public void wrapperItemCarriesTheRotation() {
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;height:100px;width:50px'>abc def ghi</div>");
        LayoutBox root = c.layout();
        LayoutBox wrapper = find(root, "v").children.get(0);
        DisplayItem n = item(c.render(root), wrapper.node.key());
        assertNotNull(n, "回転を持つ錨");
        assertEquals(90f, n.rotate(), 0.001f);
        assertEquals(0f, n.meta().originX(), 0.001f, "原点は左上");
        assertEquals(0f, n.meta().originY(), 0.001f);
    }

    @Test
    public void mixedOrientationSplitsUprightCharacters() {
        // 日本語は 1 字ずつ -90°（回転を打ち消して正立）、ラテンは寝かせたまま 1 つの塊
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;height:300px'>日本語abc</div>");
        List<DisplayItem> t = texts(c.render());
        assertEquals(4, t.size(), "日・本・語 + abc: " + t.stream().map(DisplayItem::text).toList());
        assertEquals("日", t.get(0).text());
        assertEquals("本", t.get(1).text());
        assertEquals("語", t.get(2).text());
        assertEquals("abc", t.get(3).text());
        for (int i = 0; i < 3; i++) assertEquals(-90f, t.get(i).rotate(), 0.001f);
        assertEquals(0f, t.get(3).rotate(), 0.001f);
        // 論理の横方向に隙間なく並ぶ（後の字は前の字の右端から）
        for (int i = 1; i < 4; i++) {
            assertEquals(t.get(i - 1).x() + t.get(i - 1).width(), t.get(i).x(), 1.01f, "run " + i);
        }
    }

    @Test
    public void uprightOrientationStandsEveryCharacterUp() {
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;text-orientation:upright;height:300px'>ab日</div>");
        List<DisplayItem> t = texts(c.render());
        assertEquals(3, t.size());
        assertTrue(t.stream().allMatch(i -> i.rotate() == -90f));
    }

    @Test
    public void sidewaysOrientationKeepsEverythingRotated() {
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;text-orientation:sideways;height:300px'>日本語abc</div>");
        List<DisplayItem> t = texts(c.render());
        assertTrue(t.stream().allMatch(i -> i.rotate() == 0f), "全て寝かせたまま（打ち消しの回転なし）");
        assertEquals("日本語abc", joined(t));
    }

    @Test
    public void sidewaysWritingModeDoesNotStandCharactersUp() {
        Ctx c = new Ctx("<div id=v style='writing-mode:sideways-rl;height:300px'>日本語abc</div>");
        List<DisplayItem> t = texts(c.render());
        assertTrue(t.stream().allMatch(i -> i.rotate() == 0f));
        assertEquals("日本語abc", joined(t));
    }

    @Test
    public void latinOnlyTextIsOneRunInMixed() {
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;height:300px'>hello world</div>");
        List<DisplayItem> t = texts(c.render());
        assertTrue(t.stream().allMatch(i -> i.rotate() == 0f));
        assertEquals("hello world", joined(t), "ラテンだけなら字ごとに割らない（単語ごとの断片のまま）");
    }

    private static String joined(List<DisplayItem> t) {
        StringBuilder sb = new StringBuilder();
        for (DisplayItem i : t) sb.append(i.text());
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------ 画素

    /** 白地に黒で描いたときの、黒っぽい画素の外接矩形 {@code [minX, minY, maxX, maxY]}（無ければ null）。 */
    private static int[] inkBounds(int[] px, int x0, int y0, int x1, int y1) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = px[y * W + x];
                int lum = ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
                if (lum < 3 * 128) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX, maxY};
    }

    private static int[] inkBounds(int[] px) {
        return inkBounds(px, 0, 0, W, H);
    }

    private static int[] paint(String html) {
        StandaloneView view = new StandaloneView(CSS + html, W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            view.frame(1000, cpu, true);
            return cpu.pixmap().pixels().clone();
        } finally {
            view.close();
        }
    }

    @Test
    public void horizontalTextIsWideAndVerticalTextIsTall() {
        int[] h = inkBounds(paint("<div style='position:absolute;left:100px;top:100px;font:bold 20px monospace'>WWWWWWWWWW</div>"));
        int[] v = inkBounds(paint("<div style='position:absolute;left:100px;top:100px;height:300px;writing-mode:vertical-rl;font:bold 20px monospace'>WWWWWWWWWW</div>"));
        assertNotNull(h);
        assertNotNull(v);
        int hw = h[2] - h[0], hh = h[3] - h[1], vw = v[2] - v[0], vh = v[3] - v[1];
        assertTrue(hw > hh * 3, "横書きは横長: " + hw + "x" + hh);
        assertTrue(vh > vw * 3, "縦書きは縦長: " + vw + "x" + vh);
        // 横書きの文字列の長さ ≒ 縦書きの文字列の長さ（同じ文字列を 90° 回しただけ）
        assertEquals(hw, vh, Math.max(3, hw * 0.05));
    }

    @Test
    public void verticalTextStaysInsideItsBox() {
        // 行は根の内容ボックス（left 100〜140 / top 100〜400）の内側に描かれる
        int[] v = inkBounds(paint("<div style='position:absolute;left:100px;top:100px;width:40px;height:300px;writing-mode:vertical-rl;font:20px/20px monospace'>WWWW WWWW</div>"));
        assertNotNull(v);
        assertTrue(v[0] >= 100 - 1 && v[2] <= 140 + 1, "x は箱の内側: " + v[0] + ".." + v[2]);
        assertTrue(v[1] >= 100 - 1 && v[3] <= 400 + 1, "y は箱の内側: " + v[1] + ".." + v[3]);
    }

    @Test
    public void firstLineIsAtTheRightEdge() {
        // 2 行に折り返すと、1 行目は右の列・2 行目は左の列（vertical-rl）
        int[] px = paint("<div style='position:absolute;left:100px;top:100px;width:40px;height:60px;writing-mode:vertical-rl;font:bold 20px/20px monospace'>WWW WWW</div>");
        int[] right = inkBounds(px, 120, 100, 140, 400);
        int[] left = inkBounds(px, 100, 100, 120, 400);
        assertNotNull(right, "右の列に 1 行目");
        assertNotNull(left, "左の列に 2 行目");
    }

    @Test
    public void sidewaysLrReadsBottomToTop() {
        // sideways-lr は下から上へ読む: 先頭の 'I' は箱の下端側
        int[] px = paint("<div style='position:absolute;left:100px;top:100px;width:20px;height:200px;writing-mode:sideways-lr;font:bold 20px/20px monospace'>I</div>");
        int[] ink = inkBounds(px);
        assertNotNull(ink);
        assertTrue(ink[3] > 270, "文字列は箱の下端から始まる: " + ink[1] + ".." + ink[3]);
    }

    @Test
    public void uprightCharactersAreNotRotated() {
        // 'I'（縦長の字形）: sideways なら横棒、upright なら縦棒のまま
        String base = "<div style='position:absolute;left:100px;top:100px;width:40px;height:200px;writing-mode:vertical-rl;font:bold 24px/24px monospace;";
        int[] side = inkBounds(paint(base + "text-orientation:sideways'>I</div>"));
        int[] up = inkBounds(paint(base + "text-orientation:upright'>I</div>"));
        assertNotNull(side);
        assertNotNull(up);
        int sw = side[2] - side[0], sh = side[3] - side[1], uw = up[2] - up[0], uh = up[3] - up[1];
        assertTrue(sw >= sh, "寝かせた I は横棒: " + sw + "x" + sh);
        assertTrue(uh >= uw, "正立した I は縦棒: " + uw + "x" + uh);
    }

    @Test
    public void mixedKeepsAsciiSideways() {
        String base = "<div style='position:absolute;left:100px;top:100px;width:40px;height:200px;writing-mode:vertical-rl;font:bold 24px/24px monospace;";
        assertArrayEquals(paint(base + "text-orientation:sideways'>IW</div>"), paint(base + "text-orientation:mixed'>IW</div>"));
    }

    // ------------------------------------------------------------------ 動的な変更

    private static int[] frameAfter(StandaloneView view, SoftwareRenderBackend cpu, long now) {
        view.frame(now, cpu, true);
        return cpu.pixmap().pixels().clone();
    }

    private static final String BOX = "position:absolute;left:100px;top:100px;width:60px;height:200px;font:bold 20px/20px monospace;";

    @Test
    public void togglingWritingModeAtRuntimeMatchesAFreshRender() {
        String html = "<div id=d style='" + BOX + "'>WW WW WW</div>";
        StandaloneView view = new StandaloneView(CSS + html, W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            int[] horizontal = frameAfter(view, cpu, 1000);
            assertArrayEquals(paint(html), horizontal);
            ((com.nishiyu.enderframelib.core.dom.node.HtmlElement) view.root().getNodeById("d"))
                    .setAttribute("style", BOX + "writing-mode:vertical-rl");
            int[] vertical = frameAfter(view, cpu, 1016);
            assertArrayEquals(paint("<div id=d style='" + BOX + "writing-mode:vertical-rl'>WW WW WW</div>"), vertical);
            assertFalse(java.util.Arrays.equals(horizontal, vertical), "見た目が変わる");
            ((com.nishiyu.enderframelib.core.dom.node.HtmlElement) view.root().getNodeById("d"))
                    .setAttribute("style", BOX);
            assertArrayEquals(horizontal, frameAfter(view, cpu, 1032), "横書きへ戻すと元の画素");
        } finally {
            view.close();
        }
    }

    @Test
    public void textUpdateInsideVerticalRootMatchesAFreshRender() {
        String style = BOX + "writing-mode:vertical-rl";
        StandaloneView view = new StandaloneView(CSS + "<div style='" + style + "'><span id=t>WW</span> WW</div>", W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            frameAfter(view, cpu, 1000);
            view.root().getNodeById("t").updateTextContent("日本W");
            int[] after = frameAfter(view, cpu, 1016);
            assertArrayEquals(paint("<div style='" + style + "'><span id=t>日本W</span> WW</div>"), after);
        } finally {
            view.close();
        }
    }

    @Test
    public void hoverInsideVerticalRootMatchesAFreshRender() {
        String html = "<style>#h:hover{color:#f00}</style><div style='" + BOX + "writing-mode:vertical-rl'><span id=h>WW</span> WW</div>";
        StandaloneView view = new StandaloneView(CSS + html, W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            int[] before = frameAfter(view, cpu, 1000);
            view.ui().setHoveredElement(view.root().getNodeById("h").key());
            int[] hovered = frameAfter(view, cpu, 1016);
            assertFalse(java.util.Arrays.equals(before, hovered), "ホバーで色が変わる");
            view.ui().setHoveredElement("");
            assertArrayEquals(before, frameAfter(view, cpu, 1032));
        } finally {
            view.close();
        }
    }

    @Test
    public void hitTestFollowsTheRotation() {
        // 縦書きの文字列の先頭（右列の上端）を指すと、その span に当たる。横書きなら当たらない位置（箱の右上）
        String html = "<div style='" + BOX + "writing-mode:vertical-rl'><span id=a>WWWW</span></div>";
        StandaloneView view = new StandaloneView(CSS + html, W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            view.frame(1000, cpu, true);
            // 1 行目は右端の列（x 140〜160）の上（y 100〜）から下へ。文字列の中ほど (150, 130)
            view.mouseMove(150, 130, cpu.sessionId());
            view.frame(1016, cpu, true);
            var hit = view.ui().interactionState().hoveredNode();
            assertNotNull(hit);
            assertEquals("a", hit.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT ? hit.parent.getId() : hit.getId(),
                    "回転した文字の上でホバーが取れる");
            // 左の空き（文字が無い列）
            view.mouseMove(110, 130, cpu.sessionId());
            view.frame(1032, cpu, true);
            var hv = view.ui().interactionState().hoveredNode();
            assertTrue(hv == null || hv.nodeType == com.nishiyu.enderframelib.core.dom.node.NodeType.TEXT ? hv == null : !"a".equals(hv.getId()),
                    "文字の無い列では span の文字に当たらない");
        } finally {
            view.close();
        }
    }

    @Test
    public void scrollExtentDoesNotGrowFromTheRotatedWrapper() {
        // 内容が収まる縦書きの overflow:auto 箱に、回転前の箱（右へ張り出して置く）が原因の横スクロールを作らない
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;height:200px;width:80px;overflow:auto'>abc def</div>");
        LayoutBox v = find(c.layout(), "v");
        assertTrue(v.scrollWidth <= v.w + 0.5f, "横の内容幅 " + v.scrollWidth + " が箱の幅 " + v.w + " を超えない");
    }

    // ------------------------------------------------------------------ vertical-lr（転置）

    private static String lrBox(String mode, String extra, String text) {
        return "<div style='position:absolute;left:100px;top:100px;width:24px;height:200px;writing-mode:" + mode
                + ";font:bold 20px/24px monospace;" + extra + "'>" + text + "</div>";
    }

    @Test
    public void verticalLrSingleLineLooksLikeVerticalRl() {
        // 1 行だけなら、行の幅ぴったりの箱では vertical-lr と vertical-rl は同じ画素（文字の向き・読む向きが同じ）
        assertArrayEquals(paint(lrBox("vertical-rl", "", "L7F")), paint(lrBox("vertical-lr", "", "L7F")));
    }

    @Test
    public void verticalLrUprightLooksLikeVerticalRl() {
        assertArrayEquals(paint(lrBox("vertical-rl", "text-orientation:upright", "L7F")),
                paint(lrBox("vertical-lr", "text-orientation:upright", "L7F")));
    }

    @Test
    public void verticalLrStacksLinesLeftToRight() {
        String html = "<div id=v style='position:absolute;left:100px;top:100px;width:60px;height:60px;writing-mode:vertical-lr;font:bold 20px/20px monospace'>WWW WWW</div>";
        int[] px = paint(html);
        int[] left = inkBounds(px, 100, 100, 120, 400);
        int[] mid = inkBounds(px, 120, 100, 140, 400);
        assertNotNull(left, "1 行目は左の列");
        assertNotNull(mid, "2 行目は 1 行目の右");
        Ctx c = new Ctx(html);
        LayoutBox v = find(c.layout(), "v");
        assertEquals(v.x, v.children.get(0).x, 0.01f, "転置は左上に置く（張り出さない）");
        assertEquals(60, v.w, 0.01f);
    }

    @Test
    public void verticalLrBlocksStackLeftToRight() {
        // 段落の背景は転置のまま物理の向きに合う: 1 つ目の段落が左、2 つ目がその右
        String html = "<div id=v style='position:absolute;left:100px;top:100px;writing-mode:vertical-lr;height:100px'>"
                + "<p id=a style='background:#f00;height:30px'>x</p><p id=b style='background:#00f;height:30px'>y</p></div>";
        StandaloneView view = new StandaloneView(CSS + html, W, H, FontResolver.FALLBACK);
        try (SoftwareRenderBackend cpu = new SoftwareRenderBackend(W, H, 1f)) {
            view.frame(1000, cpu, true);
            int[] px = cpu.pixmap().pixels();
            int a = px[(100 + 50) * W + 105], b = px[(100 + 50) * W + 135];
            assertEquals(0xFF0000, a & 0xFFFFFF, "左の段落は赤");
            assertEquals(0x0000FF, b & 0xFFFFFF, "右の段落は青");
        } finally {
            view.close();
        }
    }
}
