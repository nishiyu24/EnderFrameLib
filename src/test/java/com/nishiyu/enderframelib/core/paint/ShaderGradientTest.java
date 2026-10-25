package com.nishiyu.enderframelib.core.paint;

import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.paint.BoxDecorations.Rect;
import com.nishiyu.enderframelib.core.paint.Gradients.ConicGradient;
import com.nishiyu.enderframelib.core.paint.Gradients.Gradient;
import com.nishiyu.enderframelib.core.paint.Gradients.LinearGradient;
import com.nishiyu.enderframelib.core.paint.Gradients.RadialGradient;
import com.nishiyu.enderframelib.core.paint.Gradients.ResolvedStops;
import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * グラデーションをシェーダーの区間にする {@link Gradients#shaderGradients}。
 * フラグメントシェーダーの計算を CPU で再現（{@link ShaderGradientEmulator}）して、
 * 停止点を直接引く基準の実装（{@code colorAt}）と画素ごとに比べる。
 */
public class ShaderGradientTest {
    private static final Rect TILE = new Rect(10, 20, 120, 60);
    private static final float W = 800, H = 600, FONT = 16;

    private static void assertColorClose(int expected, int actual, int tolerance, String message) {
        for (int shift = 0; shift <= 24; shift += 8) {
            int e = (expected >>> shift) & 0xFF, a = (actual >>> shift) & 0xFF;
            assertTrue(Math.abs(e - a) <= tolerance, message + String.format(" 期待 #%08X 実際 #%08X", expected, actual));
        }
    }

    /** 基準の位置 u（停止点の尺度）を返す関数と、同じ停止点・混合器で、全画素を比べる。 */
    private static void compare(String css, Gradient g, BiFunction<Float, Float, Double> position, ResolvedStops stops,
                                boolean repeating, ColorParser.ColorMixer mixer, int tolerance) {
        List<ShaderGradient> segments = Gradients.shaderGradients(g, TILE, W, H, FONT, FONT);
        assertFalse(segments.isEmpty(), css + " はシェーダーで描ける");
        int checked = 0;
        for (float y = TILE.y() + 0.37f; y < TILE.y() + TILE.h(); y += 3.1f) {
            for (float x = TILE.x() + 0.41f; x < TILE.x() + TILE.w(); x += 2.9f) {
                float u = position.apply(x, y).floatValue();
                if (nearBoundary(stops, u, repeating)) continue;
                int expected = Gradients.colorAt(stops, u, repeating, mixer);
                int actual = ShaderGradientEmulator.colorOf(segments, x, y);
                assertColorClose(expected, actual, tolerance, css + " (" + x + ", " + y + ") u=" + u);
                checked++;
            }
        }
        assertTrue(checked > 150, "十分な数の画素を比べた: " + checked);
    }

    /** 幅 0 の停止点（色の切り替わり）や周期の継ぎ目の近くは、どちらの色でも正しい。 */
    private static boolean nearBoundary(ResolvedStops stops, float u, boolean repeating) {
        float[] p = stops.positions();
        float period = p[p.length - 1] - p[0];
        float v = u;
        if (repeating && period > 1e-6f) {
            float d = u - p[0];
            v = p[0] + (d - period * (float) Math.floor(d / period));
            if (Math.abs(v - p[0]) < 0.01f || Math.abs(v - p[p.length - 1]) < 0.01f) return true;
        }
        for (int i = 0; i + 1 < p.length; i++) {
            if (p[i + 1] - p[i] <= 1e-6f && Math.abs(v - p[i]) < 0.01f) return true;
        }
        return false;
    }

    private static void compareLinear(String css) {
        LinearGradient g = Gradients.parseLinear(css);
        assertNotNull(g, css);
        double rad = Math.toRadians(g.angleFor(TILE.w(), TILE.h()));
        double sin = Math.sin(rad), cos = Math.cos(rad);
        double length = Math.abs(TILE.w() * sin) + Math.abs(TILE.h() * cos);
        ResolvedStops stops = Gradients.resolveStops(g.stops(), (float) length, false, W, H, FONT, FONT);
        double cx = TILE.x() + TILE.w() / 2.0, cy = TILE.y() + TILE.h() / 2.0;
        ColorParser.ColorMixer mixer = g.colorSpace() == null ? null : ColorParser.mixer(g.colorSpace());
        compare(css, g, (x, y) -> ((x - cx) * sin - (y - cy) * cos) / length + 0.5, stops, g.repeating(), mixer,
                mixer == null ? 2 : 24);
    }

    @Test
    @DisplayName("斜めの 3 色は、画素ごとに停止点を直接引いた色と一致する")
    void diagonalThreeStops() {
        compareLinear("linear-gradient(135deg, rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255))");
    }

    @Test
    @DisplayName("位置つきの 4 色・手前と先は端の色が伸びる")
    void positionedStopsExtendTheEnds() {
        compareLinear("linear-gradient(60deg, rgb(255, 0, 0) 20%, rgb(0, 255, 0) 45%, rgb(0, 0, 255) 50%, rgb(255, 255, 0) 80%)");
    }

    @Test
    @DisplayName("同じ位置の停止点は色が急に切り替わる（区間は描かず、両側の色を伸ばす）")
    void hardStops() {
        compareLinear("linear-gradient(100deg, rgb(255, 0, 0) 30%, rgb(255, 0, 0) 30%, rgb(0, 0, 255) 30%, rgb(0, 255, 0) 90%)");
        compareLinear("linear-gradient(to bottom, rgb(255, 0, 0) 0%, rgb(255, 0, 0) 50%, rgb(0, 0, 255) 50%, rgb(0, 0, 255) 100%)");
        // 区間がすべて幅 0 でも、境目の両側を単色で塗る
        compareLinear("linear-gradient(to right, rgb(255, 0, 0) 40%, rgb(0, 0, 255) 40%)");
    }

    @Test
    @DisplayName("透明を含む色は乗算済みアルファで補間する")
    void transparentStops() {
        compareLinear("linear-gradient(20deg, rgba(255, 0, 0, 0), rgb(0, 0, 255), rgba(0, 255, 0, 0.3))");
    }

    @Test
    @DisplayName("繰り返しは周期に畳む（手前にも先にも続く）")
    void repeatingLinear() {
        compareLinear("repeating-linear-gradient(to right, rgb(255, 0, 0), rgb(0, 255, 0) 20px)");
        compareLinear("repeating-linear-gradient(35deg, rgb(255, 0, 0) 10px, rgb(0, 0, 255) 25px, rgb(255, 255, 0) 40px)");
        compareLinear("repeating-linear-gradient(to right, rgb(255, 0, 0) 20px, rgb(0, 255, 0) 60px)");
    }

    @Test
    @DisplayName("補間のヒントは区間の中間点を動かす")
    void interpolationHints() {
        compareLinear("linear-gradient(to right, rgb(255, 0, 0), 25%, rgb(0, 0, 255))");
        compareLinear("linear-gradient(80deg, rgb(255, 0, 0), rgb(0, 255, 0) 40%, 70%, rgb(0, 0, 255))");
    }

    @Test
    @DisplayName("補間空間の指定は、刻んだ区間を sRGB でつなぐ近似で、基準の色空間の補間に近い")
    void colorSpaceInterpolation() {
        compareLinear("linear-gradient(to right in oklab, rgb(255, 0, 0), rgb(0, 0, 255))");
        compareLinear("linear-gradient(120deg in oklch, rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255))");
    }

    @Test
    @DisplayName("放射の 3 色と繰り返し")
    void radial() {
        for (String css : List.of(
                "radial-gradient(circle at 30% 40%, rgb(255, 0, 0), rgb(0, 255, 0) 50%, rgb(0, 0, 255))",
                "radial-gradient(ellipse farthest-side, rgb(255, 0, 0), rgb(0, 0, 255))",
                "repeating-radial-gradient(circle at 50% 50%, rgb(255, 0, 0), rgb(0, 255, 0) 15px)")) {
            Gradient parsed = Gradients.parse(css);
            RadialGradient g = (RadialGradient) parsed;
            float[] center = Gradients.resolveCenter(g.center(), TILE, W, H, FONT, FONT);
            float[] radii = Gradients.radialRadii(g, TILE, center[0], center[1], W, H, FONT, FONT);
            float rx = Math.max(radii[0], 1e-3f), ry = Math.max(radii[1], 1e-3f);
            ResolvedStops stops = Gradients.resolveStops(g.stops(), rx, false, W, H, FONT, FONT);
            compare(css, g, (x, y) -> Math.hypot((x - center[0]) / rx, (y - center[1]) / ry), stops, g.repeating(), null, 2);
        }
    }

    @Test
    @DisplayName("円錐の 3 色・開始角・繰り返し")
    void conic() {
        for (String css : List.of(
                "conic-gradient(rgb(255, 0, 0), rgb(0, 255, 0), rgb(0, 0, 255))",
                "conic-gradient(from 45deg at 30% 60%, rgb(255, 0, 0) 20%, rgb(0, 0, 255) 70%)",
                "repeating-conic-gradient(rgb(255, 0, 0) 0deg, rgb(0, 255, 0) 30deg)")) {
            ConicGradient g = (ConicGradient) Gradients.parse(css);
            float[] center = Gradients.resolveCenter(g.center(), TILE, W, H, FONT, FONT);
            ResolvedStops stops = Gradients.resolveStops(g.stops(), 1, true, W, H, FONT, FONT);
            double from = g.from();
            compare(css, g, (x, y) -> {
                double deg = Math.toDegrees(Math.atan2(x - center[0], -(y - center[1])));
                return (((deg - from) % 360) + 360) % 360 / 360.0;
            }, stops, g.repeating(), null, 2);
        }
    }

    @Test
    @DisplayName("2 色は区間 1 つで、両側とも端の色が伸びる")
    void twoStopsAreASingleOpenSegment() {
        List<ShaderGradient> segments = Gradients.shaderGradients(
                Gradients.parseLinear("linear-gradient(to right, red 25%, blue 75%)"), TILE, W, H, FONT, FONT);
        assertEquals(1, segments.size());
        assertEquals(0, segments.getFirst().bounds(), "範囲の印なし");
        assertEquals(0f, segments.getFirst().repeatPeriod());
    }

    @Test
    @DisplayName("大きさ 0 のタイルは何も描かない")
    void emptyTile() {
        assertTrue(Gradients.shaderGradients(Gradients.parseLinear("linear-gradient(red, blue)"),
                new Rect(0, 0, 0, 10), W, H, FONT, FONT).isEmpty());
    }
}
