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
    public void flexAndGridAndTableCanBeVerticalRoots() {
        Ctx cf = new Ctx("<div id=f style='display:flex;writing-mode:vertical-rl;height:100px'><div>x</div></div>");
        assertTrue(find(cf.layout(), "f").isVerticalRoot, "flex コンテナも縦書きの根になる");
        Ctx cg = new Ctx("<div id=g style='display:grid;writing-mode:vertical-rl;height:100px'><div>x</div></div>");
        assertTrue(find(cg.layout(), "g").isVerticalRoot, "grid コンテナも縦書きの根になる");
        Ctx ct = new Ctx("<table id=t style='writing-mode:vertical-rl;height:100px'><tr><td>x</td></tr></table>");
        assertTrue(find(ct.layout(), "t").isVerticalRoot, "table コンテナも縦書きの根になる");
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
        // 段落の背景は転置のまま物理の向きに合う: 1 つ目の段落が左、2 つ目がその右（子孫の width は物理幅 30px）
        String html = "<div id=v style='position:absolute;left:100px;top:100px;writing-mode:vertical-lr;height:100px'>"
                + "<p id=a style='background:#f00;width:30px'>x</p><p id=b style='background:#00f;width:30px'>y</p></div>";
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

    // ------------------------------------------------------------------ flex / grid / table の縦書き根

    @Test
    public void verticalFlexRowRunsTopToBottomAndColumnRunsRightToLeft() {
        // flex-direction: row（主軸＝論理インライン＝物理の上→下）で赤が上、青が下（子の width/height は物理）
        String rowHtml = "<div style='position:absolute;left:100px;top:100px;display:flex;writing-mode:vertical-rl;height:120px;gap:10px'>"
                + "<div style='width:20px;height:30px;background:#f00'></div>"
                + "<div style='width:20px;height:30px;background:#00f'></div></div>";
        int[] rowPx = paint(rowHtml);
        assertEquals(0xFF0000, rowPx[115 * W + 110] & 0xFFFFFF, "row の 1 つ目は上");
        assertEquals(0x0000FF, rowPx[155 * W + 110] & 0xFFFFFF, "row の 2 つ目は gap 10px 挟んで下");

        // flex-direction: column（主軸＝論理ブロック＝vertical-rl なので右→左）で赤が右、青が左
        String colHtml = "<div style='position:absolute;left:100px;top:100px;display:flex;flex-direction:column;writing-mode:vertical-rl;height:60px'>"
                + "<div style='width:25px;height:40px;background:#f00'></div>"
                + "<div style='width:25px;height:40px;background:#00f'></div></div>";
        int[] colPx = paint(colHtml);
        assertEquals(0xFF0000, colPx[120 * W + 135] & 0xFFFFFF, "column の 1 つ目は右");
        assertEquals(0x0000FF, colPx[120 * W + 110] & 0xFFFFFF, "column の 2 つ目は左");
    }

    @Test
    public void verticalGridPlacesTracksAlongVerticalAndHorizontalAxes() {
        // 2列×2行のグリッド（vertical-rl）: 列は縦（上から下へ 40px, 40px）、行は横（右から左へ 1fr, 1fr）
        String html = "<div style='position:absolute;left:100px;top:100px;display:grid;writing-mode:vertical-rl;"
                + "width:60px;height:80px;grid-template-columns:40px 40px;grid-template-rows:1fr 1fr'>"
                + "<div style='background:#f00'></div><div style='background:#0f0'></div>"
                + "<div style='background:#00f'></div><div style='background:#ff0'></div></div>";
        int[] px = paint(html);
        assertEquals(0xFF0000, px[120 * W + 145] & 0xFFFFFF, "(col0, row0) は右上＝赤");
        assertEquals(0x00FF00, px[160 * W + 145] & 0xFFFFFF, "(col1, row0) は右下＝緑");
        assertEquals(0x0000FF, px[120 * W + 115] & 0xFFFFFF, "(col0, row1) は左上＝青");
        assertEquals(0xFFFF00, px[160 * W + 115] & 0xFFFFFF, "(col1, row1) は左下＝黄");
    }

    @Test
    public void verticalTableStacksRowsAcrossColumns() {
        String html = "<table style='position:absolute;left:100px;top:100px;writing-mode:vertical-rl;border-collapse:collapse;height:80px'>"
                + "<tr><td style='width:30px;height:30px;padding:0;background:#f00'></td>"
                + "<td style='width:30px;height:30px;padding:0;background:#0f0'></td></tr>"
                + "<tr><td style='width:30px;height:30px;padding:0;background:#00f'></td>"
                + "<td style='width:30px;height:30px;padding:0;background:#ff0'></td></tr></table>";
        int[] px = paint(html);
        assertEquals(0xFF0000, px[115 * W + 145] & 0xFFFFFF, "1 行目 1 列目＝右上赤");
        assertEquals(0x00FF00, px[145 * W + 145] & 0xFFFFFF, "1 行目 2 列目＝右下緑");
        assertEquals(0x0000FF, px[115 * W + 115] & 0xFFFFFF, "2 行目 1 列目＝左上青");
        assertEquals(0xFFFF00, px[145 * W + 115] & 0xFFFFFF, "2 行目 2 列目＝左下黄");
    }

    // ------------------------------------------------------------------ 縦書き内の horizontal-tb（縦中横・直交戻し）

    @Test
    public void horizontalTbInsideVerticalReturnsToUprightHorizontal() {
        String html = "<div id=v style='position:absolute;left:100px;top:100px;writing-mode:vertical-rl;height:200px'>"
                + "<div id=h style='writing-mode:horizontal-tb;width:180px;font:bold 20px/24px monospace'>WWWWWW</div></div>";
        Ctx c = new Ctx(html);
        LayoutBox root = c.layout();
        LayoutBox h = find(root, "h");
        assertNotNull(h);
        assertTrue(h.isHorizontalReturnRoot, "縦書き内の horizontal-tb は直交戻しの根になる");

        int[] px = paint(html);
        int[] ink = inkBounds(px);
        assertNotNull(ink);
        int w = ink[2] - ink[0], ht = ink[3] - ink[1];
        assertTrue(w > ht * 3, "縦書きの中に戻した horizontal-tb は横長に描かれる: " + w + "x" + ht);
    }

    @Test
    public void tateChuYokoInlineBlockSitsHorizontallyInsideVerticalColumn() {
        // 縦中横: 縦に並ぶ文字列の中に inline-block の horizontal-tb("II") を挟む。
        // vertical-rl / vertical-lr / sideways-lr のいずれでも、水平の箱の中は正立横並び
        for (String mode : new String[]{"vertical-rl", "vertical-lr", "sideways-lr"}) {
            String html = "<div style='position:absolute;left:100px;top:100px;writing-mode:" + mode
                    + ";height:240px;font:bold 20px/24px monospace'>"
                    + "WWWW<span id=t style='display:inline-block;writing-mode:horizontal-tb'>II</span>WWWW</div>";
            Ctx c = new Ctx(html);
            LayoutBox root = c.layout();
            LayoutBox t = find(root, "t");
            assertTrue(t.isHorizontalReturnRoot, mode + " の中の縦中横");

            int[] px = paint("<div style='position:absolute;left:100px;top:100px;writing-mode:" + mode
                    + ";width:40px;height:200px;font:bold 24px/24px monospace'>"
                    + "<span style='display:inline-block;writing-mode:horizontal-tb'>I</span></div>");
            int[] ink = inkBounds(px);
            assertNotNull(ink, mode + " の縦中横が描画される");
            int iw = ink[2] - ink[0], ih = ink[3] - ink[1];
            assertTrue(ih >= iw, mode + " の縦中横の I は縦棒（正立）: " + iw + "x" + ih);
        }
    }

    @Test
    public void verticalThenHorizontalThenVerticalNestsCleanly() {
        String html = "<div id=v1 style='writing-mode:vertical-rl;height:200px'>"
                + "<div id=h1 style='writing-mode:horizontal-tb;width:150px'>"
                + "<div id=v2 style='writing-mode:vertical-rl;height:100px'>abc</div></div></div>";
        Ctx c = new Ctx(html);
        LayoutBox root = c.layout();
        assertTrue(find(root, "v1").isVerticalRoot);
        assertTrue(find(root, "h1").isHorizontalReturnRoot);
        assertTrue(find(root, "v2").isVerticalRoot);
    }

    @Test
    public void horizontalReturnBoxScrollExtentUsesRotatedBounds() {
        // 横幅 100px の horizontal-tb を縦書き内の overflow:auto 箱に入れたとき、scrollWidth/scrollHeight が逆回転前の座標で膨らまない
        Ctx c = new Ctx("<div id=v style='writing-mode:vertical-rl;height:200px;width:150px;overflow:auto'>"
                + "<div id=h style='writing-mode:horizontal-tb;width:100px;height:40px;overflow:auto'>abc</div></div>");
        LayoutBox h = find(c.layout(), "h");
        assertTrue(h.scrollWidth <= h.w + 0.5f, "scrollWidth=" + h.scrollWidth + " <= h.w=" + h.w);
        assertTrue(h.scrollHeight <= h.h + 0.5f, "scrollHeight=" + h.scrollHeight + " <= h.h=" + h.h);
    }

    // ------------------------------------------------------------------ 縦書き子孫の物理プロパティ

    private static int[] colorBounds(int[] px, int targetRgb) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int rgb = px[y * W + x] & 0xFFFFFF;
                boolean match = targetRgb < 0 ? rgb != 0xFFFFFF : rgb == targetRgb;
                if (match) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        return maxX < 0 ? null : new int[]{minX, minY, maxX, maxY};
    }

    @Test
    public void descendantPhysicalWidthHeightAreNotSwappedByVerticalWritingMode() {
        for (String mode : new String[]{"vertical-rl", "vertical-lr"}) {
            String html = "<div style='position:absolute;left:80px;top:80px;writing-mode:" + mode + ";width:160px;height:160px'>"
                    + "<div id=child style='width:70px;height:40px;background:#e11'></div></div>";
            int[] bounds = colorBounds(paint(html), -1);
            assertNotNull(bounds);
            assertEquals(70, bounds[2] - bounds[0] + 1, 1, mode + " 描画横幅");
            assertEquals(40, bounds[3] - bounds[1] + 1, 1, mode + " 描画縦幅");
        }
    }

    @Test
    public void descendantPhysicalMarginAndPaddingApplyToPhysicalSides() {
        // vertical-rl / vertical-lr 内の子: margin-top:12px で上端が下がり、padding-top:6px で中身が物理下に寄る
        // block-start（vertical-rl は右、vertical-lr は左）側の余白・padding も物理辺に一致する
        for (String mode : new String[]{"vertical-rl", "vertical-lr"}) {
            boolean rl = "vertical-rl".equals(mode);
            String html = "<div style='position:absolute;left:50px;top:50px;writing-mode:" + mode + ";width:120px;height:120px'>"
                    + "<div id=box style='box-sizing:border-box;width:60px;height:60px;margin-top:12px;margin-left:8px;margin-right:8px;"
                    + "padding-left:14px;padding-right:10px;padding-top:6px;background:#0a0'>"
                    + "<div id=inner style='width:20px;height:20px;background:#00f'></div>"
                    + "</div></div>";
            int[] px = paint(html);
            int[] boxB = colorBounds(px, -1);
            int[] innerB = colorBounds(px, 0x0000FF);
            assertNotNull(boxB);
            assertNotNull(innerB);
            assertEquals(62, boxB[1], 1, mode + " margin-top:12px は物理上余白 (50+12)");
            if (rl) {
                assertEquals(50 + 120 - 1 - 8, boxB[2], 1, mode + " margin-right:8px は物理右余白");
                assertEquals(10, boxB[2] - innerB[2], 1, mode + " padding-right:10px で中身が物理左に 10px 寄る");
            } else {
                assertEquals(58, boxB[0], 1, mode + " margin-left:8px は物理左余白 (50+8)");
                assertEquals(14, innerB[0] - boxB[0], 1, mode + " padding-left:14px で中身が物理右に 14px 寄る");
            }
            assertEquals(6, innerB[1] - boxB[1], 1, mode + " padding-top:6px で中身が物理下に 6px 寄る");
        }
    }

    @Test
    public void descendantPhysicalBordersAndCornerRadiusMatchPhysicalEdgesAndCorners() {
        for (String mode : new String[]{"vertical-rl", "vertical-lr", "sideways-lr"}) {
            String html = "<div style='position:absolute;left:60px;top:60px;writing-mode:" + mode + ";width:120px;height:120px'>"
                    + "<div id=card style='width:80px;height:80px;box-sizing:border-box;"
                    + "border-top:8px solid #f00;border-left:8px solid #00f;"
                    + "border-bottom-right-radius:20px;background:#0a0'></div></div>";
            int[] px = paint(html);
            int[] b = colorBounds(px, -1);
            assertNotNull(b);
            assertEquals(80, b[2] - b[0] + 1, 1, mode + " width");
            assertEquals(80, b[3] - b[1] + 1, 1, mode + " height");
            int x0 = b[0], y0 = b[1];
            assertEquals(0xFF0000, px[(y0 + 3) * W + (x0 + 40)] & 0xFFFFFF, mode + " border-top は物理上辺");
            assertEquals(0x0000FF, px[(y0 + 40) * W + (x0 + 3)] & 0xFFFFFF, mode + " border-left は物理左辺");
            assertEquals(0xFFFFFF, px[(y0 + 78) * W + (x0 + 78)] & 0xFFFFFF, mode + " border-bottom-right-radius は物理右下隅を丸める");
            assertEquals(0x00AA00, px[(y0 + 70) * W + (x0 + 20)] & 0xFFFFFF, mode + " 左下寄りの内部は緑のまま");
        }
    }

    @Test
    public void descendantPhysicalOverflowClipsCorrespondingPhysicalAxis() {
        // CSS Overflow 3 §3.1 により単軸だけを切るには clip を使う:
        // overflow-x:clip / overflow-y:visible -> 物理横方向 (40px) のみ切られ、物理縦方向 (90px) は見える
        String htmlX = "<div style='position:absolute;left:50px;top:50px;writing-mode:vertical-rl;width:160px;height:160px'>"
                + "<div id=c style='width:40px;height:40px;overflow-x:clip;overflow-y:visible'>"
                + "<div style='width:100px;height:90px;background:#e11'></div>"
                + "</div></div>";
        int[] bX = colorBounds(paint(htmlX), -1);
        assertNotNull(bX);
        assertEquals(40, bX[2] - bX[0] + 1, 1, "overflow-x:clip は物理横はみ出しを 40px で切る");
        assertEquals(90, bX[3] - bX[1] + 1, 1, "overflow-y:visible は物理縦はみ出し 90px を残す");

        // 逆: overflow-x:visible / overflow-y:clip
        String htmlY = "<div style='position:absolute;left:50px;top:50px;writing-mode:vertical-rl;width:160px;height:160px'>"
                + "<div id=c style='width:40px;height:40px;overflow-x:visible;overflow-y:clip'>"
                + "<div style='width:100px;height:90px;background:#11e'></div>"
                + "</div></div>";
        int[] bY = colorBounds(paint(htmlY), -1);
        assertNotNull(bY);
        assertEquals(100, bY[2] - bY[0] + 1, 1, "overflow-x:visible は物理横はみ出し 100px を残す");
        assertEquals(40, bY[3] - bY[1] + 1, 1, "overflow-y:clip は物理縦はみ出しを 40px で切る");
    }
}
