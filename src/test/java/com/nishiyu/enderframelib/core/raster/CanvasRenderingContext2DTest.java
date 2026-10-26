package com.nishiyu.enderframelib.core.raster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** {@link CanvasRenderingContext2D} のピクセル検証（Web の Canvas 2D の意味に沿った期待値）。 */
class CanvasRenderingContext2DTest {

    private static int px(CanvasRenderingContext2D c, int x, int y) {
        return c.bitmap().getStraight(x, y);
    }

    private static void assertArgb(int expected, int actual, int tol) {
        for (int shift = 0; shift <= 24; shift += 8) {
            int e = (expected >>> shift) & 0xFF;
            int a = (actual >>> shift) & 0xFF;
            assertTrue(Math.abs(e - a) <= tol,
                    String.format("expected %08x but was %08x (channel shift %d)", expected, actual, shift));
        }
    }

    @Test
    void fillRectPaintsExactPixels() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(20, 20);
        c.fillStyle("#ff0000");
        c.fillRect(5, 5, 10, 10);
        assertEquals(0xFFFF0000, px(c, 5, 5));
        assertEquals(0xFFFF0000, px(c, 14, 14));
        assertEquals(0, px(c, 4, 5));
        assertEquals(0, px(c, 15, 15));
    }

    @Test
    void defaultsMatchSpecification() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(4, 4);
        assertEquals(1, c.lineWidth());
        assertEquals("butt", c.lineCap());
        assertEquals("miter", c.lineJoin());
        assertEquals(10, c.miterLimit());
        assertEquals(1, c.globalAlpha());
        assertEquals("source-over", c.globalCompositeOperation());
        assertEquals("10px sans-serif", c.font());
        assertTrue(c.imageSmoothingEnabled());
        assertArrayEquals(new double[0], c.getLineDash());
    }

    @Test
    void invalidValuesAreIgnored() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(4, 4);
        c.lineWidth(-3);
        c.lineWidth(Double.NaN);
        assertEquals(1, c.lineWidth());
        c.globalAlpha(2);
        assertEquals(1, c.globalAlpha());
        c.globalCompositeOperation("bogus");
        assertEquals("source-over", c.globalCompositeOperation());
        c.fillStyle("not-a-color");
        c.fillRect(0, 0, 4, 4);
        assertEquals(0xFF000000, px(c, 1, 1));   // 既定の黒のまま
        c.setLineDash(2, -1);
        assertEquals(0, c.getLineDash().length);
        c.setLineDash(3, 1, 2);
        assertArrayEquals(new double[]{3, 1, 2, 3, 1, 2}, c.getLineDash());
    }

    @Test
    void globalAlphaAndSourceOver() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(4, 4);
        c.fillStyle("#0000ff");
        c.fillRect(0, 0, 4, 4);
        c.globalAlpha(0.5);
        c.fillStyle("#ff0000");
        c.fillRect(0, 0, 2, 2);
        assertArgb(0xFF800080, px(c, 0, 0), 1);
        assertEquals(0xFF0000FF, px(c, 3, 3));
        // 半透明の色を空の面に: 非乗算で読むと元の色に戻る
        CanvasRenderingContext2D d = new CanvasRenderingContext2D(2, 2);
        d.fillStyle("rgba(255, 0, 0, 0.5)");
        d.fillRect(0, 0, 2, 2);
        assertArgb(0x80FF0000, px(d, 0, 0), 1);
    }

    @Test
    void compositeOperations() {
        // 下: 不透明の青の左半分 (0..4)。上: 赤を全面（4x4）。
        java.util.function.Function<String, CanvasRenderingContext2D> run = op -> {
            CanvasRenderingContext2D c = new CanvasRenderingContext2D(8, 4);
            c.fillStyle("#0000ff");
            c.fillRect(0, 0, 4, 4);
            c.globalCompositeOperation(op);
            c.fillStyle("#ff0000");
            c.fillRect(2, 0, 4, 4);   // 重なり x=2..3、赤だけ x=4..5、青だけ x=0..1
            return c;
        };
        CanvasRenderingContext2D over = run.apply("source-over");
        assertEquals(0xFFFF0000, px(over, 3, 1));
        assertEquals(0xFF0000FF, px(over, 0, 1));
        CanvasRenderingContext2D in = run.apply("source-in");
        assertEquals(0xFFFF0000, px(in, 3, 1));
        assertEquals(0, px(in, 5, 1));   // 下が無い所は消える
        assertEquals(0, px(in, 0, 1));   // 図形の外も消える（unbounded）
        CanvasRenderingContext2D out = run.apply("source-out");
        assertEquals(0xFFFF0000, px(out, 5, 1));
        assertEquals(0, px(out, 3, 1));
        CanvasRenderingContext2D atop = run.apply("source-atop");
        assertEquals(0xFFFF0000, px(atop, 3, 1));
        assertEquals(0, px(atop, 5, 1));
        assertEquals(0xFF0000FF, px(atop, 0, 1));
        CanvasRenderingContext2D dover = run.apply("destination-over");
        assertEquals(0xFF0000FF, px(dover, 3, 1));
        assertEquals(0xFFFF0000, px(dover, 5, 1));
        CanvasRenderingContext2D din = run.apply("destination-in");
        assertEquals(0xFF0000FF, px(din, 3, 1));
        assertEquals(0, px(din, 0, 1));
        CanvasRenderingContext2D dout = run.apply("destination-out");
        assertEquals(0, px(dout, 3, 1));
        assertEquals(0xFF0000FF, px(dout, 0, 1));
        CanvasRenderingContext2D datop = run.apply("destination-atop");
        assertEquals(0xFF0000FF, px(datop, 3, 1));
        assertEquals(0xFFFF0000, px(datop, 5, 1));
        assertEquals(0, px(datop, 0, 1));
        CanvasRenderingContext2D lighter = run.apply("lighter");
        assertEquals(0xFFFF00FF, px(lighter, 3, 1));
        CanvasRenderingContext2D copy = run.apply("copy");
        assertEquals(0xFFFF0000, px(copy, 3, 1));
        assertEquals(0, px(copy, 0, 1));
        CanvasRenderingContext2D xor = run.apply("xor");
        assertEquals(0, px(xor, 3, 1));
        assertEquals(0xFF0000FF, px(xor, 0, 1));
        assertEquals(0xFFFF0000, px(xor, 5, 1));
        CanvasRenderingContext2D multiply = run.apply("multiply");
        assertEquals(0xFF000000, px(multiply, 3, 1));   // 赤 × 青 = 黒
        CanvasRenderingContext2D screen = run.apply("screen");
        assertEquals(0xFFFF00FF, px(screen, 3, 1));
        CanvasRenderingContext2D difference = run.apply("difference");
        assertEquals(0xFFFF00FF, px(difference, 3, 1));
        assertEquals(0xFFFF0000, px(difference, 5, 1));   // 下が透明なら上の色のまま
    }

    @Test
    void linearGradientInterpolates() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(100, 2);
        CanvasGradient g = c.createLinearGradient(0, 0, 100, 0);
        g.addColorStop(0, 0xFF000000);
        g.addColorStop(1, 0xFFFFFFFF);
        c.fillStyle(g);
        c.fillRect(0, 0, 100, 2);
        assertArgb(0xFF7F7F7F, px(c, 49, 0), 4);
        assertArgb(0xFF000000, px(c, 0, 0), 4);
        assertArgb(0xFFFFFFFF, px(c, 99, 0), 4);
    }

    @Test
    void gradientStopsPadOutsideRange() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(100, 1);
        CanvasGradient g = c.createLinearGradient(25, 0, 75, 0);
        g.addColorStop(0, 0xFFFF0000);
        g.addColorStop(1, 0xFF0000FF);
        c.fillStyle(g);
        c.fillRect(0, 0, 100, 1);
        assertEquals(0xFFFF0000, px(c, 5, 0));
        assertEquals(0xFF0000FF, px(c, 95, 0));
        assertThrows(IllegalArgumentException.class, () -> g.addColorStop(1.5, 0));
    }

    @Test
    void gradientRespectsTransformAtFillTime() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(100, 1);
        CanvasGradient g = c.createLinearGradient(0, 0, 50, 0);
        g.addColorStop(0, 0xFF000000);
        g.addColorStop(1, 0xFFFFFFFF);
        c.scale(2, 1);
        c.fillStyle(g);
        c.fillRect(0, 0, 50, 1);   // デバイスでは 0..100 に広がる
        assertArgb(0xFF7F7F7F, px(c, 49, 0), 6);
    }

    @Test
    void radialGradientCenterAndEdge() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(40, 40);
        CanvasGradient g = c.createRadialGradient(20, 20, 0, 20, 20, 20);
        g.addColorStop(0, 0xFFFFFFFF);
        g.addColorStop(1, 0xFF000000);
        c.fillStyle(g);
        c.fillRect(0, 0, 40, 40);
        assertArgb(0xFFFFFFFF, px(c, 20, 20), 12);
        assertArgb(0xFF000000, px(c, 39, 20), 12);
        int mid = px(c, 30, 20) & 0xFF;
        assertTrue(mid > 100 && mid < 156, "mid=" + mid);
    }

    @Test
    void conicGradientSweepsAngle() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(40, 40);
        CanvasGradient g = c.createConicGradient(0, 20, 20);
        g.addColorStop(0, 0xFFFF0000);
        g.addColorStop(0.5, 0xFF00FF00);
        g.addColorStop(1, 0xFFFF0000);
        c.fillStyle(g);
        c.fillRect(0, 0, 40, 40);
        // 角 0 は +x 方向（赤）、角 π は -x 方向（緑）
        assertArgb(0xFFFF0000, px(c, 38, 20), 16);
        assertArgb(0xFF00FF00, px(c, 1, 20), 16);
    }

    @Test
    void patternRepeats() {
        Pixmap tile = new Pixmap(2, 2);
        tile.setStraight(0, 0, 0xFFFF0000);
        tile.setStraight(1, 0, 0xFF00FF00);
        tile.setStraight(0, 1, 0xFF0000FF);
        tile.setStraight(1, 1, 0xFFFFFFFF);
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(8, 8);
        c.imageSmoothingEnabled(false);
        CanvasPattern p = c.createPattern(tile, "repeat");
        c.fillStyle(p);
        c.fillRect(0, 0, 8, 8);
        assertEquals(0xFFFF0000, px(c, 0, 0));
        assertEquals(0xFFFF0000, px(c, 4, 4));
        assertEquals(0xFF00FF00, px(c, 5, 4));
        assertEquals(0xFFFFFFFF, px(c, 7, 7));
        CanvasRenderingContext2D n = new CanvasRenderingContext2D(8, 8);
        n.fillStyle(n.createPattern(tile, "no-repeat"));
        n.fillRect(0, 0, 8, 8);
        assertEquals(0, px(n, 5, 5));
        assertThrows(IllegalArgumentException.class, () -> c.createPattern(tile, "bogus"));
    }

    @Test
    void drawImageScalesAndFlips() {
        Pixmap src = new Pixmap(2, 1);
        src.setStraight(0, 0, 0xFFFF0000);
        src.setStraight(1, 0, 0xFF0000FF);
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(8, 4);
        c.imageSmoothingEnabled(false);
        c.drawImage(src, 0, 0, 8, 4);
        assertEquals(0xFFFF0000, px(c, 1, 2));
        assertEquals(0xFF0000FF, px(c, 6, 2));
        CanvasRenderingContext2D f = new CanvasRenderingContext2D(8, 4);
        f.imageSmoothingEnabled(false);
        f.translate(8, 0);
        f.scale(-1, 1);
        f.drawImage(src, 0, 0, 8, 4);
        assertEquals(0xFF0000FF, px(f, 1, 2));
        assertEquals(0xFFFF0000, px(f, 6, 2));
        // ソース矩形の指定
        CanvasRenderingContext2D s = new CanvasRenderingContext2D(4, 4);
        s.imageSmoothingEnabled(false);
        s.drawImage(src, 1, 0, 1, 1, 0, 0, 4, 4);
        assertEquals(0xFF0000FF, px(s, 2, 2));
    }

    @Test
    void getAndPutImageDataRoundTrip() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(4, 4);
        ImageData d = c.createImageData(2, 2);
        d.setArgb(0, 0, 0xFF112233);
        d.setArgb(1, 0, 0x80FF0000);
        d.setArgb(0, 1, 0x00000000);
        d.setArgb(1, 1, 0xFFFFFFFF);
        c.putImageData(d, 1, 1);
        ImageData back = c.getImageData(1, 1, 2, 2);
        assertEquals(0xFF112233, back.argbAt(0, 0));
        assertArgb(0x80FF0000, back.argbAt(1, 0), 1);
        assertEquals(0, back.argbAt(0, 1));
        assertEquals(0xFFFFFFFF, back.argbAt(1, 1));
        // 範囲外は透明
        ImageData outside = c.getImageData(-1, -1, 2, 2);
        assertEquals(0, outside.argbAt(0, 0));
        assertEquals(0, outside.argbAt(1, 1) == 0 ? 0 : 1);
        assertThrows(IllegalArgumentException.class, () -> c.getImageData(0, 0, 0, 1));
    }

    @Test
    void putImageDataIgnoresStateButGetSeesDraws() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(4, 4);
        c.globalAlpha(0.1);
        c.globalCompositeOperation("destination-out");
        c.translate(2, 2);
        ImageData d = c.createImageData(1, 1);
        d.setArgb(0, 0, 0xFF00FF00);
        c.putImageData(d, 0, 0);
        assertEquals(0xFF00FF00, px(c, 0, 0));
    }

    @Test
    void saveRestoreStateStack() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(10, 10);
        c.fillStyle("#00ff00");
        c.translate(5, 5);
        c.save();
        c.fillStyle("#ff0000");
        c.translate(-5, -5);
        c.globalAlpha(0.2);
        c.restore();
        c.fillRect(0, 0, 2, 2);
        assertEquals(0xFF00FF00, px(c, 5, 5));
        assertEquals(1, c.globalAlpha());
        c.restore();   // 余分な restore は無視される
        assertEquals(1, c.globalAlpha());
    }

    @Test
    void transformsApplyToFills() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(20, 20);
        c.translate(10, 10);
        c.rotate(Math.PI / 2);
        c.fillStyle("#ff0000");
        c.fillRect(0, 0, 6, 2);   // 90 度回すと x:8..10, y:10..16
        assertEquals(0xFFFF0000, px(c, 9, 12));
        assertEquals(0, px(c, 12, 9));
        c.setTransform(1, 0, 0, 1, 0, 0);
        c.fillStyle("#0000ff");
        c.fillRect(0, 0, 1, 1);
        assertEquals(0xFF0000FF, px(c, 0, 0));
        // 逆行列の無い変換では何も描かない
        c.scale(0, 1);
        c.fillStyle("#00ff00");
        c.fillRect(0, 0, 20, 20);
        assertEquals(0xFF0000FF, px(c, 0, 0));
    }

    @Test
    void pathIsFixedAtConstructionTime() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(20, 20);
        c.beginPath();
        c.rect(0, 0, 5, 5);
        c.translate(10, 10);   // 作った後の変換は、既にあるパスを動かさない
        c.fillStyle("#ff0000");
        c.fill();
        assertEquals(0xFFFF0000, px(c, 2, 2));
        assertEquals(0, px(c, 12, 12));
    }

    @Test
    void strokeWidthFollowsNonUniformScale() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(40, 40);
        c.scale(4, 1);
        c.lineWidth(2);
        c.strokeStyle("#000000");
        c.beginPath();
        c.moveTo(0, 20);
        c.lineTo(8, 20);
        c.stroke();
        // 横線なので縦の太さは 2、縦線なら x 方向に 8 になる
        assertEquals(0xFF000000, px(c, 10, 19));
        assertEquals(0xFF000000, px(c, 10, 20));
        assertEquals(0, px(c, 10, 18));
        CanvasRenderingContext2D v = new CanvasRenderingContext2D(40, 40);
        v.scale(4, 1);
        v.lineWidth(2);
        v.beginPath();
        v.moveTo(5, 0);
        v.lineTo(5, 40);
        v.stroke();
        assertEquals(0xFF000000, px(v, 17, 5));   // 20 ± 4
        assertEquals(0xFF000000, px(v, 23, 5));
        assertEquals(0, px(v, 15, 5));
    }

    @Test
    void clipRestrictsDrawing() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(20, 20);
        c.save();
        c.beginPath();
        c.rect(5, 5, 5, 5);
        c.clip(false);
        c.fillStyle("#ff0000");
        c.fillRect(0, 0, 20, 20);
        assertEquals(0xFFFF0000, px(c, 7, 7));
        assertEquals(0, px(c, 2, 2));
        assertEquals(0, px(c, 12, 12));
        c.restore();
        c.fillStyle("#0000ff");
        c.fillRect(0, 0, 1, 1);
        assertEquals(0xFF0000FF, px(c, 0, 0));
        // クリップは交差する
        c.save();
        c.beginPath();
        c.rect(0, 0, 10, 10);
        c.clip(false);
        c.beginPath();
        c.rect(5, 5, 10, 10);
        c.clip(false);
        c.fillStyle("#00ff00");
        c.fillRect(0, 0, 20, 20);
        assertEquals(0xFF00FF00, px(c, 7, 7));
        assertEquals(0, px(c, 12, 12));
        assertEquals(0xFF0000FF, px(c, 0, 0));
    }

    @Test
    void clearRectRespectsClipAndTransform() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(10, 10);
        c.fillStyle("#ff0000");
        c.fillRect(0, 0, 10, 10);
        c.clearRect(2, 2, 3, 3);
        assertEquals(0, px(c, 3, 3));
        assertEquals(0xFFFF0000, px(c, 6, 6));
        c.save();
        c.beginPath();
        c.rect(0, 0, 3, 10);
        c.clip(false);
        c.clearRect(0, 0, 10, 10);
        c.restore();
        assertEquals(0, px(c, 1, 8));
        assertEquals(0xFFFF0000, px(c, 8, 8));
    }

    @Test
    void pointInPathAndStroke() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(20, 20);
        c.beginPath();
        c.rect(5, 5, 10, 10);
        assertTrue(c.isPointInPath(10, 10, false));
        assertFalse(c.isPointInPath(2, 2, false));
        c.lineWidth(4);
        assertTrue(c.isPointInStroke(5, 10));
        assertTrue(c.isPointInStroke(6.5, 10));
        assertFalse(c.isPointInStroke(10, 10));
        // Path2D 版は現在の変換を掛けて判定する
        Path2D p = new Path2D().rect(0, 0, 4, 4);
        c.translate(10, 10);
        assertTrue(c.isPointInPath(p, 12, 12, false));
        assertFalse(c.isPointInPath(p, 2, 2, false));
    }

    @Test
    void arcFillsCircle() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(40, 40);
        c.fillStyle("#000000");
        c.beginPath();
        c.arc(20, 20, 10, 0, Math.PI * 2, false);
        c.fill();
        assertEquals(0xFF000000, px(c, 20, 20));
        assertEquals(0xFF000000, px(c, 28, 20));
        assertEquals(0, px(c, 31, 20));
        assertEquals(0, px(c, 28, 28));   // 角は円の外
        assertThrows(IllegalArgumentException.class, () -> c.arc(0, 0, -1, 0, 1, false));
    }

    @Test
    void closePathThenLineToStartsFromSubpathStart() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(20, 20);
        c.beginPath();
        c.moveTo(2, 2);
        c.lineTo(10, 2);
        c.lineTo(10, 10);
        c.closePath();
        c.lineTo(2, 10);   // 始点 (2,2) からの新しい部分パス
        c.lineWidth(2);
        c.strokeStyle("#000000");
        c.stroke();
        assertEquals(0xFF000000, px(c, 2, 6));   // (2,2) → (2,10) の縦線
    }

    @Test
    void dashedStroke() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(40, 10);
        c.setLineDash(5, 5);
        c.lineWidth(2);
        c.beginPath();
        c.moveTo(0, 5);
        c.lineTo(40, 5);
        c.stroke();
        assertEquals(0xFF000000, px(c, 2, 5));
        assertEquals(0, px(c, 7, 5));
        assertEquals(0xFF000000, px(c, 12, 5));
        c.reset();
        assertEquals(0, c.getLineDash().length);
        assertEquals(0, px(c, 2, 5));
    }

    @Test
    void shadowDrawsOffsetCopyAndBlurSpreads() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(40, 40);
        c.shadowColor("#ff0000");
        c.shadowOffsetX(10);
        c.shadowOffsetY(0);
        c.fillStyle("#0000ff");
        c.fillRect(2, 10, 6, 6);
        assertEquals(0xFF0000FF, px(c, 4, 12));
        assertEquals(0xFFFF0000, px(c, 14, 12));   // 影はずらした位置
        CanvasRenderingContext2D b = new CanvasRenderingContext2D(40, 40);
        b.shadowColor("#000000");
        b.shadowBlur(8);
        b.fillStyle("#ffffff");
        b.fillRect(15, 15, 10, 10);
        int outside = b.bitmap().get(13, 20) >>> 24;
        int far = b.bitmap().get(1, 1) >>> 24;
        assertTrue(outside > 0, "blur must leak outside the shape");
        assertEquals(0, far);
        // 影が無い設定（透明色）では何も出ない
        CanvasRenderingContext2D n = new CanvasRenderingContext2D(10, 10);
        n.shadowOffsetX(5);
        n.fillRect(0, 0, 2, 2);
        assertEquals(0, px(n, 6, 0));
    }

    @Test
    void roundRectAndBounds() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(30, 30);
        c.fillStyle("#000000");
        c.beginPath();
        c.roundRect(5, 5, 20, 20, 8);
        c.fill();
        assertEquals(0xFF000000, px(c, 15, 15));
        assertEquals(0, px(c, 5, 5));   // 角は丸く欠ける
        assertEquals(0xFF000000, px(c, 15, 5));
    }

    @Test
    void measureTextUsesFontFaceMetrics() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(10, 10);
        c.font("20px serif");
        TextMetrics m = c.measureText("iii");
        assertTrue(m.width() > 0);
        c.textAlign("center");
        TextMetrics centered = c.measureText("iii");
        assertEquals(m.width(), centered.width(), 1e-9);
        assertEquals(m.width() / 2, centered.actualBoundingBoxLeft(), 1e-9);
        c.font("garbage");
        assertEquals("20px serif", c.font());
        assertEquals(0, c.measureText("").width());
    }

    @Test
    void resizeClearsBitmapAndState() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(4, 4);
        c.fillStyle("#ff0000");
        c.lineWidth(7);
        c.fillRect(0, 0, 4, 4);
        c.resize(8, 6);
        assertEquals(8, c.width());
        assertEquals(6, c.height());
        assertEquals(0, px(c, 1, 1));
        assertEquals(1, c.lineWidth());
    }

    @Test
    void modCountAdvancesOnDraw() {
        CanvasRenderingContext2D c = new CanvasRenderingContext2D(4, 4);
        long before = c.modCount();
        c.fillRect(0, 0, 2, 2);
        assertTrue(c.modCount() > before);
    }
}
