package com.nishiyu.enderframelib.core.paint.gpu;

import com.nishiyu.enderframelib.core.paint.model.ShaderGradient;
import com.nishiyu.enderframelib.core.paint.gpu.GpuSink.Clip;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import com.nishiyu.enderframelib.core.raster.Pixmap;
import com.nishiyu.enderframelib.core.text.font.GlyphBitmap;

/**
 * 図形・字形・画像を「1 インスタンス = 64 テクセル（{@code float[256]}）」のデータへ詰める、描画 API に依存しないパッカー。
 * OpenGL（データテクスチャ）・Vulkan（SSBO）・独自エンジンのどれでも、{@link #data} を {@link #count} 行ぶん転送して
 * インスタンス描画するだけで同じ絵になる（シェーダー側が同じレイアウトを読む前提）。
 *
 * <p>API との接点は {@link Host} だけ:
 * <ul>
 *   <li>{@link Host#beforeShape}: 図形を積む前に、描画順を守るため先に描き切るべきものを描かせる</li>
 *   <li>{@link Host#flushBatch}: 溜めた行が上限に達した。バックエンドは {@link #data} を転送・描画して {@link #clear} を呼ぶ</li>
 *   <li>{@link Host#touch}: 層（オフスクリーン）に描く範囲を記録させる（{@link Host#tracksBounds} が true のときだけ呼ぶ）</li>
 * </ul>
 *
 * <p>テクセルの割り当て: 0 範囲 / 1-2 姿勢 / 3 モード・穴・クリップ数 / 4-9 矩形と角 / 10-14 枠線の太さと色 / 15 色 /
 * 16 影・繰り返し / 17-20 グラデーション / 21-23 字形・画像 / 24-43 クリップ（5 テクセル × 4）/ 44-47 破線 / 48 画像の標本間隔。
 * スレッドセーフではない。
 */
public final class GpuBatchPacker {

    /** 1 インスタンスのテクセル数（データテクスチャの幅）。 */
    public static final int STRIDE = 64;
    /** 1 回の draw にまとめる最大数（データテクスチャの高さ）。 */
    public static final int MAX_BATCH = 512;

    /** 図形のモード（シェーダーの {@code mode} と同じ値）。 */
    public static final int MODE_FILL = 0, MODE_STROKE = 1, MODE_GRADIENT = 2, MODE_OUTER_SHADOW = 3, MODE_INSET_SHADOW = 4,
            MODE_GLYPH = 5, MODE_IMAGE = 6, MODE_CIRCLE_BORDER = 8, MODE_GLYPH_TRANSFORMED = 9, MODE_GLYPH_STROKE = 10;

    /** バックエンド側の受け口。 */
    public interface Host {
        /** 図形を積む前に呼ぶ。描画順を守るために、先に描き切るべきものがあれば描く。 */
        void beforeShape();

        /** 行が上限に達した。溜めた行を転送して描き、{@link GpuBatchPacker#clear} を呼ぶこと。 */
        void flushBatch();

        /** 層に描く範囲を記録したいか（層が開いているときだけ true でよい）。 */
        default boolean tracksBounds() {
            return false;
        }

        /** 層に描く範囲（デバイス画素の外接矩形）。 */
        default void touch(float x0, float y0, float x1, float y1) {}
    }

    private final Host host;
    private final float[] batch = new float[STRIDE * MAX_BATCH * 4];
    private int batchN;
    private int base;
    private long committed;
    private int curMode, curInnerEmpty, curHole, curClipN;
    private float curSigma, curRepO, curRepP;

    public GpuBatchPacker(Host host) {
        this.host = host;
    }

    /** 溜まっている行の数。 */
    public int count() {
        return batchN;
    }

    /** 行データ（先頭から {@code count() * STRIDE * 4} 個の float が有効）。 */
    public float[] data() {
        return batch;
    }

    /** 溜めた行を捨てる（転送し終わったら呼ぶ）。 */
    public void clear() {
        batchN = 0;
        usedMax = -1;
    }

    /**
     * 溜めた行を {@code device} へ転送して描き、溜めを空にする。何も溜まっていなければ何もしない。
     * 順序は固定: 溜めを空にする → {@link BatchDevice#uploadInstances} → {@link BatchDevice#drawInstances}
     * （行データは次に積むまで書き換わらないので、空にしてから転送してよい）。
     *
     * @return 描いたインスタンスの数
     */
    public int flushTo(BatchDevice device) {
        int n = batchN;
        if (n == 0) return 0;
        batchN = 0;
        int hi = usedMax;
        usedMax = -1;
        device.uploadInstances(batch, n, Math.min(hi, CORE_TEXELS - 1) + 1, Math.max(hi - CORE_TEXELS + 1, 0));
        device.drawInstances(n);
        return n;
    }

    /** 共通部（テクセル 0..23）の幅。24 以降（クリップ・破線・画像の標本間隔）は拡張部として別に転送できる。 */
    public static final int CORE_TEXELS = 24;
    /** 拡張部（テクセル 24..48）の最大の幅。 */
    public static final int EXT_TEXELS = 25;

    /** 今のバッチで書いた最大のテクセル番号（空なら -1）。 */
    private int usedMax = -1;

    /** これまでに積んだ行の総数（{@link #clear} では戻らない）。 */
    public long committedTotal() {
        return committed;
    }

    // ------------------------------------------------------------------ 図形

    public boolean fillRoundRect(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                 int argb, Clip[] clips, float opacity) {
        if (!beginShape(pose, x0, y0, x1, y1, clips, opacity)) return true;
        curMode = MODE_FILL;
        setRect(4, rx, ry, x0, y0, x1, y1);
        setColor(15, argb);
        commit();
        return true;
    }

    public boolean strokeRoundBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                     float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                     int[] colors, float[] widths, Clip[] clips, float opacity) {
        if (!beginShape(pose, x0, y0, x1, y1, clips, opacity)) return true;
        curMode = MODE_STROKE;
        setRect(4, rx, ry, x0, y0, x1, y1);
        setRect(7, irx, iry, ix0, iy0, ix1, iy1);
        curInnerEmpty = innerEmpty ? 1 : 0;
        put(10, widths[0], widths[1], widths[2], widths[3]);
        for (int i = 0; i < 4; i++) setColor(11 + i, colors[i]);
        commit();
        return true;
    }

    /**
     * 破線・点線。{@code period} が 0 以下なら詰めずに {@code false}（呼び出し側が別の経路へ回す）。
     * 姿勢が壊れていて何も積まれなかったときは {@code true}（描くものが無い）。
     */
    public boolean strokeDashedBorder(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                      float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                                      int[] colors, float[] widths,
                                      float bx, float by, float cxm, float cym, float[] pcx, float[] pcy,
                                      float period, float dash, boolean round, float thickness,
                                      Clip[] clips, float opacity) {
        if (!(period > 0f)) return false;
        long before = committed;
        strokeRoundBorder(pose, x0, y0, x1, y1, rx, ry, ix0, iy0, ix1, iy1, irx, iry, innerEmpty, colors, widths, clips, opacity);
        if (committed == before) return true; // 姿勢が壊れていて何も積まれていない
        // 直前に積んだ行を破線にする
        base = (batchN - 1) * STRIDE * 4;
        put(44, bx, by, cxm, cym);
        put(45, pcx[0], pcx[1], pcx[2], pcx[3]);
        put(46, pcy[0], pcy[1], pcy[2], pcy[3]);
        put(47, period, dash, round ? 1f : 0f, thickness);
        batch[base + 3 * 4 + 2] = 1f; // 破線の印（3 番目のテクセルの穴の欄）
        return true;
    }

    public boolean strokeCircleBorder(Matrix2D pose, float cx, float cy, float outerR, float innerR,
                                      float bx0, float by0, float bx1, float by1, int[] colors, Clip[] clips, float opacity) {
        if (!beginShapeIn(pose, bx0, by0, bx1, by1, clips, opacity)) return true;
        curMode = MODE_CIRCLE_BORDER;
        put(4, cx, cy, outerR, innerR);
        for (int i = 0; i < 4; i++) setColor(11 + i, colors[i]);
        commit();
        return true;
    }

    public boolean fillGradient(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                                ShaderGradient g, Clip[] clips, float opacity) {
        if (!beginShape(pose, x0, y0, x1, y1, clips, opacity)) return true;
        curMode = MODE_GRADIENT;
        setRect(4, rx, ry, x0, y0, x1, y1);
        float[] a = g.gradA(), b = g.gradB();
        put(17, a[0], a[1], a[2], a[3]);
        put(18, b[0], b[1], b[2], b[3]);
        curRepO = g.repeatOrigin();
        curRepP = g.repeatPeriod();
        setColor(19, g.color0());
        setColor(20, g.color1());
        commit();
        return true;
    }

    public boolean outerShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1,
                               float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry, float sigma,
                               float[] hole, float[] hrx, float[] hry, int argb, Clip[] clips, float opacity) {
        if (!beginShapeIn(pose, qx0, qy0, qx1, qy1, clips, opacity)) return true;
        curMode = MODE_OUTER_SHADOW;
        setRect(4, srx, sry, sx0, sy0, sx1, sy1);
        curSigma = sigma;
        curHole = hole != null ? 1 : 0;
        if (hole != null) setRect(7, hrx, hry, hole[0], hole[1], hole[2], hole[3]);
        setColor(15, argb);
        commit();
        return true;
    }

    public boolean insetShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float[] rx, float[] ry,
                               float sigma, float[] hole, float[] hrx, float[] hry, int argb, Clip[] clips, float opacity) {
        if (!beginShapeIn(pose, qx0 - 1, qy0 - 1, qx1 + 1, qy1 + 1, clips, opacity)) return true;
        curMode = MODE_INSET_SHADOW;
        setRect(4, rx, ry, qx0, qy0, qx1, qy1);
        curSigma = sigma;
        setRect(7, hrx, hry, hole[0], hole[1], hole[2], hole[3]);
        setColor(15, argb);
        commit();
        return true;
    }

    // ------------------------------------------------------------------ 字形・画像（積めたら true、描くものが無ければ false）

    /** 字形（デバイス画素の格子に揃えた文字）。{@code slot} は {@link AtlasPacker#slot} の結果。 */
    public boolean glyph(GlyphBitmap g, int[] slot, int bx, int by, int argb, Clip[] clips, float opacity) {
        if (!beginShapeIn(Matrix2D.IDENTITY, bx, by, bx + g.width(), by + g.height(), clips, opacity)) return false;
        curMode = MODE_GLYPH;
        put(4, bx, by, bx + g.width(), by + g.height());
        put(21, slot[0], slot[1], g.width(), g.height());
        setColor(15, argb);
        commit();
        return true;
    }

    /**
     * 字形の縁取り（デバイス画素の格子に揃えた文字）。{@code sdf} は SDF の字形（128 = 輪郭、内側が大きい、
     * {@code spread} 画素で 128 段階）で、{@code half} は縁取りの幅の半分（画素）。帯は輪郭からの距離 {@code |d| <= half}。
     */
    public boolean glyphStroke(GlyphBitmap sdf, int[] slot, int bx, int by, float half, int argb, Clip[] clips, float opacity) {
        if (!beginShapeIn(Matrix2D.IDENTITY, bx, by, bx + sdf.width(), by + sdf.height(), clips, opacity)) return false;
        curMode = MODE_GLYPH_STROKE;
        put(4, bx, by, bx + sdf.width(), by + sdf.height());
        put(21, slot[0], slot[1], sdf.width(), sdf.height());
        put(22, half, 0, 0, 0);
        setColor(15, argb);
        commit();
        return true;
    }

    /** 回転などの姿勢の下の字形。 */
    public boolean glyphTransformed(Matrix2D pose, GlyphBitmap g, int[] slot, float ox, float oy, float s,
                                    float bx0, float by0, float bx1, float by1, int argb, Clip[] clips, float opacity) {
        if (!beginShapeIn(pose, bx0, by0, bx1, by1, clips, opacity)) return false;
        curMode = MODE_GLYPH_TRANSFORMED;
        put(21, slot[0], slot[1], g.width(), g.height());
        put(22, ox, oy, s, 0);
        put(23, g.left(), g.top(), 0, 0);
        setColor(15, argb);
        commit();
        return true;
    }

    /** 画像。テクスチャの束縛はバックエンドが、積んだ（{@code true}）後に行う。 */
    public boolean image(Matrix2D pose, Pixmap image, float bx0, float by0, float bx1, float by1,
                         float px0, float py0, float sx, float sy,
                         float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                         int taps, float stepX, float stepY, Clip[] clips, float opacity) {
        return image(pose, image.width(), image.height(), bx0, by0, bx1, by1, px0, py0, sx, sy, x0, y0, x1, y1, rx, ry,
                taps, stepX, stepY, clips, opacity);
    }

    /** 画素の大きさだけを渡す版（画素を持たない外部テクスチャ用）。 */
    public boolean image(Matrix2D pose, int imageWidth, int imageHeight, float bx0, float by0, float bx1, float by1,
                         float px0, float py0, float sx, float sy,
                         float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                         int taps, float stepX, float stepY, Clip[] clips, float opacity) {
        // 回転の下では、辺の斜めの画素が箱の外側にもはみ出す（CPU 版は外接矩形の全画素を評価する）。1 画素分広げて合わせる
        float m = 1f / Math.max((float) pose.meanScale(), 0.01f);
        if (!beginShapeIn(pose, bx0 - m, by0 - m, bx1 + m, by1 + m, clips, opacity)) return false;
        curMode = MODE_IMAGE;
        setRect(4, rx, ry, x0, y0, x1, y1);
        put(22, px0, py0, sx, sy);
        put(23, imageWidth, imageHeight, taps, 0);
        put(48, stepX, stepY, 0, 0);
        commit();
        return true;
    }

    // ------------------------------------------------------------------ 詰め方

    /** 図形を描く共通の準備（余白を 1 画素ぶん足した版）。描くものが無い（姿勢が壊れている）なら {@code false}。 */
    private boolean beginShape(Matrix2D pose, float x0, float y0, float x1, float y1, Clip[] clips, float opacity) {
        float m = 1f / Math.max((float) pose.meanScale(), 0.01f);
        return beginShapeIn(pose, x0 - m, y0 - m, x1 + m, y1 + m, clips, opacity);
    }

    /** {@link #beginShape} の、描く範囲（余白込み）を直接渡す版。 */
    private boolean beginShapeIn(Matrix2D pose, float bx0, float by0, float bx1, float by1, Clip[] clips, float opacity) {
        host.beforeShape();
        if (!pose.isFinite() || pose.invert() == null || opacity <= 0f) return false;
        if (host.tracksBounds()) { // 層に描く範囲（四隅を姿勢で写した外接矩形）を記録する
            double ax = pose.applyX(bx0, by0), ay = pose.applyY(bx0, by0), bx = pose.applyX(bx1, by0), by = pose.applyY(bx1, by0);
            double cx = pose.applyX(bx0, by1), cy = pose.applyY(bx0, by1), dx = pose.applyX(bx1, by1), dy = pose.applyY(bx1, by1);
            host.touch((float) Math.min(Math.min(ax, bx), Math.min(cx, dx)), (float) Math.min(Math.min(ay, by), Math.min(cy, dy)),
                    (float) Math.max(Math.max(ax, bx), Math.max(cx, dx)), (float) Math.max(Math.max(ay, by), Math.max(cy, dy)));
        }
        if (batchN == MAX_BATCH) host.flushBatch();
        base = batchN * STRIDE * 4;
        // 前のバッチの残りを持ち越さない（使わないテクセルが 0 で揃うので、フレーム間の行の比較が内容だけで決まる）
        java.util.Arrays.fill(batch, base, base + STRIDE * 4, 0f);
        curMode = 0;
        curInnerEmpty = 0;
        curHole = 0;
        curSigma = 0f;
        curRepO = 0f;
        curRepP = 0f;
        put(0, bx0, by0, bx1, by1);
        put(1, (float) pose.a(), (float) pose.b(), (float) pose.c(), (float) pose.d());
        put(2, (float) pose.e(), (float) pose.f(), (float) pose.meanScale(), opacity);
        int n = clips.length;
        curClipN = n;
        for (int i = 0; i < n; i++) {
            Clip c = clips[i];
            Matrix2D v = c.inv();
            int t = 24 + i * 5;
            put(t, (float) v.a(), (float) v.b(), (float) v.c(), (float) v.d());
            put(t + 1, (float) v.e(), (float) v.f(), c.scale(), 0);
            put(t + 2, c.x0(), c.y0(), c.x1(), c.y1());
            put(t + 3, c.rx()[0], c.rx()[1], c.rx()[2], c.rx()[3]);
            put(t + 4, c.ry()[0], c.ry()[1], c.ry()[2], c.ry()[3]);
        }
        return true;
    }

    private void commit() {
        put(3, curMode, curInnerEmpty, curHole, curClipN);
        put(16, curSigma, curRepO, curRepP, 0);
        batchN++;
        committed++;
    }

    private void setRect(int t, float[] rx, float[] ry, float x0, float y0, float x1, float y1) {
        put(t, x0, y0, x1, y1);
        put(t + 1, rx[0], rx[1], rx[2], rx[3]);
        put(t + 2, ry[0], ry[1], ry[2], ry[3]);
    }

    private void setColor(int t, int argb) {
        put(t, ((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
    }

    private void put(int texel, float x, float y, float z, float w) {
        if (texel > usedMax) usedMax = texel;
        int i = base + texel * 4;
        batch[i] = x;
        batch[i + 1] = y;
        batch[i + 2] = z;
        batch[i + 3] = w;
    }
}
