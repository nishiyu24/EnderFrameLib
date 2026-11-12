package com.nishiyu.enderframelib.core.paint.gl;

import com.nishiyu.enderframelib.core.kernel.gpu.PropertyTrees;
import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import com.nishiyu.enderframelib.core.raster.Matrix2D;
import org.lwjgl.opengl.GL11;

/**
 * バックエンドが受け取る単色の角丸矩形を、{@link GlTreeStore} のプリミティブとして GPU へ流す窓口（新経路）。
 *
 * <p><b>位置による差分</b>: そのフレームで {@code n} 番目に受け付けた矩形はスロット {@code n} に書く。
 * 前のフレームと同じ並び・同じ内容ならレコードは 1 バイトも変わらず、GPU への転送は 0（永続マップなら元から 0）。
 * 描画順の表は恒等（{@code order[k] = k}）で一度だけ書く。</p>
 *
 * <p><b>受け付ける矩形</b>: クリップが上限以内・不透明度 &gt; 0・姿勢が拡縮・回転・平行移動だけ（せん断なし、拡大率は正）・
 * 容量に空きがある。角が丸いなら拡大率が縦横等しいこと。それ以外は {@code false} を返し、呼び出し側（バックエンド）は
 * 図形を断って CPU に描かせる（旧 {@code GpuBatchPacker} 経路は撤去済み）。</p>
 *
 * <p><b>描画順</b>: 溜めた矩形（run）は、他の何かを描く直前に {@link #drain} で吐く（呼び出し側の責務）。</p>
 */
final class GlRectStream implements AutoCloseable {
    private static final float GRID_EPS = 1e-3f;

    private final GlTreeStore store;
    private final GlRectRenderer renderer;
    private final PropertyTrees trees;
    private static final int CLIP_CAP = 1024, KEY = 20;
    private final int capacity, clipCapacity;
    /** そのフレームで書いたクリップの内容（重複排除の鍵）と、鍵 → スロットのハッシュ表（開地址法）。 */
    private final float[] clipKeys;
    private final int[] clipTable;
    private int clipCount;
    /** 使ったスロット数（主スロット + 続きスロット）と、描画順の表に載せた数（= 描く図形の数）。 */
    private int slots;
    private int count;
    private int runStart;
    private long accepted;
    private long drawCalls;
    /** 診断: 受け付けなかった理由別の数（0 pose / 1 格子 / 2 角 / 3 容量・不透明度）。 */
    final long[] rejects = new long[4];
    long clipRejects;
    /** スロット・クリップの容量が足りず、描く図形を CPU へ回した回数の累計（フレームをまたいで増える。保持した層の記録の安全判定に使う）。 */
    long overflows;

    GlRectStream(int requestedCapacity) {
        // バッファテクスチャの大きさの上限（GL 3.1 の最小保証は 65536 テクセル）。1 プリミティブ = 半精度で 64 テクセル
        int maxTexels = GL11.glGetInteger(org.lwjgl.opengl.GL31.GL_MAX_TEXTURE_BUFFER_SIZE);
        this.capacity = Math.max(16, Math.min(requestedCapacity, maxTexels / 64));
        this.clipCapacity = Math.min(CLIP_CAP, Math.max(4, maxTexels / 64));
        this.store = new GlTreeStore(capacity, clipCapacity, capacity, capacity, true);
        this.clipKeys = new float[clipCapacity * KEY];
        this.clipTable = new int[Integer.highestOneBit(clipCapacity * 2 - 1) << 1];
        this.trees = store.trees;
        this.renderer = new GlRectRenderer();
        // 全スロットを確保して初期化する（親・スクロール・クリップ・効果の参照を「なし」にそろえる）
        for (int k = 0; k < clipCapacity; k++) trees.clips.allocate();
        for (int k = 0; k < capacity; k++) {
            trees.addTransform(PropertyTrees.NONE, 0, 0, 1, 1, 0);
            trees.addEffect(PropertyTrees.NONE, 1f, 0, 0, 0f);
            trees.addPrimitive();
        }
        store.flush();
    }

    boolean isPersistent() { return store.isPersistent(); }
    int capacity() { return capacity; }
    int pending() { return count - runStart; }
    long accepted() { return accepted; }
    long drawCalls() { return drawCalls; }
    int frameCount() { return count; }

    /** フレームの頭。永続マップなら前フレームの GPU 読み出しの終わりを待ち、受け付けの番号を 0 に戻す。 */
    void begin() {
        store.beginFrame();
        slots = 0;
        count = 0;
        runStart = 0;
        clipCount = 0;
        java.util.Arrays.fill(clipTable, -1);
    }

    /** 図形の種類ごとの受け付け条件。 */
    private static final int FILL = 0, SHADOW_OUTER = 1; // 種類（現在は受け付け条件に差が無い）
    private static final float[] ZERO4 = new float[4];
    /** 診断・切り分け用: 新経路に載せる図形の種類（ビット 0 塗り・1 枠線・2 外側の影・3 内側の影・4 グラデーション・5 字形・6 画像・7 円の枠・8 変形した字形・9 字形の縁取り・10 破線枠・11 外部テクスチャ）。 */
    static volatile int opMask = 0xFFF;

    /** この塗り・枠線を新経路で描けるか（積みはしない）。縁が格子に乗ること。 */
    boolean accepts(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        return accepts(pose, x0, y0, x1, y1, rx, ry, clips, opacity, FILL, 1);
    }

    /** 続きスロットを使う図形（グラデーション・内側の影）用。 */
    boolean acceptsWithExt(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        return accepts(pose, x0, y0, x1, y1, rx, ry, clips, opacity, FILL, 2);
    }

    /** 外側の影: 描く範囲（quad）は縁が格子に乗らなくてよい（従来のシェーダーも縁を滑らかにしない）。 */
    boolean acceptsOuterShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, GpuClip[] clips, float opacity) {
        return accepts(pose, qx0, qy0, qx1, qy1, ZERO4, ZERO4, clips, opacity, SHADOW_OUTER, 2);
    }

    private boolean accepts(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry, GpuClip[] clips,
                            float opacity, int kind, int slotsNeeded) {
        if (clips != null && clips.length > 0 && (clips.length > PropertyTrees.MAX_CLIPS_PER_PRIMITIVE || clipCount + clips.length > clipCapacity)) {
            rejects[3]++;
            if (clips.length <= PropertyTrees.MAX_CLIPS_PER_PRIMITIVE) overflows++; // 容量の不足だけ数える（1 図形のクリップ数の上限は構造の制約）
            return false;
        }
        if (slots + slotsNeeded > capacity) { rejects[3]++; overflows++; return false; }
        if (!(opacity > 0f) || !pose.isFinite() || !(x1 > x0) || !(y1 > y0)) { rejects[3]++; return false; }
        if (!decompose(pose)) { rejects[0]++; return false; }
        boolean rounded = false;
        for (int i = 0; i < 4; i++) if (rx[i] > 0f || ry[i] > 0f) rounded = true;
        // 円・楕円の角は縦横の拡大率が等しいときだけ（距離を局所の単位で測るため）
        if (rounded && Math.abs(Math.abs(trs[3]) - trs[2]) > 1e-4f * trs[2]) { rejects[2]++; return false; }
        return true;
    }

    /** 姿勢 = 平行移動 * 回転 * 拡縮（傾きなし）なら {@link #trs} に (tx, ty, sx, sy, rot) を書いて true。 */
    private final float[] trs = new float[5];

    private boolean decompose(Matrix2D pose) {
        double a = pose.a(), b = pose.b(), c = pose.c(), d = pose.d();
        double sx = Math.hypot(a, b);
        if (!(sx > 1e-9)) return false;
        double sy = (a * d - b * c) / sx;
        double rot = Math.atan2(b, a);
        double cs = Math.cos(rot), sn = Math.sin(rot);
        double tol = 1e-5 * Math.max(sx, Math.abs(sy));
        if (Math.abs(c + sn * sy) > tol || Math.abs(d - cs * sy) > tol || !(Math.abs(sy) > 1e-9)) return false; // 傾き
        trs[0] = (float) pose.e();
        trs[1] = (float) pose.f();
        trs[2] = (float) sx;
        trs[3] = (float) sy;
        trs[4] = (float) rot;
        return true;
    }

    /** 字形（デバイス画素の格子に揃えた文字）。姿勢は恒等。 */
    boolean acceptsGlyph(int bx, int by, int w, int h, GpuClip[] clips, float opacity) {
        return accepts(Matrix2D.IDENTITY, bx, by, bx + w, by + h, ZERO4, ZERO4, clips, opacity, SHADOW_OUTER, 1);
    }

    void pushGlyph(int bx, int by, int w, int h, int atlasX, int atlasY, int argb, GpuClip[] clips, float opacity) {
        int k = open(Matrix2D.IDENTITY, PropertyTrees.OP_GLYPH, bx, by, bx + w, by + h, argb, clips, opacity, 0f);
        trees.setPrimitiveAtlas(k, atlasX, atlasY, w, h);
    }

    /** 続きスロットを使う、姿勢を持つ図形（変形した字形・縁取り・円の枠）。 */
    boolean acceptsPosed(Matrix2D pose, float x0, float y0, float x1, float y1, GpuClip[] clips, float opacity) {
        return accepts(pose, x0, y0, x1, y1, ZERO4, ZERO4, clips, opacity, SHADOW_OUTER, 2);
    }

    /** 画像は形の角（rx/ry）が円でなくても、拡大率が縦横等しければよい。 */
    boolean acceptsImage(Matrix2D pose, float bx0, float by0, float bx1, float by1, float[] rx, float[] ry, GpuClip[] clips, float opacity) {
        return accepts(pose, bx0, by0, bx1, by1, rx, ry, clips, opacity, SHADOW_OUTER, 2);
    }

    void pushGlyphStroke(int bx, int by, int w, int h, int atlasX, int atlasY, float half, int argb, GpuClip[] clips, float opacity) {
        open(Matrix2D.IDENTITY, PropertyTrees.OP_GLYPH_STROKE, bx, by, bx + w, by + h, argb, clips, opacity, 0f);
        int ext = slots++;
        trees.setExtFloat(ext, 0, atlasX);
        trees.setExtFloat(ext, 1, atlasY);
        trees.setExtFloat(ext, 2, w);
        trees.setExtFloat(ext, 3, h);
        trees.setExtFloat(ext, 4, half);
    }

    void pushGlyphTransformed(Matrix2D pose, float bx0, float by0, float bx1, float by1, int atlasX, int atlasY, int w, int h,
                              float ox, float oy, float s, float left, float top, int argb, GpuClip[] clips, float opacity) {
        open(pose, PropertyTrees.OP_GLYPH_TRANSFORMED, bx0, by0, bx1, by1, argb, clips, opacity, 0f);
        int ext = slots++;
        trees.setExtFloat(ext, 0, atlasX);
        trees.setExtFloat(ext, 1, atlasY);
        trees.setExtFloat(ext, 2, w);
        trees.setExtFloat(ext, 3, h);
        trees.setExtFloat(ext, 4, ox);
        trees.setExtFloat(ext, 5, oy);
        trees.setExtFloat(ext, 6, s);
        trees.setExtFloat(ext, 8, left);
        trees.setExtFloat(ext, 9, top);
    }

    void pushCircleBorder(Matrix2D pose, float cx, float cy, float outerR, float innerR,
                          float bx0, float by0, float bx1, float by1, int[] colors, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_CIRCLE_BORDER, bx0, by0, bx1, by1, colors[0], clips, opacity, 0f);
        trees.setPrimitiveColors(k, colors[0], colors[1], colors[2], colors[3]);
        int ext = slots++;
        trees.setExtFloat(ext, 0, cx);
        trees.setExtFloat(ext, 1, cy);
        trees.setExtFloat(ext, 2, outerR);
        trees.setExtFloat(ext, 3, innerR);
    }

    void pushImage(Matrix2D pose, float bx0, float by0, float bx1, float by1, float px0, float py0, float sx, float sy,
                   float x0, float y0, float x1, float y1, float[] rx, float[] ry, int imgW, int imgH,
                   int taps, float stepX, float stepY, GpuClip[] clips, float opacity) {
        open(pose, PropertyTrees.OP_IMAGE, bx0, by0, bx1, by1, 0, clips, opacity, 0f);
        int ext = slots++;
        trees.setExtFloat(ext, 0, x0);
        trees.setExtFloat(ext, 1, y0);
        trees.setExtFloat(ext, 2, x1);
        trees.setExtFloat(ext, 3, y1);
        trees.setExtFloat(ext, 4, px0);
        trees.setExtFloat(ext, 5, py0);
        trees.setExtFloat(ext, 6, sx);
        trees.setExtFloat(ext, 7, sy);
        trees.setExtFloat(ext, 8, imgW);
        trees.setExtFloat(ext, 9, imgH);
        trees.setExtFloat(ext, 10, taps);
        trees.setExtFloat(ext, 12, stepX);
        trees.setExtFloat(ext, 13, stepY);
        for (int i = 0; i < 4; i++) {
            trees.setExtFloat(ext, 16 + i, rx[i]);
            trees.setExtFloat(ext, 20 + i, ry[i]);
        }
    }


    /** 主スロットを確保し、変換・効果・描画順・クリップ・矩形・色 0 を書く。 */
    private int open(Matrix2D pose, int opcode, float x0, float y0, float x1, float y1, int argb, GpuClip[] clips, float opacity,
                     float margin) {
        int k = slots++;
        decompose(pose); // accepts が true を返した姿勢なので必ず成功する
        trees.setTransformTarget(k, trs[0], trs[1], trs[2], trs[3], trs[4]);
        int ef = PropertyTrees.NONE;
        if (opacity < 1f) {
            trees.setEffect(k, PropertyTrees.NONE, opacity, 0, 0, 0f);
            ef = k;
        }
        trees.setPrimitive(k, opcode, k, PropertyTrees.NONE, ef, x0, y0, x1 - x0, y1 - y0, argb, 0);
        trees.setPrimitiveMargin(k, margin);
        writeClips(k, clips);
        trees.order.putInt(count++, 0, k); // 描画順の表には主スロットだけを載せる
        accepted++;
        return k;
    }

    /** {@link #accepts} が true を返した矩形を run に積む。 */
    void push(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry, int argb, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_FILL, x0, y0, x1, y1, argb, clips, opacity, 1f / Math.max((float) pose.meanScale(), 0.01f));
        trees.setPrimitiveRadiiXY(k, rx, ry);
    }

    /** 角丸の枠線を run に積む（{@link #accepts} が外側の矩形で true を返したもの）。色・幅は上・右・下・左。 */
    void pushStroke(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                    float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                    int[] colors, float[] widths, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_STROKE, x0, y0, x1, y1, colors[0], clips, opacity, 1f / Math.max((float) pose.meanScale(), 0.01f));
        trees.setPrimitiveColors(k, colors[0], colors[1], colors[2], colors[3]);
        trees.setPrimitiveRadiiXY(k, rx, ry);
        trees.setPrimitiveInner(k, ix0, iy0, ix1, iy1, irx, iry, innerEmpty ? PropertyTrees.FLAG_INNER_EMPTY : 0);
        trees.setPrimitiveBorderWidths(k, widths[0], widths[1], widths[2], widths[3]);
    }

    /** 破線・点線の枠線（{@link #acceptsWithExt} が外側の矩形で true を返したもの）。 */
    void pushStrokeDashed(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                          float ix0, float iy0, float ix1, float iy1, float[] irx, float[] iry, boolean innerEmpty,
                          int[] colors, float[] widths, float bx, float by, float cxm, float cym, float[] pcx, float[] pcy,
                          float period, float dash, boolean round, float thickness, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_STROKE, x0, y0, x1, y1, colors[0], clips, opacity, 1f / Math.max((float) pose.meanScale(), 0.01f));
        trees.setPrimitiveColors(k, colors[0], colors[1], colors[2], colors[3]);
        trees.setPrimitiveRadiiXY(k, rx, ry);
        trees.setPrimitiveInner(k, ix0, iy0, ix1, iy1, irx, iry,
                (innerEmpty ? PropertyTrees.FLAG_INNER_EMPTY : 0) | PropertyTrees.FLAG_DASHED);
        trees.setPrimitiveBorderWidths(k, widths[0], widths[1], widths[2], widths[3]);
        trees.setExtDash(slots++, bx, by, cxm, cym, pcx, pcy, period, dash, round, thickness);
    }


    /** グラデーション（{@link #acceptsWithExt} が true を返したもの）。 */
    void pushGradient(Matrix2D pose, float x0, float y0, float x1, float y1, float[] rx, float[] ry,
                      com.nishiyu.enderframelib.core.paint.model.ShaderGradient g, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_GRADIENT, x0, y0, x1, y1, 0, clips, opacity, 1f / Math.max((float) pose.meanScale(), 0.01f));
        trees.setPrimitiveRadiiXY(k, rx, ry);
        trees.setPrimitiveGradient(slots++, g.gradA(), g.gradB(), g.repeatOrigin(), g.repeatPeriod(), g.color0(), g.color1());
    }

    /** 外側の影（{@link #acceptsOuterShadow} が true を返したもの）。 */
    void pushOuterShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1,
                         float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry, float sigma,
                         float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
        open(pose, PropertyTrees.OP_OUTER_SHADOW, qx0, qy0, qx1, qy1, argb, clips, opacity, 0f);
        trees.setPrimitiveShadow(slots++, sx0, sy0, sx1, sy1, srx, sry, sigma, hole, hrx, hry);
    }

    /** 内側の影（{@link #acceptsWithExt} が箱の矩形で true を返したもの）。箱は (qx0,qy0)-(qx1,qy1)、角は rx/ry。 */
    void pushInsetShadow(Matrix2D pose, float qx0, float qy0, float qx1, float qy1, float[] rx, float[] ry,
                         float sigma, float[] hole, float[] hrx, float[] hry, int argb, GpuClip[] clips, float opacity) {
        int k = open(pose, PropertyTrees.OP_INSET_SHADOW, qx0, qy0, qx1, qy1, argb, clips, opacity, 1f) // 従来経路の内側の影と同じ 1 単位の余白
        ;
        trees.setPrimitiveRadiiXY(k, rx, ry);
        trees.setPrimitiveShadow(slots++, qx0, qy0, qx1, qy1, rx, ry, sigma, hole, hrx, hry);
    }

    /** プリミティブ {@code k} にクリップを結ぶ（同じ内容のクリップはそのフレームで 1 スロットを共有する）。 */
    private void writeClips(int k, GpuClip[] clips) {
        int n = clips == null ? 0 : clips.length;
        int a = 0, b = 0, c = 0, d = 0;
        for (int i = 0; i < n; i++) {
            int id = clipSlot(clips[i]);
            switch (i) { case 0 -> a = id; case 1 -> b = id; case 2 -> c = id; default -> d = id; }
        }
        trees.setPrimitiveClips(k, n, a, b, c, d);
    }

    private int clipSlot(GpuClip c) {
        Matrix2D v = c.inv();
        float[] key = clipKeys;
        // 鍵: inv a b c d e f, scale, x0 y0 x1 y1, rx[4], ry[4]
        float f0 = (float) v.a(), f1 = (float) v.b(), f2 = (float) v.c(), f3 = (float) v.d(), f4 = (float) v.e(), f5 = (float) v.f();
        int h = 17;
        h = h * 31 + Float.floatToIntBits(f0); h = h * 31 + Float.floatToIntBits(f1); h = h * 31 + Float.floatToIntBits(f2);
        h = h * 31 + Float.floatToIntBits(f3); h = h * 31 + Float.floatToIntBits(f4); h = h * 31 + Float.floatToIntBits(f5);
        h = h * 31 + Float.floatToIntBits(c.x0()) + 7 * Float.floatToIntBits(c.y0()) + 13 * Float.floatToIntBits(c.x1()) + 29 * Float.floatToIntBits(c.y1());
        h ^= h >>> 15;
        int mask = clipTable.length - 1;
        int i = h & mask;
        while (clipTable[i] >= 0) {
            int slot = clipTable[i];
            int o = slot * KEY;
            if (key[o] == f0 && key[o + 1] == f1 && key[o + 2] == f2 && key[o + 3] == f3 && key[o + 4] == f4 && key[o + 5] == f5
                    && key[o + 6] == c.scale() && key[o + 7] == c.x0() && key[o + 8] == c.y0() && key[o + 9] == c.x1() && key[o + 10] == c.y1()
                    && same(key, o + 11, c.rx()) && same(key, o + 15, c.ry())) {
                return slot;
            }
            i = (i + 1) & mask;
        }
        int slot = clipCount++;
        int o = slot * KEY;
        key[o] = f0; key[o + 1] = f1; key[o + 2] = f2; key[o + 3] = f3; key[o + 4] = f4; key[o + 5] = f5;
        key[o + 6] = c.scale(); key[o + 7] = c.x0(); key[o + 8] = c.y0(); key[o + 9] = c.x1(); key[o + 10] = c.y1();
        for (int j = 0; j < 4; j++) { key[o + 11 + j] = c.rx()[j]; key[o + 15 + j] = c.ry()[j]; }
        clipTable[i] = slot;
        trees.setClip(slot, v.a(), v.b(), v.c(), v.d(), v.e(), v.f(), c.scale(), c.x0(), c.y0(), c.x1(), c.y1(), c.rx(), c.ry());
        return slot;
    }

    private static boolean same(float[] key, int o, float[] r) {
        return key[o] == r[0] && key[o + 1] == r[1] && key[o + 2] == r[2] && key[o + 3] == r[3];
    }

    private static boolean onGrid(float v) {
        return Math.abs(v - Math.round(v)) <= GRID_EPS;
    }

    /** 溜めた矩形を、いま結ばれている描画先へ 1 回のインスタンス描画で吐く。 */
    void drain(int viewW, int viewH) {
        int n = count - runStart;
        if (n <= 0) return;
        store.flush();
        renderer.draw(store, runStart, n, 0f, viewW, viewH, true);
        runStart = count;
        drawCalls++;
    }

    /** フレームの末（最後の {@link #drain} の後）。フェンスを置く。 */
    void end() {
        store.endFrame();
    }

    /** 診断: 直近に GPU へ送ったバイト数の累計（永続マップなら 0）。 */
    long uploadedBytes() {
        return trees.primitives.uploadedBytes() + trees.transforms.uploadedBytes() + trees.effects.uploadedBytes();
    }

    @Override
    public void close() {
        renderer.close();
        store.close();
    }
}
