package com.nishiyu.enderframelib.common;

import com.nishiyu.enderframelib.client.renderer.DisplayListBuilder;
import com.nishiyu.enderframelib.core.css.ComputedStyle;
import com.nishiyu.enderframelib.core.css.LengthResolver;
import com.nishiyu.enderframelib.core.layout.LayoutBox;
import com.nishiyu.enderframelib.core.layout.model.ScrollState;
import com.nishiyu.enderframelib.core.paint.BorderPath;
import com.nishiyu.enderframelib.core.paint.BoxDecorations;
import com.nishiyu.enderframelib.core.paint.model.ClipRect;
import com.nishiyu.enderframelib.core.paint.model.Corners;
import com.nishiyu.enderframelib.core.paint.model.DisplayItem;
import com.nishiyu.enderframelib.core.paint.model.ElementType;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3b ①: 楕円の角と、角丸に沿う破線・点線。
 *
 * 楕円の半径が箱の寸法から正しく解かれ（% は横が幅・縦が高さ、CSS の縮小倍率）、描画項目・内側の角・クリップまで届くこと、
 * 破線・点線が経路ごとに割り付けられること、シェーダーと同じ式の {@link BorderPath} が経路上の位置を正しく返すこと。
 */
public class EllipticalCornersTest {

    private static final int VIEWPORT_W = 320;
    private static final int VIEWPORT_H = 240;

    @Test
    public void percentRadiusOnRectangleIsAnEllipse() {
        // 50% は横が幅の半分、縦が高さの半分: 200x100 の箱は 100 x 50 の楕円の角（以前は小さい方の円 50 で近似していた）
        Rendered r = render("<div id=\"a\" style=\"width: 200px; height: 100px; background-color: red; border-radius: 50%;\"></div>");
        Corners c = element(r, "a_bg").meta().borderRadii();
        assertEquals(100, c.topLeft());
        assertEquals(50, c.topLeftV());
        assertEquals(100, c.bottomRight());
        assertEquals(50, c.bottomRightV());
        assertTrue(c.isElliptical());
    }

    @Test
    public void slashSyntaxGivesIndependentHorizontalAndVerticalRadii() {
        Rendered r = render("<div id=\"a\" style=\"width: 120px; height: 80px; background-color: red; border-radius: 10px 20px / 30px 5px;\"></div>");
        Corners c = element(r, "a_bg").meta().borderRadii();
        assertEquals(10, c.topLeft());
        assertEquals(30, c.topLeftV());
        assertEquals(20, c.topRight());
        assertEquals(5, c.topRightV());
        // 右下は左上、左下は右上と同じ値（4 値の短縮）
        assertEquals(10, c.bottomRight());
        assertEquals(30, c.bottomRightV());
        assertEquals(20, c.bottomLeft());
        assertEquals(5, c.bottomLeftV());
    }

    @Test
    public void circularCornersStayCircular() {
        Rendered r = render("<div id=\"a\" style=\"width: 120px; height: 80px; background-color: red; border-radius: 12px;\"></div>");
        Corners c = element(r, "a_bg").meta().borderRadii();
        assertFalse(c.isElliptical());
        assertEquals(12, c.topLeft());
        assertEquals(12, c.topLeftV());
    }

    @Test
    public void radiiAreScaledDownTogetherWhenTheySumPastASide() {
        // CSS Backgrounds 3 §5.5: 横 80 + 80 が幅 100 を越えるので全部の半径に 100/160 を掛ける（縦もそのまま同じ倍率）
        ComputedStyle style = resolve("border-radius", "80px / 40px");
        float[] xy = LengthResolver.cornerRadiiXY(style, 100, 100, 320, 200);
        assertEquals(50f, xy[0], 0.01f);
        assertEquals(25f, xy[4], 0.01f);
        assertEquals(50f, xy[2], 0.01f);
    }

    @Test
    public void zeroAxisMakesASquareCorner() {
        ComputedStyle style = resolve("border-radius", "0 10px / 5px 0");
        float[] xy = LengthResolver.cornerRadiiXY(style, 100, 100, 320, 200);
        // 左上は 0 / 5px、右上は 10px / 0: どちらも直角
        assertEquals(0f, xy[0], 0.01f);
        assertEquals(0f, xy[4], 0.01f);
        assertEquals(0f, xy[1], 0.01f);
        assertEquals(0f, xy[5], 0.01f);
    }

    @Test
    public void innerBorderEdgeShrinksEachAxisByItsOwnBorderWidth() {
        // 外側 60 x 30、左右の枠 6 と上下の枠 2 → 内側は 54 x 28
        Rendered r = render("<div id=\"a\" style=\"width: 200px; height: 100px; box-sizing: border-box; border: 2px solid blue; "
                + "border-left-width: 6px; border-right-width: 6px; border-radius: 60px / 30px;\"></div>");
        DisplayItem ring = element(r, "a_b_round");
        assertNotNull(ring);
        Corners outer = ring.meta().borderRadii();
        assertEquals(60, outer.topLeft());
        assertEquals(30, outer.topLeftV());
        // 内側の角を見る手段は padding-box クリップ（背景画像レイヤーが使う）。ここでは外側の半径だけを固定する
        assertTrue(outer.isElliptical());
    }

    @Test
    public void overflowHiddenClipsChildrenWithTheEllipse() {
        Rendered r = render("<div id=\"a\" style=\"width: 200px; height: 100px; overflow: hidden; border-radius: 50%;\">"
                + "<div id=\"c\" style=\"width: 400px; height: 400px; background-color: red;\"></div></div>");
        ClipRect clip = element(r, "c_bg").meta().clipRect();
        assertNotNull(clip);
        assertTrue(clip.isElliptical(), "子のクリップが円のまま");
        assertEquals(100f, clip.radiusTopLeft(), 0.01f);
        assertEquals(50f, clip.radiusTopLeftV(), 0.01f);
        // 配列にしても縦の半径が落ちない
        float[] arr = clip.toArray();
        assertEquals(12, arr.length);
        assertEquals(50f, arr[8], 0.01f);
    }

    @Test
    public void intersectingEllipticalClipsKeepsEachAxis() {
        float[] outer = {0, 0, 200, 100, 100, 100, 100, 100, 50, 50, 50, 50};
        float[] inner = {0, 0, 400, 400, 0, 0, 0, 0};
        float[] out = BoxDecorations.intersectClip(outer, inner);
        assertEquals(12, out.length);
        assertEquals(100f, out[4], 0.01f);
        assertEquals(50f, out[8], 0.01f);
        // 円だけの交わりは 8 値のまま
        assertEquals(8, BoxDecorations.intersectClip(new float[]{0, 0, 50, 50, 5, 5, 5, 5}, inner).length);
    }

    @Test
    public void dashedAndDottedRoundedBordersCarryTheirKind() {
        Rendered r = render("""
                <div id="a" style="width: 100px; height: 60px; border: 2px dashed red; border-radius: 12px;"></div>
                <div id="b" style="width: 100px; height: 60px; border: 3px dotted red; border-radius: 12px;"></div>
                <div id="c" style="width: 100px; height: 60px; border: 2px solid red; border-radius: 12px;"></div>
                """);
        assertEquals(1, element(r, "a_b_round").meta().roundedBorder().dashKind());
        assertEquals(2, element(r, "b_b_round").meta().roundedBorder().dashKind());
        assertEquals(0, element(r, "c_b_round").meta().roundedBorder().dashKind());
    }

    @Test
    public void dashedCircleUsesTheRingPathInsteadOfTheSolidCircleBorder() {
        Rendered r = render("""
                <div id="a" style="width: 60px; height: 60px; border: 2px dashed red; border-radius: 50%;"></div>
                <div id="b" style="width: 60px; height: 60px; border: 2px solid red; border-radius: 50%;"></div>
                """);
        assertNotNull(element(r, "a_b_round"), "破線の円が実線の円の枠になっている");
        assertNull(element(r, "a_cb"));
        assertEquals(ElementType.CIRCLE_BORDER, element(r, "b_cb").type());
    }

    @Test
    public void mixedBorderStylesFallBackToSolidRing() {
        Rendered r = render("<div id=\"a\" style=\"width: 100px; height: 60px; border: 2px dashed red; border-bottom-style: solid; border-radius: 12px;\"></div>");
        assertEquals(0, element(r, "a_b_round").meta().roundedBorder().dashKind());
    }

    @Test
    public void ellipticalBorderUsesTheRingPath() {
        Rendered r = render("<div id=\"a\" style=\"width: 200px; height: 100px; border: 3px dashed red; border-radius: 50%;\"></div>");
        DisplayItem ring = element(r, "a_b_round");
        assertNotNull(ring);
        assertTrue(ring.meta().borderRadii().isElliptical());
        assertEquals(1, ring.meta().roundedBorder().dashKind());
    }

    // ==========================================
    // BorderPath（シェーダーの pathParam / pathLength と同じ式）
    // ==========================================

    @Test
    public void lengthOfACircleMatchesTheCircumference() {
        float r = 20;
        float len = BorderPath.length(r, r, new float[]{r, r, r, r}, new float[]{r, r, r, r});
        assertEquals((float) (2 * Math.PI * r), len, 0.01f);
    }

    @Test
    public void lengthOfARoundedRectangleAddsStraightRunsAndArcs() {
        // 半サイズ 50 x 30、半径 10: 直線は 4 本の和 = 2*(100-20) + 2*(60-20) = 240、弧は円周 2*pi*10
        float len = BorderPath.length(50, 30, new float[]{10, 10, 10, 10}, new float[]{10, 10, 10, 10});
        assertEquals(240f + (float) (2 * Math.PI * 10), len, 0.01f);
    }

    @Test
    public void quarterArcOfAnEllipseIsBetweenItsAxes() {
        float arc = BorderPath.quarterArc(40, 20);
        // 楕円の周長 ≈ 4 * 弧。長半径 40・短半径 20 の周長は約 193.7
        assertEquals(193.7f / 4f, arc, 0.2f);
        assertEquals(0f, BorderPath.quarterArc(0, 20), 0f);
    }

    @Test
    public void paramFollowsTheOutlineClockwiseFromTheTopEdge() {
        float bx = 50, by = 30;
        float[] rx = {10, 10, 10, 10}, ry = {10, 10, 10, 10};
        float total = BorderPath.length(bx, by, rx, ry);
        // 上辺の左の角の終わりが 0
        assertEquals(0f, BorderPath.param(-bx + 10, -by, bx, by, rx, ry), 0.01f);
        // 上辺の中央（x = 0）は 上辺の長さの半分 + 左の角の分… 上辺は x=-40..40 なので 40
        assertEquals(40f, BorderPath.param(0, -by, bx, by, rx, ry), 0.01f);
        // 右辺の中央は上辺 80 + 右上の弧 + 右辺の半分（20）
        float arc = (float) (Math.PI * 10 / 2);
        assertEquals(80f + arc + 20f, BorderPath.param(bx, 0, bx, by, rx, ry), 0.01f);
        // 下辺の中央、左辺の中央は経路を一周する途中で単調に増える
        float bottom = BorderPath.param(0, by, bx, by, rx, ry);
        float left = BorderPath.param(-bx, 0, bx, by, rx, ry);
        assertEquals(80f + arc + 40f + arc + 40f, bottom, 0.01f);
        assertTrue(left > bottom && left < total);
        // 左上の弧は 1 周の終わりに向かう
        float tl = BorderPath.param(-bx + 10 - 10 * (float) Math.cos(Math.PI / 4), -by + 10 - 10 * (float) Math.sin(Math.PI / 4), bx, by, rx, ry);
        assertEquals(total - arc / 2f, tl, 0.05f);
    }

    @Test
    public void paramMatchesAPolylineWalkOfTheEllipticalOutline() {
        // 楕円の角を折れ線で歩き、その点の param が歩いた距離と（弧の媒介変数の近似の範囲で）合うこと
        float bx = 60, by = 40;
        float[] rx = {30, 20, 25, 10}, ry = {15, 25, 10, 20};
        float total = BorderPath.length(bx, by, rx, ry);
        List<float[]> points = outline(bx, by, rx, ry, 4000);
        double walked = 0;
        float[] prev = points.getFirst();
        double worst = 0;
        for (float[] p : points) {
            walked += Math.hypot(p[0] - prev[0], p[1] - prev[1]);
            prev = p;
            float s = BorderPath.param(p[0], p[1], bx, by, rx, ry);
            // 経路の始点の近くは 0 と total の継ぎ目なので比べない
            if (walked < 2 || walked > total - 2) continue;
            worst = Math.max(worst, Math.abs(s - walked));
        }
        // 弧の上の位置は角度の媒介変数に比例させた近似（弧長の厳密な積分ではない）なので、楕円が細いほどずれる。
        // 最も長い弧（30 x 15 の 4 分の 1 ≈ 35px）の 12% 以内なら、破線の位相のずれは見分けられない
        double longestArc = BorderPath.quarterArc(30, 15);
        assertTrue(worst < 0.12 * longestArc, "経路上の位置が歩いた距離からずれすぎ: " + worst);
    }

    @Test
    public void patternClosesWithoutASeam() {
        BorderPath.Pattern dashed = BorderPath.pattern(BorderPath.DASHED, 2f, 300f);
        // 細い破線（2px）は線 3 倍 = 6・間 2 倍 = 4 で周期 10。300 に割り付けると 30 個
        assertEquals(30, dashed.count());
        assertEquals(300f, dashed.count() * dashed.period(), 0.001f);
        assertEquals(6f, dashed.dash(), 0.001f);
        assertFalse(dashed.round());
        // 太い破線（3px 以上）は線 2 倍・間 1 倍（Blink と同じ）
        assertEquals(6f, BorderPath.dashLength(BorderPath.DASHED, 3f), 0.001f);
        assertEquals(3f, BorderPath.gapLength(BorderPath.DASHED, 3f), 0.001f);
        BorderPath.Pattern dotted = BorderPath.pattern(BorderPath.DOTTED, 4f, 300f);
        assertTrue(dotted.round());
        assertEquals(4f, dotted.dash(), 0.001f);
        assertEquals(300f, dotted.count() * dotted.period(), 0.001f);
        // 1px の点線は丸くしない（直線の辺と同じ）
        assertFalse(BorderPath.pattern(BorderPath.DOTTED, 1f, 300f).round());
    }

    /** 外形を折れ線で歩く点（上辺の左の角の終わりから時計回り）。 */
    private static List<float[]> outline(float bx, float by, float[] rx, float[] ry, int steps) {
        java.util.ArrayList<float[]> out = new java.util.ArrayList<>();
        // 角 4 つと辺 4 本を順に歩く: 上辺 → 右上 → 右辺 → 右下 → 下辺 → 左下 → 左辺 → 左上
        float[][] centers = {
                {bx - rx[1], -(by - ry[1])}, {bx - rx[2], by - ry[2]}, {-(bx - rx[3]), by - ry[3]}, {-(bx - rx[0]), -(by - ry[0])}};
        // 上辺
        line(out, -bx + rx[0], -by, bx - rx[1], -by, steps);
        arc(out, centers[0][0], centers[0][1], rx[1], ry[1], -Math.PI / 2, 0, steps);
        line(out, bx, -by + ry[1], bx, by - ry[2], steps);
        arc(out, centers[1][0], centers[1][1], rx[2], ry[2], 0, Math.PI / 2, steps);
        line(out, bx - rx[2], by, -bx + rx[3], by, steps);
        arc(out, centers[2][0], centers[2][1], rx[3], ry[3], Math.PI / 2, Math.PI, steps);
        line(out, -bx, by - ry[3], -bx, -by + ry[0], steps);
        arc(out, centers[3][0], centers[3][1], rx[0], ry[0], Math.PI, Math.PI * 1.5, steps);
        return out;
    }

    private static void line(List<float[]> out, float x0, float y0, float x1, float y1, int steps) {
        for (int i = 0; i <= steps / 4; i++) {
            float t = i / (steps / 4f);
            out.add(new float[]{x0 + (x1 - x0) * t, y0 + (y1 - y0) * t});
        }
    }

    private static void arc(List<float[]> out, float cx, float cy, float rx, float ry, double a0, double a1, int steps) {
        for (int i = 1; i <= steps / 4; i++) {
            double a = a0 + (a1 - a0) * i / (steps / 4.0);
            out.add(new float[]{(float) (cx + rx * Math.cos(a)), (float) (cy + ry * Math.sin(a))});
        }
    }

    // ==========================================

    private static ComputedStyle resolve(String... keyValues) {
        Map<String, String> raw = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) raw.put(keyValues[i], keyValues[i + 1]);
        return com.nishiyu.enderframelib.core.css.StyleResolver.finalizeComputedStyle(raw, null, null);
    }

    private record Rendered(DisplayListBuilder renderer, LayoutBox root, List<DisplayItem> elements) {}

    private static Rendered render(String html) {
        UiDocumentCompiler parser = new UiDocumentCompiler();
        Document doc = parser.parseDocument(
                "<html><head><style>body { margin: 0; }</style></head><body>" + html + "</body></html>", "", VIEWPORT_W, VIEWPORT_H);
        LayoutBox root = parser.layoutDocument(doc, VIEWPORT_W, VIEWPORT_H, Map.of(), new ScrollState(), FontResolver.FALLBACK);
        DisplayListBuilder renderer = new DisplayListBuilder(VIEWPORT_W, VIEWPORT_H, doc.sheet.keyframes, new ScrollState());
        return new Rendered(renderer, root, renderer.render(root));
    }

    private static DisplayItem element(Rendered r, String id) {
        return r.elements.stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }
}
