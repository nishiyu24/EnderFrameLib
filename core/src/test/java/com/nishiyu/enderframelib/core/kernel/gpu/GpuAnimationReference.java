package com.nishiyu.enderframelib.core.kernel.gpu;

/**
 * 【テスト専用】{@link GpuAnimation#GLSL} が GPU で行うアニメーション・スクロール評価の CPU 参照実装（割り当て 0）。
 * 本番にはシェーダー（GLSL）だけを置き、同じ数学の Java 版はテストの「正解」としてここに置く
 * （番人: {@code Milestone4GateTest.gpuAnimationJavaReferenceStaysTestOnly}）。
 *
 * <p>シェーダーは {@code uFrameTimeMs} と {@link PropertyTrees} のレコードだけから現在の行列・不透明度を決める。
 * ここの Java は同じ手順を踏み、テストで {@code EasingFunction} や素朴な行列積と突き合わせる。
 * 戻り値は呼び出し側が渡す {@code float[]} に書く。</p>
 */
public final class GpuAnimationReference {
    private static final int MAX_DEPTH = 64;
    private static final float[][] PRESETS = {
            {0f, 0f, 1f, 1f},            // linear
            {0.25f, 0.1f, 0.25f, 1.0f},  // ease
            {0.42f, 0f, 1f, 1f},         // ease-in
            {0f, 0f, 0.58f, 1f},         // ease-out
            {0.42f, 0f, 0.58f, 1f},      // ease-in-out
    };

    private GpuAnimationReference() {}

    /** 進行度 t = clamp((frameMs - startMs) / durationMs, 0, 1)。持続 0 以下は完了済み（1）。 */
    public static float progress(float frameMs, float startMs, float durationMs) {
        if (durationMs <= 0f) return 1f;
        float t = (frameMs - startMs) / durationMs;
        return t < 0f ? 0f : (t > 1f ? 1f : t);
    }

    /**
     * イージング。シェーダーと同じく、固定回数の Newton 法 + 二分法で x(u)=t を解く（分岐の少ない形）。
     * {@code easing} は {@link PropertyTrees} の値（下位 8 ビット = 種類、上位 = カスタム曲線の番号）。
     */
    public static float ease(PropertyTrees trees, int easing, float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        int kind = easing & 0xFF;
        if (kind == PropertyTrees.EASE_LINEAR) return t;
        float x1, y1, x2, y2;
        if (kind == PropertyTrees.EASE_CUBIC) {
            int c = easing >>> 8;
            x1 = trees.curve(c, 0); y1 = trees.curve(c, 1); x2 = trees.curve(c, 2); y2 = trees.curve(c, 3);
        } else {
            float[] p = PRESETS[Math.min(kind, PRESETS.length - 1)];
            x1 = p[0]; y1 = p[1]; x2 = p[2]; y2 = p[3];
        }
        return cubic(t, x1, y1, x2, y2);
    }

    private static float cubic(float x, float x1, float y1, float x2, float y2) {
        float cx = 3f * x1, bx = 3f * (x2 - x1) - cx, ax = 1f - cx - bx;
        float cy = 3f * y1, by = 3f * (y2 - y1) - cy, ay = 1f - cy - by;
        float u = x;
        for (int i = 0; i < 8; i++) {
            float e = ((ax * u + bx) * u + cx) * u - x;
            float d = (3f * ax * u + 2f * bx) * u + cx;
            if (Math.abs(d) < 1e-6f) break;
            u = Math.clamp(u - e / d, 0f, 1f);
        }
        float lo = 0f, hi = 1f;
        for (int i = 0; i < 12; i++) {
            float xe = ((ax * u + bx) * u + cx) * u;
            if (Math.abs(xe - x) < 1e-6f) break;
            if (x > xe) lo = u; else hi = u;
            u = (lo + hi) * 0.5f;
        }
        return ((ay * u + by) * u + cy) * u;
    }

    /** 変換スロットの現在の TRS（tx, ty, sx, sy, rot）を {@code out[0..4]} に。 */
    public static void evalTrs(PropertyTrees tr, int slot, float frameMs, float[] out) {
        PersistentVramBuffer b = tr.transforms;
        float dur = b.getFloat(slot, PropertyTrees.T_DURATION);
        float e = dur <= 0f ? 1f
                : ease(tr, b.getInt(slot, PropertyTrees.T_EASING), progress(frameMs, b.getFloat(slot, PropertyTrees.T_START_MS), dur));
        for (int i = 0; i < 5; i++) {
            float target = b.getFloat(slot, PropertyTrees.T_TARGET + 4 * i);
            float start = dur <= 0f ? target : b.getFloat(slot, PropertyTrees.T_START + 4 * i);
            out[i] = start + (target - start) * e;
        }
    }

    /** 2x3 アフィン {@code [a b c d tx ty]}（列優先）を TRS から組む: T * R * S。 */
    public static void trsToMatrix(float tx, float ty, float sx, float sy, float rot, float[] m) {
        float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
        m[0] = c * sx; m[1] = s * sx; m[2] = -s * sy; m[3] = c * sy; m[4] = tx; m[5] = ty;
    }

    /** {@code out = p * q}（2x3 アフィンの合成。{@code out} は p・q と別の配列）。 */
    public static void multiply(float[] p, float[] q, float[] out) {
        out[0] = p[0] * q[0] + p[2] * q[1];
        out[1] = p[1] * q[0] + p[3] * q[1];
        out[2] = p[0] * q[2] + p[2] * q[3];
        out[3] = p[1] * q[2] + p[3] * q[3];
        out[4] = p[0] * q[4] + p[2] * q[5] + p[4];
        out[5] = p[1] * q[4] + p[3] * q[5] + p[5];
    }

    /**
     * 根までたどった世界行列。スクロール ID を持つ節は、自身の変換の前に {@code translate(-scroll)} を掛ける
     * （その節は「スクロールされる内容の層」）。{@code scratch} は長さ 17 以上の作業配列。
     */
    public static void worldMatrix(PropertyTrees tr, int slot, float frameMs, float[] out, float[] scratch) {
        // scratch 配置: [0..4] TRS, [5..10] ローカル行列, [11..16] 積の一時置き場
        out[0] = 1; out[1] = 0; out[2] = 0; out[3] = 1; out[4] = 0; out[5] = 0;
        int node = slot;
        for (int depth = 0; node >= 0 && depth < MAX_DEPTH; depth++) {
            evalTrs(tr, node, frameMs, scratch);
            trsToMatrix(scratch[0], scratch[1], scratch[2], scratch[3], scratch[4], scratch, 5);
            int sc = tr.transforms.getInt(node, PropertyTrees.T_SCROLL);
            if (sc >= 0) { // translate(-scroll) * local: 線形部は変わらず、平行移動に -scroll が足される
                scratch[9] -= tr.scrollX(sc);
                scratch[10] -= tr.scrollY(sc);
            }
            multiplyAt(scratch, 5, out, 0, scratch, 11); // out = local * out
            System.arraycopy(scratch, 11, out, 0, 6);
            node = tr.transforms.getInt(node, PropertyTrees.T_PARENT);
        }
    }

    private static void trsToMatrix(float tx, float ty, float sx, float sy, float rot, float[] m, int o) {
        float c = (float) Math.cos(rot), s = (float) Math.sin(rot);
        m[o] = c * sx; m[o + 1] = s * sx; m[o + 2] = -s * sy; m[o + 3] = c * sy; m[o + 4] = tx; m[o + 5] = ty;
    }

    private static void multiplyAt(float[] p, int po, float[] q, int qo, float[] out, int oo) {
        out[oo] = p[po] * q[qo] + p[po + 2] * q[qo + 1];
        out[oo + 1] = p[po + 1] * q[qo] + p[po + 3] * q[qo + 1];
        out[oo + 2] = p[po] * q[qo + 2] + p[po + 2] * q[qo + 3];
        out[oo + 3] = p[po + 1] * q[qo + 2] + p[po + 3] * q[qo + 3];
        out[oo + 4] = p[po] * q[qo + 4] + p[po + 2] * q[qo + 5] + p[po + 4];
        out[oo + 5] = p[po + 1] * q[qo + 4] + p[po + 3] * q[qo + 5] + p[po + 5];
    }

    /** 効果の不透明度（親の積）。アニメ中は {@code animTransform} スロットの時刻・曲線で補間。 */
    public static float effectiveOpacity(PropertyTrees tr, int effectSlot, float frameMs) {
        float result = 1f;
        int node = effectSlot;
        for (int depth = 0; node >= 0 && depth < MAX_DEPTH; depth++) {
            PersistentVramBuffer b = tr.effects;
            float target = b.getFloat(node, PropertyTrees.E_OPACITY);
            int timing = b.getInt(node, PropertyTrees.E_ANIM);
            float o = target;
            if (timing >= 0) {
                PersistentVramBuffer t = tr.transforms;
                float dur = t.getFloat(timing, PropertyTrees.T_DURATION);
                float e = dur <= 0f ? 1f
                        : ease(tr, t.getInt(timing, PropertyTrees.T_EASING), progress(frameMs, t.getFloat(timing, PropertyTrees.T_START_MS), dur));
                float start = b.getFloat(node, PropertyTrees.E_START_OPACITY);
                o = start + (target - start) * e;
            }
            result *= o;
            node = b.getInt(node, PropertyTrees.E_PARENT);
        }
        return result;
    }
}
