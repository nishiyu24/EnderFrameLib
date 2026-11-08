package com.nishiyu.enderframelib.core.paint.software;

/** 局所座標 {@code (lx, ly)} の事前乗算の色。0 は何も塗らない。 */
@FunctionalInterface
interface Shader {
    int shade(float lx, float ly);
}
