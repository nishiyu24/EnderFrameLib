package com.nishiyu.enderframelib.core.kernel.layout;

import com.nishiyu.enderframelib.core.kernel.simd.SimdBackend;
import com.nishiyu.enderframelib.core.kernel.simd.SimdBackends;
import java.util.Arrays;

/**
 * SoA の配列に対する、再帰のない一括レイアウト演算。{@link SimdBackend} 経由なので、既定の分岐なしループは
 * HotSpot C2 が自動ベクトル化し、Vector API プラグインがあればそれが使われる。
 *
 * <p>座標のスイープは、ノードが DFS 行きがけ順（親の添字 &lt; 子の添字）で並ぶ前提。</p>
 */
public final class SimdLayoutKernel {
    private final SimdBackend simd;
    private float[] zeros = new float[0];

    public SimdLayoutKernel() {
        this(SimdBackends.current());
    }

    public SimdLayoutKernel(SimdBackend simd) {
        this.simd = simd;
    }

    /**
     * 1 軸ぶんの縁の合算 {@code out = mStart + mEnd + bStart + bEnd + pStart + pEnd}。
     * {@code scratch} は作業用（長さ n 以上）で、{@code out} と同じ配列でもよい。
     */
    public void edgeSums(float[] mStart, float[] mEnd, float[] bStart, float[] bEnd,
                         float[] pStart, float[] pEnd, float[] scratch, float[] out, int n) {
        if (zeros.length < n) zeros = new float[n];
        simd.sum4(mStart, mEnd, bStart, bEnd, scratch, n);
        simd.sum4(scratch, pStart, pEnd, zeros, out, n);
    }

    /** 内容寸法を {@code [min, max]} に収める（max に +∞ を入れれば上限なし）。 */
    public void clampContent(float[] size, float[] min, float[] max, int n) {
        simd.clamp(size, min, max, n);
    }

    /** 行きがけ順の 1 本のスイープで、相対座標から世界座標を出す。ルートの親は -1。 */
    public static void accumulateWorld(int[] parent, float[] relX, float[] relY,
                                       float[] worldX, float[] worldY, int n) {
        for (int i = 0; i < n; i++) {
            int p = parent[i];
            worldX[i] = (p < 0 ? 0f : worldX[p]) + relX[i];
            worldY[i] = (p < 0 ? 0f : worldY[p]) + relY[i];
        }
    }

    /**
     * 深さごとに並べ直す（計数ソート）。{@code order[starts[d] .. starts[d+1])} が深さ d の節の添字。
     * 同じ深さの節は互いに独立なので、深さ単位で一括処理できる。{@code starts} は長さ 最大の深さ + 2 以上。
     *
     * @return 最大の深さ
     */
    public static int levelOrder(short[] depth, int n, int[] order, int[] starts) {
        int max = 0;
        for (int i = 0; i < n; i++) if (depth[i] > max) max = depth[i];
        Arrays.fill(starts, 0, max + 2, 0);
        for (int i = 0; i < n; i++) starts[depth[i] + 1]++;
        for (int d = 0; d <= max; d++) starts[d + 1] += starts[d];
        for (int i = 0; i < n; i++) order[starts[depth[i]]++] = i;
        // 上の書き込みで starts[d] が次の深さの先頭へ進んだので戻す
        for (int d = max + 1; d > 0; d--) starts[d] = starts[d - 1];
        starts[0] = 0;
        return max;
    }

    /** 深さ順の上から下へのスイープで世界座標を出す（{@link #accumulateWorld} と同じ結果。行きがけ順でなくてもよい）。 */
    public static void accumulateWorldByLevel(int[] parent, int[] order, int[] starts, int maxDepth,
                                              float[] relX, float[] relY, float[] worldX, float[] worldY) {
        for (int d = 0; d <= maxDepth; d++) {
            for (int k = starts[d]; k < starts[d + 1]; k++) {
                int i = order[k];
                int p = parent[i];
                worldX[i] = (p < 0 ? 0f : worldX[p]) + relX[i];
                worldY[i] = (p < 0 ? 0f : worldY[p]) + relY[i];
            }
        }
    }
}
