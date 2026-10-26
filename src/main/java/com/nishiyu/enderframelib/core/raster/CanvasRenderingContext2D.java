package com.nishiyu.enderframelib.core.raster;

import com.nishiyu.enderframelib.core.css.ColorParser;
import com.nishiyu.enderframelib.core.text.font.OutlineFontFace;
import com.nishiyu.enderframelib.core.text.model.FontFace;
import com.nishiyu.enderframelib.core.text.model.FontResolver;
import com.nishiyu.enderframelib.core.text.model.FontStyle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * HTML Standard の {@code CanvasRenderingContext2D}（ソフトウェア描画）。
 *
 * ビットマップは {@link Pixmap}（事前乗算済み ARGB）。実装している範囲: パス（{@link Path2D}）・塗りと線・
 * 線の端と結合とマイター・破線・3 種のグラデーションとパターン・{@code drawImage}・テキスト・影・
 * {@code globalAlpha} / {@code globalCompositeOperation}（22 種）・変換・クリップ・{@code save} / {@code restore}・
 * {@code getImageData} / {@code putImageData}・{@code isPointInPath} / {@code isPointInStroke}。
 *
 * 現在のパスは、追加した時点の変換を掛けたデバイス座標で持つ（仕様どおり。後から {@code transform} を変えても、
 * 既に足した部分は動かない）。線を引くときは現在の変換の逆でユーザー空間に戻して太らせるので、
 * 非一様な拡大でも線の太さが仕様どおりに歪む。
 *
 * LIMITATION(R8): テキストは平行移動と一様な拡大だけ反映する（回転・せん断では字形を回さない）。
 * {@code strokeText} は {@code fillText} と同じく塗りで描く。{@code filter} は持たない。
 */
public final class CanvasRenderingContext2D {

    private static final double FLATTEN_TOLERANCE = 0.1;

    /** save / restore で戻す状態。 */
    private static final class State implements Cloneable {
        Matrix2D transform = Matrix2D.IDENTITY;
        Paint fillStyle = Paint.solid(0xFF000000);
        Paint strokeStyle = Paint.solid(0xFF000000);
        String fillCss = "#000000";
        String strokeCss = "#000000";
        double lineWidth = 1;
        Stroker.Cap lineCap = Stroker.Cap.BUTT;
        Stroker.Join lineJoin = Stroker.Join.MITER;
        double miterLimit = 10;
        double[] lineDash = new double[0];
        double lineDashOffset = 0;
        double globalAlpha = 1;
        CompositeOp composite = CompositeOp.SOURCE_OVER;
        int shadowColor = 0;
        double shadowBlur = 0;
        double shadowOffsetX = 0;
        double shadowOffsetY = 0;
        boolean smoothing = true;
        String font = "10px sans-serif";
        String textAlign = "start";
        String textBaseline = "alphabetic";
        byte[] clip;

        @Override
        public State clone() {
            try {
                State s = (State) super.clone();
                s.lineDash = lineDash.clone();
                return s;
            } catch (CloneNotSupportedException e) {
                throw new AssertionError(e);
            }
        }
    }

    private Pixmap bitmap;
    private State state = new State();
    private final ArrayDeque<State> stack = new ArrayDeque<>();
    private Path2D path = new Path2D();
    private FontResolver fonts = FontResolver.FALLBACK;
    private long modCount;

    public CanvasRenderingContext2D(int width, int height) {
        this.bitmap = new Pixmap(Math.max(1, width), Math.max(1, height));
    }

    // ------------------------------------------------------------------ ビットマップ

    public Pixmap bitmap() {
        return bitmap;
    }

    public int width() {
        return bitmap.width();
    }

    public int height() {
        return bitmap.height();
    }

    /** 描画のたびに増える値（テクスチャの作り直しの判定に使う）。 */
    public long modCount() {
        return modCount;
    }

    /** ビットマップの大きさを変える。仕様どおり、中身と状態（パスを含む）がすべて初期化される。 */
    public void resize(int width, int height) {
        bitmap = new Pixmap(Math.max(1, width), Math.max(1, height));
        resetState();
    }

    /** {@code reset()}。ビットマップを透明にし、状態を初期値へ戻す。 */
    public void reset() {
        bitmap.clear();
        resetState();
    }

    private void resetState() {
        state = new State();
        stack.clear();
        path = new Path2D();
        modCount++;
    }

    public CanvasRenderingContext2D fonts(FontResolver fonts) {
        this.fonts = fonts == null ? FontResolver.FALLBACK : fonts;
        return this;
    }

    // ------------------------------------------------------------------ 状態

    public void save() {
        stack.push(state.clone());
    }

    public void restore() {
        if (!stack.isEmpty()) state = stack.pop();
    }

    public double globalAlpha() {
        return state.globalAlpha;
    }

    public void globalAlpha(double v) {
        if (v >= 0 && v <= 1) state.globalAlpha = v;
    }

    public String globalCompositeOperation() {
        return state.composite.cssName();
    }

    public void globalCompositeOperation(String name) {
        CompositeOp op = CompositeOp.parse(name);
        if (op != null) state.composite = op;
    }

    public double lineWidth() {
        return state.lineWidth;
    }

    public void lineWidth(double v) {
        if (v > 0 && Double.isFinite(v)) state.lineWidth = v;
    }

    public String lineCap() {
        return state.lineCap.name().toLowerCase(Locale.ROOT);
    }

    public void lineCap(String v) {
        if (v == null) return;
        switch (v) {
            case "butt" -> state.lineCap = Stroker.Cap.BUTT;
            case "round" -> state.lineCap = Stroker.Cap.ROUND;
            case "square" -> state.lineCap = Stroker.Cap.SQUARE;
            default -> {
            }
        }
    }

    public String lineJoin() {
        return state.lineJoin.name().toLowerCase(Locale.ROOT);
    }

    public void lineJoin(String v) {
        if (v == null) return;
        switch (v) {
            case "miter" -> state.lineJoin = Stroker.Join.MITER;
            case "round" -> state.lineJoin = Stroker.Join.ROUND;
            case "bevel" -> state.lineJoin = Stroker.Join.BEVEL;
            default -> {
            }
        }
    }

    public double miterLimit() {
        return state.miterLimit;
    }

    public void miterLimit(double v) {
        if (v > 0 && Double.isFinite(v)) state.miterLimit = v;
    }

    /** 破線。負・非有限の値が 1 つでもあれば無視する。奇数個なら 2 回繰り返した配列になる。 */
    public void setLineDash(double... segments) {
        if (segments == null) segments = new double[0];
        for (double d : segments) if (!(d >= 0) || !Double.isFinite(d)) return;
        state.lineDash = segments.length % 2 == 1
                ? concat(segments, segments) : segments.clone();
    }

    public double[] getLineDash() {
        return state.lineDash.clone();
    }

    public double lineDashOffset() {
        return state.lineDashOffset;
    }

    public void lineDashOffset(double v) {
        if (Double.isFinite(v)) state.lineDashOffset = v;
    }

    private static double[] concat(double[] a, double[] b) {
        double[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    public void fillStyle(String css) {
        if (css == null || !ColorParser.isColor(css) || css.trim().equalsIgnoreCase("currentcolor")) return;
        state.fillStyle = Paint.solid(ColorParser.parseColor(css));
        state.fillCss = css;
    }

    public void fillStyle(Paint paint) {
        if (paint != null) state.fillStyle = paint;
    }

    public void strokeStyle(String css) {
        if (css == null || !ColorParser.isColor(css) || css.trim().equalsIgnoreCase("currentcolor")) return;
        state.strokeStyle = Paint.solid(ColorParser.parseColor(css));
        state.strokeCss = css;
    }

    public void strokeStyle(Paint paint) {
        if (paint != null) state.strokeStyle = paint;
    }

    public Paint fillStyle() {
        return state.fillStyle;
    }

    public Paint strokeStyle() {
        return state.strokeStyle;
    }

    public void shadowColor(String css) {
        if (css != null && ColorParser.isColor(css)) state.shadowColor = ColorParser.parseColor(css);
    }

    public void shadowColor(int argb) {
        state.shadowColor = argb;
    }

    public void shadowBlur(double v) {
        if (v >= 0 && Double.isFinite(v)) state.shadowBlur = v;
    }

    public void shadowOffsetX(double v) {
        if (Double.isFinite(v)) state.shadowOffsetX = v;
    }

    public void shadowOffsetY(double v) {
        if (Double.isFinite(v)) state.shadowOffsetY = v;
    }

    public void imageSmoothingEnabled(boolean v) {
        state.smoothing = v;
    }

    public boolean imageSmoothingEnabled() {
        return state.smoothing;
    }

    public void font(String css) {
        if (css != null && parseFont(css) != null) state.font = css;
    }

    public String font() {
        return state.font;
    }

    public void textAlign(String v) {
        if (v != null && List.of("start", "end", "left", "right", "center").contains(v)) state.textAlign = v;
    }

    public void textBaseline(String v) {
        if (v != null && List.of("top", "hanging", "middle", "alphabetic", "ideographic", "bottom").contains(v)) {
            state.textBaseline = v;
        }
    }

    // ------------------------------------------------------------------ 変換

    public Matrix2D getTransform() {
        return state.transform;
    }

    public void setTransform(double a, double b, double c, double d, double e, double f) {
        Matrix2D m = new Matrix2D(a, b, c, d, e, f);
        if (m.isFinite()) state.transform = m;
    }

    public void setTransform(Matrix2D m) {
        if (m != null && m.isFinite()) state.transform = m;
    }

    public void resetTransform() {
        state.transform = Matrix2D.IDENTITY;
    }

    public void transform(double a, double b, double c, double d, double e, double f) {
        Matrix2D m = new Matrix2D(a, b, c, d, e, f);
        if (m.isFinite()) state.transform = state.transform.multiply(m);
    }

    public void translate(double x, double y) {
        if (Double.isFinite(x) && Double.isFinite(y)) state.transform = state.transform.multiply(Matrix2D.translate(x, y));
    }

    public void scale(double x, double y) {
        if (Double.isFinite(x) && Double.isFinite(y)) state.transform = state.transform.multiply(Matrix2D.scale(x, y));
    }

    public void rotate(double radians) {
        if (Double.isFinite(radians)) state.transform = state.transform.multiply(Matrix2D.rotate(radians));
    }

    // ------------------------------------------------------------------ パス

    public void beginPath() {
        path = new Path2D();
    }

    public void closePath() {
        path.closePath();
    }

    public void moveTo(double x, double y) {
        if (!finite(x, y)) return;
        Matrix2D m = state.transform;
        path.moveTo(m.applyX(x, y), m.applyY(x, y));
    }

    public void lineTo(double x, double y) {
        if (!finite(x, y)) return;
        Matrix2D m = state.transform;
        path.lineTo(m.applyX(x, y), m.applyY(x, y));
    }

    public void quadraticCurveTo(double cx, double cy, double x, double y) {
        if (!finite(cx, cy) || !finite(x, y)) return;
        Matrix2D m = state.transform;
        path.quadTo(m.applyX(cx, cy), m.applyY(cx, cy), m.applyX(x, y), m.applyY(x, y));
    }

    public void bezierCurveTo(double c1x, double c1y, double c2x, double c2y, double x, double y) {
        if (!finite(c1x, c1y) || !finite(c2x, c2y) || !finite(x, y)) return;
        Matrix2D m = state.transform;
        path.cubicTo(m.applyX(c1x, c1y), m.applyY(c1x, c1y), m.applyX(c2x, c2y), m.applyY(c2x, c2y),
                m.applyX(x, y), m.applyY(x, y));
    }

    public void rect(double x, double y, double w, double h) {
        path.append(new Path2D().rect(x, y, w, h), state.transform);
    }

    public void roundRect(double x, double y, double w, double h, double... radii) {
        path.append(new Path2D().roundRect(x, y, w, h, radii), state.transform);
    }

    public void arc(double x, double y, double r, double start, double end, boolean counterclockwise) {
        if (r < 0) throw new IllegalArgumentException("IndexSizeError: negative radius");
        path.append(new Path2D().arc(x, y, r, start, end, counterclockwise), state.transform, true);
    }

    public void ellipse(double x, double y, double rx, double ry, double rotation, double start, double end,
                        boolean counterclockwise) {
        if (rx < 0 || ry < 0) throw new IllegalArgumentException("IndexSizeError: negative radius");
        path.append(new Path2D().ellipse(x, y, rx, ry, rotation, start, end, counterclockwise), state.transform, true);
    }

    public void arcTo(double x1, double y1, double x2, double y2, double r) {
        if (!finite(x1, y1) || !finite(x2, y2) || !Double.isFinite(r)) return;
        if (r < 0) throw new IllegalArgumentException("IndexSizeError: negative radius");
        Matrix2D m = state.transform;
        Matrix2D inv = m.invert();
        if (inv == null) return;
        if (!path.hasCurrentPoint()) path.moveTo(m.applyX(x1, y1), m.applyY(x1, y1));
        double ux = inv.applyX(path.currentX(), path.currentY());
        double uy = inv.applyY(path.currentX(), path.currentY());
        Path2D tmp = new Path2D().moveTo(ux, uy).arcTo(x1, y1, x2, y2, r);
        path.append(tmp, m, true);
    }

    /** 現在のパスのコピー（デバイス座標）。 */
    public Path2D currentPath() {
        return new Path2D(path);
    }

    // ------------------------------------------------------------------ 塗りと線

    public void fill() {
        fill(false);
    }

    public void fill(boolean evenOdd) {
        fillPolys(path.flatten(Matrix2D.IDENTITY, FLATTEN_TOLERANCE), evenOdd, state.fillStyle);
    }

    public void fill(Path2D p, boolean evenOdd) {
        fillPolys(p.flatten(state.transform, FLATTEN_TOLERANCE), evenOdd, state.fillStyle);
    }

    public void stroke() {
        Matrix2D inv = state.transform.invert();
        if (inv == null) return;
        double scale = state.transform.meanScale();
        strokeUser(path.flatten(inv, FLATTEN_TOLERANCE / scale), state.strokeStyle);
    }

    public void stroke(Path2D p) {
        strokeUser(p.flatten(Matrix2D.IDENTITY, FLATTEN_TOLERANCE / Math.max(1e-6, state.transform.meanScale())),
                state.strokeStyle);
    }

    public void fillRect(double x, double y, double w, double h) {
        if (!finite(x, y) || !finite(w, h) || w == 0 || h == 0) return;
        fillPolys(new Path2D().rect(x, y, w, h).flatten(state.transform, FLATTEN_TOLERANCE), false, state.fillStyle);
    }

    public void strokeRect(double x, double y, double w, double h) {
        if (!finite(x, y) || !finite(w, h)) return;
        if (w == 0 && h == 0) return;
        double scale = Math.max(1e-6, state.transform.meanScale());
        strokeUser(new Path2D().rect(x, y, w, h).flatten(Matrix2D.IDENTITY, FLATTEN_TOLERANCE / scale), state.strokeStyle);
    }

    /** 変換した矩形の領域を透明にする（クリップは効く）。 */
    public void clearRect(double x, double y, double w, double h) {
        if (!finite(x, y) || !finite(w, h) || w == 0 || h == 0) return;
        Rasterizer.Coverage cov = Rasterizer.fill(
                new Path2D().rect(x, y, w, h).flatten(state.transform, FLATTEN_TOLERANCE), false, bitmap.width(), bitmap.height());
        if (cov == null) return;
        int[] px = bitmap.pixels();
        byte[] clip = state.clip;
        for (int row = 0; row < cov.height(); row++) {
            int y0 = cov.y() + row;
            for (int col = 0; col < cov.width(); col++) {
                int c = cov.data()[row * cov.width() + col] & 0xFF;
                if (c == 0) continue;
                int x0 = cov.x() + col;
                int idx = y0 * bitmap.width() + x0;
                if (clip != null) c = Pixmap.div255(c * (clip[idx] & 0xFF));
                if (c == 0) continue;
                px[idx] = c == 255 ? 0 : scalePremultiplied(px[idx], 255 - c);
            }
        }
        modCount++;
    }

    /** 現在のパスでクリップを狭める。 */
    public void clip(boolean evenOdd) {
        clipPolys(path.flatten(Matrix2D.IDENTITY, FLATTEN_TOLERANCE), evenOdd);
    }

    public void clip(Path2D p, boolean evenOdd) {
        clipPolys(p.flatten(state.transform, FLATTEN_TOLERANCE), evenOdd);
    }

    private void clipPolys(List<Path2D.Polyline> polys, boolean evenOdd) {
        int w = bitmap.width();
        int h = bitmap.height();
        byte[] next = new byte[w * h];
        Rasterizer.Coverage cov = Rasterizer.fill(polys, evenOdd, w, h);
        if (cov != null) {
            byte[] old = state.clip;
            for (int row = 0; row < cov.height(); row++) {
                for (int col = 0; col < cov.width(); col++) {
                    int c = cov.data()[row * cov.width() + col] & 0xFF;
                    if (c == 0) continue;
                    int idx = (cov.y() + row) * w + cov.x() + col;
                    next[idx] = (byte) (old == null ? c : Pixmap.div255(c * (old[idx] & 0xFF)));
                }
            }
        }
        state.clip = next;
    }

    public boolean isPointInPath(double x, double y, boolean evenOdd) {
        return finite(x, y) && Rasterizer.contains(path.flatten(Matrix2D.IDENTITY, FLATTEN_TOLERANCE), x, y, evenOdd);
    }

    public boolean isPointInPath(Path2D p, double x, double y, boolean evenOdd) {
        return finite(x, y) && Rasterizer.contains(p.flatten(state.transform, FLATTEN_TOLERANCE), x, y, evenOdd);
    }

    public boolean isPointInStroke(double x, double y) {
        if (!finite(x, y)) return false;
        Matrix2D inv = state.transform.invert();
        if (inv == null) return false;
        double scale = state.transform.meanScale();
        List<Path2D.Polyline> polys = strokePolygons(path.flatten(inv, FLATTEN_TOLERANCE / scale), scale);
        return Rasterizer.contains(polys, x, y, false);
    }

    public boolean isPointInStroke(Path2D p, double x, double y) {
        if (!finite(x, y)) return false;
        double scale = Math.max(1e-6, state.transform.meanScale());
        List<Path2D.Polyline> polys = strokePolygons(p.flatten(Matrix2D.IDENTITY, FLATTEN_TOLERANCE / scale), scale);
        return Rasterizer.contains(polys, x, y, false);
    }

    /** ユーザー空間の折れ線を太らせ、デバイス座標の多角形にする。 */
    private List<Path2D.Polyline> strokePolygons(List<Path2D.Polyline> userLines, double scale) {
        List<Path2D.Polyline> user = Stroker.stroke(userLines, state.lineWidth, state.lineCap, state.lineJoin,
                state.miterLimit, state.lineDash, state.lineDashOffset, FLATTEN_TOLERANCE / Math.max(1e-6, scale));
        List<Path2D.Polyline> out = new ArrayList<>(user.size());
        Matrix2D m = state.transform;
        for (Path2D.Polyline pl : user) {
            double[] xy = new double[pl.count() * 2];
            for (int i = 0; i < pl.count(); i++) {
                xy[i * 2] = m.applyX(pl.x(i), pl.y(i));
                xy[i * 2 + 1] = m.applyY(pl.x(i), pl.y(i));
            }
            out.add(new Path2D.Polyline(xy, pl.count(), true));
        }
        return out;
    }

    private void strokeUser(List<Path2D.Polyline> userLines, Paint paint) {
        if (!state.transform.isInvertible()) return;
        double scale = Math.max(1e-6, state.transform.meanScale());
        fillPolys(strokePolygons(userLines, scale), false, paint);
    }

    // ------------------------------------------------------------------ グラデーション・パターン

    public CanvasGradient createLinearGradient(double x0, double y0, double x1, double y1) {
        return CanvasGradient.linear(x0, y0, x1, y1);
    }

    public CanvasGradient createRadialGradient(double x0, double y0, double r0, double x1, double y1, double r1) {
        return CanvasGradient.radial(x0, y0, r0, x1, y1, r1);
    }

    public CanvasGradient createConicGradient(double startAngle, double x, double y) {
        return CanvasGradient.conic(startAngle, x, y);
    }

    public CanvasPattern createPattern(Pixmap image, String repetition) {
        return new CanvasPattern(image, repetition);
    }

    // ------------------------------------------------------------------ 画像

    public void drawImage(Pixmap img, double dx, double dy) {
        drawImage(img, 0, 0, img.width(), img.height(), dx, dy, img.width(), img.height());
    }

    public void drawImage(Pixmap img, double dx, double dy, double dw, double dh) {
        drawImage(img, 0, 0, img.width(), img.height(), dx, dy, dw, dh);
    }

    public void drawImage(Pixmap img, double sx, double sy, double sw, double sh,
                          double dx, double dy, double dw, double dh) {
        if (img == null || !finite(sx, sy) || !finite(sw, sh) || !finite(dx, dy) || !finite(dw, dh)) return;
        if (sw == 0 || sh == 0 || dw == 0 || dh == 0) return;
        // 負の幅・高さは、反対側を起点にした同じ矩形
        if (sw < 0) {
            sx += sw;
            sw = -sw;
        }
        if (sh < 0) {
            sy += sh;
            sh = -sh;
        }
        double cx0 = Math.max(0, sx);
        double cy0 = Math.max(0, sy);
        double cx1 = Math.min(img.width(), sx + sw);
        double cy1 = Math.min(img.height(), sy + sh);
        if (cx0 >= cx1 || cy0 >= cy1) return;
        // 元の矩形が画像からはみ出す分は、先の矩形も同じ比で切る
        double kx = dw / sw;
        double ky = dh / sh;
        double ndx = dx + (cx0 - sx) * kx;
        double ndy = dy + (cy0 - sy) * ky;
        double ndw = (cx1 - cx0) * kx;
        double ndh = (cy1 - cy0) * ky;
        int isx = (int) Math.floor(cx0);
        int isy = (int) Math.floor(cy0);
        int isw = Math.max(1, (int) Math.ceil(cx1) - isx);
        int ish = Math.max(1, (int) Math.ceil(cy1) - isy);
        CanvasPattern pattern = new CanvasPattern(img, isx, isy, isw, ish, "no-repeat").smoothing(state.smoothing);
        pattern.setTransform(Matrix2D.translate(ndx - (cx0 - isx) * ndw / (cx1 - cx0), ndy - (cy0 - isy) * ndh / (cy1 - cy0))
                .multiply(Matrix2D.scale(ndw / (cx1 - cx0), ndh / (cy1 - cy0))));
        fillPolys(new Path2D().rect(ndx, ndy, ndw, ndh).flatten(state.transform, FLATTEN_TOLERANCE), false, pattern);
    }

    public void drawImage(CanvasRenderingContext2D canvas, double dx, double dy) {
        drawImage(canvas.bitmap(), dx, dy);
    }

    public ImageData createImageData(int w, int h) {
        return new ImageData(w, h);
    }

    public ImageData getImageData(int sx, int sy, int sw, int sh) {
        if (sw == 0 || sh == 0) throw new IllegalArgumentException("IndexSizeError: zero size");
        if (sw < 0) {
            sx += sw;
            sw = -sw;
        }
        if (sh < 0) {
            sy += sh;
            sh = -sh;
        }
        ImageData out = new ImageData(sw, sh);
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int argb = bitmap.getStraight(sx + x, sy + y);
                if (argb != 0) out.setArgb(x, y, argb);
            }
        }
        return out;
    }

    /** 変換・クリップ・合成を使わず、そのまま上書きする。 */
    public void putImageData(ImageData img, int dx, int dy) {
        putImageData(img, dx, dy, 0, 0, img.width(), img.height());
    }

    public void putImageData(ImageData img, int dx, int dy, int dirtyX, int dirtyY, int dirtyW, int dirtyH) {
        if (dirtyW < 0) {
            dirtyX += dirtyW;
            dirtyW = -dirtyW;
        }
        if (dirtyH < 0) {
            dirtyY += dirtyH;
            dirtyH = -dirtyH;
        }
        int x0 = Math.max(0, dirtyX);
        int y0 = Math.max(0, dirtyY);
        int x1 = Math.min(img.width(), dirtyX + dirtyW);
        int y1 = Math.min(img.height(), dirtyY + dirtyH);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int bx = dx + x;
                int by = dy + y;
                if (bx < 0 || by < 0 || bx >= bitmap.width() || by >= bitmap.height()) continue;
                bitmap.setStraight(bx, by, img.argbAt(x, y));
            }
        }
        modCount++;
    }

    // ------------------------------------------------------------------ テキスト

    private record ParsedFont(float sizePx, int weight, boolean italic, String family) {}

    private static ParsedFont parseFont(String css) {
        String s = css.trim();
        if (s.isEmpty()) return null;
        String[] tokens = s.split("\\s+");
        int weight = 400;
        boolean italic = false;
        int i = 0;
        for (; i < tokens.length; i++) {
            String t = tokens[i].toLowerCase(Locale.ROOT);
            if (t.equals("normal") || t.equals("small-caps")) continue;
            if (t.equals("italic") || t.equals("oblique")) {
                italic = true;
                continue;
            }
            if (t.equals("bold")) {
                weight = 700;
                continue;
            }
            if (t.equals("bolder")) {
                weight = 700;
                continue;
            }
            if (t.equals("lighter")) {
                weight = 300;
                continue;
            }
            if (t.matches("[1-9]00")) {
                weight = Integer.parseInt(t);
                continue;
            }
            break;
        }
        if (i >= tokens.length - 1) return null;
        String size = tokens[i].toLowerCase(Locale.ROOT);
        int slash = size.indexOf('/');
        if (slash >= 0) size = size.substring(0, slash);
        float px;
        try {
            if (size.endsWith("px")) px = Float.parseFloat(size.substring(0, size.length() - 2));
            else if (size.endsWith("pt")) px = Float.parseFloat(size.substring(0, size.length() - 2)) * 4f / 3f;
            else if (size.endsWith("rem")) px = Float.parseFloat(size.substring(0, size.length() - 3)) * 16f;
            else if (size.endsWith("em")) px = Float.parseFloat(size.substring(0, size.length() - 2)) * 16f;
            else if (size.endsWith("%")) px = Float.parseFloat(size.substring(0, size.length() - 1)) * 0.16f;
            else return null;
        } catch (NumberFormatException e) {
            return null;
        }
        if (!(px > 0) || !Float.isFinite(px)) return null;
        StringBuilder fam = new StringBuilder();
        for (int k = i + 1; k < tokens.length; k++) {
            if (fam.length() > 0) fam.append(' ');
            fam.append(tokens[k]);
        }
        return new ParsedFont(px, weight, italic, fam.toString());
    }

    private ParsedFont currentFont() {
        ParsedFont f = parseFont(state.font);
        return f != null ? f : new ParsedFont(10, 400, false, "sans-serif");
    }

    public TextMetrics measureText(String text) {
        ParsedFont f = currentFont();
        FontFace face = fonts.resolve(f.family());
        FontStyle style = new FontStyle(f.sizePx(), f.weight() >= 600, f.italic(), 0, f.weight(), true);
        double width = text == null || text.isEmpty() ? 0 : face.advance(text, style);
        double dx = alignOffset(width);
        return new TextMetrics(width, -dx, width + dx, face.ascent(style), face.descent(style));
    }

    private double alignOffset(double width) {
        return switch (state.textAlign) {
            case "center" -> -width / 2;
            case "right", "end" -> -width;
            default -> 0;
        };
    }

    public void fillText(String text, double x, double y) {
        fillText(text, x, y, Double.NaN);
    }

    public void fillText(String text, double x, double y, double maxWidth) {
        drawText(text, x, y, maxWidth, state.fillStyle);
    }

    public void strokeText(String text, double x, double y) {
        drawText(text, x, y, Double.NaN, state.strokeStyle);
    }

    private void drawText(String text, double x, double y, double maxWidth, Paint paint) {
        if (text == null || text.isEmpty() || !finite(x, y) || maxWidth <= 0) return;
        ParsedFont f = currentFont();
        FontFace face = fonts.resolve(f.family());
        if (!(face instanceof OutlineFontFace outline)) return;
        Matrix2D m = state.transform;
        double scale = m.meanScale();
        if (!(scale > 0) || !Double.isFinite(scale)) return;
        float px = f.sizePx();
        FontStyle userStyle = new FontStyle(px, f.weight() >= 600, f.italic(), 0, f.weight(), true);
        double width = face.advance(text, userStyle);
        if (Double.isFinite(maxWidth) && width > maxWidth) {
            px = (float) (px * maxWidth / width);
            width = maxWidth;
        }
        double ox = x + alignOffset(width);
        double asc = face.ascent(userStyle);
        double desc = face.descent(userStyle);
        double baselineShift = switch (state.textBaseline) {
            case "top" -> asc;
            case "hanging" -> asc * 0.8;
            case "middle" -> (asc - desc) / 2;
            case "ideographic", "bottom" -> -desc;
            default -> 0;
        };
        double oy = y + baselineShift;
        double devX = m.applyX(ox, oy);
        double devY = m.applyY(ox, oy);
        float devPx = (float) (px * scale);
        FontStyle devStyle = new FontStyle(devPx, f.weight() >= 600, f.italic(), 0, f.weight(), true);
        float[] positions = outline.positions(text, devStyle);
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            int n = Character.charCount(cp);
            if (!Character.isWhitespace(cp) && cp >= 0x20) {
                var gf = outline.glyphFace(cp, devStyle);
                var bmp = gf.face().rasterize(cp, devPx, gf.syntheticBold(), gf.syntheticItalic());
                if (bmp != null && !bmp.isEmpty()) {
                    int gx = (int) Math.round(devX + positions[i]) + bmp.left();
                    int gy = (int) Math.round(devY) - bmp.top();
                    Rasterizer.Coverage cov = clipToBitmap(new Rasterizer.Coverage(gx, gy, bmp.width(), bmp.height(), bmp.coverage()));
                    if (cov != null) drawCoverage(cov, paint, Matrix2D.IDENTITY);
                }
            }
            i += n;
        }
    }

    private Rasterizer.Coverage clipToBitmap(Rasterizer.Coverage c) {
        int x0 = Math.max(0, c.x());
        int y0 = Math.max(0, c.y());
        int x1 = Math.min(bitmap.width(), c.x() + c.width());
        int y1 = Math.min(bitmap.height(), c.y() + c.height());
        if (x0 >= x1 || y0 >= y1) return null;
        if (x0 == c.x() && y0 == c.y() && x1 - x0 == c.width() && y1 - y0 == c.height()) return c;
        byte[] data = new byte[(x1 - x0) * (y1 - y0)];
        for (int y = y0; y < y1; y++) {
            System.arraycopy(c.data(), (y - c.y()) * c.width() + (x0 - c.x()), data, (y - y0) * (x1 - x0), x1 - x0);
        }
        return new Rasterizer.Coverage(x0, y0, x1 - x0, y1 - y0, data);
    }

    // ------------------------------------------------------------------ 描画の中心

    private void fillPolys(List<Path2D.Polyline> devicePolys, boolean evenOdd, Paint paint) {
        Rasterizer.Coverage cov = Rasterizer.fill(devicePolys, evenOdd, bitmap.width(), bitmap.height());
        if (cov == null && !state.composite.affectsOutsideShape()) return;
        drawCoverage(cov, paint, state.transform);
    }

    /**
     * 被覆率を、塗りと合成演算と影とクリップに従って描く。{@code paintToDevice} は塗りの座標系からデバイスへの変換。
     */
    private void drawCoverage(Rasterizer.Coverage cov, Paint paint, Matrix2D paintToDevice) {
        if (cov != null && hasShadow()) drawShadow(cov);
        composite(cov, paint.shader(paintToDevice), state.globalAlpha);
        modCount++;
    }

    private boolean hasShadow() {
        return (state.shadowColor >>> 24) != 0
                && (state.shadowBlur != 0 || state.shadowOffsetX != 0 || state.shadowOffsetY != 0);
    }

    private void drawShadow(Rasterizer.Coverage cov) {
        double sigma = state.shadowBlur / 2;
        int pad = sigma > 0 ? (int) Math.ceil(sigma * 3) + 1 : 0;
        int ox = (int) Math.round(state.shadowOffsetX);
        int oy = (int) Math.round(state.shadowOffsetY);
        int w = cov.width() + pad * 2;
        int h = cov.height() + pad * 2;
        float[] a = new float[w * h];
        for (int y = 0; y < cov.height(); y++) {
            for (int x = 0; x < cov.width(); x++) {
                a[(y + pad) * w + x + pad] = (cov.data()[y * cov.width() + x] & 0xFF) / 255f;
            }
        }
        if (sigma > 0) blur(a, w, h, sigma);
        byte[] data = new byte[w * h];
        for (int i = 0; i < data.length; i++) data[i] = (byte) Math.min(255, Math.round(a[i] * 255f));
        Rasterizer.Coverage sc = clipToBitmap(new Rasterizer.Coverage(cov.x() - pad + ox, cov.y() - pad + oy, w, h, data));
        if (sc == null) return;
        final int shadow = Pixmap.premultiply(state.shadowColor);
        composite(sc, (x, y) -> shadow, state.globalAlpha);
    }

    /** ガウスぼかしを 3 回の箱ぼかしで近似する。 */
    private static void blur(float[] a, int w, int h, double sigma) {
        int d = Math.max(1, (int) Math.floor(sigma * 3 * Math.sqrt(2 * Math.PI) / 4 + 0.5));
        float[] tmp = new float[Math.max(w, h)];
        for (int pass = 0; pass < 3; pass++) {
            for (int y = 0; y < h; y++) boxLine(a, y * w, 1, w, d, tmp);
            for (int x = 0; x < w; x++) boxLine(a, x, w, h, d, tmp);
        }
    }

    private static void boxLine(float[] a, int start, int step, int n, int d, float[] tmp) {
        int r0 = d / 2;
        int r1 = d - r0 - 1;
        float sum = 0;
        for (int i = -r0; i <= r1; i++) if (i >= 0 && i < n) sum += a[start + i * step];
        float inv = 1f / d;
        for (int i = 0; i < n; i++) {
            tmp[i] = sum * inv;
            int add = i + r1 + 1;
            int sub = i - r0;
            if (add < n) sum += a[start + add * step];
            if (sub >= 0) sum -= a[start + sub * step];
        }
        for (int i = 0; i < n; i++) a[start + i * step] = tmp[i];
    }

    private void composite(Rasterizer.Coverage cov, Paint.Shader shader, double globalAlpha) {
        CompositeOp op = state.composite;
        int w = bitmap.width();
        int h = bitmap.height();
        int[] px = bitmap.pixels();
        byte[] clip = state.clip;
        int alpha256 = (int) Math.round(globalAlpha * 255);
        boolean everywhere = op.affectsOutsideShape();
        int x0 = everywhere ? 0 : cov.x();
        int y0 = everywhere ? 0 : cov.y();
        int x1 = everywhere ? w : cov.x() + cov.width();
        int y1 = everywhere ? h : cov.y() + cov.height();
        boolean solid = shader.isSolid();
        int solidColor = shader.solidColor();
        boolean fast = op == CompositeOp.SOURCE_OVER;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int c = cov == null ? 0 : cov.at(x, y);
                if (c == 0 && !everywhere) continue;
                int idx = y * w + x;
                int mask = clip == null ? 255 : (clip[idx] & 0xFF);
                if (mask == 0) continue;
                int src = c == 0 ? 0 : (solid ? solidColor : shader.colorAt(x + 0.5, y + 0.5));
                int scale = c == 0 ? 0 : Pixmap.div255(c * alpha256);
                if (scale != 255 && src != 0) src = scalePremultiplied(src, scale);
                if (fast && src == 0) continue;
                int dst = px[idx];
                int out = op.apply(src, dst);
                if (mask != 255) out = lerp(dst, out, mask);
                px[idx] = out;
            }
        }
    }

    /** 事前乗算の画素の全成分に {@code k / 255} を掛ける。 */
    static int scalePremultiplied(int pm, int k) {
        if (k >= 255) return pm;
        if (k <= 0) return 0;
        return (Pixmap.div255((pm >>> 24) * k) << 24)
                | (Pixmap.div255(((pm >> 16) & 0xFF) * k) << 16)
                | (Pixmap.div255(((pm >> 8) & 0xFF) * k) << 8)
                | Pixmap.div255((pm & 0xFF) * k);
    }

    private static int lerp(int from, int to, int t) {
        int it = 255 - t;
        return (Pixmap.div255((from >>> 24) * it + (to >>> 24) * t) << 24)
                | (Pixmap.div255(((from >> 16) & 0xFF) * it + ((to >> 16) & 0xFF) * t) << 16)
                | (Pixmap.div255(((from >> 8) & 0xFF) * it + ((to >> 8) & 0xFF) * t) << 8)
                | Pixmap.div255((from & 0xFF) * it + (to & 0xFF) * t);
    }

    private static boolean finite(double a, double b) {
        return Double.isFinite(a) && Double.isFinite(b);
    }
}
