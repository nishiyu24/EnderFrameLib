package com.nishiyu.enderframelib.core.kernel.layout;

import com.nishiyu.enderframelib.core.kernel.simd.SimdBackend;
import com.nishiyu.enderframelib.core.kernel.simd.SimdBackends;

/** 世界座標の矩形列に対する点の包含判定。最後（最前面）に当たる添字を返す。割り当て 0。 */
public final class SimdHitTester {
    private final SimdBackend simd;

    public SimdHitTester() {
        this(SimdBackends.current());
    }

    public SimdHitTester(SimdBackend simd) {
        this.simd = simd;
    }

    /** 当たる矩形が無ければ -1。 */
    public int hit(float[] x, float[] y, float[] w, float[] h, int n, float px, float py) {
        return simd.hitTestLast(x, y, w, h, n, px, py);
    }
}
