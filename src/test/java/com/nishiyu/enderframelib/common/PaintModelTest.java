package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.BoxDecorations;
import com.nishiyu.enderframelib.client.renderer.Gradients;
import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.LayoutEngine.LayoutContext.ScrollState;
import com.nishiyu.enderframelib.core.layout.DisplayItem;
import com.nishiyu.enderframelib.core.layout.TextShaper.FontFace.FontResolver;
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
 * W8 の分: 角丸の枠線が枠の領域だけを塗ること、overflow のロングハンドと包含ブロックに沿ったクリップ、
 * 入れ子のクリップの角丸、visibility と pointer-events、box-shadow、linear-gradient の角度と停止点。
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
        DisplayItem canvas = r.elements.getFirst();
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
        DisplayItem top = element(r, "a_b_top");
        assertNotNull(top);
        assertEquals(GREEN, top.bgColor());
    }

    @Test
    public void testDashedAndDottedBordersSplitIntoSegments() {
        Rendered r = render("""
                <div id="a" style="width: 60px; height: 20px; border: 2px dashed rgb(255, 0, 0);"></div>
                <div id="b" style="width: 60px; height: 20px; border: 4px dotted rgb(255, 0, 0);"></div>
                """);
        List<DisplayItem> dashes = parts(r, "a_b_top");
        assertTrue(dashes.size() > 2, "破線が 1 本の線のまま: " + dashes.size());
        for (DisplayItem d : dashes) {
            assertEquals(2, d.height());
            assertEquals(6, d.width());
            assertEquals(RED, d.bgColor());
        }
        // 両端が線で終わる
        LayoutBox a = find(r.root, "a");
        assertEquals(Math.round(a.x), dashes.getFirst().x());
        DisplayItem last = dashes.getLast();
        assertEquals(Math.round(a.x + a.w), last.x() + last.width());

        List<DisplayItem> dots = parts(r, "b_b_left");
        assertTrue(dots.size() > 2);
        assertEquals(4, dots.getFirst().width());
        assertEquals(4, dots.getFirst().height());
        assertEquals(2, dots.getFirst().meta().borderRadii().topLeft(), "4px の点線の点が丸くない");
    }

    @Test
    public void testDoubleBorderDrawsTwoLinesPerSide() {
        Rendered r = render("<div id=\"a\" style=\"width: 60px; height: 30px; border: 6px double rgb(255, 0, 0);\"></div>");
        List<DisplayItem> lines = parts(r, "a_b_top");
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
        DisplayItem top = element(r, "a_o_top");
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
        List<DisplayItem> tiles = parts(r, "a_bgl0");
        assertEquals(4, tiles.size());
        LayoutBox a = find(r.root, "a");
        for (int i = 0; i < 4; i++) {
            assertEquals(Math.round(a.x + i * 10), tiles.get(i).x());
            assertEquals(DisplayItem.ElementType.IMAGE, tiles.get(i).type());
            assertEquals("a.png", tiles.get(i).text());
        }
        // 描画領域（border-box）で切る
        assertEquals(a.x + a.w, tiles.getLast().meta().clipRect().maxX(), 0.01f);

        List<DisplayItem> centered = parts(r, "b_bgl0");
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
        DisplayItem bg = element(r, "a_bg");
        assertNotNull(bg, "ヒットテストの受け口の _bg が消えている");
        assertEquals(0, bg.bgColor());
        DisplayItem color = element(r, "a_bgl1_0");
        assertNotNull(color);
        assertEquals(RED, color.bgColor());
        assertEquals(Math.round(a.x + 5), color.x());
        assertEquals(20, color.width());
    }

    // ==========================================
    // W8: 角丸の枠線・クリップ・可視性・影・グラデーション
    // ==========================================

    @Test
    public void testTransparentRoundedBorderPaintsOnlyTheBorderArea() {
        Rendered r = render("<div id=\"a\" style=\"width: 40px; height: 20px; border: 2px solid rgb(255, 0, 0); border-radius: 6px; background: transparent;\"></div>");
        LayoutBox a = find(r.root, "a");
        // 以前は箱全体を枠の色で塗った下地（_bbg）が出て、透明な背景の箱が赤く塗りつぶされていた
        assertTrue(r.elements.stream().noneMatch(e -> e.id().startsWith("a_") && e.bgColor() == RED),
                "枠の色で塗りつぶす部品が残っている");
        DisplayItem ring = element(r, "a_b_round");
        assertNotNull(ring);
        assertEquals(DisplayItem.ElementType.ROUNDED_BORDER, ring.type());
        DisplayItem.RoundedBorderSpec spec = ring.meta().roundedBorder();
        assertEquals(2, spec.widthTop(), 0.01f);
        assertEquals(RED, spec.colorLeft());
        assertEquals(6, ring.meta().borderRadii().topLeft());

        // 片は枠の領域だけを覆う: 中央の行は左右の 2px だけ、どの片も内側の矩形に食い込まない
        List<BoxDecorations.Span> spans = BoxDecorations.roundedBorderSpans(a.x, a.y, a.w, a.h,
                new float[]{6, 6, 6, 6}, new float[]{2, 2, 2, 2});
        float midY = a.y + a.h / 2;
        List<BoxDecorations.Span> middle = spans.stream().filter(s -> s.y() <= midY && s.y() + s.h() >= midY).toList();
        assertEquals(2, middle.size());
        for (BoxDecorations.Span s : middle) assertEquals(2, s.w(), 0.01f);
        for (BoxDecorations.Span s : spans) {
            boolean overlapsInner = s.x() < a.x + a.w - 6 && s.x() + s.w() > a.x + 6
                    && s.y() < a.y + a.h - 2 && s.y() + s.h() > a.y + 2;
            assertTrue(!overlapsInner, "枠の片が内側を塗っている: " + s);
        }
        // 面積は「外側の角丸矩形 − 内側の角丸矩形」に近い（content-box なので枠線ボックスは 44 × 24）
        double area = spans.stream().mapToDouble(s -> s.w() * s.h()).sum();
        double outer = a.w * a.h - (4 - Math.PI) * 36;
        double inner = (a.w - 4) * (a.h - 4) - (4 - Math.PI) * 16;
        assertEquals(outer - inner, area, 6.0);
    }

    @Test
    public void testRoundedBorderDrawsBackgroundUnderTheWholeBorderBox() {
        Rendered r = render("<div id=\"a\" style=\"width: 40px; height: 20px; border: 2px solid rgb(255, 0, 0); border-radius: 6px; background-color: rgba(0, 255, 0, 0.5);\"></div>");
        LayoutBox a = find(r.root, "a");
        DisplayItem bg = element(r, "a_bg");
        assertNotNull(bg);
        assertEquals(0x8000FF00, bg.bgColor());
        assertEquals(Math.round(a.w), bg.width());
        // 枠線は背景の後（上）に描く
        assertTrue(r.elements.indexOf(bg) < r.elements.indexOf(element(r, "a_b_round")));
    }

    @Test
    public void testOverflowLonghandsClipChildren() {
        Rendered r = render("""
                <div id="a" style="height: 20px; overflow-y: auto;"><div id="c" style="height: 50px; background-color: rgb(0, 255, 0);"></div></div>
                <div id="b" style="height: 20px; overflow: hidden;"><div id="d" style="height: 50px; background-color: rgb(0, 255, 0);"></div></div>
                <div id="e" style="width: 30px; height: 20px; overflow-x: clip;"><div id="f" style="width: 80px; height: 50px; background-color: rgb(0, 255, 0);"></div></div>
                """);
        LayoutBox a = find(r.root, "a");
        DisplayItem.ClipRect c = element(r, "c_bg").meta().clipRect();
        assertNotNull(c, "overflow-y: auto の中身が切られていない");
        assertEquals(a.y + a.h, c.maxY(), 0.01f);

        LayoutBox b = find(r.root, "b");
        DisplayItem.ClipRect d = element(r, "d_bg").meta().clipRect();
        assertNotNull(d, "overflow: hidden の中身が切られていない");
        assertEquals(b.y + b.h, d.maxY(), 0.01f);

        // overflow-x: clip（もう片方は visible）は横だけを切る
        LayoutBox e = find(r.root, "e");
        DisplayItem.ClipRect f = element(r, "f_bg").meta().clipRect();
        assertNotNull(f);
        assertEquals(e.x + e.w, f.maxX(), 0.01f);
        assertTrue(f.maxY() > e.y + 1000, "overflow-x: clip が縦も切っている: " + f);
        // スクロールバーは overflow-y: auto の箱にだけ出る
        assertNotNull(element(r, "a_sb_v"));
        assertTrue(r.elements.stream().noneMatch(x -> x.id().startsWith("b_sb_")));
    }

    @Test
    public void testAbsoluteAndFixedDescendantsEscapeOverflowOfNonContainingBlocks() {
        Rendered r = render("""
                <div id="wrap" style="position: relative;">
                  <div id="o" style="height: 10px; overflow: hidden;">
                    <div id="abs" style="position: absolute; top: 0; width: 10px; height: 50px; background-color: rgb(0, 255, 0);"></div>
                    <div id="fix" style="position: fixed; top: 0; width: 10px; height: 50px; background-color: rgb(0, 255, 0);"></div>
                    <div id="flow" style="height: 50px; background-color: rgb(0, 255, 0);"></div>
                  </div>
                </div>
                <div id="o2" style="position: relative; height: 10px; overflow: hidden;">
                  <div id="inner" style="position: absolute; top: 0; width: 10px; height: 50px; background-color: rgb(0, 255, 0);"></div>
                </div>
                """);
        assertNotNull(element(r, "flow_bg").meta().clipRect(), "通常フローの子は切られる");
        assertEquals(null, element(r, "abs_bg").meta().clipRect(), "包含ブロックでない overflow が絶対配置を切っている");
        assertEquals(null, element(r, "fix_bg").meta().clipRect(), "固定配置が祖先の overflow で切られている");
        // 包含ブロック自身の overflow は切る
        LayoutBox o2 = find(r.root, "o2");
        DisplayItem.ClipRect inner = element(r, "inner_bg").meta().clipRect();
        assertNotNull(inner);
        assertEquals(o2.y + o2.h, inner.maxY(), 0.01f);
    }

    @Test
    public void testNestedClipKeepsParentRoundedCornersAndCollapsesWhenDisjoint() {
        Rendered r = render("""
                <div id="p" style="width: 100px; height: 60px; border-radius: 12px; overflow: hidden;">
                  <div id="s" style="height: 60px; overflow-y: auto;"><div id="c" style="height: 100px; background-color: rgb(0, 255, 0);"></div></div>
                </div>
                """);
        DisplayItem.ClipRect c = element(r, "c_bg").meta().clipRect();
        assertEquals(12, c.radiusTopLeft(), 0.01f, "角丸の親の中の角丸の無いスクロール領域で親の角が消えている");
        assertEquals(12, c.radiusBottomRight(), 0.01f);

        float[] disjoint = BoxDecorations.intersectClip(new float[]{0, 0, 10, 10, 0, 0, 0, 0}, new float[]{20, 20, 30, 30, 0, 0, 0, 0});
        assertTrue(disjoint[2] >= disjoint[0] && disjoint[3] >= disjoint[1], "交わらないクリップが負の大きさ");
        assertEquals(0, disjoint[2] - disjoint[0], 0.001f);
    }

    @Test
    public void testVisibilityHiddenSkipsOwnPaintButKeepsVisibleDescendants() {
        Rendered r = render("""
                <div id="h" style="visibility: hidden; height: 30px; background-color: rgb(255, 0, 0); border: 1px solid; box-shadow: 0 0 0 2px rgb(0, 0, 0);">hidden text
                  <div id="v" style="visibility: visible; height: 10px; background-color: rgb(0, 255, 0);"></div>
                  <div id="i" style="height: 10px; background-color: rgb(0, 255, 0);"></div>
                </div>
                <div id="after" style="height: 10px;"></div>
                """);
        assertTrue(r.elements.stream().noneMatch(e -> e.id().startsWith("h_")), "visibility: hidden の箱の部品が出ている");
        assertTrue(r.elements.stream().noneMatch(e -> e.type() == DisplayItem.ElementType.TEXT && e.text().contains("hidden")),
                "visibility: hidden の文字が描かれている");
        assertNotNull(element(r, "v_bg"), "visible に戻した子孫が描かれていない");
        assertEquals(null, element(r, "i_bg"), "visibility を継いだ子が描かれている");
        // 場所は取る
        assertTrue(find(r.root, "after").y >= find(r.root, "h").y + 30);
    }

    @Test
    public void testPointerEventsNoneIsCarriedOnEveryPart() {
        Rendered r = render("<div id=\"a\" style=\"pointer-events: none; height: 20px; background-color: rgb(255, 0, 0); border: 1px solid;\">text</div>");
        List<DisplayItem> parts = r.elements.stream().filter(e -> "a".equals(e.baseId()) || e.id().startsWith("a_")).toList();
        assertTrue(parts.size() > 2);
        for (DisplayItem e : parts) {
            assertEquals("none", e.events().get(DisplayItem.RenderKeys.POINTER_EVENTS), "pointer-events が載っていない: " + e.id());
        }
        // 子のテキストノードは継承した値を持つ
        assertTrue(r.elements.stream().filter(e -> e.type() == DisplayItem.ElementType.TEXT)
                .allMatch(e -> "none".equals(e.events().get(DisplayItem.RenderKeys.POINTER_EVENTS))));
    }

    @Test
    public void testBoxShadowReadsOffsetSpreadBlurAndInset() {
        List<BoxDecorations.Shadow> shadows = BoxDecorations.parseBoxShadows(
                "2px 3px 4px 1px rgba(0, 0, 0, 0.5), inset 0 0 0 2px rgb(255, 0, 0), 1px 1px", 0xFF00FF00, 320, 200, 16, 16);
        assertEquals(3, shadows.size());
        BoxDecorations.Shadow s = shadows.getFirst();
        assertEquals(2, s.offsetX(), 0.01f);
        assertEquals(3, s.offsetY(), 0.01f);
        assertEquals(4, s.blur(), 0.01f);
        assertEquals(1, s.spread(), 0.01f);
        assertTrue(!s.inset());
        assertTrue(shadows.get(1).inset());
        assertEquals(RED, shadows.get(1).color());
        assertEquals(GREEN, shadows.get(2).color(), "色の無い影は currentcolor");
        assertTrue(BoxDecorations.parseBoxShadows("2px rgb(0, 0, 0)", 0, 320, 200, 16, 16).isEmpty(), "長さが 1 つの影は無効");

        Rendered r = render("<div id=\"a\" style=\"margin: 20px; width: 40px; height: 20px; background-color: rgb(255, 255, 255); box-shadow: 5px 6px 0 2px rgb(0, 0, 0);\"></div>");
        LayoutBox a = find(r.root, "a");
        List<DisplayItem> parts = parts(r, "a_shadow");
        assertEquals(1, parts.size(), "ぼかしの無い影は 1 枚");
        DisplayItem shadow = parts.getFirst();
        assertEquals(Math.round(a.x + 5 - 2), shadow.x());
        assertEquals(Math.round(a.y + 6 - 2), shadow.y());
        assertEquals(44, shadow.width());
        assertEquals(24, shadow.height());
        assertEquals(0xFF000000, shadow.bgColor());
        // 影は箱の背景より下
        assertTrue(r.elements.indexOf(shadow) < r.elements.indexOf(element(r, "a_bg")));

        Rendered blurred = render("<div id=\"a\" style=\"width: 40px; height: 20px; box-shadow: 0 0 8px rgb(0, 0, 0);\"></div>");
        assertTrue(parts(blurred, "a_shadow").size() >= 2, "ぼかしが層に分かれていない");
    }

    @Test
    public void testInsetShadowPaintsInsideThePaddingBoxAboveTheBackground() {
        Rendered r = render("<div id=\"a\" style=\"width: 40px; height: 20px; border: 2px solid rgb(0, 0, 255); background-color: rgb(255, 255, 255); box-shadow: inset 0 0 0 3px rgb(255, 0, 0);\"></div>");
        LayoutBox a = find(r.root, "a");
        List<DisplayItem> bands = r.elements.stream().filter(e -> e.id().startsWith("a_shadow_in")).toList();
        assertEquals(4, bands.size());
        for (DisplayItem band : bands) {
            assertTrue(band.x() >= Math.round(a.x + 2) && band.x() + band.width() <= Math.round(a.x + a.w - 2), "内側の影がパディングボックスの外に出ている");
            assertEquals(RED, band.bgColor());
            assertTrue(r.elements.indexOf(band) > r.elements.indexOf(element(r, "a_bg")), "内側の影が背景より下");
            assertTrue(r.elements.indexOf(band) < r.elements.indexOf(element(r, "a_b_top")), "内側の影が枠線より上");
        }
        DisplayItem top = bands.stream().filter(b -> b.width() == 40).findFirst().orElseThrow();
        assertEquals(3, top.height());
    }

    @Test
    public void testLinearGradientAnglesAndMultipleStops() {
        Gradients.LinearGradient g = Gradients.parseLinear("linear-gradient(to right, rgb(255, 0, 0), rgb(0, 255, 0) 25%, rgb(0, 0, 255))");
        assertNotNull(g);
        assertEquals(90, g.angle(), 0.001);
        assertEquals(3, g.stops().size());
        List<Gradients.GradientStrip> strips = Gradients.strips(g, new BoxDecorations.Rect(0, 0, 100, 10), 320, 200, 16, 16);
        assertEquals(2, strips.size(), "縦横のグラデーションは停止点の位置で切る");
        assertEquals(25, strips.get(0).w(), 0.01f);
        assertEquals(RED, strips.get(0).topLeft());
        assertEquals(GREEN, strips.get(0).topRight());
        assertEquals(GREEN, strips.get(1).bottomLeft());
        assertEquals(0xFF0000FF, strips.get(1).bottomRight());

        // to top は下から上へ
        Gradients.LinearGradient up = Gradients.parseLinear("linear-gradient(0.5turn, rgb(255, 0, 0), rgb(0, 255, 0))");
        assertEquals(180, up.angle(), 0.001);
        Gradients.LinearGradient top = Gradients.parseLinear("linear-gradient(to top, rgb(255, 0, 0), rgb(0, 255, 0))");
        Gradients.GradientStrip t = Gradients.strips(top, new BoxDecorations.Rect(0, 0, 10, 10), 320, 200, 16, 16).getFirst();
        assertEquals(GREEN, t.topLeft());
        assertEquals(RED, t.bottomLeft());

        // 斜めの 2 色は 1 枚で正確（隅の色は線上の位置で決まる）。45deg の正方形は左下が始点、右上が終点
        Gradients.LinearGradient diag = Gradients.parseLinear("linear-gradient(45deg, rgb(255, 0, 0), rgb(0, 0, 255))");
        List<Gradients.GradientStrip> d = Gradients.strips(diag, new BoxDecorations.Rect(0, 0, 10, 10), 320, 200, 16, 16);
        assertEquals(1, d.size());
        assertEquals(RED, d.getFirst().bottomLeft());
        assertEquals(0xFF0000FF, d.getFirst().topRight());
        assertEquals(d.getFirst().topLeft(), d.getFirst().bottomRight(), "45deg の左上と右下は同じ位置");

        // 以前は 10deg が "0deg" を含むので上向きになっていた
        assertEquals(10, Gradients.parseLinear("linear-gradient(10deg, red, blue)").angle(), 0.001);
        // to top right は箱の縦横比で決まる
        Gradients.LinearGradient corner = Gradients.parseLinear("linear-gradient(to top right, red, blue)");
        assertEquals(Math.toDegrees(Math.atan2(10, 20)), corner.angleFor(20, 10), 0.001);
    }

    @Test
    public void testGradientBackgroundIsSplitIntoStripsInsideTheRoundedClip() {
        Rendered r = render("<div id=\"a\" style=\"width: 60px; height: 20px; border-radius: 8px; background-image: linear-gradient(to right, rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255));\"></div>");
        List<DisplayItem> strips = parts(r, "a_bgl0");
        assertEquals(2, strips.size());
        for (DisplayItem s : strips) {
            assertNotNull(s.meta().gradient());
            assertEquals(8, s.meta().clipRect().radiusTopLeft(), 0.01f, "帯が箱の角丸で切られない");
        }
        assertEquals(GREEN, strips.getFirst().meta().gradient().topRight());
    }

    // ---- W10

    @Test
    public void testCornerLonghandsReachTheDrawnBox() {
        Rendered r = render("<div id=\"a\" style=\"width: 40px; height: 20px; background-color: red; "
                + "border-radius: 8px; border-top-left-radius: 0; border-bottom-right-radius: 0.25em; font-size: 16px;\"></div>");
        DisplayItem bg = element(r, "a_bg");
        assertNotNull(bg);
        // 以前は描画が border-radius の文字列しか読まず、角のロングハンドを無視していた
        assertEquals(0, bg.meta().borderRadii().topLeft());
        assertEquals(8, bg.meta().borderRadii().topRight());
        assertEquals(4, bg.meta().borderRadii().bottomRight());
        assertEquals(8, bg.meta().borderRadii().bottomLeft());
    }

    @Test
    public void testTransparentTextColorIsNotWhitened() {
        Rendered r = render("<p style=\"color: transparent\">ghost</p><p style=\"color: #ff000080\">half</p>");
        DisplayItem ghost = r.elements.stream().filter(e -> e.type() == DisplayItem.ElementType.TEXT && e.text().contains("ghost"))
                .findFirst().orElseThrow();
        // 以前は 0x00000000 をオフホワイト（0xFFF5F5F0）に書き換えていた
        assertEquals(0x00000000, ghost.color());
        DisplayItem half = r.elements.stream().filter(e -> e.type() == DisplayItem.ElementType.TEXT && e.text().contains("half"))
                .findFirst().orElseThrow();
        assertEquals(0x80FF0000, half.color());
    }

    @Test
    public void testMinecraftPaletteIsAvailableAsVariables() {
        Rendered r = render("<p style=\"color: var(--mc-red)\">mc</p><p style=\"color: red\">css</p>");
        DisplayItem mc = r.elements.stream().filter(e -> e.type() == DisplayItem.ElementType.TEXT && e.text().contains("mc"))
                .findFirst().orElseThrow();
        DisplayItem css = r.elements.stream().filter(e -> e.type() == DisplayItem.ElementType.TEXT && e.text().contains("css"))
                .findFirst().orElseThrow();
        // 名前付き色は CSS の値、Minecraft の色は ua.css の --mc-* 変数
        assertEquals(0xFFFF5555, mc.color());
        assertEquals(RED, css.color());
    }

    // ==========================================
    // W11: インラインボックスの断片
    // ==========================================

    @Test
    public void testInlineBoxPaintsOneBackgroundPerLineAndSlicesItsBorders() {
        Rendered r = render("<div style=\"width: 60px;\"><span id=\"s\" style=\"border: 2px solid rgb(255, 0, 0); background-color: rgb(0, 255, 0); padding: 0 3px;\">alpha beta gamma delta</span></div>");
        List<LayoutBox.InlineFragment> frags = find(r.root, "s").inlineFragments;
        assertTrue(frags.size() > 1, "折り返しが起きていない");
        // 以前は語ごとに背景の部品があり、枠線は描かれなかった
        assertEquals(frags.size(), numbered(r, "s_frag_").size());
        assertEquals(frags.size(), numbered(r, "s_b_top_").size());
        assertEquals(frags.size(), numbered(r, "s_b_bottom_").size());
        // 左の枠線は最初の断片、右の枠線は最後の断片だけ
        List<DisplayItem> lefts = numbered(r, "s_b_left_");
        List<DisplayItem> rights = numbered(r, "s_b_right_");
        assertEquals(1, lefts.size());
        assertEquals(1, rights.size());
        assertEquals(Math.round(frags.getFirst().x), lefts.getFirst().x());
        assertEquals(Math.round(frags.getFirst().y), lefts.getFirst().y());
        LayoutBox.InlineFragment last = frags.getLast();
        assertEquals(Math.round(last.x + last.w), rights.getFirst().x() + rights.getFirst().width());
        assertEquals(RED, lefts.getFirst().bgColor());
        assertEquals(0xFF00FF00, numbered(r, "s_frag_").getFirst().bgColor());
    }

    @Test
    public void testInlineBoxRoundsOnlyTheOuterEndsOfItsFragments() {
        Rendered r = render("<div style=\"width: 60px;\"><span id=\"s\" style=\"border: 1px solid; border-radius: 4px;\">alpha beta gamma delta</span></div>");
        List<DisplayItem> rings = numbered(r, "s_b_round_");
        assertTrue(rings.size() > 1, "折り返しが起きていない");
        DisplayItem first = rings.getFirst(), last = rings.getLast();
        assertEquals(4, first.meta().borderRadii().topLeft());
        assertEquals(0, first.meta().borderRadii().topRight());
        assertEquals(0, first.meta().roundedBorder().widthRight(), 0.01f);
        assertEquals(1, first.meta().roundedBorder().widthLeft(), 0.01f);
        assertEquals(0, last.meta().borderRadii().bottomLeft());
        assertEquals(4, last.meta().borderRadii().bottomRight());
        assertEquals(0, last.meta().roundedBorder().widthLeft(), 0.01f);
    }

    @Test
    public void testInlineBlockIsPaintedAsABoxNotAsAFragment() {
        // 行に置いた不可分なインラインも断片を持つが、描画は箱として（枠線つきで）行う
        Rendered r = render("<div style=\"font-size: 0;\"><span id=\"ib\" style=\"display: inline-block; width: 20px; height: 10px; border: 1px solid;\"></span></div>");
        assertNotNull(element(r, "ib_b_top"), "inline-block の枠線が描かれていない");
        assertTrue(numbered(r, "ib_frag_").isEmpty());
    }

    // ==========================================

    /** {@code <prefix><番号>} の部品を描画順に。 */
    private static List<DisplayItem> numbered(Rendered r, String prefix) {
        return r.elements.stream().filter(e -> e.id().startsWith(prefix) && e.id().substring(prefix.length()).matches("\\d+")).toList();
    }

    private record Rendered(DisplayListBuilder renderer, LayoutBox root, List<DisplayItem> elements) {}

    private static Rendered render(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        UiDocumentCompiler.Document doc = parser.parseDocument(
                "<html><head><style>body { margin: 0; }</style></head><body>" + html + "</body></html>", "", VIEWPORT_W, VIEWPORT_H);
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        DisplayListBuilder renderer = new DisplayListBuilder(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, new ScrollState());
        return new Rendered(renderer, root, renderer.render(root));
    }

    private static DisplayItem element(Rendered r, String id) {
        return r.elements.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    /** 番号の付く部品（{@code <prefix>} と {@code <prefix>_<n>}）を描画順に。 */
    private static List<DisplayItem> parts(Rendered r, String prefix) {
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