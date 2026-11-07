package com.nishiyu.enderframelib.core.kernel.gpu;

/**
 * GPU が読む 4 つの Property Tree（Transform / Clip / Effect / Primitive）と、スクロール量・イージング曲線の小さな表。
 * 各ツリーは {@link PersistentVramBuffer}（固定長レコードの SSBO 領域）で、レコードの配置は下の定数で固定。
 *
 * <pre>
 * Transform (64 B)  0 parent i | 4 scrollId i | 8 startMs f | 12 durationMs f | 16 easing i | 20 flags i
 *                   24 target TRS (tx ty sx sy rot : 5 f) | 44 start TRS (5 f)
 * Clip      (64 B)  0 inv a b c d (4 f) | 16 inv e f (2 f) | 24 scale f | 32 rect x0 y0 x1 y1 (4 f) | 48 rx (4 half) | 56 ry (4 half)
 *                   inv = デバイス画素 → 持ち主の空間の逆行列。1 つのプリミティブが最大 {@link #MAX_CLIPS_PER_PRIMITIVE} 個を重ねて持つ
 * Effect    (32 B)  0 opacity f | 4 startOpacity f | 8 blend s | 10 filter s | 12 blurSigma f | 16 parent i
 *                   20 animTransform i (時刻の出所。-1 = 無し) | 24 atlasUv (4 u16)
 * Primitive (128 B) 0 opcode | 4 transformId | 8 clipId | 12 effectId | 16 x y w h (4 f)
 *                   32 rx (4 half) | 40 ry (4 half) | 48 inner x0 y0 x1 y1 (4 f) | 64 irx (4 half) | 72 iry (4 half)
 *                   80 widths t r b l (4 half) | 88 color0..3 ARGB (4 i) | 104 flags | 108 margin f（描く範囲の余白。形の外へ足す） | 112 atlasUv (4 u16)
 *                   120 clipIds (4 u16。clipId 欄 = 個数)
 *                   opcode 3/4（影）・5（グラデーション）は<b>直後のスロット</b>（続きスロット。描画順の表には載せない）に追加データを置く:
 *                   3/4: float 0..3 = 影の形の矩形、4..7 = 抜き穴の矩形、8 = σ、整数 9 bit0 = 穴あり、
 *                        10..13 / 14..17 = 影の形の rx / ry、18..21 / 22..25 = 穴の rx / ry（ぼかしの縁で半精度の誤差が見えるので float）
 *                   2（枠線）の flags bit1 = 破線: 続きスロットの float 0..3 = (bx, by, cxm, cym)、4..7 = pcx、8..11 = pcy、12..15 = (周期, 破線の長さ, 丸、太さ)
 *                   6（字形）: 主スロットの uv 欄 = アトラス上の (x, y, 幅, 高さ)、矩形 = デバイス画素の左上と大きさ（姿勢は恒等）
 *                   7（画像）: float 0..3 = 形の矩形、4..7 = (画像の左上 x,y, 局所 1 単位あたりの画素 sx,sy)、8..11 = (幅, 高さ, taps, 0)、12..13 = 標本の間隔、16..19 / 20..23 = 形の rx / ry
 *                   8（円の枠）: float 0..3 = (中心 x, y, 外半径, 内半径)、色 0..3 = 上右下左
 *                   9（変形した字形）: float 0..3 = アトラス上の (x,y,w,h)、4..7 = (ox, oy, s, 0)、8..11 = (left, top, 0, 0)
 *                   10（字形の縁取り）: float 0..3 = アトラス上の (x,y,w,h)、4 = 帯の幅の半分
 *                   5: float 0..3 = gradA、4..7 = gradB、8..11 = 色 0（RGBA）、12..15 = 色 1、16 = 繰り返しの原点、17 = 周期
 *                   opcode: 1 = 角丸矩形の塗り（color0）、2 = 角丸の枠線（color0..3 = 上右下左、inner = 内側の矩形、flags bit0 = 内側なし）
 * </pre>
 *
 * <p>設計書の {@code mat3x2} を持たず、ローカル変換は <b>分解した TRS</b>（平行移動・拡縮・回転）で持つ。
 * 開始値と目標値を行列で補間すると回転が崩れるため。シェーダーが現在時刻から補間して行列を組む
 * （{@link GpuAnimation}）。親 ID は 0 起点の添字で、{@link #NONE} = -1 が根。</p>
 */
public final class PropertyTrees {
    public static final int NONE = -1;

    public static final int TRANSFORM_STRIDE = 64;
    public static final int CLIP_STRIDE = 64;
    public static final int EFFECT_STRIDE = 32;
    public static final int PRIMITIVE_STRIDE = 128;

    // Transform
    static final int T_PARENT = 0, T_SCROLL = 4, T_START_MS = 8, T_DURATION = 12, T_EASING = 16, T_FLAGS = 20, T_TARGET = 24, T_START = 44;
    // Clip
    static final int C_INV = 0, C_INV_T = 16, C_SCALE = 24, C_RECT = 32, C_RX = 48, C_RY = 56;
    public static final int MAX_CLIPS_PER_PRIMITIVE = 4;
    // Effect
    static final int E_OPACITY = 0, E_START_OPACITY = 4, E_BLEND = 8, E_FILTER = 10, E_SIGMA = 12, E_PARENT = 16, E_ANIM = 20, E_UV = 24;
    // Primitive
    static final int P_OPCODE = 0, P_TRANSFORM = 4, P_CLIP = 8, P_EFFECT = 12, P_RECT = 16, P_RX = 32, P_RY = 40, P_INNER = 48,
            P_IRX = 64, P_IRY = 72, P_WIDTHS = 80, P_COLOR = 88, P_FLAGS = 104, P_MARGIN = 108, P_UV = 112, P_CLIPS = 120;
    /** プリミティブの opcode。 */
    /** 枠線（opcode 2）のフラグ: 内側が無い／破線（続きスロットに破線のデータ）。 */
    public static final int FLAG_INNER_EMPTY = 1, FLAG_DASHED = 2;
    public static final int OP_FILL = 1, OP_STROKE = 2, OP_OUTER_SHADOW = 3, OP_INSET_SHADOW = 4, OP_GRADIENT = 5,
            OP_GLYPH = 6, OP_IMAGE = 7, OP_CIRCLE_BORDER = 8, OP_GLYPH_TRANSFORMED = 9, OP_GLYPH_STROKE = 10;

    /** イージングの種類（{@code easing} の下位 8 ビット）。上位 24 ビットはカスタム曲線の番号。 */
    public static final int EASE_LINEAR = 0, EASE_EASE = 1, EASE_IN = 2, EASE_OUT = 3, EASE_IN_OUT = 4, EASE_CUBIC = 5;

    public static final int MAX_CURVES = 256;
    public static final int MAX_SCROLLS = 1024;

    public final PersistentVramBuffer transforms;
    public final PersistentVramBuffer clips;
    public final PersistentVramBuffer effects;
    public final PersistentVramBuffer primitives;

    /** スクロール量（1 スロット = x, y の 8 B）と曲線表（1 スロット = x1 y1 x2 y2 の 16 B）。GPU からも読む表。 */
    public final PersistentVramBuffer scrolls;
    public final PersistentVramBuffer curveTable;
    /** 描画順: {@code order[i]} = i 番目に描くプリミティブのスロット（4 B / 要素）。 */
    public final PersistentVramBuffer order;
    private int curveCount;

    /** 全バッファが同じ {@code device} を共有する（GL を使わない用途・テスト向け）。 */
    public PropertyTrees(int transformCap, int clipCap, int effectCap, int primitiveCap, PersistentVramBuffer.Device device) {
        this(transformCap, clipCap, effectCap, primitiveCap, () -> device);
    }

    /**
     * バッファごとに別の {@link PersistentVramBuffer.Device}（= GPU 側の別バッファ）を作る。{@code devices} は
     * transforms, clips, effects, primitives, scrolls, curveTable, order の順に 1 回ずつ呼ばれる。
     */
    public PropertyTrees(int transformCap, int clipCap, int effectCap, int primitiveCap,
                         java.util.function.Supplier<PersistentVramBuffer.Device> devices) {
        this.transforms = new PersistentVramBuffer(TRANSFORM_STRIDE, transformCap, devices.get());
        this.clips = new PersistentVramBuffer(CLIP_STRIDE, clipCap, devices.get());
        this.effects = new PersistentVramBuffer(EFFECT_STRIDE, effectCap, devices.get());
        this.primitives = new PersistentVramBuffer(PRIMITIVE_STRIDE, primitiveCap, devices.get());
        this.scrolls = new PersistentVramBuffer(8, MAX_SCROLLS, devices.get());
        this.curveTable = new PersistentVramBuffer(16, MAX_CURVES, devices.get());
        this.order = new PersistentVramBuffer(4, primitiveCap, devices.get());
        for (int i = 0; i < primitiveCap; i++) order.allocate();
        for (int i = 0; i < MAX_SCROLLS; i++) scrolls.allocate(); // 番号 = スロット（固定の表）
        for (int i = 0; i < MAX_CURVES; i++) curveTable.allocate();
    }

    // ---- Transform ----------------------------------------------------------------------------

    /** 静止した変換を作る（アニメ無し: 持続 0 なら常に目標値）。 */
    public int addTransform(int parent, float tx, float ty, float sx, float sy, float rot) {
        int s = transforms.allocate();
        if (s < 0) return -1;
        transforms.putInt(s, T_PARENT, parent);
        transforms.putInt(s, T_SCROLL, NONE);
        setTransformTarget(s, tx, ty, sx, sy, rot);
        return s;
    }

    public void setTransformParent(int slot, int parent) { transforms.putInt(slot, T_PARENT, parent); }

    public void setScroll(int slot, int scrollId) { transforms.putInt(slot, T_SCROLL, scrollId); }

    public void setTransformTarget(int slot, float tx, float ty, float sx, float sy, float rot) {
        transforms.putFloat(slot, T_TARGET, tx);
        transforms.putFloat(slot, T_TARGET + 4, ty);
        transforms.putFloat(slot, T_TARGET + 8, sx);
        transforms.putFloat(slot, T_TARGET + 12, sy);
        transforms.putFloat(slot, T_TARGET + 16, rot);
    }

    /**
     * アニメを 1 回だけ書く。以降は CPU が触らず、シェーダーが {@code frameMs} から補間する。
     * 開始値は現在の目標値ではなく引数の値。
     */
    public void startTransformAnimation(int slot, float sTx, float sTy, float sSx, float sSy, float sRot,
                                        float startMs, float durationMs, int easing) {
        transforms.putFloat(slot, T_START, sTx);
        transforms.putFloat(slot, T_START + 4, sTy);
        transforms.putFloat(slot, T_START + 8, sSx);
        transforms.putFloat(slot, T_START + 12, sSy);
        transforms.putFloat(slot, T_START + 16, sRot);
        transforms.putFloat(slot, T_START_MS, startMs);
        transforms.putFloat(slot, T_DURATION, durationMs);
        transforms.putInt(slot, T_EASING, easing);
    }

    public float transformValue(int slot, int component) { return transforms.getFloat(slot, T_TARGET + 4 * component); }

    // ---- Scroll / curves ----------------------------------------------------------------------

    /** スクロール量を書く（CPU が毎フレームやる仕事はこれだけ）。 */
    public void setScrollOffset(int scrollId, float x, float y) {
        scrolls.putFloat(scrollId, 0, x);
        scrolls.putFloat(scrollId, 4, y);
    }

    public float scrollX(int scrollId) { return scrolls.getFloat(scrollId, 0); }
    public float scrollY(int scrollId) { return scrolls.getFloat(scrollId, 4); }

    /** カスタム {@code cubic-bezier} を表へ登録（同じ曲線は同じ番号）。{@link #EASE_CUBIC} と組にした easing 値を返す。 */
    public int registerCurve(float x1, float y1, float x2, float y2) {
        for (int i = 0; i < curveCount; i++) {
            if (curve(i, 0) == x1 && curve(i, 1) == y1 && curve(i, 2) == x2 && curve(i, 3) == y2) {
                return EASE_CUBIC | (i << 8);
            }
        }
        if (curveCount >= MAX_CURVES) throw new IllegalStateException("curve table full");
        int i = curveCount++;
        curveTable.putFloat(i, 0, x1);
        curveTable.putFloat(i, 4, y1);
        curveTable.putFloat(i, 8, x2);
        curveTable.putFloat(i, 12, y2);
        return EASE_CUBIC | (i << 8);
    }

    public int curveCount() { return curveCount; }
    float curve(int index, int component) { return curveTable.getFloat(index, 4 * component); }

    // ---- Clip ---------------------------------------------------------------------------------

    /** クリップを 1 つ書く。{@code inv*} はデバイス画素 → 持ち主の空間、{@code scale} は持ち主の 1 単位が何画素か。 */
    public void setClip(int s, double a, double b, double c, double d, double e, double f, float scale,
                        float x0, float y0, float x1, float y1, float[] rx, float[] ry) {
        clips.putFloat(s, C_INV, (float) a);
        clips.putFloat(s, C_INV + 4, (float) b);
        clips.putFloat(s, C_INV + 8, (float) c);
        clips.putFloat(s, C_INV + 12, (float) d);
        clips.putFloat(s, C_INV_T, (float) e);
        clips.putFloat(s, C_INV_T + 4, (float) f);
        clips.putFloat(s, C_SCALE, scale);
        clips.putFloat(s, C_RECT, x0);
        clips.putFloat(s, C_RECT + 4, y0);
        clips.putFloat(s, C_RECT + 8, x1);
        clips.putFloat(s, C_RECT + 12, y1);
        for (int i = 0; i < 4; i++) {
            clips.putHalf(s, C_RX + 2 * i, rx[i]);
            clips.putHalf(s, C_RY + 2 * i, ry[i]);
        }
    }

    /** プリミティブに重ねるクリップ（0〜4 個）。ID は 16 ビット。 */
    public void setPrimitiveClips(int s, int count, int id0, int id1, int id2, int id3) {
        primitives.putInt(s, P_CLIP, count);
        primitives.putShort(s, P_CLIPS, id0);
        primitives.putShort(s, P_CLIPS + 2, id1);
        primitives.putShort(s, P_CLIPS + 4, id2);
        primitives.putShort(s, P_CLIPS + 6, id3);
    }

    // ---- Effect -------------------------------------------------------------------------------

    public int addEffect(int parent, float opacity, int blendMode, int filterType, float blurSigma) {
        int s = effects.allocate();
        if (s < 0) return -1;
        setEffect(s, parent, opacity, blendMode, filterType, blurSigma);
        effects.putInt(s, E_ANIM, NONE);
        return s;
    }

    public void setEffect(int s, int parent, float opacity, int blendMode, int filterType, float blurSigma) {
        effects.putFloat(s, E_OPACITY, opacity);
        effects.putFloat(s, E_START_OPACITY, opacity);
        effects.putShort(s, E_BLEND, blendMode);
        effects.putShort(s, E_FILTER, filterType);
        effects.putFloat(s, E_SIGMA, blurSigma);
        effects.putInt(s, E_PARENT, parent);
    }

    /** 不透明度のアニメ: 時刻と曲線は {@code timingTransform}（変換スロット）のものを使う。 */
    public void startOpacityAnimation(int effectSlot, float startOpacity, int timingTransform) {
        effects.putFloat(effectSlot, E_START_OPACITY, startOpacity);
        effects.putInt(effectSlot, E_ANIM, timingTransform);
    }

    public void setEffectAtlas(int s, int u0, int v0, int u1, int v1) {
        effects.putShort(s, E_UV, u0);
        effects.putShort(s, E_UV + 2, v0);
        effects.putShort(s, E_UV + 4, u1);
        effects.putShort(s, E_UV + 6, v1);
    }

    // ---- Primitive ----------------------------------------------------------------------------

    public int addPrimitive() {
        int s = primitives.allocate();
        if (s >= 0) {
            primitives.putInt(s, P_TRANSFORM, NONE);
            primitives.putInt(s, P_CLIP, NONE);
            primitives.putInt(s, P_EFFECT, NONE);
        }
        return s;
    }

    public void setPrimitive(int s, int opcode, int transformId, int clipId, int effectId,
                             float x, float y, float w, float h, int fillArgb, int borderArgb) {
        primitives.putInt(s, P_OPCODE, opcode);
        primitives.putInt(s, P_TRANSFORM, transformId);
        primitives.putInt(s, P_CLIP, clipId);
        primitives.putInt(s, P_EFFECT, effectId);
        primitives.putFloat(s, P_RECT, x);
        primitives.putFloat(s, P_RECT + 4, y);
        primitives.putFloat(s, P_RECT + 8, w);
        primitives.putFloat(s, P_RECT + 12, h);
        primitives.putInt(s, P_COLOR, fillArgb);
        primitives.putInt(s, P_COLOR + 4, borderArgb);
    }

    /** 4 隅とも円の角（rx = ry）。 */
    public void setPrimitiveRadii(int s, float tl, float tr, float br, float bl) {
        primitives.putHalf(s, P_RX, tl);
        primitives.putHalf(s, P_RX + 2, tr);
        primitives.putHalf(s, P_RX + 4, br);
        primitives.putHalf(s, P_RX + 6, bl);
        primitives.putHalf(s, P_RY, tl);
        primitives.putHalf(s, P_RY + 2, tr);
        primitives.putHalf(s, P_RY + 4, br);
        primitives.putHalf(s, P_RY + 6, bl);
    }

    /** 楕円の角（左上・右上・右下・左下）。 */
    public void setPrimitiveRadiiXY(int s, float[] rx, float[] ry) {
        for (int i = 0; i < 4; i++) {
            primitives.putHalf(s, P_RX + 2 * i, rx[i]);
            primitives.putHalf(s, P_RY + 2 * i, ry[i]);
        }
    }

    /** 枠線: 内側の矩形・その角・フラグ（{@link #FLAG_INNER_EMPTY} | {@link #FLAG_DASHED}）。色は {@link #setPrimitiveColors}、幅は {@link #setPrimitiveBorderWidths}。 */
    public void setPrimitiveInner(int s, float x0, float y0, float x1, float y1, float[] irx, float[] iry, int flags) {
        primitives.putFloat(s, P_INNER, x0);
        primitives.putFloat(s, P_INNER + 4, y0);
        primitives.putFloat(s, P_INNER + 8, x1);
        primitives.putFloat(s, P_INNER + 12, y1);
        for (int i = 0; i < 4; i++) {
            primitives.putHalf(s, P_IRX + 2 * i, irx[i]);
            primitives.putHalf(s, P_IRY + 2 * i, iry[i]);
        }
        primitives.putInt(s, P_FLAGS, flags);
    }

    /** 4 色（塗りは color0 だけ。枠線は上・右・下・左）。 */
    public void setPrimitiveColors(int s, int c0, int c1, int c2, int c3) {
        primitives.putInt(s, P_COLOR, c0);
        primitives.putInt(s, P_COLOR + 4, c1);
        primitives.putInt(s, P_COLOR + 8, c2);
        primitives.putInt(s, P_COLOR + 12, c3);
    }

    public void setPrimitiveBorderWidths(int s, float t, float r, float b, float l) {
        primitives.putHalf(s, P_WIDTHS, t);
        primitives.putHalf(s, P_WIDTHS + 2, r);
        primitives.putHalf(s, P_WIDTHS + 4, b);
        primitives.putHalf(s, P_WIDTHS + 6, l);
    }

    /** 続きスロット {@code ext} に影のデータを書く（opcode 3 = 外側、4 = 内側）。{@code hole} は {x0,y0,x1,y1}、無ければ null。 */
    public void setPrimitiveShadow(int ext, float sx0, float sy0, float sx1, float sy1, float[] srx, float[] sry,
                                   float sigma, float[] hole, float[] hrx, float[] hry) {
        primitives.putFloat(ext, 0, sx0);
        primitives.putFloat(ext, 4, sy0);
        primitives.putFloat(ext, 8, sx1);
        primitives.putFloat(ext, 12, sy1);
        for (int i = 0; i < 4; i++) {
            primitives.putFloat(ext, 40 + 4 * i, srx[i]);
            primitives.putFloat(ext, 56 + 4 * i, sry[i]);
        }
        primitives.putFloat(ext, 32, sigma);
        boolean has = hole != null;
        primitives.putInt(ext, 36, has ? 1 : 0);
        primitives.putFloat(ext, 16, has ? hole[0] : 0f);
        primitives.putFloat(ext, 20, has ? hole[1] : 0f);
        primitives.putFloat(ext, 24, has ? hole[2] : 0f);
        primitives.putFloat(ext, 28, has ? hole[3] : 0f);
        for (int i = 0; i < 4; i++) {
            primitives.putFloat(ext, 72 + 4 * i, has ? hrx[i] : 0f);
            primitives.putFloat(ext, 88 + 4 * i, has ? hry[i] : 0f);
        }
    }

    /** 続きスロット {@code ext} にグラデーションのデータを書く（opcode 5）。色は非乗算の ARGB。 */
    public void setPrimitiveGradient(int ext, float[] gradA, float[] gradB, float repeatOrigin, float repeatPeriod, int argb0, int argb1) {
        for (int i = 0; i < 4; i++) {
            primitives.putFloat(ext, 4 * i, gradA[i]);
            primitives.putFloat(ext, 16 + 4 * i, gradB[i]);
        }
        putColor(ext, 32, argb0);
        putColor(ext, 48, argb1);
        primitives.putFloat(ext, 64, repeatOrigin);
        primitives.putFloat(ext, 68, repeatPeriod);
    }

    private void putColor(int slot, int byteOffset, int argb) {
        primitives.putFloat(slot, byteOffset, ((argb >> 16) & 0xFF) / 255f);
        primitives.putFloat(slot, byteOffset + 4, ((argb >> 8) & 0xFF) / 255f);
        primitives.putFloat(slot, byteOffset + 8, (argb & 0xFF) / 255f);
        primitives.putFloat(slot, byteOffset + 12, ((argb >>> 24) & 0xFF) / 255f);
    }

    /** 続きスロット {@code ext} の float 欄 {@code index}（0 起点）へ書く。字形・画像・円の枠の追加データ用。 */
    public void setExtFloat(int ext, int index, float v) {
        primitives.putFloat(ext, 4 * index, v);
    }


    /** 描く範囲（四角形）を形の矩形から外へ広げる量（局所の単位）。 */
    public void setPrimitiveMargin(int s, float margin) {
        primitives.putFloat(s, P_MARGIN, margin);
    }


    /** 破線の枠線の追加データ（続きスロット {@code ext}）。 */
    public void setExtDash(int ext, float bx, float by, float cxm, float cym, float[] pcx, float[] pcy,
                           float period, float dash, boolean round, float thickness) {
        setExtFloat(ext, 0, bx);
        setExtFloat(ext, 1, by);
        setExtFloat(ext, 2, cxm);
        setExtFloat(ext, 3, cym);
        for (int i = 0; i < 4; i++) {
            setExtFloat(ext, 4 + i, pcx[i]);
            setExtFloat(ext, 8 + i, pcy[i]);
        }
        setExtFloat(ext, 12, period);
        setExtFloat(ext, 13, dash);
        setExtFloat(ext, 14, round ? 1f : 0f);
        setExtFloat(ext, 15, thickness);
    }


    public void setPrimitiveAtlas(int s, int u0, int v0, int u1, int v1) {
        primitives.putShort(s, P_UV, u0);
        primitives.putShort(s, P_UV + 2, v0);
        primitives.putShort(s, P_UV + 4, u1);
        primitives.putShort(s, P_UV + 6, v1);
    }

    /** 4 つのツリーの汚れた範囲をまとめて送る。送ったバイト数の合計。 */
    public int flush() {
        return transforms.flush() + clips.flush() + effects.flush() + primitives.flush() + scrolls.flush() + curveTable.flush() + order.flush();
    }
}
