package com.nishiyu.enderframelib.client;

import com.nishiyu.enderframelib.client.renderer.Canvas2DContext;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** {@code <canvas>} の描画面。補助関数は標準の Canvas 2D の上に載り、大きさは width / height 属性に従う。 */
class Canvas2DContextTest {

    private static int px(Canvas2DContext c, int x, int y) {
        return c.context2d().bitmap().getStraight(x, y);
    }

    @Test
    void defaultsTo300By150() {
        Canvas2DContext c = new Canvas2DContext();
        assertEquals(300, c.width());
        assertEquals(150, c.height());
        assertEquals(0, c.context2d().modCount());
    }

    @Test
    void helpersDrawIntoTheStandardBitmap() {
        Canvas2DContext c = new Canvas2DContext().resize(100, 100);
        c.fillRect(10, 10, 20, 20, 0xFFFF0000);
        assertEquals(0xFFFF0000, px(c, 15, 15));
        assertEquals(0, px(c, 35, 35));

        c.fillRoundedRect(50, 10, 30, 30, 15, 0xFF00FF00);
        assertEquals(0xFF00FF00, px(c, 65, 25));
        assertEquals(0, px(c, 50, 10));

        c.drawLine(0, 60, 100, 60, 4, 0xFF0000FF);
        assertEquals(0xFF0000FF, px(c, 50, 60));
        assertEquals(0, px(c, 50, 66));

        c.drawCircle(50, 80, 10, 0, 0xFFFFFF00);   // 塗りつぶし
        assertEquals(0xFFFFFF00, px(c, 50, 80));
        c.drawCircle(20, 80, 10, 2, 0xFF00FFFF);    // 輪
        assertEquals(0, px(c, 20, 80));
        assertEquals(0xFF00FFFF, px(c, 20, 71));
    }

    @Test
    void arrowSparklineAndBezierAreDrawn() {
        Canvas2DContext c = new Canvas2DContext().resize(100, 60);
        c.drawArrow(5, 30, 90, 30, 3, 10, 0xFF000000);
        assertEquals(0xFF000000, px(c, 40, 30));
        assertNotEquals(0, px(c, 84, 25));           // 矢じりの羽
        c.clear();
        assertEquals(0, px(c, 40, 30));

        c.drawSparkline(new float[]{0f, 1f, 0f, 1f}, 0, 0, 90, 50, 2, 0xFFFF0000, 0x400000FF);
        assertNotEquals(0, px(c, 15, 25));           // 折れ線か塗りのどちらか
        assertNotEquals(0, px(c, 15, 45));           // 塗り（下側）

        Canvas2DContext n = new Canvas2DContext().resize(100, 40);
        n.drawNodeLink(5, 5, 95, 35, 3, 0xFF00AA00);
        assertEquals(0xFF00AA00, px(n, 50, 20));     // S 字の中点
    }

    @Test
    void invalidArgumentsAreIgnored() {
        Canvas2DContext c = new Canvas2DContext().resize(20, 20);
        c.fillRect(Float.NaN, 0, 5, 5, 0xFF000000);
        c.fillRect(0, 0, -5, 5, 0xFF000000);
        c.drawLine(0, 0, Float.POSITIVE_INFINITY, 5, 2, 0xFF000000);
        c.drawText(null, 0, 0, 0xFF000000, 1);
        c.drawText("x", 0, 0, 0xFF000000, 0);
        c.drawCircle(5, 5, 0, 0, 0xFF000000);
        c.drawSparkline(new float[]{1f}, 0, 0, 10, 10, 1, 0xFF000000, 0);
        assertEquals(0, c.context2d().modCount() - 1, "resize の 1 回だけ");
    }

    @Test
    void adoptSizeFollowsAttributesAndClearsOnChange() {
        Canvas2DContext c = new Canvas2DContext();
        c.fillRect(2, 2, 4, 4, 0xFFFF0000);   // まだレイアウトから大きさを受ける前に描いた内容
        c.adoptSize(Map.of("canvas-width", "64", "canvas-height", "32"));
        assertEquals(64, c.width());
        assertEquals(32, c.height());
        assertEquals(0xFFFF0000, px(c, 3, 3), "最初の採用では内容を左上へ残す");
        c.adoptSize(Map.of("canvas-width", "64", "canvas-height", "32"));
        assertEquals(0xFFFF0000, px(c, 3, 3), "同じ大きさなら消えない");
        c.adoptSize(Map.of("canvas-width", "10", "canvas-height", "10"));
        assertEquals(10, c.width());
        assertEquals(0, px(c, 3, 3), "属性が変わると仕様どおり内容は消える");
        c.adoptSize(Map.of("canvas-width", "", "canvas-height", "oops"));
        assertEquals(300, c.width());
        assertEquals(150, c.height());
    }

    @Test
    void standardContextIsAvailableForArbitraryDrawing() {
        Canvas2DContext c = new Canvas2DContext().resize(30, 30);
        var g = c.context2d();
        var grad = g.createLinearGradient(0, 0, 30, 0);
        grad.addColorStop(0, 0xFF000000);
        grad.addColorStop(1, 0xFFFFFFFF);
        g.fillStyle(grad);
        g.fillRect(0, 0, 30, 30);
        assertTrue((px(c, 2, 5) & 0xFF) < (px(c, 27, 5) & 0xFF));
    }
}
