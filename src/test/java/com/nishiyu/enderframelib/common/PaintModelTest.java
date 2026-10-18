package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.UIRenderer;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.model.UIElement;
import com.nishiyu.enderframelib.core.text.FontFace.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 描画モデル（W5）の検証。
 *
 * スタッキングコンテキストと z-index の前後は忠実度コーパスの z 列がブラウザと突き合わせている。
 * ここでは矩形と描画順の列に出ない振る舞いを固定する: 付録 E の層（ブロックの背景とインライン内容）、
 * キャンバスへの背景の伝播、枠線の幅の算出値と線種、輪郭線、背景のレイヤー。
 */
public class PaintModelTest {

    private static final int VIEWPORT_W = 320;
    private static final int VIEWPORT_H = 200;

    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;

    @Test
    public void testLaterBlockBackgroundPaintsBelowEarlierInlineContent() {
        // 付録 E: 通常フローのブロックの背景（層 4）はすべてインライン内容（層 7）より下
        Rendered r = render("""
                <div id="a" style="height: 20px; font-size: 0;"><span id="s" style="display: inline-block; width: 10px; height: 10px;"></span></div>
                <div id="b" style="height: 20px; margin-top: -20px; background-color: red;"></div>
                """);
        List<LayoutBox> order = r.renderer.paintOrder(r.root);
        int b = order.indexOf(find(r.root, "b"));
        int s = order.indexOf(find(r.root, "s"));
        assertTrue(b >= 0 && s > b, "後のブロックの背景がインラインブロックを覆っている: b=" + b + " s=" + s);
    }

    @Test
    public void testCanvasTakesRootBackgroundAndRootDoesNotRepaintIt() {
        Rendered r = render("<style>html { background-color: rgb(0, 0, 255); }</style><div style=\"height: 10px;\"></div>");
        UIElement canvas = r.elements.getFirst();
        assertEquals("bg_root", canvas.id());
        assertEquals(0xFF0000FF, canvas.bgColor());
        long repainted = r.elements.stream().filter(e -> e != canvas && e.bgColor() == 0xFF0000FF).count();
        assertEquals(0, repainted, "キャンバスに譲った背景を html の箱がもう一度塗っている");
    }

    @Test
    public void testCanvasIsTransparentWithoutRootBackground() {
        // ゲームの上に重ねる文書は iframe と同じく透明（HUD がゲーム画面を塗りつぶさない）
        Rendered r = render("<div style=\"height: 10px;\"></div>");
        assertTrue(r.elements.stream().noneMatch(e -> "bg_root".equals(e.id())));
    }

    @Test
    public void testBorderWidthDependsOnBorderStyle() {
        Rendered r = render("""
                <div id="a" style="border-width: 2px;"></div>
                <div id="b" style="border-style: solid;"></div>
                <div id="c" style="border: 4px none red;"></div>
                <div id="d" style="border: thin solid;"></div>
                <div id="e" style="border: 2px hidden; border-left-style: dashed;"></div>
                """);
        assertEquals(0, find(r.root, "a").bT, 0.01f);
        assertEquals(3, find(r.root, "b").bT, 0.01f);
        assertEquals(0, find(r.root, "c").bT, 0.01f);
        assertEquals(1, find(r.root, "d").bT, 0.01f);
        assertEquals(0, find(r.root, "e").bT, 0.01f);
        assertEquals(2, find(r.root, "e").bL, 0.01f);
    }

    @Test
    public void testBorderWithoutColorUsesCurrentColor() {
        Rendered r = render("<div id=\"a\" style=\"color: rgb(0, 255, 0); height: 10px; border: 1px solid;\"></div>");
        UIElement top = element(r, "a_b_top");
        assertNotNull(top);
        assertEquals(GREEN, top.bgColor());
    }

    @Test
    public void testDashedAndDottedBordersSplitIntoSegments() {
        Rendered r = render("""
                <div id="a" style="width: 60px; height: 20px; border: 2px dashed rgb(255, 0, 0);"></div>
                <div id="b" style="width: 60px; height: 20px; border: 4px dotted rgb(255, 0, 0);"></div>
                """);
        List<UIElement> dashes = parts(r, "a_b_top");
        assertTrue(dashes.size() > 2, "破線が 1 本の線のまま: " + dashes.size());
        for (UIElement d : dashes) {
            assertEquals(2, d.height());
            assertEquals(6, d.width());
            assertEquals(RED, d.bgColor());
        }
        // 両端が線で終わる
        LayoutBox a = find(r.root, "a");
        assertEquals(Math.round(a.x), dashes.getFirst().x());
        UIElement last = dashes.getLast();
        assertEquals(Math.round(a.x + a.w), last.x() + last.width());

        List<UIElement> dots = parts(r, "b_b_left");
        assertTrue(dots.size() > 2);
        assertEquals(4, dots.getFirst().width());
        assertEquals(4, dots.getFirst().height());
        assertEquals(2, dots.getFirst().meta().borderRadii().topLeft(), "4px の点線の点が丸くない");
    }

    @Test
    public void testDoubleBorderDrawsTwoLinesPerSide() {
        Rendered r = render("<div id=\"a\" style=\"width: 60px; height: 30px; border: 6px double rgb(255, 0, 0);\"></div>");
        List<UIElement> lines = parts(r, "a_b_top");
        assertEquals(2, lines.size());
        LayoutBox a = find(r.root, "a");
        assertEquals(2, lines.get(0).height());
        assertEquals(Math.round(a.y), lines.get(0).y());
        assertEquals(Math.round(a.y + 4), lines.get(1).y());
        // 内側の線は左右の辺の内側の線と角で接する（幅 6 の 2/3 = 4 ずつ縮む）
        assertEquals(lines.get(0).width() - 8, lines.get(1).width());
    }

    @Test
    public void testOutlineIsDrawnOutsideTheBorderBoxWithoutAffectingLayout() {
        Rendered r = render("<div id=\"a\" style=\"margin: 10px; width: 50px; height: 20px; outline: 2px solid rgb(255, 0, 0); outline-offset: 3px;\"></div>");
        LayoutBox a = find(r.root, "a");
        assertEquals(50, a.w, 0.01f);
        UIElement top = element(r, "a_o_top");
        assertNotNull(top);
        assertEquals(Math.round(a.x - 5), top.x());
        assertEquals(Math.round(a.y - 5), top.y());
        assertEquals(60, top.width());
        assertEquals(2, top.height());
        assertEquals(RED, top.bgColor());
    }

    @Test
    public void testBackgroundShorthandExpandsIntoLayerLists() {
        Rendered r = render("<div id=\"a\" style=\"height: 10px; background: url(a.png) center / 10px 20px no-repeat, linear-gradient(to right, red, blue) padding-box rgb(0, 255, 0);\"></div>");
        var style = find(r.root, "a").style;
        assertEquals("url(a.png), linear-gradient(to right, red, blue)", style.get("background-image"));
        assertEquals("10px 20px, auto", style.get("background-size"));
        assertEquals("no-repeat, repeat", style.get("background-repeat"));
        assertEquals("center, 0% 0%", style.get("background-position"));
        assertEquals("padding-box, padding-box", style.get("background-origin"));
        assertEquals("border-box, padding-box", style.get("background-clip"));
        assertTrue(style.get("background-color").contains("0, 255, 0") || "#00ff00".equalsIgnoreCase(style.get("background-color")),
                style.get("background-color"));
    }

    @Test
    public void testBackgroundImageTilesFollowRepeatSizeAndPosition() {
        Rendered r = render("""
                <div id="a" style="width: 35px; height: 10px; background: url(a.png) repeat-x; background-size: 10px 10px;"></div>
                <div id="b" style="width: 40px; height: 40px; background: url(b.png) no-repeat center / 10px 10px;"></div>
                """);
        List<UIElement> tiles = parts(r, "a_bgl0");
        assertEquals(4, tiles.size());
        LayoutBox a = find(r.root, "a");
        for (int i = 0; i < 4; i++) {
            assertEquals(Math.round(a.x + i * 10), tiles.get(i).x());
            assertEquals(UIElement.ElementType.IMAGE, tiles.get(i).type());
            assertEquals("a.png", tiles.get(i).text());
        }
        // 描画領域（border-box）で切る
        assertEquals(a.x + a.w, tiles.getLast().meta().clipRect().maxX(), 0.01f);

        List<UIElement> centered = parts(r, "b_bgl0");
        assertEquals(1, centered.size());
        LayoutBox b = find(r.root, "b");
        assertEquals(Math.round(b.x + 15), centered.getFirst().x());
        assertEquals(Math.round(b.y + 15), centered.getFirst().y());
        assertEquals(10, centered.getFirst().width());
    }

    @Test
    public void testBackgroundClipPaddingBoxKeepsColorOffTheBorder() {
        Rendered r = render("<div id=\"a\" style=\"width: 20px; height: 20px; border: 5px solid rgba(0, 0, 0, 0.5); background-color: rgb(255, 0, 0); background-clip: padding-box;\"></div>");
        LayoutBox a = find(r.root, "a");
        UIElement bg = element(r, "a_bg");
        assertNotNull(bg, "ヒットテストの受け口の _bg が消えている");
        assertEquals(0, bg.bgColor());
        UIElement color = element(r, "a_bgl1_0");
        assertNotNull(color);
        assertEquals(RED, color.bgColor());
        assertEquals(Math.round(a.x + 5), color.x());
        assertEquals(20, color.width());
    }

    // ==========================================

    private record Rendered(UIRenderer renderer, LayoutBox root, List<UIElement> elements) {}

    private static Rendered render(String html) {
        UIParser parser = new UIParser();
        UIParser.Document doc = parser.parseDocument(
                "<html><head><style>body { margin: 0; }</style></head><body>" + html + "</body></html>", "", VIEWPORT_W, VIEWPORT_H);
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        UIRenderer renderer = new UIRenderer(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, new ScrollState());
        return new Rendered(renderer, root, renderer.render(root));
    }

    private static UIElement element(Rendered r, String id) {
        return r.elements.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    /** 番号の付く部品（{@code <prefix>} と {@code <prefix>_<n>}）を描画順に。 */
    private static List<UIElement> parts(Rendered r, String prefix) {
        return r.elements.stream().filter(e -> e.id().equals(prefix) || e.id().startsWith(prefix + "_")).toList();
    }

    private static LayoutBox find(LayoutBox box, String id) {
        if (id.equals(box.node.attrs.get("id"))) return box;
        for (LayoutBox child : box.elementChildren) {
            LayoutBox hit = find(child, id);
            if (hit != null) return hit;
        }
        return null;
    }
}
