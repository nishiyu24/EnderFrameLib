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
    private final int[] clipSlotVersion;
    private int clipVerNext = 1, clipHighWater;
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
        this.clipSlotVersion = new int[clipCapacity];
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

    // ------------------------------------------------------------------ 項目単位の保持（再生）
    //
    // 直前のフレームで同じ slot 位置・同じクリップ表の状態から同じ入力で出した項目は、このフレームでも GPU レコード（主・続きの slot、
    // 描画順の表、クリップ表の登録）が一字一句同じになる。計算して「同じ値」と照合する代わりに、範囲ごと前進させる。
    // 成り立つ前提（どれかが崩れたら再生しない）: ①直前のフレームに同じ項目が出ている（spFrame）②開始の slot・描画順・クリップ個数が同じ
    // ③参照するクリップ slot の中身が記録時と同じ（他の項目がその slot を上書きしていない）④字形 atlas が作り直されていない
    // ⑤記録中に CPU 描画・容量超過・画像が無かった。

    /** 再生を使うか（切り分け用: {@code -Denderframe.gl.noreplay=true} で無効）。 */
    static volatile boolean replayEnabled = !Boolean.getBoolean("enderframe.gl.noreplay");

    private long frameNo;
    private int[] spSlot0 = new int[0], spSlot1, spCount0, spCount1, spClip0, spClip1, spAtlas;
    private long[] spFrame;
    private int[] spGlyphs;
    private int[][] spRefs;
    private int[][] spRefVers;
    private boolean spanOn, spanUnclean;
    private int spanIndex, spanSlot0, spanCount0, spanClip0, spanAtlas;
    private long spanOverflows;
    private int[] spanRefs = new int[16];
    private int spanRefCount;
    long replayedShapes, replayedItems;
    /** 直前に再生した項目の、字形の数（バックエンドの統計用）。 */
    int replayedGlyphs;

    private void ensureSpan(int index) {
        if (index < spSlot0.length) return;
        int n = Math.max(index + 1, spSlot0.length * 2 + 64);
        spSlot0 = java.util.Arrays.copyOf(spSlot0, n);
        spSlot1 = spSlot1 == null ? new int[n] : java.util.Arrays.copyOf(spSlot1, n);
        spCount0 = spCount0 == null ? new int[n] : java.util.Arrays.copyOf(spCount0, n);
        spCount1 = spCount1 == null ? new int[n] : java.util.Arrays.copyOf(spCount1, n);
        spClip0 = spClip0 == null ? new int[n] : java.util.Arrays.copyOf(spClip0, n);
        spClip1 = spClip1 == null ? new int[n] : java.util.Arrays.copyOf(spClip1, n);
        spAtlas = spAtlas == null ? new int[n] : java.util.Arrays.copyOf(spAtlas, n);
        spFrame = spFrame == null ? new long[n] : java.util.Arrays.copyOf(spFrame, n);
        spGlyphs = spGlyphs == null ? new int[n] : java.util.Arrays.copyOf(spGlyphs, n);
        spRefs = spRefs == null ? new int[n][] : java.util.Arrays.copyOf(spRefs, n);
        spRefVers = spRefVers == null ? new int[n][] : java.util.Arrays.copyOf(spRefVers, n);
    }

    /** 項目 {@code index} の出力の記録を始める。 */
    void spanBegin(int index, int atlasResets) {
        spanOn = true;
        spanUnclean = false;
        spanIndex = index;
        spanSlot0 = slots;
        spanCount0 = count;
        spanClip0 = clipCount;
        spanAtlas = atlasResets;
        spanOverflows = overflows;
        spanRefCount = 0;
        lastClips = null; // 項目の最初の図形から参照を数える
    }

    private void spanRef(int id) {
        for (int i = 0; i < spanRefCount; i++) if (spanRefs[i] == id) return;
        if (spanRefCount == spanRefs.length) spanRefs = java.util.Arrays.copyOf(spanRefs, spanRefCount * 2);
        spanRefs[spanRefCount++] = id;
    }

    /** 記録を閉じる。{@code ok} が false（CPU 描画があった等）・画像・容量超過があれば、この項目の記録は捨てる。 */
    void spanEnd(boolean ok, int atlasResets, int glyphs) {
        if (!spanOn) return;
        spanOn = false;
        int index = spanIndex;
        ensureSpan(index);
        if (!ok || spanUnclean || overflows != spanOverflows || atlasResets != spanAtlas || slots - spanSlot0 > 64) {
            spFrame[index] = -1;
            return;
        }
        spSlot0[index] = spanSlot0; spSlot1[index] = slots;
        spCount0[index] = spanCount0; spCount1[index] = count;
        spClip0[index] = spanClip0; spClip1[index] = clipCount;
        spAtlas[index] = atlasResets;
        spGlyphs[index] = glyphs;
        // 参照するクリップの個数が同じなら、前回の配列を書き換えて使う（毎フレーム・毎項目の確保を避ける）
        int[] refs = spRefs[index];
        int[] vers = spRefVers[index];
        if (refs == null || refs.length != spanRefCount) {
            refs = new int[spanRefCount];
            vers = new int[spanRefCount];
            spRefs[index] = refs;
            spRefVers[index] = vers;
        }
        System.arraycopy(spanRefs, 0, refs, 0, spanRefCount);
        for (int i = 0; i < spanRefCount; i++) vers[i] = clipSlotVersion[refs[i]];
        spFrame[index] = frameNo;
    }

    /** 記録を無効にする（この項目は再生しない）。 */
    void spanDrop(int index) {
        spanOn = false;
        if (index < spFrame.length) spFrame[index] = -1;
    }

    /** 直前のフレームの出力をそのまま前進させられる状態か（状態は変えない）。 */
    boolean spanCanReplay(int index, int atlasResets) {
        if (index >= spSlot0.length || spFrame[index] != frameNo - 1) return false;
        if (slots != spSlot0[index] || count != spCount0[index] || clipCount != spClip0[index] || atlasResets != spAtlas[index]) return false;
        int[] refs = spRefs[index];
        int[] vers = spRefVers[index];
        int c0 = spClip0[index], c1 = spClip1[index];
        for (int i = 0; i < refs.length; i++) {
            int r = refs[i];
            if (r >= clipCapacity || clipSlotVersion[r] != vers[i]) return false;
            if (r >= clipCount && (r < c0 || r >= c1)) return false; // まだ登録されていない slot は、この項目が登録したものだけ
        }
        return true;
    }

    /** {@link #spanCanReplay} が true の項目を前進させる。 */
    void spanApply(int index) {
        int c0 = spClip0[index], c1 = spClip1[index];
        // 新しく登録された slot [c0, c1) をクリップ表へ入れ直す（以降の項目が同じ内容を同じ slot に引き当てるため）
        for (int slot = c0; slot < c1; slot++) insertClipSlot(slot);
        clipCount = c1;
        replayedShapes += spCount1[index] - spCount0[index];
        accepted += spCount1[index] - spCount0[index];
        replayedItems++;
        replayedGlyphs = spGlyphs[index];
        slots = spSlot1[index];
        count = spCount1[index];
        spFrame[index] = frameNo;
        lastClips = null;
    }

    /** {@link #clipKeys} の slot の内容を、{@link #clipTable}（鍵 → slot）へ登録する（{@link #clipSlot} の登録部と同じ鍵の組み方）。 */
    private void insertClipSlot(int slot) {
        float[] key = clipKeys;
        int o = slot * KEY;
        int h = 17;
        h = h * 31 + Float.floatToIntBits(key[o]); h = h * 31 + Float.floatToIntBits(key[o + 1]); h = h * 31 + Float.floatToIntBits(key[o + 2]);
        h = h * 31 + Float.floatToIntBits(key[o + 3]); h = h * 31 + Float.floatToIntBits(key[o + 4]); h = h * 31 + Float.floatToIntBits(key[o + 5]);
        h = h * 31 + Float.floatToIntBits(key[o + 7]) + 7 * Float.floatToIntBits(key[o + 8]) + 13 * Float.floatToIntBits(key[o + 9]) + 29 * Float.floatToIntBits(key[o + 10]);
        h ^= h >>> 15;
        int mask = clipTable.length - 1;
        int i = h & mask;
        while (clipTable[i] >= 0) i = (i + 1) & mask;
        clipTable[i] = slot;
    }

    /** フレームの頭。永続マップなら前フレームの GPU 読み出しの終わりを待ち、受け付けの番号を 0 に戻す。 */
    void begin() {
        store.beginFrame();
        slots = 0;
        count = 0;
        runStart = 0;
        clipCount = 0;
        lastClips = null;
        frameNo++;
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
        if (b == 0 && c == 0 && a > 1e-9 && d > 1e-9) {
            // 回転・傾き無し（拡大と平行移動だけ。ほとんどの図形）。一般の経路の式（hypot・atan2・cos・sin）に代入した結果と同じ値で、
            // 図形ごとに 2 回（accepts と open）走る三角関数を省く。sx = |a| = a、rot = atan2(0, a) = 0、sy = (a*d - 0)/a
            trs[0] = (float) pose.e();
            trs[1] = (float) pose.f();
            trs[2] = (float) a;
            trs[3] = (float) ((a * d) / a);
            trs[4] = 0f;
            return true;
        }
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
        spanUnclean = true; // 画像のテクスチャは項目の外の状態（結んだテクスチャ）に依る
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
        // クリップ欄は writeClips が個数で書く。ここで NONE を書いてから個数に書き戻すと、毎図形・毎フレーム値が往復して
        // 「変更」になり（永続バッファが常に汚れる）、同じ内容のフレームでも全図形ぶんの書き込みと範囲の更新が走っていた
        trees.setPrimitive(k, opcode, k, clips == null ? 0 : clips.length, ef, x0, y0, x1 - x0, y1 - y0, argb, 0);
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
    /** 直前に引いたクリップ配列（同一の配列なら slot も同じ）。フレームの頭で捨てる（slot の表を作り直すため）。 */
    private GpuClip[] lastClips;
    private int lastA, lastB, lastC, lastD;

    private void writeClips(int k, GpuClip[] clips) {
        int n = clips == null ? 0 : clips.length;
        if (n > 0 && clips == lastClips) { // 同じ項目の文字・図形は同じ配列を共有する: 表の引き直し（ハッシュ・比較）を省く
            trees.setPrimitiveClips(k, n, lastA, lastB, lastC, lastD);
            return;
        }
        int a = 0, b = 0, c = 0, d = 0;
        for (int i = 0; i < n; i++) {
            int id = clipSlot(clips[i]);
            switch (i) { case 0 -> a = id; case 1 -> b = id; case 2 -> c = id; default -> d = id; }
        }
        if (n > 0) { lastClips = clips; lastA = a; lastB = b; lastC = c; lastD = d; }
        if (spanOn) { if (n > 0) spanRef(a); if (n > 1) spanRef(b); if (n > 2) spanRef(c); if (n > 3) spanRef(d); }
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
        boolean changed = slot >= clipHighWater;
        if (changed) {
            clipHighWater = slot + 1;
        } else {
            changed = key[o] != f0 || key[o + 1] != f1 || key[o + 2] != f2 || key[o + 3] != f3 || key[o + 4] != f4 || key[o + 5] != f5
                    || key[o + 6] != c.scale() || key[o + 7] != c.x0() || key[o + 8] != c.y0() || key[o + 9] != c.x1() || key[o + 10] != c.y1()
                    || !same(key, o + 11, c.rx()) || !same(key, o + 15, c.ry());
        }
        if (changed) {
            clipSlotVersion[slot] = clipVerNext++;
            key[o] = f0; key[o + 1] = f1; key[o + 2] = f2; key[o + 3] = f3; key[o + 4] = f4; key[o + 5] = f5;
            key[o + 6] = c.scale(); key[o + 7] = c.x0(); key[o + 8] = c.y0(); key[o + 9] = c.x1(); key[o + 10] = c.y1();
            for (int j = 0; j < 4; j++) { key[o + 11 + j] = c.rx()[j]; key[o + 15 + j] = c.ry()[j]; }
        }
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
        renderer.drawPremulBlend(store, runStart, n, 0f, viewW, viewH, true);
        runStart = count;
        drawCalls++;
    }

    /** フレームの末（最後の {@link #drain} の後）。フェンスを置く。 */
    void end() {
        store.endFrame();
    }

    /** 診断: 直近に GPU へ送ったバイト数の累計（永続マップなら 0）。 */
    /** 値が変わった GPU レコードの書き込みの累計（プリミティブ・変換・効果）。 */
    long changedWrites() {
        return trees.primitives.changedWrites() + trees.transforms.changedWrites() + trees.effects.changedWrites();
    }

    long uploadedBytes() {
        return trees.primitives.uploadedBytes() + trees.transforms.uploadedBytes() + trees.effects.uploadedBytes();
    }

    @Override
    public void close() {
        renderer.close();
        store.close();
    }
}
