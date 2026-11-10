package com.nishiyu.enderframelib.core.paint.software;

import com.nishiyu.enderframelib.core.paint.gpu.GpuClip;
import java.util.List;
import java.util.Objects;

/**
 * 項目のクリップ（部品の列と、その外接・被覆率 1 の内側）。作った後は変わらない値で、等しさは値で決まる
 * （以前はレコード。GPU へ渡す形 {@link #gpuClips()} を 1 度だけ作って覚えるため、通常のクラスにした）。
 */
final class Clip {
    private final List<ClipPart> parts;
    private final int x0, y0, x1, y1;
    private final double ix0, iy0, ix1, iy1;
    /** {@link GpuAttempt#gpuClips} の結果の覚え。部品が多すぎて渡せないときは null で、{@link #gpuComputed} が真。 */
    private GpuClip[] gpu;
    private boolean gpuComputed;

    Clip(List<ClipPart> parts, int x0, int y0, int x1, int y1, double ix0, double iy0, double ix1, double iy1) {
        this.parts = parts;
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
        this.ix0 = ix0;
        this.iy0 = iy0;
        this.ix1 = ix1;
        this.iy1 = iy1;
    }

    List<ClipPart> parts() { return parts; }
    int x0() { return x0; }
    int y0() { return y0; }
    int x1() { return x1; }
    int y1() { return y1; }
    double ix0() { return ix0; }
    double iy0() { return iy0; }
    double ix1() { return ix1; }
    double iy1() { return iy1; }

    boolean empty() {
        return x1 <= x0 || y1 <= y0;
    }

    float coverage(double dx, double dy) {
        if (dx >= ix0 && dx <= ix1 && dy >= iy0 && dy <= iy1) return 1f; // 全部の内側（軸平行のときだけ非空）
        float c = 1f;
        for (int i = 0; i < parts.size(); i++) {
            c *= parts.get(i).coverage(dx, dy);
            if (c <= 0f) return 0f;
        }
        return c;
    }

    /**
     * GPU へ渡せるクリップの列（{@link GpuAttempt#gpuClips} と同じ。多すぎれば null）。この {@link Clip} が同じ間は同じ配列を返す
     * （図形・字形ごとに作り直さない）。返した配列は読むだけ。1 つの描画スレッドが使う前提。
     */
    GpuClip[] gpuClips() {
        if (!gpuComputed) {
            gpu = GpuAttempt.buildGpuClips(this);
            gpuComputed = true;
        }
        return gpu;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Clip c && x0 == c.x0 && y0 == c.y0 && x1 == c.x1 && y1 == c.y1
                && Double.compare(ix0, c.ix0) == 0 && Double.compare(iy0, c.iy0) == 0
                && Double.compare(ix1, c.ix1) == 0 && Double.compare(iy1, c.iy1) == 0 && Objects.equals(parts, c.parts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(parts, x0, y0, x1, y1, ix0, iy0, ix1, iy1);
    }

    @Override
    public String toString() {
        return "Clip[parts=" + parts + ", x0=" + x0 + ", y0=" + y0 + ", x1=" + x1 + ", y1=" + y1 + ", ix0=" + ix0 + ", iy0=" + iy0
                + ", ix1=" + ix1 + ", iy1=" + iy1 + "]";
    }
}
